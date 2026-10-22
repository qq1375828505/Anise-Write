# Anise Write 软件改造计划书

## ——基于 ZorvAI 基座的网文写作 AI Agent 改造方案

## 1. 项目概述

### 1.1 项目背景

网文创作是典型的高强度长文本生产活动：一部长篇作品涉及数十万至数百万字的正文、成体系的世界观设定、密集的人物关系与伏笔网络，且对"爽点密度""节奏控制""去 AI 味"等流派技法有专业化要求。通用 AI 对话工具无法承载这一工作流，而纯人工创作的认知负荷极高。

本项目选定 **ZorvAI**（开源安卓端侧 AI Agent，包名 `com.ai.assistance.quro`，当前版本 1.1.7）作为基座，通过领域化改造，构建一款专注于小说创作的 AI Agent 软件 **Anise Write**。改造完成后，Anise Write 需同时满足两类用户场景：面向读者的"能写"（续写、润色、精修、风格化改写）与面向作者的"能管"（作品结构、资料库、伏笔跟踪、一致性审查）。

### 1.2 产品定位与目标

**产品定位**：端侧优先的网文创作智能体——以 Agent 自主执行为核心，以网文领域知识为约束，以本地数据主权为底线。

**核心目标**：

1. 在 ZorvAI 既有 Agent 内核之上构建网文写作工具域，覆盖作品、卷章、八类资料、伏笔、大纲的完整生命周期管理；
2. 移植成熟网文创作资产（流派预设、子 Agent 提示词、审查规则集），使 AI 输出符合网文工业标准；
3. 建立面向长篇创作的一致性保障机制（上下文预算管理、记忆分型、前文检索）；
4. 修复基座已确认的影响垂直场景的缺陷（详见第 7 章，共 7 项，均经代码级核实）；
5. 保持基座可升级性，改造以最小侵入为原则，避免与上游产生不可合并的分叉。

**非目标**：不做通用设备控制、不做 IM 机器人平台、不做多角色集群编排（基座集群整体移除，不做软裁剪）、不自建导入导出管线（数据依托基座本地工作区）、不追求全平台（本期仅 Android）。

### 1.3 改造对象与范围

| 项 | 内容 |
| --- | --- |
| 基座 | ZorvAI v1.1.7（Kotlin 2.3 + Jetpack Compose，Apache-2.0） |
| 领域资产来源一 | novelIDE（Flutter 网文写作 IDE，MIT，50 工具 + 6 子 Agent + 25 流派预设 + 双轨记忆） |
| 领域资产来源二 | AIGC-NetNov（Operit 分支的网文化改造尝试，20 张数据表 + 66 工具 + 107 桥接方法的设计资产） |
| 交付物 | Anise Write 安卓应用（仓库 `qq1375828505/Anise-Write`，应用名 Anise Write） |
| 基座缺陷输入 | 使用者通过 GitHub Issues #15–#21 提交的 7 项问题（全部纳入第 7 章） |

### 1.4 术语定义

| 术语 | 定义 |
| --- | --- |
| QuroTool | ZorvAI 工具接口，四要素：name / description / parametersJson / run |
| coreNames | ZorvAI 工具默认下发白名单（`QuroTool.kt:143`），未入列的工具模型不可见 |
| ALWAYS_ON | ZorvAI 工具常驻集（`QuroToolRouter.kt:59`），渐进式披露模式下免检索直接下发 |
| suite | ZorvAI 技能分组机制，技能页按套件折叠，可整体默认启用 |
| 交付闸门 | LongHorizonOrchestrator 对产物原文做可交付性判定、不可交付则带记忆重规划的机制 |
| ToolPkg | Operit/AIGC-NetNov 体系的插件包机制（ZIP + manifest），本项目仅作设计参考 |

## 2. 基座调研结论（ZorvAI）

### 2.1 基座概况

ZorvAI 是运行于 Android 8.0+（minSdk 26）的端侧 AI Agent，技术栈为 Kotlin 2.3 + Jetpack Compose 1.10.2（Material3），AGP 8.13 / compileSdk 36。工程为多模块 Gradle 项目（rootProject 名 "Quro AI"），核心模块包括 `:app`（宿主，857 个源文件）、`:mnn` 与 `:llama`（离线推理引擎）、`:terminal-core`（proot + Ubuntu 24.04 终端）、`:genuiagent-sdk`（530+ 组件的生成式界面 SDK）、`:miniapp-sdk`、`:kaleidobox`、6 个 `:cluster-*` 模块（多角色集群）、插件框架系列与 ACI 跨应用协议系列。

构建约束（改造全程必须遵守）：NDK r27、ABI 仅 arm64-v8a、Maven 仓库走阿里云镜像、release 签名仅允许经 `scripts/sign.sh` 且禁止接触私钥（AGENTS.md 硬红线）、`requireSameSignature` 不得弱化。

### 2.2 Agent 内核能力（可直接复用）

| 能力 | 实现位置 | 对 Anise Write 的价值 |
| --- | --- | --- |
| ReAct 主循环 + 任务级闭环 | `core/QuroAssistant.kt`（1588 行）`reactPass()` + `LongHorizonOrchestrator.runTask` | 写作任务（读前文→产出→自检→重写）的调度骨架现成 |
| 交付闸门 | `core/agent/orchestration/`：`DefaultTaskPlanner` / `HeuristicDeliverabilityJudge` / `FreePlanComposer` | 章节稿"不可交付则带原因重规划"，直接提升成稿率 |
| 只读并发 / 副作用串行 | `QuroToolEngine`（`readOnly` 双条件白名单） | 多资料并行读取不牺牲写序 |
| 失败闭环回喂 | `ClosedLoopExecutor` + `QuroToolFeedback.compose` | 工具失败可自愈重试 |
| 检查点恢复 | `QuroRunCheckpoint` 每轮落盘 | 长章节生成中断可恢复 |
| 死循环防护 | 签名滑动窗口（LOOP_WINDOW=16） | 防写作循环空转 |

**结论：Agent 内核无需任何改造， Anise Write 的全部工作量集中在领域层。**

### 2.3 工具系统与扩展机制

工具接口定义（`core/tools/QuroTool.kt:31-55`）：

```kotlin
interface QuroTool {
    val name: String
    val description: String
    val parametersJson: String          // JSON-Schema
    fun run(context: Context, arguments: String): String
    val requiredPermissions: List<String> get() = emptyList()
    val readOnly: Boolean get() = false
}
```

**新增工具的完整注册链路（七步法，以 `QuroCalculatorTool` 为样本）**：

1. 实现 `QuroTool` 子类（`run` 同步返回字符串）；
2. 在 `buildQuroRegistry()`（`QuroBuiltInTools.kt:183`）注册一行；
3. 工具名加入 `coreNames`（`QuroTool.kt:143`）；
4. 工具名加入 `QuroToolRouter.ALWAYS_ON`（`QuroToolRouter.kt:59`，约 154 个）；
5. （建议）`ToolCapabilityDirectory` 补 `ToolInfo`（useCases/examples/tips），提升意图检索召回；
6. （建议）`QuroToolUsageHints.TOOL_USAGE_HINTS` 补口语映射，注入系统提示词；
7. 按 `app/src/test/` 现成范式补 JVM 单测。

> ⚠️ 第 3、4 步为隐性硬约束：在默认 PROGRESSIVE 配置下，未入 `coreNames` 或 `ALWAYS_ON` 的工具模型永远不可见——这是基座仓库内大量"功能完好但模型调不到"故障的根因，Anise Write 工具域必须双写。

现有 248 个工具注册（`QuroBuiltInTools.kt` 实测 248 处 `register(`），其中 203 个进入 `coreNames` 默认下发白名单、154 个进入 `ALWAYS_ON` 常驻集；按能力域组织（基础/设备、应用管理、通信、文件、联网、代码执行、终端、特权控屏、记忆、知识库、文档生成、UI 组件、生成式界面、多媒体、构建台、CMS/ACI/MCP/插件、集群/工作流、UI 动作、技能工具等）。其中文件读写、联网检索、记忆、知识库、代码执行（QuickJS）、文档生成（AIP 排版）六类与写作场景直接相关，全部保留。

### 2.4 技能、人格与提示词体系

- **技能系统**：内置技能播种自 `assets/skills/zorv/manifest.json`（`core/skill/QuroSkill.kt` 的 `seedBuiltinZorvSkills`），技能可 `alwaysOn` 注入系统提示词，或 `callable` 注册为 `skill__*` 工具（上限 16 个）。`suite` 字段分组，现有唯一默认启用套件为 `design-studio`。增量播种有一次性守卫 key 机制，新增技能套件需新开守卫 key。
- **人格卡**：`core/QuroPersona.kt` 的 `seed()`（:163-193）定义默认人格，持久化于 `filesDir/quro_personas.json`；`roleSetting` 即系统提示词核心。
- **系统提示词组装**：`ui/QuroChatViewModel.kt:1889` `buildSystemPrompt()`，顺序为 `QuroPlatformManifest.SYSTEM`（能力环境基座）→ 语言指令 → 灵魂层 → 真实下发工具集同源生成的工具清单 → alwaysOn 技能段 → 经验库段。新工具注册后自动出现在清单中，无需手写。
- **本地模型分支**：MNN/llama.cpp 走极简提示词（:1903-1943，仅人格核心 + 回复纪律），且只下发设备类小子集（`deviceToolNames`，:564-576）。Anise Write 若主打端侧，写作纪律必须压缩进 persona 核心，且小说工具名需加入该子集。

### 2.5 数据与记忆层

基座 **未使用 Room**（全仓仅 `:cluster-storage` 用 Room + SQLCipher），持久化为自研 JSON 文件 + SharedPreferences + 少量原生 SQLite：

| 数据 | 位置 | 形式 |
| --- | --- | --- |
| 会话/消息 | `core/QuroConversationPersistence.kt` | `filesDir/quro_conversations.json`（启动自愈去重） |
| 人格卡 | `core/QuroPersona.kt` | `filesDir/quro_personas.json` |
| 记忆库 | `core/memory/QuroMemoryStore.kt` | `filesDir/quro_memory.json` |
| 知识库 RAG | `core/knowledge/QuroKnowledgeRag.kt` | 原生 SQLite `rag_chunks` 表 + 文件指纹增量同步 |
| 模型配置 | `core/model/QuroModelConfigRepository.kt` | SharedPreferences |

**四类认知记忆**（`core/memory/QuroMemoryKind.kt`）：语义 / 情节 / 程序 / 工作，各有 TTL 与检索权重策略（情节 30 天降权 0.4、工作 2 小时归零），按内容启发式自动分型。长篇写作场景映射直接：主角设定存语义、章节伏笔存情节、写作流程存程序、当前任务存工作。

**知识库 RAG** 双模式：有 Embedding Key 走远程向量余弦，否则降级为 CJK 二元分词 + 词频余弦（零依赖）。前文稿、设定集可灌入做一致性检索。

### 2.6 推理层

云端多 Provider（含上游拒答回执归一化、四家思考字段互斥编译、工具调用容错解析）与端侧 MNN / llama.cpp 共用 `onToken` 流式接口，离线/在线对上层透明。模型管理：`core/model/QuroLocalModelManager.kt` 扫描 `filesDir/local_models/`，`QuroHuggingFace.kt` 支持 HF/ModelScope 搜索 GGUF（hf-mirror 镜像 + 手机可跑量化过滤）。

### 2.7 许可证分析

ZorvAI 主体代码为 **Apache-2.0**，允许修改、商用与闭源分发，仅需保留 NOTICE 归属。GeckoView（MPL-2.0，file-level copyleft）为可选依赖且当前渲染实际走系统 WebView；端侧 CPython 为 PSF-2.0。**Anise Write 基于该基座改造无许可障碍**，但须完整继承 NOTICE 第三方归属清单，并继续遵守签名红线。

## 3. 领域资产移植分析（novelIDE）

### 3.1 资产清点

novelIDE 为 Flutter 3.8+ / Dart 实现的网文写作 IDE（v2.1.0），业务核心集中于 `lib/data/services`（约 19000 行）。经代码级核查，其数据层对 Flutter 的依赖仅 3 处（`ai_service.dart` 尾部一个 Riverpod Provider、`notification_service.dart`、`voice_service.dart`），**约 95% 业务逻辑与 UI 框架无关**，具备整体平移条件。

### 3.2 可移植资产分级

| 级别 | 资产 | 位置 | 移植形态 |
| --- | --- | --- | --- |
| P0 | 50 个工具定义 + 三段式中文描述文案 | `workspace_agent.dart:193-649`、`agent_tool_executors.dart` | 转 Kotlin `QuroTool` 实现；描述文案决定调度质量，原样保留 |
| P0 | 25 个番茄流派预设 | `data/presets/tomato_presets_data.dart`（374 行纯数据） | 转 Kotlin 常量 / JSON，零依赖 |
| P0 | 6 个子 Agent 提示词 + 五级模糊匹配 + 3 轮迭代收敛 | `app_providers.dart:60-157`、`agent_tool_executors.dart:27-135` | prompt 照搬；匹配逻辑转 Kotlin |
| P0 | XML 降级调用（协议层 400/422 去 tools 重试 + 正则解析层 + 循环层回传） | `chat/xml_tool_call_parser.dart`、`ai_service.dart:366-383` | 正则改 Kotlin Regex；端侧小模型刚需 |
| P1 | 爽点密度 / 水文检测 / 爆款标题 / 一键精修 8 维度 / 全文审查 4 类的规则与截断策略 | `ai_drawer.dart:156-227`、`polish_engine_page.dart:56`、`full_text_review_page.dart:96-102` | prompt 抽为 Kotlin 常量；截断策略（20 章 × 2000 字、15000 字分析上限）照搬 |
| P1 | 8 类资料数据模型 | `material_models.dart`（605 行） | 直译 Kotlin data class（剥离 PlotHook 的 Colors 依赖） |
| P1 | 小说记忆双轨：10 节文本记忆（5 分钟 TTL 缓存）+ SQLite 记忆图谱（5 表 + RRF 混合检索，K=60） | `novel_memory.dart`、`database_helper.dart:208-284`、`memory_repository.dart` | 文本轨逻辑直译；图谱轨可映射基座四类记忆或独立 JSON |
| P1 | AI 资料自动分析（6 类 JSON 提取 prompt + 名称去重合并） | `ai_analysis_service.dart` | prompt 照搬，"读一遍书自动建档" |
| P2 | 纯 Dart 校对引擎（60+ 错别字词库 + 标点规则） | `proofread_service.dart` | 词库转 Kotlin List，零 AI 依赖可离线运行 |
| — | EPUB / DOCX / novelpack 导出 | `epub_export_service.dart`、`docx_export_service.dart`、`local_file_datasource.dart:114-158` | **不做**（用户决策）：基座已绑定本地工作区，正文与资料以文件形式直接可访问、可转换格式，无需自建导入导出管线 |
| P2 | 记忆三阶段管线 + AI 知识图谱分析 | `memory/memory_pipeline.dart`、`memory_analyzer.dart` | 话题分类关键词表 + JSON 抽取 prompt 可搬 |
| P2 | 工作流引擎（4 个预置：章节检查 3 步 / 全文审查 5 步 / 大纲刷新 3 步 / 角色审查 2 步） | `workflow_engine.dart` | 步骤=工具调用的编排模型可扩展 |
| P2 | 任务→模型路由 | `model_router.dart` | 策略简单，作参考 |

### 3.3 移植映射方法

1. **Prompt 资产抽取**：所有内嵌于 UI 文件与 Provider 的 prompt 统一抽取至 `core/novel/prompts/` 常量文件，消除"改文案要动 UI"的耦合；
2. **数据结构映射**：Dart 模型 → Kotlin data class + `org.json` 序列化（沿用基座 JSON 持久化风格），或评估 Room 化（见 6.4）；
3. **调度逻辑映射**：五级模糊匹配、3 轮收敛判定（两轮输出长度差 < 5%）转 Kotlin 纯函数，配 JVM 单测；
4. **网络层不移植**：novelIDE 的 `ai_service.dart`（Dio）不搬，Anise Write 直接用基座 `QuroLlmClient` 云端 + `QuroLocalEngineNative` 端侧双通道；仅借鉴其"多认证头兼容、双协议、条件采样参数"的请求组装策略；
5. **UI 不移植**：Flutter 页面全部不搬，仅参考信息架构（作品区/资料区/统计三类；树形目录按用户决策砍掉，改为扁平章节列表）；novelIDE 的 WebView 富文本编辑器资产不搬代码，但其交互范式作为 Anise Write 独立编辑器模块的设计基线（见 6.7）；

### 3.4 不宜移植部分

- Flutter Widget 树、Riverpod Provider、主题皮肤（8 套）、富文本 WebView 编辑器资产（不直接复用代码，其交互范式作为 Anise Write 编辑器模块的设计基线，见 6.7）；
- `humanize_text` 的"执行器只校验、AI 按工具描述改写"模式需保留其设计思想（规则即 prompt），但实现重写；
- 语音通话、系统通知等系统能力绑定部分。

## 4. 前车之鉴：AIGC-NetNov 改造实践分析

### 4.1 项目概况

AIGC-NetNov 是 AAswordman/Operit v1.10.1 的深度改造分支（包名 `com.ai.assistance.novelide`，版本 1.11.0+6，LGPLv3），目标同样是将通用 AI Agent 基座改造为网文写作 IDE。该仓库沉淀了一套 20 篇的改造规划文档与大量领域资产，是**同题实践的完整样本**。

### 4.2 已验证有效的架构决策

1. **插件包承载业务、宿主最小改造**：网文业务以独立插件包（66 个 `novelide:` 前缀工具 + 7 个子 Agent）承载，原生层只加导航入口、数据表、桥接 API。该路线使上游可同步、裁剪可回滚，Anise Write 应采用同构路线（ZorvAI 侧对应"工具域 + 技能套件"，而非插件 APK）；
2. **三层数据流边界**：UI 层无数据访问权，全部经桥接 → Repository → 存储。该安全边界正确，Anise Write 照搬；
3. **领域建模资产完整**：20 张表（作品/卷/章节/8 类资料/番茄预设/技能/关系图/事件）、25 个番茄预设种子（含完整 systemPrompt）、7 个子 Agent 调度（3 轮推敲 + 相似度 > 90% 收敛提前退出）、"章节状态机 unwritten→draft→polishing→completed→exported"——**与基座无关，可直接平移**；
4. **上下文管理显式化**：章节/角色/设定三类上下文选择器 + 4000-8000 字符预算 + 结构化模板，是长篇连贯性保障，Anise Write 第一天就要设计；
5. **AI 辅助工程化配方**：CI 构建日志回流给 AI、分阶段验收（每阶段带禁止事项）、commit 规范、多子代理分工（planner / reviewer / build-resolver）、预编译大资源走 Release Assets 不进 git。

### 4.3 失败模式分析（Anise Write 必须规避）

| 失败模式 | 事实 | Anise Write 对策 |
| --- | --- | --- |
| 最后一公里未闭环 | HTML 调 `window.NativeBridge.*` 而实际注入名为 `NovelNativeBridge`；`Tools.callNative` 的 novel 方法未注册，页面数据链路断裂，自称 95% 实为"代码写完" | **第一里程碑必须是端到端最小闭环**（入口→页面→建作品→AI 写一章→保存），链路通了才算完成 |
| 基座运行时被打桩 | 17 个上游文件被 stub 以通过 CI，ToolPkg 运行时残缺，插件能否加载无法验证 | 与基座运行时的兼容性验证先于功能开发；不为过编译而桩改基座 |
| 双轨 UI 债务 | 同时维护 HTML 设计稿与原生 DSL 页面两套界面，且存在 `UI/` 与 `examples/novelide/` 两个同源工程 | 开跑前冻结唯一 UI 技术路线 |
| 命名空间晚期更改 | `com.ai.assistance.operit`→`novelide` 的全仓 R 类污染 | applicationId / namespace / App 名第一天定死（Anise Write 已在 Stage 0 确定） |
| 规划反复翻烙饼 | 数据库版本 v21→v22→v20→v21、存储方案文件系统→Room、Screen order 三改 | 冻结唯一决策文档，所有分册引用，不允许分册自相矛盾 |
| 半裁剪 | gradle 摘了 NDK 模块但 web-chat 仍在构建；NavItem 写了没接线 | 裁剪决策落到构建脚本与注册处，可验证 |
| 安全设计纸面化 | WebView `allowFileAccess=true`、API Key 明文、无证书锁定 | 安全配置与功能同轴上线 |

## 5. 总体改造方案

### 5.1 改造原则

1. **基座最小侵入**：所有领域能力以"新工具 + 新技能套件 + 新页面 + 新人格"挂载，不改 Agent 内核、不改工具引擎、不动签名体系；
2. **端到端闭环先行**：任何阶段以"用户可走完一条完整创作路径"为完成定义；
3. **单一决策文档**：存储方案、包名版本、UI 路线、工具命名在本文档冻结，实现期不允许分册自相矛盾；
4. **软裁剪优先**：通用功能裁剪以"不注册工具 + 移除设置入口"为主，模块级硬裁剪仅在有包体/合规确需时执行；
5. **资产平移而非重造**：novelIDE 与 AIGC-NetNov 已验证的 prompt、数据模型、规则集直接平移，Anise Write 不重复造轮子。

### 5.2 目标架构

```
┌────────────────────────────────────────────────────────┐
│ Anise Write 应用层（com.ai.assistance.quro，重命名/换皮后交付）  │
│  ChatScreen（主对话，继承基座）                          │
│    └─ NovelStudioScreen（小说工作台：章节/编辑器/资料库/   │
│       关系图/统计，数据落工作区文件直管）                   │
├────────────────────────────────────────────────────────┤
│ 网文领域层（新增，core/novel/）                          │
│  ├─ 工具域：novel_* 工具族（30 个，QuroTool 实现）         │
│  ├─ 技能套件：novel-writing（写作规约）                    │
│  │            + tomato-genre（25 流派预设，用户可改）      │
│  ├─ 子 Agent：6 个（大纲/角色/爽点/水文/标题/去AI味）       │
│  │   形态=callable 技能，经 novel_delegate_subagent 调度， │
│  │   零写权限，写操作一律回流主 AI                        │
│  └─ 人格与提示词：网文创作助手（一张默认人格）             │
├────────────────────────────────────────────────────────┤
│ 独立模块（可插拔，:novel-editor）                         │
│  富文本编辑器（WebView + 桥接契约，可摘除降级纯文本）       │
├────────────────────────────────────────────────────────┤
│ 基座能力层（复用，零改动）                                │
│  ReAct + 长程编排 + 交付闸门 │ 工具引擎 │ 记忆四型 │ RAG   │
│  MNN/llama.cpp 端侧推理 │ 云端多 Provider │ 技能系统      │
├────────────────────────────────────────────────────────┤
│ 数据层（filesDir JSON + 工作区文件）                      │
│  作品/卷/章节 │ 8 类资料 │ 伏笔 │ 关系图三表 │ 番茄预设     │
└────────────────────────────────────────────────────────┘
```

### 5.3 功能矩阵

| 处置 | 功能 |
| --- | --- |
| **保留（基座）** | Agent 内核、工具引擎、交付闸门、记忆四型、知识库 RAG（保留前先拆 2 个被污染索引）、MNN/llama.cpp 推理、云端多 Provider、技能系统、人格系统、AIP 文档排版、代码执行（文本处理可用）、联网检索、语音（章节朗读场景）、CodeCanvas（封面图） |
| **新增（网文域）** | 小说工具族（30 个）、novel-writing + tomato-genre 双技能套件、6 个写作子 Agent（callable 技能形态，主 AI 统一协调）、NovelStudioScreen、网文人格卡、番茄预设库（25 个）、资料自动建档、一致性审查、人物关系图系统、独立富文本编辑器模块 |
| **软裁剪（不注册）** | IM 机器人（core/bot/）、设备控制（core/shizuku、core/privilege、core/permissions、core/adb）、APK 插件框架、ACI 跨应用、CMS、浏览器工具、KaleidoBox/小程序、GenUI 子应用 |
| **硬裁剪（整模块/整链路移除）** | 多角色集群（6 个 `:cluster-*` 模块 + 注册链路，见 5.4）、MCP 外部服务器（DroidMcp 内嵌于 `QuroTool.kt`，动手术式摘除，见 5.4） |
| **移除设置入口** | 上述子系统对应的设置页与导航项 |

### 5.4 基座裁剪策略

裁剪按耦合度分级执行，全部可回滚：

| 子系统 | 耦合度 | 裁剪方式 |
| --- | --- | --- |
| IM 通道（飞书/QQ/微信） | 极低（app 外引用仅 4 处） | 删 `core/bot/` + 4 处引用 + 设置项 |
| 多角色集群 | 中（6 个 Gradle 模块，注册链路深） | **必砍**（用户决策，非软裁剪）：`settings.gradle` 摘 include + 删 `QuroBuiltInTools.kt:612` 注册行 + 摘 `QuroChatViewModel` 集群 VM 注册与页面路由；集群砍掉后子 Agent 降级为 callable 技能（见 6.6） |
| ACI / 插件框架 / CMS / KaleidoBox | 低（纯挂载） | 不注册工具 + 摘设置入口 |
| 设备控制（L1–L4） | 低-中（权限网关与工具执行集成） | 不注册工具 + 注释无障碍服务声明 |
| MCP 外部服务器 | 中（DroidMcp 客户端内嵌于 `QuroTool.kt:408-409`，非独立目录） | 动手术：摘除内嵌客户端与工具注册 + 禁用设置入口；**不得**按"整目录删"处理 |
| 终端 / Linux 沙箱 | 中（FTP jar 已致 packaging 冲突） | 工具不注册；模块级摘除需验证 packaging |
| 语音 | 中 | 保留（朗读场景有用），TTS 供应商按需裁剪 |

> RAG 保留前提：知识库索引需先拆分 2 个被污染索引（混入非目标语料），拆分校验后再灌入前文稿与设定集，避免检索召回被污染。

## 6. 详细设计

### 6.1 网文工具域设计

**数据层**：新建 `core/novel/`（数据模型 + JSON 持久化于 `filesDir/novel/`），不引入 Room（与基座风格一致，避免双持久化范式）。

**工具族清单**（共 30 个，全部纯本地、无危险权限，按七步法注册，coreNames/ALWAYS_ON 双写）：

| 分类 | 工具 | 说明 |
| --- | --- | --- |
| 作品/结构 | novel_list_works, novel_create_work, novel_update_work, novel_get_outline, novel_save_outline | 作品 CRUD + 三级大纲（卷/章/节） |
| 章节 | novel_get_chapter, novel_write_chapter, novel_delete_chapter, novel_chapter_stats, novel_chapter_status | 正文读写删 + 字数统计 + 章节状态机（unwritten→draft→polishing→completed） |
| 资料（8 类） | novel_materials_get, novel_materials_add, novel_materials_update, novel_materials_delete | 角色/设定/地点/势力/道具/伏笔/参考/待办统一入口，type 参数分流 |
| 伏笔 | novel_hooks_status | 已埋/已回收/已废弃 + 闲置章数统计 |
| 关系图 | novel_relationships, novel_relationship_graph | 人物关系 CRUD（类型/强度 1-5/自定义颜色）+ 关系图渲染数据生成 |
| 分析 | novel_consistency_check, novel_shuangdian_report, novel_water_report, novel_title_candidates | 映射 novelIDE 四类审查规则 |
| 精修 | novel_polish | 8 维度精修入口 |
| 去AI味 | novel_humanize | 规则即 prompt 设计 |
| 检索 | novel_query_previous | 前文检索（人物上次出场/道具/时间线），控制上下文成本 |
| 上下文 | novel_build_context | 按预算（4000-8000 字符）组装作品上下文，复用 AIGC-NetNov context_builder 设计 |
| 流派 | novel_list_genres, novel_apply_genre | 25 个番茄流派预设的查询与应用 |
| 记忆 | novel_memory_save, novel_memory_query | 小说双轨记忆：文本轨（近期章节快照）+ 图谱轨（实体关系） |
| 调度 | novel_delegate_subagent | 6 个子 Agent 模糊匹配调度（五级匹配 + 3 轮收敛，见 6.6） |

**工具描述规范**：沿用 novelIDE 三段式（功能说明 + 何时用/触发词 + 返回内容），中文撰写；只读工具声明 `readOnly = true` 进入并发白名单。

### 6.2 技能包设计

新建两个技能套件，manifest.json 登记，种子播种复用基座 `seedBuiltinZorvSkills` 机制（**增量播种需新开一次性守卫 key**，基座既有坑）：

| 套件 | 内容 | 默认态 |
| --- | --- | --- |
| `novel-writing` | 写作规约：黄金三章结构、爽点节奏控制、伏笔管理规范、长篇一致性守则、去 AI 味文风规范 | `alwaysOn=false`，触发词命中注入，避免挤占提示词预算 |
| `tomato-genre` | 25 个番茄流派预设各注册为一项技能（systemPrompt 全文平移），用户选用即注入 | 默认全部关闭，按作品选用 |

**用户可改性**（载体结论）：技能以文本形式持久化（SharedPreferences JSON + assets 播种），用户在技能页可查看、修改、开关、导出全部提示词——流派预设与写作规约对用户完全透明可改，不锁定、不混淆。流派注入链路为用户显式动作（选流派 → 技能启用 → 注入系统提示词），不写死人格。

### 6.3 人格与系统提示词设计

1. `QuroPersona.seed()` 默认人格改为「网文创作助手」，`roleSetting` 承载创作纪律（先细纲后正文、每章爽点下限、禁用词表）；
2. `QuroPlatformManifest.SYSTEM` 能力环境段补小说工作流说明；
3. 本地模型分支：将创作纪律压缩进 persona 核心（本地提示词预算极紧），小说工具名加入 `deviceToolNames` 子集；
4. 流派预设注入链路：用户选定流派 → 对应 `tomato-genre` 技能启用 → 预设 systemPrompt 作为会话级约束注入（不写死人格，全库仅一张默认人格）。

### 6.4 数据层设计

**Phase 1（JSON 文件 + 工作区）**：`filesDir/novel/{works,chapters,materials,outline,memory}`，模型直译 novelIDE 的 8 类资料 + AIGC-NetNov 的实体设计（作品/卷/章节/8 类资料/番茄预设/技能），正文存 `.md` 文件、元数据存 JSON。**人物关系图三表**（平移 AIGC-NetNov 设计）：关系表（关系类型、强度 1-5、自定义颜色）、事件表（章节锚点）、参与者表（人物-事件多对多桥接）；novelIDE 侧 `character_relationship.dart` 的关系类型枚举一并直译。

**Phase 2（评估 Room 化）**：当资料规模与并发查询出现瓶颈时引入 Room（基座已声明 Room 2.8.4 依赖），迁移脚本按 AIGC-NetNov 的 Migration 模式（CREATE TABLE IF NOT EXISTS + 版本递进 + 禁止删用户数据）。

**数据主权（导入导出不做，用户决策）**：不自建 EPUB/DOCX/novelpack 导入导出管线——基座已绑定本地工作区，全部数据以文件形式落在用户可见目录，可直接编辑与格式转换；卸载重装不丢稿由工作区目录绑定保证，AIGC-NetNov NovelBackupManager 式的自建备份方案不再采用。

### 6.5 记忆与上下文管理设计

| 记忆类型 | 映射内容 | 示例 |
| --- | --- | --- |
| 语义 | 世界观铁律、主角核心设定 | "主角不能死""修炼体系上限" |
| 情节 | 章节事件、伏笔埋收 | "第 87 章埋的刀第 210 章收" |
| 程序 | 写作流程规范 | "先细纲后正文" |
| 工作 | 当前任务态 | "正在写第三卷第十二章" |

上下文组装（`novel_build_context`）：任务相关的前文章节（按检索相关性 top-N）× 2000 字截断 + 相关角色/设定卡一行摘要 + 未回收伏笔清单，总预算 4000-8000 字符，超预算按重要度降序淘汰。novelIDE 的 10 节文本记忆作为 AI 上下文快照的补充形态保留（TTL 缓存）。

### 6.6 子 Agent 体系设计

6 个子 Agent（大纲生成器 / 角色生成器 / 爽点检查 / 水文检测 / 爆款标题 / 去AI味），prompt 全文移植自 novelIDE（已经真实场景打磨）。多角色集群砍掉后，子 Agent 不再依赖基座 `spawn_subagent` 集群机制，**降级为 callable 技能形态**：每个子 Agent 的 systemPrompt 注册为一项技能，经 `novel_delegate_subagent` 工具统一调度，全部执行发生在主 AI 的 ReAct 循环内（无独立 subStore，天然规避 Issue #15 的上下文压缩缺陷）。

**职责边界矩阵**（硬约束：每个 Agent 只干一件事，越权与冲突一律上交主 AI）：

| 子 Agent | 负责（只读 + 产出建议） | 明确不做 |
| --- | --- | --- |
| 大纲生成器 | 读大纲/章节摘要 → 产出黄金三章、分卷建议、章节概略 | 不写正文、不修改已有大纲文件 |
| 角色生成器 | 读作品类型/主角设定 → 产出角色卡草稿 | 不直接写入资料库 |
| 爽点检查 | 读单章正文 → 评分数 + 爽点列表 + 优化建议 | 不改正文、不评价文笔（属精修域） |
| 水文检测 | 读单章正文 → 水文率 + 冗余段落定位 + 精简建议 | 不改正文 |
| 爆款标题 | 读章节摘要 → 10 个候选标题 + 点击理由 | 不修改作品名/章节名 |
| 去AI味 | 读指定文本 → 人性化改写建议稿 | 不直接落盘，改写稿以建议形式返回 |

**冲突仲裁规则**：

1. 爽点检查与水文检测都读正文、都出"建议"——两者只读不写、输出均为建议态，不构成冲突；用户同时要求时，主 AI 并行调度后汇总为一份报告；
2. 全部子 Agent **零写权限**：涉及落库、改稿、改状态的操作只能由主 AI 执行（经 `novel_*` 写工具），子 Agent 输出一律作为主 AI 的决策输入；
3. **主 AI 是唯一编排者**：负责派发（五级模糊匹配：精确 ID→中文名→互相包含→关键词映射→描述包含）、汇总（多 Agent 结果合并去重）、裁决（建议冲突时以用户明示要求优先）、落库；
4. 执行最多 3 轮迭代，第 2 轮起 prompt 要求"检查并优化上一轮结果"，两轮输出长度差 < 5% 判定收敛提前退出；子 Agent 失败返回可用清单引导重试。

### 6.7 界面设计

**视觉风格：iOS 对齐范式**（用户决策：去陶土色、去中式文艺范；2026-10-22 定为 iOS 分组列表语汇，取代此前的"现代文学出版风"）：

| 令牌 | 取值 | 用途 |
| --- | --- | --- |
| 页面底 | `#F2F2F7` 亮 / `#000000` 暗（iOS systemGroupedBackground） | 页面背景 |
| 卡片 | `#FFFFFF` 亮 / `#1C1C1E` 暗（中性分组卡片，不着主色） | 分组容器 |
| 文字 | `#1C1C1E` 亮（对比度 15.6:1）/ `#F2F2F7` 暗（14.2:1） | 正文 |
| 强调色 | 去饱和 iOS 蓝 `#528AD2` 亮 / `#6A94CE` 暗 | 主按钮、选中态、链接、分组标题 |
| 分隔 | 0.5px `#D1D1D6` 亮 / `#38383A` 暗，**自卡片左缘内缩 16dp** | 行分隔 |
| 圆角 | 卡片 12dp / 行首图标 29dp 见方 + 6.5dp 圆角 / 底部 Sheet 10dp | 容器 |
| 节奏 | 屏幕内缩 16dp、行高 44dp、组内行距 8dp、组间距 24dp、Tab 栏 49dp | 布局 |

整体气质参照 iOS 系统应用（分组列表 + inset 分隔线 + 大标题 + 彩色圆角图标）：安静、克制、信息密度高，适合长时间操作。

**规范与实现**：完整规范见 `docs/design/AniseWrite_UI_SPEC.md`，令牌见 `docs/design/design_tokens.json`，代码侧收在单一真相源 `ui/novel/NovelDesignTokens.kt`（`NovelDesign` / `NovelPalette` / `NovelType`）——**所有界面只允许引用，禁止内联硬编码数值**。

方法论参考 OpenMinis(GPL-3.0) 的 iOS 对齐范式（逐像素测量、单一真相源、对比度实测入注释），**只取方法与数值，实现独立编写，未复制其源码或素材**（详见 NOTICE "DESIGN METHODOLOGY REFERENCE"）。

**NovelStudioScreen**（新建文件，最小侵入接线）：

- 四个 Tab：**章节**（扁平列表，无树形目录——按用户决策砍掉大纲树/资料树，作品结构用卷章分组列表呈现）、**资料库**（8 类卡片）、**关系图**（见 6.8）、**统计**（字数/打卡/爽点密度趋势）；点击章节进入全屏富文本编辑器（独立模块，见下）；
- 接线方式：ChatScreen 加 `showNovelStudio` 布尔开关 + 入口按钮（模式 A，仿 `QuroKnowledgeScreen` 521 行范式）；
- AI 可自开界面：`QuroToolsUiActions.kt` 的 `UI_ACTIONS` 增加 `ui_open_novel`（模式 B），用户点击与 AI 主动打开同路。

**富文本编辑器模块（独立可插拔，用户决策保留）**：

- 形态：独立模块 `:novel-editor`（WebView 承载富文本编辑），对 NovelStudioScreen 暴露统一接口，可单独摘除；
- 桥接契约（吸取 AIGC-NetNov `NativeBridge` 命名不匹配致最后一公里断裂的教训）：桥接对象名、方法名、参数 schema 在本文档冻结——`load(html)` / `getContent()` / `setContent(html)` / `insertText(text)` / `getSelection()` 五方法 + 内容变更回调；端到端验收标准：编辑器输入一个字，`novel_get_chapter` 能原样读回；
- 降级：模块缺失或桥接未就绪时，编辑器 Tab 自动降级为纯文本/Markdown 编辑（与基座一致），功能不阻塞；
- 交互范式参考 novelIDE WebView 编辑器资产（不移植代码）。

### 6.8 人物关系图系统

**数据**：关系图三表（关系/事件/参与者，见 6.4），关系强度 1-5 级、支持自定义颜色；AI 经 `novel_relationships` 维护数据、`novel_relationship_graph` 取渲染数据，数据与视图分离。

**渲染**：自研 Compose Canvas + 弹簧斥力物理模拟（节点斥力 + 关联弹簧 + 阻尼，约 200-400 行），复用基座 DrawCommand 图元集与 VizIcon 手绘风；关系强度映射边宽、自定义颜色映射边色；支持节点拖拽、缩放、长按查看关系详情；物理模拟收敛判据与单测范式见第 9 章。

**可行性结论**：5-7 人周，中规模低风险——三仓库拼图齐全（novelIDE 交互范式 + AIGC-NetNov 数据表 + ZorvAI 绘图家底）。

### 6.9 模型配置与推理策略

- 云端：沿用基座多 Provider + 思考协议编译；**扩展 `detectFamily` 兼容国产 Anthropic 兼容端点**（见 7.6，#21）；
- 端侧：MNN/llama.cpp GGUF，模型经 `QuroLocalModelManager` 导入；端侧提示词按 6.3 压缩；
- 任务路由：按 novelIDE `model_router` 策略，重任务（全文审查）走云端、轻任务（润色/校对）可端侧；
- 工具调用降级：移植 novelIDE XML 降级三层机制，保障不支持的 function calling 的模型可用。

## 7. 基座缺陷修复与优化

本章收录使用者向 ZorvAI 官方仓库反馈的全部 7 项 issue（#15–#21，均处于 open 状态）。所有条目经对 v1.1.7 源码的代码级核实，按"必须修复 / 建议修复 / 复验后处置"分级。修复随 Anise Write 分支同步进行，**修复 Patch 同步向上游提交，履行开源回馈义务**。

### 7.1 修复总览与优先级

| # | 问题 | 核实结论 | 优先级 | 处置 |
| --- | --- | --- | --- | --- |
| 15 | 子智能体上下文不参与主循环压缩 | 属实（`QuroAssistant.kt:1394` subStore 独立存储） | P0 | 修复 |
| 16 | 长耗时工具缺乏进度心跳 | 属实 | P1 | 优化 |
| 17 | run_code 大文件挂死 + 文件读取缺口 + 技能库无 AI 接口 | 属实 | P0 | 修复 |
| 18 | 技能列表无中文说明 | 属实 | P1 | 优化 |
| 19/20 | 甘特图卡片渲染崩溃 | v1.1.7 第 82 行已空安全，疑似已修 | P2 | 真机复验后处置 |
| 21 | 深度思考开关对 Anthropic 兼容端点不生效 | 属实（`QuroReasoningControl.kt:173-196`） | P0 | 修复 |

### 7.2 Issue #15：子智能体上下文压缩缺失

**现象**：`spawn_subagent` 执行"读多份资料 + 产出报告"任务时频繁返回"子智能体未在限定轮数内产出结果"或 90 秒超时；实测 4 次派发中 2 次超轮、1 次仅返回中间状态、1 次超时。

**根因**：子代理运行于独立 `subStore`（`QuroAssistant.kt:1394`），其工具结果不经主循环的 `toLlmMessages` 前压缩（compactToolResults / truncateToolResult / pruneOrphanToolMessages），上下文只增不减；叠加中英混排下 `estTokens = length/4` 粗估偏差，长任务必然超限。

**修复方案**：

1. 子代理每轮 `engine.execute` 前接入与主循环同款的压缩管线；
2. 为子代理设置显式 token 预算（复用 `SUBAGENT_CONTEXT_WINDOW_TOKENS`），临近预算触发自动压缩钩子；
3. 子代理失败返回附带上文占用估算，便于调用方定位；
4. 补 JVM 单测：构造超预算子代理会话，断言触发压缩而非超轮失败。

**影响面**：`core/QuroAssistant.kt` 子代理段（约 :1388-1500）。**Anise Write 相关性：高**——"读前文稿 + 产出章节/报告"是写作 Agent 的核心调用形态。

### 7.3 Issue #16：长耗时任务进度不可观测

**现象**：工具执行超数秒后对话框仅静态"正在执行: xxx…"；网络重试无提示；子代理中间状态曾被当作最终结果交付；用户反复催问造成重复调用。

**修复方案**：

1. 工具执行超阈值（10s）向会话回传进度行（"仍在执行，已耗时 Xs / 第 N 次重试"）；
2. 长任务支持分段结果流（run_code 增量输出、子代理中间状态透传，非最终结论不得交付）；
3. 网络类工具自动重试时展示重试过程；
4. 子代理返回非最终结论时打标记，调用方可见状态。

**影响面**：`QuroToolEngine` 执行回调 + 子代理结果判定。**Anise Write 相关性：高**——章节生成属典型长任务。

### 7.4 Issue #17：文件读取链路与技能库工具缺口

**现象与根因**：① `run_code` 读 736KB 本地文件无限挂起，声明的 15s 超时未触发（执行异常无明确错误返回）；② `file_read` 无 offset/maxBytes 却建议分段读取；`read_text_file` 有 maxBytes 无 offset 且两工具根路径语义不一致；③ 宿主技能库（81 个技能）无 AI 可列、可增删的接口，`cluster_skill_install` 安装后无法代启用。

**修复方案**：

1. `run_code` 执行体补超时强绑与异常兜底，大文件读取明确成功或明确拒绝；
2. `file_read` 增加 `offset`/`maxBytes`；`read_text_file` 支持绝对路径或在报错中说明可访问根；两者行为对齐；
3. 新增 `skill_list` / `skill_enable` / `skill_disable` 工具（七步法注册，注意 coreNames + ALWAYS_ON 双写），打通"安装→启用"链路。

**影响面**：`core/tools/` 文件读取族 + 技能系统。**Anise Write 相关性：高**——长篇小说章节文件常在数百 KB 级，网文技能包的启用依赖第 3 项。

### 7.5 Issue #18：技能列表中文化

**现象**：81 个技能中开源来源的技能仅英文 name/description，中文用户无法判断该启用哪个；开关不可逆（81 选 6），选错成本高。

**修复方案**：

1. 技能模型增加 `description_zh` 字段；开源技能安装时生成中文一句话摘要（安装环节一次性处理）；
2. 技能选择界面优先显示中文，无则降级英文；支持中文关键词搜索（复用 `ToolCapabilityDirectory.matchToolsByIntent` 分词）；
3. 与 7.4 第 3 项联动：AI 用中文给出启用裁决清单，用户确认后经 `skill_enable` 代启用。

**影响面**：技能模型 + 技能页 UI。**Anise Write 相关性：高**——novel-writing 套件面向中文作者，此为实现易用性的前置。

### 7.6 Issue #19 / #20：甘特图卡片渲染崩溃（复验项）

**现象**：渲染 gantt 卡片时主线程 `NoSuchElementException`（`QuroChatCardsEx2.kt:82`，Compose 重组路径）。

**核实结论**：v1.1.7 源码该行已为 `val total = (card.total.takeIf { it > 0 } ?: card.tasks.maxOfOrNull { it.start + it.duration } ?: 1).coerceAtLeast(1)`，且存在 `if (card.tasks.isEmpty())` 空数据分支与相应注释，**疑似已在某版本修复**。

**处置**：Anise Write 分支基于 v1.1.7 构建后，按 issue 复现路径（对话框渲染 gantt 卡片）真机复验。若仍现，执行原修复建议（空安全 + 空数据降级为 note 卡 + 三类单测：空数组/缺字段/畸形 JSON）；若已修，补回归单测防回潮，issue 标注关闭。

### 7.7 Issue #21：深度思考开关对 Anthropic 兼容端点不生效

**现象**：开启"深度思考"后选 Agnes 3.0 Flash（Anthropic 兼容 Messages API），输出与关闭时逐字节相同。

**根因**：`QuroReasoningControl.kt` 的 `detectFamily`（:173-196）仅按 baseUrl 关键词判定家族（`anthropic.com` / `api.openai.com` / `dashscope`），其他一律 `Family.NONE`；`apihub.agnes-ai.com` 不匹配任何关键词，NONE 家族返回空 plan，请求体不含任何 thinking 字段。

**修复方案**：

1. `detectFamily` 增加兼容端点识别：baseUrl 含 `agnes-ai` 或 model 名含 `agnes` → 归入 ANTHROPIC 家族（或新增子家族）；
2. 注意参数名差异：Agnes 用 `enable_thinking`，Anthropic 用 `budget_tokens`，请求体组装需分支处理；
3. 通用化：对"协议特征探测"（如端点响应 `anthropic-version` 头）增加兜底识别，避免逐厂商打补丁；
4. 补 `QuroReasoningControlTest` 用例：Agnes 端点 + 开关两态断言请求体差异。

**影响面**：`core/network/QuroReasoningControl.kt`。**Anise Write 相关性：高**——国产 Anthropic 兼容端点是中文网文作者的常用模型来源。

## 8. 实施路线图

### 8.1 阶段划分

| 阶段 | 目标 | 出口验收 | 禁止事项 |
| --- | --- | --- | --- |
| Phase 0 环境与基线 | 仓库改名（applicationId/namespace/App 名 Anise Write）、签名配置、跑通基座干净构建、锁工具链、建 feature/penai 分支、冻结决策文档 | `assembleFullRelease` 产出可安装包 | 未跑通构建不动业务代码 |
| Phase 1 端到端最小闭环 | NovelStudioScreen 空壳 + 入口 + `ui_open_novel` + 作品/章节 JSON 存取 + 手动写一章 | 用户可：打开工作台→建作品→写一章→保存→重进仍在 | 不接 AI、不做资料库 |
| Phase 2 AI 闭环 | 人格替换 + 3 个核心工具（novel_write_chapter / novel_get_chapter / novel_build_context）+ 云端续写通 | 对话框说"续写第三章"→正文落盘→页面可见 | 不上子 Agent |
| Phase 3 工具域全量 | 30 个工具族 + 8 类资料 + 伏笔跟踪 + 前文检索 + 关系图三表数据层 | AI 可完成"读资料→写章节→更新角色卡"全链路 | 不忘 coreNames/ALWAYS_ON 双写 |
| Phase 4 技能与流派 | novel-writing + tomato-genre 双套件 + 25 预设注入链路 + 技能页中文化（7.5） | 用户选流派→AI 输出符合流派规约，用户可改提示词 | 不挤爆本地提示词预算 |
| Phase 5 子 Agent | 6 个子 Agent 技能化 + 模糊匹配 + 3 轮收敛 + 职责边界矩阵落地 | 爽点/水文/标题/去AI味报告可产出，写操作全部经主 AI | 子 Agent 不得绕过主 AI 直写数据 |
| Phase 6 基座修复 | 7.4 / 7.7 修复 + 7.6 复验 + 7.3 / 7.5 优化（7.2 随集群移除在 Anise Write 分支失去触发路径，仅向上游提交 Patch） | 各 issue 复现路径通过 + 单测 | 不桩改基座代码 |
| Phase 7 关系图与编辑器 | 关系图三表 + Canvas 物理渲染 + 独立富文本编辑器模块（桥接契约冻结） | 关系图可交互渲染；编辑器端到端读写通；模块摘除后功能降级不阻塞 | 桥接对象名/schema 未冻结不动工 |
| Phase 8 审查与精修 | 一致性审查 + 精修 + 工作区绑定验证（卸载重装不丢稿） | 审查报告可产出；重装恢复验证通过 | 安全配置不同轴不上线 |
| Phase 9 裁剪与发布 | 硬裁剪集群 + 软裁剪 IM/设备控制等 + MCP 动手术 + 包体优化 + R8 keep 规则 + 签名发布 | 发布包体积较基座下降且功能回归通过 | 裁剪不落地可验证不算完成 |

### 8.2 环境准备

JDK 17+、Android SDK（compileSdk 36 / NDK r27）、阿里云 Maven 镜像配置保留、签名走 `scripts/sign.sh`、回归仅依赖 JVM 单测（`app/src/test/`）+ 构建（无 adb 真机流水线时）。大型二进制资源（模型文件）走 Release Assets，不进 git。

### 8.3 工程协作规范

1. Commit 规范：`[Phase N] 模块: 描述`；
2. 阻塞处理：编译失败先修编译；API 不确定先读源码不猜测；数据迁移失败回退版本号而非删用户数据；
3. 禁止兜底代码（继承 AIGC-NetNov AGENTS.md 规范）；
4. CI 构建日志回流驱动修复循环；每阶段验收标准写入 PR 模板。

## 9. 测试方案

| 层级 | 内容 | 来源范式 |
| --- | --- | --- |
| 单元 | 工具参数校验、模糊匹配五级、收敛判定、上下文预算淘汰、流派预设完整性、detectFamily 新用例、关系图物理模拟收敛、编辑器桥接契约五方法、子 Agent 零写权限（职责矩阵） | 基座 `app/src/test/` 范式 |
| 数据 | 作品级联删除、10 万字章节读写性能（< 2s）、关系图三表迁移版本递进/重复升级/降级抛错 | AIGC-NetNov 70 用例集 |
| 端到端 | 建作品→写章节→AI 续写→资料建档→审查→关系图渲染→编辑器回读→卸载重装恢复 | AIGC-NetNov E2E-001~008 |
| 模块化 | 编辑器模块摘除后全功能降级可用；集群模块移除后构建与回归通过 | 裁剪可验证范式 |
| 回归 | 基座功能不受影响（对话/记忆/知识库/技能/离线推理） | 每阶段门禁 |
| 安全 | WebView 接口白名单、禁文件跨域、API Key Keystore 加密、HTTPS 强制 | 与功能同轴上线 |

## 10. 风险登记与对策

| 风险 | 等级 | 对策 |
| --- | --- | --- |
| 工具未入 coreNames/ALWAYS_ON 导致模型不可见 | 高 | 注册 checklist 化 + 集成测试断言工具可见性 |
| ChatScreen（9807 行）/ QuroChatViewModel（3058 行）巨石改动冲突 | 高 | 新功能一律新文件，仅做最小接线 |
| 本地模型提示词预算不足 | 中 | 创作纪律压缩进 persona 核心；端侧轻任务优先 |
| 构建环境（NDK/镜像/签名）受阻 | 中 | Phase 0 先跑通；CI 复用同一配置 |
| 领域资产平移后调度质量不达预期 | 中 | 工具描述文案保留中文三段式；ToolCapabilityDirectory 补 ToolInfo |
| 端到端闭环再次失守 | 高 | 每阶段出口验收以"用户可走完路径"定义，禁止以层完成冒充 |
| 富文本编辑器桥接断裂（AIGC-NetNov 最后一公里教训） | 高 | 桥接契约文档冻结 + 端到端验收（编辑器输入→工具读回）+ 降级纯文本不阻塞 |
| 子 Agent 职责冲突或越权写数据 | 中 | 职责边界矩阵 + 子 Agent 零写权限 + 集成测试断言写工具只能经主 AI |
| 裁剪引发回归 | 中 | 软裁剪可回滚；集群硬裁剪与 MCP 动手术逐项验证构建与功能 |

## 11. 预期产出

1. Anise Write 安卓应用（可安装发布包，中文网文创作定位）；
2. 网文工具域（30 工具）+ 双技能套件 + 6 子 Agent 的完整领域层代码；
3. 25 流派预设库与全套 prompt 资产（中文，用户可改）；
4. 基座修复 Patch（回馈上游）；
5. 单测与端到端测试套件；
6. 本文档及配套决策记录。

## 12. 附录

### 12.1 关键文件索引（基座）

| 内容 | 路径 |
| --- | --- |
| 工具接口与注册表 | `app/src/main/java/com/ai/assistance/quro/core/tools/QuroTool.kt` |
| 内置工具注册 | `core/tools/QuroBuiltInTools.kt`（`buildQuroRegistry` :183） |
| 渐进式路由 | `core/tools/QuroToolRouter.kt`（ALWAYS_ON :59） |
| UI 动作工具 | `core/tools/QuroToolsUiActions.kt`（UI_ACTIONS :53-90） |
| Agent 主循环 | `core/QuroAssistant.kt` |
| 任务编排 | `core/agent/orchestration/LongHorizonOrchestrator.kt` |
| 技能播种 | `core/skill/QuroSkill.kt`（`seedBuiltinZorvSkills` :357） |
| 人格卡 | `core/QuroPersona.kt`（seed :163-193） |
| 提示词组装 | `ui/QuroChatViewModel.kt`（buildSystemPrompt :1889） |
| 平台清单 | `core/QuroPlatformManifest.kt` |
| 记忆分型 | `core/memory/QuroMemoryKind.kt` |
| 知识库 RAG | `core/knowledge/QuroKnowledgeRag.kt` |
| 推理控制 | `core/network/QuroReasoningControl.kt` |
| 主界面 | `ui/ChatScreen.kt`（9807 行） |

### 12.2 领域资产来源索引

| 资产 | 来源路径 |
| --- | --- |
| 50 工具定义 | novelIDE `lib/data/services/workspace_agent.dart:193-649` |
| 50 工具执行器 | novelIDE `lib/data/services/agent_tool_executors.dart` |
| 6 子 Agent prompt | novelIDE `lib/presentation/state/app_providers.dart:60-157` |
| 25 番茄预设 | novelIDE `lib/data/presets/tomato_presets_data.dart` |
| XML 降级解析 | novelIDE `lib/data/services/chat/xml_tool_call_parser.dart` |
| 审查规则 | novelIDE `ai_drawer.dart:156-227`、`polish_engine_page.dart:56`、`full_text_review_page.dart:96-102` |
| 记忆图谱 | novelIDE `database_helper.dart:208-284`、`memory_repository.dart` |
| 文本记忆 | novelIDE `novel_memory.dart` |
| 资料模型 | novelIDE `material_models.dart` |
| 校对引擎 | novelIDE `proofread_service.dart` |
| 关系图数据模型 | novelIDE `character_relationship.dart`；AIGC-NetNov `软件改造计划/04_数据模型.md:259-292`（关系/事件/参与者三表，强度 1-5 + 自定义颜色） |
| 编辑器设计参考 | novelIDE WebView 富文本编辑器资产（交互范式参考，不移植代码） |
| 20 实体设计 | AIGC-NetNov `app/src/main/java/com/ai/assistance/novelide/data/model/novel/` |
| 107 桥接方法 | AIGC-NetNov `novelide/bridge/NovelNativeBridge.kt` |
| 66 工具 ToolPkg | AIGC-NetNov `examples/novelide/src/packages/` |
| 预设种子 | AIGC-NetNov `data/migration/TomatoPresetSeeder.kt` |
| 改造规划全档 | AIGC-NetNov `软件改造计划/`（20 篇） |

### 12.3 基座缺陷 Issue 索引

| # | 标题 | 链接 |
| --- | --- | --- |
| 15 | 子智能体上下文不参与主循环压缩 | https://github.com/Quor-a/ZorvAI/issues/15 |
| 16 | 长耗时工具缺乏进度心跳 | https://github.com/Quor-a/ZorvAI/issues/16 |
| 17 | run_code 大文件挂死 + 读取缺口 + 技能库无接口 | https://github.com/Quor-a/ZorvAI/issues/17 |
| 18 | 技能列表无中文说明 | https://github.com/Quor-a/ZorvAI/issues/18 |
| 19 | 甘特图卡片渲染崩溃 | https://github.com/Quor-a/ZorvAI/issues/19 |
| 20 | 甘特图卡片渲染崩溃（重复提交） | https://github.com/Quor-a/ZorvAI/issues/20 |
| 21 | 深度思考开关对 Anthropic 兼容端点不生效 | https://github.com/Quor-a/ZorvAI/issues/21 |

> 本文档基于 ZorvAI v1.1.7、novelIDE v2.1.0、AIGC-NetNov v1.11.0+6 三仓库的代码级调查结论编制；所有源码断言均经本地克隆核实。改造实施期间如基座升级，需重新评估受影响章节。
