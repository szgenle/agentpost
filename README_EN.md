# AgentPost

> A mobile console for mailing your home Agent · Native Android

**📖 Language · 语言**：[简体中文](README.md) · **English**

**AgentPost** is an Android app that uses **standard email protocols** as an asynchronous channel, purpose-built for "remotely commanding your home AI agent while you're away." When you're out (on a business trip or traveling), send tasks to your home AI from your phone and check execution logs and final results — just like emailing your secretary at home to handle errands.

[![Build & Test](https://github.com/szgenle/agentpost/actions/workflows/build.yml/badge.svg)](https://github.com/szgenle/agentpost/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%209%2B-green.svg)](#)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1-purple.svg)](https://kotlinlang.org/)
[![Compose](https://img.shields.io/badge/Jetpack-Compose-4285F4.svg)](https://developer.android.com/jetpack/compose)

---

## ✨ Features

- 📮 **Pure standard email protocols**: IMAP + SMTP, zero custom protocols, zero custom headers — **works with any mailbox, zero changes required on the AI side**
- 🧵 **Task-centric aggregation**: Not an inbox, but a task pool. Message-ID thread chains as the main routing path, with normalized Subject matching as fallback
- ⚡ **Dual-mode sync**: 30s foreground polling + 15min background `WorkManager`; enable the experimental toggle for **IMAP IDLE + Foreground Service** real-time push
- 🔔 **System notifications + deep links**: New messages jump straight to the corresponding task detail page
- 📎 **Attachment download & preview**: One-tap system preview after download; **encrypted ZIP auto-decryption** (zip4j, supports ZipCrypto / AES-128/256)
- 📝 **Markdown rendering**: Task details in a timeline style, not chat bubbles
- 📋 **Command templates**: One-tap fill for frequently used commands (CRUD + reorder in Settings)
- 💾 **Local drafts + send state machine**: No input lost when offline; retry on failure
- 🌍 **Multilingual**: Simplified Chinese / English (per-app locale)
- 🛡️ **Privacy-first**: Credentials stored in `EncryptedSharedPreferences` + Android Keystore; self-built crash reporting routes through your own SELF mailbox — no third-party analytics platforms

## 📱 Use Cases

- You run an AI agent at home (Claude Code / Cursor Agent / self-hosted LLM service, etc. — **tech stack agnostic, treated as a black box**)
- The AI is capable of sending and receiving emails
- You're away and want to dispatch tasks / check results, and can accept **minute-level latency**

> Mental model: "emailing your secretary at home to handle things," not "chatting with ChatGPT."

## 🏗️ Tech Stack

| Layer | Choice |
|-------|--------|
| Language | Kotlin 2.1 |
| UI | Jetpack Compose + Material 3 |
| Email | Jakarta Mail / Angus Mail (SMTP + IMAP + IDLE) |
| Storage | Room + DataStore |
| Async | Coroutines + Flow |
| Background | WorkManager + Foreground Service |
| Build | Gradle Kotlin DSL + Version Catalog + Convention Plugins |
| minSdk / targetSdk | 28 / 36 |

## 🧩 Module Structure

```
app                    # App entry, sync scheduling, crash reporter wiring
core/
  ├─ model             # Pure data models (Account / Task / TaskMessage / Attachment / CommandTemplate)
  ├─ common            # Logging, time formatting, ZIP decryption, test utilities
  ├─ database          # Room DAOs / Entities / Converters
  ├─ datastore         # DataStore Preferences (settings, templates)
  ├─ mail              # Jakarta Mail implementation + provider presets + MIME utilities
  ├─ data              # Repositories + Router + credential vault
  └─ ui                # Shared Compose components and resources
feature/
  ├─ tasks             # Task list / detail / unclassified management
  ├─ newtask           # Task creation + template picker
  └─ settings          # Account config / template management / crash reporting / real-time push
build-logic/           # Convention Plugins (AndroidApplication / AndroidLibrary / AndroidFeature)
```

## 🚀 Getting Started

### Prerequisites

- JDK 17
- Android Studio Ladybug or newer (AGP 8.12+)
- Android device / emulator: Android 9 (API 28) or above

### Build & Run

```bash
# Clone the repository
git clone https://github.com/szgenle/agentpost.git
cd agentpost

# Build + install + launch + tail logs in one command (requires a connected device)
make install-run

# Compile only
make build
# or
./gradlew :app:assembleDebug

# Run unit tests
./gradlew :core:common:testDebugUnitTest :core:data:testDebugUnitTest
```

See [Makefile](Makefile) for more commands (`make help` lists all targets).

### First-Time Setup

1. Launch the app and open **Settings**
2. Configure two accounts:
   - **SELF** (your own mailbox): used to send commands and receive AI replies
   - **AGENT** (your home AI's mailbox): the recipient
3. For each account, fill in the IMAP / SMTP host, port, SSL/TLS, and password (or app-specific password)
4. Go back to the task list, tap the **+** FAB to create your first task

> For **Gmail / Outlook / etc.**, use an **app-specific password** instead of your main account password. Generate one in the account security settings after enabling 2FA.

### What Does the Home AI Need to Do?

**Nothing.**

As long as your AI can receive emails and reply using the standard "reply" feature, the mail library will automatically attach `In-Reply-To` / `References` headers, and AgentPost will route the reply to the correct task. No need to modify the Subject, no custom headers, no protocol agreements.

## 🗺️ Roadmap

Completed (v0.1):

- [x] MVP: account setup, task CRUD, SMTP/IMAP, Message-ID routing
- [x] Attachment download + encrypted ZIP auto-decryption
- [x] Notification deep links, pull-to-refresh, task summaries with unread badges
- [x] Send state machine + local drafts
- [x] Unclassified message management, command templates
- [x] Logging infrastructure, self-built crash reporting
- [x] IMAP IDLE real-time push (experimental toggle)
- [x] Multilingual (zh-CN / en)

Planned:

- [ ] Multi-Agent support (data layer already prepared)
- [ ] In-app attachment preview (images / PDF / Markdown / code highlighting)
- [ ] Sender-side attachment picker
- [ ] Command template placeholders (`{{date}}` / `{{task}}`)

## 🤝 Contributing

Issues and PRs are welcome. Before submitting a PR, please make sure:

1. Code follows the project's existing style (official Kotlin conventions + Compose best practices)
2. `./gradlew :app:assembleDebug` succeeds
3. Unit tests are added for changes affecting core logic
4. Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/) (e.g. `feat(tasks): ...` / `fix(mail): ...`)

## 📄 License

This project is licensed under the [Apache License 2.0](LICENSE).

```
Copyright 2026 szgenle

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0
```

Third-party copyright notices are listed in [NOTICE](NOTICE).

## 🙏 Acknowledgments

Thanks to the following open source projects:

- [Jakarta Mail / Angus Mail](https://eclipse-ee4j.github.io/mail/) — the email protocol foundation
- [Zip4j](https://github.com/srikanth-lingala/zip4j) — encrypted ZIP support
- [Jetpack Compose](https://developer.android.com/jetpack/compose) — modern UI toolkit

---

<sub>AgentPost is maintained by [szgenle](https://lioesquieu.github.io/) ([GitHub](https://github.com/szgenle) · [Homepage](https://lioesquieu.github.io/)). Got ideas? Let's chat in [Issues](https://github.com/szgenle/agentpost/issues).</sub>
