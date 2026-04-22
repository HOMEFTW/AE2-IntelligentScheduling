# 开发日志

## 2026-04-22：实现计划定稿并接入 AE2 源码参考

### 已完成
- 基于已确认 spec 编写实现计划文档 `docs/superpowers/plans/2026-04-22-ae2-intelligent-scheduling-implementation.md`
- 明确实现计划默认使用 `modId=ae2intelligentscheduling`
- 明确实现计划默认使用根包 `com.homeftw.ae2intelligentscheduling`
- 确认可直接参考本地 AE2 源码目录 `D:\Code\GTNH LIB\Applied-Energistics-2-Unofficial-rv3-beta-695-GTNH`
- 确认首批关键接入点为 `GuiCraftConfirm`、`ContainerCraftConfirm`、`PacketValueConfig`、`ICraftingGrid`、`CraftingJobV2`、`CraftingRequest`、`CraftableItemResolver`

### 遇到的问题
- AE2 的“智能合成”入口不能直接复用原 `Terminal.Start` 路径，否则会覆盖原有按钮行为
- 若要可靠追踪提交后的 job 状态，不能长期依赖 `requestingMachine = null`，需要单独实现 `ICraftingRequester` 桥接

### 设计决策
- `智能合成` 按钮通过 Mixin 注入到 `GuiCraftConfirm`
- 客户端点击 `智能合成` 后改走本模组自定义 packet，而不是 AE2 自带的 `PacketValueConfig("Terminal.Start", ...)`
- 合成树分析优先基于 `CraftingJobV2 -> CraftingRequest.usedResolvers -> CraftFromPatternTask.childRequests` 做只读遍历

---

## 2026-04-22：AE2-IntelligentScheduling 设计定稿

### 已完成
- 确认新模组名称为 `AE2-IntelligentScheduling`
- 明确功能载体为 AE2 网络部件 / 终端
- 明确保留 AE2 原 `合成` 按钮，仅新增 `智能合成` 按钮
- 明确智能合成将递归分析整棵 AE2 合成树
- 明确库存语义为先扣 AE 现存库存，再按缺口生成队列
- 明确调度方式为按依赖分层下单，先底层后上层
- 明确 CPU 选择为自动挑选当前空闲 CPU
- 将完整设计写入 `docs/superpowers/specs/2026-04-22-ae2-intelligent-scheduling-design.md`

### 遇到的问题
- 当前 `D:\Code` 根目录不是单独的 git 仓库，不能直接在根目录提交设计文档
- 需要为新模组建立独立项目目录后，再对设计文档和跟踪文件进行版本管理

### 设计决策
- `1g` 固定定义为 `1,000,000,000`
- `2.1g` 固定定义为 `2,147,483,647`
- 大缺口的拆分规则固定为 `<1g -> 1 task`、`>=1g 且 <2.1g -> 2 task`、`>=2.1g -> 3 task`
- 第一版不做跨重启运行中订单无损恢复，改为“重启后重新分析并重建队列”

---
