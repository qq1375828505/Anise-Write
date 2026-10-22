# Anise Write 工程约定（ENGINEERING_CONVENTIONS）

> 本文件是 Anise Write 移植工程所有子代理与主编排层的共同契约。任何分支的代码必须遵守。
> 上位文档：《Anise Write 软件改造计划书 V1.1》（`/workspace/penai/规划书V1.1-源稿.md`）。

## 1. 工程布局

- 构造仓库：`/workspace/penai/Anise Write`（分支 `feature/penai`，origin = `https://github.com/qq1375828505/AniseWrite.git`）
- 各阶段在独立 worktree + 独立分支作业：`git worktree add /workspace/penai/worktrees/<名> -b phase/<N>-<名>`
- 参考克隆（只读）：`/workspace/penai/repos/{ZorvAI,novelIDE,AIGC-NetNov}`
- **不在本地构建**：本机不装 Android SDK、不跑 gradle。编译与打包由 GitHub Actions 云端完成（见 Phase 7a 的 `.github/workflows/`）。

## 2. 代码风格（继承基座，不得自创）

- Kotlin 2.3 + Jetpack Compose + Material3；包名 `com.ai.assistance.quro.*`（冻结不变）
- 新功能一律**新文件**；对既有文件只做**最小加法接线**（ChatScreen 9807 行 / QuroChatViewModel 3058 行是巨石，严禁大改）
- 持久化：Phase 1-3 用 `filesDir/novel/` 下的 JSON + `.md` 正文（org.json，对齐 `core/QuroConversationPersistence.kt` 风格）；**不引入 Room**
- UI 串一律进 `app/src/main/res/values/strings.xml`（只追加，勿重排既有条目）
- 视觉令牌集中在 `ui/novel/NovelDesignTokens.kt`（暖白纸 #FAF8F3 / 墨黑 #1F1E1C / 深靛蓝 #3E5C76 / 细线 #E5E1D8；衬线标题 FontFamily.Serif；禁陶土色、禁中式文艺）

## 3. 硬性禁令

- **禁止兜底代码**（catch 后返回假数据、TODO 桩、TODO()、UnimplementedError 等）；功能未就绪就明说，不假装完成（AIGC-NetNov 教训）
- 不确定的基座 API 先读源码，不猜签名
- 不碰签名密钥与 `scripts/sign.sh` 凭证机制；release 签名走 GitHub Secrets
- 不跑 gradle、不产 APK（云端构建）
- 不做 git push（github.com 网络不通；主编排层用 API 统一推送）

## 4. 阶段分支与文件归属（防合并冲突）

| 分支 | 负责领域 | 独占文件/目录 |
|---|---|---|
| phase/0-baseline | 更名、版本冻结、签名审计 | settings.gradle.kts、app_name、docs/ |
| phase/1-minimal-loop | 数据层契约 + 工作台骨架 + 接线 | `core/novel/NovelModels.kt`、`core/novel/NovelRepository.kt`、`ui/novel/NovelStudioScreen.kt`、ChatScreen 一行入口、UI_ACTIONS 的 ui_open_novel |
| phase/2-ai-loop | 文风令牌 + 人设 + 三工具 | `core/tools/novel/NovelWriteTools.kt`、`assets/prompts/penai-novel.*`、工具注册表加法行 |
| phase/3a-structure | 结构域 13 工具 | `core/tools/novel/NovelStructureTools.kt`、注册表加法行 |
| phase/3b-assets | 资料域 8 类模型+工具 | `core/novel/asset/*`、`core/tools/novel/NovelAssetTools.kt` |
| phase/3c-graph | 伏笔/关系/分析/记忆域 | `core/novel/graph/*`、`core/tools/novel/NovelGraphTools.kt` |
| phase/4a-skills | 25 流派技能包 | `assets/skills/penai/*` |
| phase/4b-prompts | 6 子代理提示词 | `assets/prompts/subagents/*` |
| phase/5-workflow | 七阶段工作流+上下文预算 | `core/novel/workflow/*` |
| phase/7a-ci | 云端构建+NOTICE | `.github/workflows/*`、NOTICE |

- **数据契约**：`core/novel/NovelModels.kt`（Phase 1）是全工程数据模型基准；其他分支只读引用、不得修改该文件。若发现契约缺陷，写入报告交主编排层，不自行改。
- 工具注册表（基座工具清单文件）各分支只做**加法行**；冲突由主编排层合并时统一裁决。

## 5. 提交与验收

- 提交规范：`[Phase N] 模块: 描述`（每个 phase 一个或多个 commit）
- 每个子代理交付 = 分支 + commit + 结构化报告（改动清单 / 出口验收对照 / 遗留问题）
- 主编排层职责：审 diff、合并进 feature/penai、跑云端构建、验收签字
