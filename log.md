# 开发日志

## 2026-04-28（再续）：VERIFYING_OUTPUT 永久卡死的真正根因

### 已完成
- **`SmartCraftRuntimeCoordinator.reconcileTaskExecution` 在 `link.isDone()` 时不再走 baseline-delta 校验，直接 → DONE**：
  - 用户实测反馈"已经合成完成后智能调度并没有检测到一直卡在第一步" → 之前以为只是 Bug A（产物消失）+ Bug B（applyLayerStatus 误 PAUSED）共同作用。其实**VERIFYING_OUTPUT 验证路径本身就是错的**
  - 通过翻 AE2 源码 `appeng.me.cluster.implementations.CraftingCPUCluster.injectItems`（line 416-431）确认调用链：
    1. `finalOutput.decStackSize(...)` 记账
    2. `link.injectItems(...)` 调到 bridge → bridge 返回 `items`（修过的）
    3. `completeJob()` → `markDone()` → `link.isDone() = true`
    4. **同一调用栈**继续 `return leftover` → `CraftingGridCache.injectItems` → 上层 storage handler → ME 网络 `inject`
  - 也就是说**当我们下一 tick 看到 `link.isDone() == true` 时，产物已经在 ME 里**
  - 旧逻辑：在这一 tick 才 `currentStock` 作为 baseline，再切到 VERIFYING_OUTPUT；但 baseline 已经包含了产物 → `isOutputAvailable` 检查 `(current - baseline) >= amount` 永为 false → **任务卡死 VERIFYING_OUTPUT 永远不进 DONE** → 当前 layer 永远不算完成 → 后续 layer 永远不下单 → 这就是用户看到的"卡在第一步"
  - 新逻辑：完全信任 AE2 的 `link.isDone()`（AE2 自己保证 markDone 只在 `hasRemainingTasks()==false && finalOutput<=0` 时调用），直接 `RUNNING → DONE`
  - VERIFYING_OUTPUT 状态被保留为兼容性桩：旧存档里若有任务停在此状态，下个 tick 直接放行 → DONE
- **同步移除 `SmartCraftRuntimeCoordinator` 里 unused 的 `SmartCraftStockVerifier` 字段**（`SmartCraftStockVerifier` 类暂不删，未来若需 pre-craft baseline 验证可复用）
- **新增回归测试 `completed_link_advances_to_done_in_one_tick_without_verifying_output_trap`**：模拟 RUNNING 状态下 `link.done = true`，下一 tick 必须直达 DONE + 整单 COMPLETED
- 全部 54 个测试通过（含本次新增 1 个）

### 遇到的问题 / 教训
- 之前以为 Bug A（产物消失）修了 + Bug B（PAUSED 误判）修了之后整个流程就闭环了，没意识到**VERIFYING_OUTPUT 机制本身在产线 AE2 行为下根本不可能成功**（仅在测试 mock 环境因 `currentStock` 返回 -1 才走兜底分支到 DONE）。这是典型的"测试通过 ≠ 生产正确"陷阱
- 必须 actually 看 AE2 cluster 源码才能搞清楚 `injectCraftedItems` 调用链相对于 `markDone()` 的时序。光看 `ICraftingRequester` interface JavaDoc 是看不出来的
- 单测在 `session.grid()` 返回 null 的情况下 `currentStock` 直接 -1 走 DONE 兜底，掩盖了产线行为下 baseline 永远 ≥ 0 的真实路径。后续若要加 baseline 验证，单测必须 mock 出可查询的 IStorageGrid 才能复现

### 设计决策
- **彻底放弃下游验证**，trust AE2 link 状态：因为 AE2 已经在 cluster 内部保证只有所有 task 都 done + finalOutput 已记账归零才会 markDone，任何额外验证只会引入"baseline 时序错位"这类 bug。最小改动 + 最大可信
- 保留 VERIFYING_OUTPUT 枚举值（不删 status 常量）：避免破坏 NBT 序列化；旧存档加载到此状态时一 tick passthrough 到 DONE，永不死锁
- `SmartCraftStockVerifier` 类保留但 coordinator 不再持有：将来若需要 pre-submission baseline（在 `dispatchReadyTasks` 提交前 snap）可复用此类

---

## 2026-04-28（续）：建单失败孤儿订单清理（A3 / A4）

### 已完成
- **`OpenSmartCraftPreviewPacket.Handler` A3 / A4 路径补救**：
  - **A3 场景**：`actionSource == null` —— 旧代码已 `track(order)` 但直接 return，订单永远滞留在 `orderManager` 中、没 session 也没 sync。多次失败点击会让幽灵订单越积越多
  - **A4 场景**：`session == null` —— 旧代码已 `track(order)` 且依旧执行了 `sync(...)`。客户端 OVERLAY.hasData=true，AE2 合成状态页上"查看调度"按钮**会显示**，但点了之后服务端 `syncLatestOrderForPlayer(player)` 找不到 session（因为没 register），静默丢请求，玩家面对一个看着可点实际无响应的按钮
  - 修复：两个失败分支都先 `SMART_CRAFT_ORDER_MANAGER.remove(trackedOrderId)` 撤销 track；A4 路径不再发 sync，避免客户端按钮误显示
  - 重新执行 `gradlew --offline --no-daemon spotlessApply test reobfJar` 通过

### 设计决策
- 失败时撤销 track 而非"留着等以后清理"：smart craft 创建是同步流程，失败即终态，没有任何后续逻辑会自然清理它，必须在失败点立即 `remove`
- A4 不发 sync：单纯的状态语义——"服务端没准备好处理这个订单 → 客户端就不应该看到它"。一致性比"让玩家看到错误状态"更重要，玩家已经收到聊天提示

---

## 2026-04-28：下单/调度核心 bug 修复 + 按钮 enabled 控制

### 已完成
1. **`SmartCraftRequesterBridge.injectCraftedItems` 修复（关键 bug）**：
   - 旧实现 `return null` → AE2 视为 "requester 接收了所有产物"，但 bridge 没有真实库存 → 产物**消失到虚空**
   - 新实现 `return items` → AE2 把整个产物栈"退回"，AE2 经 `Platform.poweredInsert(...)` 路由到 ME 网络存储
   - 这正是用户报的"提交后无 link 没 progress"的根因：链路完成、CPU 真在合成，但产物被吃掉、stockVerifier 永远看不到、任务卡死 `VERIFYING_OUTPUT`
2. **`applyLayerStatus` 不再过早 PAUSE（中等严重 bug）**：
   - 旧逻辑：`if (hasFailed && !hasActive) return PAUSED;` —— 只看 SUBMITTING/RUNNING/VERIFYING_OUTPUT 三个"活跃"状态
   - 触发场景：层内 `[FAILED, WAITING_CPU]`（A 规划失败、B 规划完成等 CPU），`hasFailed=true && hasActive=false` → 整单 PAUSED → `updateOrder` 早退 → B 永远不再 dispatch，CPU 即使空了也救不回来
   - 新逻辑增加 `hasPlannable`（PENDING/QUEUED）轴线，按"还有任何活儿可干就别 PAUSE"重排：`hasActive → RUNNING; hasPlannable → QUEUED; hasWaiting → WAITING_CPU; hasFailed → PAUSED; else → QUEUED`
   - PAUSE 仅在"无任何任务可推进 + 至少有一个失败"时设置，由玩家决定按"重试失败"
3. **重试失败 / 取消整单按钮 enabled 控制**：
   - `SmartCraftOverlayRenderer` 新增 `hasRetriableTasks()`（FAILED 或 CANCELLED）/ `isOrderActive()`（任意非终态任务）
   - `GuiSmartCraftStatus` 缓存 `cancelButton` / `retryButton` 引用，`refreshActionButtonStates()` 在 `initGui` 与每帧 `drawScreen` 调用，按 overlay 状态动态切 `button.enabled`：
     - 无 FAILED/CANCELLED 任务 → 重试按钮灰
     - 所有任务都终态（DONE/COMPLETED/FAILED/CANCELLED）→ 取消按钮灰
4. **新增 2 个回归测试**：
   - `SmartCraftRequesterBridgeTest.injectCraftedItems_returns_full_stack_so_ae2_routes_output_back_to_me_storage`
   - `SmartCraftRuntimeCoordinatorTest.mixed_failed_and_waiting_cpu_must_not_pause_the_order`
5. 已执行 `./gradlew.bat --offline --no-daemon spotlessApply test reobfJar`，结果 `BUILD SUCCESSFUL`，全部 53 个测试通过

### 遇到的问题
- `injectCraftedItems` 这个 bug 隐蔽得离谱：从代码层面看 link 提交、submit 成功、reconcile 看到 link.isDone()，一切"正常"，但产物根本没到 ME 网络。需要熟悉 AE2 `ICraftingRequester` 契约：返回值是"requester 拒收的部分"，AE2 把这部分通过 `Platform.poweredInsert(...)` 投回 ME 存储；`null` 等价"全收"
- `applyLayerStatus` 的 PAUSED 误判，原 case 检查 `hasFailed && !hasActive` 漏掉了 PENDING/QUEUED 的"未启动但仍能推进"语义；与 `dispatchReadyTasks` 配合时本来 PENDING 应该秒变 SUBMITTING，但当 `dispatchReadyTasks` 处于 BRANCH A（`plannedJob != null && link == null` + 无 idle CPU）时 task 会停在 WAITING_CPU 而非 SUBMITTING，整层就有可能 `[FAILED, WAITING_CPU]` 这种"无 active 但仍可推进"的组合
- 测试 setup 用 dynamic Proxy 模拟 AE2 接口，规划阶段返回 `simulation=true` 的 fake job 来触发 FAILED 路径，busy CPU 触发 WAITING_CPU 路径；用 `task.taskKey()` 在 lambda 里区分两条路径，无须额外 mock 框架

### 设计决策
- `injectCraftedItems` 永远 `return items`：bridge 不区分 SIMULATE / MODULATE，因为我们从不"接收"产物，全部交还 AE2 路由到 ME 网络
- 按钮 enabled 状态在每帧刷新而非订阅 sync 事件：实现简单、CPU 消耗可忽略；GuiSmartCraftStatus 本来就每帧 syncScheduleButtons，多两个 if 比较没影响
- PAUSED 语义收紧到"没活儿可干 + 有失败" —— 避免单 CPU 玩家碰到一次失败就被锁死，但保留 "全部任务终态包括失败" 时的视觉提示让玩家知道该按重试

---

## 2026-04-27：tooltip 重构 + AE2 合成状态页注入智能调度补充提示

### 已完成
- `SmartCraftOverlayRenderer.buildTaskTooltip(...)` 重构：
  - 三段式结构：①原版 `ItemStack#getTooltip` 行（或 requestKeyId 兜底）；②智能调度子标题（金色 `[智能调度]`）；③共享的状态明细行
  - 抽出 `appendSmartCraftLines(lines, task)` 把 `数量 / 状态 / 当前层 / 拆分 / CPU / 阻塞原因` 统一拼装，被 `buildTaskTooltip` 与新增的 `buildAe2SupplementHintLines` 共用，避免重复
  - 全部使用 `EnumChatFormatting`：title GRAY、值 WHITE、状态按 `chatColorForStatus(...)` 着色（RUNNING=AQUA、PENDING/QUEUED/WAITING_CPU=YELLOW、PAUSED=GOLD、DONE/COMPLETED=GREEN、FAILED=RED、CANCELLED=DARK_GRAY），CPU=AQUA、阻塞原因=GOLD
- `SmartCraftOverlayRenderer` 新增 3 个 public 接口供 AE2 合成状态页 hook 使用：
  - `findMatchingTask(ItemStack)` — 按 `Item + Damage + NBT (areItemStackTagsEqual)` 三因素匹配，与 AE2 `IAEItemStack.equals` 行为一致
  - `buildAe2SupplementHintLines(TaskView)` — 不再重复 ItemStack 的 vanilla tooltip，只输出"智能调度"子标题 + 共享明细行
  - `drawSupplementaryTooltip(lines, x, y)` — 内部转发到原 `drawTooltipLines`，保持一致的视觉风格
- `SmartCraftConfirmGuiEventHandler.onDrawScreen(...)` 注入逻辑：
  - 在 `screenKind == CRAFTING_STATUS` 且 overlay 有数据时调用 `drawSmartCraftSupplementOnAe2Status(...)`
  - 实现：若 GUI instanceof `appeng.client.gui.implementations.GuiCraftingCPU`，则读 `getHoveredStack()` 拿当前悬停物品；用 `OVERLAY.findMatchingTask` 匹配；命中则在 `(mouseX, mouseY + 60)` 渲染补充 tooltip——偏移 60px 是为了避开 AE2 native 的 4 行 shift-tooltip
  - 通过 `DrawScreenEvent.Post` 时机保证我们的 tooltip 渲染在 AE2 之后，永远盖在最上层
- 新增 lang key `gui.ae2intelligentscheduling.smartCraftHintHeader`：
  - zh_CN：`【智能调度】`
  - en_US：`[Smart Craft]`
- 已执行 `./gradlew.bat --offline --no-daemon spotlessApply test reobfJar`，结果 `BUILD SUCCESSFUL`，全部测试通过

### 遇到的问题
- AE2 cell tooltip 在不按 shift 时是 AE2 自己拼的字符串（不走 `ItemStack#getTooltip`），所以 Forge `ItemTooltipEvent` 无法注入；只能通过 `DrawScreenEvent.Post` + 反射 `getHoveredStack()` 这条路
- AE2 `GuiCraftingCPU.hoveredStack` 在格子有 hover 时被填，但 `GuiCraftingCPU.hoveredAEStack` 仅当 `activeStack != null`（在合成中）时才填——选 `getHoveredStack()` 覆盖更广（包括 PENDING / WAITING / DONE 的格子）
- AE2 native tooltip 在不按 shift 时只有 1 行（物品显示名），按 shift 会展开 NBT/合成状态多行；用固定 `+60px` 垂直偏移作为粗略让位，避免在多数情况下重叠

### 设计决策
- 不使用 mixin 修改 AE2 `drawFG`：保持 addon 不破坏 AE2 行为，只追加显示信息；rollback 时只需移除事件处理
- 所有视觉 tooltip 复用同一个 `drawTooltipLines` / `drawSupplementaryTooltip` 渲染路径，保证视觉一致；AE2 渲染什么样我们叠加的就什么样
- `buildAe2SupplementHintLines` 不重复 vanilla tooltip：AE2 已经画过物品名 + Stored/Crafting/Scheduled 数量，我们只补"层级 / 拆分 / CPU 名 / 阻塞原因"等 AE2 看不到的信息

---

## 2026-04-27：格子区视觉对齐 AE2 原版 `GuiCraftingCPU`

### 已完成
- 研读 `GuiCraftingCPU.java` 关键参数，确认布局规格：6 行 × 3 列，`SECTION_LENGTH=67` / `SECTION_HEIGHT=23` / `XO=9` / `YO=22`，与我们当前已对齐；滚动条 `x=218 / y=19 / h=137 / w=12`；图标 `cellX + SECTION_LENGTH - 19`；文字用 `GL11.glScaled(0.5, 0.5, 0.5)` 半字号居中
- `SmartCraftOverlayRenderer.drawTaskGrid(...)` 重写为 AE2 风格：
  - 复用 AE2 本地化 `GuiText.Stored` / `GuiText.Crafting` / `GuiText.Scheduled`，让单元格文字读起来与原版 CPU 状态完全一致
  - 用 `GL11.glPushMatrix() + glScaled(0.5,0.5,0.5)` + 双倍坐标渲染半字号 `<状态>: <数量>`，水平居中于图标左侧
  - 物品图标位置保持 `cellX + SECTION_LENGTH - 19`（与原版一致）
  - 背景着色范围扩为 `cellY-3` 到 `cellY + offY - 1`，与原版相同（之前是 `cellY-1` 到 `cellY+offY-2`）
  - 删除原本右下角的 status dot（原版没有）
- `cellBg(...)` 改用 AE2 原版色板：
  - RUNNING / SUBMITTING / VERIFYING_OUTPUT → `GuiColors.CraftingCPUActive`
  - PENDING / QUEUED / WAITING_CPU / PAUSED → `GuiColors.CraftingCPUInactive`
  - FAILED 仍保留半透红 `0x55FF3030` 让玩家一眼识别
- 新增 `aeStatusLabel(TaskView)` / `aeStatusColor(SmartCraftStatus)` 两个映射，把我们的状态机映射到 AE2 的"已存 / 合成中 / 计划中"语义和 `CraftingCPUStored / CraftingCPUAmount / CraftingCPUScheduled` 颜色
- `GuiSmartCraftStatus` 顶部格子滚动条坐标改为与 AE2 一致：
  - 新增常量 `GRID_SCROLLBAR_X = 218`、`GRID_SCROLLBAR_TOP_OFFSET = 19`、`GRID_SCROLLBAR_HEIGHT = 137`、`GRID_SCROLLBAR_WIDTH = 12`
  - `gridScrollbarX/Top/Bottom()` 改用上述常量
  - `drawScrollbar(...)` / `tryStartScrollbarDrag(...)` 接受 `width` 参数，以便格子滚动条 12px、调度滚动条 6px 同时渲染
- 调度区滚动条保留原本 `SCROLLBAR_WIDTH=6` 的窄风格，与"非 AE2 区域"视觉区分
- 已执行 `./gradlew.bat --offline --no-daemon spotlessApply test reobfJar`，结果 `BUILD SUCCESSFUL`，全部测试通过

### 遇到的问题
- `multi_edit` 一次性改太多块导致 `drawGridScrollbar` / `drawScheduleScrollbar` / `drawScrollbar` / `tryStartScrollbarDrag` 互相串到一起；通过定位 corruption 区段后用单次 `edit` 一次性重建四个方法解决
- AE2 文字渲染细节：必须先 `GL11.glPushMatrix()`，scale 0.5x，然后所有 `drawString` 坐标都要 `* 2`；如果忘记乘 2 会让文字渲染到画布之外或字号错乱
- AE2 单元格内文字水平居中算法：`textCenterScaled = (cellX + (SECTION_LENGTH - 19) / 2) * 2`，对应居中于"格子左侧除去 19px 图标空间"的中点，再用 `textW / 2`（在 0.5x scale 下宽度本身就半了）做偏移

### 设计决策
- 不复制 AE2 多行渲染（Stored / Crafting / Scheduled 三行同时显示）：我们的单格只有一个 task，单行更清晰；AE2 那种多行只在物品同时有库存 + 合成中 + 计划中时才出现
- 复用 AE2 的 `GuiText` / `GuiColors` 而不是新建 lang key：玩家在 AE2 里看到"合成中"/"计划中"，在我们 UI 里看到一模一样的文字，零认知成本
- 调度滚动条不跟着对齐 AE2：那是我们的自创区域，AE2 没有相对应位置；保留 6px 窄风格作为视觉区分

---

## 2026-04-27：调度核心重构（取消 / 重试 / 失败 / CPU 调度）

### 已完成
- `SmartCraftOrderManager.retryFailedTasks(...)`：
  - 引入 `hasRetriableTasks(...)`：仅当存在 `FAILED` 或 `CANCELLED` 叶子任务时才触发重试，避免空 sync
  - 新增 `retryTerminalFailures(...)`：把 `FAILED` 与 `CANCELLED` 任务一并复位为 `PENDING`、清除 `blockingReason`，订单状态转 `QUEUED`；旧 `updateAllTasks` 只复活 `FAILED`，导致取消的订单永远无法重试
- `SmartCraftRuntimeCoordinator.cancel(...)`：
  - 不再从 `sessions` 中移除 session；session 生命周期 ≡ order 在 manager 中的生命周期，让"取消后再点重试"能拿到原 grid / requesterBridge 句柄继续推进
- `SmartCraftRuntimeCoordinator.tick(...)`：
  - 删除"`updated.isFinished()` 后立即 `cancelAll()` 并移除 session"分支
  - 仅当 `orderManager.get(orderId)` 缺席（订单被外部移除）时才 `cancelAll()` + 移除 session，避免 `CANCELLED / COMPLETED / PAUSED` 终态被误清
- `SmartCraftRuntimeCoordinator.retryFailed(...)`：
  - 重试成功后遍历所有 layer 中刚被复位为 `PENDING` 的任务，调 `session.clearExecution(task)` 清掉 stale `TaskExecution / stockBaseline`，下一次 `dispatchReadyTasks` 才会重新规划
- `SmartCraftRuntimeCoordinator.reconcileTaskExecution(...)`：
  - 入口先判 `task.isTerminal()`：终态任务直接清 execution 后返回，避免 `orderManager.cancel(...)` 刚把任务翻成 `CANCELLED`，下一 tick 又被 `link.isCanceled()` 改写成 `FAILED`
  - `VERIFYING_OUTPUT → DONE` 路径补 `session.clearExecution(task)`，修复 `stockBaseline` 残留泄漏
- `SmartCraftRuntimeCoordinator.dispatchReadyTasks(...)`：
  - 规划阶段不再 `takeNextCpu`：CPU 仅在 submit（已 plannedJob、待 craftingLink）阶段消耗
  - 修复 "1 个空闲 CPU 时只允许 1 个任务规划，其它都伪 WAITING_CPU" 的 starvation；规划吞吐由 AE2 自身的 `beginCraftingJob` 上限决定
- 新增 `SmartCraftRuntimeCoordinatorTest` 三个回归用例：
  - `cancel_then_retry_revives_cancelled_tasks_and_resumes_dispatch`：取消 → 重试 → 下一 tick 重新规划，订单不再卡死
  - `terminal_tasks_are_not_overwritten_by_canceled_link_state`：模拟取消后下一 tick `link.isCanceled() == true`，断言任务仍为 `CANCELLED` 而非被覆写为 `FAILED`
  - `planning_starts_for_all_tasks_even_when_only_one_cpu_is_idle`：3 任务 + 1 空闲 CPU，断言所有 3 任务都进入 `SUBMITTING`，证明并行规划不再被 CPU 数限制
- 现有 `SmartCraftSchedulerTest.waits_when_no_idle_cpu_is_available`：契约从"规划被 CPU 阻断 → 一 tick 即 WAITING_CPU"改为"规划完成后 submit 受阻 → 两 tick 后 WAITING_CPU"，更新断言路径
- 已执行 `./gradlew.bat --offline --no-daemon spotlessApply test reobfJar`，结果 `BUILD SUCCESSFUL`，全部测试套件通过

### 遇到的问题
- "取消 → 重试卡死"根因实际是 **session 生命周期**与 **task `isTerminal()` 早退保护** 两个 bug 复合：
  1. cancel 时移除 session 让重试拿不到执行环境
  2. 即使保住 session，旧 reconcile 又会被 `link.isCanceled()` 覆写终态
  必须两处同时修复，缺一不可
- 删除规划阶段 CPU 预占后，规划数量上限完全交给 AE2 网络的 `beginCraftingJob`；如果 AE2 内部限流仍存在排队，理论上会让所有 PENDING 同帧进 SUBMITTING，但 AE2 的 `Future` 会按它的内部线程池逐个处理，这是预期行为
- 旧测试 `waits_when_no_idle_cpu_is_available` 一直绿就是因为它"测了错误的契约"——这恰是用户报告"调度卡顿"的缩影；修复时不得不更新该测试以反映新契约

### 设计决策
- 不引入新订单态（如 `RETRYING / CANCELLING`）：保持 `QUEUED → RUNNING / WAITING_CPU → PAUSED / CANCELLED / COMPLETED` 五态机；过渡态由任务态 + dispatch 自然产生
- 取消时仍调用 `session.cancelAll()` 来传播 `link.cancel() / future.cancel(true)`，但 task 状态由 `orderManager.cancel(...)` 直接翻为 `CANCELLED`；reconcile 不再依赖 link.isCanceled() 派生终态——这种"双写"是必须的，因为 AE2 link.cancel() 不保证立刻反映 isCanceled()
- 重试同时复活 FAILED 和 CANCELLED：让玩家从 PAUSED 或 CANCELLED 都能恢复，单一入口降低 UX 复杂度

---

## 2026-04-27：格子区/调度区可见滚动条 + 区域比例重排

### 已完成
- `SmartCraftStatusLayout` 重排：
  - 新增 `GRID_ROWS = 6` / `GRID_COLS = 3`：顶部格子区从 5 行扩到 6 行，多出一整行 23px 的视觉空间
  - 新增 `MAX_VISIBLE_TASK_ROWS = 4`：调度列表可见任务行硬上限，加上 1 行总览共 5 行 60px，避免列表区无限增长
  - `TOP_SECTION_HEIGHT` 由 `165` 抬高到 `188`、`INFO_BAR_TOP` 由 `138` 抬高到 `161`，跟随 6 行格子的尾部
  - `visibleScheduleRows(...)` 现在用 `MAX_VISIBLE_TASK_ROWS + 1` 做硬封顶，再受屏幕可用空间约束
  - 新增 `totalGridRows` / `maxGridScroll` / `clampGridScroll` 三个工具方法，供格子区滚动条使用
- `SmartCraftOverlayRenderer`：
  - `drawTaskGrid` 把硬编码的 `5/3` 全部替换为 `SmartCraftStatusLayout.GRID_ROWS/GRID_COLS`，配合扩大后的 6×3 区域绘制
  - `drawCpuDetailGrid` 暗化层和进度条宽度都改用 `GRID_COLS * (SECTION_LENGTH + 1)`；进度条下移到 `cellY + 28..34`，新增 `数量(剩余) / 耗时` 两行信息行
  - `drawTaskDetailGrid` 重排：图标 + 显示名右上、状态行紧贴其下、加 `0x55FFFFFF` 分隔线，再展开层级 / 拆分 / 数量 / CPU 行；阻塞原因走 `fr.listFormattedStringToWidth(...)` 自动换行，超出网格高度自动截断不溢出
  - `scroll(...)` 现在统一通过 `SmartCraftStatusLayout.clampGridScroll(...)` 计算，避免与新格子尺寸不一致；新增 `getGridScroll() / setGridScroll(...)` 供滚动条拖拽时回写偏移
- `GuiSmartCraftStatus` 增加可见滚动条与拖拽：
  - 新增枚举 `DragMode { NONE, GRID_THUMB, SCHEDULE_THUMB }` 与字段 `dragMode / dragOffsetWithinThumb`，支持滚动条点击与按住拖拽
  - 顶部格子区滚动条：x 在 `guiLeft + GUI_WIDTH - 10`，y 跨 6 行格子高度；调度区滚动条：x 同列、y 从 `SCHEDULE_BUTTON_TOP` 到 `guiHeight - ACTION_AREA_HEIGHT - 2`，互不重叠
  - `drawScrollbar(...)` 通用辅助：黑灰轨道 + 浅灰滑块，鼠标悬停滑块亮显，无可滚动内容时只画轨道；滑块高度 = `max(8, trackHeight * visible / total)` 比例缩放
  - `mouseClicked` / `mouseClickMove` / `mouseMovedOrUp` 重写：点击滚动条进入 `GRID_THUMB` 或 `SCHEDULE_THUMB` 拖拽模式，滑块内点击保留 grab 偏移，轨道空位点击则跳到点击位置
  - `handleMouseInput` 滚轮分流：光标在格子区滚格子滚动偏移、在调度区滚调度列表
  - `isMouseInGridArea(...)` 新增以判定滚轮区域；`isMouseInScheduleList` 保留
- 测试：
  - `SmartCraftStatusLayoutTest` 新增 4 个用例：
    - `visible_schedule_rows_is_capped_so_list_area_stays_compact` 验证大屏下调度可见行 ≤ `MAX_VISIBLE_TASK_ROWS + 1`
    - `grid_scroll_helpers_clamp_to_visible_task_grid` 验证 27 个任务时 `totalGridRows = 9`、`maxGridScroll = 3`、上下越界都正确夹紧
    - `grid_fits_all_tasks_when_total_rows_below_grid_capacity` 验证 18 个任务和 0 任务时无需滚动
  - 已执行 `./gradlew.bat --offline --no-daemon spotlessApply test reobfJar`，结果 `BUILD SUCCESSFUL`，39 个测试全部通过

### 遇到的问题
- 老 `scroll(...)` 内部把可视格子数硬编码为 5，改格子尺寸后会与新的 6 行不一致，必须统一走 `SmartCraftStatusLayout.clampGridScroll`
- 调度滚动条和格子滚动条同列布置在 `guiLeft + GUI_WIDTH - 10`，需要分别用各自区域的上下沿（`SCHEDULE_BUTTON_TOP / ACTION_AREA_HEIGHT`、`GRID_Y_OFFSET / GRID_ROWS`）才不会越界、不会和按钮覆盖
- 任务详情面板原本固定到 `cellY + 56` 处放 `阻塞原因`，6 行扩展后空间富裕，但长 reason 仍可能溢出；改用 `fr.listFormattedStringToWidth` 自动换行，再用 `cellY + gridHeight - 4` 做硬截断
- 拖拽时若直接根据 `mouseY` 计算 thumbY，会让滑块在第一帧瞬间跳到光标处；引入 `dragOffsetWithinThumb` 记录点击瞬间相对滑块的偏移，避免“跳跃感”

### 设计决策
- 仍把滑块绘制在 `super.drawScreen(...)` 之后，因此可以盖在按钮和 overlay 之上，永远可见
- 滑块高度按 `visible / total` 比例缩放，最小 8px，让玩家直观感知“当前查看的占比”
- 网格滚轮速度保持 1 行 / tick，方便对齐 6 行栅格；调度滚轮也保持 1 行 / tick，但调度行更窄

---

## 2026-04-27：修复调度清单分层任务按钮不可点击

### 已完成
- 根本原因：`SmartCraftScheduleButtonLayout` 把任务按钮的 `enabled` 与 `task.assignedCpuName()` 绑定；当任务尚未分配 AE2 CPU（队列中、等待中），按钮直接变灰不可点击；即便强制点也只走到 `selectTaskCpu`，CPU 名为空时直接 `return` 不做任何事
- `SmartCraftScheduleButtonLayout.buttons(...)`：任务按钮的 `enabled` 改为常量 `true`，注释解释为何不再以 CPU 分配状态做门控
- `SmartCraftOverlayRenderer`：
  - 新增字段 `selectedTaskIndex`（`-1` 表示未选中任务）
  - 新增 `selectTask(int)`：有 CPU 时复用 `selectCpu(...)` 走 CPU 详情；无 CPU 时清空 `selectedCpuName / cpuDetail`，进入“仅任务详情”模式
  - 新增 `drawTaskDetailGrid(...)`：在上方网格区显示选中任务的图标、显示名 / requestKeyId、`状态`（按状态着色）、`当前层 / 拆分`、`数量`、`CPU`（若有）、`阻塞原因`（若有）
  - `draw(...)` 派发顺序改为：`selectedCpuName != null` → CPU 详情；否则若 `selectedTaskIndex >= 0` → 任务详情；否则总调度网格
  - `selectOverview()` / `clear()` 同时清掉 `selectedTaskIndex`，避免回到总调度后还有遗留选中
- `GuiSmartCraftStatus.actionPerformed`：`TASK_BUTTON_BASE + i` 分支改为调用 `OVERLAY.selectTask(i)`；移除原本的 `selectTaskCpu(...)` 私有方法
- 测试：`SmartCraftScheduleButtonLayoutTest.task_button_is_disabled_until_cpu_is_assigned` 用例改为 `task_button_is_always_enabled_so_player_can_open_task_detail`，覆盖 “有 CPU / 无 CPU” 两种情况均保持可点击
- 已执行 `./gradlew.bat --offline --no-daemon spotlessApply test reobfJar`，结果 `BUILD SUCCESSFUL`，所有测试套件通过

### 遇到的问题
- 旧 UX 假设任务点击只服务于查看 AE2 CPU 详情，没考虑队列态 / 等待态任务也需要被玩家点开查看进度和阻塞原因
- 任务详情面板沿用 CPU 详情同一块网格区域，需要避免与 CPU 详情同时被绘制；通过 `selectedCpuName` 的优先级排查解决

### 设计决策
- 优先 CPU 详情：当一个任务已经被绑定到某个 AE2 CPU，CPU 详情比任务详情提供的进度信息更具体（开始数量 / 剩余数量 / 进度条 / 已用时间），所以保留原 `selectCpu` 路径
- 任务详情面板复用 `TaskView` 已经携带的字段，避免新增网络包和服务端协议

---

## 2026-04-27：实心面板贴底 + AE2 三个挂载页查看调度按钮重排

### 已完成
- 根据截图 `2026-04-27_22.18.10.png`，将状态页实心面板继续向下延伸到底部按钮上方，覆盖 AE2 cell 纹理在调度列表与取消/重试按钮之间残留的 3 行空格子：
  - `GuiSmartCraftStatus` 新增常量 `SOLID_PANEL_BOTTOM_GAP = 28`，`drawSolidPanelOverlay` 改用该常量决定面板下沿
  - `ACTION_AREA_HEIGHT` 与底部段对齐保留不变，仅实心面板的视觉边界进一步压低
- 将 AE2 入口按钮宽度从 `68` 收窄至 `52`，与 AE2 自身 `Cancel` / `Start` 按钮宽度对齐：
  - `SmartCraftConfirmButtonLayout.BUTTON_WIDTH` 改为 `52`
  - 新增 `BUTTON_GAP = 4`、`STATUS_HEADER_TOP = 4`、`STATUS_HEADER_HEIGHT = 14` 等常量
- 将 `查看调度` 按钮拆分为按屏幕种类的三套位置，避免与 AE2 原按钮挤占空间：
  - `viewStatusOnConfirmPosition`：AE2 合成确认页中段空白行（`ySize - 47`），紧贴 `智能合成` 按钮左侧 4px，确保不与底部 `Cancel / StartWithFollow / Start` 行重叠
  - `viewStatusOnTerminalPosition`：终端 `ySize - 25` 右下，宽度 52
  - `viewStatusOnCraftingStatusPosition`：AE2 `GuiCraftingStatus` 顶部 `状态` 标题右侧 (`guiLeft + 60`, `guiTop + 4`)，宽度 52、高度 14，避开 AE2 顶部搜索框（`xSize - 101`）和上下找寻按钮
  - 旧 `viewStatusPosition` 标记 `@Deprecated`，转发到 `viewStatusOnTerminalPosition`，避免破坏外部调用
- 在 AE2 合成 CPU 状态页 (`GuiCraftingStatus`) 也注入 `查看调度` 按钮：
  - `SmartCraftScreenFlow` 新增 `ScreenKind.CRAFTING_STATUS`，`kindOf` 识别 `GuiCraftingStatus`
  - `shouldShowViewStatusButton` / `shouldRequestStatus` 现在覆盖 `CRAFTING_STATUS`
  - `SmartCraftConfirmGuiEventHandler.onGuiInit` 在进入 `GuiCraftingStatus` 时同步注入按钮，`onDrawScreen` 同步坐标，`onGuiClosed` 也保留 overlay 数据
  - `viewStatusPositionFor(...)` 内部分发：按 `ScreenKind` 选择对应位置
- 新增 / 调整测试：
  - `SmartCraftConfirmButtonLayoutTest`：用例覆盖 `智能合成` 新位置、`查看调度` 在确认页与 `智能合成` 同行紧邻、不与 AE2 `Start` 行重叠、终端右下 52 宽、CraftingStatus 不越过 AE2 搜索框范围、按钮不超过 AE2 顶部条带高度
  - `SmartCraftScreenFlowTest`：用例覆盖 `CRAFTING_STATUS` 在有/无订单数据时是否显示 `查看调度` 按钮，以及按钮路由
- 已执行 `./gradlew.bat --offline --no-daemon spotlessApply test ...` 运行 4 个 GUI 套件，结果 `BUILD SUCCESSFUL`，23 个 GUI 测试全部通过
- 已执行 `./gradlew.bat --offline --no-daemon reobfJar`，结果 `BUILD SUCCESSFUL`，产物位于 `build/libs/ae2intelligentscheduling-0.1.0-dev.jar`

### 遇到的问题
- 旧 `viewStatusPosition` 在 AE2 合成确认页与 `Start` 按钮（`xSize - 78`）水平重叠 48px，并且与新加 `智能合成` 按钮位于同一右上区域，造成挤占
- AE2 `GuiCraftingStatus` 顶部已被搜索框 (`xSize - 101`) 与 `↑ / ↓` 找寻按钮 (`xSize - 48 ~ -16`) 占用，按钮必须严格落在中段 `guiLeft + 60` 起、宽 ≤ `xSize - 101 - 60` 区域，否则会盖住搜索控件
- 实心面板原下沿与 `ACTION_AREA_HEIGHT(51)` 绑定，会让 AE2 cell 纹理在调度列表与底部按钮之间漏出 3 行空格子；解耦后由 `SOLID_PANEL_BOTTOM_GAP = 28` 决定下沿，调度列表行高与底部段对齐逻辑保持不变

### 设计决策
- 共用同一个 `查看调度` 按钮 ID (`VIEW_STATUS_BUTTON_ID`)，按屏幕种类切换坐标，避免在按钮列表中产生 ID 冲突或重复实例
- AE2 `GuiCraftingStatus` 上的按钮高度选 `14` 而非标准 `20`：与 AE2 顶部搜索框 (`12px`) 对齐，避免突出到 CPU 列表区
- 维持 `viewStatusPosition` 旧入口为 `@Deprecated` 转发版本，避免后续如有反射或脚本依赖旧 API 时直接破坏

---

## 2026-04-27：智能合成状态页布局收紧与调度区背景实心化

### 已完成
- 根据截图 `2026-04-27_21.43.09.png`，将顶部信息行（量级 / 状态 / 任务数 / 数量）下移贴近调度区域：
  - `SmartCraftStatusLayout.TOP_SECTION_HEIGHT` 从 `206` 调整为 `165`
  - 新增 `SmartCraftStatusLayout.INFO_BAR_TOP = 138`，`SmartCraftOverlayRenderer` 直接使用该常量定位面板，移除原 `PANEL_TOP_OFFSET = -91`
  - 调整后信息行紧贴 5×3 任务网格底部（`y = 137`），调度标题位于 `y = 173`，避免大段空白
- 将调度列表背景从 AE2 cell 纹理切片改为实心面板：
  - `GuiSmartCraftStatus` 新增 `drawSolidPanelOverlay`，在 AE2 长背景之上覆盖 `0xFFC6C6C6` 实心矩形并加 `0xFF8B8B8B` 边框线
  - 覆盖范围从信息行起到底部按钮区上沿，让“调度 / 当前层 N: …” 等文字在统一的实心背景上呈现，去除原本格子条纹观感
- 收紧 `SmartCraftStatusLayout.ACTION_AREA_HEIGHT` 从 `34` 提升到 `51`：
  - 与 AE2 `craftingcpu.png` 底部 51px 段对齐，确保调度列表最后一行不会越过 AE2 底部边框
  - 顶部按钮 `cancel/retry` 仍位于 `guiTop + guiHeight - 25`，落在底部段内不变
- 已执行 `./gradlew.bat --offline --no-daemon spotlessApply test --tests SmartCraftStatusLayoutTest --tests SmartCraftScheduleButtonLayoutTest`，结果 `BUILD SUCCESSFUL`

### 遇到的问题
- 旧布局把信息行画在主网格内部（`panelY = guiTop + 115`，落在第 5 行任务格子位置），而调度区起点在 `y = 226`，造成大段空白同时信息行远离调度区
- AE2 `craftingcpu.png` 中段是带分隔线的 CPU 行纹理，作为调度列表背景时 2px 间隙直接漏出格子条纹
- 旧 `ACTION_AREA_HEIGHT = 34` 小于底部段 51px，调度列表最后一行实际越过底部段顶端，与 AE2 底部边框重叠

### 设计决策
- 信息行不再用相对 `ySize` 的负偏移定位，改为绝对 `INFO_BAR_TOP` 常量，方便与上方任务网格、下方调度标题对齐
- 选择保留 AE2 长背景再叠加实心矩形，而不是直接替换底层背景；这样顶部任务网格仍保持 AE2 cell 风格，仅调度列表区域为实心
- 实心面板色用 `0xFFC6C6C6` 与 AE2 主面板内饰色一致，避免在浅色 AE2 边框中显得突兀

---

## 2026-04-27：将智能合成状态页改为 AE2 风格长 UI

### 已完成
- 根据截图 `2026-04-27_21.17.24.png` 确认旧问题：调度清单被绘制到主 GUI 外部，并且上方单元格没有渲染任务物品图标
- 参考本地 AE2 源码 `GuiCraftingStatus` / `GuiCraftingCPU` 的长 UI 思路，改为使用 AE2 `guis/craftingcpu.png` 分段绘制长背景
- 新增 `SmartCraftStatusLayout`，统一计算状态页高度、清单可见行数、滚动范围和清单切片
- `GuiSmartCraftStatus` 现在会把调度清单绘制在同一个 GUI 内部，位于上方单元格区域下方、底部取消/重试按钮上方
- 调度清单支持鼠标滚轮滚动，滚轮只在清单区域内生效
- `SmartCraftScheduleButtonLayout` 改为支持可见任务切片，同时保留任务按钮 ID 与原任务索引对应
- `SmartCraftOverlayRenderer` 的总调度单元格现在会渲染 `TaskView.itemStack()` 物品图标，并在单元格内显示压缩数量
- 新增 `SmartCraftStatusLayoutTest` 覆盖长 UI 高度、滚动边界和可见任务行计算

### 遇到的问题
- 旧实现沿用 `guiTop + GUI_HEIGHT + 4` 绘制清单，导致清单不属于 GUI 背景，和世界画面、快捷栏发生重叠
- 固定高度 `206` 的状态页无法容纳调度清单，必须把窗口本身变长，而不是把内容画到窗口外
- 极小屏幕下如果强行限制到屏幕边距内，会出现清单起点低于底部按钮区域的问题；现在优先保证 GUI 内部布局不重叠

### 验证
- 已执行 `./gradlew.bat --offline --no-daemon test --tests com.homeftw.ae2intelligentscheduling.client.gui.SmartCraftStatusLayoutTest --tests com.homeftw.ae2intelligentscheduling.client.gui.SmartCraftScheduleButtonLayoutTest --tests com.homeftw.ae2intelligentscheduling.client.gui.SmartCraftScreenFlowTest`，结果 `BUILD SUCCESSFUL`
- 已执行 `./gradlew.bat --offline --no-daemon test`，结果 `BUILD SUCCESSFUL`
- 已执行 `./gradlew.bat --offline --no-daemon reobfJar`，结果 `BUILD SUCCESSFUL`

### 设计决策
- 采用 AE2 长 UI 风格：窗口本体增长，背景分段绘制，调度清单保持在同一个 GUI 内
- 清单滚动不新增服务端协议，只在客户端显示层切换可见任务按钮
- 上方单元格继续承担总调度/CPU 详情切换，底部清单只作为导航入口

---

## 2026-04-27：为智能合成状态页加入调度清单按钮

### 已完成
- 在 `GuiSmartCraftStatus` 的单元格区域下方绘制调度清单标签与按钮
- 新增 `SmartCraftScheduleButtonLayout`，集中计算调度清单按钮的 ID、位置、标题和可点击状态
- 调度清单最上方加入 `总调度` 按钮，点击后调用 `SmartCraftOverlayRenderer.selectOverview()`，上方单元格区域回到总调度视图
- 每个任务分片加入一个按钮，按钮 ID 与任务索引一一对应
- 点击已分配 AE2 CPU 的任务分片按钮时，调用 `SmartCraftOverlayRenderer.selectCpu(cpuName)`，通过现有 `RequestCpuDetailPacket` 请求该 CPU 的合成详情，并在上方单元格区域显示
- 未分配 CPU 的任务分片按钮保持可见但禁用，避免玩家误以为可以查看尚不存在的 AE2 CPU 详情
- 为 `overview` 补充 `zh_CN.lang` 与 `en_US.lang` 语言键
- 新增 `SmartCraftScheduleButtonLayoutTest` 覆盖总调度按钮、任务按钮 ID 映射和未分配 CPU 时禁用按钮

### 遇到的问题
- 旧版 `SmartCraftOverlayRenderer.drawScheduleList()` 只绘制清单文字，不负责按钮；本轮将按钮同步职责放在 `GuiSmartCraftStatus`
- 状态同步会更新 `OVERLAY` 中的任务数据，因此状态页每次绘制都会重新同步清单按钮，避免任务数量变化后残留旧按钮

### 验证
- 已执行 `./gradlew.bat --offline --no-daemon test --tests com.homeftw.ae2intelligentscheduling.client.gui.SmartCraftScheduleButtonLayoutTest --tests com.homeftw.ae2intelligentscheduling.client.gui.SmartCraftScreenFlowTest`，结果 `BUILD SUCCESSFUL`
- 已执行 `./gradlew.bat --offline --no-daemon test`，结果 `BUILD SUCCESSFUL`
- 已执行 `./gradlew.bat --offline --no-daemon reobfJar`，结果 `BUILD SUCCESSFUL`

### 设计决策
- 调度清单按钮只改变状态页上方单元格区域的显示模式，不打开新的 UI
- 顶部 `总调度` 是总览入口；任务分片按钮是 CPU 详情入口
- 复用现有 `RequestCpuDetailPacket` / `SyncCpuDetailPacket` 链路，不新增网络协议

---

## 2026-04-27：修复智能合成状态页 ESC 后反复弹回

### 已完成
- 确认根因是 `ClientProxy.openSmartCraftStatus()` 把每个 `SyncSmartCraftOrderPacket` 都当成打开状态页的命令，导致玩家按 ESC 关闭后下一次同步又自动弹回
- 修改 `SmartCraftScreenFlow`，将“普通订单同步”和“玩家主动请求打开状态页”拆成两个条件
- `SyncSmartCraftOrderPacket` 现在默认只更新客户端 `OVERLAY` 数据，不再抢占当前界面
- 点击 `查看调度` 时会设置一次性的 `openSmartCraftStatusOnNextSync` 意图，下一次状态同步才会打开 `GuiSmartCraftStatus`
- AE2 `GuiCraftConfirm` 和 AE2 终端界面在已有订单数据后会显示 `查看调度` 按钮，玩家可以手动回到智能合成状态页
- `GuiCraftConfirm` 仍保留 `智能合成` 启动按钮，但点击启动后不会把玩家卡在状态 UI 中

### 遇到的问题
- 旧逻辑没有区分服务端主动推送状态和玩家点击按钮查看状态，两者都会走同一个打开 UI 路径
- 如果玩家关闭状态页，服务端运行态刷新仍会继续同步订单数据，旧逻辑会把关闭操作立刻“撤销”

### 验证
- 已先运行 `SmartCraftScreenFlowTest` 并确认新断言在旧实现下编译失败
- 已执行 `./gradlew.bat --offline --no-daemon test --tests com.homeftw.ae2intelligentscheduling.client.gui.SmartCraftScreenFlowTest`，结果 `BUILD SUCCESSFUL`
- 已执行 `./gradlew.bat --offline --no-daemon test`，结果 `BUILD SUCCESSFUL`
- 已执行 `./gradlew.bat --offline --no-daemon reobfJar`，结果 `BUILD SUCCESSFUL`

### 设计决策
- 状态同步只负责刷新数据，不负责强制打开 UI
- 打开状态页必须来自玩家显式点击 `查看调度`，或玩家已经停留在状态页内
- 通过一次性客户端意图衔接按钮点击和服务端状态响应，避免给同步包增加新的协议字段

---

## 2026-04-27：排查并收束智能调度 UI 流程混乱

### 已完成
- 明确旧截图已过时，本轮不再以截图作为判断依据，改为直接排查当前代码路径
- 新增 `SmartCraftScreenFlow`，用测试锁定客户端界面流转规则
- 将 AE2 `GuiCraftConfirm` 收束为只保留 `智能合成` 启动入口，不再在确认页绘制智能调度状态叠层、取消/重试按钮或任务按钮
- 修复终端页 `查看调度` 按钮可见但事件处理被 `GuiCraftConfirm` 判断提前拦截的问题
- 调整 `ClientProxy.openSmartCraftStatus`，服务端同步智能订单状态时会打开专用 `GuiSmartCraftStatus`，当前已在状态页时则复用现有页面
- 将取消整单、重试失败和每 20 tick 刷新职责迁移到 `GuiSmartCraftStatus`
- 修正状态页绘制顺序，先绘制 AE2 背景和内容，再绘制按钮，避免按钮被背景盖住
- 将 `SmartCraftOverlayRenderer` 中的 `Scale / Status / Tasks / Amt / Loading / Time` 等硬编码英文改为语言键，并补充缺失语言项

### 遇到的问题
- 旧逻辑同时把智能调度状态画在 AE2 原生确认页，又存在独立状态页，导致 UI 职责重叠
- `VIEW_STATUS_BUTTON_ID` 按钮会被加到终端页，但点击事件处理函数开头只接受 `GuiCraftConfirm`，所以终端页按钮实际上不会发起状态请求
- `GuiSmartCraftStatus.drawScreen()` 原本先调用 `super.drawScreen()`，再绘制背景纹理，后续按钮容易被盖住
- 状态渲染器部分文本仍是英文硬编码，和已有中文语言资源混用

### 验证
- 已先运行新增目标测试并观察到缺少 `SmartCraftScreenFlow` 的失败，再补齐实现
- 已执行 `./gradlew.bat --offline --no-daemon test --tests com.homeftw.ae2intelligentscheduling.client.gui.SmartCraftScreenFlowTest`，结果 `BUILD SUCCESSFUL`
- 已执行 `./gradlew.bat --offline --no-daemon test`，结果 `BUILD SUCCESSFUL`
- 已执行 `./gradlew.bat --offline --no-daemon reobfJar`，结果 `BUILD SUCCESSFUL`

### 设计决策
- AE2 原生确认页只负责启动智能合成，不再承载订单运行态 UI
- 智能订单运行态统一进入 `GuiSmartCraftStatus`，减少按钮、刷新和绘制职责分散
- 暂时继续复用 `SmartCraftOverlayRenderer` 作为状态页内容渲染器，避免在本轮排查中引入大规模 UI 重写

---

## 2026-04-27：修复 AE2Things 合成确认页 NEI manager 空指针

### 已完成
- 根据崩溃堆栈确认受影响界面为 `com.asdflj.ae2thing.client.gui.GuiCraftConfirm`，其继承 AE2 原生 `GuiCraftConfirm`
- 追踪 NEI ASM 逻辑，确认 `GuiContainer.updateScreen()` 会直接调用动态字段 `manager.updateScreen()`，当 `manager` 未初始化时会触发本次 NPE
- 新增 `NeiGuiContainerManagerGuard`，在检测到 NEI 已注入 `manager` 字段但字段值仍为 `null` 时，按 NEI 的字段类型反射创建 manager 并调用 `load()`
- 新增 `GuiContainerNeiManagerGuardMixin`，在客户端所有 `GuiContainer.updateScreen()` 开头执行缺失 manager 修复
- 将 `mixins.ae2intelligentscheduling.json` 的 client mixin 配置更新为包含该兼容护栏
- 新增 `NeiGuiContainerManagerGuardTest` 覆盖缺失 manager、已有 manager、无 NEI 字段三种情况
- 将旧的 `SmartCraftSchedulerTest` 从已删除的 `SmartCraftScheduler` 迁移到当前 `SmartCraftRuntimeCoordinator`，恢复完整测试编译
- 重新生成客户端测试用 `build/libs/ae2intelligentscheduling-0.1.0-dev.jar`

### 遇到的问题
- `./gradlew test --tests ...` 在编译所有测试源时被旧 `SmartCraftSchedulerTest` 阻塞，因为生产类 `SmartCraftScheduler` 已在当前工作树中删除
- NEI 的 `manager` 字段是运行时 ASM 注入字段，源码编译期不能直接访问，因此修复必须使用反射而不是字段引用

### 验证
- 已执行 `./gradlew.bat --offline --no-daemon compileJava`，结果 `BUILD SUCCESSFUL`
- 已执行 `./gradlew.bat --offline --no-daemon test --tests com.homeftw.ae2intelligentscheduling.client.gui.NeiGuiContainerManagerGuardTest --tests com.homeftw.ae2intelligentscheduling.smartcraft.runtime.SmartCraftSchedulerTest`，结果 `BUILD SUCCESSFUL`
- 已执行 `./gradlew.bat --offline --no-daemon test`，结果 `BUILD SUCCESSFUL`
- 已执行 `./gradlew.bat --offline --no-daemon reobfJar`，结果 `BUILD SUCCESSFUL`
- 已确认 `ae2intelligentscheduling-0.1.0-dev.jar` 包含 `NeiGuiContainerManagerGuard`、`GuiContainerNeiManagerGuardMixin` 与 `mixins.ae2intelligentscheduling.json`

### 设计决策
- 修复点放在 `GuiContainer.updateScreen()` 开头，覆盖 AE2 原生确认页和 AE2Things 派生确认页，不依赖具体 GUI 类名
- 不引入对 `codechicken.nei` 的编译期依赖，避免没有 NEI 字段的环境直接崩溃
- 只在反射修复失败时记录一次 warn，避免客户端每 tick 刷屏

---

## 2026-04-23：修复状态页乱码与顶部布局溢出

### 已完成
- 根据客户端截图 `2026-04-23_09.12.09.png` 确认状态页显示的是 `\uXXXX` 字面量，而不是真实中文
- 将 `en_US.lang` 从 Unicode 转义文本改为真实 UTF-8 中文
- 新增 `zh_CN.lang`，确保中文客户端优先加载中文资源
- 新增 `SmartCraftLanguageFileTest`，防止语言文件再次出现 `\uXXXX` 字面量
- 收紧 `GuiSmartCraftStatus` 顶部摘要区域的文本裁剪，避免长物品 ID、状态或数量文本穿过分隔线和右侧区域
- 重新生成客户端测试用 `build/libs/ae2intelligentscheduling-0.1.0-dev.jar`

### 遇到的问题
- Minecraft 1.7.10 / 当前 GTNH 语言加载路径没有把 `.lang` 中的 `\uXXXX` 按 Java properties 转义解码，导致其直接显示在按钮和状态页上
- 这些转义文本长度很长，又进一步造成状态页顶部文本互相覆盖和布局错乱

### 验证
- 已用 `Zulu21` 执行 `SmartCraftLanguageFileTest`、`SmartCraftConfirmButtonLayoutTest`、`SmartCraftPacketCodecTest`，结果 `BUILD SUCCESSFUL`
- 已用 `Zulu21` 执行完整 `test reobfJar`，结果 `BUILD SUCCESSFUL`
- 已确认源码语言资源中不再存在 `\uXXXX` 转义残留
- 已确认新 jar 包含 `assets/ae2intelligentscheduling/lang/en_US.lang` 与 `assets/ae2intelligentscheduling/lang/zh_CN.lang`

### 设计决策
- GTNH 客户端已有中文字体显示能力，因此语言资源直接使用 UTF-8 中文文本，不再使用 `\uXXXX` 转义
- 状态页摘要区优先保证不溢出；完整物品 ID 继续通过任务 tooltip 查看

---

## 2026-04-23：改用 Forge GUI 事件注入智能合成入口

### 已完成
- 将 `智能合成` 入口从 `GuiCraftConfirmMixin` 改为 `SmartCraftConfirmGuiEventHandler`
- 在客户端 `ClientProxy` 注册 Forge GUI 事件处理器
- 通过 `GuiScreenEvent.InitGuiEvent.Post` 检测 AE2 `GuiCraftConfirm` 并追加 `智能合成` 按钮
- 按钮当前位于 AE2 合成确认页右下区域，靠近原 `Start` 按钮
- 新增 `SmartCraftConfirmButtonLayout` 与 `SmartCraftConfirmButtonLayoutTest`，锁定按钮布局
- 新增 `Ae2CraftConfirmAccess`，通过反射读取 `ContainerCraftConfirm.result` 并构造 action source，避免服务端 packet 依赖 accessor mixin
- 从 mixin 配置中移除 GUI / accessor mixin，删除旧的 `GuiCraftConfirmMixin`
- 重新生成客户端测试用 `build/libs/ae2intelligentscheduling-0.1.0-dev.jar`

### 遇到的问题
- 客户端测试反馈确认页找不到旧 Mixin 注入按钮；为降低整包环境里 Mixin 加载差异的影响，改用 Forge GUI 事件作为入口注入机制

### 验证
- 已用 `Zulu21` 执行 `SmartCraftConfirmButtonLayoutTest` 与 `SmartCraftPacketCodecTest`，结果 `BUILD SUCCESSFUL`
- 已用 `Zulu21` 执行完整 `test reobfJar`，结果 `BUILD SUCCESSFUL`
- 已确认新 jar 包含 `SmartCraftConfirmGuiEventHandler`、`SmartCraftConfirmButtonLayout`、`Ae2CraftConfirmAccess`，且不再包含 `GuiCraftConfirmMixin`

### 设计决策
- 入口 UI 优先使用 Forge GUI 事件注入，减少对 AE2 GUI Mixin 的依赖
- 运行态仍从 AE2 合成确认页读取已经计算好的 `CraftingJobV2`，因此入口仍放在点击 `下一页` 后的合成确认页，而不是数量选择页

---

## 2026-04-23：调整智能合成按钮位置并重打客户端 jar

### 已完成
- 将 `GuiCraftConfirmMixin` 注入的 `智能合成` 按钮从底部中间区域移到 AE2 合成确认页左上角，方便客户端测试时直接识别
- 为按钮注入增加 `Injected smart craft button into AE2 GuiCraftConfirm` 日志，便于判断 Mixin 是否实际应用
- 重新生成客户端测试用 `build/libs/ae2intelligentscheduling-0.1.0-dev.jar`

### 遇到的问题
- 客户端测试反馈在 AE2 合成确认页找不到 `智能合成` 按钮；当前证据显示 jar manifest 与 mixin 配置存在，因此先通过更显眼的位置和日志确认运行时注入状态

### 验证
- 已用 `Zulu21` 执行 `reobfJar`，结果 `BUILD SUCCESSFUL`

### 设计决策
- 当前测试 jar 优先保证入口可见性与可诊断性，后续确认注入稳定后再考虑更精细的最终 UI 位置

---

## 2026-04-23：生成客户端测试 jar

### 已完成
- 执行 `reobfJar` 生成客户端测试用 jar
- 确认可用于客户端 `mods` 目录测试的产物为 `build/libs/ae2intelligentscheduling-0.1.0-dev.jar`
- 确认同时生成开发 jar `build/libs/ae2intelligentscheduling-0.1.0-dev-dev.jar`

### 验证
- 已用 `Zulu21` 执行 `reobfJar`，结果 `BUILD SUCCESSFUL`

### 设计决策
- 客户端测试优先使用不带第二个 `-dev` 后缀的 reobf 产物

---

## 2026-04-22：完成计划闭环验证与文档同步

### 已完成
- 确认智能合成分析、缺口拆分、分层调度、真实 AE2 下单、取消 / 重试、状态同步与主动刷新链路已形成完整闭环
- 确认 `GuiSmartCraftStatus` 当前使用 `craftingreport.png`，上方展示正常合成摘要，下方展示智能队列任务、状态、CPU 与执行阶段
- 将 `ToDOLIST.md`、`context.md` 与本轮实际实现状态同步，保留图标化任务列表或整棵合成树可视化为后续可选项

### 验证
- 已用 `Zulu21` 执行 `compileJava -x compileInjectedTagsJava`，结果 `BUILD SUCCESSFUL`
- 已用 `Zulu21` 执行关键测试 `SmartCraftPacketCodecTest`、`SmartCraftSchedulerTest`、`SmartCraftRuntimeCoordinatorTest`，结果 `BUILD SUCCESSFUL`
- 已用 `Zulu21` 执行完整 `test`，结果 `BUILD SUCCESSFUL`

### 设计决策
- 第一版状态页继续保持文本型任务队列，优先保证实时反馈、滚动、tooltip、取消 / 重试与刷新闭环稳定
- 图标化或树状视图作为后续增强，不阻塞当前计划闭环

---

## 2026-04-22：状态页改回 craftingreport 上下分区布局

### 已完成
- `GuiSmartCraftStatus` 背景切回 AE2 `guis/craftingreport.png`
- 状态页高度恢复为 `238x206`
- UI 上半部分新增 `正常合成` 区域，显示目标物品、目标数量、量级、订单状态与当前层
- UI 下半部分新增 `智能队列` 区域，显示智能合成任务队列、分片、状态、CPU 与执行阶段
- 保留每 20 tick 主动刷新机制，继续与 AE2 合成状态页的保底刷新节奏对齐

### 验证
- 已用 `Zulu21` 执行 `compileJava -x compileInjectedTagsJava`，结果 `BUILD SUCCESSFUL`

### 设计决策
- 背景与整体视觉回到 `craftingreport.png`，但布局明确拆成上方正常合成摘要与下方智能队列
- 不改变智能合成入口、调度队列与 AE2 原始合成按钮行为

---

## 2026-04-22：状态页刷新与 AE2 合成 CPU 页面风格对齐

### 已完成
- `GuiSmartCraftStatus` 的窗口高度改为 AE2 `GuiCraftingCPU` 使用的 `238x184`
- 状态页背景从 `guis/craftingreport.png` 切换为 AE2 合成 CPU 页面使用的 `guis/craftingcpu.png`
- 状态页文字颜色改为复用 AE2 `CraftingCPUTitle`、`CraftingCPUStored`、`CraftingCPUAmount` 等颜色
- 智能任务行按 AE2 合成状态语义着色：运行中任务使用 `CraftingCPUActive`，等待 / 计划任务使用 `CraftingCPUInactive`
- 保持每 20 tick 主动刷新一次的节奏，与 AE2 `ContainerCPUTable` 的 CPU 列表保底刷新节奏一致

### 遇到的问题
- AE2 原 `GuiCraftingCPU` 强绑定 `ContainerCraftingCPU` 和真实 `CraftingCPUCluster`，不适合直接继承来显示智能调度队列
- 因此本轮选择复用 AE2 贴图、尺寸、颜色和刷新节奏，而不是直接把我们的状态页改成 AE2 原 container

### 验证
- 已用 `Zulu21` 执行 `compileJava -x compileInjectedTagsJava`，结果 `BUILD SUCCESSFUL`

### 设计决策
- UI 表现尽量贴近 AE2 合成 CPU 页面，数据层继续使用本模组自己的智能订单 packet
- 当前刷新模型采用“服务端状态变化推送 + 客户端 20 tick 保底请求”，与 AE2 状态页的 container 保底同步思路保持一致

---

## 2026-04-22：Zulu21 构建兼容与状态页主动刷新

### 已完成
- `build.gradle` 显式将本模组 `compileJava`、`compileTestJava`、`compileInjectedTagsJava` 和 `test` 任务定向到 `JAVA_HOME` 中的 `Zulu21`
- 保留 RFG patched Minecraft 相关任务的默认行为，避免 JDK21 编译缺失 `Pack200` 的 Forge 源码
- `RequestSmartCraftActionPacket` 新增 `REFRESH_ORDER` 动作，用于客户端状态页主动请求服务端重发订单状态
- `GuiSmartCraftStatus` 增加每 20 tick 的主动刷新，订单完成或取消后停止刷新
- 取消 / 重试 / 刷新后的同步现在会优先携带 `SmartCraftRuntimeSession`，避免丢失 CPU 名称与执行阶段
- `SmartCraftPacketCodecTest` 增加 `REFRESH_ORDER` 编解码覆盖

### 遇到的问题
- GTNH convention 默认会把 Java toolchain 锁到 `Azul Zulu 17`，在只允许 `Zulu21` 的环境下会导致离线构建失败
- 不能全局把所有 `JavaCompile` 都切到 `Zulu21`，因为 RFG 的 patched Minecraft 源码仍引用 JDK21 已删除的 `Pack200`
- `addon.gradle` 的语言预处理任务依赖项目根目录相对路径，因此测试命令需要从项目根目录启动

### 验证
- 已用 `Zulu21` 执行 `compileJava -x compileInjectedTagsJava`，结果 `BUILD SUCCESSFUL`
- 已用 `Zulu21` 执行关键测试 `SmartCraftPacketCodecTest`、`SmartCraftSchedulerTest`、`SmartCraftRuntimeCoordinatorTest`，结果 `BUILD SUCCESSFUL`

### 设计决策
- 只覆盖本模组相关编译和测试任务的 toolchain，不改 RFG 内部 patched Minecraft 编译任务
- 状态页主动刷新复用现有 action packet，避免新增一个只传订单 ID 的重复网络包

---

## 2026-04-22：完成真实 AE2 下单链路与服务端运行态协调器

### 已完成
- 为 `SmartCraftTask` 增加稳定的 `taskId`，避免同层相同物品任务在运行态追踪时发生 key 冲突
- 为 `Ae2RequestKey` 增加请求模板保存与 `createCraftRequest(long amount)`，支持从分析结果重建真实 AE2 下单请求
- 新增 `SmartCraftRuntimeSession`、`SmartCraftRuntimeCoordinator`、`SmartCraftServerTickHandler`
- 新增 `Ae2SmartCraftJobPlanner` 与 `SmartCraftAe2RuntimeSessionFactory`
- `OpenSmartCraftPreviewPacket` 现在会在生成订单后自动注册运行态 session，不再只是静态预览
- 服务端 tick 期间会执行 `beginCraftingJob -> Future 完成 -> submitJob -> ICraftingLink 轮询 -> task/order 状态回写`
- `RequestSmartCraftActionPacket` 现在改为经过 `SMART_CRAFT_RUNTIME` 执行取消 / 重试，能影响真实运行中的 future 和 link
- `SmartCraftRequesterBridge` 现在委托真实 `IActionHost`，使 AE2 crafting link 生命周期挂接在真实网络节点上
- 新增 `SmartCraftRuntimeCoordinatorTest`
- 验证 `./gradlew.bat --offline --no-daemon "-Pelytra.manifest.version=true" -DDISABLE_BUILDSCRIPT_UPDATE_CHECK=true -PautoUpdateBuildScript=false -PdisableSpotless=true test --tests com.homeftw.ae2intelligentscheduling.smartcraft.runtime.SmartCraftRuntimeCoordinatorTest --tests com.homeftw.ae2intelligentscheduling.smartcraft.runtime.SmartCraftSchedulerTest --tests com.homeftw.ae2intelligentscheduling.network.packet.SmartCraftPacketCodecTest` 通过
- 验证 `./gradlew.bat --offline --no-daemon "-Pelytra.manifest.version=true" -DDISABLE_BUILDSCRIPT_UPDATE_CHECK=true -PautoUpdateBuildScript=false -PdisableSpotless=true test --tests com.homeftw.ae2intelligentscheduling.smartcraft.analysis.SmartCraftOrderBuilderTest --tests com.homeftw.ae2intelligentscheduling.integration.ae2.Ae2CraftTreeWalkerTest --tests com.homeftw.ae2intelligentscheduling.smartcraft.runtime.SmartCraftRuntimeCoordinatorTest` 通过

### 遇到的问题
- 当前仓库同时使用了 Elytra conventions，PowerShell 下带点的 Gradle 属性需要用引号传递 `"-Pelytra.manifest.version=true"` 才能正确命中本地 manifest 缓存
- 真实运行态追踪要求 `ICraftingRequester` 绑定到有效 AE 网络节点；原先纯空壳 `SmartCraftRequesterBridge` 会让 link 生命周期不稳定
- `SmartCraftTask.taskKey()` 之前依赖 `requestKey + depth + split`，遇到同层相同物品的重复任务时可能冲突

### 设计决策
- 运行态改为独立的服务端协调器推进，而不是把异步 `beginCraftingJob` 强行塞回现有同步 scheduler 接口
- 真实 AE2 下单请求通过 `Ae2RequestKey` 保存的模板栈重建，先保证 item 任务链路跑通
- 预览入口现在即是自动启动入口：生成订单后立即注册 session，由服务端 tick 按层推进

---

## 2026-04-22：完成 Task 6 智能合成状态页骨架与同步链路

### 已完成
- 新增 `SmartCraftOrderSyncService`，用于按订单 ID 将 `SmartCraftOrder` 同步到客户端
- 新增 `SyncSmartCraftOrderPacket`，把订单量级、状态、当前层与任务列表序列化到客户端状态页
- 新增 `RequestSmartCraftActionPacket`，支持从客户端请求 `取消整单` 与 `重试失败`
- 新增 `GuiSmartCraftStatus` 与 `SmartCraftTaskList`，提供最小可用的智能合成状态界面
- `OpenSmartCraftPreviewPacket` 在服务端完成订单分析后会直接同步并打开本模组状态页，不影响 AE2 原始合成按钮
- `SmartCraftOrderManager` 新增整单取消与失败任务重试入口，并补齐整批替换 `layers` 的能力
- 修复 Task 6 新增代码中的 Java 8 不兼容写法，包括 pattern matching `instanceof` 与缺失导入
- 验证 `./gradlew.bat --offline --no-daemon test --tests com.homeftw.ae2intelligentscheduling.network.packet.SmartCraftPacketCodecTest` 通过
- 验证 `./gradlew.bat --offline --no-daemon test --tests com.homeftw.ae2intelligentscheduling.smartcraft.runtime.SmartCraftSchedulerTest --tests com.homeftw.ae2intelligentscheduling.network.packet.SmartCraftPacketCodecTest` 通过

### 遇到的问题
- Task 6 新增代码里混入了 Java 16+ 的 pattern matching `instanceof` 语法，和当前 GTNH/Java 8 目标不兼容
- `SmartCraftOrderManager` 需要整体替换 `layers` 时，`SmartCraftOrder` 之前只支持单层替换，缺少整单重建入口
- 终端查看中文文档时出现编码显示异常，需要以文件实际内容为准而不是控制台渲染

### 设计决策
- 状态页先做“最小闭环”：预览分析完成后即可打开页面，并支持取消/重试的端到端 packet 回传
- Task 级展示当前先复用扁平任务列表，不提前引入树形 UI、滚动容器或复杂交互
- 文档与代码状态保持一致：本阶段只确认状态页骨架与同步链路完成，真实 `ICraftingJob` 运行态回填仍留在后续任务

---

## 2026-04-22：完成 Task 5 AE2 智能合成按钮与预览入口

### 已完成
- 新增 `NetworkHandler` 与 `OpenSmartCraftPreviewPacket`
- 新增 `GuiCraftConfirmMixin`、`ContainerCraftConfirmAccessor`、`ContainerCraftConfirmInvoker`
- 在 `GuiCraftConfirm` 中加入 `智能合成` 按钮，并接到本模组自定义 packet
- 服务端收到点击后会读取当前 `CraftingJobV2`，生成 `SmartCraftOrder` 并登记到 `SMART_CRAFT_ORDER_MANAGER`
- 新增 `SmartCraftPacketCodecTest`
- 验证 `./gradlew.bat --offline --no-daemon test --tests com.homeftw.ae2intelligentscheduling.network.packet.SmartCraftPacketCodecTest` 通过

### 遇到的问题
- AE2 自己的按钮区几乎占满底部主操作区，新增按钮如果放在原 `Start / Cancel / StartWithFollow` 行会直接挤压原逻辑
- 第三方 mod 类的 accessor / invoker mixin 默认会尝试走混淆映射，导致编译阶段出现无意义警告

### 设计决策
- `智能合成` 按钮放在 AE2 合成确认 UI 中的独立位置，避免影响原始 `Start` 按钮和原有行为
- AE2 预览入口当前先做“点击后分析并登记订单”的最小闭环，状态 GUI 与真实执行反馈留到后续任务接入
- AE2 第三方目标 mixin 明确使用 `remap = false`，避免对非原版目标做错误映射

---

## 2026-04-22：完成 Task 4 运行态调度骨架与 AE2 requester bridge

### 已完成
- 新增 `SmartCraftRequesterBridge`、`SmartCraftOrderManager`、`SmartCraftScheduler`、`SmartCraftStockVerifier`
- 新增 `Ae2CpuSelector` 与 `Ae2CraftSubmitter`
- 为 `SmartCraftTask`、`SmartCraftLayer`、`SmartCraftOrder`、`SmartCraftStatus` 补充运行态状态转换辅助方法
- 新增 `Ae2CpuSelectorTest` 与 `SmartCraftSchedulerTest`
- 验证 `./gradlew.bat --offline --no-daemon test --tests com.homeftw.ae2intelligentscheduling.integration.ae2.Ae2CpuSelectorTest --tests com.homeftw.ae2intelligentscheduling.smartcraft.runtime.SmartCraftSchedulerTest` 通过

### 遇到的问题
- 当前阶段尚未把真实 `ICraftingJob` 绑定进 `SmartCraftTask`，因此调度器需要先以可测试的提交抽象运行，避免过早和 UI / packet 链路耦死

### 设计决策
- 调度器当前以“当前层完成后才推进下一层”为硬约束，先保证依赖顺序正确，再继续接 UI 与真实下单链路
- CPU 选择先采用最小可用策略：从空闲 CPU 列表中顺序挑选，避免在运行态骨架阶段过早引入复杂负载均衡

---

## 2026-04-22：完成 Task 3 AE2 合成树快照与智能订单构建

### 已完成
- 新增 `Ae2RequestKey`、`Ae2CraftTreeWalker`、`Ae2CraftingJobSnapshotFactory`
- 新增 `SmartCraftOrderBuilder`，将 AE2 请求树转换为按缺口扣库存后的分层 `SmartCraftOrder`
- 新增 `SmartCraftOrderBuilderTest` 与 `Ae2CraftTreeWalkerTest`
- 验证 `./gradlew.bat --offline --no-daemon test --tests com.homeftw.ae2intelligentscheduling.smartcraft.analysis.SmartCraftOrderBuilderTest --tests com.homeftw.ae2intelligentscheduling.integration.ae2.Ae2CraftTreeWalkerTest` 通过

### 遇到的问题
- `AEItemStack` 在普通 JUnit 环境里会隐式依赖 Minecraft / FML 引导流程，直接使用 `Items.*` 或 `Bootstrap.func_151354_b()` 都会导致测试失败
- Gradle 测试在沙箱内无法直接使用真实 GTNH 缓存，需要切换到可访问本机缓存的验证方式

### 设计决策
- AE2 树分析阶段先做只读快照，不在分析时修改 AE2 运行态对象
- 智能订单分层顺序采用自底向上，保证底层中间材料先入队、上层产物后推进
- `Ae2CraftTreeWalkerTest` 使用测试内的 `FakeAeItemStack` 隔离 AE2 内部启动副作用，单测只覆盖我们自己的遍历语义

---

## 2026-04-22：完成 Task 2 纯规划模型与拆分规则

### 已完成
- 新增 `SmartCraftOrderScale`、`SmartCraftStatus`、`SmartCraftNode`、`SmartCraftTask`、`SmartCraftLayer`、`SmartCraftOrder`
- 新增 `SmartCraftRequestKey` 作为请求键抽象，为后续 `Ae2RequestKey` 接入留出稳定边界
- 新增 `SmartCraftOrderScaleClassifier` 与 `SmartCraftSplitPlanner`
- 新增 `SmartCraftSplitPlannerTest`，覆盖 `SMALL / MEDIUM / LARGE` 三档规则
- 验证 `./gradlew.bat --offline test --tests com.homeftw.ae2intelligentscheduling.smartcraft.analysis.SmartCraftSplitPlannerTest` 通过

### 遇到的问题
- 规划文档中的示例测试使用了 `List.of(...)`，但当前目标字节码仍是 Java 8，需要改成兼容写法

### 设计决策
- 规划模型当前先保持最小可用实现，优先服务 Task 3 的树转订单能力
- 请求键先抽象成 `SmartCraftRequestKey` 接口，避免纯规划模型过早耦合到 AE2 具体类

---

## 2026-04-22：完成 Task 1 项目脚手架

### 已完成
- 在 `feature/implement-smart-craft` worktree 中建立 GTNH 模组脚手架
- 新增 `build.gradle`、`settings.gradle`、`gradle.properties`、`dependencies.gradle`、`addon.gradle`、`repositories.gradle`
- 新增 `AE2IntelligentScheduling` 主类、`CommonProxy`、`ClientProxy`、`Config`
- 新增 mixin 配置、`mixin` 包占位与 `accesstransformer.cfg`
- 复制 Gradle wrapper，并确认 `./gradlew.bat --offline compileJava` 可成功执行
- 补充项目级 `.gitignore`，忽略 `.gradle`、`build`、`run`、`bin` 等生成目录

### 遇到的问题
- `JAVA_HOME` 指向 `Zulu 25` 时，GTNH 构建链会在启动期失败，需要改用兼容的 Gradle JDK
- AE2 依赖若使用错误坐标会命中 TLS 有问题的镜像，需要改为本机已缓存可用的 GTNH 坐标

### 设计决策
- 当前项目构建验证统一使用 `JDK 21` 运行 Gradle
- `gradle.properties` 默认设置 `modVersion = 0.1.0-dev` 与 `gtnh.modules.gitVersion = false`
- Task 1 完成后，下一步进入纯规划模型实现与测试
# 开发日志
## 2026-04-22：同步工作树启动脚本到 JAVA_HOME / Zulu21 环境约定

### 已完成
- 清理工作树 `gradlew.bat` 中前一轮临时加入的 `Zulu17` 与额外 toolchain 注入逻辑
- 保持工作树启动脚本只通过 `JAVA_HOME` 寻找构建 JDK，不再探测或切换到 `Zulu17`
- 将 `gradlew.bat` 与 `gradlew` 的错误提示统一为“请将 `JAVA_HOME` 指向 Zulu21”
- 检查工作树启动脚本，确认 `gradlew` 与 `gradlew.bat` 中已无 `Zulu17` 相关入口

### 遇到的问题
- 当前项目的实际编译链仍可能对 Java toolchain 有额外要求，但本轮按用户要求不再在启动脚本层引入 `Zulu17`

### 设计决策
- 启动脚本层只保留单一 JDK 入口：`JAVA_HOME`
- JDK 版本约定明确收敛为用户环境变量中的 `Zulu21`，不再在脚本中做额外自动修正

---

## 2026-04-22：将智能合成状态页切换为 AE2 风格 GUI

### 已完成
- 将 `GuiSmartCraftStatus` 从普通 `GuiScreen` 重构为 `AEBaseGui`
- 复用 AE2 的 `guis/craftingreport.png` 背景与 `GuiScrollbar`，让状态页风格和 AE2 合成确认界面保持一致
- 在状态页中加入目标、数量、量级、状态、当前层与任务数摘要，并将任务列表改为可滚动展示
- 为任务行增加悬浮 tooltip，显示完整请求键、精确数量、状态与阻塞原因
- 保留 `GuiCraftConfirmMixin` 中注入到 AE2 合成确认界面的 `智能合成` 按钮入口，不影响原有合成按钮与流程
- 验证 `./gradlew.bat -p D:\Code\AE2-IntelligentScheduling\.worktrees\implement-smart-craft compileJava -x compileInjectedTagsJava --offline --no-daemon "-Pelytra.manifest.version=true" -DDISABLE_BUILDSCRIPT_UPDATE_CHECK=true -PautoUpdateBuildScript=false -PdisableSpotless=true` 在本地 Azul Zulu 17 下通过

### 遇到的问题
- 工作树路径在当前沙箱下不能直接作为命令工作目录使用，需要改为从 `D:\Code` 调用绝对路径的 `gradlew.bat`
- 当前仓库工具链仍要求 `Azul Zulu 17` 编译目标，若直接用 `Zulu 25` 或未切到兼容 JDK，会在构建脚本或 toolchain 解析阶段失败

### 设计决策
- 状态页优先贴近 AE2 现有视觉语言，而不是继续扩展自绘 `GuiScreen`，这样能保持用户在 AE2 合成链路中的操作一致性
- 本轮只替换状态页表现层，不改动 AE2 原有确认按钮和原始下单行为，智能合成入口继续作为额外按钮存在

---
## 2026-04-22 智能合成状态页补充运行态可视化

### 已完成
- 为 `SmartCraftRuntimeSession` 增加任务 `assignedCpuName` 记录
- 在 `SmartCraftRuntimeCoordinator` 选中 CPU 后回填 session，供状态同步读取
- 扩展 `SyncSmartCraftOrderPacket.TaskView`，同步 `executionState` 与 `assignedCpuName`
- `GuiSmartCraftStatus` 现在会显示 `PLANNING / PLANNED / SUBMITTED` 阶段，以及已分配 CPU 名称
- 补充 `SmartCraftPacketCodecTest`，覆盖执行阶段与 CPU 名称编解码
- 修复 `SmartCraftRuntimeSession` 缺少 `ICraftingCPU` import 的编译问题

### 遇到的问题
- 当前状态页仍以服务端事件推送为主，尚未加入客户端主动轮询刷新
- 本轮按要求不再引入 `Zulu17`，后续编译验证需要基于现有 `JAVA_HOME=Zulu21` 环境继续确认

### 设计决策
- 先把运行态可视化控制在轻量级字段扩展，不额外引入新的复杂状态机
- GUI 继续复用 AE2 原有风格，只增强智能合成状态信息，不改动原始下单按钮与行为
