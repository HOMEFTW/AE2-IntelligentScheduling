# TODO 列表

## 当前计划
- [ ] 实现智能合成分析、拆分、分层调度与 UI
- [ ] 补强状态 GUI 的实时刷新、分页/滚动展示与更细粒度任务反馈

## 未来想法
- [ ] 评估是否需要支持运行中智能订单的跨重启恢复
- [ ] 评估是否需要提供整棵合成树的可视化展示
- [ ] 评估是否需要支持玩家限制可用 CPU 范围

## 已完成
- [x] 明确新模组名称为 `AE2-IntelligentScheduling`
- [x] 完成智能合成整体设计并落地为 spec 文档
- [x] 基于设计文档完成实现计划，并确认可参考本地 AE2 源码项目
- [x] 审阅并确认 `docs/superpowers/plans/2026-04-22-ae2-intelligent-scheduling-implementation.md`
- [x] 接入 AE2 相关依赖与基础集成点，并完成 GTNH 模组脚手架与 `compileJava` 验证
- [x] 完成纯规划模型、订单量级判定与三档拆分规则实现，并跑通对应测试
- [x] 基于 AE2 CPU 状态截图重定拆分规则为 `1 / 2 / 4 / 8 / 16`
- [x] 将订单规则升级为“小 / 中 / 大”三档，并确定按整棵树最大节点缺口判定
- [x] 完成 AE2 合成树转智能订单与分层队列的基础实现，并跑通 `SmartCraftOrderBuilderTest` 与 `Ae2CraftTreeWalkerTest`
- [x] 完成运行态 `SmartCraftOrderManager`、`SmartCraftScheduler`、`SmartCraftRequesterBridge` 骨架，并跑通 `Ae2CpuSelectorTest` 与 `SmartCraftSchedulerTest`
- [x] 完成 `GuiCraftConfirm` 上的 `智能合成` 按钮、自定义 packet 预览入口与基础订单登记链路，并跑通 `SmartCraftPacketCodecTest`
- [x] 完成智能合成状态页骨架、订单同步 packet、取消 / 重试动作链路，并补齐 Task 6 的 Java 8 兼容修复
- [x] 打通真实 `ICraftingJob` 计算 / 提交 / link 轮询链路，并新增服务端运行态协调器自动推进智能合成订单

## 暂缓 / 拒绝
- 暂缓：第一版不做跨重启运行中订单无损恢复，原因是需要持久化整棵运行态任务树并重新绑定 AE2 运行中 job，复杂度过高
