# Anise Write 软件改造计划书 · V1.1 备份

本目录为 **Anise Write 软件改造计划书（V1.1，2026-10-16 定稿）** 的完整规划资产备份，
源自三仓库代码级调查（ZorvAI v1.1.7 / novelIDE v2.1.0 / AIGC-NetNov v1.11.0+6）
与用户评审决策，供 Anise Write 改造实施期随时取用。

## 文件清单

| 文件 | 说明 |
| --- | --- |
| `PenAi软件改造计划书.docx` | **主交付物**。12 章、21 表，文学出版风排版；封面/目录/正文三节，正文页码从 1 重起 |
| `plan.md` | 计划书 Markdown 源稿（V1.1，含全部用户决策） |
| `plan.html` | 排版版 HTML（design token + 执行摘要 + 目录） |
| `design_tokens.json` | 文学出版风 design token（暖白纸/墨黑/衬线标题/深靛蓝/哑砖红） |
| `draft_review.md` | 评审稿记录 |
| `pipeline-state.yaml` | 生成流水线状态与后处理记录 |
| `scripts/regen_stage2.py` | Stage2 HTML 再生脚本（md → HTML） |
| `scripts/html_review.py` | Stage2 六维自检脚本（结构/渲染/语义/token/排版/保真） |
| `scripts/fix_docx_round3.py` | Stage3 docx 一站式 XML 级后处理（当前生效版本） |
| `scripts/fix_docx_round2.py` | Stage3 后处理历史版本（已被 round3 取代，留存备查） |

## 内容要点（V1.1 冻结决策）

- 基座 ZorvAI 零改复用 Agent 内核；全部工作量集中在网文领域层（30 个 `novel_*` 工具）
- 25 个番茄流派 → `novel-writing` + `tomato-genre` 双技能套件（用户可改）
- 6 个子 Agent 以 callable 技能形态存在、零写权限，写操作一律回流主 AI 统一协调
- 富文本编辑器以 `:novel-editor` 独立可插拔模块保留；不移植基座 UI（自建 4 Tab）
- 不要工作树目录，只保留工作区；不做导入导出
- 人物关系图三表（关系/事件/参与者）；基座 7 项缺陷修复（Issues #15–#21）

## 质量记录

- 官方校验 `verify_output.py`：内容 525/525 零缺失、标题样式绑定 56/56
- 工具数口径经代码级实测校正：基座 248 注册/203 coreNames/154 ALWAYS_ON；novelIDE 50 工具
