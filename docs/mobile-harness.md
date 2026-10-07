# 移动端 Harness 与 Muse

核对日期：2026-10-07。这里整理其他项目的产品和工程方向；FoliKeep 的功能范围见 README。

## 移动端已经有 Harness 吗

有。移动端项目既有连接云端执行层的客户端，也有在手机上运行执行层的应用。看项目时应先问：对话和执行状态在哪里、工具在哪里运行、模型在哪里推理。

| 项目 | 它做什么 | 与 FoliKeep 的区别 |
|---|---|---|
| [Mobile Harness](https://github.com/techjarves/Mobile-Harness) | 原生 Android 界面，加 PRoot Linux 用户空间，运行编码 agent、命令及开发工具 | 通用开发环境，安装和运行成本比阅读问答更高 |
| [Hermes Agent Mobile](https://github.com/amirghm/hermes-agent-mobile) | 项目提供移动环境的安装方式，Android 路径使用 Termux | 把现有 agent 带进手机环境；FoliKeep 使用应用内的 Kotlin 阅读执行层 |
| [PhoneHarness](https://phoneharness.github.io/) | 研究用的手机 agent 编排，结合设备侧 CLI、GUI 委派与主机工具 | 自动操作与基准评测，需要区别设备侧和主机侧组件 |
| [AOHP](https://github.com/aohp-os/aohp) | 基于 AOSP 的操作系统级 agent harness | 涉及系统层集成，不能直接当作普通阅读 APK 依赖 |

以上描述来自各项目自己的文档，没有在这里做设备实测。FoliKeep 的定位是应用内阅读问答：资料与上下文编排在本机，模型请求发到用户接口，不提供 Linux 工作区或手机自动操作。

## 最近提到的 Muse 是什么

如果指近期的 Meta Muse，它是个人 agent 产品。Meta 的[官方架构说明](https://research.meta.ai/blog/security-and-safety-for-ai-agents-our-approach-with-muse)描述了独立云端计算环境，手机和网页客户端连接云端执行层。

它把任务执行、工具、持久状态与权限控制组织起来。可以从中参考资料边界和权限分离的思路。

另有名为 [MUSE: A Unified Agentic Harness for MLLMs](https://github.com/Jianglin954/MUSE) 的研究项目，围绕多模态任务、结构化执行、验证和修复。它是独立的研究项目。Muse Spark 则是模型名称，也不能和 agent 产品或 harness 互换。

## FoliKeep 可以怎样介绍

对外介绍可使用“带安卓端上下文管理的本地 PDF 阅读器”，或“面向阅读问答的 Kotlin harness”。重点说明资料关联、对话压缩和本地保存，缓存命中率以服务商返回的实际用量为准。
