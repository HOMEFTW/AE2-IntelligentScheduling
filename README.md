# AE2-IntelligentScheduling

> 为 GTNH（Minecraft 1.7.10）的 Applied Energistics 2 提供"智能合成调度"层，把一次大合成拆解为多层小订单，按 CPU 空闲情况依序自动下单，让多 CPU 网络真正跑满。

## 功能概览

- **智能合成按钮**：在 AE2 合成确认界面（Crafting Confirm）右下注入"智能合成"按钮，点击即按当前请求生成调度订单。
- **分层任务规划**：以"中间产物"为切割点把一次复杂合成拆成多层（layer），层内任务并行、层间顺序推进。
- **自动 CPU 调度**：每个 server tick 检查空闲 CPU，把已规划完成的任务依次提交到 AE2 合成系统，避免一次性塞满单 CPU。
- **状态可视化**：自定义 GUI 复刻 AE2 合成状态界面风格，6×3 任务格、滚动条、状态色与状态文字与原版一致；同时在 AE2 合成状态界面注入"查看调度"按钮和补充 tooltip。
- **取消 / 重试**：失败任务可一键重试；整单可一键取消并真正回收 AE2 合成 link。
- **本地化**：内置中英双语。

## 依赖

| 依赖 | 版本 |
|---|---|
| Minecraft | 1.7.10 |
| Forge | 10.13.4.1614 |
| Applied Energistics 2 (Unofficial GTNH) | rv3-beta-695 及以上 |
| Java | 8（运行时） / 21（构建时） |

## 安装

1. 从 [Releases](https://github.com/HOMEFTW/AE2-IntelligentScheduling/releases) 下载对应版本的 `ae2intelligentscheduling-<version>.jar`
2. 放进客户端 / 服务端的 `mods/` 目录
3. 启动游戏。该 mod 仅依赖 AE2，不需要额外配置即可使用

> 客户端和服务端都必须安装。

## 使用

1. 在 AE2 终端发起任意合成请求，进入"合成确认"界面
2. 点击右下角的 **"智能合成"** 按钮，订单会被切分并开始调度
3. 在 AE2 合成状态界面或 mod 自带的"查看调度"界面查看每层任务进度
4. 失败时可点击 **"重试失败"**；想终止整单点 **"取消订单"**

## 构建

```bash
./gradlew --no-daemon spotlessApply test reobfJar
```

产物位于 `build/libs/ae2intelligentscheduling-<version>.jar`（不带 `-dev` 后缀的那个为反混淆产物，用于实机）。

## 已知问题 / 限制

- 仅在单玩家发起的合成上下文中受支持；自动机器（接口、子网）触发的合成走 AE2 原生流程，不被本 mod 接管
- 当前不持久化订单到存档；服务器重启后未完成的智能合成订单会丢失，AE2 已经下发的 link 由 AE2 自行处理
- 目前只针对 AE2 GTNH 分支测试过

## 许可证

本仓库内代码采用 MIT 许可证。AE2 相关 API、贴图、本地化键名归 Applied Energistics 2 项目所有。
