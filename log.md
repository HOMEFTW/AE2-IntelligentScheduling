# 开发日志

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
