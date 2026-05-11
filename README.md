# AgentPost

> 给家里 Agent 寄信的移动端控制台 · Android 原生

**📖 Language · 语言**：**简体中文** · [English](README_EN.md)

**AgentPost** 是一个用**标准邮件协议**作为异步通道的 Android 应用，专为"在外远程控制家里 AI 智能体"而生。人在外（出差/旅行）时，通过手机向家里的 AI 下达任务、查看执行日志和最终结果——就像给家里的秘书发邮件交代事情。

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%209%2B-green.svg)](#)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1-purple.svg)](https://kotlinlang.org/)
[![Compose](https://img.shields.io/badge/Jetpack-Compose-4285F4.svg)](https://developer.android.com/jetpack/compose)

---

## ✨ 特性一览

- 📮 **纯标准邮件协议**：IMAP + SMTP，零自定义协议、零自定义 header，**任何邮箱都能用，家里 AI 零改造**
- 🧵 **按任务聚合**：不是收件箱，是任务池。以 Message-ID 线程链为主路、Subject 规范化匹配兜底
- ⚡ **双模同步**：前台 30s 轮询 + 后台 15min WorkManager；开启实验开关后走 **IMAP IDLE + Foreground Service** 实时推送
- 🔔 **系统通知 + 深链**：新消息直接跳转到对应任务详情页
- 📎 **附件收发**：下载后一键系统预览；**加密 zip 自动解密**（zip4j，支持 ZipCrypto / AES-128/256）
- 📝 **Markdown 渲染**：任务详情时间线风格，不是气泡
- 📋 **命令模板**：常用指令一键填充（设置页 CRUD + 排序）
- 💾 **本地草稿 + 发送状态机**：断网不丢字，失败可重试
- 🌍 **多语言**：简体中文 / English（per-app locale）
- 🛡️ **端到端隐私**：凭据走 EncryptedSharedPreferences + Android Keystore；自建崩溃上报走你自己的 SELF 邮箱，不依赖任何三方分析平台

## 📱 使用场景

- 家里跑着一个 AI 智能体（Claude Code / Cursor Agent / 自建 LLM 服务等，**技术栈无关，视为黑盒**）
- AI 具备收发邮件能力
- 你人在外，希望下指令/查结果，能接受**分钟级延迟**

> 心理模型：「给家里的秘书发邮件交代事情」，不是「和 ChatGPT 闲聊」。

## 🏗️ 技术栈

| 层 | 选型 |
|----|------|
| 语言 | Kotlin 2.1 |
| UI | Jetpack Compose + Material 3 |
| 邮件 | Jakarta Mail / Angus Mail（SMTP + IMAP + IDLE）|
| 存储 | Room + DataStore |
| 异步 | Coroutines + Flow |
| 后台 | WorkManager + Foreground Service |
| 构建 | Gradle Kotlin DSL + Version Catalog + Convention Plugins |
| minSdk / targetSdk | 28 / 36 |

## 🧩 模块结构

```
app                    # 应用入口、同步调度、崩溃上报装配
core/
  ├─ model             # 纯数据模型（Account / Task / TaskMessage / Attachment / CommandTemplate）
  ├─ common            # 日志、时间格式化、ZIP 解密、测试辅助
  ├─ database          # Room DAO / Entity / Converter
  ├─ datastore         # DataStore Preferences（偏好、模板）
  ├─ mail              # Jakarta Mail 实现 + 提供商预设 + MIME 工具
  ├─ data              # Repository + Router + 凭据保险箱
  └─ ui                # 共用 Compose 组件与资源
feature/
  ├─ tasks             # 任务列表 / 详情 / 未分类管理
  ├─ newtask           # 新建任务 + 模板选择
  └─ settings          # 账户配置 / 模板管理 / 崩溃上报 / 实时推送
build-logic/           # Convention Plugins（AndroidApplication / AndroidLibrary / AndroidFeature）
```

## 🚀 快速开始

### 环境要求

- JDK 17
- Android Studio Ladybug 或更新（AGP 8.12+）
- Android 设备 / 模拟器：Android 9 (API 28) 及以上

### 构建 & 运行

```bash
# 克隆仓库
git clone https://github.com/szgenle/agentpost.git
cd agentpost

# 一键构建 + 安装 + 启动 + 跟日志（需连接设备）
make install-run

# 仅编译
make build
# 或
./gradlew :app:assembleDebug

# 跑单元测试
./gradlew :core:common:testDebugUnitTest :core:data:testDebugUnitTest
```

更多命令见 [Makefile](Makefile)（`make help` 查看全部目标）。

### 首次配置

1. 启动 App，进入「设置」
2. 填入两个账户：
   - **SELF**（你自己的邮箱）：用来发送指令与接收 AI 的回复
   - **AGENT**（家里 AI 的邮箱）：作为收件方
3. 每个账户需要填写 IMAP / SMTP 主机、端口、SSL/TLS、密码（或 App 专用密码）
4. 回到任务列表，点右下角 **+** 新建任务发出第一条指令即可

> **Gmail / Outlook 等** 建议使用**应用专用密码**（App Password），而不是主账号密码。开启两步验证后在账号安全设置里生成。

### 家里 AI 需要做什么？

**答：什么都不用做。**

只要你的 AI 能收邮件并用"回复"功能回信，标准邮件库会自动带上 `In-Reply-To` / `References` header，AgentPost 就能把回信路由到对应任务。不需要改 Subject、不需要加自定义 header、不需要约定任何协议。

## 🗺️ 路线图

已完成（v0.1）：

- [x] MVP：账户配置、任务 CRUD、SMTP/IMAP、Message-ID 路由
- [x] 附件下载 + 加密 zip 自动解密
- [x] 通知深链、下拉刷新、任务摘要与未读角标
- [x] 发送状态机 + 本地草稿
- [x] 未分类消息管理、命令模板
- [x] 日志基础设施、自建崩溃上报
- [x] IMAP IDLE 实时推送（实验开关）
- [x] 多语言（zh-CN / en）

计划中：

- [ ] 多 Agent 支持（数据层已预留）
- [ ] 附件内嵌预览（图片 / PDF / Markdown / 代码高亮）
- [ ] 发送端附件选择器
- [ ] 命令模板占位符（`{{date}}` / `{{task}}`）

## 🤝 贡献

欢迎 issue 与 PR。在提交 PR 前，请确保：

1. 代码遵循项目既有风格（Kotlin 官方规范 + Compose 最佳实践）
2. `./gradlew :app:assembleDebug` 通过
3. 影响核心逻辑的改动补充单元测试
4. 提交信息遵循 [Conventional Commits](https://www.conventionalcommits.org/)（如 `feat(tasks): ...` / `fix(mail): ...`）

## 📄 License

本项目采用 [Apache License 2.0](LICENSE) 开源协议。

```
Copyright 2026 szgenle

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0
```

第三方依赖的版权声明见 [NOTICE](NOTICE)。

## 🙏 致谢

感谢以下开源项目：

- [Jakarta Mail / Angus Mail](https://eclipse-ee4j.github.io/mail/) — 邮件协议基石
- [Zip4j](https://github.com/srikanth-lingala/zip4j) — 加密 ZIP 支持
- [Jetpack Compose](https://developer.android.com/jetpack/compose) — 现代 UI 工具包

---

<sub>AgentPost 由 [szgenle](https://lioesquieu.github.io/) 维护（[GitHub](https://github.com/szgenle) · [个人主页](https://lioesquieu.github.io/)）。有想法？欢迎在 [Issues](https://github.com/szgenle/agentpost/issues) 里聊聊。</sub>
