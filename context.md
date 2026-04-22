# 项目上下文

## 基本信息
- Mod Name: AE2-IntelligentScheduling
- Mod ID: `ae2intelligentscheduling`
- Package: `com.homeftw.ae2intelligentscheduling`
- Target: Minecraft 1.7.10 + GTNH + AE2
- 当前阶段：已完成项目脚手架、纯规划模型、AE2 合成树快照、运行态调度骨架、真实 AE2 下单运行态协调器、AE2 智能合成按钮与预览入口，以及最小可用的智能合成状态页骨架

## 已实现内容

### 设计文档
| 名称 | 路径 | 状态 |
|------|------|------|
| 智能合成设计文档 | `docs/superpowers/specs/2026-04-22-ae2-intelligent-scheduling-design.md` | 已完成 |
| 实现计划文档 | `docs/superpowers/plans/2026-04-22-ae2-intelligent-scheduling-implementation.md` | 已完成 |

### 基础代码
- 已创建 `AE2IntelligentScheduling` 主类与 `CommonProxy` / `ClientProxy`
- 已创建 `Config` 配置骨架
- 已创建基础 Gradle 构建文件与 wrapper
- 已创建 mixin 配置文件与 `mixin` 包占位
- 已验证 `./gradlew.bat --offline compileJava` 可通过

### 智能合成规划模型
- 已创建 `SmartCraftOrderScale`、`SmartCraftStatus`
- 已创建 `SmartCraftNode`、`SmartCraftTask`、`SmartCraftLayer`、`SmartCraftOrder`
- 已创建 `SmartCraftRequestKey` 抽象接口
- 已创建 `SmartCraftOrderScaleClassifier`、`SmartCraftSplitPlanner`
- 已创建 `SmartCraftOrderBuilder`
- 已为 `SmartCraftTask`、`SmartCraftLayer`、`SmartCraftOrder`、`SmartCraftStatus` 补充运行态状态转换辅助方法
- 已验证 `SmartCraftSplitPlannerTest` 与 `SmartCraftOrderBuilderTest` 通过

### AE2 集成基础
- 已创建 `Ae2RequestKey`
- 已创建 `Ae2CraftTreeWalker`
- 已创建 `Ae2CraftingJobSnapshotFactory`
- 已创建 `Ae2CpuSelector`
- 已创建 `Ae2CraftSubmitter`
- 已创建 `Ae2SmartCraftJobPlanner`
- 已创建 `ContainerCraftConfirmAccessor` 与 `ContainerCraftConfirmInvoker`
- `Ae2CraftTreeWalker` 当前按 `CraftingRequest.usedResolvers -> CraftFromPatternTask.childRequests` 做只读遍历
- `Ae2RequestKey` 当前会保留 item 请求模板，可在运行态按 task amount 重建 `IAEItemStack`
- 已验证 `Ae2CraftTreeWalkerTest` 与 `Ae2CpuSelectorTest` 通过

### 运行态调度骨架
- 已创建 `SmartCraftRequesterBridge`
- 已创建 `SmartCraftOrderManager`
- 已创建 `SmartCraftScheduler`
- 已创建 `SmartCraftStockVerifier`
- 已创建 `SmartCraftRuntimeSession`
- 已创建 `SmartCraftRuntimeCoordinator`
- 已创建 `SmartCraftServerTickHandler`
- 已创建 `SmartCraftAe2RuntimeSessionFactory`
- `SmartCraftScheduler` 当前支持：
- 只推进当前层
- 当前层全部完成后再进入下一层
- 无空闲 CPU 时将任务标记为 `WAITING_CPU`
- 有空闲 CPU 时通过提交回调将任务标记为 `RUNNING`
- `SmartCraftOrderManager` 当前支持 `track(UUID)`、`cancel(UUID)`、`retryFailedTasks(UUID)` 与整单 layer 替换
- `SmartCraftRuntimeCoordinator` 当前支持：
- 从 `SmartCraftTask` 发起真实 `beginCraftingJob`
- 在服务端 tick 中轮询 planning `Future<ICraftingJob>`
- 在有空闲 CPU 时执行 `submitJob`
- 使用 `ICraftingLink` 回填 `RUNNING / DONE / FAILED / CANCELLED`
- 取消 / 重试会同步影响运行中的 future 与 link
- 已验证 `SmartCraftSchedulerTest` 通过

### AE2 智能合成入口
- 已创建 `NetworkHandler`
- 已创建 `OpenSmartCraftPreviewPacket`
- 已创建 `RequestSmartCraftActionPacket`
- 已创建 `SyncSmartCraftOrderPacket`
- 已创建 `GuiCraftConfirmMixin`
- `GuiCraftConfirmMixin` 当前会向 AE2 合成确认界面注入 `智能合成` 按钮
- 客户端点击 `智能合成` 后会发送本模组自定义 packet，而不是走 AE2 的 `PacketValueConfig("Terminal.Start", ...)`
- 服务端当前会读取 `ContainerCraftConfirm` 中现有的 `CraftingJobV2`，分析生成 `SmartCraftOrder` 并登记到 `AE2IntelligentScheduling.SMART_CRAFT_ORDER_MANAGER`
- 服务端在登记订单后会创建 `SmartCraftRuntimeSession` 并注册到 `SMART_CRAFT_RUNTIME`
- 服务端在登记订单后会通过 `SmartCraftOrderSyncService` 下发 `SyncSmartCraftOrderPacket`，直接打开本模组状态页
- 已验证 `SmartCraftPacketCodecTest` 通过

### 状态页与订单同步
- 已创建 `SmartCraftOrderSyncService`
- 已创建 `GuiSmartCraftStatus`
- 已创建 `SmartCraftTaskList`
- `GuiSmartCraftStatus` 当前展示订单 ID、目标物品、量级、状态、当前层与扁平任务列表
- 状态页当前支持 `取消整单` 与 `重试失败` 两个动作按钮，并通过 `RequestSmartCraftActionPacket` 回传到服务端
- 当前状态页仍属于最小实现：尚未提供滚动列表或自动轮询刷新，但订单状态已由服务端运行态协调器按事件推送更新

### 配置项
| Key | Default | Description |
|-----|---------|-------------|
| `maxCpuPerNode` | `16` | 单个缺口节点允许分配的最大 CPU 数 |
| `enableDebugLogging` | `false` | 是否启用智能合成调试日志 |

### Mixin
- `ae2.ContainerCraftConfirmAccessor`：读取 `ContainerCraftConfirm.result`
- `ae2.ContainerCraftConfirmInvoker`：保留对 `getActionSrc()` 的调用入口
- `ae2.GuiCraftConfirmMixin`：向 AE2 合成确认 GUI 注入 `智能合成` 按钮并发送自定义 packet

## 目标功能摘要
- 在 AE2 原合成 UI 上新增 `智能合成` 按钮
- 保留 AE2 原 `合成` 按钮和原有行为
- 递归分析整棵 AE2 合成树
- 先扣 AE 网络库存，只按缺口创建任务
- 对达到阈值的中间产物和最终产物执行拆分
- 生成按依赖分层的智能合成队列
- 自动使用当前空闲 CPU 进行程序性下单
- 下层完成后自动推进上一层，直到最终产物完成

## 已确认规则
- `1g = 1,000,000,000`
- `2.1g = 2,147,483,647`
- 订单量级按整棵树里的最大节点缺口判定，不按最终产物单独判定
- `SMALL`：最大节点缺口 `< 2.1g`
- `MEDIUM`：最大节点缺口 `>= 2.1g` 且 `< 16g`
- `LARGE`：最大节点缺口 `>= 16g`
- `SMALL` 拆分规则：
- `< 1g`：1 个 task / 1 个 CPU
- `>= 1g` 且 `< 2.1g`：1 个 task / 2 个 CPU
- `>= 2.1g`：1 个 task / 3 个 CPU
- `MEDIUM` 拆分规则：
- `< 1g`：1 个 task / 1 个 CPU
- `>= 1g` 且 `< 2.1g`：1 个 task / 2 个 CPU
- `>= 2.1g` 且 `< 4g`：1 个 task / 3 个 CPU
- `>= 4g` 且 `< 8g`：1 个 task / 4 个 CPU
- `>= 8g` 且 `< 16g`：1 个 task / 6 个 CPU
- `LARGE` 拆分规则：
- `< 1g`：1 个 task / 1 个 CPU
- `>= 1g` 且 `< 4g`：1 个 task / 2 个 CPU
- `>= 4g` 且 `< 16g`：1 个 task / 4 个 CPU
- `>= 16g` 且 `< 64g`：1 个 task / 8 个 CPU
- `>= 64g`：16 个 task / 16 个 CPU
- 拆分范围：整棵树中的中间产物和最终产物都递归拆分
- 库存语义：先扣库存，只为缺口建队列
- 调度语义：必须按依赖顺序分层推进
- CPU 语义：自动选择当前空闲 CPU，并受“单节点最大 CPU 数”配置限制

## 架构备注
- 推荐方案是“AE2 原 UI 注入 + 智能合成按钮 + 独立调度器内核”
- AE2 负责单个 job 的实际执行，本模组负责分析、拆分、排队、依赖控制与自动推进
- 当前 AE2 集成基础层已经能把 `CraftingRequest` 树转换成 `SmartCraftOrderBuilder` 可消费的快照结构
- 当前运行态调度层已打通真实 `ICraftingJob` 计算、`submitJob` 提交与 `ICraftingLink` 回填
- 当前 UI 入口层已具备 `智能合成` 按钮、预览入口与最小状态 GUI，并支持取消 / 重试动作回传
- 当前状态页同步仍是“按事件推送”模式，尚未接入持续轮询或调度器驱动的实时刷新
- 当前真实运行态重建仅对 item 请求开放；fluid 请求模板尚未接入自动下单
- 第一版不做运行中订单的跨重启无损恢复，服务器重启后应重新分析当前 AE 网络状态
- 当前可参考本地 AE2 源码目录：`D:\Code\GTNH LIB\Applied-Energistics-2-Unofficial-rv3-beta-695-GTNH`
- 当前已确认的关键 AE2 接入点包括 `GuiCraftConfirm`、`ContainerCraftConfirm`、`PacketValueConfig`、`ICraftingGrid`、`CraftingJobV2`、`CraftingRequest`、`CraftableItemResolver`
- 当前编译与测试验证统一使用 `JDK 21` 运行 Gradle，AE2 依赖坐标使用 `com.github.GTNewHorizons:Applied-Energistics-2-Unofficial:rv3-beta-695-GTNH:dev`
