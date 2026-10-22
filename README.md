<div align="center">

# PenAi

### 运行在 Android 上的网文写作 AI Agent · 把「想故事」变成「可交付章节」

*On-device AI writing agent for Chinese web novels — a seven-stage chapter workflow, 38 writing tools, 25 genre presets and a character-relationship graph, all on your phone.*

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](./LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android--API%2026%2B-3DDC84.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3-7F52FF.svg)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-1.10.2-4285F4.svg)](https://developer.android.com/compose)
[![基座](https://img.shields.io/badge/base-ZorvAI%20v1.1.7-8A8A8A.svg)](https://github.com/Quor-a/ZorvAI)
[![CI](https://img.shields.io/github/actions/workflow/status/qq1375828505/PenAi/.github/workflows/build.yml?branch=main)](https://github.com/qq1375828505/PenAi/actions)

</div>

---

## 这是什么

PenAi 是在 **[ZorvAI](https://github.com/Quor-a/ZorvAI)**（Android 端侧 AI Agent 基座，Apache-2.0）之上改造的**网文写作专用 Agent**。

- **基座提供**：端侧离线推理（MNN / llama.cpp）、proot 终端、脚本引擎、工具运行时、插件与 ACI 框架、记忆与知识库、通用对话界面。
- **PenAi 提供**：把「写一章网文」拆成七阶段的领域工作流、38 个写作工具、25 套中文网文流派预设、6 个写作子代理，以及伏笔 / 人物关系 / 记忆三件套图谱。
- **工程铁律**（承自基座）：**无兜底**（解析失败即抛错，绝不返回假数据）、**断点续跑**（每阶段状态落盘，杀进程可恢复）、**显式契约**（阶段输入输出编码在数据类上，不靠约定俗成）。

## 核心能力

### 七阶段写章工作流

```
需求理解 → 资料收集 → 细纲生成 → 正文撰写 → 自审 → 润色 → 交付
```

每个阶段的产出都是强类型数据对象并落盘。某一阶段失败时只把**该阶段**降级为待重跑，其余阶段断点照常恢复——不会因为一处出错丢掉整条进度。

### 四域 38 个写作工具

| 域 | 数量 | 能力 |
|---|:--:|---|
| 结构域 | 13 | 作品 / 卷 / 章 CRUD、大纲读写、章节状态与统计、续写 |
| 资料域 | 6 | 八类资料（人物/势力/地点/物品/能力/情节线/设定/参考资料）增删改查与 RAG 检索 |
| 图谱域 | 8 | 伏笔生命周期、人物关系 CRUD 与关系图数据、记忆存取、一致性检查 |
| 写作域 | 11 | 流派切换与上下文组装、正文写作、标题候选、去 AI 味诊断、双点 / 水字数仪表盘、子代理委托 |

工具全部走基座的统一注册表（`QuroBuiltInTools` + `coreNames` 白名单），AI 可自主调用，也可由用户手动触发。

### 25 套中文网文流派预设

内置 25 套流派规约（都市爽文、玄幻奇幻、悬疑推理、古代言情、青春校园、无限流、科幻末世、种田日常……），可一键套用到当前作品，并按平台章节规约裁剪上下文预算。预设以资源包形式随仓库分发（`assets/skills/penai/`），不依赖联网。

### 6 个写作子代理 + 委托协议

大纲、人物、伏笔、润色、审读、一致性——每个子代理一份独立提示词资产。委托走统一信封协议：会话 ID、目标路由、轮次上限、前轮结果回传、**零写断言**（子代理不得改文件）、失败可见并附可用子代理清单。

### 三件套图谱

- **伏笔**：埋设 → 推进 → 回收的状态机，全书可查；
- **人物关系**：带类型与强度的边，可渲染为关系图（Compose Canvas + 斥力物理模拟）；
- **记忆库**：跨会话的写作记忆，支撑全书一致性检查。

---

## 架构

```
┌──────────────── ZorvAI 基座（Apache-2.0，上游可持续同步）────────────────┐
│ 端侧推理 MNN/llama.cpp · proot 终端 · 脚本引擎 · 工具运行时 · 插件/ACI   │
│ 记忆与知识库 · 通用对话与设置界面                                         │
└───────────────────────────────────────────────────────────────────────────┘
                              ▲ 同构扩展（工具域 + 技能套件，不 fork 基座）
┌────────────────────────── PenAi 增量层 ──────────────────────────────────┐
│ core/novel/           领域契约：作品/卷/章 · 资料资产 · 图谱 · 七阶段工作流 │
│ core/tools/novel/     38 个写作工具（结构 / 资料 / 图谱 / 写作）          │
│ assets/skills/penai/  25 套流派预设 + manifest                          │
│ assets/prompts/subagents/ 6 个子代理提示词 + 委托协议                    │
│ ui/novel/             PenAi 写作界面（原生 Compose）                     │
└──────────────────────────────────────────────────────────────────────────┘
```

### 目录速览

| 路径 | 内容 |
|---|---|
| `app/src/main/java/com/ai/assistance/quro/core/novel/` | 领域契约层：作品/卷/章模型、资料仓储、图谱存储、工作流模型与编解码（纯 Kotlin，无 Android 依赖，可 JVM 单测） |
| `app/src/main/java/com/ai/assistance/quro/core/tools/novel/` | 38 个写作工具实现与工具集注册 |
| `app/src/main/assets/skills/penai/` | 25 套流派预设资源包 |
| `app/src/main/assets/prompts/subagents/` | 子代理提示词与委托协议（`DELEGATION.md`） |
| `app/src/main/java/com/ai/assistance/quro/ui/novel/` | PenAi 写作界面与设计令牌 |
| `docs/plan-v1.1/` | 《PenAi 软件改造计划书》（含目标形态、裁剪清单、UI 路线裁决） |
| `docs/progress/` | 工程恢复方案、阶段落地说明与交接文档 |

## 开发进度

| 阶段 | 内容 | 状态 |
|---|---|---|
| Phase 3a | 结构域：作品/卷/章/大纲 | ✅ |
| Phase 3b | 资料域：八类资产 + RAG 检索 | ✅ |
| Phase 3c | 图谱域：伏笔 / 关系 / 记忆 + 一致性检查 | ✅ |
| Phase 4a | 写作域：流派、标题候选、去 AI 味、水字数/双点仪表盘、子代理委托 | ✅ |
| Phase 5 | 契约对齐：工作流模型与 Phase 1/3a/3b 同源（无第二套章节/大纲/资料真相） | ✅ |
| Phase 6 | PenAi 写作界面（Compose） | 🚧 进行中 |
| Phase 7b | 正式签名打包与发布 | ⏳ 待办 |

> UI 路线（计划书已冻结）：**沿用上游原生 Kotlin + Jetpack Compose**；写作界面自研，不移植 Flutter 代码——仅参考 novelIDE 的信息架构与交互范式。富文本编辑器为独立 `:novel-editor` 模块（WebView 承载，可摘除降级纯文本），关系图为自研 Compose Canvas 渲染。

## 从源码构建

### 系统要求

- **Android 8.0+（API 26+）** 设备
- **开发机**：JDK **17+**、Android SDK（compileSdk 36 / minSdk 26）、Gradle（用仓库自带 wrapper `./gradlew`）；编译离线引擎原生库需 NDK

### 构建

```bash
# 1. 克隆仓库
git clone https://github.com/qq1375828505/PenAi
cd PenAi

# 2. 准备环境
#    - 安装 JDK 17+，在 local.properties 配置 sdk.dir=/path/to/Android/Sdk
#    - 需要离线引擎原生库时安装 NDK

# 3. 构建 debug 包（产出 full + fdroid 两个风味）
./gradlew assembleDebug
#    产物：app/build/outputs/apk/*/debug/app-*-debug.apk

# 4. 构建 release 包（需自备签名配置，凭证与 scripts/sign.sh 对齐）
./gradlew :app:assembleFullRelease
#    产物：app/build/outputs/apk/full/release/app-full-release.apk

# 清理
./gradlew clean
```

> `full` 风味含预编译原生库（ncnn / sherpa / proot）与源码编译的离线引擎（`:mnn` + `:llama`）；`fdroid` 风味不含任何预编译二进制。正式发布走 `scripts/sign.sh`（幂等校验/补签）与 tag 触发的 release workflow。

### CI

`build` 工作流在每次推送后自动执行：`assembleDebug` → `:app:testDebugUnitTest` → 上传 APK 与测试报告 artifact；打 `v*` tag 触发签名发布流程。

## 文档索引

| 文档 | 内容 |
|---|---|
| [`docs/plan-v1.1/plan.md`](docs/plan-v1.1/plan.md) | 《PenAi 软件改造计划书》：目标形态、能力裁剪清单、UI 路线与分层裁决 |
| [`docs/progress/ENGINEERING_RECOVERY_PLAN.md`](docs/progress/ENGINEERING_RECOVERY_PLAN.md) | 工程恢复方案与 Phase 5 逐类型裁决表 |
| [`docs/progress/HANDOFF_2026-10-21.md`](docs/progress/HANDOFF_2026-10-21.md) | 阶段交接文档（跨软件迁移与修复记录） |
| [`docs/progress/PHASE5_2026-10-22.md`](docs/progress/PHASE5_2026-10-22.md) | Phase 5 契约对齐落地说明（类型映射表与 JSON 契约变更） |
| [`docs/architecture/`](docs/architecture/) | 终端、浏览器等基座架构文档 |

## 许可与归属

- PenAi 基于 **ZorvAI**（Apache-2.0）改造，**NOTICE** 完整保留上游与全部第三方归属清单；本项目同样以 Apache-2.0 发布。
- 领域资产来源 **novelIDE**（MIT）：仅参考其**信息架构与交互范式**，**未移植其 Flutter 代码**；若后续引入代码，须按 MIT 要求保留版权声明。
- 第三方组件（GeckoView / MPN-2.0 等）各自遵循其许可证，见 [`NOTICE`](./NOTICE) 与 [`LICENSE`](./LICENSE)。

## 参与贡献

Issue 与 PR 欢迎。改动请遵守：本地不提交二进制产物与预编译原生库（`fdroid` 风味须保持「仓库内无预编译二进制」）、不提交签名凭证（`keystore.properties` / `*.jks` / `*.p12` 一律不入库）、提交信息采用 `[Phase N] 模块: 描述` 格式。

## 关键词

网文写作 AI · AI 写小说 · Android AI Agent · 端侧大模型 · 章节工作流 · 人物关系图谱 · 伏笔管理 · 番茄小说创作 · Jetpack Compose · 本地 LLM
