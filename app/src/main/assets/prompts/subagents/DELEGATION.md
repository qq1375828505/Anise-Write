# 子代理委派契约（DELEGATION）

> 本文件冻结 PenAi 6 个创作子代理（outline / character / foreshadow / polish /
> review / consistency）的输入输出 schema 与异步委派接口约定。
> 消费方：Phase 5（`novel_delegate_subagent` 工具实现与工作流集成）、
> Phase 3c（记忆层/图谱域的上下文供给与 findings 回流）。
> 本文件与 6 个提示词文件、`CONVERGENCE.md`、`ORCHESTRATION.md` 同属
> `assets/prompts/subagents/`，schema 版本号见各提示词 frontmatter `output-contract`。

## 1. 资产格式与加载契约

### 1.1 文件格式

每个子代理一个 Markdown 文件，YAML frontmatter + 正文：

```
---
name: outline                 # 子代理 ID（委派信封 subagent 字段的枚举值）
title: 大纲代理               # 中文名（UI 展示）
description: ……               # L1 摘要（≤100字，注入调度提示词）
trigger: 大纲,细纲,分卷,…      # 触发词，逗号分隔（模糊匹配用）
abilityWords: 大纲 细纲 …     # 能力词，空格分隔（路由召回用）
requires-tools: novel_get_outline,novel_build_context   # 派发前预取数据所需的只读工具
version: 1.0.0                # 提示词版本
output-contract: 1.0          # 输出契约版本（与本文件对齐）
risk: low
zero-write: true              # 硬约束标记，加载器与调度器可断言
---

# 正文 = 该子代理的 system prompt
```

格式兼容性结论：

- frontmatter 字段是基座 `assets/skills/zorv/manifest.json` 条目
  （id/name/description/file/size/suite/signature）与基座 cluster-skills
  frontmatter（name/description/trigger/abilityWords/requires-tools）的并集，
  **两种基座加载器都能解析**（详见 Phase 4b 报告「兼容性结论」节）。
- 正文即 system prompt，上限 20,000 字符（对齐 cluster-engine `SkillParser.scan`
  的正文长度红线）；本目录全部文件已控制在 6,000-9,000 字符。
- 提示词可被用户查看、修改、开关、导出（规划 §6.2 用户可改性结论）：
  加载器持久化时原样保留文件内容，不做重写。

### 1.2 加载器职责（Phase 5 实现）

1. 扫描 `assets/prompts/subagents/`，解析 6 个 `*.md`（跳过 `CONVERGENCE.md` /
   `DELEGATION.md` / `ORCHESTRATION.md`——按文件名黑名单或 frontmatter 缺
   `zero-write` 字段判定）。
2. 由 frontmatter 生成 `SubagentMeta`：`id/name/title/description/trigger[]/
   abilityWords[]/requiresTools[]/version/outputContract`；计算 `size`（字节）
   与 `signature`（SHA-256），供增量播种与用户修改检测。
3. 播种为基座 `QuroSkill` 记录（`core/skill/QuroSkill.kt` 的 seed 机制）：
   `suite = "novel-subagents"`，`enabled = true`，`callable = false`，
   `alwaysOn = false`。**不注册为 `skill__*` 工具**——调度只走
   `novel_delegate_subagent`（规划 §6.6）。增量播种须新开一次性守卫 key
   （基座既有坑：复用旧 key 不会重新播种）。
4. 用户修改检测：`signature` 变化 → 以用户版本为准并更新签名；禁止回写 assets。

## 2. 委派信封（JSON schema）

所有委派只有一个入口：`novel_delegate_subagent` 工具。子代理收到的 user
消息是下述 **请求信封**；子代理的完整输出是下述 **响应信封**。
信封是纯 JSON，可被 `org.json` 解析；禁止注释、尾随逗号、代码块围栏。

### 2.1 请求信封（SubagentRequest）

```json
{
  "delegation_id": "dlg_3f9a1c2b7e04d5a6",
  "subagent": "outline",
  "round": 1,
  "task": "为第三卷展开第81-100章细纲",
  "work": {
    "work_id": "wk_01",
    "title": "剑出小镇",
    "genre": "玄幻·热血",
    "premise": "铁匠之子觉醒剑印，被七大世家追杀",
    "target_words": 1200000
  },
  "targets": { "scope": "chapters", "from": 81, "to": 100 },
  "context": {
    "outline": "第二卷末：……（既有大纲摘要，无则空串）",
    "chapter": { "chapter_id": "ch_047", "title": "断刃", "content": "正文……", "summary": "" },
    "chapters": [ { "chapter_id": "ch_060", "title": "", "content": "" } ],
    "materials": {
      "characters": [ { "name": "林渊", "role": "主角", "key_fields": "……" } ],
      "settings": [], "items": [], "factions": [],
      "hooks": [ { "hook_id": "hk_01", "title": "断剑来历", "status": "planted", "plant_chapter": 31, "idle_chapters": 49 } ],
      "relationships": [ { "from": "林渊", "to": "苏劫", "type": "宿敌", "strength": 5 } ]
    },
    "previous_chapters": [ "第46章：……", "第45章：……" ],
    "chapter_summaries": [ "第88章：……" ],
    "notes": "其他已确认事实（可空）"
  },
  "constraints": {
    "max_output_chars": 8000,
    "focus": [ "爽点分布", "反转" ],
    "style_tokens": "……",
    "user_requirements": "用户原话（可空）",
    "thresholds": { "water_ratio": 0.25, "dup_similarity": 0.8 }
  },
  "previous_round": null
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `delegation_id` | string | 是 | `dlg_` + 16 位 hex，全工程唯一；响应必须回显 |
| `subagent` | enum | 是 | `outline/character/foreshadow/polish/review/consistency` |
| `round` | int | 是 | 1-3，见 CONVERGENCE §5 |
| `task` | string | 是 | 主 AI 转写的自然语言任务 |
| `work` | object | 是 | `work_id` 必填；其余可空 |
| `targets` | object | 是 | 各代理专有，见 §2.3 |
| `context` | object | 否 | 预取数据；主 AI 按 §3 用 `requires-tools` 预填 |
| `constraints` | object | 否 | `user_requirements` 优先级高于提示词一切默认规则 |
| `previous_round` | object\|null | 否 | `{round, summary, output}`；round ≥ 2 必填 |

### 2.2 响应信封（SubagentResponse）

```json
{
  "delegation_id": "dlg_3f9a1c2b7e04d5a6",
  "subagent": "outline",
  "round": 1,
  "status": "ok",
  "confidence": 0.85,
  "summary": "……（≤200字结论，供主 AI 直接引用）",
  "payload": { },
  "escalations": [
    { "type": "authority_conflict|missing_data|user_decision|out_of_scope", "detail": "……", "evidence": [ "……" ] }
  ],
  "self_check": { "passed": true, "failed_items": [ ] }
}
```

`status` 枚举与主 AI 的处置：

| status | 含义 | 主 AI 处置 |
|---|---|---|
| `ok` | 正常产出 | 进入汇总/收敛流程 |
| `insufficient_input` | 关键输入缺失 | 按 `escalations.missing_data` 清单补取后重派（≤1 次），否则转用户 |
| `conflict_detected` | 发现与权威源冲突 | 不自行落库，按 CONVERGENCE §3 处理 |
| `needs_user_decision` | 需用户拍板 | 进升级清单 |
| `error` | 执行失败 | 按 CONVERGENCE §5 失败处理 |

### 2.3 各代理 targets 专有字段

| subagent | targets 字段 | 必填 |
|---|---|---|
| outline | `scope`（`whole/volume/chapters`）、`volume_index`、`from`、`to` | `scope` 必填 |
| character | `mode`（`create/enrich/derive_relationships`）、`character_names[]` | `mode` 必填 |
| foreshadow | `mode`（`plan/audit/both`）、`chapter_range{from,to}` | `mode` 必填 |
| polish | `mode`（`suggest/rewrite`） | 必填 |
| review | （无专有字段，范围由 `context.chapter` 决定） | — |
| consistency | `scope{from_chapter,to_chapter}` | 必填 |

各代理 `payload` 的完整结构见对应提示词文件「输出契约」节，以文件为准；
本表与文件不一致时以提示词文件为准并 bump 其 `output-contract` 版本。

## 3. 预取规则（主 AI 派发前执行）

主 AI 按目标代理 frontmatter `requires-tools` 预取数据填 `context`：

| 工具 | 填入 | 说明 |
|---|---|---|
| `novel_get_outline` | `context.outline` | outline/character/foreshadow 用 |
| `novel_build_context` | `context.previous_chapters` 等 | 4000-8000 字符预算内按相关度 top-N（规划 §6.5） |
| `novel_materials_get` | `context.materials.*` | character/consistency 用 |
| `novel_hooks_status` | `context.materials.hooks` | foreshadow 用 |
| `novel_relationships` | `context.materials.relationships` | character/consistency 用 |
| `novel_get_chapter` | `context.chapter` / `context.chapters` | polish/review/consistency 用 |
| `novel_query_previous` | 按 task 检索补充 | 大范围任务替代全量灌注 |
| `novel_chapter_stats` | metrics 参照 | review 用 |

预取失败（工具报错）→ 对应 context 字段留空并在 `constraints` 或 task 中注明，
**禁止用假数据填充**（工程禁令：无兜底）。

## 4. 异步委派接口约定（novel_delegate_subagent）

### 4.1 工具参数（供 Phase 5 实现 QuroTool）

```json
{
  "name": "novel_delegate_subagent",
  "description": "派发一个创作子代理（大纲/人物/伏笔/润色/审读/一致性）。子代理零写权限，结果以建议态返回，写操作由主 AI 执行。",
  "parametersJson": {
    "type": "object",
    "properties": {
      "subagent": { "type": "string", "enum": ["outline","character","foreshadow","polish","review","consistency"], "description": "目标子代理；也接受中文名/任务词，服务端五级模糊匹配（P0-2）" },
      "task": { "type": "string", "description": "自然语言任务" },
      "work_id": { "type": "string" },
      "chapter_id": { "type": "string", "description": "单章任务必填" },
      "chapter_range": { "type": "object", "properties": { "from": {"type":"integer"}, "to": {"type":"integer"} } },
      "mode": { "type": "string", "description": "子代理专有模式，见 DELEGATION.md §2.3" },
      "round": { "type": "integer", "minimum": 1, "maximum": 3 },
      "model_group": { "type": "string", "enum": ["same_as_me","default_model","sub_model"], "description": "本次委派用哪一档模型：same_as_me=跟当前会话同一模型（默认）、default_model=用户的默认组（通常最强）、sub_model=用户的轻量组（通常更快更便宜）。只表达意图，不写模型名——你不知道用户有哪些模型、哪个强哪个便宜。若用户为该子代理钉死了模型组，本字段被忽略。未给时服务端按任务重量自动路由（重任务→默认组、轻任务→轻量组）。" },
      "previous_result": { "type": "string", "description": "round≥2 时填上轮响应 JSON" }
    },
    "required": ["subagent", "task", "work_id"]
  },
  "readOnly": true
}
```

### 4.2 执行语义（硬约束）

1. **主循环内执行**：子代理调用发生在主 AI 的 ReAct 循环内，作为一次
   工具调用完成；**不创建独立 subStore**（规避基座 Issue #15 上下文压缩缺陷，
   规划 §6.6）。
2. **同步返回、可观测**：`run` 返回子代理响应 JSON 字符串；长任务（全文审查）
   由基座 Issue #16 修复后的进度心跳机制透传中间态（Phase 6 事项，Phase 5
   先按「执行中」单态返回）。
3. **零写权限**：`novel_delegate_subagent` 本身 `readOnly = true`；子代理
   会话不得下发任何 `novel_*` 写工具 schema；主 AI 的写操作经
   `novel_write_chapter` / `novel_materials_add` 等显式另发工具调用完成。
4. **失败可见**：子代理失败返回 `{status:"error", detail}` + 可用子代理清单，
   不返回空结果冒充成功（工程禁令：无兜底）。

### 4.3 工具注册（七步法，规划 §2.3）

Phase 5 实现时须双写：`buildQuroRegistry()` 注册行 + `coreNames` +
`QuroToolRouter.ALWAYS_ON`（或按检索模式入 ToolCapabilityDirectory）。
`app/src/test/` 补 JVM 单测：枚举校验、信封回显、零写断言。

## 5. 与 Phase 3c / Phase 5 的集成点

| 集成方 | 契约 |
|---|---|
| Phase 3c 记忆层 | 子代理 findings 回流：主 AI 将响应 `payload` 中可持久化结论（伏笔状态建议、关系边建议、冲突记录）经 `novel_memory_save` / 图谱域工具落库；**子代理不直接写记忆**。上下文供给：`novel_build_context` 与 `novel_query_previous` 的输出结构即 `context` 字段（§3 表），Phase 3c 交付时须对齐本表键名。 |
| Phase 5 工作流 | 七阶段工作流的每个「分析/检查」步骤 = 一次或多次 `novel_delegate_subagent` 派发 + 收敛（CONVERGENCE.md）；「生成」步骤的产出经交付闸门判定（基座 `HeuristicDeliverabilityJudge`）。迭代轮次由工作流步骤驱动，信封 `round` 递增并携带 `previous_round`。 |
| 端侧/云端路由 | 轻任务（polish 单章、review 单章）可端侧；重任务（consistency 全书扫描、review 多章连审）走云端（规划 §6.9）。路由发生在主 AI 侧，信封不变。 |

## 6. 版本演进规则

- 信封 schema 变更：bump 本文件版本号 + 全部 6 个提示词 `output-contract`，
  旧委派在途时按 `output-contract` 版本选择解析器。
- 单代理 `payload` 结构变更：只 bump 该代理 `output-contract` 次版本号
  （1.0 → 1.1），并在其提示词「输出契约」节更新。
- 任何变更不得破坏：`delegation_id` 回显、`status` 枚举、`escalations` 结构、
  零写权限四要素。
