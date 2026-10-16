# PenAi 移植工程 · 进度记录（PROJECT_STATUS）

> 快照时间：2026-10-16 18:40（GMT+8）
> 仓库：`qq1375828505/PenAi`（本文件所在仓库）
> 上位文档：[docs/plan-v1.1/](../plan-v1.1/)（规划书 V1.1 全套）
> 工程约定：[ENGINEERING_CONVENTIONS.md](../../ENGINEERING_CONVENTIONS.md)

---

## 1. 工程概览

**PenAi** = 基于 **ZorvAI v1.1.7**（开源安卓端侧 AI Agent，`com.ai.assistance.quro`，Kotlin 2.3 + Compose，Apache-2.0）改造的**网文写作 AI Agent**。

- 基座代码已完整入库（3262 文件），App 更名 PenAi，包名冻结不变
- 施工方式：**子代理并行分工写代码，主编排层验收合并**，Git worktree 隔离（每阶段独立分支）
- 构建方式：**本地不构建、不产安装包**；代码推送本仓库后由 **GitHub Actions 云端打包**（见 §6）

## 2. 方针变更记录

| 时间 | 变更 |
|---|---|
| 16 日下午 | 用户裁定：**不在本地生成安装包**；全部代码写好上传仓库，GitHub 云端构建。本地 Android SDK 已卸载 |
| 同日 | 子代理规模从 2 个扩至 10 个并行（4 个默认模型 + 7 个 lite 模型，其中默认模型批次因配额 429 有 7 个改走 lite 重派） |
| 同日晚 | **8 个阶段分支已全部推送远程**（rebase 到最新 main 之上，克隆即可 checkout 续开发）；远端分支：main + phase/1~5、4a、4b |

## 2.1 云端 CI 排障记录（build.yml，共 5 修）

| 轮次 | 现象 | 根因 | 修复 |
|---|---|---|---|
| 1 | Set up job 6 秒失败 | action 名 `android-actions/setup-android-sdk` 不存在 | 改 `setup-android@v3` |
| 2 | `sdkmanager --licenses` 退出 1 | cmdline-tools 16.0 许可证交互流程变化 | 弃用该 action，用 runner 预装 sdkmanager 直装组件 |
| 3 | `ClassNotFoundException: GradleWrapperMain` | 上游 .gitignore 滤掉了 gradle-wrapper.jar | 本地生成 jar 强制入库 |
| 4/重跑 | KSP 2.3.9 "not found in any sources" | 构件**实际存在**（Maven Central POM/JAR 均 200）；首轮网络抖动被 Gradle 缓存为"缺失"并随 Actions 缓存扩散 | gradlew 加 `--refresh-dependencies` |
| 进行中 | 第 5 轮构建 | — | 结果见 [Actions](https://github.com/qq1375828505/PenAi/actions) |

## 3. 阶段状态总表

| Phase | 内容 | 分支 | 状态 | 说明 |
|---|---|---|---|---|
| 0 环境与基线 | 更名/版本冻结/签名审计 | `phase/0-baseline` | ✅ **已验收合并** | 更名 commit `9b3e6c0`；构建验证按新方针移交云端 CI |
| 1 端到端最小闭环 | 数据层+工作台+接线 | `phase/1-minimal-loop` | 🟡 WIP 封存 `574b6fa` | 模型/仓库/设计令牌已产出，界面与接线未完 |
| 2 AI 闭环 | 文风令牌+人设+三工具 | `phase/2-ai-loop` | 🟡 WIP 封存 `8d015ee` | persona/style_tokens/NovelWriteTools(45K) 已产出，注册接线未完 |
| 3a 结构域工具 | 作品/卷/章 13 工具 | `phase/3a-structure` | 🟡 WIP 封存 `8a03ee7` | NovelStructureTools(46K) 已产出，单测未完 |
| 3b 资料域工具 | 八类资料模型+仓库+工具 | `phase/3b-assets` | 🟡 WIP 封存 `02f6d12` | 模型/仓库/工具 69K 已产出 |
| 3c 图谱域工具 | 伏笔/关系/分析/记忆 | `phase/3c-graph` | 🟡 WIP 封存 `199c260` | 伏笔模型起步，其余未完 |
| 4a 流派技能包 | novelIDE 25 番茄流派移植 | `phase/4a-skills` | 🟡 WIP 封存 `2ed4594` | **25 个 SKILL.md + manifest.json + README 全部产出**，待验收 |
| 4b 子代理提示词 | 6 子代理+收敛+委派 | `phase/4b-prompts` | 🟡 WIP 封存 `776761a` | 6 提示词+CONVERGENCE+DELEGATION 已产出，ORCHESTRATION 未完 |
| 5 工作流引擎 | 七阶段状态机+上下文预算 | `phase/5-workflow` | 🟡 WIP 封存 `308b487` | ChapterWorkflowModels(28.7K) 起步 |
| 7a 云端 CI + NOTICE | GitHub Actions + 归属清单 | `phase/7a-ci` | ✅ **已验收合并** | 详见 §6 与 §4 |
| 6 UI 完整版 / 7b 打包 / 8 验收 / 9 发布 | — | — | ⏳ 未启动 | 依赖 Phase 1/2/5 合并后派发 |

## 4. 已验收合并记录（main 分支）

1. `6637c84` [Phase 0] 工程约定 ENGINEERING_CONVENTIONS.md（布局/风格/禁令/分支归属契约）
2. `52b3ca3` 验收合并 Phase 7a：
   - `.github/workflows/build.yml`（126 行）：push/PR 触发，JDK 17 + platform 36 + build-tools 36.0.0 + NDK 27.0.12077973 + CMake 3.22.1，Gradle 双层缓存，`assembleDebug` + `testDebugUnitTest`，APK/测试报告 artifact，90 分钟超时
   - `.github/workflows/release.yml`（161 行）：tag `v*` 触发，Secrets 注入签名凭证（fail-fast 预检），`assembleFullRelease` + sign.sh 幂等补签 + SHA256SUMS + Release 附件；**workflow 内无写死密钥**
   - `.github/workflows/README.md`：CI 说明与 Secrets 配置指南
   - `NOTICE`：343 行基座清单完整继承 + 补齐 Room/SQLCipher/Markwon/kaml/BouncyCastle 等 13 项审计缺口 + UPSTREAM BASE ATTRIBUTION（ZorvAI 上游归属）
3. `（本次）` 验收合并 Phase 0 更名 `9b3e6c0`：rootProject.name + 12 locale app_name（"Zorv AI"→"PenAi"）+ sc_browser_long 桌面标签 + 3 处 QS 磁贴标签 + 伴随 APK 显示名；**applicationId/namespace 保持 `com.ai.assistance.quro`（冻结决策）**

### 验收结论依据
- Phase 7a：YAML 双校验通过（yaml.safe_load + bash -n）、SDK 组件与 build.gradle.kts 完全对齐、发现并解决 `signingConfigs` 配置期 NPE（占位 keystore.properties 方案）、Secrets 仅列名不落值
- Phase 0：12 locale 全覆盖、包名未动、diff 31 行最小侵入

## 5. 并行产出封存明细（docs/progress/wip/）

8 个阶段分支的未完工作已提交封存，并快照到 `docs/progress/wip/<phase>/`（51 文件，592K）。**此目录是快照归档，不是可编译代码**——合并须待各分支完成并验收。

| 分支 | 封存 commit | 核心产出（大小） | 缺口 |
|---|---|---|---|
| phase/1-minimal-loop | `574b6fa` | NovelModels.kt(6.5K)、NovelRepository.kt(12.8K)、NovelDesignTokens.kt(3.7K) | NovelStudioScreen、ChatScreen 接线、ui_open_novel、单测 |
| phase/2-ai-loop | `8d015ee` | persona.md(4.9K)、style_tokens.md(9K)、NovelWriteTools.kt(45.4K) | 工具注册接线、泛用写工具下线结论、人设激活 |
| phase/3a-structure | `8a03ee7` | NovelStructureTools.kt(46.4K) | 单测、注册接线 |
| phase/3b-assets | `02f6d12` | NovelAssetModels.kt(23.2K)、NovelAssetRepository.kt(16.2K)、NovelAssetTools.kt(30.1K) | 单测、注册接线 |
| phase/3c-graph | `199c260` | NovelHook.kt(4.7K)、ChapterRef.kt(1.1K)（+契约文件副本） | 关系/分析/记忆模块、工具 |
| phase/4a-skills | `2ed4594` | **25 流派 SKILL.md + manifest.json(13.8K) + README(9.5K) + gen_skills_manifest.py** | 主编排层验收 |
| phase/4b-prompts | `776761a` | 6 个子代理提示词(各 6.3~7.6K) + CONVERGENCE.md(6.8K) + DELEGATION.md(12.5K) | ORCHESTRATION.md |
| phase/5-workflow | `308b487` | ChapterWorkflowModels.kt(28.7K) | 状态机引擎/预算管理器/记忆接口/单测 |

> 注：3c 分支按其工作方式把 Phase 1 契约文件（NovelModels/NovelRepository）复制进了自己分支——合并时以 Phase 1 版本为准，属预期行为。

## 6. 云端构建链路（用户方针：本地不打包）

- **build.yml**：push/PR → main 或 feature/** 即触发云端构建。本次推送 main 后 Actions 将自动跑 `assembleDebug` + `testDebugUnitTest`（首次冷缓存约 30-50 分钟）
- **release.yml**：打 `v*` tag 时构建正式签名包，需在仓库 Settings → Secrets 配置：`ZORV_KEYSTORE_BASE64`、`ZORV_KEY_ALIAS`、`ZORV_STORE_PASS`、`ZORV_KEY_PASS`（详见 `.github/workflows/README.md`）
- 本地 SDK 已按用户指令卸载；`local.properties` 不入库

## 7. 已知问题与风险

1. **代理中断**：本轮 10 个子代理中，7a/0 完成，其余 8 个被用户叫停，产出为 WIP（见 §5），续跑时从各分支封存 commit 继续
2. **默认模型配额 429**：2026-10-17 09:54（GMT+8）重置；lite 模型可用（本轮 8 个 WIP 均为 lite 产出，续派可用）
3. **数据契约单一来源**：`NovelModels.kt`/`NovelRepository.kt` 以 Phase 1 分支为权威；Phase 2/3a/3b/3c/5 均按该契约开发，合并时若有漂移由主编排层统一
4. **工具注册表多分支加法行**：Phase 2/3a/3b 都会在基座注册表加行，合并时统一裁决
5. **WIP 未经编译验证**：本地无 SDK，全部代码正确性待云端 CI 首次构建检验
6. **3 个参考克隆**（ZorvAI/novelIDE/AIGC-NetNov）仅存本地 `/workspace/penai/repos/`，按用户指令**不入库**

## 8. 下一步计划（待用户指令）

1. 续跑 8 个 WIP 阶段（lite 或重置后的默认模型）至各自出口验收
2. 逐分支验收 → 合并 feature/penai → 推送 → 云端 CI 检验编译与单测
3. Phase 1 闭环打通后派发 Phase 6（UI 完整版：四 Tab/富文本 :novel-editor/关系图/统计）
4. Release Secrets 配置后打 tag 走云端出正式包

## 9. 规划文件索引（docs/plan-v1.1/）

| 文件 | 说明 |
|---|---|
| `PenAi软件改造计划书.docx` | 主交付物（12 章 21 表，文学出版风排版） |
| `plan.md` / `plan.html` | 规划书源稿 / 排版 HTML |
| `design_tokens.json` | 视觉设计令牌 |
| `draft_review.md` / `pipeline-state.yaml` | 评审记录 / 生成流水线状态 |
| `README.md` | 归档索引 |
| `scripts/`（4 个 py） | Stage2 再生/自检、Stage3 docx 后处理脚本 |
