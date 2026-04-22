# 项目上下文

## 基本信息
- Mod Name: AE2-IntelligentScheduling
- Mod ID: `ae2intelligentscheduling`（计划默认值）
- Package: `com.homeftw.ae2intelligentscheduling`（计划默认值）
- Target: Minecraft 1.7.10 + GTNH + AE2
- 当前阶段：已完成项目脚手架与纯规划模型，开始进入 AE2 树转订单实现阶段

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
- 已创建 `SmartCraftOrderScaleClassifier` 与 `SmartCraftSplitPlanner`
- 已验证 `SmartCraftSplitPlannerTest` 通过

### 机器 / 部件
- 暂无代码实现

### 物品
- 暂无代码实现

### 方块
- 暂无代码实现

### 配方
- 暂无代码实现

### 配置项
| Key | Default | Description |
|-----|---------|-------------|
| `maxCpuPerNode` | `16` | 单个缺口节点允许分配的最大 CPU 数 |
| `enableDebugLogging` | `false` | 是否启用智能合成调试日志 |

### Mixin
- `mixins.ae2intelligentscheduling.json`：基础 mixin 配置文件，当前尚未注册具体 mixin 类

## 目标功能摘要
- 在 AE2 原合成 UI 上新增 `智能合成` 按钮
- 保留 AE2 原 `合成` 按钮和原有行为
- 递归分析整棵 AE2 合成树
- 先扣 AE 网络库存，只按缺口创建任务
- 对达到阈值的中间产物和最终产物执行拆分
- 生成按依赖分层的智能合成队列
- 自动使用当前空闲 CPU 进行程序性下单
- 下层完成后自动推进上一级，直到最终产物

## 已确认规则
- `1g = 1,000,000,000`
- `2.1g = 2,147,483,647`
- 订单量级按整棵树里的最大节点缺口判定，不按最终产物单独判定
- `SMALL`：最大节点缺口 `< 2.1g`
- `MEDIUM`：最大节点缺口 `>= 2.1g` 且 `< 16g`
- `LARGE`：最大节点缺口 `>= 16g`
- `SMALL` 订单拆分：
- `< 1g`：1 个 task / 1 个 CPU
- `>= 1g` 且 `< 2.1g`：2 个 task / 2 个 CPU
- `>= 2.1g`：3 个 task / 3 个 CPU
- `MEDIUM` 订单拆分：
- `< 1g`：1 个 task / 1 个 CPU
- `>= 1g` 且 `< 2.1g`：2 个 task / 2 个 CPU
- `>= 2.1g` 且 `< 4g`：3 个 task / 3 个 CPU
- `>= 4g` 且 `< 8g`：4 个 task / 4 个 CPU
- `>= 8g` 且 `< 16g`：6 个 task / 6 个 CPU
- `LARGE` 订单拆分：
- `< 1g`：1 个 task / 1 个 CPU
- `>= 1g` 且 `< 4g`：2 个 task / 2 个 CPU
- `>= 4g` 且 `< 16g`：4 个 task / 4 个 CPU
- `>= 16g` 且 `< 64g`：8 个 task / 8 个 CPU
- `>= 64g`：16 个 task / 16 个 CPU
- 拆分范围：整棵树中的中间产物和最终产物都递归拆分
- 库存语义：先扣库存，只为缺口建队列
- 调度语义：必须按依赖顺序分层推进
- CPU 语义：自动选择当前空闲 CPU，并受“单节点最大 CPU 数”配置限制

## 架构备注
- 推荐方案为“AE2 原 UI 注入 + 智能合成按钮 + 独立调度器内核”
- AE2 负责单个 job 实际执行，本模组负责分析、拆分、排队、依赖控制与自动推进
- 第一版不做运行中订单的跨重启无损恢复，服务器重启后应重新分析当前 AE 网络状态
- 当前已确认可参考本地 AE2 源码目录：`D:\Code\GTNH LIB\Applied-Energistics-2-Unofficial-rv3-beta-695-GTNH`
- 当前已确认的关键 AE2 接入点包括 `GuiCraftConfirm`、`ContainerCraftConfirm`、`PacketValueConfig`、`ICraftingGrid`、`CraftingJobV2`、`CraftingRequest`、`CraftableItemResolver`
- 当前编译验证使用 `JDK 21` 运行 Gradle，AE2 编译依赖使用 `com.github.GTNewHorizons:Applied-Energistics-2-Unofficial:rv3-beta-695-GTNH:dev`
