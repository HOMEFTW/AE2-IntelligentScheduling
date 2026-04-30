# TODO 列表


## 2026-04-30（v0.1.9.2 SmartCraft 全局 CPU 占用上限 G13）已完成

> 用户反馈：「Programmable Hatches 的合成 CPU 配合智能合成时会无限创建 cluster 导致服务器卡顿。智能合成最多让合成CPU分配默认50个CPU，可配置。要分清玩家手动 craft，不能影响他们。」

### ✨ 已完成

- [x] **`Config.MAX_CONCURRENT_SMART_CRAFT_SUBMISSIONS = 50`**：forge config `maxConcurrentSmartCraftSubmissions`，0 = 禁用，range 0-1024
- [x] **`SmartCraftRuntimeSession.countActiveSubmissions()`**：本 session 内 craftingLink != null 的 task 计数
- [x] **`SmartCraftRuntimeCoordinator.globalActiveSubmissions()`**：跨 sessions 汇总
- [x] **`dispatchReadyTasks` Phase 3 cap-gate**：超 budget 的 task 标 WAITING_CPU + throttle banner，保留 cached plan，下 tick 重试
- [x] **`submissionsThrottledByCap` 计数器** + stats 日志加 `activeSubmissions/cap` 字段
- [x] **2 个新单测**：cap=2 时 4 task 仅 2 RUNNING + 2 throttled；cap=0 sentinel 全放行
- [x] **modVersion 0.1.9.1 → 0.1.9.2** + log.md changelog + 3 个 jar；33 个测试全过

### 🚧 后续可优化

- [ ] **per-player cap**：现在是全服共享一个 50。多玩家服务器可能需要每个玩家独立 cap（防止一个玩家用大订单挤压其他玩家）
- [ ] **cap 超载时的 GUI 提示**：目前只在 task tooltip 显示 `Throttled: ...`，可在 order tab bar 上加个可见标记（如黄色感叹号）
- [ ] **dynamic cap based on AE2 cluster count**：理想情况是检测网络上真实 idle cluster 数自动调，而不是死写 50

---

## 2026-04-29（v0.1.9.1 标签页图标改为最终产物）已完成

> 用户反馈："UI 中标签页的图标不合理，应该显示的是最终产物的图标，而不是第一步的图标。" 修复 `SmartCraftOrderTabsWidget` 取 task[0] 而不是 root 的 bug。

### ✨ 已完成

- [x] **`SyncSmartCraftOrderPacket` 加 `targetItemStack` 字段**：紧跟 `targetRequestKeyId` 序列化（writeItemStack / readItemStack），`from(...)` 取 `order.targetRequestKey().itemStack()`
- [x] **`SmartCraftOrderTabsWidget.rootItemStack` 优先读 `getTargetItemStack()`**：保留 task list fallback 应对 fluid / 旧 packet
- [x] **`getTargetItemStack()` getter** 文档说明用途和 nullable 含义
- [x] **modVersion 0.1.9.0 → 0.1.9.1** + log.md changelog + assemble 出 3 jar；现有 31 个测试零修改全过

---

## 2026-04-29（v0.1.9.0 服务器重启恢复运行中订单 阶段 4 G12）已完成

> 4 节点 retry 机制的最后一环：服务器重启后保持订单存活并自动恢复运行。NBT 持久化 + WorldSavedData + 玩家上线 session 重建一条龙。

### ✨ 已完成

- [x] **`SmartCraftRequestKey` 加 NBT** + 新增 `SmartCraftRequestKeyRegistry` type 路由
- [x] **`Ae2RequestKey` 实现** writeToNBT/readFromNBT，preInit 注册到 registry
- [x] **`SmartCraftTask` / `SmartCraftLayer` / `SmartCraftOrder` 全部加 NBT** + Order 加 ownerName 字段
- [x] **`SmartCraftOrderManager`** 加 DirtyListener + writeToNBT/loadFromNBT + resetForRestart 静态 helper
- [x] **`SmartCraftOrderWorldData`** （新文件）：WorldSavedData 实现 + attach() 静态 entry
- [x] **`AE2IntelligentScheduling.serverStarted`** 调 attach 挂到 overworld
- [x] **`SmartCraftAe2RuntimeSessionFactory.extractActionSourceFromOpenContainer`** 反射拿 actionHost
- [x] **`SmartCraftRuntimeCoordinator.attemptRebindSession`** owner-gate + factory.create + register
- [x] **`RequestSmartCraftActionPacket.Handler`** 在 dispatch action 前 attempt rebind
- [x] **`SmartCraftOrderBuilder.build(root, ownerName)`** 重载 + `OpenSmartCraftPreviewPacket` 传玩家名
- [x] **3 个新增单测**（NBT round-trip / dirty listener / resetForRestart 折回）：22 + 9 = 31 个测试全过
- [x] **modVersion 0.1.8.4 → 0.1.9.0** + log.md changelog + assemble 出 3 jar

### 🚧 后续可优化

- [ ] **Player 上线时主动 sync list**：当前重启后玩家上线必须先打开 ME 终端再触发 RequestOrderStatusPacket 才能看到 order。可加 PlayerLoggedInEvent 自动 syncListTo
- [ ] **Session rebind 不依赖 ME 终端**：当前必须玩家有 AE2 容器打开才能 rebind。可改成扫所有 player 的可达 AE2 grid（通过 wireless terminal 等），让玩家在任何状态都能恢复
- [ ] **持久化测试覆盖到 packet 层**：当前测试只到 OrderManager.writeToNBT/loadFromNBT。未来可加 mock world 模拟完整 server-stop / server-start 循环
- [ ] **数据迁移工具**：未来如果要重命名 lang key / status enum，需要 NBT 迁移钩子
- [ ] **多服务器订单导出**：把 NBT 文件作为可搬运资产，让玩家在 single-player → multi-player 之间迁移合成进度

---

## 2026-04-29（v0.1.8.4 detail panel 显示任务失败次数 G11）已完成

> 用户反馈："点击下面调度层 UI 中的分调度时添加额外显示信息：是否失败过，失败次数。" 把 plan/submit/link-cancel 三层 retry 的 attempts 总和暴露给 client，detail panel + tooltip 红色显示 `曾失败 失败次数: N`，仅 N>0 时显示。

### ✨ 已完成

- [x] **`SmartCraftRuntimeCoordinator.totalFailuresFor(taskKey)`**：累加 `planRetries` + `submitRetries` + `linkCancelRetries` 中该 taskKey 的 `attempts` 字段
- [x] **`SyncSmartCraftOrderPacket.TaskView` 加 `failureCount` 字段**：11 参构造重载（旧 10 参转调新版 with 0），`from(...)` 通过 `AE2IntelligentScheduling.SMART_CRAFT_RUNTIME.totalFailuresFor(task.taskKey())` 取值；`toBytes` / `fromBytes` 多读写 1 个 int
- [x] **`drawTaskDetailGrid` 红色行**：`failureCount > 0` 时在 cpu 行之前插入 `曾失败  失败次数: N`
- [x] **`appendSmartCraftLines` 同步追加**：tooltip 也显示同信息，detail/hover 两处一致
- [x] **lang keys 新增 2 个**：`failedBefore`（曾失败 / Failed before）+ `failureCount`（失败次数 / Failure count）
- [x] **modVersion 0.1.8.3 → 0.1.8.4** + log.md 详细 changelog + assemble 出 3 个 jar
- [x] 17 个 RuntimeCoordinator 测试仍全过

### 🚧 后续可优化

- [ ] **lifetime failure count**：当前是 current-retry-window count，task 重新成功就清零。未来加独立 `Map<String, Integer> taskLifetimeFailures` 记录"该 task 整个生命周期的累计失败次数"，给玩家完整诊断
- [ ] **按 retry 层细分**：当前只暴露总和，未来可拆 plan/submit/link-cancel 三个独立计数让玩家定位是哪一层在反复失败
- [ ] **schedule list 加 ⚠ 角标**：failureCount > 0 的 task 在左下 schedule list 行尾标个红 ⚠，避免玩家必须点开 detail 才看到

---

## 2026-04-29（v0.1.8.3 info bar 调度统计行 G10）已完成

> 用户反馈："在 UI 中任务数目的下面添加调度信息：已完成任务数目，未完成任务数目，失败数目，正在合成的数目。" 在 info bar 第 2 行右侧（status 同行）追加 4 类计数，颜色与 grid tile 一致。

### ✨ 已完成

- [x] **`drawInfoBar` 增加状态桶分类计数**：遍历 `cur.getTasks()` 分 4 桶（合成中 / 已完成 / 未完成 / 失败），CANCELLED + COMPLETED + ANALYZING 故意排除，避免误导
- [x] **§x 颜色码单次绘制**：`合成中` aqua / `已完成` green / `未完成` gray / `失败` red，颜色与 `cellBg` / `statusDotColor` 一致；`getStringWidth` 自动忽略颜色码无需手动 strip
- [x] **lang keys 新增 4 个**：`statsCrafting` / `statsCompleted` / `statsPending` / `statsFailed`，zh_CN 中文、en_US 英文
- [x] **modVersion 0.1.8.2 → 0.1.8.3** + log.md changelog + assemble 出 3 个 jar
- [x] 17 个 RuntimeCoordinator 测试仍全过，编译验证通过

### 🚧 后续可优化

- [ ] **stats 数字 hover 显示明细**：合成中=R 时 hover 显示具体 task 名字 list
- [ ] **stats 区域整行替代 right 区第 1 行**：如果 task 数与 amount 信息可以合并到一行（如 `任务数 50, 数量 1.2K`），把第 2 行整个右侧让给 stats 显示更详细分桶（甚至加进度百分比）
- [ ] **stats 行可选的进度条**：把 `已完成 / 总数` 转成视觉进度条让玩家一眼看到 % 完成度

---

## 2026-04-29（v0.1.8.2 grid 视图过滤 DONE 任务 G9）已完成

> 用户反馈："智能合成下单后，UI 上面的格子区域需要向 AE2 一样，已经合成完成的物品不再显示在格子区。" 修复：grid 顶区显示时过滤掉 DONE 任务，schedule list 仍保留全部任务作为进度概览。

### ✨ 已完成

- [x] **`SmartCraftOverlayRenderer` 加 grid 专用过滤**：新增 `private static activeGridTasks(cur)` helper（每帧重建过滤后的 list，性能可忽略），`drawTaskGrid` 改用它；新增 `public getGridTaskCount()` 给 GUI 调用做 scrollbar 计算
- [x] **`scroll(int)` / `setGridScroll(int)` 改用 grid task count**：clamp 用过滤后的 size，避免拖拽进空行
- [x] **`GuiSmartCraftStatus` 4 处 grid scrollbar 计算改用 `getGridTaskCount()`**：`drawGridScrollbar` / `gridScrollMax` / `dragGridThumb` / `tryStartScrollbarDrag`（grid 分支）
- [x] **schedule list / detail panel / findMatchingTask 故意保留 full list**：schedule list 是进度概览必须含 DONE；detail panel 索引绑 schedule list；findMatchingTask 给 ME 网格 hover 用，DONE 物品仍能 match
- [x] **modVersion 0.1.8.1 → 0.1.8.2** + log.md 详细 changelog + 跑 `assemble -x compileInjectedTagsJava` 出 3 个 jar
- [x] 17 个 RuntimeCoordinator 测试仍全过（无客户端 GUI 单测影响）

### 🚧 后续可优化

- [ ] **schedule list 中 DONE 任务可视降权**：当前 DONE 行与 PENDING/RUNNING 行视觉一致，可加 strike-through 或半透明，让玩家一眼区分"已完成"vs"还在做"
- [ ] **grid 添加"已完成 N 项"角标**：在 grid 区上方加小字 "Completed: N" 让玩家知道隐藏了多少 DONE 任务
- [ ] **grid 默认排序优化**：当前按 task index 排，filtered 后顺序保持。未来可考虑按 status 二次排序：RUNNING > WAITING_CPU > PENDING > FAILED，让"正在跑"的最显眼

---

## 2026-04-29（v0.1.8.1 子任务静音 + 整单完成通知 G8）已完成

> 用户反馈："AE2 合成完毕后会有提示音和提示弹窗，但由于调度，会不停的弹出提示音和提示弹窗，我想改成如果使用智能合成只有合成最终产物完成后再触发提示音和提示弹窗。" 排查发现源头是 AE2Things 的 `MixinCraftingCPUCluster`：仅当 `src instanceof PlayerSource` 时挂钩，每个 sub-task completeJob 时给持无线终端的玩家发 NOTIFICATION 包。修复：submit 用 MachineSource 屏蔽 mixin，整单 COMPLETED 时由 mod 自己发一次。

### ✨ 已完成

- [x] **`Ae2CraftSubmitter.submit` 改用 MachineSource**：`new MachineSource(requesterBridge)` 替代外部传入的 PlayerSource。AE2Things mixin 的 `instanceof PlayerSource` 检查失败 → 不再记录 player → completeJob 不发包；同时 defence-in-depth 屏蔽 vanilla AE2 followCraft 路径。actionSource 参数保留在签名里带 javadoc 解释
- [x] **新增 `OrderCompletionNotifier` 函数式接口**：`onOrderCompleted(session, orderId, order)`。null 表示禁用（5 参构造默认）
- [x] **新增 6 参构造重载**：`(orderManager, cpuSelector, jobPlanner, jobSubmitter, orderSync, notifier)`；5 参构造转调 6 参 with `null`，所有 17 个老测试零改动
- [x] **`tick()` 中加 rising-edge 检测**：`prev.status != COMPLETED && updated.status == COMPLETED` 时 try/catch 调 notifier 一次。不会重复触发（terminal-orders-vanish 在下 tick 移除 order）
- [x] **production 实现注入到 `AE2IntelligentScheduling`**：从 `order.targetRequestKey().itemStack().getDisplayName()` 取物品名（fallback 到 RequestKey id），拼 `"[智能合成] {name} x{amount} 已完成"` 绿色样式，`addChatMessage` + `playSoundAtEntity(owner, "random.levelup", 1.0f, 1.0f)`
- [x] **2 个新增测试**（17 个 RuntimeCoordinator 测试全过）：
  - `order_completion_notifier_fires_exactly_once_on_completed_transition_v0181`：分阶段断言 RUNNING/COMPLETED/post-vanish 时的 firedCount
  - `order_completion_notifier_does_not_fire_for_failed_or_cancelled_orders_v0181`：simulation planner → PAUSED → spin 50 tick 断言 firedCount=0
- [x] **新增 test stub `RecordingCompletionNotifier`**：list of completed orderIds + firedCount()
- [x] **modVersion 0.1.8.0 → 0.1.8.1** + log.md 详细 changelog + 跑 `assemble -x compileInjectedTagsJava` 出 3 个 jar

### 🚧 后续可优化

- [ ] **通知文案多语言化**：当前 chat message 是硬编码中文 + 英文 mix（"[智能合成]" 中文 + display name 跟物品本身），未来加 lang key 让英文玩家看英文版本
- [ ] **可配置开关**：玩家不希望 mod 自己发通知时（比如想看玩家自己 follow 单个 sub-task 的进度）可以加 Config `NOTIFY_ON_ORDER_COMPLETED=true` 默认开
- [ ] **声音可配置**：当前固定 `random.levelup`，未来可换成更柔和的 `note.harp` 之类
- [ ] **提示音音量随订单大小变化**：小订单（< 100 item）小声、大订单大声，强化"合成成功"的反馈感

---

## 2026-04-29（v0.1.8.0 4 节点自动重试机制 阶段 1-3：G5+G6+G7）已完成

> 用户原话："AE2 超大订单合成可能合成几个小时至十几个小时，需要添加自动重试的节点。" 现有 G1 plan retry 不够覆盖：submit 失败 / link cancel / 订单整体 FAILED 三处都需要独立的 backoff 重试预算。本版本（0.1.8.0）覆盖前 3 处（G5/G6/G7），第 4 处"服务器重启恢复"留给 0.1.9 单独做。

### ✨ 已完成

- [x] **G5 submit 失败自动重试**：`SmartCraftRuntimeCoordinator` 新增 `SubmitRetryState` + `handleSubmitFailure` + `submitRetryReady`，backoff 表 20/60/200/600/1200 ticks，default `SUBMIT_RETRY_MAX_ATTEMPTS=5`。submit 失败时保留 cached plan 只 detach CPU（`SmartCraftRuntimeSession.detachAssignedCpu` 新方法），下次 backoff 期满直接重 submit 不重 plan
- [x] **G6 link cancel 自动重试**：新增 `LinkCancelRetryState` + `handleLinkCancelFailure` + `linkCancelRetryReady`，backoff 表 6000/18000/36000 ticks（5min/15min/30min），default `LINK_CANCEL_RETRY_MAX_ATTEMPTS=2`。reconcile 时 link.isCanceled() 在非 CANCELLED 订单上路由进重试；plan 候选过滤加 `linkCancelRetryReady` gate；订单被玩家 cancel 时仍走 CANCELLED 路径
- [x] **G7 订单级服务端自动 retry**：新增 `OrderAutoRetryState` 跟踪 (firstFailedTick, attempts) 双字段，`tick()` 末尾扫描 retry-eligible 订单，间隔到达且 budget 在就 `doRetryFailed(orderId, false)`（保留 attempts 累计）。default `ORDER_AUTO_RETRY_INTERVAL_SECONDS=600` + `ORDER_AUTO_RETRY_MAX_ATTEMPTS=3`。`firstFailedTick=-1` 表示已 retry 等下次失败，`-2` 表示 budget 耗尽
- [x] **`retryFailed` 拆分**：拆出 `private doRetryFailed(orderId, clearAutoRetryBudget)`，玩家 GUI retry 走 true 清 budget，服务端自动 retry 走 false 保留累计 attempts。避免死循环
- [x] **Config**：新增 4 个配置项 `SUBMIT_RETRY_MAX_ATTEMPTS` / `LINK_CANCEL_RETRY_MAX_ATTEMPTS` / `ORDER_AUTO_RETRY_INTERVAL_SECONDS` / `ORDER_AUTO_RETRY_MAX_ATTEMPTS` 含 javadoc + forge config 同步
- [x] **G4 stats 扩展**：周期性日志多 7 个 counter（submitsAutoRetried / submitsFailedPermanently / linkCancelsAutoRetried / linkCancelsFailedPermanently / ordersAutoRetried / ordersAutoRetryExhausted + 4 个 pending size）让服主能区分三类失败的占比
- [x] **map 泄漏防护**：cancel / cancelGracefully / 终态自动删除 / retryFailed 全部清扫 4 个 retry map（planRetries / submitRetries / linkCancelRetries / orderAutoRetries），避免订单消失后还残留 entry
- [x] **6 个新增单元测试**（共 15 个 RuntimeCoordinator 测试全过）：
  - `submit_failure_keeps_cached_plan_and_retries_after_backoff_v0180`：第 1 次 null + 25 tick 后第 2 次成功
  - `submit_failure_marks_task_failed_after_exhausting_retry_budget_v0180`：max=2 + 永久 null + 200 tick → FAILED
  - `link_cancel_marks_task_failed_immediately_when_retry_budget_zero_v0180`：max=0 → 一击 FAILED
  - `link_cancel_retries_after_backoff_and_resubmits_with_fresh_plan_v0180`：max=1 + 6010 tick + 双 link → DONE
  - `order_auto_retry_triggers_after_interval_for_failed_order_v0180`：interval=20tick → planner 被多次调用
  - `order_auto_retry_stops_after_max_attempts_v0180`：max=1 → planCalls 收敛 + 订单仍 PAUSED
- [x] **现有 `submit_failure_records_diagnostic_blocking_reason` 测试改造**：包 SUBMIT_RETRY_MAX_ATTEMPTS=0 保留旧的"诊断 hint 仍然在 FAILED reason 里"语义
- [x] **modVersion 0.1.7.5 → 0.1.8.0** + log.md 详细 changelog + 跑 `assemble -x compileInjectedTagsJava` 出 3 个 jar

### 🚧 阶段 4（推迟到 0.1.9）

- [ ] **阶段 4a NBT 持久化**：`SmartCraftOrder` / `SmartCraftLayer` / `SmartCraftTask` / `SmartCraftRequestKey`（接口扩接口方法 `toNBT` 或外置 codec）全部支持 NBT 读写。`Ae2RequestKey` 的 ItemStack 走 `writeToNBT`。`SmartCraftOrderManager` 加 `loadFromNBT(World)` / `saveToNBT(World)` + 所有 mutator 调 `markDirty()`。
- [ ] **阶段 4b 服务端启动 load + session 重建**：`WorldSavedData` 挂载在 overworld；`FMLServerStartedEvent` 加载所有订单；非终态 task 全部重置 PENDING；`craftingLink` / `plannedJob` 丢弃。session 重建走"玩家上线 + 第一次开 ME 终端" 流程：扫 OrderManager 中 owner 是该玩家的订单，按需重建 `SmartCraftRuntimeSession` 并 `register`。
- [ ] **回归测试**：单元测试覆盖序列化往返；mock world 模拟重启 + 验证非终态 task 回到 PENDING + 终态 task 保持原状态。

### 🔭 后续可优化（独立）

- [ ] **G5/G6/G7 backoff 表外置到 Config**：当前 backoff 表是源码内 `private static final long[]`，未来允许玩家在 forge config 中覆盖（`config/AE2IntelligentScheduling.cfg`）
- [ ] **GUI 显示自动重试预算剩余**：tab 上加 hover tooltip 显示当前 task 的 `(planRetries + submitRetries + linkCancelRetries) attempts/max`，让玩家明确这个 task 是首次失败还是已经被 cover 过 N 次
- [ ] **诊断也覆盖 link cancel reason**：AE2 cancel link 的具体原因（pattern lookup miss / cluster destroyed / etc.）目前没暴露在 blockingReason，可扩展
- [ ] **G7 单一间隔 → 渐进 backoff**：当前 G7 是固定 600s 间隔。未来可改成"第 1 次 5min / 第 2 次 30min / 第 3 次 2h"，减少长期 FAILED 订单的重试噪声

---

## 2026-04-29（v0.1.7.5 重试失败按钮越出 GUI 右框）已完成

> 截图 `2026-04-29_12.34.07.png` 显示 `GuiSmartCraftStatus` 右下角"重试失败"按钮整个绘制到了 GUI 框右边界外侧。

### ✨ 已完成

- [x] **`SmartCraftConfirmButtonLayout.retryPosition`**：right-edge 锚点从 `xSize - 6`（→ 232）改为新常量 `STATUS_BUTTON_RIGHT_USABLE = 213`，与 AE2 自身 cancel 按钮 (`CANCEL_LEFT_OFFSET=163 + CANCEL_WIDTH=50`) 对齐。AE2 craftingcpu.png 的 [218..230] 是 scrollbar 列、[230..238] 是边框，旧坐标整个跨进了这两块视觉区。
- [x] **2 个回归测试**：`retry_button_right_edge_clears_ae2_scrollbar_and_frame`（断言 retry right-edge ≤ 213）+ `cancel_and_retry_share_the_status_row_and_do_not_overlap`（断言两按钮同行不重叠）
- [x] **modVersion 0.1.7.4 → 0.1.7.5**，jar 已重新打包（`ae2intelligentscheduling-0.1.7.5.jar` 151 KB）

### 🚧 后续可优化

- [ ] **整张 craftingcpu.png 可用区域常量化**：把 `STATUS_BUTTON_RIGHT_USABLE` / scrollbar 列范围 / 边框宽度抽到 `SmartCraftStatusLayout` 层，避免散落 magic number

---

## 2026-04-29（v0.1.7.4 submit 失败诊断 + 详细 blockingReason）已完成

> 用户反馈"重试失败 FAILED 订单仍然下不了单"。根因不在 mod 端 retry 逻辑（代码路径正确），而是 AE2 的 `submitJob` 因 CPU 容量不足 / busy 持续 reject。旧版只把任务标 FAILED 留通用文案"Failed to submit AE2 crafting job"，玩家无法判断是 mod bug 还是 AE2 限制。

### ✨ 已完成

- [x] **`SmartCraftRuntimeCoordinator.diagnoseSubmitFailure`** 新增静态 helper：根据 `plannedJob.getByteTotal()` + 网络中所有 CPU 的 `getAvailableStorage` / `isBusy` 给出 root-cause hint（"no idle CPU large enough" / "chosen CPU became busy" / "chosen CPU too small" / 兜底 "AE2 rejected ..."）
- [x] **dispatch 阶段接入诊断**：submit 返回 null 时把 hint 写入 `task.blockingReason()`，玩家可在 GUI tooltip 直接看到失败原因
- [x] **服务端 WARN 日志**：每次 submit reject 输出完整 CPU 快照（每台 CPU 的 busy/avail/coProcessors 列表 + chosen CPU 状态 + byteTotal）方便服主排障
- [x] **回归测试** `submit_failure_records_diagnostic_blocking_reason`：jobSubmitter 始终返回 null，断言 task FAILED + blockingReason 以诊断前缀开头 + 含具体 CPU 名/idle 计数
- [x] modVersion 0.1.7.3 → 0.1.7.4
- [x] **本地 GTNH manifest 缓存方案** `D:\Code\.gtnh-manifests\`：解决大陆环境 GitHub raw 经常 reset 导致 elytra-conventions plugin 启动卡死。包括 fetch / inject / 一键 gradlew 包装脚本和 README。本项目 `gradle.properties` 显式声明 `elytra.manifest.version = 2.8.4` 匹配 GTNH 当前 stable
- [x] **测试 + 打包**：11 个 RuntimeCoordinator 测试全过，`ae2intelligentscheduling-0.1.7.4.jar` (151 KB) 已生成

### 🚧 后续可优化

- [ ] **诊断 hint 多语言化**：当前 hint 是英文，未来可走 lang key + 客户端在 tooltip 处替换
- [ ] **诊断也覆盖 link.isCanceled() 路径**：目前 link 被 AE2 主动 cancel 走 `CRAFTING_LINK_CANCELLED_REASON` 通用文案，可扩展为读取 cancel reason

---

## 2026-04-29（v0.1.7.3 按钮回归 AE2 风格文字按钮 + Start 行旁右侧 GUI 外）已完成

> 用户反馈 v0.1.7.2 图标太小看不全 4 字，且顶部 ear 区覆盖了 AE2 的 switchDisplayMode tab 按钮。v0.1.7.3 改回 vanilla GuiButton 52x20，挂在 AE2 Start 行同高的 GUI 框外右侧（CraftConfirm 多一个智能合成在上方一槽）。

### ✨ 已完成

- [x] **`SmartCraftConfirmButtonLayout`**：earSlot 改成从底部 Start 行向上索引；常量改为 `BUTTON_WIDTH=52, BUTTON_HEIGHT=20, EAR_RIGHT_GAP=4, EAR_BOTTOM_OFFSET=25`
- [x] **`SmartCraftConfirmGuiEventHandler`**：回归 vanilla `GuiButton`，删除 `drawEarButtonTooltip` 调用
- [x] **删除 `SmartCraftEarIconButton`** 文件（v0.1.7.2 引入的自定义按钮类不再需要）
- [x] **5 个 ButtonLayout 测试**全部改写为新坐标
- [x] modVersion 0.1.7.2 → 0.1.7.3
- [x] 旧 0.1.7.2 jar 已清理

---

## 2026-04-29（v0.1.7.2 智能合成/查看调度按钮改 16x16 ear-icon）已完成

> 用户反馈按钮太大覆盖 AE 文字。选定方案：16x16 图标按钮挂在 AE2 GUI 框外右侧 ear 区，hover 显示 tooltip。完全不占 AE GUI 内部空间。

### ✨ 已完成

- [x] **`SmartCraftEarIconButton`** 新组件：16x16 单字符图标按钮
- [x] **`SmartCraftConfirmButtonLayout`** 重构：所有 AE 注入按钮位置改为 ear 区坐标（GUI 框外右侧 +2 px）
- [x] **`SmartCraftConfirmGuiEventHandler`** 创建用 SmartCraftEarIconButton + 加 hover tooltip 渲染
- [x] **5 个 ButtonLayout 测试**改写：验证 ear 区布局、永不进入 AE GUI 内部、垂直堆叠正确
- [x] modVersion 0.1.7 → 0.1.7.2（合并 v0.1.7.1 不单独发版）

---

## 2026-04-29（v0.1.7.1 FAILED 订单保留 + advanceLayers bugfix）已完成

> v0.1.7 的"终态即消失"让 FAILED 订单也立即消失，玩家无 retry 入口。修复让 FAILED/PAUSED 永久保留，玩家可手动 retry 或 cancel 丢弃。同时修复了一个预先存在的 advanceLayers bug（layer 全 FAILED 被错误标 COMPLETED）。

### ✨ 已完成

- [x] **`advanceLayers` 修复**：只在所有 task 都 DONE 时才标 COMPLETED；其他终态混合让 applyLayerStatus 走 PAUSED
- [x] **tick 终态检测条件收紧**：FAILED/PAUSED 不再触发 auto-remove（CANCELLED/COMPLETED 仍走 1-tick 延迟删除）
- [x] **FAILED retention 测试**：12 tick 后订单仍在 manager + 状态 PAUSED + session 存活
- [x] 修正 v0.1.7 ToDOLIST 中的"FAILED 订单 retry 入口"项

### 🚧 后续可优化

- [ ] **PAUSED 订单 max-age 配置**：玩家长期不操作可自动 CANCELLED 防 list 膨胀
- [ ] **GUI 上明确区分 PAUSED vs 其他状态**：当前 tabs widget 用统一状态点，PAUSED 需要更醒目的"⚠ retry 我"视觉

---

## 2026-04-29（v0.1.7 多玩家多订单标签页 UI）已完成

> 用户提出"不同玩家不同订单，UI 顶部加标签页按时间排序，点 tab 切换查看不同清单和格子"。决策：全员可见 + 终态即消失 + 顶部 ear + 物品图标+状态点 + server 全 push。

### ✨ 已完成

- [x] **协议**：`SyncSmartCraftOrderListPacket` 全量 list 包 + `ownerName` 字段
- [x] **Server**：`OrderManager.snapshot()` + `RuntimeCoordinator.sessionsView()` + `Session.ownerName()`
- [x] **终态延迟 1 tick 删除**：tick mark + 下一 tick 删，OrderSync 有机会推终态包
- [x] **OrderSyncService.syncListTo(player)**：3 个 packet handler 都接入
- [x] **OverlayRenderer 多订单改造**：orders map + currentOrderId + reconcile + selectOrder + tabOrders + fallback
- [x] **`SmartCraftOrderTabsWidget`**：物品图标 + 状态点 + hover tooltip + 滚动箭头
- [x] **GuiSmartCraftStatus 集成**：tab 区 ear 在 body 上方 + tabScroll + 命中优先级 + sendRefresh 改用 RequestOrderStatusPacket
- [x] **17 个测试**：snapshot 顺序 / 防御副本 / 终态延迟 / retry 清除 mark / tab 命中各种场景

### 🚧 后续可优化

- [ ] **隐私控制配置项**：可选只看自己 / 只看自己+OP / 全员可见三档（当前固定全员可见）
- [ ] **失败订单 retry 入口**：终态即消失意味着 FAILED 订单也立即从 list 移除，玩家无法在 GUI 中 retry。需要给 FAILED 订单单独保留更长时间，或加历史栏目
- [ ] **`SmartCraftScheduleListWidget` 的 RenderItem lazy 化**：与 tabs widget 一致，便于未来加 hit-test 单测
- [ ] **list packet 大小自适应**：当并发订单 > 30 时切换为 metadata-only + 按需 detail，避免 packet 超过 1MB
- [ ] **tab 滚动支持鼠标滚轮**：当前只能点箭头滚动，hover 在 tab strip 上时滚轮应横向滚动 tabs

---

## 2026-04-29（v0.1.6 Cancel/Retry 按钮 bugfix）已完成

> v0.1.4 GUI 重构误删 `actionPerformed` 方法导致 cancel + retry 按钮全部失效；同时发现 v0.1.3 引入 RETRY_FAILED 时遗漏了客户端 `sendRetry()` 触发器。

### ✨ 已完成

- [x] **`SmartCraftOverlayRenderer.sendRetry()`** 新增（v0.1.3 遗漏的 client trigger）
- [x] **`GuiSmartCraftStatus.actionPerformed`** 重新加上（处理 cancel + shift 软取消 + retry）
- [x] `button.enabled` 守卫防止键盘 enter 触发灰按钮

### 🚧 后续可优化

- [ ] **GUI 事件路由集成测试**：避免再发生"按钮被点击但无人响应"。1.7.10 GuiScreen 难以单测，可考虑用 OpenComputers 风格 reflection 构造 + 调 `actionPerformed(mockButton)` 断言 packet 发出

---

## 2026-04-29（v0.1.5 H1 小量节点合并）已完成

> 用户反馈"几千数量的小中间产物占据整个 CPU 浪费资源"。按 OrderScale 设阶梯阈值：SMALL ≥ 1M / MEDIUM ≥ 5M / LARGE ≥ 10M 才发独立 task；不到的合并到上层 AE2 plan。

### ✨ 已完成

- [x] **`SmartCraftMergeThreshold`** 阈值表 + Config 读取
- [x] **`SmartCraftOrderBuilder`** 加合并逻辑（依赖注入式 resolver，根节点保护）
- [x] **`Config.java`** 新增 mergeThresholdSmall / Medium / Large 配置项（0 = 关闭）
- [x] **5 个 H1 测试** 覆盖叶子合并 / 根保护 / 大小同级 / 跨层透传 / 阈值 0 关闭
- [x] **现有 3 个 OrderBuilder 测试** 改用 `withMergingDisabled()` 保持原语义

### 🚧 后续可优化（视用户反馈）

- [ ] **H2 CPU 容量感知 dispatch**：合并后大 task 撞小 storage CPU 仍是问题。dispatch 时按 task amount × CPU storage 匹配
- [ ] **H3 合并子树深度限制**：极深的小量子树合并到根可能让 AE2 plan 超时；可加 maxMergeDepth 上限
- [ ] **H4 合并诊断改善**：父节点 plan 失败时，标注失败可能源于哪个被合并的子树（用 blockingReason 附带 hint）

---

## 2026-04-29（v0.1.4 UI 清单区域重做）已完成

> 用户反馈"清单区域太丑"。重做为自定义 row widget + 物品图标。

### ✨ 已完成

- [x] **新组件 `SmartCraftScheduleListWidget`**：16x16 物品图标 + 状态色点 + displayName×amount + 短状态文本 + split 进度 + layer 分隔线 + hover/selected 高亮
- [x] **`GuiSmartCraftStatus` 接入**：drawScreen 调用 widget；mouseClicked 命中测试；删除 syncScheduleButtons / removeScheduleButtons
- [x] **删除 dead code**：`SmartCraftScheduleButtonLayout` + 对应测试
- [x] **统一行高常量**：`LIST_ROW_HEIGHT` 来源单一化为 widget 常量

### 🚧 后续可优化（视用户反馈）

- [ ] **清单滚动条用 AE2 vanilla 风格**：当前 schedule scrollbar 是自实现的灰色块，可换为 AE2 cluster 滚动条同款贴图，进一步提升一致性
- [ ] **行 hover 时 tooltip 显示完整 task 详情**：当前需要点击进 detail 面板，hover tooltip 可一键看到（amount / blocking reason / CPU 等）
- [ ] **多选 / 批量操作**：shift-click 选中范围内 task，右键菜单批量 cancel / retry

---

## 2026-04-29（v0.1.3 调度健壮性增强）已完成

> A 推测式 Plan 受 AE2 API 限制不可行（无库存 override 参数）。改做 G1-G4 替代优化全部落地。

### ✨ 已完成

- [x] **G1 Plan 失败自动重试**（指数退避 5/10/20 tick，默认 3 次）
- [x] **G2 WAITING_CPU 超时重 plan**（默认 10 分钟，库存漂移防御）
- [x] **G3 Plan 超时可配置**（默认 60 秒，范围 5-600）
- [x] **G4 计数器周期性 log**（默认 5 分钟一行 INFO）

### 🚧 推到 v0.2 或更后

- [ ] **A 推测式 Plan**：需 mixin AE2 内部库存查询（`IStorageGrid.getItemInventory()`），让 plan 时虚拟看到 RUNNING task 的预期产出。工作量 1-2 天，风险高
- [ ] **B CPU 容量匹配**：需 `ICraftingJob.getByteTotal()` 之类的 plan 复杂度 metric
- [ ] **D 跨订单去重**：架构性改动
- [ ] **G 分支放弃**：视用户反馈再做

---

## 2026-04-28（v0.1.2 调度策略增强）已完成

> 用户优先级：做 C, E, F，A 之后做，其它放弃。本轮全部落地。

### ✨ 已完成

- [x] **C 关键路径优先**
  - **修复**：`dispatchReadyTasks` 入口 DFS 算每个 task 的"最长下游依赖链长度"，submit / plan 候选都按 CPL 降序排序
  - **测试**：`critical_path_first_when_only_one_cpu_is_idle`（长链 root vs 短链 leaf，1 idle CPU，长链先抢）

- [x] **E 等待时长反饥饿**
  - **修复**：`TaskExecution.waitingCpuSinceTick`（首次进 WAITING_CPU stamp，幂等）；submit candidate 排序 CPL desc → wait age asc
  - **测试**：`waiting_cpu_age_breaks_ties_when_priorities_match`（两等价 task，早等的先拿 CPU）

- [x] **F 软取消**
  - **修复**：`OrderManager.cancelGracefully` + `Coordinator.cancelGracefully`（保留 RUNNING / VERIFYING_OUTPUT，cancel 其余）；新协议 `Action.CANCEL_ORDER_SOFT`；UI 入口 = **Shift+点击 Cancel 按钮**
  - **测试**：3 个用例覆盖正常 / 退化 / planning future 取消

### ⏳ 推到 v0.2 或更后

- [ ] **A 推测式 Plan / Plan Ahead** — 让 layer N+1 的 plan 在 layer N RUNNING 时开始，需要 AE2 库存 hint API（不存在）
- [ ] **B CPU 容量匹配** — 大 plan 派大 CPU，需 `ICraftingJob.getByteTotal()` 之类的 metric
- [ ] **D 跨订单去重** — 架构性改动，涉及订单所有权 / 公平分配
- [ ] **G 分支放弃** — 视用户反馈再做

---

## 2026-04-28（v0.1.1 跨 layer 并行后续）调度风险清单 — 待修复

> 由跨 layer 并行调度引入或暴露的潜在调度问题。按"严重度 × 触发概率"排序，从上到下依次修复。

### 🔴 P0 — 已修复✅

- [x] **#1 `clearExecution` 取消 in-flight planning future**
  - **修复**：`SmartCraftRuntimeSession.clearExecution` 现在会 `cancel(true)` 未完成的 planning future，防止 AE2 planner 线程泄漏及后续回写到幽灵 session
  - **测试**：`cancel_during_planning_cancels_planning_future` — cancel order 时 future.cancel 被调。通过

- [x] **#2 `SUBMITTING` 超时转 FAILED**
  - **修复**：
    - `TaskExecution` 加 `submittedAtTick: long` 字段
    - `Coordinator` 加 `tickCounter` 字段，每 `tick()` ++，避免外部注入 LongSupplier（保持构造函数签名不变）
    - `reconcileTaskExecution` 检查 `tickCounter - execution.submittedAtTick() > MAX_PLANNING_TICKS`（1200 tick = 60s），超时则 `clearExecution` + 转 FAILED + reason="AE2 planning did not complete within timeout"
  - **测试**：`submitting_times_out_after_max_planning_ticks` — 1201 tick 后任务 FAILED，future 被 cancel。通过

### 🟡 P1 — 已修复✅

- [x] **#3 同节点 split 任务串行化 plan**
  - **修复**：`SmartCraftRuntimeCoordinator.dispatchReadyTasks` 在初始扫描阶段收集所有 SUBMITTING 状态 task 的 `requestKey.id()` 到 `planningInFlightRequestKeys: Set<String>`；任何 PENDING task 在调用 jobPlanner 之前检查该 set，存在同 requestKey 的 并发 plan 则 skip。成功调用 `trackPlanning` 后也将 requestKey 加入 set，防止同 tick 多个 sibling split 同时进入 SUBMITTING
  - **测试**：
    - `splits_of_same_request_key_serialize_their_planning`：4 个 split（1 SUBMITTING + 3 PENDING）
    - `splits_of_different_request_keys_plan_in_parallel`：3 个不同节点（3 SUBMITTING）
  - **代价**：同节点拆出 N 个 split 的 plan 阶段串行化（total_plan_time = N × single_plan_time）；但 submit/run 仍并行。鲁棒性优于 plan 吞吐
  - **发现**：修复后两个旧 RuntimeCoordinator 测试压不过（`planning_starts_for_all_tasks_even_when_only_one_cpu_is_idle` 和 `mixed_failed_and_waiting_cpu_must_not_pause_the_order`），原因是他们都用了 `new FakeRequestKey("processor")` 让多个 task 共享 requestKey。调整 helper 让 `requestKey.id() = taskId`（该 helper 的语义本来就是独立 task）

- [x] **#4 多层重试 依赖链接力测试覆盖**
  - **修复**：`retry_propagates_progress_through_dependency_chain` 覆盖：
    - 5 层 `[DONE, DONE, FAILED, PENDING, PENDING]` 场景
    - 验证 PAUSED 状态下不调度
    - 验证 retry 后 l2 立即 SUBMITTING（deps 已 DONE）
    - 验证 l3 在 l2 未 DONE 时仍 PENDING
    - 验证 l2 转 DONE 后 l3 下一 tick 自动 SUBMITTING，l4 仍 PENDING
  - **余下未补**：跨订单 CPU 并发公平性测试 — mock CPU 不会 submit 后自动改 busy，难写 meaningful。推为 P2

### 🟢 P2 — 已知限制 / 文档化即可，暂不修

- [ ] **#5 大订单（≥ 2000 task）每 tick 全表扫描** — 等用户反馈卡 TPS 再做事件驱动 ready 集合
- [ ] **#11 跨订单 CPU 抢占公平性测试** — 需要 mock CPU 动态改 busy 才能 meaningful，现阶段 mock 代价偏高。可考虑接 AE2 真实 实例调起游戏内集成测试
- [ ] **#6 WAITING_CPU UI 状态抖动** — 多 task 抢少 CPU 时状态闪烁，加 200ms 去抖。视觉问题不影响正确性
- [ ] **#7 跨订单合成同物品不去重** — AE2 vanilla 行为，文档化
- [ ] **#8 服务器重启不恢复运行中订单** — 已在 v0.1.0 release notes 文档化
- [ ] **#9 玩家拆 CPU 块导致 link cancelled 的反应** — 应该走 `link.isCanceled()` 转 FAILED，需实测确认 AE2 行为
- [ ] **#10 跨订单 CPU 抢占公平性** — 当前先注册先得，可能让长尾订单饿死后开订单。可选 round-robin 改进

---

## 2026-04-28（续）本轮完成
- [x] **建单失败时撤销 track，避免孤儿订单**：
  - A3（actionSource null）/ A4（session null）两条 fail 分支都已 `track(order)`，但旧代码不清理；A4 还会继续 `sync` 到客户端，导致"查看调度"按钮显示但点击无反应
  - 现在两路径均 `orderManager.remove(trackedOrderId)`，A4 不再 sync

## 2026-04-28 本轮完成
- [x] **下单/调度核心 bug 修复**：
  - `SmartCraftRequesterBridge.injectCraftedItems` 改为 `return items`（之前 `return null` 导致产物被 AE2 视为"requester 全收"而消失到虚空，stockVerifier 永远看不到 → 任务卡死 VERIFYING_OUTPUT）
  - `applyLayerStatus` 增加 `hasPlannable`（PENDING/QUEUED）轴线，PAUSED 仅在"无 active 无 waiting 无 plannable + 有 failed"时才设置；修复 `[FAILED, WAITING_CPU]` 组合误判 PAUSED 导致 WAITING_CPU 任务永久卡死的 bug
- [x] **重试失败 / 取消按钮 enabled 控制**：无 FAILED/CANCELLED 时重试按钮灰；所有任务终态时取消按钮灰
- [x] 新增 2 个回归测试覆盖上述两个 bug

## 2026-04-27 本轮完成
- [x] **tooltip 重构 + AE2 合成状态页注入智能调度补充提示**：
  - `buildTaskTooltip` 重构为三段式（vanilla item tooltip + 智能调度子标题 + 状态明细），全部用 `EnumChatFormatting` 着色
  - 新增 `findMatchingTask(ItemStack)` / `buildAe2SupplementHintLines(...)` / `drawSupplementaryTooltip(...)` public API
  - `SmartCraftConfirmGuiEventHandler.onDrawScreen` 在 AE2 `GuiCraftingStatus` 上读 `getHoveredStack()`，匹配到任务后在 `mouseY+60` 处渲染补充 tooltip
  - 新增 lang key `smartCraftHintHeader`（中文【智能调度】/ 英文 [Smart Craft]）
- [x] **格子区视觉对齐 AE2 原版**：6×3 单元格 + 半字号居中文字 + AE2 原版滚动条坐标 (x=218, y=19, h=137, w=12) + AE2 原版 `GuiText` / `GuiColors`（"合成中" / "计划中" / "已存"）
- [x] **调度核心重构**：
  - `cancel(...)` 不再删除 session（修复"取消后重试卡死"）
  - `tick(...)` 仅在订单从 manager 中移除时清理 session（保留 CANCELLED/PAUSED/COMPLETED 终态供重试）
  - `retryFailedTasks(...)` 复活 FAILED + CANCELLED 任务为 PENDING（旧实现只复活 FAILED）
  - `retryFailed(...)` 同时调 `session.clearExecution(task)` 清掉 stale execution
  - `reconcileTaskExecution(...)` 入口先判 `task.isTerminal()`（修复"刚 CANCELLED 又被 link.isCanceled() 改写为 FAILED"）
  - `VERIFYING_OUTPUT → DONE` 路径补 `clearExecution` 释放 stockBaseline
  - `dispatchReadyTasks(...)` 取消规划阶段预占 CPU（修复"1 个 CPU 时其它任务伪 WAITING_CPU"的 starvation）
- [x] 新增 3 个回归测试：cancel→retry 复活、终态保护、并行规划不被 CPU 数限制
- [x] 顶部格子区扩到 6 行（`GRID_ROWS = 6`），调度列表硬封顶 4 任务行 + 1 总览行，整体区域比例从 “小格大列表” 调整为 “大格小列表”
- [x] 为顶部格子区和调度列表区各增加可见滚动条（轨道 + 滑块），滑块高度按比例缩放、悬停亮显，支持点击轨道跳转 / 按住滑块平滑拖动
- [x] 鼠标滚轮按光标所在区域分流：在格子区滚动 `OVERLAY.scroll(...)`、在调度区滚动 `scheduleScroll`
- [x] 任务详情 / CPU 详情面板适配 6 行新尺寸：`drawTaskDetailGrid` 重排（图标 + 显示名、分隔线、层级 / 拆分 / 数量 / CPU / 阻塞原因自动换行）、`drawCpuDetailGrid` 进度条下移 + 新增 `数量(剩余)` 行
- [x] 修复调度清单分层任务按钮不可点击：去掉 `enabled` 上的 CPU 分配门控，新增 `selectedTaskIndex` 与 `drawTaskDetailGrid`，无 CPU 走任务详情、有 CPU 走 CPU 详情
- [x] 状态页实心面板继续向下延伸至底部按钮上方：覆盖原本调度列表与取消/重试按钮间残留的 3 行 AE2 空格子
- [x] 将 `智能合成` 与 `查看调度` 按钮宽度从 `68` 收窄到 `52`，与 AE2 自身 `Cancel / Start` 按钮对齐，不再挤占 AE2 按钮空间
- [x] `查看调度` 按钮按屏幕种类拆分为三套位置：AE2 合成确认页中段空白行、终端右下、AE2 合成 CPU 状态页顶部 `状态` 标题右侧；旧 API 标记 `@Deprecated` 转发
- [x] 在 AE2 `GuiCraftingStatus` (合成 CPU 状态页) 顶部 `状态` 文字右侧注入 `查看调度` 按钮，按钮宽 52、高 14，避开 AE2 顶部搜索框 / 找寻按钮区
- [x] 收紧智能合成状态页顶部信息行：量级 / 状态 / 任务数 / 数量 现在贴近调度区域顶部，去除中段大块空白
- [x] 调度列表区域背景改为实心面板：在 AE2 长背景之上叠加 `0xFFC6C6C6` 实心矩形 + `0xFF8B8B8B` 边框，去除原本 AE2 cell 纹理透出的格子条纹
- [x] 调整 `ACTION_AREA_HEIGHT` 与 AE2 `craftingcpu.png` 底部 51px 段对齐，确保调度列表最后一行不再越过 AE2 底部边框
- [x] 将智能合成状态页改为 AE2 风格长 UI：调度清单绘制在同一个 GUI 内，支持清单区域鼠标滚轮滚动，并恢复上方单元格物品图标显示
- [x] 在智能合成状态页单元格区域下方加入调度清单：顶部 `总调度` 按钮回到总览，每个任务分片按钮可切换上方单元格区域显示对应 AE2 CPU 合成详情
- [x] 修复开启智能合成后状态页按 ESC 退出又被同步包自动弹回的问题，改为通过 AE2 界面 `查看调度` 按钮手动返回智能合成 UI
- [x] 排查并收束智能调度 UI 流程混乱：AE2 确认页仅保留启动入口，运行态统一进入 `GuiSmartCraftStatus`，终端 `查看调度` 按钮恢复可用

## 当前计划
- [ ] 暂无

## 未来想法
- [ ] 评估是否需要支持运行中智能订单的跨重启恢复
- [ ] 评估是否需要提供整棵合成树的可视化展示
- [ ] 评估是否需要支持玩家限制可用 CPU 范围
- [ ] 后续可选：状态页任务列表图标化或整棵合成树可视化

## 已完成
- [x] 明确新模组名称为 `AE2-IntelligentScheduling`
- [x] 完成智能合成整体设计并落地为 spec 与 implementation plan
- [x] 完成 GTNH 模组脚手架、AE2 依赖接入与基础 `compileJava` 验证
- [x] 完成智能合成规划模型、订单量级判定与 `SMALL / MEDIUM / LARGE` 拆分规则
- [x] 完成 AE2 合成树快照分析、智能订单构建与分层队列基础实现
- [x] 完成运行态 `SmartCraftOrderManager`、`SmartCraftScheduler`、`SmartCraftRequesterBridge` 与服务端协调器骨架
- [x] 完成 AE2 `GuiCraftConfirm` 中的 `智能合成` 入口按钮与预览/登记链路
- [x] 打通真实 `ICraftingJob` 计算、提交、`ICraftingLink` 轮询与取消/重试链路
- [x] 将智能合成状态页改为 AE2 风格 GUI，并保留 AE2 合成确认页中的 `智能合成` 入口按钮
- [x] 将工作树 `gradlew` / `gradlew.bat` 同步为只使用 `JAVA_HOME` 中 `Zulu21` 的启动约定
- [x] 完成智能合成分析、缺口拆分、分层调度与自动下单的完整闭环
- [x] 补强状态 GUI 的实时刷新与更细粒度任务反馈
- [x] 完善 AE2 状态页中的任务信息展示，当前保留图标化或树状视图为后续可选项
- [x] 生成客户端测试用 `ae2intelligentscheduling-0.1.0-dev.jar`
- [x] 将 `智能合成` 入口改为 Forge GUI 事件注入，按钮位于 AE2 合成确认页右下、靠近原 `Start` 区域
- [x] 修复智能合成状态页 `\uXXXX` 字面量乱码，并收紧顶部摘要文本裁剪以避免布局错乱
- [x] 修复 AE2Things 合成确认页中 NEI `GuiContainerManager` 为空导致的 `Ticking screen` 崩溃

## 暂缓 / 拒绝
- 暂缓：第一版不做跨重启运行中订单的无损恢复，原因是需要持久化整棵运行态任务树并重新绑定 AE2 运行中 job，复杂度过高
## 2026-04-22 本轮补充
- [x] 为智能合成状态页同步 CPU 名称与执行阶段
- [x] 修复项目在只使用 `Zulu21` 时仍被 GTNH convention 默认 `Zulu17` toolchain 阻塞的问题
- [x] 为本模组编译、测试和 injected tags 任务定向 `Zulu21`
- [x] 为智能合成状态页加入每 20 tick 主动刷新
- [x] 主动刷新会保留 runtime session 中的 CPU 名称与执行阶段信息
- [x] 将智能合成状态页背景、尺寸、颜色映射对齐 AE2 `GuiCraftingCPU`
- [x] 按需求将状态页背景切回 `craftingreport.png`，并改为上方正常合成摘要、下方智能队列
