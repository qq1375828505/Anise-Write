# PenAi 工程恢复方案与进度总表

> 快照：2026-10-20 | 产出：主编排层 | 仓库：`qq1375828505/PenAi`
> 约束红线：**本地不构建、不产出 APK**（编译打包只走 GitHub Actions）；**不接触任何签名私钥材料**。

---

## 1. 一句话现状

云端 CI 三连败的**根因已定位并修复**（已推 main，云端验证中）；8 个阶段分支全是「WIP 封存」、**从未经过任何编译验证**；各阶段 `novel_*` 工具代码已写完但**一个都没挂载**（注册表加法行 = 0 行）。

---

## 2. 工程进度总表

| 分支 | 已交付 | 状态 | 缺口 |
|---|---|---|---|
| `phase/1-minimal-loop` | `NovelModels.kt`(177行) `NovelRepository.kt`(272行) `NovelDesignTokens.kt`(95行) | ✅ 数据契约权威版 | 无 |
| `phase/2-ai-loop` | `NovelWriteTools.kt`（`novel_write` `novel_continue` `novel_polish`）+ `prompts/penai-novel/{persona,style_tokens}.md` | ⚠️ 工具未挂载 | 注册表加法行 |
| `phase/3a-structure` | `NovelStructureTools.kt`（10 个工具） | ⚠️ 未挂载 | 约定 13 工具，**欠 3 个** |
| `phase/3b-assets` | `NovelAssetTools.kt`（6 个工具）+ `core/novel/asset/*` | ⚠️ 未挂载 | 约定 8 类，**欠 2 类** |
| `phase/3c-graph` | `core/novel/graph/{ChapterRef,hook/NovelHook}.kt` + 契约文件 | ⚠️ 未挂载 | 伏笔/关系/分析/记忆域工具未见 |
| `phase/4a-skills` | 25 流派技能包（28 文件 / 2446 行）`assets/skills/penai/*` | ✅ 纯资源 | 无需注册 |
| `phase/4b-prompts` | 6 子代理提示词（8 文件 / 1167 行）`assets/prompts/subagents/*` | ✅ 纯资源 | 无需注册 |
| `phase/5-workflow` | `ChapterWorkflowModels.kt`（687 行 / 20 类型） | ⚠️ 自建地基 | 与 Phase 1 契约零交集 |
| `phase/7a-ci` | `build.yml` `release.yml` + 5 轮排障 | 🔧 本轮再修 | 云端验证中 |

---

## 3. CI 根因与修复（已落地）

**失败现象**：最近 3 次 run 全红，失败步骤固定在「构建 Debug APK」：

```
could not resolve plugin artifact 'com.google.devtools.ksp:com.google.devtools.ksp.gradle.plugin:2.3.9'
```

**排障过程**：
1. 前 8 步全绿（检出 / JDK 17 / Android SDK / 缓存 / Gradle / 占位签名）→ 排除环境、密钥、缓存
2. 实测 KSP 2.3.9 **确实存在**（Maven Central HTTP 200，版本线 2.3.0–2.3.12）→ 排除版本号写错
3. 阿里云 gradle-plugin / public、pluginPortal、repo1 **此刻全部能拉到** → 排除源缺包
4. 定性：Gradle 插件解析时，**仓库返回非 404 错误（超时/5xx）会直接中止、不再回退下一个仓库**；`pluginManagement` 把阿里云排在最前，GitHub 境外 runner 访问国内镜像一抖动即整条链崩掉
5. 前序 `942f52f` 其实已触到同一根因（「KSP 2.3.9 实际存在，前轮网络抖动被缓存为缺失」），但只加 `--refresh-dependencies`，没根治「不回退」

**修复**（commit `4084412`，已推 main）：`pluginManagement.repositories` 改为**官方源前置**（`google()` → `gradlePluginPortal()` → `mavenCentral()`），阿里云三个镜像**降为兜底**保留（保证国内直连场景仍可用）。

**验证**：新 run `head_sha=4084412` 已触发，状态 `in_progress`。构建链现状：JDK 17 + cmdline-tools 直装 SDK/NDK 27 + `gradle-wrapper.jar` 已入库 + 自动生成**占位 keystore 只出 Debug 包**（不碰真签名）+ 跑 `:app:testDebugUnitTest` + 上传 APK 与测试报告。冷缓存 30–50 分钟，命中缓存 10–20 分钟。

---

## 4. 待办一：工具注册表加法行（最大缺口）

**事实**：6 个分支在 `QuroTool.kt` 上的新增行 **= 0**。工具类写完了但没挂载 → 用户调不出来。

**基座挂载模式**（已验证）：

```kotlin
// core/cluster/ClusterTools.kt
object ClusterToolSet {
    fun registerAll(registry: QuroToolRegistry) {
        registry.register(ClusterStartTool())
        ...
    }
}
```

注释明确：*「在 QuroBuiltInTools 的注册处加一行即可：`ClusterToolSet.registerAll(QuroToolRegistry.active!!)`」*

**PenAi 应照抄该模式**：

1. 新增 `core/tools/novel/NovelToolSet.kt`：

```kotlin
object NovelToolSet {
    fun registerAll(registry: QuroToolRegistry) {
        registry.register(NovelWriteTool())
        registry.register(NovelContinueTool())
        // ... phase/3a、3b、3c 的工具
    }
}
```

2. **加法行落点**：`core/tools/QuroBuiltInTools.kt` 的注册处加一行
   `NovelToolSet.registerAll(QuroToolRegistry.active!!)`
3. `QuroTool.kt` 的 `coreNames` 集合加入对应 `novel_*` 名字（该集合是「关掉完整工具集」时的下发集，漏了会导致工具被**静默丢弃**）

**工具清单（当前已实现，待挂载）**：

| 分支 | 工具名 |
|---|---|
| phase/2 | `novel_write` `novel_continue` `novel_polish` |
| phase/3a | `novel_list_works` `novel_create_work` `novel_delete_work` `novel_get_outline` `novel_save_outline` `novel_create_volume` `novel_delete_volume` `novel_create_chapter` `novel_delete_chapter` `novel_chapter_stats` |
| phase/3b | `novel_materials_get` `novel_materials_list` `novel_materials_search` `novel_materials_add` `novel_materials_update` `novel_materials_delete` |

**跨分支冲突裁决**：以上 19 个工具名**互不重复**，无同名撞车；`coreNames` 计数口径需同步更新（基座约 248 注册 / 203 coreNames / 154 ALWAYS_ON）。

---

## 5. 待办二：Phase 5 分层与 Phase 1 契约对齐

Phase 5 的 `ChapterWorkflowModels.kt` 自建 20 个类型，与 Phase 1 契约**零交集**（两套地基）。建议分层：

| 类型 | 裁决 | 理由 |
|---|---|---|
| `ChapterDraft` `EnsuredChapter` | **改为引用** `NovelChapter` / `NovelVolume` | 承载作品实体，不该另起炉灶 |
| `ChapterOutline` `OutlineScene` | ⚠️ **与 phase/3a 重复** | 3a 已有 `novel_get_outline`/`novel_save_outline`，需与 3a 统一大纲模型 |
| `MaterialRef` `MaterialCollection` | ⚠️ **与 phase/3b 重复** | 3b 已有资料域模型与工具，需统一 |
| `ChapterWorkflowStatus` | **保留**但改名 | 与 `NovelChapterStatus`（章节状态机）语义不同，同名易混，建议 `WorkflowRunStatus` |
| 其余 13 个（`ChapterWorkflowPhase` `RequirementAnalysis` `SelfReview` `ReviewIssue` `PolishResult` `DeliveryResult` `ChapterWorkflowRequest` `PhaseRecordStatus` `WorkflowPhaseRecord` `ChapterWorkflowState` `ChapterDeliveryTarget` `WorkflowError` `PhaseOutputCodec`） | **保留**为 workflow 层自有 | 纯流程编排，不承载作品实体 |

**重要澄清**：此前一度统计出「6 个分支契约漂移 164/247 行」，实为 `docs/progress/wip/` 下的**阶段快照副本**造成的路径统计失真；`git diff` 显示为 `deleted file mode`——那些分支正式代码路径下并无契约文件。**唯一真正携带契约文件的是 `phase/3c-graph`，且与 Phase 1 零差异 → 合并无冲突。** 契约漂移实际不成立。

---

## 6. 建议合并顺序

1. `phase/1-minimal-loop`（数据契约，先行）
2. `phase/7a-ci` 已在 main（CI 通道先绿）
3. `phase/2-ai-loop`（3 工具，最小验证挂载链路）
4. `phase/3a-structure` → `phase/3b-assets` → `phase/3c-graph`（模型域，依赖契约）
5. `phase/4a-skills` → `phase/4b-prompts`（纯资源，与代码解耦，可并行）
6. `phase/5-workflow`（**最后**，需先完成 §5 的分层对齐）

---

## 7. 风险与红线

- ⚠️ **WIP 全部未经编译验证**——本地无 SDK，唯一检验通道是云端 CI；任何合并后都必须等 CI 绿
- ⚠️ 每次 CI 全量构建 30–50 分钟，**修复迭代成本高**，务必先本地静态自检再推
- 🔴 不得在本地执行 gradle / 不得产出 APK
- 🔴 不得读取或输出 `keystore.properties` / `*.jks` / `*.p12` / `*.keystore` / `*.pem`
- 🔴 宿主与插件必须同签名，签名统一走 `scripts/sign.sh`


---

## 8. Phase 5 契约对齐 · 逐类型裁决表（本轮补充）

> Phase 5 的 `ChapterWorkflowModels.kt` 自建 20 个类型。按「是否承载作品实体」分层裁决如下。

| 类型 | 裁决 | 对象 | 理由 |
|---|---|---|---|
| `ChapterDraft` | **改为引用** | `NovelChapter` + `NovelVolume` | 承载章节实体，正文与元数据应落 Phase 1 的章节模型，避免第二套章节真相 |
| `EnsuredChapter` | **改为引用** | `NovelChapter` | 「已就绪章节」本质是章节实体引用 |
| `ChapterOutline` | ⚠️ **与 phase/3a 重复** | 需与 `novel_get_outline`/`novel_save_outline` 统一 | 3a 已有大纲存取工具；两处大纲模型必须同源，否则读写不同步 |
| `OutlineScene` | ⚠️ **与 phase/3a 重复** | 同上 | 大纲场景结构应挂在 3a 的大纲模型下 |
| `MaterialRef` | ⚠️ **与 phase/3b 重复** | `asset.NovelAsset` | 3b 已有资料模型与仓储，Phase 5 不应另立一套引用结构 |
| `MaterialCollection` | ⚠️ **与 phase/3b 重复** | `NovelAssetRepository` | 同上 |
| `ChapterWorkflowStatus` | **保留但改名** | 建议 `WorkflowRunStatus` | 与 `NovelChapterStatus`（章节状态机）语义不同，同名易混 |
| `ChapterWorkflowPhase` | 保留 | — | 七阶段流程枚举，属 workflow 层 |
| `RequirementAnalysis` | 保留 | — | 需求分析产物，流程中间态 |
| `SelfReview` `ReviewIssue` | 保留 | — | 自检/审读产物，流程中间态 |
| `PolishResult` `DeliveryResult` | 保留 | — | 润色/交付产物，流程中间态 |
| `ChapterWorkflowRequest` `ChapterWorkflowState` | 保留 | — | 流程入参与运行态 |
| `PhaseRecordStatus` `WorkflowPhaseRecord` | 保留 | — | 阶段记录与状态，属流程 |
| `ChapterDeliveryTarget` | 保留 | — | 交付目标接口，属流程 |
| `WorkflowError` `PhaseOutputCodec` | 保留 | — | 错误枚举与编解码，属流程 |

**落地最小改动（按改动量升序）**：
1. `ChapterWorkflowStatus` → 重命名 `WorkflowRunStatus`（纯改名，低风险）
2. `ChapterDraft` / `EnsuredChapter` 的字段改为携带 `NovelChapter` / `NovelVolume` 引用，删除自建章节字段（中等）
3. `ChapterOutline` / `OutlineScene` 与 phase/3a 大纲模型二选一（跨分支，建议由 3a 主导，Phase 5 引用）
4. `MaterialRef` / `MaterialCollection` 改为引用 phase/3b 的 `asset.NovelAsset` / `NovelAssetRepository`（跨分支，3b 主导）

**执行顺序建议**：先做 1（无跨分支依赖）→ 再做 2 → 最后做 3、4（需 3a/3b 先合）。

---

## 9. 本轮（2026-10-20）实况

- ✅ **CI 根因修复已推 main**（`4084412`）：`pluginManagement` 官方源前置、阿里云降兜底
- ✅ **`.git` 分离到内部文件系统**：Android FUSE 不支持 git 的「临时文件+rename」写 object，导致
  `/sdcard` 上的仓库无法 `git add/commit`；已把 `.git` 迁到 `/root/penai-git` 并以 `gitdir:` 指针接回，
  worktree 仍在 `/sdcard/Ai/PenAi`（用户在工具箱里照常可见可编辑）
- ✅ **8 个阶段分支全部零冲突合并**（`feature/penai`，53 文件 / 8666 行），逐文件核实无遗漏
- ✅ **19 个 `novel_*` 工具已挂载**：新增 `core/tools/novel/NovelToolSet.kt`（照抄基座 `ClusterToolSet` 模式），
  `QuroBuiltInTools.kt` 加一行 `NovelToolSet.registerAll(r)`，`QuroTool.kt` 的 `coreNames` 补 19 个名字
  （不补会被静默丢弃）。静态自检通过：类定义全部命中、工具名无重复、与基座无撞名、契约符号全部可解析
- ⏳ **云端 CI 双线在跑**：`main`（验证仓库顺序修复）与 `feature/penai`（验证合并+挂载）
- ⚠️ **未解决**：`phase/3a` 只产出 `NovelStructureTools.kt`（10 工具），约定为 13 工具，**欠 3 个**；
  `phase/3b` 6 工具，约定 8 类，**欠 2 类**；`phase/3c` 伏笔/关系/分析/记忆域工具未见
