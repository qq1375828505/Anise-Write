# Anise Write · 项目记录（Project Record）

> **这是项目的权威档案。** 想知道"这是什么、做到哪、接下来做什么"，读这一份就够。
> 最后更新：2026-10-22（会话收尾）
> 配套：[UI 规范](docs/design/AniseWrite_UI_SPEC.md) · [模型组规范](docs/design/ModelGroup_SPEC.md) · [续作指引](docs/progress/HANDOFF_2026-10-22.md) · [验收清单](docs/progress/ACCEPTANCE_CHECKLIST.md)

---

## 1. 项目身份

**Anise Write** —— 运行在 Android 上的**端侧网文写作 AI Agent**。

| | |
|---|---|
| 基座 | [ZorvAI](https://github.com/Quor-a/ZorvAI) v1.1.7（Kotlin 2.3 + Compose 1.10.2，Apache-2.0） |
| 领域资产 | novelIDE v2.1.0（Flutter，MIT）——只借鉴信息架构与资产，**不移植代码** |
| 界面范式 | **完全沿用 Minis（= OpenMinis）界面布局**（GPL-3.0，只取布局与数值，实现独立编写） |
| 仓库 | `github.com/qq1375828505/Anise-Write`（旧名 PenAi / AniseWrite 均 302 跳转） |
| 本地 | `/sdcard/Ai/Anise Write`（沙箱 `/var/minis/mounts/Ai/Anise Write`） |
| 包名 | `com.ai.assistance.quro`（**刻意不改**：改名 = 857 文件 diff + 应用身份断裂） |
| 许可 | Apache-2.0；NOTICE 含 ZorvAI 与 novelIDE 归属声明 |

**做什么**：把"想故事"变成"可交付章节" —— 七阶段写章工作流、40 个写作工具、25 套中文网文流派预设、6 个写作子代理、伏笔/人物关系/记忆三件套图谱、按任务选模的模型组。
**不做什么**：通用 Agent 换皮；不做导入导出；不搬 Flutter 代码。

## 2. 架构与代码地图

```
app/src/main/java/com/ai/assistance/quro/
├─ core/novel/                    2942 行 · 领域契约层（纯 Kotlin，零 Android 依赖，可 JVM 单测）
│   ├─ NovelModels.kt            作品/卷/章/章节状态
│   ├─ NovelRepository.kt        作品数据仓库（原子写，正文 .md）
│   ├─ asset/                    8 类资料模型 + 仓储
│   └─ graph/                    伏笔/关系/记忆 + 一致性分析
├─ core/tools/novel/             4677 行 · 40 工具（结构 15 / 资料 6 / 图谱 8 / 写作 11）
├─ core/model/QuroModelGroup.kt   384 行 · 模型组数据层 + 路由（意图三选一/钉住优先/显式回落）
├─ ui/novel/                     2520 行 · 写作工作台（令牌 + 分组组件 + 四页签 + 关系图 v2）
└─ ui/QuroModelGroupScreen.kt     784 行 · 模型组界面（列表/详情/成员选择/子代理绑定）
```

**资产**：`assets/skills/penai/` 25 套流派预设 · `assets/prompts/subagents/` 6 个子代理提示词 + DELEGATION.md 委托协议 + 人格卡
**文档**：70 篇 · **提交**：60 次 · **novel 域代码**约 1.1 万行

## 3. 功能清单（已交付）

| 域 | 交付物 | 状态 |
|---|---|---|
| 结构 | 作品/卷/章 CRUD、大纲读写、章节状态与统计、续写、章节重命名/移动 | 已交付 |
| 资料 | 8 类资料增删改查 + RAG 检索 | 已交付 |
| 图谱 | 伏笔生命周期、人物关系 + 关系图数据、记忆、一致性检查 | 已交付 |
| 写作 | 25 流派切换、上下文组装、正文、标题候选、去 AI 味、爽点/水字数报告、子代理委托 | 已交付 |
| 工作流 | 七阶段模型与编解码（Phase 5 契约对齐：与 Phase 1/3a/3b 同源，无第二套真相） | 已交付 |
| 界面 | 工作台四页签 + 纯文本编辑器 + 侧滑/Sheet/对话框组件 | 已交付 |
| 关系图 v2 | 人物卡节点（首字头像+姓名+身份，度数分级）+ 势力聚类 + 类型着色边 + 力导向 + 点选高亮 + 节点拖动 | 已交付 |
| 模型组 | 命名组（默认/轻量/自定义）+ 成员增删 + 子代理绑档 + 委派意图三选一 + 不可用显式回落 | 已交付 |
| 工具侧 | 40 个 novel_* 全部注册进基座工具表与 coreNames | 已交付 |

## 4. 关键决策（不可轻易推翻）

| 决策 | 内容与理由 |
|---|---|
| **改名** | PenAi → **Anise Write**。GitHub 仓库名不支持空格（自动转连字符 → Anise-Write）；显示名/应用名/文档用带空格的 "Anise Write" |
| **界面范式** | **完全沿用 Minis 界面布局**；前两版（"现代文学出版风"、自创"暗色玻璃"）作废。铁律：新增界面先在 Minis 找对应页面/组件再映射；数值统一在 NovelDesign；Canvas 绘制层不得读 @Composable 状态 |
| **组件方法论** | 数值取自 Minis 逐像素测量；单一真相源；对比度实测写进注释；语义命名（值才是契约） |
| **许可边界** | Minis=OpenMinis(GPL-3.0)、novelIDE(MIT)：只取方法论/布局/数值/交互范式，**代码独立编写**，不复制源码素材 |
| **契约对齐** | Phase 5 按 §8 裁决表收敛工作流层 20 类型：WorkflowRunStatus 改名；章节/大纲/资料改为**引用** Phase 1/3a/3b 模型 |
| **模型组语义** | 委派方只表达**意图**（同款/默认组/轻量组），**不写模型名** —— "哪个模型强/便宜"是用户的知识，"任务多难"才是模型的知识。**钉住 > 意图 > 父会话**；钉住不可用时**显式标记**回落 |
| **不重复造** | provider 级故障转移复用 QuroFailoverManager；多 provider 复用 QuroMultiProviderRepository |
| **包名/内部标识不动** | 保证升级兼容与控制 diff；对外显示名已改 |

## 5. 未完成清单（按优先级）

**P0 · 端侧小模型的坎**
1. **XML 工具调用降级三层**（400/422 去 tools 重试 → 正则解析层 → 循环层回传）—— 端侧刚需，**未做**
2. **子代理五级模糊匹配** —— 现为精确枚举，"帮我润色这段"会失配

**P1 · 计划书点名的差异化能力**
3. **AI 资料自动分析**（读书 → 6 类 JSON 提取 → 去重合并 → 自动建档）
4. **小说记忆双轨**（10 节文本记忆 5 分钟 TTL + 记忆图谱 5 表 + RRF 混合检索 K=60）—— 现只有单表 JSON
5. **重任务/轻任务路由**（novelIDE model_router）—— 模型组已打好地基，差最后一接
6. **审读/爽点/水文/标题工具的 UI 入口** —— 工具层全有，工作台一处没用
7. **记忆（memories）UI 入口** —— 数据层已加载，界面未露

**打磨**：⑧ 富文本编辑器 :novel-editor（WebView，工作量最大） ⑨ 卷内拖拽排序 ⑩ 统计页二级指标 ⑪ 模型组上下文上限/思考档透传调用链 ⑫ 关系图图例可折叠

**流程**：⑬ **Phase 7b 正式打包**（用户手动配 4 个 Secret：ZORV_KEYSTORE_BASE64 / ZORV_KEY_ALIAS / ZORV_STORE_PASS / ZORV_KEY_PASS，再打 v* tag） ⑭ **Phase 8/9 验收发布**（按 ACCEPTANCE_CHECKLIST.md 逐条走）

## 6. 工作方式与红线

- 本地**不能编译**（红线：不得本地跑 gradle、不得产出 APK）。**CI 是唯一编译器**，每轮 25~30 分钟。
- 私钥 / keystore / 口令绝不进仓库、不进日志、不贴进聊天。
- `rg` **不要用 -r**（那是替换不是递归）；大文件 file_write 拆段（<=8KB）否则流式卡死。
- 提交前跑静态自检：`python3 /var/minis/workspace/check_phase6.py`（括号配平/符号存在性/Canvas import/Double 字面量/禁用语法/图标 import 完整性）
- 提交规范 `[Phase N] 模块: 描述`；**feature/penai 与 main 始终同步推送**（首页只渲染 main 的 README）
- CI 配了 cancel-in-progress → 同分支再推会取消在跑的 run（**cancelled 不等于失败**），尽量攒批再推
- 沙箱网络 github.com:443 时断时续（api.github.com 通常通）；主站挂了走 **Git Data API**（api_push.py），收尾 `git fetch` + `git rebase origin/feature/penai`
- 挂载会瞬时丢文件 → 先查挂载，`git checkout --` 恢复

## 7. 运维速查

| 用途 | 命令 / 位置 |
|---|---|
| 推送 | `git push origin feature/penai` |
| 网络恢复后对齐 sha | `sh /var/minis/workspace/sync_remote.sh` |
| 主站挂了推送 | `python3 /var/minis/workspace/api_push.py` |
| 静态自检 | `python3 /var/minis/workspace/check_phase6.py` |
| 看 CI | `python3 /var/minis/workspace/poll_ci.py <sha前8位> <最长分钟>` |
| UI 视觉稿 | `/var/minis/workspace/anise-ui-preview.html`、`anise-graph-preview.html` |
| 打包 Secret 步骤 | docs/progress/HANDOFF_2026-10-22.md §4b |

## 8. 文档索引

| 类别 | 文档 |
|---|---|
| 计划书 | docs/plan-v1.1/plan.md（§6.7 界面、§6.8 关系图、§6.10 模型组） |
| 设计规范 | docs/design/AniseWrite_UI_SPEC.md（**Minis 组件对照表**）、design_tokens.json、ModelGroup_SPEC.md |
| 进度 | docs/progress/PHASE6_2026-10-22.md（全量）、HANDOFF_2026-10-22.md（**续作先读**）、ENGINEERING_RECOVERY_PLAN.md（§8 契约裁决表）、ACCEPTANCE_CHECKLIST.md |
| 自述 | README.md |

---

**一句话现状**：功能与骨架已成（40 工具 / 25 流派 / 6 子代理 / 四页签工作台 / 关系图 v2 / 模型组），代码在 feature/penai 与 main 同线上；**下一步是 P0 的端侧兼容（XML 降级 + 子代理模糊匹配）**，再之后是验收与正式打包。
