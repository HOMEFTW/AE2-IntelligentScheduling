# 项目上下文

## 基本信息
- Mod Name: AE2-IntelligentScheduling
- Mod ID: `ae2intelligentscheduling`
- Package: `com.homeftw.ae2intelligentscheduling`
- Target: Minecraft 1.7.10 + GTNH + AE2
- 当前阶段：已完成项目脚手架、智能合成规划模型、AE2 合成树分析、运行态调度骨架、真实 AE2 下单链路、AE2 风格智能合成状态页、主动刷新、运行态任务反馈与 AE2Things/NEI 客户端兼容护栏

## 2026-04-27 UI 流程现状
- `GuiCraftConfirm` 现在只承载 `智能合成` 启动按钮，不再绘制智能调度运行态叠层，也不再注入取消、重试或任务切换按钮
- `SyncSmartCraftOrderPacket` 默认只更新客户端订单数据，不再主动打开或抢占 UI，因此玩家按 ESC 关闭状态页后不会被下一次同步自动拉回
- `SmartCraftScreenFlow` 集中描述客户端界面流转：只有玩家显式点击 `查看调度` 后，下一次服务端状态同步才会打开 `GuiSmartCraftStatus`
- AE2 `GuiCraftConfirm` 和 AE2 终端页在已有订单数据后都会显示 `查看调度` 按钮，并通过 `RequestOrderStatusPacket` 请求最新订单状态
- `GuiSmartCraftStatus` 当前继承 `GuiScreen`，复用 AE2 `craftingcpu.png` 长 UI 背景和 `SmartCraftOverlayRenderer`
- `GuiSmartCraftStatus` 负责取消整单、重试失败和每 20 tick 主动刷新；按钮绘制顺序已调整为背景和内容之后
- `GuiSmartCraftStatus` 会在单元格区域下方显示调度清单，顶部 `总调度` 按钮会让上方单元格区域显示总览，每个已分配 CPU 的任务分片按钮会让上方单元格区域切换到对应 AE2 CPU 合成详情
- `SmartCraftScheduleButtonLayout` 负责生成调度清单按钮模型，按钮 ID 使用 `OVERVIEW_BUTTON_ID` 与 `TASK_BUTTON_BASE + taskIndex`
- `GuiSmartCraftStatus` 当前已改为 AE2 风格长 UI，使用 `guis/craftingcpu.png` 分段绘制背景；调度清单位于同一个 GUI 内部并支持清单区域鼠标滚轮滚动
- `SmartCraftStatusLayout` 负责计算长 UI 高度、调度清单可见行数和滚动范围，避免清单绘制到 GUI 外部
- `SmartCraftOverlayRenderer` 的总调度单元格会渲染任务物品图标和压缩数量
- `SmartCraftOverlayRenderer` 的主要状态标签已改为语言键，避免中英文硬编码混排

## 已实现内容

### 设计文档
| 名称 | 路径 | 状态 |
|------|------|------|
| 智能合成设计文档 | `docs/superpowers/specs/2026-04-22-ae2-intelligent-scheduling-design.md` | 已完成 |
| 实现计划文档 | `docs/superpowers/plans/2026-04-22-ae2-intelligent-scheduling-implementation.md` | 已完成 |

### 基础代码
- 已创建 `AE2IntelligentScheduling`、`CommonProxy`、`ClientProxy`、`Config`
- 已接入 Gradle wrapper、mixin 配置与基础工程结构
- `gradle.properties` 默认使用固定 `modVersion = 0.1.0-dev` 且 `gtnh.modules.gitVersion = false`
- 工作树 `gradlew` 与 `gradlew.bat` 当前只通过 `JAVA_HOME` 查找构建 JDK，脚本提示已明确要求使用 `Zulu21`

### 智能合成规划模型
- 已实现 `SmartCraftOrderScale`、`SmartCraftStatus`、`SmartCraftNode`、`SmartCraftTask`、`SmartCraftLayer`、`SmartCraftOrder`
- 已实现 `SmartCraftOrderScaleClassifier`、`SmartCraftSplitPlanner`、`SmartCraftOrderBuilder`
- 订单量级按整棵树中的最大节点缺口判定，而不是只看最终产物
- 当前拆分档位如下：
- `SMALL`：最大节点缺口 `< 2.1g`
- `MEDIUM`：最大节点缺口 `>= 2.1g` 且 `< 16g`
- `LARGE`：最大节点缺口 `>= 16g`

### AE2 集成
- 已实现 `Ae2RequestKey`、`Ae2CraftTreeWalker`、`Ae2CraftingJobSnapshotFactory`
- 已实现 `Ae2CpuSelector`、`Ae2CraftSubmitter`、`Ae2SmartCraftJobPlanner`
- 已实现 `ContainerCraftConfirmAccessor`、`ContainerCraftConfirmInvoker`
- 当前通过 `CraftingRequest.usedResolvers -> CraftFromPatternTask.childRequests` 读取 AE2 合成树快照
- `Ae2RequestKey` 当前可保存 item 模板，并在运行态按数量重建 `IAEItemStack`

### 运行态调度
- 已实现 `SmartCraftRequesterBridge`
- 已实现 `SmartCraftOrderManager`
- 已实现 `SmartCraftScheduler`
- 已实现 `SmartCraftStockVerifier`
- 已实现 `SmartCraftRuntimeSession`
- 已实现 `SmartCraftRuntimeCoordinator`
- 已实现 `SmartCraftServerTickHandler`
- 已实现 `SmartCraftAe2RuntimeSessionFactory`
- 服务端当前可执行：
- 从智能任务发起真实 `beginCraftingJob`
- 在服务端 tick 中轮询 `Future<ICraftingJob>`
- 选择空闲 CPU 后执行 `submitJob`
- 使用 `ICraftingLink` 回填 `RUNNING / DONE / FAILED / CANCELLED`
- 支持整单取消与失败任务重试

### AE2 界面入口
- 已实现 `NetworkHandler`
- 已实现 `OpenSmartCraftPreviewPacket`
- 已实现 `RequestSmartCraftActionPacket`
- 已实现 `SyncSmartCraftOrderPacket`
- 已实现 `SmartCraftConfirmGuiEventHandler`
- `SmartCraftConfirmGuiEventHandler` 会通过 Forge `GuiScreenEvent.InitGuiEvent.Post` 在 AE2 `GuiCraftConfirm` 中追加 `智能合成` 按钮
- `智能合成` 按钮当前位于 AE2 合成确认页右下区域，靠近原 `Start` 按钮；注入成功时客户端日志会出现 `Added smart craft button to AE2 GuiCraftConfirm via Forge event`
- 原始 AE2 合成按钮与原有功能保持不变

### 状态页与客户端 GUI
- 已实现 `GuiSmartCraftStatus`
- `GuiSmartCraftStatus` 已从普通 `GuiScreen` 切换为 `AEBaseGui`
- 状态页复用 AE2 `guis/craftingreport.png` 背景与 `GuiScrollbar`
- 状态页当前展示目标请求键、目标数量、订单量级、订单状态、当前层、任务数与任务分片信息
- 任务列表当前为文本型滚动列表，支持 tooltip 查看完整请求键、精确数量、状态与阻塞原因
- 状态页支持 `取消整单` 与 `重试失败` 按钮，并通过 `RequestSmartCraftActionPacket` 回传服务端
- 语言资源当前使用真实 UTF-8 中文文本，并提供 `en_US.lang` 与 `zh_CN.lang`
- 状态页顶部摘要文本已按固定区域裁剪，避免长物品 ID、状态文本或数量文本穿过布局边界
- 已实现 `NeiGuiContainerManagerGuard`，用于修复 AE2Things 派生 `GuiCraftConfirm` 进入 tick 时 NEI `GuiContainerManager` 字段仍为 `null` 的情况

### 测试与验证
- 已验证 `SmartCraftSplitPlannerTest`
- 已验证 `SmartCraftOrderBuilderTest`
- 已验证 `Ae2CraftTreeWalkerTest`
- 已验证 `Ae2CpuSelectorTest`
- 已验证 `SmartCraftSchedulerTest`
- 已验证 `SmartCraftRuntimeCoordinatorTest`
- 已验证 `SmartCraftPacketCodecTest`
- 已验证 `SmartCraftLanguageFileTest`
- 已验证 `NeiGuiContainerManagerGuardTest`
- 已验证 `compileJava -x compileInjectedTagsJava` 可通过
- 已验证 `reobfJar` 可通过，并生成客户端测试用 `build/libs/ae2intelligentscheduling-0.1.0-dev.jar`

## 配置项
| Key | Default | Description |
|-----|---------|-------------|
| `maxCpuPerNode` | `16` | 单个缺口节点允许分配的最大 CPU 数 |
| `enableDebugLogging` | `false` | 是否启用智能合成调试日志 |

## Mixin
- 当前智能合成入口不再依赖 AE2 `GuiCraftConfirm` GUI Mixin
- `mixins.ae2intelligentscheduling.json` 当前包含客户端 `minecraft.GuiContainerNeiManagerGuardMixin`
- `GuiContainerNeiManagerGuardMixin` 在 `GuiContainer.updateScreen()` 开头调用 `NeiGuiContainerManagerGuard`，当 NEI 已注入 `manager` 字段但值仍为 `null` 时补建 manager 并调用 `load()`
- `ContainerCraftConfirm.result` 与 action source 当前通过 `Ae2CraftConfirmAccess` 反射读取，避免运行时 accessor mixin 未加载导致入口失效

## 已确认规则
- `1g = 1,000,000,000`
- `2.1g = 2,147,483,647`
- 订单量级按整棵树中的最大节点缺口判定
- 库存语义：先扣库存，只为缺口创建任务
- 调度语义：必须按依赖顺序分层推进
- CPU 语义：自动选择当前空闲 CPU，并受 `maxCpuPerNode` 限制

## 架构备注
- 当前推荐方案是 “AE2 原 UI 注入 + 智能合成按钮 + 独立调度器内核”
- AE2 负责单个 job 的实际执行，本模组负责分析、拆分、排队、依赖控制与自动推进
- 当前 GUI 改造只替换智能合成状态页的表现层，不改变 AE2 原始确认按钮和原始下单流程
- 当前状态同步采用“服务端事件推送 + 客户端每 20 tick 主动刷新”，可持续展示任务阶段与 CPU 分配信息
- 当前真实运行态重建仅对 item 请求开放；fluid 请求模板尚未接入自动下单
- 当前可参考本地 AE2 源码目录：`D:\Code\GTNH LIB\Applied-Energistics-2-Unofficial-rv3-beta-695-GTNH`
- 当前启动脚本层不再注入 `Zulu17`，只保留 `JAVA_HOME -> Zulu21` 的单一入口
## 2026-04-22 进展补充
- 智能合成状态同步已补充任务执行阶段字段：`PLANNING`、`PLANNED`、`SUBMITTED`
- 当服务端为任务选定 AE2 CPU 后，会把 CPU 名称写回运行态 session，并同步到状态页
- `GuiSmartCraftStatus` 当前除任务状态外，还可显示 CPU 分配结果与执行阶段文案
- 启动脚本层面当前约束仍是只通过 `JAVA_HOME` 使用环境中的 `Zulu21`
- 构建层面已将本模组 `compileJava`、`compileTestJava`、`compileInjectedTagsJava` 和 `test` 任务定向到 `Zulu21`，但不会全局覆盖 RFG patched Minecraft 内部编译任务
- `RequestSmartCraftActionPacket.Action.REFRESH_ORDER` 用于状态页主动刷新
- `GuiSmartCraftStatus` 每 20 tick 主动刷新一次，订单 `COMPLETED` 或 `CANCELLED` 后停止刷新
- 服务端刷新同步会优先携带 runtime session，保留 CPU 名称与执行阶段
- `GuiSmartCraftStatus` 复用 AE2 合成 CPU 页面贴图 `guis/craftingcpu.png`，窗口尺寸对齐为 `238x184`
- 智能任务行颜色映射到 AE2 合成状态语义：运行中使用 `CraftingCPUActive`，等待 / 计划使用 `CraftingCPUInactive`
- 最新 UI 要求已改回 AE2 `guis/craftingreport.png`
- `GuiSmartCraftStatus` 当前布局为上半部分 `正常合成` 摘要，下半部分 `智能队列` 任务列表
