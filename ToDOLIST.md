# TODO 列表

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
