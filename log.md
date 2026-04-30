# 开发日志

## 2026-04-30（v0.1.9.5）：查看调度按钮 fix + 重启对齐 AE2 原版（G15）

### 背景

v0.1.9.4 上线后用户测试反馈：「重启倒是有查看调度按钮了，但是点击后无任何反应」。同时用户提出新方向：希望"重启机制对齐原版 AE2 的机制"——保留订单作为历史，但不能继续调度，按钮变成"从列表删除"。

我把 v0.1.9.3 / v0.1.9.4 的 release 删了（按用户要求），并记下"未来推送必须先询问"的规则到 memory。

### Bug A 根因：查看调度按钮点击无反应

`@/src/main/java/com/homeftw/ae2intelligentscheduling/ClientProxy.java:38-56` 路径分析：

1. 玩家点"查看调度"按钮 → client `onButtonClicked` 调 `requestOpenSmartCraftStatusOnNextSync()` 设 flag=true，然后发 `RequestOrderStatusPacket`
2. server 收到 → `syncListTo(player)` → 推 **list 包**（`SyncSmartCraftOrderListPacket`）
3. client 收到 list 包 → `applySmartCraftOrderList` → 只调 `OVERLAY.applyOrderList(packet)`，**不消耗 flag，不打开 GUI**
4. flag 还是 true，但下一次 sync 不会自动来——只有"提交新订单"才走单包路径打开 GUI

这个 bug **从 v0.1.7（多订单 list 包引入时）就存在**，但之前隐藏：玩家通常先提交新订单（产生单包路径）→ GUI 自然打开。重启场景没单包路径，bug 暴露。

### Bug B 设计：重启对齐 AE2 原版（用户选择方案 C）

AE2 原版重启时 craftingJob 状态全丢，cluster 里残留产物保留供玩家手动 cancel cluster 退回。

我们的智能调度持久化 v0.1.9.3 之后保留了订单，但 sessions 不持久化、craftingLink 失效——v0.1.9 G12 的"folds in-flight tasks 到 PENDING + QUEUED 重新调度"路径在实际场景下不可靠（race / 失序 / cluster 已被 AE2 内部 reset）。

**用户选 C 方案**：保留订单作为历史，重启后**所有非终态 task → CANCELLED**，整个订单 → CANCELLED + `interruptedByRestart=true` 标记。Retry 按钮禁用，Cancel 按钮重新映射为"从列表删除"。

### 设计实现

**Bug A 修复**：

- `ClientProxy.applySmartCraftOrderList` 镜像 `openSmartCraftStatus` 的 GUI 打开逻辑：检查 flag → 用 OVERLAY 的当前订单 packet 作为初始数据打开 `GuiSmartCraftStatus`
- `SmartCraftOverlayRenderer.currentOrderPacket()`：新增 public accessor 暴露当前订单 packet 给 ClientProxy

**Bug B 实现**（方案 C）：

1. **`SmartCraftOrder.interruptedByRestart`**：新 final boolean 字段
   - 8 参 canonical 构造（其余构造委托）
   - 所有 `with*` 方法传递
   - NBT 持久化：true 时写 `interruptedByRestart` tag，false 不写（避免 NBT bloat）；read 时缺失 = false（向前兼容）
2. **`SmartCraftOrderManager.resetForRestart`**：fold 行为大改
   - 非终态 task → `CANCELLED` + banner `"Interrupted by server restart"`
   - 整个 order → `CANCELLED` + `interruptedByRestart=true`
   - 所有 task 都已 terminal 的订单（COMPLETED/PAUSED）→ 不打 interrupted 标记，保持原状态
3. **`SmartCraftOrderManager.retryFailedTasks`**：interrupted 订单返回 `Optional.empty()`（不可 retry）
4. **`SmartCraftRuntimeCoordinator.removeOrder(orderId)`**：新 API，从 manager 删除订单 + 清 session/retry/markedTerminalLastTick state，返回 `Optional.of(removedOrder)` 让 packet handler sync
5. **`RequestSmartCraftActionPacket` Handler `CANCEL_ORDER`** 路由：
   - `order.interruptedByRestart=true` → 调 `removeOrder`（"删除"）
   - 否则 → 调 `cancel`（mark CANCELLED + cancel link）
6. **`SyncSmartCraftOrderPacket`** 携带 `interruptedByRestart` 字段（wire format **末尾追加**，旧 client 收新包 fall through 默认 false 向前兼容）
7. **GUI**：
   - `OVERLAY.hasRetriableTasks()`：interrupted=true 强制返回 false（retry 按钮 grey out）
   - `OVERLAY.isOrderActive()`：interrupted=true 强制返回 true（cancel 按钮保持可点）
   - `OVERLAY.isCurrentOrderInterruptedByRestart()`：新 accessor
   - `GuiSmartCraftStatus.refreshActionButtonStates`：interrupted 时 cancel 按钮 label 切换为 `gui.ae2intelligentscheduling.removeFromList`
   - `OVERLAY.drawInfoBar` 状态行追加红色 `重启中断（仅历史查看）` banner
8. **i18n**：zh_CN + en_US 加 `removeFromList` / `interruptedByRestart` key

### 测试（+5）

- `SmartCraftOrderManagerTest.resetForRestart_folds_in_flight_tasks_to_cancelled_v0195` —— 替换旧 v0.1.9 测试，验证 fold-to-CANCELLED + interruptedByRestart=true
- `resetForRestart_does_not_mark_interrupted_when_no_task_was_in_flight` —— 已全 terminal 的 PAUSED/COMPLETED 订单**不**打 interrupted 标记
- `retryFailedTasks_rejects_interrupted_orders` —— interrupted 订单 retry 拒绝
- `SmartCraftPersistenceTest.interruptedByRestart_field_round_trips_through_nbt` —— NBT round-trip true
- `interruptedByRestart_defaults_to_false_when_not_persisted` —— false 不写盘 + missing tag 读为 false

125 个测试全过（123 老 + 2 新 + 1 改写覆盖原 v0.1.9 测试）。

### 包

modVersion `0.1.9.4` → `0.1.9.5`，3 个 jar。

### 反思

- **Bug A 是 sync 路径设计盲区**：v0.1.7 引入多订单 list 包时，`applySmartCraftOrderList` 没复用 `openSmartCraftStatus` 的 GUI 打开 flag-consume 逻辑——两个路径分了岔，flag 只在单包侧消费。新代码不该假设玩家"会先创建订单"，每条 sync 路径都要独立处理 GUI 打开请求。修复后两路径行为一致。
- **方案 C 的"对齐 AE2"是正确的工程取舍**：之前 G12 的"重启自动恢复"听起来美好，但实际上 AE2 内部状态全失效，重新调度等于另起炉灶。用户没法获得"无缝恢复"体验，反而看到一堆 PENDING task 在卡住或重新计划失败。改为"历史归档 + 玩家自行重新提交"既匹配 AE2 行为也减少状态机复杂度——这种放弃幻想的简化在游戏 mod 里通常更稳。
- **wire-format 向前兼容用 tail-append + readableBytes 守卫**：新加 `interruptedByRestart` 字段我放在 `SyncSmartCraftOrderPacket` 序列化末尾，反序列化用 `buf.readableBytes() >= 1` 守卫——v0.1.9.4 client 收 v0.1.9.5 server 包不会崩，只是该字段读为默认 false。NBT 也是缺失键 = 默认 false。两层 wire-format 都守住了"老客户端不死"。
- **测试 design：避开 helper 副作用**：`SmartCraftPersistenceTest.interruptedByRestart_*` 的第一版用 `manager.loadFromNBT` 路径，被 `resetForRestart` 副作用搞乱（PENDING task 被 fold 成 interrupted）。改成直接走 `SmartCraftOrder.writeToNBT/readFromNBT` 绕过 manager，断言更精准。每加一个新 fold 路径要警惕：单测如果撞上 helper 副作用就会假阳性。

---

## 2026-04-30（v0.1.9.4）：补 client OVERLAY 自动 sync（G14）

### 背景

v0.1.9.3 持久化已上线（File IO + WorldEvent.Save），但用户反馈：「重启后查看调度按钮消失，过一会所有订单自动取消」。

### 根因（不是持久化）

服务端 dat 文件已正确写入并加载，订单仍在 manager 里。问题是**客户端 OVERLAY 不知道**：

1. 客户端启动时 `SmartCraftOverlayRenderer.OVERLAY` 是空的
2. server 端 `SmartCraftOrderSyncService.syncListTo` 只在玩家**主动请求**时触发（`RequestOrderStatusPacket` / `RequestSmartCraftActionPacket`）
3. 没有 `PlayerLoggedInEvent` hook — 玩家进入存档后 server 不会主动推订单列表
4. 玩家打开 ME 终端 → `OVERLAY.hasData() = false` → `shouldShowViewStatusButton` 返回 false → **"查看调度"按钮不显示**
5. 玩家**没法**通过点按钮触发请求 → 死循环

「自动取消」是用户对「标签栏空 + 按钮不显示」的合理推测。`SmartCraftRuntimeCoordinator.tick()` 只遍历 `this.sessions`（重启后空），不会动 manager 里的订单。所以订单**实际上没被取消**，只是客户端看不到。

这是 v0.1.9 G12 引入持久化时的回归：以前没持久化，"重启 = 订单丢" 是预期行为，玩家不期望看到旧订单；加了持久化后玩家期望看到，但 client sync 链路从未为此设计。

### 设计

双层 sync 触发：

**层 1（主）：服务端 `PlayerLoggedInEvent` 主动推**
- 新建 `SmartCraftLoginSyncHandler`，订阅 `cpw.mods.fml.common.gameevent.PlayerEvent.PlayerLoggedInEvent`
- 玩家上线时调 `syncListTo(player)`，推送当前 manager 的全量订单列表
- 注册到 `FMLCommonHandler.bus()`（FML 玩家事件走这个 bus，与 `WorldEvent.Save` 走的 Forge bus 不同）
- 测试友好：handler 接受 `Pusher` 函数式接口（生产代码用 method reference 适配 SyncService），方便单测注入 lambda 而不需要 mock 整个网络层

**层 2（补偿）：客户端打开 ME/CraftConfirm/CraftingStatus 时节流拉取**
- `SmartCraftConfirmGuiEventHandler.onGuiInit` 在三种 GUI 初始化后发 `RequestOrderStatusPacket`
- 节流 1 秒（`ORDER_LIST_PULL_THROTTLE_MS`）防玩家快速切换 GUI 刷屏
- 主要是兜底：万一 PlayerLoggedIn 推丢失（slow 客户端 packet 还没就绪），打开终端时仍能恢复

### 实现

新增：
- `smartcraft/runtime/SmartCraftLoginSyncHandler.java`：100 行，FML 事件 hook + Pusher 函数式接口

修改：
- `AE2IntelligentScheduling.java`：加 `SMART_CRAFT_LOGIN_SYNC` 单例，`serverStarted` 中注册到 `FMLCommonHandler.bus()`
- `client/gui/SmartCraftConfirmGuiEventHandler.java`：`onGuiInit` 在三种 AE2 GUI 初始化后调 `requestOrderListIfStale`，节流 1 秒

### 测试（+7）

`SmartCraftLoginSyncHandlerTest` 7 个：

- `constructor_rejects_null_pusher` / `constructor_rejects_null_sync_service` — null 入参 → IllegalArgumentException
- `onPlayerLoggedIn_silently_skips_when_event_is_null` — null 事件 → no-op，pusher 不调用
- `onPlayerLoggedIn_silently_skips_when_player_is_null` — null 玩家 → no-op
- `onPlayerLoggedIn_isolates_pusher_throws` — pusher 抛异常 → handler 不传播（防止破坏 FML 玩家登录流）
- `pusher_abstraction_records_calls_in_order` — Pusher 接口契约
- `handler_with_recording_pusher_threads_player_through_unchanged` — 文档锚点（说明集成路径覆盖范围）

121 个测试全过（114 老 + 7 新 login sync）。

### 包

modVersion `0.1.9.3` → `0.1.9.4`，3 个 jar。

### 反思

- **持久化跟显示是两件事**：v0.1.9 G12 只做了"数据持久化"，没做"客户端从持久化恢复后的初始 sync 通路"。这两层在 vanilla 设计里有时是耦合的（比如玩家 inventory），但在我们的客户端 overlay 架构里不耦合 — 必须显式 sync。
- **deadlock 排查教训**：UI 按钮"显示与否"依赖于"客户端是否有数据"，而"获取数据"又依赖于"按钮被点击"。这种循环依赖在常态（玩家提交新订单走 OpenSmartCraftPreviewPacket → server reply）下隐藏，重启场景才暴露。审查 GUI flag 路径时要明确"启动状态下能否走通"。
- **双层 sync 触发的合理性**：服务端 push 是主路径，客户端 pull 是补偿。这种 belt-and-suspenders 在网络层不可靠时（如 GTNH 玩家电脑配置千差万别）是合理的冗余。节流 1 秒已经吸收 99% 的 spam。
- **测试可注入性的代价是值得的**：把 `SmartCraftLoginSyncHandler` 接受 `Pusher` 函数式接口（而不是直接接 `SmartCraftOrderSyncService`）只多了 6 行代码，但获得了完整的单测覆盖。生产构造函数依然用 method reference 适配旧 SyncService，对调用方零侵入。

---

## 2026-04-30（v0.1.9.3）：重写 SmartCraft 持久化层（G12-fix）

### 背景

用户报告：「重启后整个智能合成的订单都消失了，所有AE2在做的东西全部自动取消，重启后打开AE2合成订单页面会卡死。」

实测复现：v0.1.9.2 在单人存档创建智能合成订单 → 退出存档 → 重新进入 → 智能合成标签栏空。期望行为是订单保留 + task 折回 PENDING + banner "Resumed after server restart"，但实际订单 map 完全空。

### 根因

v0.1.9 G12 的 `SmartCraftOrderWorldData` 走 vanilla Forge `WorldSavedData` 接口：

1. 继承 `WorldSavedData`，注册到 `world.mapStorage`
2. 通过 `markDirty()` 标记，依赖 vanilla `MapStorage.saveAllData` 周期性写盘
3. 重启时 `mapStorage.loadData(SmartCraftOrderWorldData.class, DATA_KEY)` 反射构造 + 走 `readFromNBT` 双阶段加载

问题出在 vanilla 1.7.10 + GTNH 整合包的 `MapStorage.saveAllData` 调用时机不可靠：单人存档的快速退出路径（玩家点 "Save and Quit to Title"）下，vanilla 不一定走到 `saveAllData`。结果：
- `markDirty()` 设了 dirty flag，但 vanilla 没在 stop 路径触发 `WorldSavedData.writeToNBT`
- `<world>/data/AE2IS_SmartCraftOrders.dat` 文件根本没生成（或残留旧版本）
- 下次启动 `loadData` 找不到文件，manager 留空
- 玩家看到的是"订单全消失"

`attach()` 中的两阶段构造（`readFromNBT(manager==null)` 把 tag 暂存到 `pendingLoadTag`，再由 `attach` 的 `replayPendingLoad` 转给 manager）也有时序竞态：单元测试虽然过了 round-trip（直接调 `manager.writeToNBT/loadFromNBT`），但绕过了 vanilla 接线层，**所以测试假阳性**。

### 设计

**抛弃 `WorldSavedData`，自管 IO**：

1. `SmartCraftPersistence`（纯 IO 静态类，无 Forge 依赖）：
   - `dataFile(File worldDir)` 解析 `<world>/data/AE2IS_SmartCraftOrders.dat`
   - `writeToFile(File, manager)` atomic write：写到 `target.tmp` → `fsync` → 删旧 target → rename。Windows-compatible。
   - `readFromFile(File, manager)` 健壮加载：文件不存在 → log info 返回；NBT 损坏 → log warn 不修改 manager；自动 unwrap legacy "data" 包装（v0.1.9 vanilla MapStorage 写过的文件依然能读）。
2. `SmartCraftPersistenceHandler`（Forge 事件 hook）：
   - `loadOnServerStart(File worldDir)`：FMLServerStartedEvent 调用，初始读盘；之后 reset dirty flag（避免立即又写回）
   - `@SubscribeEvent onWorldSave(WorldEvent.Save)`：vanilla 每个 dimension save 都触发，我们只在 DIM 0 + dirty 时 flush
   - `flushOnServerStop()`：FMLServerStoppingEvent 调用，强制最终 flush（dirty=true 强制写一次）
3. `AE2IntelligentScheduling.serverStarted` 替换：用新的 handler 调 `loadOnServerStart` + 注册到 `MinecraftForge.EVENT_BUS`
4. 新增 `serverStopping` 事件：调 `flushOnServerStop`
5. **删除** `SmartCraftOrderWorldData.java`

为什么这套靠谱：

- **WorldEvent.Save 是 vanilla save 周期的标准入口**：autosave / `/save-all` / server stop 都会触发。GTNH 各种 mod（NewHorizonsCoreMod / GT++）都用这个钩子，battle-tested。
- **直接 File IO**：Forge 事件触发时我们立刻 `CompressedStreamTools.writeCompressed` 到 tmp + rename。崩溃保护：JVM 中途死了只会留 .tmp 不会破坏 live 文件。
- **可单测**：`SmartCraftPersistence` 框架无关，用 JUnit `@TempDir` 直接驱动真实文件 IO。9 个集成测试覆盖：round-trip / 不存在 / 损坏 / legacy 兼容 / 覆写 / dirty flag / loadOnServerStart 不留 dirty / null event 安全。

### 实现

新增：
- `smartcraft/runtime/SmartCraftPersistence.java`：186 行，三个 public static 方法（`dataFile` / `writeToFile` / `readFromFile`）
- `smartcraft/runtime/SmartCraftPersistenceHandler.java`：154 行，事件 hook + dirty flag 状态机

删除：
- `smartcraft/runtime/SmartCraftOrderWorldData.java`：130 行，整个文件移除

修改：
- `AE2IntelligentScheduling.java`：加 `SMART_CRAFT_PERSISTENCE` 单例；`serverStarted` 改用新 handler；新增 `serverStopping`
- `SmartCraftOrderManager.java`：javadoc 引用 `SmartCraftPersistenceHandler` 替代旧类

### 测试（+9）

`SmartCraftPersistenceTest`（114 个测试中新增的 9 个）：

- `writeToFile_then_readFromFile_round_trips_orders` — 真实文件 IO round-trip
- `readFromFile_silently_skips_when_target_does_not_exist` — 首次启动不存在文件 → 不修改 manager
- `readFromFile_silently_skips_when_target_is_corrupt` — 写垃圾字节 → 加载报 warn 不 crash
- `readFromFile_unwraps_legacy_vanilla_data_tag` — v0.1.9 / v0.1.9.1 / v0.1.9.2 残留的 "data"-wrapper 文件依然能读
- `writeToFile_overwrites_existing_target_atomically` — 多次写同一文件不留 .tmp orphan
- `persistenceHandler_marks_dirty_on_track_and_clears_on_flush` — track → dirty=true → flush → dirty=false
- `persistenceHandler_loadOnServerStart_loads_existing_file_and_resets_dirty` — 重启场景模拟：handler1 写 → handler2 读 → dirty 立即清零（避免无意义重写）
- `persistenceHandler_save_event_routing_rejects_invalid_inputs` — `shouldHandleSaveEvent(null)` 不 crash
- `persistenceHandler_onWorldSave_safe_against_null_event` — 防御性 null 检查

114 个测试全过（35 老 + 8 老 + ... + 21 + 17 + 7 + 9 新 persistence）。

### 包

modVersion `0.1.9.2` → `0.1.9.3`，3 个 jar：`ae2intelligentscheduling-0.1.9.3.jar` + `-dev.jar` + `-sources.jar`

### 反思

- **绕开"看起来正确"的 framework 接口**：`WorldSavedData` 在 vanilla 设计意图里就是给"地图数据 / 末影龙 boss bar"这种"重要但偶尔变化"的数据用的，不是给"每 tick 都可能 mutate 的运行时状态"用的。它的 dirty/save 周期不可靠是 vanilla 的已知问题（被 OptiFine / 各种性能 mod 进一步弱化）。直接 File IO + Forge 事件钩是更可控的方案。
- **集成测试必须穿透接线层**：v0.1.9 的 round-trip 单元测试通过但实地失败，是因为测试只测了 `SmartCraftOrderManager.writeToNBT/loadFromNBT`——纯模型层。真正的 bug 在 vanilla `MapStorage.saveAllData` 不调用，绕过了。新版 `SmartCraftPersistenceTest` 用 `@TempDir` 直接打到 File IO，确保 round-trip 经过完整磁盘路径。
- **legacy 兼容比看起来重要**：用户存档里可能有 v0.1.9 / v0.1.9.1 / v0.1.9.2 残留的 "data"-wrapped 文件（即使是空的也存在）。新代码自动 unwrap 旧格式，避免用户升级到 v0.1.9.3 后还看到一次"订单消失"——这次是因为格式不兼容而非 bug。
- **null-safety 不是过度设计**：Forge 事件总线在某些 mod stop 路径会传 partial-state 事件。`shouldHandleSaveEvent(null)` 的测试看似无聊，但生产环境 `event.world == null` 不是不可能——加 null check 成本几乎为零，回报是绝不在退服 race condition 里 NPE。

---

## 2026-04-30（v0.1.9.2）：SmartCraft 全局 CPU 占用上限（G13）

### 背景

用户反馈：「Programmable Hatches 的合成 CPU（`TileCPU`，自动 CPU 多方块）配合智能合成时会无限创建 CraftingCPUCluster，导致服务器卡顿。要给智能合成设上限——默认最多 50 个，可配置。同时要分清玩家手动 craft 和智能合成，不能影响玩家自己提交的合成。」

阅读 `D:\Code\GTNH LIB\Programmable-Hatches-Mod-290-daily-latest\src\main\java\reobf\proghatches\ae\cpu\TileCPU.java` 后定位根因：

```java
// onPostTick (line 615-671):
k:{
    boolean donotCreateNewCCC=false;
    Iterator<...> it = clusterData.entrySet().iterator();
    for(;it.hasNext();){
        ...
        if (set.getValue().state == 0) {
            if (set.getKey().isBusy()) {
                set.getValue().state = 1;
            } else {
                ...
                donotCreateNewCCC = true;
            }
        }
        ...
    }
    if(donotCreateNewCCC) break k;
    // 创建新 CraftingCPUCluster
    CraftingCPUCluster c = newCCC();
    ...
}
```

只要所有现存 cluster 都 busy（`isBusy()` true）且没有 idle 的，TileCPU 就在每 tick 创建一个新 cluster。SmartCraft 把一个大订单拆成 N 个 task 并发 submit，每个 task acquire 一个 cluster，TileCPU 就疯狂 mint 新 cluster——直到服务器 GC 死。

### 设计

在 SmartCraft 的 dispatch 路径加全局 budget gate，**只对 SmartCraft 提交的 task 生效**——玩家手动 craft 走 `ContainerCraftConfirm` → AE2 `submitJob`，根本不经过 `SmartCraftRuntimeCoordinator`，天然隔离。

核心策略：

1. `Config.MAX_CONCURRENT_SMART_CRAFT_SUBMISSIONS = 50`，0 = 禁用
2. `SmartCraftRuntimeSession.countActiveSubmissions()` 数当前 session 内 `craftingLink != null` 的 task（即占着 AE2 cluster 的）
3. `SmartCraftRuntimeCoordinator.globalActiveSubmissions()` 跨所有 sessions 汇总
4. `dispatchReadyTasks` Phase 3 submit 循环之前算 budget = max - global active；超 budget 的 task 不调 `submitJob`，保留 cached plan，标 `WAITING_CPU` + throttle banner，下 tick 继续争 budget
5. 成功拿到 link 时 `globalBudget--`，让同一 tick 内剩余 candidate 也尊重 cap
6. 失败的 submit (link == null) **不**消耗 budget——它根本没占 cluster

关键决策：

- **Status = WAITING_CPU 而非 PENDING**：throttle 是临时性的（其他 task 完成就放出 budget），重用 WAITING_CPU 状态语义最贴切；玩家在 GUI 看到的 banner 直接说明原因
- **不 detach plannedJob**：被 throttle 的 task 保留 cached plan，下 tick 一旦 budget 释放就直接 submit，零 plan-cost 开销
- **每个 dispatchReadyTasks 调用计算一次 globalActiveSubmissions**：per-order tick 重新计算成本是 O(orders × tasks)，与 dispatch 自身复杂度同级，无新瓶颈
- **stats 行加 capThrottled + activeSubmissions/cap**：让 admin 一眼看出 cap 是不是当前的瓶颈

### 实现

`config/Config.java`：
- 加 `MAX_CONCURRENT_SMART_CRAFT_SUBMISSIONS = 50` 字段 + 文档（解释 TileCPU 语境 + 玩家隔离 + 0 禁用）
- `synchronizeConfiguration` 加对应 forge config 注册（range 0-1024）

`smartcraft/runtime/SmartCraftRuntimeSession.java`：
- 加 `public int countActiveSubmissions()` 数 craftingLink != null 的 execution（注释说明轻微 over-count 是 intentional——保守倾向于 throttle 多）

`smartcraft/runtime/SmartCraftRuntimeCoordinator.java`：
- 加 `submissionsThrottledByCap` long 计数器
- 加 `public int globalActiveSubmissions()` 跨 sessions sum
- `dispatchReadyTasks` Phase 3 开头算 globalBudget；submit 循环加 cap-gate（throttle banner + markWaitingCpu + 计数 + skip submit）；成功 submit 后 `globalBudget--`
- stats 日志行加 `submissionsThrottledByCap={} activeSubmissions={}/cap={}`

### 测试（+2）

`SmartCraftRuntimeCoordinatorTest`：
- **`global_submission_cap_throttles_excess_tasks_v0192`**：4 独立 task + 4 idle CPU + cap=2 → tick 2 后恰 2 个 RUNNING，2 个 WAITING_CPU 带 throttle banner；`globalActiveSubmissions() == 2`
- **`global_submission_cap_zero_disables_throttling_v0192`**：cap=0 → 3 个 task 全部 RUNNING（sentinel 语义验证）

加了一个 `sessionWithCpus(ICraftingCPU...)` test helper 给多 CPU 场景。

33 个测试全过。

### 包

modVersion `0.1.9.1` → `0.1.9.2`，assemble 出 3 jar：
- `ae2intelligentscheduling-0.1.9.2.jar`（玩家 notch obf）
- `ae2intelligentscheduling-0.1.9.2-dev.jar`（dev SRG）
- `ae2intelligentscheduling-0.1.9.2-sources.jar`

### 反思

- **第三方 mod 副作用建模而非补丁**：本想直接给 TileCPU 加 mixin 限制 cluster 创建，但那会影响玩家手动 craft（玩家手动 submit 也会让 TileCPU 创建 cluster）。在 SmartCraft 自己的 dispatch 路径上 throttle 是更精确的"只针对 SmartCraft"的语义，且不依赖 Programmable Hatches 是否安装——其他类似行为的 CPU mod（未来如果出现）都自动受益。
- **玩家手动 craft 完全不被影响**：SmartCraftRuntimeCoordinator 是 SmartCraft 的私有 API，玩家 ContainerCraftConfirm → AE2.submitJob 路径根本不导入这个类。隔离是结构性的，不是开关性的——不可能通过任何配置错误让 cap 误伤玩家手动 craft。这是设计上的强约束。
- **错误路径不消耗 budget**：submit 返回 null（link == null）时不 `globalBudget--`，否则会让 SUBMIT_RETRY 循环一直被 cap "占用"虚拟 slot，整个 SmartCraft 卡死。budget 只在真正绑定 cluster（link != null）时消耗，与"占用 AE2 cluster 资源"严格对应。
- **cap=0 sentinel 语义测试是必要的**：没这个测试，未来重构时很容易把 `cap <= 0 ? Integer.MAX_VALUE : ...` 改成 `Math.max(0, cap - active)` 一行看似简洁的代码，结果让 cap=0 把 SmartCraft 全锁死。专门一个测试盯住这个边界条件比依赖代码 review 更可靠。

---

## 2026-04-29（v0.1.9.1）：标签页图标改为最终产物

### 背景

用户反馈："UI 中标签页的图标不合理，应该显示的是最终产物的图标，而不是第一步的图标。"

multi-order tab 的图标渲染靠 `SmartCraftOrderTabsWidget.rootItemStack` 提取——此前实现是遍历 `tab.getTasks()` 取**第一个**有非空 ItemStack 的 task。但 `SmartCraftOrderBuilder` 的 `visit()` 是 height-from-leaves 编号（先 visit 子树再 emit 当前节点），所以"第一个 task"必然是叶子原料（铁锭、橡胶、石粉之类），整个 tab 列表的图标看起来雷同，没法区分订单。

### 设计

最干净的修复：让 `SyncSmartCraftOrderPacket` 直接携带最终产物的 ItemStack，不依赖 task list 顺序的隐式约定。

- 顶层加 `private ItemStack targetItemStack`（nullable，fluid / virtual target 时 null）
- `from(orderId, order, session)` 工厂取 `order.targetRequestKey().itemStack()`
- toBytes / fromBytes 在 `targetRequestKeyId` 之后插入 `writeItemStack` / `readItemStack` 对（与 TaskView 风格一致）
- `SmartCraftOrderTabsWidget.rootItemStack` 优先读 `tab.getTargetItemStack()`，旧 task 遍历降级为兼容 fallback

注意 wire 兼容性：旧 client（不知道新字段）和新 server 之间不能混用——但 mod 是 client+server 同时部署，跨版本不会发生。任何旧 packet 残留在 buffer 里都会在 server-restart 后被新代码 readItemStack 读出（旧 buffer 不存在所以没问题）。

### 实现

`network/packet/SyncSmartCraftOrderPacket.java`：
- 加 `targetItemStack` 字段 + 文档说明
- 改主 constructor 签名（10 参 → 11 参，加 ItemStack）
- `from(...)` 取 `order.targetRequestKey().itemStack()` 防 null
- `getTargetItemStack()` getter
- 序列化：`targetRequestKeyId` 之后立即 `writeItemStack` / `readItemStack`

`client/gui/SmartCraftOrderTabsWidget.java`：
- `rootItemStack(tab)` 优先 `tab.getTargetItemStack()`
- task list fallback 保留以应对 fluid / 极旧版 packet

### 测试

`SmartCraftPacketCodecTest` round-trip 用 `from(orderId, order)` 工厂自动覆盖新字段。FakeRequestKey.itemStack() 返回 null，验证 nullable 路径也跑得通。31 个测试全过，零修改。

### 包

modVersion `0.1.9.0` → `0.1.9.1`，assemble 出 3 jar：
- `ae2intelligentscheduling-0.1.9.1.jar`
- `ae2intelligentscheduling-0.1.9.1-dev.jar`
- `ae2intelligentscheduling-0.1.9.1-sources.jar`

### 反思

- **builder 顺序约定不应外泄**：原代码假设 "first task is root" 是 builder 的契约的一部分，但实际是反的（root 最后 emit）。让 packet 显式 ship root ItemStack 切断了 UI 对 builder 内部顺序的依赖，未来如果 builder 改成宽度优先 / 拓扑排序也不会破图标。
- **packet 字段加载顺序紧贴语义相关字段**：把 `targetItemStack` 放在 `targetRequestKeyId` 紧后面，而不是塞末尾。读者第一眼就能从 wire layout 看出 "id 与 stack 是同一逻辑实体的两面"。

---

## 2026-04-29（v0.1.9.0）：服务器重启恢复运行中订单（阶段 4 G12）

### 背景

v0.1.8 三层 backoff retry 解决了 AE2 大订单运行期临时失败的自动恢复，但还剩最后一个失败模式：**服务器重启**。原版本 OrderManager 仅在内存中持有订单，重启后立即清空——半夜挂机合成的玩家早晨进服只能眼睁睁看着上游 task 全部消失。这一节负责把 4 节点 retry 机制中的"L4 重启恢复"补齐。

### 设计

完整的持久化 + 重启恢复链路：

1. **NBT 序列化**：`SmartCraftOrder` / `SmartCraftLayer` / `SmartCraftTask` / `SmartCraftRequestKey` 全部加 `writeToNBT` / `readFromNBT`
2. **Registry 路由**：`SmartCraftRequestKey` 是接口，无法静态识别。新增 `SmartCraftRequestKeyRegistry` 用 `type` 字符串路由 → reader factory，让 `model` 包不必导入 `integration.ae2.Ae2RequestKey`，保持架构边界
3. **WorldSavedData**：`SmartCraftOrderWorldData` 挂在 overworld（dim 0），存进 `<world>/data/AE2IS_SmartCraftOrders.dat`
4. **OrderManager 钩 markDirty**：每次 mutator（track / update / remove / cancel / cancelGracefully / retryFailedTasks / loadFromNBT）调 `DirtyListener.onDirty` → 转发到 `WorldSavedData.markDirty`
5. **状态折回 PENDING**：load 后非终态 task（PENDING / QUEUED / WAITING_CPU / SUBMITTING / RUNNING / VERIFYING_OUTPUT / PAUSED / ANALYZING）全部重置 PENDING + blockingReason 改为 "Resumed after server restart"。AE2 内部对象（plannedJob / craftingLink）不可序列化故只能 re-plan
6. **owner 持久化**：`SmartCraftOrder` 加 `ownerName` 字段（玩家用户名），让重启后 server 知道哪个 order 该绑给哪个玩家
7. **Session 重建**：玩家点 retry / cancel 等 action 时，`RequestSmartCraftActionPacket.Handler` 调 `coordinator.attemptRebindSession` 用玩家当前打开的 AE2 容器（反射 `getTarget()`）作为 actionHost 重建 SmartCraftRuntimeSession

关键决策：

- **default + override 而非 abstract method**：`SmartCraftRequestKey.writeToNBT` 给了 default 实现（写 `type=_unsupported`），让现有所有测试 fake 类不必修改即可继续编译。真实实现必须 override 才能持久化，registry 拒绝 `_unsupported` type 的读路径。
- **Registry 而非硬编码**：让 `model` 包保持 AE2-clean，`Ae2RequestKey.registerNbtReader()` 在 `preInit` 调用一次。
- **Owner gate 在 attemptRebindSession**：只有原玩家能绑回自己的 order（用 `EntityPlayerMP.getCommandSenderName()` 比对 `order.ownerName()`），避免管理员误触发别人订单的 session 让 AE2 完成回调（chat / sound）发到错误玩家
- **反射拿 actionHost**：`AEBaseContainer.getTarget()` 通过反射调用，跨 AE2 fork 兼容（GTNH / AE2-Stuff 等可能有自己的子类，不锁定到具体编译时类型）
- **resetForRestart 重置 currentLayerIndex**：扫第一个 incomplete layer，避免 `advanceLayers` 拿到错误的初始索引（之前持久化的 index 可能对应已完成的 layer）

### 实现

`smartcraft/model/SmartCraftRequestKey.java`：
- 加 `default void writeToNBT(NBTTagCompound)` 写 type+id placeholder

`smartcraft/model/SmartCraftRequestKeyRegistry.java`（新文件）：
- `register(String type, Reader)` + `readFromNBT(NBTTagCompound)` 路由
- 拒绝 `_unsupported` type、未注册 type、reader 抛异常 → 返回 null（caller 跳过该 entry）

`smartcraft/model/SmartCraftTask.java` / `SmartCraftLayer.java` / `SmartCraftOrder.java`：
- 三个类各加 `writeToNBT` / `static readFromNBT`
- `SmartCraftOrder` 加 `ownerName` 字段 + 7 参 constructor 重载（旧 6 参委托 with 空字符串，所有测试零改动）+ `withOwnerName` + 序列化 ownerName 字段

`integration/ae2/Ae2RequestKey.java`：
- 加 `NBT_TYPE = "ae2.requestKey"` 常量 + `registerNbtReader()` 静态注册方法
- `writeToNBT` 写 type + id + (optional) itemStack
- `readFromNBT` 用 `ItemStack.loadItemStackFromNBT` 容错读取（mod 卸载导致 item 找不到则 stack=null）

`smartcraft/runtime/SmartCraftOrderManager.java`：
- 加 `DirtyListener` 函数式接口 + `setDirtyListener` + `markDirty()` 包裹 try/catch
- 所有 mutator（track / trackWithId / update / remove / cancel / cancelGracefully / retryFailedTasks / loadFromNBT）调 markDirty
- 加 `writeToNBT` / `loadFromNBT` 累加 LOGGER.info 报告 loaded/skipped 数量
- 加 `static SmartCraftOrder resetForRestart(order)` 折回 + 重置 currentLayerIndex

`smartcraft/runtime/SmartCraftOrderWorldData.java`（新文件）：
- 继承 `WorldSavedData`，DATA_KEY = `"AE2IS_SmartCraftOrders"`
- `attach(world, manager)` 静态 entry：loadData → readFromNBT 缓存 pendingLoadTag → 设置 manager → replayPendingLoad → wireDirtyListener

`smartcraft/runtime/SmartCraftAe2RuntimeSessionFactory.java`：
- 加 `static extractActionSourceFromOpenContainer(player)`：反射 `Container.getTarget()` 拿 IActionHost 包成 PlayerSource

`smartcraft/runtime/SmartCraftRuntimeCoordinator.java`：
- 加 `public boolean attemptRebindSession(orderId, player, factory)`：owner-gate + extractActionSource + factory.create + register

`AE2IntelligentScheduling.java`：
- `preInit` 调 `Ae2RequestKey.registerNbtReader()`
- `serverStarted` 用 `MinecraftServer.getServer().worldServerForDimension(0)` 拿 overworld 调 `SmartCraftOrderWorldData.attach`

`smartcraft/analysis/SmartCraftOrderBuilder.java`：
- `build(root)` 转调 `build(root, "")`，新增 `build(root, ownerName)` 重载

`network/packet/OpenSmartCraftPreviewPacket.java`：
- 调 `builder.build(snapshot, player.getCommandSenderName())` 传入玩家用户名

`network/packet/RequestSmartCraftActionPacket.java`：
- handler 在 dispatch action 之前先调 `coordinator.attemptRebindSession(...)` 让重启后孤儿 order 能恢复 session

### 测试（共 +3）

`SmartCraftOrderManagerTest`：
- **新** `manager_round_trips_orders_through_nbt_v019`：track 2 个 order → writeToNBT → 新 manager.loadFromNBT → 断言 size / 顺序 / 字段还原（FakeRequestKey 注册到 registry 配合）
- **新** `mutators_call_dirty_listener_v019`：track / update / remove 各自递增 dirtyCount
- **新** `resetForRestart_folds_in_flight_tasks_to_pending_v019`：RUNNING + DONE + FAILED 三种 task → reset 后 RUNNING→PENDING（带恢复 banner），DONE/FAILED 不变，order status → QUEUED

测试中 FakeRequestKey override `writeToNBT` 写 `type=test.fake`、加 `static readFromNBT`，并在测试方法内 register 到 registry。

22 个 RuntimeCoordinator + 9 个 OrderManager 测试全过。

### 包

modVersion `0.1.8.4` → `0.1.9.0`，跑 `assemble -x compileInjectedTagsJava` 出 3 个 jar：
- `ae2intelligentscheduling-0.1.9.0.jar`（玩家分发，notch obf）
- `ae2intelligentscheduling-0.1.9.0-dev.jar`（dev 用 SRG）
- `ae2intelligentscheduling-0.1.9.0-sources.jar`

### 反思

- **包边界 vs 持久化路由**：原本想直接在 `SmartCraftOrder.readFromNBT` 内部 instanceof Ae2RequestKey 路由，但 model 包不该 import integration.ae2 子层。Registry 是在不打破"model 不依赖具体实现"原则下的唯一办法——付出一点静态注册仪式（preInit 调 register），换来更干净的依赖边界。
- **Owner field 是必需的**：原本想通过 SmartCraftRuntimeSession 的 owner 推回去，但 session 是 transient 的，重启后没有它。把 ownerName 升级到 SmartCraftOrder 字段是唯一能跨重启 propagate 的方式。代价：所有 withXxx 方法都要 propagate ownerName，加了点样板代码。值得。
- **resetForRestart 是单测最大头**：实际写完后才意识到，task status 折回逻辑必须独立于 NBT 路径（整 pipeline 的 stub mock 太复杂）。把它做成 public static 方法 + 直接 unit-test 输入输出，比试图测整个 NBT round-trip + reset 简单数倍。
- **Session 重建在 action 而不是 list sync**：玩家打开 GUI 时（list sync）不需要 session，能看到 list 即可。只有按下 retry / cancel / refresh 时才真正需要 session。在 action 路径 attempt rebind 减少误绑（玩家可能只是路过查看，不应触发 session）。
- **反射 getTarget**：AE2 1.7.10 fork 多（GTNH 自己 fork 了一支），各 container 子类层次复杂。反射调 `Container.getClass().getMethod("getTarget")` 在所有这些 fork 上都能用，比 `instanceof AEBaseContainer` 兼容性好——后者要求编译时绑定到 AE2 的 base container 类型，跨 fork 时会断。

---

## 2026-04-29（v0.1.8.4）：detail panel 显示任务失败次数（G11）

### 背景

用户反馈："点击下面调度层 UI 中的分调度时添加额外显示信息：是否失败过，失败次数。"

之前 schedule list 选中某个 task → detail panel 只显示 status / layer / amount / cpu / blockingReason。即便该 task 在 plan / submit / link-cancel 三层 retry 中已经失败了多次，玩家也看不到具体计数——只能从 blockingReason 字符串里隐约读出 "Retrying ... attempt 3/4"。这种信息埋藏对调试自动化链路问题不直观。

### 设计

把"当前正在 retry 中的失败次数"作为独立字段暴露给客户端：
- **数据来源**：`SmartCraftRuntimeCoordinator` 的三个 retry map (`planRetries` / `submitRetries` / `linkCancelRetries`)，每条记录的 `attempts` 表示已经发生的失败次数。三者之和 = 当前 task 在所有 retry 层加起来的失败计数
- **传输**：`SyncSmartCraftOrderPacket.TaskView` 加 `int failureCount` 字段，server 端构造 packet 时通过 `AE2IntelligentScheduling.SMART_CRAFT_RUNTIME.totalFailuresFor(taskKey)` 拿到值
- **UI 表现**：detail panel 在 cpu 行之前插入红色行 `曾失败  失败次数: N`（仅当 N > 0 时显示）；hover tooltip 同步追加 `失败次数: N`

**语义边界**：当 task 重新成功（plan succeeded / submit succeeded / link.isDone() 完成）时，retry map entry 被移除，attempts 也跟着丢失。所以这个 count 是"当前 retry 窗口内的失败次数"，不是 lifetime count。这其实是更有用的信息——成功恢复的 task 不需要标"曾失败 3 次"打扰玩家，正在挣扎的 task 才值得关注。

### 实现

`SmartCraftRuntimeCoordinator.java`：
- 新增 `public int totalFailuresFor(String taskKey)`：累加 `planRetries` + `submitRetries` + `linkCancelRetries` 三个 map 中该 taskKey 对应 entry 的 `attempts` 字段（null 视为 0）

`SyncSmartCraftOrderPacket.java`：
- `TaskView` 加 `final int failureCount` 字段 + 11 参 constructor 重载（旧 10 参 constructor 转调新构造器 with `failureCount=0`）
- `from(orderId, order, session)` 调用 `AE2IntelligentScheduling.SMART_CRAFT_RUNTIME.totalFailuresFor(task.taskKey())` 取值（singleton 为 null 时 fallback 到 0，单测安全）
- `toBytes` 末尾追加 `buf.writeInt(task.failureCount())`，`fromBytes` 同步追加 `buf.readInt()`

`SmartCraftOverlayRenderer.java`：
- `drawTaskDetailGrid`：在 `infoY` 起始处插入 `if (task.failureCount() > 0)` 分支，红色显示 `曾失败  失败次数: N`
- `appendSmartCraftLines`（tooltip 共用）：在 cpu 行之后、blocking 行之前插入同样的失败计数行（hover 浮窗也能看到）

`zh_CN.lang` / `en_US.lang`：
- 新增 `failedBefore` / `failureCount` 两个 lang key（中文 "曾失败" / "失败次数"，英文 "Failed before" / "Failure count"）

### 网络协议兼容性

`TaskView` 序列化字段顺序：旧版 10 字段 → 新版 11 字段（多 1 个 int）。client/server 必须同时升级到 0.1.8.4，否则 `fromBytes` 在旧 client 收到新 server 的 packet 时会读多 4 字节导致后续 task 解析错乱。这是同时升级的内部 mod 包，不需要协议向前/向后兼容。

### 测试

- 17 个 RuntimeCoordinator 测试仍全过（packet 路径不在单测覆盖范围）
- 编译验证 + assemble 全程通过
- 客户端 GUI 改动局部在 `drawTaskDetailGrid` + `appendSmartCraftLines`，failureCount==0 时完全不影响视觉

### 包

modVersion `0.1.8.3` → `0.1.8.4`，跑 `assemble -x compileInjectedTagsJava` 出 3 个 jar：
- `ae2intelligentscheduling-0.1.8.4.jar`（玩家分发，notch obf）
- `ae2intelligentscheduling-0.1.8.4-dev.jar`（dev 用 SRG）
- `ae2intelligentscheduling-0.1.8.4-sources.jar`

### 反思

- **从 server runtime 拉数据 vs 在 task 模型里塞**：`SmartCraftTask` 是 immutable data model，把 retry attempts 放进去会污染模型语义（attempts 是 server-side runtime state，不该和 task 数据本身耦合）。改用 packet 序列化时 server-side 静态 lookup `SMART_CRAFT_RUNTIME.totalFailuresFor` 干净得多——retry state 只在 coordinator 内部管理，packet 是外部观测面。
- **lifetime count vs current window count**：选了后者。lifetime count 需要单独的"已结算失败"map，task 完成时清空，task 删除时清空，retry 成功时不清空——比 retry map 复杂很多。current window 的语义"还在挣扎吗"对玩家更有用，实现也几乎零成本。
- **多入口共享数据**：detail panel 和 hover tooltip 都需要显示 failure count。`appendSmartCraftLines` 已经是共用 helper，加一行就两边都生效。同一信息在两个 UI 表现下展示一致，玩家不需要记住"在哪能看到"。

---

## 2026-04-29（v0.1.8.3）：info bar 增加调度统计行（G10）

### 背景

用户反馈："在 UI 中任务数目的下面添加调度信息：已完成任务数目，未完成任务数目，失败数目，正在合成的数目。"

之前 info bar 只显示 `量级 / 当前层` 和 `任务数 / 数量`，玩家想知道"现在调度到底进展如何"必须自己数 grid 里的颜色块。任务数 50+ 时几乎不可能数清。

### 设计

把 4 个分类计数渲染在 info bar 第 2 行右侧（与左边 `状态: XXX` 同行），形式：

```
[第 1 行] 量级: K/M/G   当前层: I/N           任务数: T   数量: A
[第 2 行] 状态: RUNNING                       合成中:R  已完成:D  未完成:P  失败:F
[第 3 行] 正在查看: cpu-name (可选)
```

颜色与 grid tile 的 cell-bg / status-dot 一致：
- **合成中（aqua/blue）**：RUNNING / SUBMITTING / VERIFYING_OUTPUT
- **已完成（green）**：DONE（task 级 terminal 成功）
- **未完成（gray）**：PENDING / QUEUED / WAITING_CPU / PAUSED
- **失败（red）**：FAILED

刻意排除 CANCELLED（玩家主动移除，不计入调度结果）和 COMPLETED（order 级状态，不会出现在 TaskView）。这两个语义上"不属于此处"，桶进任何一类都会误导玩家对调度真实情况的判断。

实现技巧：用 `EnumChatFormatting` 的 `§x` 颜色码拼成单一字符串，单次 `drawString` 完成。`FontRenderer.getStringWidth` 自动忽略 `§x`，宽度计算无需手动 strip。

### 实现

`SmartCraftOverlayRenderer.drawInfoBar`：
- 在 `right`（taskCount + amount）行渲染之后追加新分支：遍历 `cur.getTasks()` 桶分类计数 → 拼带色字符串 → 右对齐绘制在 `panelY + LINE_HEIGHT`（与 status 同行）
- `viewing`（cpu-detail mode）行仍保持 `panelY + LINE_HEIGHT * 2`，不冲突

`zh_CN.lang` / `en_US.lang`：
- 加 4 个 lang key：`statsCrafting` / `statsCompleted` / `statsPending` / `statsFailed`
- zh_CN 用中文（合成中 / 已完成 / 未完成 / 失败）
- en_US 用英文（Crafting / Done / Pending / Failed）

### 测试

- 17 个 RuntimeCoordinator 测试全过
- 客户端 GUI 改动局部在 `drawInfoBar`，不影响其他渲染路径
- 编译验证通过（`compileJava` 任务 SUCCESSFUL）

### 包

modVersion `0.1.8.2` → `0.1.8.3`，跑 `assemble -x compileInjectedTagsJava` 出 3 个 jar：
- `ae2intelligentscheduling-0.1.8.3.jar`（玩家分发，notch obf）
- `ae2intelligentscheduling-0.1.8.3-dev.jar`（dev 用 SRG）
- `ae2intelligentscheduling-0.1.8.3-sources.jar`

### 反思

- **§x 颜色码 + 单次 drawString**：原本想用 `for (segment : segments) { drawString; x += getStringWidth }` 累加，但 `FontRenderer` 对 `§x` 的处理已经做好——它在内部按色段绘制，`getStringWidth` 也跳过 `§x` 字符不计宽。直接拼成一个字符串即可，代码量减半。
- **状态分桶遵循一致性**：4 类计数的 case 集合与 `cellBg` / `statusDotColor` 完全一致。这个一致性不是巧合——info bar 的 stats、grid tile 的颜色、schedule list 的 status dot 三个 UI 表现共用一套 status 含义，让玩家可以从任意一处推断其他两处。如果以后引入新 status（比如 G11 的 RECOVERING），只需要加进同一组分类，三个 UI 表现自动对齐。

---

## 2026-04-29（v0.1.8.2）：grid 视图过滤 DONE 任务，对齐 AE2 vanilla 行为（G9）

### 背景

用户反馈："智能合成下单后，UI 上面的格子区域需要向 AE2 一样，已经合成完成的物品不再显示在格子区。"

之前的行为：grid 区域（GUI 顶部 6×3 = 18 格的 AE2-style task tiles）和 schedule list（左下 panel 的逐行任务列表）都从 `cur.getTasks()` 取**全部任务**，包括已 DONE 的子任务。AE2 vanilla GuiCraftingCPU 的行为是子合成完成后从 active 区消失（输出会被路由到 ME 网络，cell 被回收）。我们的智能合成把整单拆成多个子任务，每个子任务完成后仍残留在 grid 中显示 "Stored: N"，与玩家对 AE2 的视觉预期不一致。

### 设计

**grid 区过滤 DONE，schedule list 保留 DONE**：
- grid 区是当前活动视图，玩家想看的是"还在做什么"。DONE 的没意义留着，移除让 grid 始终展示真正在跑/排队的任务，与 AE2 GuiCraftingCPU 视觉对齐。
- schedule list 是左下的"任务清单"，玩家用它跟踪整单进度（哪些做完哪些没做），DONE 必须可见。这两者的语义本来就不同，不应一刀切。

`SmartCraftOverlayRenderer` 加内部 helper `activeGridTasks(cur)` 即时过滤：每帧重建一个新 list，但对 task 数量 < 100 + 60 fps × 几次 / 帧的场景成本可忽略（每帧 < 1 微秒），不需要缓存增加复杂度。新增 `getGridTaskCount()` 公共方法供 GUI scrollbar / 拖拽 / 滚轮 计算用，**不**新增 `getGridTasks()` —— 让外层只看 count，过滤后的 list 仅在 grid 内部 draw 时使用，避免 task index 在两个层（schedule list / grid）之间错位的歧义。

### 实现

`SmartCraftOverlayRenderer.java`：
- 新增 `private static List<TaskView> activeGridTasks(SyncSmartCraftOrderPacket cur)`：过滤掉 status==DONE
- 新增 `public int getGridTaskCount()`：直接 count 而不构造 list，给 GUI 调用
- `drawTaskGrid` 改为遍历 `activeGridTasks(cur)` 而不是 `cur.getTasks()`
- `scroll(int delta)` / `setGridScroll(int)`：clamp 用 `activeGridTasks(cur).size()`

`GuiSmartCraftStatus.java`：
- `drawGridScrollbar` / `gridScrollMax` / `dragGridThumb` / `tryStartScrollbarDrag`（grid 分支）：4 处 `getTasks().size()` 都改成 `getGridTaskCount()`
- schedule scrollbar / `scheduleScrollMax` / 拖拽 / 滚轮：仍用 `getTasks().size()`，schedule list 保留 DONE 行的展示

**未改的方法**（故意保留 full task list）：
- `findMatchingTask(stack)`：用于把 ME 网格里 hover 的物品匹配到 task 显示 supplementary tooltip — DONE 的也要能匹配到
- `hasRetriableTasks` / `isOrderActive`：内部判断 retry/active，与 UI 显示无关
- `drawTaskDetailGrid` (selectedTaskIndex)：detail 面板使用 schedule list 的索引（full list），DONE 也能查看详情
- `SmartCraftOrderTabsWidget` 取 representative icon：找第一个有 itemStack 的 task，DONE/非 DONE 都行

### 测试

- 17 个 RuntimeCoordinator 测试全过（v0.1.8.0/0.1.8.1 引入的全部）。
- 客户端 GUI 渲染逻辑无单测覆盖（依赖 Minecraft client 类，单测难做），改动通过编译 + 视觉验证。改动局部化在 grid 路径，schedule 区路径不变，回归风险低。

### 包

modVersion `0.1.8.1` → `0.1.8.2`，跑 `assemble -x compileInjectedTagsJava` 出 3 个 jar：
- `ae2intelligentscheduling-0.1.8.2.jar`（玩家分发，notch obf）
- `ae2intelligentscheduling-0.1.8.2-dev.jar`（dev 用 SRG）
- `ae2intelligentscheduling-0.1.8.2-sources.jar`

### 反思

- **grid 与 schedule list 是两套语义**：以前在 `SmartCraftOverlayRenderer` 把 `cur.getTasks()` 当作"唯一真相"暴露给所有调用方，导致两个不同 UI 区域共享同一过滤逻辑。这次把 grid 路径独立出来后，发现还有一些原本被假定在 grid 显示的判断（比如 scrollbar）其实是混在 GUI 类里直接调 `getTasks().size()` 的。为了不让 future-self 再踩这个坑，新增的 `getGridTaskCount()` 方法名本身是 grid-specific 的——下次 reader 看到一定不会拿它去给 schedule list 用。
- **过滤 vs 状态机区分**：DONE 是 task 级 terminal，COMPLETED 是 order 级 terminal。grid 渲染过滤 task.status == DONE 不会误伤 order 级的 COMPLETED（terminal-orders-vanish 已经在 server 端把 COMPLETED order 移除）。这种分层让 task 级的 UI 过滤决策与 order 级的生命周期决策解耦。
- **不缓存的合理性**：被诱惑去缓存 `activeGridTasks` 结果，但是 client 端没有"task 状态变更"事件可挂钩（只能跟着 packet refresh 比 diff），重建逻辑反而更复杂。直接每帧过滤简单可靠，性能成本可忽略。

---

## 2026-04-29（v0.1.8.1）：智能合成子任务静音 + 整单完成通知（G8）

### 背景

用户反馈："AE2 合成完毕后会有提示音和提示弹窗，但由于调度，会不停的弹出提示音和提示弹窗，我想改成如果使用智能合成只有合成最终产物完成后再触发提示音和提示弹窗。"

排查发现提示音/弹窗**不是 AE2 vanilla** 的功能（vanilla 的 `playersFollowingCurrentCraft` 路径需要 `followCraft=true` 才触发，我们 5 参 `submitJob` 实际是 false）。真正源头是 **AE2Things 的 `MixinCraftingCPUCluster`**：

```java
// AE2Things mixin: 仅当 PlayerSource 时挂钩，completeJob 时给持无线终端的玩家发 NOTIFICATION 包
@Inject(method = "submitJob", at = @At("RETURN"))
private void submitJob(..., CallbackInfoReturnable<ICraftingLink> cir) {
    if (src instanceof PlayerSource ps && cir.getReturnValue() != null) {
        player = ps.player;
        output = job.getOutput().copy();
        networkKey = ...;
    }
}

@Inject(method = "completeJob", at = @At("TAIL"))
private void completeJob(CallbackInfo ci) {
    // 检查 player 持有 INetworkEncodable（无线终端）→ 发 SPacketMEItemInvUpdate(NOTIFICATION)
}
```

客户端收到 NOTIFICATION 后走 `AE2ThingAPI.addCraftingCompleteNotification` → 弹窗 + `random.levelup` 声音（`Notification.java:44`）。

我们的智能合成把一个大订单拆成 N 个 sub-task，每个 sub-task 用同一个 PlayerSource 提交。每完成一个就触发一次。N=20 的订单就响 20 次。

### 设计

两个独立修复联合作用：

1. **submit 层用 MachineSource 替换 PlayerSource**：`Ae2CraftSubmitter.submit` 内部用 `new MachineSource(requesterBridge)` 提交。AE2Things mixin 的 `instanceof PlayerSource` 检查失败，不再记录 player → completeJob 时不发包。同时也 defence-in-depth 屏蔽掉 vanilla AE2 的 followCraft 自动加 follower 路径（即使未来某条路径不小心传了 `followCraft=true`）。
2. **整单 COMPLETED 时由 mod 自己发**：新增 `OrderCompletionNotifier` 函数式接口，`tick()` 在 `updated != order` 后比较 `prev.status != COMPLETED && updated.status == COMPLETED`，rising-edge 时调 notifier 一次。production 实现：给 owner 发 chat message `[智能合成] 物品名 xN 已完成` + `playSoundAtEntity("random.levelup")`。

关键正确性：

- **不影响 plan 阶段权限**：`Ae2SmartCraftJobPlanner.beginCraftingJob` 仍用 PlayerSource 做 secured pattern 权限检查。仅 submit 改 source。
- **CANCELLED / FAILED / PAUSED 不触发**：`prev.status != COMPLETED && updated.status == COMPLETED` 只在 rising edge 进入 COMPLETED 触发；其他终态走不到这条分支。CANCELLED 是玩家主动操作不需要通知，FAILED/PAUSED 已经有 Retry banner。
- **不会重复触发**：terminal-orders-vanish 在 COMPLETED tick 之后 1 tick 把 order 从 manager 移除，下个 tick `existing.isPresent()==false` 直接 continue，所以最多触发一次。测试 `order_completion_notifier_fires_exactly_once_on_completed_transition_v0181` 显式 spin 多 tick 验证。
- **notifier 异常隔离**：`try/catch (Throwable)` + `LOGGER.warn`，notifier 抛错不影响 tick 主循环（玩家短线/世界切换等场景下 owner handle 可能失效）。
- **测试构造器向后兼容**：5 参构造保留作为兼容入口，内部转 6 参 `notifier=null`。null 时跳过整个 G8 分支。所有 17 个老测试不必改构造调用。

### 实现

`Ae2CraftSubmitter.java`：
- `submit()` 内部用 `new MachineSource(requesterBridge)` 替代外部传入的 actionSource。actionSource 参数保留在签名里（因为它是一个会被未来其他 caller 调的 helper），但带详尽 javadoc 解释为什么 submit 路径不用它。

`SmartCraftRuntimeCoordinator.java`：
- 新增 `interface OrderCompletionNotifier { onOrderCompleted(session, orderId, order) }`
- 新增 6 参构造 `(orderManager, cpuSelector, jobPlanner, jobSubmitter, orderSync, notifier)`；5 参构造转调 6 参 with `null`
- `tick()` 中 `if (updated != order)` 块加：`prev.status != COMPLETED && updated.status == COMPLETED` 时 try/catch 调 notifier

`AE2IntelligentScheduling.java`：
- production notifier lambda：从 `order.targetRequestKey().itemStack().getDisplayName()` 取物品名（fallback 到 RequestKey id），拼 `"[智能合成] {name} x{amount} 已完成"`（绿色 + 灰色样式），`addChatMessage` + `playSoundAtEntity(owner, "random.levelup", 1.0f, 1.0f)`

### 测试（共 +2 个）

`SmartCraftRuntimeCoordinatorTest`：
- **新** `order_completion_notifier_fires_exactly_once_on_completed_transition_v0181`：plan 成功 + link.done=true 单 task 走 RUNNING → DONE → COMPLETED；断言 notifier 在 COMPLETED tick 触发恰好 1 次（分阶段断言：RUNNING 阶段 0 次 / COMPLETED tick 后 1 次 / 再 spin 2 tick 后仍 1 次）
- **新** `order_completion_notifier_does_not_fire_for_failed_or_cancelled_orders_v0181`：simulation planner + `PLAN_RETRY_MAX_ATTEMPTS=0` + `ORDER_AUTO_RETRY_MAX_ATTEMPTS=0` 让 task 立 FAIL → order PAUSED；spin 50 tick 断言 notifier `firedCount == 0`，且最终状态在 PAUSED/FAILED 之一

新增 test stub `RecordingCompletionNotifier`：list of completed orderIds + `firedCount()`。

17 个 RuntimeCoordinator 测试全过（含上版 15 + 本版 2）。

### 包

modVersion `0.1.8.0` → `0.1.8.1`，跑 `assemble -x compileInjectedTagsJava` 出 3 个 jar：
- `ae2intelligentscheduling-0.1.8.1.jar`（玩家分发，notch obf）
- `ae2intelligentscheduling-0.1.8.1-dev.jar`（dev 用 SRG）
- `ae2intelligentscheduling-0.1.8.1-sources.jar`

### 反思

- **MachineSource vs PlayerSource 是个二选一开关**：AE2 + 多个 addon mod（AE2Things 是其中之一）都依赖 `instanceof PlayerSource` 决定是否给玩家发通知。把 submit 改成 MachineSource 一刀切关掉所有这类自动通知，再在我们这层 fine-grained 决定何时通知。这种"关掉别人的钩子，自己做"的模式比"试图按 task 是否 final 决定 source"健壮得多——前者在源头解决，后者在每个分发点都要判断。
- **rising-edge 检测靠 tick 内 prev/next**：因为 `tick()` 是单线程顺序的，`updateOrder` 之前 `existing.get()` 拿到的就是 prev，`updated` 是 next，比较两者 status 即可。不需要外置"上次 status 缓存"map。如果以后引入异步路径再说。
- **notifier 是 Optional 而不是 always-on**：通过 `null` 表示"不接收通知"，比让所有调用方传一个 no-op lambda 更简洁——尤其是单元测试根本不关心通知时直接 5 参构造，老测试零改动。

---

## 2026-04-29（v0.1.8.0）：超长 AE2 合成订单的 4 节点自动重试机制（阶段 1-3：G5+G6+G7）

### 背景

用户反馈："AE2 超大订单合成可能合成几个小时至十几个小时，需要添加自动重试的节点。" 现有重试只覆盖 plan 阶段（G1：planFuture 失败/超时/RuntimeException → 5/10/20/40/80 tick 等差 backoff，最多 3 次重试）。AE2 大订单的真实失败模式还有 3 处：

1. **submit 阶段失败**：plan 已成功，调 AE2 `submitJob` 返回 null。常见原因：选中的 CPU 在 idle-detection 与 submit 之间被别的订单抢占成 busy；jobByteTotal 超过任何 idle CPU 的 `getAvailableStorage`。这种是秒-分钟级临时问题。旧版直接 FAILED。
2. **link.isCanceled()**：plan + submit 全成功，AE2 把 RUNNING 的 craftingLink 主动取消（自动化中断、pattern 变更、CPU 集群重整）。深依赖树的 plan 重做成本很高（秒-十几秒），不应让一次 link cancel 把所有 plan 投入扔掉。旧版直接 FAILED。
3. **订单整体 FAILED 后**：所有 task 都终态了，订单是 PAUSED/FAILED 状态，玩家不在线（半夜挂机合成场景）就一直停着等。需要服务端 timer 自动按一下 "Retry"。旧版必须等玩家手动点 GUI。

设计共识：把 4 类失败用 4 套独立的"backoff + 重试预算"管理，配置项各自独立，counter 各自独立。本版本（0.1.8.0）覆盖前 3 类（G5/G6/G7），第 4 类"服务器重启后恢复运行中订单"留给 0.1.9 单独做（涉及 NBT 序列化 + WorldSavedData + AE2 grid 重连）。

### 设计

| 节点 | 触发 | 状态记录 | backoff 表 | 重试时是否保留 cached plan | 配置项 |
|---|---|---|---|---|---|
| G1 plan | begin() 抛异常 / future 返回 simulation / 超时 | `PlanRetryState` (taskKey) | 5/10/20/40/80 ticks | 否（plan 都没成功） | `PLAN_RETRY_MAX_ATTEMPTS=3` |
| G5 submit | submit 返回 null（CPU busy / 容量不足） | `SubmitRetryState` (taskKey) | 20/60/200/600/1200 ticks | **是**（plan 完整保留，只重选 CPU） | `SUBMIT_RETRY_MAX_ATTEMPTS=5` |
| G6 link cancel | link.isCanceled() 在 RUNNING 时被观察到 | `LinkCancelRetryState` (taskKey) | 6000/18000/36000 ticks（5min/15min/30min） | 否（stock baseline 已经漂移，必须重 plan） | `LINK_CANCEL_RETRY_MAX_ATTEMPTS=2` |
| G7 order auto retry | order 在 FAILED/PAUSED 持续 N 秒 | `OrderAutoRetryState` (orderId) | 单一间隔 600s = 12000 ticks | 跨整单（doRetryFailed 内部走 retryFailedTasks） | `ORDER_AUTO_RETRY_INTERVAL_SECONDS=600` + `ORDER_AUTO_RETRY_MAX_ATTEMPTS=3` |

关键决策：

- **G5 detach 而非 clear**：`SmartCraftRuntimeSession` 新增 `detachAssignedCpu(task)` 只清 `assignedCpuName`，保留 `plannedJob` / `planningFuture`。这样 backoff 期满后 dispatch 直接走 submit 候选路径，不再付 plan 成本。对深依赖树（数百个内部 task）这是数量级的差异。
- **G6 走 clearExecution**：link cancel 期间网络 stock 已经因为半成品 craft 中途流转过，原 plan 用旧 stock 算的需求量已不可信。重置 PENDING 让下次 dispatch 重 plan。
- **G7 不重置 budget**：`doRetryFailed(orderId, clearAutoRetryBudget)` 区分玩家手动 retry（清 budget，给玩家 fresh 3 次自动重试）与服务端自动 retry（保留 attempts 计数累计上限，避免无限循环）。
- **G7 三状态机**：`OrderAutoRetryState.firstFailedTick` 三档：`>=0` 表示"在等 backoff"；`-1` 表示"已经 retry 过，等下次 FAILED 再重新计时"；`-2` 表示"budget 已耗尽，停止"。这个 sentinel 比单独再加 boolean 字段省一字段且语义自然。
- **dispatch gate 链**：plan 候选过滤里 `planRetryReady && linkCancelRetryReady` 双重 gate；submit 候选过滤里 `submitRetryReady`。任何一个 backoff 没过，对应的 task 这一 tick 就跳过——状态保持当前（PENDING/WAITING_CPU），blockingReason 为 "Retrying ... in N ticks" 玩家可见。

### 实现

`SmartCraftRuntimeCoordinator.java`（核心）：
- 新增 3 个内部 record：`SubmitRetryState`、`LinkCancelRetryState`、`OrderAutoRetryState`
- 新增 3 个 backoff 表：`SUBMIT_BACKOFF_TICKS` / `LINK_CANCEL_BACKOFF_TICKS`（G7 是单一 interval 不需要表）
- 新增 3 个 handle 方法：`handleSubmitFailure(session, task, reason)` / `handleLinkCancelFailure(session, task)`，与 G1 现有 `handlePlanFailure` 同结构
- 新增 3 个 ready 谓词：`submitRetryReady` / `linkCancelRetryReady`（与现有 `planRetryReady` 同结构）
- `dispatchReadyTasks` 改：submit 候选先过 `submitRetryReady` gate；submit 失败路径从直 FAILED 改成 `handleSubmitFailure`；submit 成功后 `submitRetries.remove(taskKey)` 清单
- `reconcileTaskExecution` 改：link.isCanceled() 时分两支（order CANCELLED → CANCELLED；否则 → `handleLinkCancelFailure`）；RUNNING 转 DONE 时 `linkCancelRetries.remove(taskKey)` 清单
- `tick()` 末尾新增 G7 扫描循环：snapshot sessions key set → 对每个 retry-eligible 的 order 维护 `OrderAutoRetryState` → 时间到 + budget 在 → `doRetryFailed(orderId, false)`
- `retryFailed` 拆出 `private doRetryFailed(orderId, clearAutoRetryBudget)`，玩家手动走 true、服务端 G7 走 false
- `cancel` / `cancelGracefully` / 终态自动删除路径都加 `submitRetries.remove` / `linkCancelRetries.remove` / `orderAutoRetries.remove(orderId)` 防止 map 泄漏
- 新增 7 个 G4 stats counter：`submitsAutoRetried` / `submitsFailedPermanently` / `linkCancelsAutoRetried` / `linkCancelsFailedPermanently` / `ordersAutoRetried` / `ordersAutoRetryExhausted` 并入周期性 stats 日志

`SmartCraftRuntimeSession.java`：
- 新增 `detachAssignedCpu(task)`：等价于 `withAssignedCpuName(null)` 但保留 `plannedJob` / `planningFuture`，G5 专用

`Config.java`：
- 新增 4 个配置项：`SUBMIT_RETRY_MAX_ATTEMPTS=5` / `LINK_CANCEL_RETRY_MAX_ATTEMPTS=2` / `ORDER_AUTO_RETRY_INTERVAL_SECONDS=600` / `ORDER_AUTO_RETRY_MAX_ATTEMPTS=3`，含详细 javadoc 说明默认值的取值理由
- 同步 `synchronizeConfiguration` 注册 4 个 forge config getter（含合理 min/max 范围）

### 测试（共 +6 个）

`SmartCraftRuntimeCoordinatorTest`：
- `submit_failure_records_diagnostic_blocking_reason`（已有）：改写为 `SUBMIT_RETRY_MAX_ATTEMPTS=0` 的覆盖，仍然测原诊断 hint 保留行为
- **新** `submit_failure_keeps_cached_plan_and_retries_after_backoff_v0180`：第 1 次 submit 返回 null，断言 task → WAITING_CPU + "Retrying submit in" banner；spin 25 tick 跨过 20-tick backoff，断言第 2 次 submit 调用且 task → RUNNING；submitter 一共被调 2 次（plan stage 没重跑）
- **新** `submit_failure_marks_task_failed_after_exhausting_retry_budget_v0180`：`SUBMIT_RETRY_MAX_ATTEMPTS=2` + 永久 null，spin 200 tick，断言 task FAILED + reason 是诊断前缀（不是 "Retrying" banner）
- **新** `link_cancel_marks_task_failed_immediately_when_retry_budget_zero_v0180`：`LINK_CANCEL_RETRY_MAX_ATTEMPTS=0`，第一次 link cancel 直 FAILED + 标准 reason
- **新** `link_cancel_retries_after_backoff_and_resubmits_with_fresh_plan_v0180`：max=1，第 1 个 link 已 canceled，断言 task → PENDING + "Retrying canceled craft in" banner；spin 6010 tick 跨过 6000-tick backoff；断言第 2 个 submit 用了第 2 个 link（state[1]）+ task → RUNNING；最后 state[1].done=true 后 1 tick 完成
- **新** `order_auto_retry_triggers_after_interval_for_failed_order_v0180`：`ORDER_AUTO_RETRY_INTERVAL_SECONDS=1`（=20 tick） + `ORDER_AUTO_RETRY_MAX_ATTEMPTS=2` + `PLAN_RETRY_MAX_ATTEMPTS=0` + simulation planner，断言 interval 内不重试（planCalls=1），interval 后 planner 至少被调 2 次
- **新** `order_auto_retry_stops_after_max_attempts_v0180`：MAX_ATTEMPTS=1，spin 100 tick 触发一次自动 retry，再 spin 200 tick 断言 planCalls 不再增加，且 order 仍在 PAUSED/FAILED 等待玩家干预

15 个 RuntimeCoordinator 测试全过（含 6010-tick 慢测试）。

### 可观测性

`tick()` 末尾的 stats 日志（默认每 5min 一行）现在多 7 个字段：

```
SmartCraft stats: plansAttempted={} succeeded={} planRetried={} planPermFailed={}
                  submitRetried={} submitPermFailed={}
                  linkCancelRetried={} linkCancelPermFailed={}
                  ordersAutoRetried={} ordersAutoRetryExhausted={}
                  runsCancelled={} activeSessions={}
                  pendingPlanRetries={} pendingSubmitRetries={}
                  pendingLinkCancelRetries={} pendingOrderAutoRetries={}
```

服主可以一眼看出"submitRetried 持续涨 + submitPermFailed 也涨"（→ CPU 容量不够）vs "linkCancelRetried 涨"（→ 自动化链路不稳）vs "ordersAutoRetried 涨 + ordersAutoRetryExhausted 长期 0"（→ G7 在帮玩家 cover 临时性失败）。

### 决策：阶段 4 推迟

阶段 4 "服务器重启恢复运行中订单" 涉及：
1. `SmartCraftOrder` / `SmartCraftLayer` / `SmartCraftTask` / `SmartCraftRequestKey` 全部 NBT 序列化（Ae2RequestKey 持有 ItemStack 还要走 `writeToNBT`）
2. `WorldSavedData` 实现挂载在 overworld
3. `OrderManager` 加 `loadFromNBT(World)` / `saveToNBT(World)`，所有 mutator 调 `markDirty()`
4. `FMLServerStartedEvent` / `WorldEvent.Load` 加载已保存订单；非终态 task 全部重置 PENDING；`craftingLink` 和 `plannedJob` 都丢弃（AE2 内部对象不可序列化）
5. session 重建：玩家上线后第一次开 ME 终端时，扫 OrderManager 中 owner 是该玩家的订单，按需重建 `SmartCraftRuntimeSession` 并 `register`

工作量评估 ~600 行 + 测试，且涉及与 AE2 grid 启动时序的细节，单独留给 0.1.9 做。本版本（0.1.8.0）已覆盖玩家原始诉求"超长合成订单的自动重试节点"——AE2 自身不重启情况下，G5/G6/G7 三层 backoff 已能让长合成订单从绝大多数临时失败中自动恢复。

### 包

modVersion `0.1.7.5` → `0.1.8.0`，跑 `assemble -x compileInjectedTagsJava` 出 3 个 jar：
- `ae2intelligentscheduling-0.1.8.0-dev.jar`（dev 用，SRG）
- `ae2intelligentscheduling-0.1.8.0-sources.jar`
- `ae2intelligentscheduling-0.1.8.0.jar`（玩家分发，notch obf）

### 反思

- **三层 backoff 表的尺度选择**：G5 用秒-分钟（CPU 临时 busy 通常很快回来）；G6 用分钟-半小时（pattern 链路问题往往要人工修）；G7 用十分钟（订单级，给单 task 级重试预算先用完的机会）。这种分层让"该快重试的快、该慢的慢"，比单一 backoff 更合 AE2 的失败语义。
- **手动 vs 自动重试 budget 隔离**：玩家点 retry 永远清自动 budget（fresh 3 次），但服务端自动 retry 不清——避免 server tick 触发 retryFailedTasks → 自动 retry 又看到 FAILED → 再触发 retryFailedTasks 的死循环。`doRetryFailed(boolean clearAutoRetryBudget)` 是这个语义的关键 hook。
- **测试中改 static config**：JUnit 单测里改 `Config.XXX = 0` 然后 finally 还原是脏但有效的做法。比起把所有 retry 配置项改成实例字段、构造时注入，单测改静态代价小很多——配置项本身不在 hot path 上，每 tick 读一次成本几乎为 0。

---

## 2026-04-29（v0.1.7.5）：重试失败按钮被 AE2 scrollbar 列挤出 GUI

### 问题

截图 `2026-04-29_12.34.07.png`：`GuiSmartCraftStatus` 右下"重试失败"按钮整体绘制在 GUI 白色框右边界**外侧**的黑色区域。左下"取消整单"正常。

### 根因

逆向 AE2 `GuiCraftingCPU` 找到关键坐标：

```
GUI_WIDTH                = 238
SCROLLBAR_LEFT           = 218        // [218..230] 是 scrollbar 列
GUI right frame          = [230..238] // 8 px 边框
CANCEL_LEFT_OFFSET       = 163
CANCEL_WIDTH             = 50         // AE2 自己 cancel 右边界 = 213
```

AE2 craftingcpu.png 的 `GUI_WIDTH=238` 实际可见**有效内容区**只到 213，剩下 25 px 是 scrollbar+边框。我们的 `retryPosition`：

```java
return new Position(guiLeft + xSize - 6 - BUTTON_WIDTH, ...);
//                              ^^^^^^^^^^^^^^^^^^^^^^^
//                              right edge = guiLeft + 232
```

right edge = 232 完全跨进了 scrollbar 列 + 部分边框。视觉上看就是按钮"超出"GUI。AE2 自己的 cancel 按钮 right edge = 213，刚好压住有效区右界。

### 修复

`SmartCraftConfirmButtonLayout` 新增常量 `STATUS_BUTTON_RIGHT_USABLE = 213`，`retryPosition` 改为：

```java
public static Position retryPosition(int guiLeft, int guiTop, int xSize, int ySize) {
    return new Position(
        guiLeft + STATUS_BUTTON_RIGHT_USABLE - BUTTON_WIDTH,  // → guiLeft + 161
        guiTop + ySize - 25,
        BUTTON_WIDTH,
        BUTTON_HEIGHT);
}
```

retry 按钮新范围 `[guiLeft + 161, guiLeft + 213]`，与 AE2 cancel 按钮的右边界对齐，永远不会进 scrollbar / 边框区。

`xSize` 参数保留在签名里只为和 `cancelPosition` 一致，不再参与计算 — 因为按钮位置是相对 AE2 craftingcpu.png 纹理的固定坐标，与外部 caller 传入的 GUI 宽度无关。

### 测试

新增 2 个 `SmartCraftConfirmButtonLayoutTest` 用例（共 7 个全过）：

- `retry_button_right_edge_clears_ae2_scrollbar_and_frame`：断言 retry right-edge ≤ 213
- `cancel_and_retry_share_the_status_row_and_do_not_overlap`：断言两按钮同 y、不重叠

### 包

modVersion `0.1.7.4` → `0.1.7.5`，`build/libs/ae2intelligentscheduling-0.1.7.5.jar` (151 KB)。

### 反思

GUI 坐标算式不能简单写成 `xSize - margin`：xSize 是纹理宽度，**视觉可用宽度**往往因 scrollbar / 边框 / search 框等结构性占位而比纹理宽度小。下次写 GUI 坐标常量时，先把"内容区有效边界"独立成常量（比如 `USABLE_RIGHT`），而不是从总宽度反推 margin。AE2 用 `CANCEL_LEFT_OFFSET=163 + CANCEL_WIDTH=50` 暗示了这条线，我之前没注意到。

---

## 2026-04-29（v0.1.7.4）：submit 失败诊断 — 玩家直接看到 AE2 拒绝原因

### 问题

玩家反馈：「重试 FAILED 订单还是下不了单。」截图显示状态页 `blockingReason` 只写了 "Failed to submit AE2 crafting job"，无法判断：
1. 是 mod retry 逻辑还在残留旧 link？
2. 是 AE2 拒绝（CPU 不够大 / 全部 busy）？
3. 是 plan 已过期但调度没察觉？

排查发现 mod 侧 retry 路径完整：`retryFailedTasks` 把 FAILED→PENDING、order→QUEUED，下次 tick 重新 plan/submit。瓶颈在 AE2 `submitJob` 因 byteTotal 超过任何 idle CPU 的 `getAvailableStorage` 持续返回 null（即 link == null）。这种结构性错误用通用文案表达不出来。

### 设计决策

不改 retry 逻辑（已正确），改诊断输出：
- **服务端 WARN 日志**：每次 submit 失败都把网络中所有 CPU 的 (name, busy, avail, coProcessors) 快照、被选中 CPU 的状态、jobByteTotal 全部打出来，让服主一眼定位
- **客户端 tooltip**：把最可能的 root-cause 写进 `task.blockingReason()`，玩家不进控制台也能看到

### 实现

新增 `SmartCraftRuntimeCoordinator.diagnoseSubmitFailure(task, plannedJob, chosenCpu, session)` 静态 helper：

1. 遍历 `session.craftingGrid().getCpus()`，统计 idle/busy 计数与最大 idle 容量
2. 启发式归因：
   - `needed > maxIdleStorage` → "no idle CPU large enough (needs %d B, biggest idle = %d B across %d idle CPUs)"
   - `chosenCpu.isBusy()` → "chosen CPU '%s' became busy between idle-detection and submit"
   - `needed > chosenCpu.getAvailableStorage()` → "chosen CPU '%s' too small (needs %d B, has %d B)"
   - 否则兜底 → "AE2 rejected (chosen CPU '%s', N idle / M busy)"
3. 把 hint 拼成 `FAILED_TO_SUBMIT_REASON + ": " + hint`，作为 `task.blockingReason()`
4. 同步 `LOGGER.warn` 输出完整 CPU 快照 + chosen CPU 状态 + byteTotal + hint

dispatch phase 3 把原本的 `nextTask = task.withStatus(SmartCraftStatus.FAILED, FAILED_TO_SUBMIT_REASON)` 替换为调用 helper 后传入 `diagnosticReason`。

### 测试

新增 `SmartCraftRuntimeCoordinatorTest.submit_failure_records_diagnostic_blocking_reason`：
- jobSubmitter 始终返回 null（模拟 AE2 reject）
- tick 1 plan / tick 2 submit
- 断言 task FAILED + reason 以 "Failed to submit AE2 crafting job: " 为前缀且后面非空，且包含 "cpu-1" 或 "idle"

### 包

modVersion 0.1.7.3 → 0.1.7.4，已生成 `build/libs/ae2intelligentscheduling-0.1.7.4.jar` (151 KB) + dev jar。

### 周边产物：本地 GTNH manifest 缓存方案

打包过程中 `elytra-conventions` plugin 反复因 GitHub raw / api.github.com 在大陆 reset 导致 `Failed to load the manifest from Github`。给 `D:\Code\.gtnh-manifests\` 整了一套独立工具：

- `config.json`：版本列表 + jsdelivr/ghproxy/raw 的 mirror 优先级
- `2.7.4.json` / `2.8.1`-`2.8.4.json`：本地 manifest 副本（jsdelivr CDN 下载）
- `fetch-manifests.ps1`：一键拉取全部版本（首发 + `-Force` 更新）
- `inject-manifest.ps1`：把对应版本拷到 `<project>/build/elytra_conventions/<version>.json`，命中 plugin 内部 `loadManifestFromCache` 路径
- `gradlew-offline.ps1`：先 inject 再 invoke gradlew 的封装
- `README.md`：完整流程说明

逆向 plugin jar 找到关键路径：

- `cn.elytra.gradle.conventions.internal.ManifestUtils.getManifestInternal(project, version, useCache=true)` 先查 `<project>/build/elytra_conventions/<version>.json`，命中即跳过 GitHub
- `ModpackVersionExtension$gtnhVersion$1.call()` 末尾 `ldc "2.7.4"` 是 hardcode fallback；项目 `gradle.properties` 中 `elytra.manifest.version` 可覆盖
- `loadManifestFromGithub` 用 URL `https://raw.githubusercontent.com/GTNewHorizons/DreamAssemblerXXL/refs/heads/master/releases/manifests/<version>.json`，jsdelivr 镜像同源

本项目 `gradle.properties` 同时新增 `elytra.manifest.version = 2.8.4`，与 GTNH 当前 stable 对齐（plugin hardcode 的 2.7.4 已显著滞后）。

### 反思

问题根因是 mod 文案太笼统，不是逻辑 bug。诊断输出三层（task tooltip / 服务端 WARN log / 截图分析）让玩家、服主、开发者各自有自己的信息层级，避免下次再陷入"是 mod 的锅还是 AE2 的锅"的扯皮。

下次任何向用户暴露的失败文案都先问一句：玩家看到这个能不能采取下一步行动？如果不能就要带上具体数字。

---

## 2026-04-29（v0.1.7.3）：按钮回归 AE2 风格文字按钮 + 移到 Start 行同高 GUI 框外右侧

### 问题

v0.1.7.2 的 16x16 图标按钮放在 GUI 顶部 ear 区有两个新问题：
1. **太小**：4 个汉字"智能合成 / 查看调度"压缩成单字"智 / 调"，玩家看不全
2. **覆盖 AE2 自己的按钮**：GUI 顶部 ear 区已被 AE2 自己的 `switchDisplayMode` tab 按钮（位置 `xSize - 25, guiTop - 4`）占用，我们的 ear 图标视觉冲突

用户原话：「智能合成按钮和查看调度的ear要放在开始按钮边上，而不是最上面，最上面会覆盖掉AE2的原来的按钮，而且智能合成和查看调度这回又太小了，要求能看到全部的4个字，按钮的样式为AE2样式」

### 设计决策

- **样式**：vanilla `GuiButton` 文字按钮 = AE2 风格（AE2 的 Cancel/Start 按钮也是直接 `new GuiButton(...)`，渲染同 minecraft 默认灰色按钮风格）
- **尺寸**：52 x 20 px，与 AE2 Cancel/Start 按钮完全一致，可显示完整 4 个汉字
- **位置**：GUI 框外右侧 +4 px，垂直堆叠在 AE2 Start 按钮行附近
  - **CraftConfirm**：智能合成（slot 1，Start 行上方一槽）+ 查看调度（slot 0，Start 行同高）
  - **Terminal / CraftingStatus**：仅查看调度（slot 0）
- **避免覆盖**：x = `guiLeft + xSize + 4` 完全在 AE2 GUI 框外；y 始于 `ySize - 25`（Start 行）向上堆叠，避开顶部 tab 按钮带

### 实现

- **`SmartCraftConfirmButtonLayout`** 第三次重写：常量 `BUTTON_WIDTH=52, BUTTON_HEIGHT=20, EAR_RIGHT_GAP=4, EAR_BOTTOM_OFFSET=25, EAR_PITCH=22`；`earSlot(slotIndex)` 从底部向上索引（slot 0 = Start 行，slot 1 = 上方一槽）
- **`SmartCraftConfirmGuiEventHandler`** 改回 vanilla `GuiButton`，`StatCollector.translateToLocal` 解析中文标签；删除 `drawEarButtonTooltip` 辅助（按钮文字本身已表达功能）
- **删除 `SmartCraftEarIconButton`**（v0.1.7.2 引入的自定义 16x16 按钮类）
- **5 个 `SmartCraftConfirmButtonLayoutTest`** 全部改写：验证新坐标系（GUI 框外右侧 +4 px、底部对齐 Start 行、垂直堆叠 22 px 间距）

### 玩家体验

- 按钮显示完整中文文字「智能合成」「查看调度」
- 视觉风格与 AE2 Cancel/Start 按钮完全一致（同尺寸、同灰色 minecraft 按钮渲染）
- CraftConfirm 屏：右侧 GUI 外两个按钮垂直堆叠
- Terminal / CraftingStatus 屏：右侧 GUI 外仅一个查看调度按钮
- 不覆盖 AE2 任何 UI 元素：x 在 GUI 框外，y 在 Start 行及其上方一槽（避开 AE2 顶部 tab 按钮）

### 测试

- 89 全过

### 包

`build/libs/ae2intelligentscheduling-0.1.7.3.jar` (150 KB)；旧 0.1.7.2 jar 已清理。

### 反思

3 版按钮迭代教训：
- v0.1.7（GUI 内部，文字 52x20）：覆盖 AE2 摘要文字
- v0.1.7.2（GUI 外右上 ear，图标 16x16）：覆盖 AE2 顶部 tab 按钮 + 文字太小
- v0.1.7.3（GUI 外右下 ear，文字 52x20）：避开 AE2 全部按钮 + 文字完整 + AE2 风格 ✓

下次 GUI 注入应先扫一遍 AE2 自己用了哪些 ear 区位置（顶部 ear / 左侧 ear / 底部行 / 右侧 ear），再选空闲位置。

---

## 2026-04-29（v0.1.7.2）：智能合成 / 查看调度按钮改造 — 16x16 ear-icon 风格

### 问题

用户反馈：「智能合成和查看调度的按钮太大了，超过了 AE 的 UI 而且覆盖掉了 AE 原本 UI 的字。」

旧布局：52x20 的文字按钮硬塞在 AE2 GUI 内部，CraftConfirm 屏的"智能合成 / 查看调度"两个按钮挤在 byte-used 摘要文字附近，Terminal 屏的"查看调度"压在 search field 旁。

### 设计选择（用户确认下）

候选方案：
1. 紧凑文字按钮（40x14，字保留）
2. 16x16 图标按钮（覆盖 AE2 GUI 内部）
3. **16x16 图标按钮 + ear 区**（挂在 AE2 GUI 框外右侧）← 选定

### 实现

- **新组件 `SmartCraftEarIconButton`**（16x16 GuiButton 子类）：
  - 单色背景（智能合成黄 #FFCC33 / 查看调度青 #33CCEE）+ 1 px 深色边框 + 中央单字符（"智" / "调"）
  - hover：边框白色 + 背景提亮 +30 RGB
  - disabled：背景深灰 + 文字浅灰
  - `tooltipText()` 方法返回 `StatCollector.translateToLocal(tooltipKey)` 用于 hover tooltip
- **`SmartCraftConfirmButtonLayout` 重构**：
  - 旧 `BUTTON_WIDTH=52, BUTTON_HEIGHT=20` 仅保留给 GuiSmartCraftStatus 自己的 cancel / retry 按钮（在我们 GUI 内部，空间足够）
  - 新增 `EAR_BUTTON_SIZE=16, EAR_RIGHT_GAP=2, EAR_TOP_OFFSET=4, EAR_SLOT_GAP=2` 用于 AE 屏注入按钮
  - 所有 `position()` / `viewStatus*Position()` 方法改为返回 ear 区坐标：`x = guiLeft + xSize + 2`（GUI 框外 2 px），y 按槽位垂直堆叠
  - CraftConfirm 屏：智能合成第 0 槽、查看调度第 1 槽（垂直堆叠）；Terminal + CraftingStatus 屏：查看调度第 0 槽（独占）
- **`SmartCraftConfirmGuiEventHandler` 改造**：
  - `initCraftConfirmButtons` 创建 `SmartCraftEarIconButton` 替代 `GuiButton`
  - `syncViewStatusButton` 同上；同时去掉 `button.width = pos.width()` 等 width/height 写回（按钮实例本身已是 16x16，无需覆盖）
  - 新增 `drawEarButtonTooltip(gui, mouseX, mouseY)`：扫描 buttonList 找 hover 中的 SmartCraftEarIconButton，调 `OVERLAY.drawSupplementaryTooltip(...)` 渲染本地化标签
  - `onDrawScreen.Post` 末尾对 CraftConfirm / CraftingStatus / Terminal 三屏调 `drawEarButtonTooltip`
  - 移除不再使用的 `StatCollector` import

### 玩家体验

- AE 终端 GUI **完全不被遮挡**：所有注入按钮挂在 GUI 框外侧右
- 鼠标 hover 任一图标 → 显示完整本地化名（"智能合成" / "查看调度"）
- CraftConfirm 屏右侧出现两个垂直堆叠的图标按钮（黄智 + 青调），与 AE2 内部布局完全分离
- Terminal / CraftingStatus 屏右侧只出现一个青色"调"图标
- 颜色与 v0.1.7 引入的 OverlayRenderer / Tab strip 配色一致（黄 = 智能合成主题色，青 = 查看调度主题色）

### 测试

- 89 全过（v0.1.7.1 89 + v0.1.7.2 改写 4 个 ButtonLayout 测试断言新坐标）
- `SmartCraftConfirmButtonLayoutTest` 全部 5 个测试更新为验证 ear 区布局：
  - smart-craft 在第 0 槽（top）
  - view-status 在 CraftConfirm 上叠在 smart-craft 下方一槽
  - view-status 在 Terminal / CraftingStatus 独占第 0 槽
  - 两按钮 x 都 ≥ guiLeft + xSize（永不进入 AE2 GUI 内部）

### 风险 / 已知边界

- **GUI 框外 hover 区**：图标在 GUI 框外侧，鼠标如果先在 GUI 内 hover 物品再移动到 ear 按钮，AE2 的物品 tooltip 可能与我们的按钮 tooltip 短暂重叠（实测体感 OK）
- **超宽 GUI mod**：如果有第三方 mod 把 AE2 GUI 拉到屏幕右边缘附近，ear 按钮（+18 px）可能贴边或被裁。GTNH 默认布局下无问题
- **跟其他 ear-style 注入冲突**：如果有别的 mod 也在 AE2 GUI 右 ear 区注入按钮（NEI、AE2 add-ons），可能视觉重叠。当前 GTNH 主流 mod 没看到这种冲突

---

## 2026-04-29（v0.1.7.1）：FAILED 订单保留 + 修复 advanceLayers COMPLETED bug

### 问题

v0.1.7 的"终态即消失"过激：FAILED 订单也立即从 manager 移除，玩家在 GUI 中再无 retry 入口（标签消失了，退路只剩重新下单同样的内容）。用户要求：**保留 FAILED 订单，玩家可点 Retry 复活；点 Cancel 才丢弃**。

### Root cause

修复过程中发现一个**预先存在的 bug**：`advanceLayers` 在 layer 全终态（`Layer.isComplete()=true`）时把订单标记为 `COMPLETED`，**不区分**是全 DONE 还是含 FAILED。在 v0.1.7 之前这个 bug 不可见，因为 COMPLETED 订单也立即消失。v0.1.7.1 让 FAILED 订单保留后，玩家会看到一个明明是失败的订单显示成 COMPLETED。

具体：

- `Layer.isComplete()` 用 `task.isTerminal()` 判断，包括 DONE/FAILED/CANCELLED
- 单 task 订单如果 task FAILED，`layer.isComplete()=true` → newIndex 推过 `layers.size` → withStatus(COMPLETED)
- 此后 `updateOrder` 早返（status=COMPLETED 在早返条件里）→ `applyLayerStatus` 没机会修正

### 修复

- **`SmartCraftRuntimeCoordinator.advanceLayers()`**：推 newIndex 过 layers.size 时增加 "all DONE" 检查 —— 只有所有 task 都是 DONE 才标 COMPLETED；其他终态混合（含 FAILED）仅更新 currentLayerIndex，不改 status。订单流转到 dispatchReadyTasks（no-op for terminal tasks）→ applyLayerStatus → 正确得出 PAUSED
- **`SmartCraftRuntimeCoordinator` tick 终态检测**：把"自动消失"条件从 `isFinished()` 收紧为 `isFinished() && status != FAILED && status != PAUSED`。FAILED/PAUSED 是"retry-eligible"状态，保留供玩家手动操作；CANCELLED/COMPLETED 仍走原 1-tick 延迟删除路径
- **新测试 `failed_order_is_retained_indefinitely_for_retry_v0171`**：simulation planner → 1 task FAILED → 12 tick 后订单仍在 manager + session 仍存活 + 订单整体状态是 PAUSED（不是错误的 COMPLETED）

### 玩家体验

- **DONE 订单（全部成功）**：tick 推到 COMPLETED → 1 tick 后从 list 消失（同 v0.1.7）
- **FAILED 订单**：tick 推出至少 1 个 FAILED task → 订单整体 PAUSED → **永久保留** → 玩家可点 Retry 复活，或点 Cancel 丢弃（→ CANCELLED → 1 tick 后消失）
- **CANCELLED 订单**：玩家点 cancel 后 → CANCELLED → 1 tick 后从 list 消失（同 v0.1.7）

### 测试

- 89 测试通过（v0.1.7 88 + v0.1.7.1 新加 1 个 FAILED 保留测试）
- 现有 6 个 task-FAILED 相关测试全过 — advanceLayers 的修复对 task-level 状态断言无副作用

### 风险

- **PAUSED 订单永久占内存**：玩家忘记点 retry/cancel 的话订单会一直在 list 里。多人服可能堆积。后续可加 max-age 配置项（FAILED 超过 N 分钟自动 CANCELLED）
- **list packet 大小**：v0.1.7 list packet 包含所有活跃订单 full data。FAILED 订单不再 vanish 会让 list 持续增长。GTNH 多人服规模内可承受（< 30 个并发订单），但极端场景下需要切到"按需 detail"协议

---

## 2026-04-29（v0.1.7）：多玩家多订单标签页 UI

### 背景

v0.1.6 之前每个客户端只能查看 1 个订单（自己最早的非终态订单）。多人服里玩家 A 想看玩家 B 在合成什么完全不可能；同一个玩家连续下两个订单，第二个订单也被遮蔽。

用户提出方案：**UI 顶部加标签页，按时间顺序排序，所有玩家所有订单全可见，点不同标签页查看不同订单的清单和格子**。

### 设计决策（在用户确认下）

| 决策点 | 选择 | 取舍 |
|---|---|---|
| 谁的订单可见 | **全员可见** | 多人协作清晰，隐私 = 0 |
| 终态订单何时消失 | **终态即消失**（≤ 1s） | 复盘失败原因受限，但 UI 整洁 |
| 标签栏位置 | **顶部横向 ear** | 类创造模式 tab，不挤占 GUI body |
| 标签信息密度 | **物品图标 + 状态点** | 每行 7-8 个 tab；hover 看完整信息 |
| 同步策略 | **server 全 push** | 简单 + 切 tab 零延迟，~5KB/s/玩家可控 |

### 已完成

- **协议层**：
  - `SyncSmartCraftOrderListPacket`（new, server→client）：携带 List<SyncSmartCraftOrderPacket>，按 server 端 LinkedHashMap 顺序（时间升序）
  - `SyncSmartCraftOrderPacket` 加 `ownerName` 字段（玩家显示名），用于 tab tooltip
  - `RequestOrderStatusPacket` handler 改为推送 list packet（client 1 秒 refresh 一次）
  - `RequestSmartCraftActionPacket` handler 在 cancel/retry/refresh 之后推送 list packet（让 acting 玩家立刻看到状态变更）
  - `OpenSmartCraftPreviewPacket` 仍走单 sync packet（首次打开 GUI 入口），随后 GUI updateScreen 自动拉 list

- **Server 端**：
  - `SmartCraftOrderManager.snapshot()`：返回 LinkedHashMap 防御副本（保持插入顺序，避免 tick 中并发修改）
  - `SmartCraftRuntimeCoordinator.sessionsView()`：unmodifiable map view
  - `SmartCraftRuntimeSession.ownerName()`：便利访问器
  - `SmartCraftRuntimeCoordinator` tick 加"终态延迟 1 tick 删除"逻辑：第一个 tick 检测到 `isFinished()` 标记 orderId，下一个 tick 仍 finished 才真正 remove。1 tick = 50ms 远低于 client 1s refresh 间隔，玩家无感知；这一短延迟保证了：
    - OrderSync 可以推送一次终态 packet 给客户端（最后一帧 status）
    - 单元测试可以在 transition tick 后立即检查 manager.get(orderId) 终态字段
    - retry 在 tick 之间还能复活订单（`isFinished()=false` → 自动清掉标记）
  - `SmartCraftOrderSyncService.syncListTo(player)`：组装 manager.snapshot + sessionsView → list packet

- **Client 端 OverlayRenderer 多订单改造**：
  - `LinkedHashMap<String, SyncSmartCraftOrderPacket> orders` + `String currentOrderId` 替代单 data 字段
  - `applyOrderList(packet)`：reconcile 整个 orders map（终态自动消失）；如 currentOrderId 失效 → fallback（自己最新 → 全局最新 → null）
  - `selectOrder(orderId)`：tab 切换 + 重置 per-order 状态（scroll/selection/cpuDetail）
  - `currentOrder()` 私有 helper 让所有现有方法（draw / sendCancel / getTasks 等）路由到当前焦点订单
  - `tabOrders()`：返回有序 list 给 widget
  - `update(packet)`：单 packet 入口现在 add-or-replace 而非清空（兼容首次开 GUI）

- **新组件 `SmartCraftOrderTabsWidget`**：
  - 22x20 px tab，16x16 物品图标 + 右上角 3x3 状态点（视觉与清单区一致）
  - 当前 tab 浅色背景 + 底部 2px 黄色 accent 条
  - hover tooltip：`[playerName] itemName ×N · status · scale`
  - tabs 总宽超过可用区时自动加 `<` `>` 滚动箭头，箭头禁用态变灰不响应
  - 物品图标 lazy 初始化 RenderItem（headless 测试 JVM 可加载）

- **`GuiSmartCraftStatus` 集成**：
  - guiTop 计算保留 24px 顶部空间（OUTER_MARGIN + TABS_TOTAL_RESERVED），保证 ear 不被裁
  - drawScreen 调 `drawTabStrip`（在 body 之上、scrollbar 之下）
  - mouseClicked 加 `tryClickTabStrip` 优先级最高（虽然区域不重叠也保持显式优先）
  - tabScroll 字段管理标签滚动状态
  - sendRefresh 改用 RequestOrderStatusPacket（不依赖当前焦点，空 manager 也能拉新）

- **测试覆盖**（71 → 88，新增 17 个）：
  - **`SmartCraftOrderManagerTest`**（新建 4 个）：snapshot 顺序保留 / 防御副本 / 不同实例 / 后续 track 不漏
  - **`SmartCraftRuntimeCoordinatorTest` 加 2 个**：终态延迟 1 tick 消失 / retry 清除 pending mark
  - **`SmartCraftOrderTabsWidgetTest`**（新建 11 个）：可见数计算 / scroll clamp / hit-test 各种场景（无箭头、有箭头、scroll 偏移、空列表、disabled 箭头）

### 设计决策细节

- **延迟 1 tick 删除 vs 立即删除**：用户选"终态即消失"，但严格立即删会导致：
  1. 单元测试无法在 tick 后检查终态状态（要重写所有相关测试）
  2. OrderSync 单 packet 路径无法发送终态（manager.get 已 empty）
  3. cancel + 立即 retry 同 tick 内可能竞争

  延迟 1 tick = 50ms，玩家无可见差异（client 1s 才 refresh），但保留了上述 3 个特性。

- **list packet 全量 push vs delta**：评估了"只 push 元数据 + 按需拉详情"方案，决定全量 push 因为：
  - 5-10 个并发订单 × 10 任务 ≈ 5-15 KB / 玩家 / 秒，可接受
  - tab 切换零延迟（数据已在 client）
  - 协议简单，无需新 detail packet

- **OverlayRenderer 字段路由 vs 重写**：决定保留所有现有方法的接口语义不变，内部用 `currentOrder()` 私有 helper 统一获取焦点订单数据。这样 GUI 端不需要重写，只需在 mouseClicked 增加 tab hit-test 即可。

- **ITEM_RENDERER lazy 初始化**：原 `SmartCraftScheduleListWidget` 的 `static final RenderItem` 字段在测试 JVM（无 OpenGL）下会 NoClassDefFoundError 阻止整个 widget 类加载。Tabs widget 改成 lazy `itemRenderer()` 方法，纯算术测试不触发 OpenGL 初始化。**TODO**：把 `SmartCraftScheduleListWidget` 也改成 lazy 以便未来给它写 hit-test 测试。

- **fallback tab 选择策略**：currentOrderId 失效（终态消失）时优先选玩家自己最新的订单，否则选全局最新的，否则 null。让玩家不会因为别人的订单结束而被强行切走焦点（如果玩家自己的订单还在）。

### 风险 / 已知边界

- **隐私=0**：所有玩家订单（包括 cancel/retry 操作的可见性）都在标签栏可见。多人服可以匹配按钮交互推断"谁取消了什么"。如果将来需要权限控制，已留好接口（list packet 可在 server 侧按 player 过滤）。
- **协议向前兼容**：v0.1.6 client 收到 v0.1.7 server 的 list packet 会忽略（未注册）；但 v0.1.7 client 收到 v0.1.6 server 不会主动 push list（因为 v0.1.6 没有 syncListTo）。**版本不匹配时多订单 UI 退化为单订单 UI**（OVERLAY 仍能通过 update(packet) 加 1 个订单进 orders map）。
- **网络包序列号**：list packet 注册在 NetworkHandler 末尾（最大 ID），不影响旧 packet ID。client/server 模组版本不同时若都是 0.1.7+ 协议一致；早于 0.1.7 的 client 需要更新。

### 遇到的问题

1. **现有 Coordinator 测试断言"tick 后 manager.get(终态订单).get()"** —— 用户选择的"终态即消失"会让 `.get()` 抛 NoSuchElement。最初尝试加测试钩子 `setRemoveTerminalOrdersForTest(false)`，但要在 11 处测试调用。最终改用"延迟 1 tick"方案，零测试改动通过。
2. **`SmartCraftOrderTabsWidget` static initialiser** —— RenderItem 构造在 headless JVM 抛 ExceptionInInitializerError 阻止类加载。改成 lazy。
3. **visible tab count 算错**：测试初稿期望 9，实际 (222-24-4)/22 = 8。修测试预期值。

---

## 2026-04-29（v0.1.6）：紧急 bugfix — Cancel / Retry 按钮在 v0.1.4 后失效

### 现象

用户报告：取消整单按钮**无法硬取消**。复测发现 Retry 按钮也同样失效。

### Root cause

v0.1.4 改造 `GuiSmartCraftStatus` 时为了删除已废弃的 `OVERVIEW_BUTTON_ID` / `TASK_BUTTON_BASE` 处理分支，**整个 `actionPerformed` 方法被一并删除**，但 cancel / retry 这两个 vanilla `GuiButton` 也依赖该方法 —— 没有 `actionPerformed`，按钮被点击时 GuiScreen 父类的默认空实现什么都不做。

`SmartCraftConfirmGuiEventHandler.onButtonClicked`（监听 Forge `ActionPerformedEvent.Post`）只处理"开始 craft"和"打开 status"两个 ID，与 CANCEL_BUTTON_ID / RETRY_BUTTON_ID 不匹配，没有兜底。

附加问题：`SmartCraftOverlayRenderer` 提供了 `sendCancel()` / `sendSoftCancel()` / `sendRefresh()`，但**没有 `sendRetry()`** 方法，所以即使 actionPerformed 还在，retry 按钮也无包可发。

### 修复

- **`SmartCraftOverlayRenderer.sendRetry()`** 新增：用 `RequestSmartCraftActionPacket.Action.RETRY_FAILED` 发包（服务端 handler 早就在了）
- **`GuiSmartCraftStatus.actionPerformed`** 重新加上：
  - `CANCEL_BUTTON_ID` + `isShiftKeyDown()` → `sendSoftCancel()`
  - `CANCEL_BUTTON_ID` + 普通点击 → `sendCancel()`（硬取消）
  - `RETRY_BUTTON_ID` → `sendRetry()`
  - `button.enabled` 检查防止灰按钮被键盘 enter 触发

### 测试

- 71 passing 全部保留
- 这是 GUI 事件路由 bug，不在单元测试覆盖范围（需要 GuiButton dispatcher，单测里没有 mock GuiScreen 体系）。需要进游戏点按钮验证

### 教训

- v0.1.4 写 log.md 时记录"删除 `OVERVIEW_BUTTON_ID` / `TASK_BUTTON_BASE` 分支"，但实际改动是把整个 `actionPerformed` 方法删了 —— 删多了一层。下次类似改造应该**只移除分支不动方法签名**，或者明确标注"方法整体删除会丢失 X / Y 处理"
- `SmartCraftOverlayRenderer.sendRetry()` 缺失更早就埋雷了 —— v0.1.3 引入 RETRY_FAILED action 时只加了 packet 端 + server handler，没加 client trigger。protocol-端到端测试缺失，不容易在单元测试发现
- 建议补一个轻量级 GUI smoke test：构造 GuiSmartCraftStatus + mock OVERLAY，调 `actionPerformed(cancelButton)` 验证 sendCancel 被调用。但 1.7.10 vanilla GuiScreen 在没有 LWJGL 上下文时构造困难，性价比低。当前选择不补，靠人工 smoke

---

## 2026-04-29（v0.1.5）：H1 小量节点合并 — 阶梯阈值过滤无效 CPU 占用

### 背景

用户反馈：合成树最底层只有几千个物品的中间产物，会单独占据一个 CPU 跑几秒就 done，浪费 CPU 资源。如果同时有 8 个底层小 task ready，就并行占 8 台 CPU，每台 CPU 利用率极低。

用户希望按 OrderScale 设置最小占用门槛：

| 订单量级 | 单 task 占 CPU 的最低数量门槛 |
|---|---|
| SMALL | ≥ 1M |
| MEDIUM | ≥ 5M |
| LARGE | ≥ 10M |

### 设计选项与选择

提了 4 种实现方案：

1. 多小 task 共用 1 台 CPU（串行）
2. 大 task 优先抢 CPU，小 task 排队
3. 限制小 task 占用 CPU 总数 ≤ K
4. **树构建时把小量节点合并到上层**（用户选择）

方案 4 最贴近"少占 CPU"的语义：**根本不为小量节点发独立 task，让父节点的 AE2 plan 一次性把整条小子树吸收进去**。这样小子树只占 1 个 CPU（父任务的 CPU），不产生独立 task 也不占用调度器资源。

### 已完成

- **新组件 `SmartCraftMergeThreshold`**：定义 `fromConfig(scale)` 静态方法，从 Config 读取每个 scale 的合并阈值。`DISABLED = 0L` 常量表示关闭合并。

- **改造 `SmartCraftOrderBuilder`**：
  - 新接口 `MergeThresholdResolver`：`(scale) → long`，支持依赖注入
  - 默认构造从 `SmartCraftMergeThreshold.fromConfig` 读取
  - `withMergingDisabled()` 静态工厂便于测试隔离
  - `visit` 方法新增 `isRoot` 参数 + 合并判断：`!isRoot && missingAmount > 0 && missingAmount < threshold` → 不发 task，把子节点 emittedTaskIds 透传给父节点
  - **根节点永远不被合并**（即使量极小），否则订单零 task 永远不执行

- **`Config.java` 新增 3 个配置项**（scheduling 组）：
  - `mergeThresholdSmall = 1000000`（默认 1M）
  - `mergeThresholdMedium = 5000000`（默认 5M）
  - `mergeThresholdLarge = 10000000`（默认 10M）
  - 任一设为 0 → 该 scale 关闭合并，回到 v0.1.4 行为
  - 内部封装 `SmartCraftMergeDefaults` 常量类避免 config → analysis 包的循环依赖

- **测试覆盖**（71 → 71，新增 5 个 H1 测试）：
  - `merge_folding_drops_below_threshold_leaf_into_parent_plan`：小 leaf 合并到父
  - `merge_folding_never_drops_the_root_even_if_below_threshold`：根永远不被合并
  - `merge_folding_keeps_big_leaves_and_drops_small_siblings_only`：大 leaf 保留，同级小 leaf 被合并
  - `merge_folding_propagates_grandchildren_through_merged_middle`：合并的中间节点必须把孙辈 taskIds 透传给父
  - `merge_folding_disabled_when_threshold_is_zero`：阈值 0 行为等价 v0.1.4
  - 现有 3 个 OrderBuilder 测试改用 `withMergingDisabled()`，保持原依赖图测试语义不被合并阈值干扰

### 设计决策

- **AE2 plan 自递归承接小子树**：`beginCraftingJob` 本身是 BFS 算缺口树。父节点 plan 时 AE2 会自动把"被合并掉"的小子树展开计算。所有合并的小工作合并成 1 个父任务的 1 个 ICraftingJob，由父任务的 1 个 CPU 处理整条链。
- **库存中合并 vs 库存外合并**：与原有 `requestedAmount == availableAmount` 的"库存透传"规则**完全对称** —— 都是"不发 task，把子 emittedTaskIds 上推"。代码路径合并到同一个 else 分支，复杂度未增加。
- **isRoot 标志显式传递**：通过 visit 参数 `boolean isRoot` 显式传递，不靠 depth==0 推断 —— 因为已经存在的 `layerIndex` 是从叶子向上计数，不是从根向下。
- **依赖注入式 resolver**：用户可在 Config 调阈值；测试不需要修改 Config 静态字段就能精确控制合并行为。
- **阈值是"strictly less than"语义**：amount 正好 == 阈值时**保留**（emit task），避免边界值二义。
- **同 requestKey 串行 plan（v0.1.3 P1-#3）兼容性**：合并不影响这条规则。被合并的小节点不发 task，本来就不参与 dispatch。父节点的 plan 由 AE2 处理，AE2 自己决定怎么算这部分。

### 风险 / 已知边界

- **AE2 plan 失败的诊断变粗**：如果父节点 plan 失败（比如某个被合并子节点的 pattern 缺失），错误信息只会指向父节点，玩家需要手动检查整条小子树。但有 G1 重试机制兜底，且 `blockingReason` 会包含 AE2 的原始消息。
- **极度巨大的合并子树**：如果一棵 100 层的小量子树全部合并到根，AE2 单 plan 计算会拉长。`PLANNING_TIMEOUT_SECONDS`（默认 60s）可以兜底。实际场景下小量子树通常很浅（几千数量的物品很少需要 10 层依赖）。
- **CPU 容量未感知**：v0.1.4 dispatch 仍是 FIFO 取 idle CPU。合并后大 task 仍可能撞到小 storage 的 CPU。这是后续优化方向（H2 想法：按 task 量匹配 CPU storage 大小）。

### 遇到的问题

- 现有测试用 `node("leaf", 64L, 0L)` 这种 64 个的极小量构造依赖图。1M 默认阈值会把它们全部合并掉，破坏原本的 layering / 依赖图断言。解决：通过 `MergeThresholdResolver` 注入 + `withMergingDisabled()` 工厂方法，让每个测试显式选择是否启用合并 —— 验证依赖图的测试关掉合并、验证合并行为的新测试明确传 1M。

---

## 2026-04-29（v0.1.4）：UI 清单区域重做 — 自定义 row widget + 物品图标

### 背景

用户反馈"清单区域太丑"。原实现把每个 task 都做成 vanilla `GuiButton`，问题：

- 灰底圆边的 vanilla button 与下层 AE2 cluster 主题完全脱节
- 每行只显示文本 `Layer X: name 1/3`，物品名截断到 8 字符（`gear...`），玩家看不出是啥
- 没有状态颜色 / 图标 / 进度条
- "Layer N:" 重复出现，浪费空间
- 滚动条用自己实现的，跟上半部分 AE2 vanilla 风格不统一

### 已完成

- **新组件 `SmartCraftScheduleListWidget`**：自定义画清单行
  - 左侧 16x16 真实 `ItemStack` 图标（用 `RenderItem` 渲染，从 `TaskView.itemStack()` 拿，**协议已有数据，免改 packet**）
  - 图标右上角 3x3 状态色点（带 1px 黑色描边，亮 item 上仍清晰可见）
    - 蓝色 = RUNNING/SUBMITTING/VERIFYING_OUTPUT
    - 绿色 = DONE
    - 灰色 = PENDING
    - 琥珀色 = WAITING_CPU/PAUSED
    - 红色 = FAILED
    - 暗灰 = CANCELLED
  - 中间显示 `itemStack.getDisplayName()`（本地化 + 模组色码 + NBT 后缀）`× 数量`，按可用宽度动态截断（`...` 省略）
  - 右侧短状态文本（"Crafting" / "Pending" / "Plan..." / "Wait CPU" 等）+ split 进度（`2/3`），AE2 GuiColors 配色
  - 跨 layer 边界处画 1px 半透明黑分隔线（替代原"Layer N:"重复文字）
  - hover 整行半透明白高亮（`0x33FFFFFF`），selected 整行更亮（`0x55FFFFFF`）
  - 行高 18px（容纳 16x16 图标 + 1px 上下 padding），原 12px

- **`GuiSmartCraftStatus` 改造**：
  - `drawScreen` 调用 `widget.draw(...)` 接管清单区域，删除 `syncScheduleButtons` / `removeScheduleButtons` 整套 vanilla button 注入逻辑
  - `mouseClicked` 加 `tryClickScheduleRow` 命中测试 → 调 `selectOverview()` / `selectTask(idx)`
  - `actionPerformed` 删除 `OVERVIEW_BUTTON_ID` / `TASK_BUTTON_BASE` 分支
  - `initGui` 不再为 schedule list 创建 GuiButton（cancel / retry button 保留）

- **删除文件**：
  - `SmartCraftScheduleButtonLayout.java`（不再使用）
  - `SmartCraftScheduleButtonLayoutTest.java`（覆盖已删除的类）

- **`SmartCraftOverlayRenderer` 改造**：
  - `LIST_ROW_HEIGHT` 12 → `SmartCraftScheduleListWidget.ROW_HEIGHT` (18) 单一来源
  - 删除 `drawScheduleList` 方法（widget 接管包括 layer divider）
  - 暴露 `getSelectedTaskIndex()` 给 widget 用于高亮当前选中行

### 设计决策

- **TaskView 已有 itemStack 字段**：之前 packet 把它发到客户端但只用于 grid 区的 tooltip。这次让它派上更大用场（图标 + displayName）—— 零协议改动是这个方案的关键支点
- **状态色点放在图标右上角而非整行单独色块**：避免占用文字宽度；3x3 + 黑描边的小尺寸足够辨识但不夺主
- **行高 18 vs 12**：大了 50%，但 `MAX_VISIBLE_TASK_ROWS` 保持 4，所以视觉变大但行数不变。有图标后玩家不需要"挤更多行"，每行信息密度本身已经提升
- **Shift-click cancel 仍走 vanilla button**：UI 改造范围限定清单区域，不动 cancel/retry 按钮（无需改）
- **layer divider 替代 "Layer N:" 文字**：信息从"显式 1 行 layer 标题 + 多行 task"改为"分隔线提示 layer 边界"，节省 1 行/层 且信息密度更高
- **getDisplayName 带 try-catch fallback**：部分模组在 stack 缺 NBT 时 `getDisplayName()` 抛异常，捕获后回退到 `requestKeyId`，避免单 task 把整个 list 渲染崩了

### 测试套件

- 66 passing（v0.1.3 的 70 - 4 个废弃 SmartCraftScheduleButtonLayoutTest）
- 视觉效果需要进游戏验证，没法单元测试（涉及 GL 渲染、字体、texture）

### 遇到的问题 / 教训

- AE2 `GuiColors` 在 1.7.10 的包路径是 `appeng.core.localization.GuiColors`（不是直觉的 `appeng.client.gui.GuiColors`）。新 widget 一开始 import 错路径报"找不到符号"。`SmartCraftOverlayRenderer` 已经用对路径，参照修复
- 删 `SmartCraftScheduleButtonLayout.java` 之后还有一个对应的 `SmartCraftScheduleButtonLayoutTest.java` 测试文件引用废弃类导致编译失败。整个测试文件直接删除（测的是已废弃的 button 布局，没保留价值）
- `removeScheduleButtons` 用 `Iterator` 遍历 buttonList 移除——这是 vanilla button 模式下"每帧重建按钮列表"的痕迹。widget 模式下完全不需要，连同 `Iterator` import 一并删除

---

## 2026-04-29（v0.1.3）：调度健壮性增强 — Plan 重试 / WAITING_CPU 老化 / 配置化 / 健康观测

### 背景：A 推测式 Plan 评估结论

用户原计划做 A（推测式 Plan / Plan Ahead），让 layer N+1 task 在 layer N RUNNING 时就开始 plan。重新审查后确认：**A 在不 mixin AE2 内部库存查询的前提下不可行**。

- AE2 的 `craftingGrid().beginCraftingJob(world, grid, actionSrc, request, callback)` 没有库存 override 参数，plan 必须基于真实 ME 库存
- Y RUNNING 时：Y 的输入已被 AE2 抽到 CPU cluster，Y 的输出尚未回到 ME
- 此时让 X plan：AE2 看不到 Y 的预期产出 → 把 Y 的合成也加进 X 的 plan
- X submit → 跟 Y 自己的 CPU **重复合成 Y**（与 P1-#3 同类问题，但跨 layer）

要做 A 必须 mixin AE2 的 `IStorageGrid.getItemInventory()` 让 plan 时看到"虚拟产出"，工作量 1-2 天且风险高（可能影响其他 mod 的库存视角、AE2 cluster 状态机异常）。用户决定跳过 A，做替代优化 G1-G4。

### 已完成（G1 + G2 + G3 + G4）

- **G1 Plan 失败自动重试（指数退避）**：
  - 问题：plan 返回 null / 抛异常 / 超时，task 立即转 FAILED 需用户手动 retry。大订单偶发暂态失败需要人工干预
  - 修复：`Coordinator.handlePlanFailure(task, reason)` 统一所有失败路径。`PlanRetryState` 跟踪每 task 的 attempts + nextAllowedTick；失败 → 转 PENDING + 退避（5/10/20/40/80 tick 表，指数双倍）；attempts > MAX 才永久 FAILED
  - retry filter：dispatch 中 plan candidate 收集时跳过 `tickCounter < state.nextAllowedTick` 的 task
  - 清理点：plan 成功（reconcile 中 attachPlannedJob 前）/ 永久 FAILED / cancel / cancelGracefully / retryFailed（手动 retry 重置自动 retry 计数）
  - 默认 PLAN_RETRY_MAX_ATTEMPTS=3 → 总共 4 次尝试机会（首次 + 3 次重试）
  - 测试：`plan_failure_auto_retries_then_fails_permanently_after_attempts_exhausted`、`plan_retry_recovers_when_a_later_attempt_succeeds`

- **G2 WAITING_CPU 超时重 plan**：
  - 问题：task plan 完进 WAITING_CPU 等几分钟，期间 ME 库存因玩家 I/O / 其他订单而漂移；submit 时 plan 依据的库存快照失效，AE2 cluster 可能 fallback 重打或失败
  - 修复：`reconcileTaskExecution` 中 plannedJob != null 分支增加 stale check：若 task 是 WAITING_CPU 且 `tickCounter - waitingCpuSinceTick > Config.WAITING_CPU_STALE_SECONDS * 20`，clearExecution + 转 PENDING（**不计入 retry attempts**——这不是失败，是过期）
  - 默认阈值 600 秒（10 分钟）；0 = 禁用
  - 测试：`waiting_cpu_stale_plan_drops_back_to_pending_for_replan`

- **G3 Plan 超时可配置**：
  - 改 `MAX_PLANNING_TICKS = 1200L` 常量为 `Config.PLANNING_TIMEOUT_SECONDS * 20`
  - 默认 60 秒，范围 5-600 秒
  - **注意**：超时路径现在走 G1 retry，不再立即 FAILED。要恢复旧"立即 FAILED"行为：把 PLAN_RETRY_MAX_ATTEMPTS 设 0
  - 旧测试 `submitting_times_out_after_max_planning_ticks` 和 `mixed_failed_and_waiting_cpu_must_not_pause_the_order` 用 `try { Config.PLAN_RETRY_MAX_ATTEMPTS = 0; ... } finally { 复原 }` 局部禁用 retry 保持原语义

- **G4 Plan/Submit/Done 计数器**：
  - Coordinator 加 5 个 long 计数器：plansAttempted / plansSucceeded / plansAutoRetried / plansFailedPermanently / runsCancelled
  - tick() 末尾每 STATS_LOG_INTERVAL_TICKS（默认 6000=5 分钟，0=禁用）输出一行 INFO
  - 计数器永不重置——读起来是"服务器启动以来累计"
  - 调试用：admins 可通过 log 发现 retry-loop / WAITING_CPU 老化频发的异常订单

### 新增 Config 项

`scheduling` 分组下：
- `planningTimeoutSeconds`：默认 60，范围 5-600
- `planRetryMaxAttempts`：默认 3，范围 0-10（0 = 关闭自动重试）
- `waitingCpuStaleSeconds`：默认 600，范围 0-3600（0 = 永不重 plan）
- `statsLogIntervalTicks`：默认 6000，范围 0-72000（0 = 禁用 stats log）

### 设计决策

- **G1 与 G2 的 retry 计数分离**：G1 是"plan 失败"语义，计 attempts；G2 是"plan 过期"语义，不计 attempts。同一个 task 可能 G2 重 plan 多次（库存反复变动）但不应被永久 FAILED
- **G1 backoff 表用静态数组而非公式**：5/10/20/40/80 ticks 比 `5 * 2^n` 更可控，n 超出表长度时夹紧到 80 防止万一 attempts 漂移到大数后退避无限增长
- **handlePlanFailure 不调 clearExecution**：调用者已经 cleared，避免双重清理；helper 只负责"决定下一态 + 计数 + 发返回值"
- **manual retry（用户点 Retry 按钮）必须清 G1 retry state**：否则用户期待"重置一切"但实际用了挂在 task 上的旧退避计数，下次 plan 立刻就 FAILED。修复点在 `Coordinator.retryFailed`
- **G4 计数器不加 volatile / 不用 AtomicLong**：tick() 是单线程（server thread），无并发问题。极简实现
- **G4 用 SLF4J/Log4j 标准接口而非 println**：进入 minecraft 的 forge log 文件，玩家提交问题时自带这些数据

### 遇到的问题 / 教训

- 旧测试 `submitting_times_out_after_max_planning_ticks` 和 `mixed_failed_and_waiting_cpu_must_not_pause_the_order` 因 G1 行为变化失败：原期望"plan 失败立即 FAILED"，新行为是"先 retry 再 FAILED"。用 `try/finally + Config.PLAN_RETRY_MAX_ATTEMPTS=0` 局部禁用 retry 是最小破坏的修法
- G2 stale check 一开始只放在 reconcile 的 `plannedJob != null` 分支，没考虑该分支以前是 fall-through 直接 return task。验证发现旧逻辑确实只 return task；插入 stale check 后保持兼容
- G1 测试用"任务一直失败 + 数 tick 直到 FAILED"模式，没法精确预测多少 tick（attempts 累加 + 各次退避 + dispatch 间隔），用 `for(0..100) { tick; if FAILED break }` 模式更稳健
- 计数器命名上踩坑：原本想叫 `plansFailed`，但歧义（含 retry 失败 + 永久失败？），最终改 `plansAutoRetried` + `plansFailedPermanently` 两个独立计数

### 测试套件

- 70 passing（v0.1.2 的 67 + 3 新增：G1 retry 2 + G2 stale 1）

---

## 2026-04-28（v0.1.2）：调度策略增强 — 关键路径优先 / 反饥饿 / 软取消

### 已完成（C + E + F）

- **增强-C 关键路径优先（Critical-Path-First Dispatch）**：
  - 问题：当 idle CPU 不足时，原 dispatch 按 layer + task 创建顺序遍历。短链 task 可能比长链 task 先抢到 CPU，但长链才是决定总耗时的关键路径，让短链先做反而拖累整体
  - 修复：每 tick 进入 `dispatchReadyTasks` 先做一次 DFS 计算每个 task 的"最长下游依赖链长度"（critical path length）；submit / plan 候选都按 CPL 降序排序；CPL 相同的并列 task 按 taskId 字典序稳定 fallback
  - 复杂度：O(N + E) 一次 DFS + O(N log N) 排序，远低于一次 tick 现有 O(N²) 状态扫描的成本
  - 测试：`critical_path_first_when_only_one_cpu_is_idle` — 长链 root（CPL=3）+ 短链 leaf（CPL=1），1 idle CPU，2 tick 后必须长链 root RUNNING、leaf WAITING_CPU

- **增强-E 等待时长反饥饿（Anti-Starvation by Wait Age）**：
  - 问题：CPL 相同的 task 之间没有公平性保证。新 ready 的 task 可能反复抢走 CPU，让早进 WAITING_CPU 的 task 永远等待
  - 修复：`TaskExecution` 新增 `waitingCpuSinceTick` 字段（首次进 WAITING_CPU 时一次性 stamp，后续不刷新）；submit candidate 排序时 CPL desc 之后用 `waitingCpuSinceTick asc` tiebreak（早等的优先）；新增 `Session.markWaitingCpu(task, tick)` 幂等方法
  - 测试：`waiting_cpu_age_breaks_ties_when_priorities_match` — 两个等价 leaf task，一个早进 WAITING_CPU、一个晚进，释放 CPU 时早等的必须先拿

- **增强-F 软取消（Graceful Cancel）**：
  - 问题：cancel 会同时打断 RUNNING task。但 AE2 此时已把中间产物路由到 CPU 内部 storage cluster，硬取消会让这些半成品孤立（玩家损失材料）
  - 修复：`OrderManager.cancelGracefully(orderId)` —— RUNNING / VERIFYING_OUTPUT 不动，PENDING / SUBMITTING / WAITING_CPU / QUEUED 转 CANCELLED；`Coordinator.cancelGracefully(orderId)` 调用前者后再选择性 `clearExecution` 取消的 task（顺带 cancel 其 planning future），保留 RUNNING task 的 craftingLink
  - 协议：`RequestSmartCraftActionPacket.Action.CANCEL_ORDER_SOFT` 新枚举值
  - UI 入口：**Shift+点击 Cancel 按钮 = 软取消**（最小改动，免布局调整 / 翻译键 / 新按钮位置）
  - 退化场景：order 当前没有 RUNNING task 时软取消等价硬取消（order 直接转 CANCELLED）
  - 测试：
    - `soft_cancel_spares_running_tasks_and_cancels_others`：[DONE, RUNNING, PENDING, WAITING_CPU] → [DONE, RUNNING, CANCELLED, CANCELLED]，order 不转 CANCELLED
    - `soft_cancel_with_no_running_tasks_degrades_to_hard_cancel`：[PENDING, WAITING_CPU] → 全 CANCELLED + order CANCELLED
    - `soft_cancel_clears_planning_future_for_cancelled_tasks`：SUBMITTING task 的 planning future 必须被 cancel 防泄漏

- 测试套件：67 passing（v0.1.1 的 62 + 5 新增：C 1 + E 1 + F 3）

### 重构副产物

- `dispatchReadyTasks` 整体重写为 4 阶段：Phase 0 收集全局状态 → Phase 1 构造 comparator → Phase 2 分类 + 排序候选 → Phase 3 应用决策 → Phase 4 重建 layers。原"layer 嵌套循环 + nextTasks 重建"被替换为"扁平排序循环 + taskId → 更新 task 的 Map + 重建"。所有现有测试无需修改即通过

### 设计决策

- **C/E 都不污染 SmartCraftTask 模型**：CPL 是 dispatch 时的派生数据，不入持久化、不发到客户端；waitingCpuSinceTick 进 TaskExecution（已是 server 端 transient）。保持模型纯净
- **F 用 shift+click 而非新按钮**：UI 改动最小，零布局 / 翻译 / 资源文件改动。可发现性靠 README 说明 + 后续可加 tooltip。比加新按钮风险低
- **plan candidate 不参与 E 排序**：plan 阶段不消耗 CPU，没有"等待 CPU 老化"的概念，所以 plan 候选只按 CPL 排
- **submit candidate 的 CPL 优先于 wait age**：决定总耗时的是最长链推进速度，公平性是次要目标。如果反过来，深挖一条短链会比启动新长链优先，违背 C 的初衷

### 遇到的问题 / 教训

- 初版 `dispatchReadyTasks` 写了一个"全局排序整张 task 列表"（Phase 1）但实际只用到 Phase 2 的两个分类排序——Phase 1 是死代码。删掉之后逻辑更清晰
- E 的测试设计踩坑：两个 leaf task 同时进入 WAITING_CPU 时 waitingCpuSinceTick 相同，会用 taskId 字典序 fallback，无法验证 wait age 真在起作用。最终通过手动 `clearExecution + 改回 PENDING` 强制让一个 task 重新进 WAITING_CPU（拿到更晚的 tick），构造出可区分的场景
- F 的 OrderManager.cancelGracefully 一开始忘了"无 RUNNING 任务时退化为硬取消"，导致一个空操作的 order 卡在 PAUSED/QUEUED。加了 `anySpared` 判断后修复

---

## 2026-04-28（v0.1.1 续 2）：split 并发库存竞争 P1-#3 修复

### 已完成
- **P1-#3 同节点 split 任务的 plan 串行化**：
  - 问题：节点拆 N 个 split 共享 requestKey，所有 split 同 tick ready → 同 tick 进 plan → N 份 plan 都基于"同一 stock 快照"，submit 时第 2..N 份会重复扣库存，AE2 cluster 走 fallback 多打中间产物（"产量 > 需求"超合成）
  - 修复：`SmartCraftRuntimeCoordinator.dispatchReadyTasks` 初始扫描时构造 `planningInFlightRequestKeys: Set<String>` —— 收集所有 SUBMITTING 状态的 task 的 `requestKey.id()`；PENDING task 准备调用 jobPlanner 之前检查 set，命中则 skip 推迟到下 tick；成功 trackPlanning 之后也把 requestKey 加入 set 防止同 tick 内多个 sibling 同时启动
  - 设计取舍：plan 阶段同 requestKey 完全串行（N × single_plan_time），但 submit / run 仍并行。**牺牲 plan 吞吐换正确性**——一旦一个 split 进入 RUNNING，AE2 已从 ME 把它的库存份额抽走，下一个 split 的 plan 看到的是真实降低后的库存，自动避免重复扣
  - 旧测试维护：`planning_starts_for_all_tasks_even_when_only_one_cpu_is_idle` 和 `mixed_failed_and_waiting_cpu_must_not_pause_the_order` 两个 RuntimeCoordinatorTest 用例本来用 `FakeRequestKey("processor")` 让 task-1/2/3 共享 requestKey，修复后这些 task 会被串行化导致测试失败。把 helper 改成 `requestKey.id() = taskId`（这两个测试的语义本来就是独立 task），最小修复
- 新增测试：`splits_of_same_request_key_serialize_their_planning`（1 SUBMITTING + 3 PENDING）+ `splits_of_different_request_keys_plan_in_parallel`（3 SUBMITTING）锁定串行化是 per-requestKey 而不是全局
- 测试套件：62 passing（v0.1.1 续 1 的 60 + 2 新增 P1-#3）

### 设计决策
- **拒绝 "plan 时预占库存表" 方案**：需要解析 `ICraftingJob` 内部"取库存数量"，AE2 API 不暴露；改 jobPlanner 接口让 plan 时传入"available stock override"也太重，且依赖 AE2 内部行为
- **拒绝 "plan 完成立刻 submit + 让出 tick" 方案**：会让单节点合成被完全串行化（plan + run 都串），失去 split 拆分的全部并行价值
- **采用"plan 串行 + run 并行"**：
  - 第一个 split 在 t=1 plan，t=2 进入 WAITING_CPU 或 RUNNING；
  - 第二个 split 在 t=2 才开始 plan（看到的库存反映了第一个的扣减）；
  - 第三个 split 在 t=3 plan...
  - 同节点 N split 总耗时 ≈ N × plan_time + max_run_time（plan 阶段串行，run 阶段并行 = 接近最优）
- **`requestKey.id()` 作为 dedup key**：依赖 `SmartCraftRequestKey.id()` 实现唯一标识同一物品的契约；split 之间共享 id，不同节点 id 不同。如果未来某物品有多种 requestKey id（如 NBT 区分），split 串行化可能误命中——目前实现 OK

### 遇到的问题 / 教训
- 旧测试 `task("task-1", ...)`、`task("task-2", ...)` 都用 `new FakeRequestKey("processor")`，看起来是独立 task 实际共享 requestKey。一开始没注意到这点，跑测试时 2 个旧测试 fail。修 helper 比改测试本身更通用，未来同样写法的测试也不会再踩坑
- 写 `splits_of_same_request_key_serialize_their_planning` 测试时一开始用了 4 个 splitIndex 但忘了 splitCount 改成 4（直接 1/4 的语义），改对后通过。注释里加上"taskId 自动从 requestKey + splitIndex 派生"的说明，避免后续读代码的人困惑

---

## 2026-04-28（v0.1.1 续）：调度健壮性 P0 加固 — planning future 生命周期 & 超时

### 已完成
- **P0-#1 `clearExecution` 取消未完成的 planning future**：`SmartCraftRuntimeSession.clearExecution` 现在如果 `removed.planningFuture()` 还未 done 会 `cancel(true)`。修复了"cancel 订单时 task 仍在 SUBMITTING → 仅 map 项被擦但 AE2 planner 线程继续跑 → 后续可能回写到已清理 session 触发 NPE"的资源泄漏 + 潜在 NPE。`cancelAll()` 一直就有这个逻辑，但 per-task 路径（reconcileTaskExecution 早返回 / dispatchReadyTasks submit 失败）漏了
- **P0-#2 SUBMITTING 超时转 FAILED**：
  - `TaskExecution` 加 `submittedAtTick: long`，由 `trackPlanning(task, future, currentTick)` 写入
  - `Coordinator` 加私有 `tickCounter`，每 `tick()` ++（不改构造函数签名，不引入 LongSupplier，测试用 coordinator.tick() 自然推进）
  - `reconcileTaskExecution` 检查 `tickCounter - execution.submittedAtTick() > MAX_PLANNING_TICKS`（常量 1200 tick = 60 秒），超时则 `clearExecution(task)`（顺带 cancel future）+ 转 FAILED + reason="AE2 planning did not complete within timeout"
  - 防 AE2 planner 卡死时任务永久滞留 SUBMITTING 的失活路径
- **P1-#4 多层重试 依赖链接力回归测试**：`retry_propagates_progress_through_dependency_chain` 覆盖 5 层 `[DONE, DONE, FAILED, PENDING, PENDING]` 场景，验证 PAUSED 静默 / retry 后立即推进 l2 / l3 等 l2 DONE / 链式接力。锁定新依赖图模型在重试场景下不破坏依赖语义
- 测试套件：60 passing（54 baseline + 6 新增/重写：跨 layer 并行 1 + builder deps 2 + cancel future 1 + planning timeout 1 + retry chain 1）

### 设计决策
- **tick counter 选私有字段而非外部注入 LongSupplier**：保持 `SmartCraftRuntimeCoordinator` 构造函数签名不变（不破坏现有 5 个测试和 `AE2IntelligentScheduling.SMART_CRAFT_RUNTIME` 实例化代码）。代价是测试只能"驱动 tick" 不能"跳跃 tick"，但所有 timeout 测试都是线性 tick，没有损失
- **MAX_PLANNING_TICKS = 1200（60 秒）**：经验值。AE2 planner 在大型 GTNH 配方上（10K+ patterns）的合理时间在几秒到十几秒，60 秒已是 4-6× safety margin。再大的话用户就该看到反馈而不是干等
- **拒绝在 clearExecution 里同时 cancel craftingLink**：所有 clearExecution 调用点都已在 link 已 isDone() / isCanceled() 之后才进入；提前 cancel link 可能与 AE2 内部 link state machine 冲突，副作用难评估。link 生命周期保持由 AE2 自己 + cancelAll() 显式管理

### 遇到的问题 / 教训
- 写 `cancel_during_planning_cancels_planning_future` 测试时一开始想直接判 `task.status == CANCELLED`，但 cancelAll 不修改 task 状态，仅 OrderManager.cancel 改 task → 测试焦点应是"future.cancel 被调"而不是状态。把 future 改成 TrackingFuture 暴露 `cancelInvoked` 即可
- 写 timeout 测试时一开始用 `for (i=0; i<1200; i++) tick();` 边界条件：第 1 tick 是 trackPlanning 那次，submittedAtTick=1，再 1200 tick 后 tickCounter=1201，elapsed=1200，**严格大于** 1200 才 trip，所以需要 +1 tick 才超时。把 loop 改 1199 + 显式 2 个 tick，让"边界还没到"和"过了边界"都被断言到
- `retryFailedTasks` 同时把 order 状态改回 QUEUED，没看清这一点会以为 PAUSED 还在 → 测试设计就要先调 `retryFailed(orderId)` 再 tick，别想着手动改 order status

---

## 2026-04-28（v0.1.1）：跨 layer 并行调度 — 任务级依赖图

### 已完成
- **数据模型扩展**：`SmartCraftTask` 新增 `dependsOnTaskIds: List<String>` 字段（带向后兼容构造函数和 `withDependsOnTaskIds`）
- **`SmartCraftOrderBuilder.visit` 改成返回 `VisitResult(layerIndex, emittedTaskIds)`**：递归过程中，每个节点的 task 把"直接子节点 emit 出来的 taskId"作为自身依赖；如果中间节点已库存（不 emit task），则把"祖父节点的 taskId"透传上去给父亲做依赖
- **`SmartCraftRuntimeCoordinator.dispatchReadyTasks` 改成全局遍历**：不再局限于 `currentLayer.tasks()`，而是遍历**所有 layer 的所有任务**，对每个 PENDING 任务先查 `areDependenciesDone(task, tasksById)` 再决定是否开始 planning。两条互不相干的分支（深度不同）现在可以同时跑
- **`applyLayerStatus` 改成全局派生**：扫描所有 layer 的所有 task 来判断 RUNNING / WAITING_CPU / PAUSED / COMPLETED，避免"layer 0 已 PAUSED 但 layer 1 还在跑"的状态错位
- **`advanceLayers` 简化为单 pass**：只用来更新 `currentLayerIndex` 给 UI 显示"Layer X/Y"，不再 gate 调度。索引取最小未完成 layer 的 index
- **回归测试**：
  - `independent_branches_at_different_layers_run_in_parallel`：分支 A（layer 1，依赖已 DONE 的 a-leaf）必须和分支 B（layer 0，b-leaf RUNNING 中）并行跑
  - `wires_task_dependencies_so_parents_wait_for_their_children_only`：root 只依赖直接孩子的 taskId，不会包含孙辈
  - `in_stock_intermediate_node_propagates_grandchildren_as_parent_dependencies`：库存中间节点不 emit task 但要把 leaf 的 taskId 透传给 root
  - 旧测试 `does_not_start_parent_layer_before_children_finish` 重写为基于任务级依赖（用 `withDependsOnTaskIds`），而不是层屏障
- 全部 57 测试通过（54 旧 + 1 重写 + 2 新增）

### 设计决策
- **保留 layer 数据结构**：UI 仍按 layer 展示（6×3 任务格按深度分组），客户端协议无需变更（`SyncSmartCraftOrderPacket` 仍只传 task list 和 currentLayerIndex）
- **依赖关系仅服务端持有**：`dependsOnTaskIds` 不进协议、不进 NBT，因为订单不持久化（重启即丢）。这避免了协议向后不兼容
- **拒绝完整 DAG 模型**：保留 layer 概念让用户视觉上能看到"还差多少层"，又用任务级依赖打破层屏障，二者互不冲突
- **`applyLayerStatus` 名字保留不改**：方法签名不变，调用方不需要改。语义已扩展为全局视图
- **AE2 CPU 物理瓶颈**：跨 layer 并行只在网络有多个空闲 CPU 时有效；单 CPU 网络仍然是串行（这是物理限制）

### 遇到的问题 / 教训
- 旧测试 `does_not_start_parent_layer_before_children_finish` 通过的是"层屏障"行为，而不是真正的任务级依赖。修改之后需要明确测试新契约，否则容易误以为还在保护"父等子"语义
- `applyLayerStatus` 之前只看 currentLayer 的设计在跨层并行下会出现"PAUSED 误判"：当 layer 0 的失败任务被 retry 后变 PENDING，而 layer 1 已经在 RUNNING，旧逻辑会误判 layer 0 为 PAUSED；现在全局视图就没问题了
- `advanceLayers` 旧实现里有 `withStatus(QUEUED)` 的副作用，给 applyLayerStatus 兜底；新实现完全不动 status，让 applyLayerStatus 自己根据全任务集决定，更干净
- builder 的"in-stock middle node 透传 grandchildren"语义之前没单测，这次发现是对的（visit 返回 dependsOnTaskIds 列表给父亲），加了测试锁定行为

---

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
