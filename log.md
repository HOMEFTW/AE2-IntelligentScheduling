# 开发日志

## 2026-04-22：将构建入口当前状态切回 Zulu21
### 已完成
- 确认实际工程位于 .worktrees/implement-smart-craft，并保留 gradle.properties 中 org.gradle.java.installations.fromEnv = JAVA_HOME 的构建入口。
- 在工作树 gradle.properties 中补充说明，当前默认构建 JDK 期望为 JAVA_HOME 指向 Zulu21。
- 保留腾讯云 Gradle 与 Maven 镜像配置，不回退镜像入口。

### 决策记录
- 项目继续通过 JAVA_HOME 切换构建 JDK，不在仓库中写死本机 JDK 路径。

---


## 2026-04-22：加入小 / 中 / 大三挡订单分流

### 已完成
- 明确订单量级按整棵树中的最大节点缺口判定，而不是只看最终产物
- 将订单量级分为 `SMALL / MEDIUM / LARGE`
- 明确阈值为 `< 2.1g`、`>= 2.1g 且 < 16g`、`>= 16g`
- 为 `MEDIUM` 档补入过渡拆分规则：`1 / 2 / 3 / 4 / 6`
- 约定 `SMALL` 保留原先的小单智能拆分模式，`LARGE` 使用激进分流模式
- 已同步更新 spec、implementation plan、context.md 与 ToDOLIST.md

### 遇到的问题
- 仅保留“小单 / 大单”两挡时，`2.1g ~ 16g` 区间缺少平滑过渡，既容易让中等订单拆得不够，也容易让规则语义不清晰

### 设计决策
- 订单量级先做一次全局判定，再按对应量级规则去拆每个节点
- `SMALL`：沿用 `1 / 2 / 3`
- `MEDIUM`：使用 `1 / 2 / 3 / 4 / 6`
- `LARGE`：使用 `1 / 2 / 4 / 8 / 16`
- 实际分配时仍然裁剪到 `min(规则建议值, 当前空闲 CPU 数, 配置允许的单节点最大 CPU 数)`

---

## 2026-04-22：按 AE2 CPU 截图重定拆分规则

### 已完成
- 结合 AE2 合成状态截图重新评估大单拆分策略
- 放弃原先基于 `1g / 2.1g -> 1 / 2 / 3 CPU` 的拆分方案
- 将拆分规则改为当时阶段的大单分流方案：`<1g -> 1`、`1g~4g -> 2`、`4g~16g -> 4`、`16g~64g -> 8`、`>=64g -> 16`
- 明确 `2.1g` 继续保留为数量单位边界参考值，但不再作为 CPU 分档阈值
- 已同步更新 spec、implementation plan、context.md 与 ToDOLIST.md

### 遇到的问题
- 从截图可以看到大量 `27G`、`41G`、`52G`、`54G`、`151G` 量级任务，原先最多只拆到 `3` 个 CPU 的方案无法有效打散超大单

### 设计决策
- 第一版当时先以“大单数量级分档”作为过渡方案，不直接引入更复杂的动态负载算法
- 实际分配时仍要裁剪到 `min(规则建议值, 当前空闲 CPU 数, 配置允许的单节点最大 CPU 数)`

---

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
- 大缺口的拆分规则已被后续分档方案替代，本条记录仅代表当时阶段性结论
- 第一版不做跨重启运行中订单无损恢复，改为“重启后重新分析并重建队列”

---
