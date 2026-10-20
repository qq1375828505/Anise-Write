package com.ai.assistance.quro.core.novel.workflow

import com.ai.assistance.quro.core.novel.countWords
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * PenAi Phase 5：`new_work_chapter` 七阶段章节生产工作流——核心模型。
 *
 * 流程（规划书 §5.3 功能矩阵「AI 全自动写一章」）：
 * 需求理解 → 资料收集 → 细纲生成 → 正文撰写 → 自审 → 润色 → 交付。
 *
 * 本文件只放**纯数据与契约**（无 Android 依赖、无 IO），保证 JVM 单测可直接构造；
 * 持久化在 [WorkflowStateStore]，编排在 [ChapterWorkflowEngine]。
 *
 * 三条设计铁律（继承基座，见 ENGINEERING_CONVENTIONS §3）：
 * 1. **无兜底**：解析/校验失败一律抛 [WorkflowException]，绝不返回假数据；
 * 2. **断点续跑**：每个阶段的状态变迁都落盘（[ChapterWorkflowState]），杀进程后可恢复；
 * 3. **显式契约**：每阶段的输入/输出、可重入/可跳过规则都编码在
 *    [ChapterWorkflowPhase] 与各输出数据类上，不靠约定俗成。
 */

// ─────────────────────────── 阶段定义 ───────────────────────────

/**
 * 七阶段枚举。字段即状态机的元数据，邻接关系见 [ChapterWorkflowEngine] 的转移表。
 *
 * @property reentrant 完成后是否允许在本工作流内**再次进入并覆盖产出**。
 *   自动流中唯一的回退边「自审不通过 → 回正文撰写重写」依赖此标记；
 *   用户显式 `restartFrom` 可从任意阶段重启（属重置操作，不受此标记约束）。
 * @property skippable 是否允许跳过。跳过后后续阶段按各阶段 KDoc 定义的替代行为运行
 *   （例：需求理解跳过 → 采用用户显式需求；自审跳过 → 视为审查通过放行）。
 */
enum class ChapterWorkflowPhase(
    val key: String,
    val label: String,
    val reentrant: Boolean,
    val skippable: Boolean,
    val order: Int,
) {
    /**
     * 需求理解：把用户的一句话需求展开为结构化写作目标。
     * 输入：作品信息 + 章节需求（[ChapterWorkflowRequest]）+ 长期记忆命中（设定/伏笔）。
     * 输出：[RequirementAnalysis]。
     * 跳过规则：skippable——用户已通过 [ChapterWorkflowRequest.explicitOutline]
     * 或明确的章节梗概表达意图时，直接采用用户需求作为分析结果。
     */
    REQUIREMENT_ANALYSIS("requirement", "需求理解", reentrant = false, skippable = true, order = 0),

    /**
     * 资料收集：按需求关键词检索角色/设定/地点等资料卡与长期记忆。
     * 输入：[RequirementAnalysis]。
     * 输出：[MaterialCollection]（允许为空集合——新书首章无前料是正常态）。
     * 跳过规则：skippable——跳过等价于空资料集，不阻塞下游。
     */
    MATERIAL_COLLECTION("materials", "资料收集", reentrant = false, skippable = true, order = 1),

    /**
     * 细纲生成：产出场景序列（每场景目标/冲突/钩子）。
     * 输入：[RequirementAnalysis] + [MaterialCollection] + 前文摘要 + 长期记忆。
     * 输出：[ChapterOutline]。
     * 跳过规则：**不可跳过**——「先细纲后正文」是人格卡创作纪律（规划书 §6.3）。
     * 可重入：用户不满可重新生成（revision +1，历史产出被覆盖）。
     */
    OUTLINE_GENERATION("outline", "细纲生成", reentrant = true, skippable = false, order = 2),

    /**
     * 正文撰写：按细纲 + 预算化上下文（细纲/前文摘要/相关资料/记忆）写正文。
     * 输入：[ChapterOutline] + 预算组装结果 + （回退重写时）上轮自审问题。
     * 输出：[ChapterDraft]。
     * 跳过规则：**不可跳过**（无正文则后续阶段无对象）。
     * 可重入：自审不通过回退重写（revision +1，受 [WorkflowConfig.maxRewriteRounds] 限制）。
     */
    CONTENT_WRITING("draft", "正文撰写", reentrant = true, skippable = false, order = 3),

    /**
     * 自审：一致性/爽点/水文维度的自我审查，产出问题清单与本章摘要。
     * 输入：[ChapterDraft] + [ChapterOutline]。
     * 输出：[SelfReview]；`passed=false` 时携带修订指令回退 [CONTENT_WRITING]。
     * 跳过规则：skippable——用户明示赶稿时视为放行（带病交付的责任在用户明示动作）。
     */
    SELF_REVIEW("review", "自审", reentrant = true, skippable = true, order = 4),

    /**
     * 润色：按自审问题对正文做精修（去 AI 味、节奏、文风统一）。
     * 输入：[ChapterDraft] + [SelfReview.issues]。
     * 输出：[PolishResult]；跳过时交付阶段回退使用 [ChapterDraft]。
     * 跳过规则：skippable。
     */
    POLISHING("polish", "润色", reentrant = true, skippable = true, order = 5),

    /**
     * 交付：把最终正文落盘（[ChapterDeliveryTarget]）并回写章节摘要到中期记忆。
     * 输入：最终正文（润色产出优先，否则正文稿）。
     * 输出：[DeliveryResult]。
     * 跳过规则：**不可跳过**（不落盘的工作流没有产出）。
     * 幂等：重复进入用同一 chapterId 覆盖写，不产生重复章节。
     */
    DELIVERY("delivery", "交付", reentrant = false, skippable = false, order = 6),
    ;

    companion object {
        /** 旧数据/损坏数据无匹配 key 时返回 null（由调用方显式处理，不猜阶段）。 */
        fun fromKey(key: String?): ChapterWorkflowPhase? =
            entries.firstOrNull { it.key == key }

        /** 线性主 flow（order 升序），供 UI 展示进度与状态图校验。 */
        val LINEAR_FLOW: List<ChapterWorkflowPhase> = entries.sortedBy { it.order }

        /** 线性流上的后置阶段；[DELIVERY] 之后无阶段（工作流完成）。 */
        fun linearNextOf(phase: ChapterWorkflowPhase): ChapterWorkflowPhase? =
            entries.filter { it.order > phase.order }.minByOrNull { it.order }
    }
}

// ─────────────────────────── 阶段输出契约 ───────────────────────────

/**
 * 阶段产出的统一标记。所有输出契约可 JSON 序列化（随 [ChapterWorkflowState] 落盘），
 * 解码由 [PhaseOutputParser] 按阶段分派（strict，无兜底）。
 */
sealed interface PhaseOutput {
    fun toJson(): JSONObject
}

/** 需求理解产出：结构化写作目标。 */
data class RequirementAnalysis(
    val chapterGoal: String,
    val coreEvents: List<String>,
    val keyCharacters: List<String>,
    val shuangdian: String,
    val constraints: List<String>,
    val targetWords: Int,
) : PhaseOutput {
    override fun toJson(): JSONObject = JSONObject().apply {
        put("chapter_goal", chapterGoal)
        put("core_events", JSONArray().also { a -> coreEvents.forEach { a.put(it) } })
        put("key_characters", JSONArray().also { a -> keyCharacters.forEach { a.put(it) } })
        put("shuangdian", shuangdian)
        put("constraints", JSONArray().also { a -> constraints.forEach { a.put(it) } })
        put("target_words", targetWords)
    }

    companion object {
        fun fromJson(o: JSONObject): RequirementAnalysis = RequirementAnalysis(
            chapterGoal = o.optString("chapter_goal", ""),
            coreEvents = o.optStringArray("core_events"),
            keyCharacters = o.optStringArray("key_characters"),
            shuangdian = o.optString("shuangdian", ""),
            constraints = o.optStringArray("constraints"),
            targetWords = o.optInt("target_words", 0),
        )
    }
}

/** 资料收集产出：与本章相关的资料卡引用（正文摘要由 Phase 3b 资料域填充）。 */
data class MaterialCollection(
    val items: List<MaterialRef>,
) : PhaseOutput {
    override fun toJson(): JSONObject = JSONObject().apply {
        put("items", JSONArray().also { a -> items.forEach { a.put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): MaterialCollection {
            val arr = o.optJSONArray("items") ?: JSONArray()
            val items = mutableListOf<MaterialRef>()
            for (i in 0 until arr.length()) {
                runCatching { arr.getJSONObject(i) }.getOrNull()?.let { items.add(MaterialRef.fromJson(it)) }
            }
            return MaterialCollection(items)
        }
    }
}

/** 单条资料引用（type 取值由 Phase 3b 八类资料枚举定义，本层只透传字符串）。 */
data class MaterialRef(
    val type: String,
    val refId: String,
    val title: String,
    val summary: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type)
        put("ref_id", refId)
        put("title", title)
        put("summary", summary)
    }

    companion object {
        fun fromJson(o: JSONObject): MaterialRef = MaterialRef(
            type = o.optString("type", ""),
            refId = o.optString("ref_id", ""),
            title = o.optString("title", ""),
            summary = o.optString("summary", ""),
        )
    }
}

/** 细纲产出：场景序列。 */
data class ChapterOutline(
    val scenes: List<OutlineScene>,
) : PhaseOutput {
    override fun toJson(): JSONObject = JSONObject().apply {
        put("scenes", JSONArray().also { a -> scenes.forEach { a.put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): ChapterOutline {
            val arr = o.optJSONArray("scenes") ?: JSONArray()
            val scenes = mutableListOf<OutlineScene>()
            for (i in 0 until arr.length()) {
                runCatching { arr.getJSONObject(i) }.getOrNull()?.let { scenes.add(OutlineScene.fromJson(it)) }
            }
            return ChapterOutline(scenes)
        }
    }
}

/** 细纲场景：目标（这个场景要达成什么）/冲突/钩子（留给后文的悬念）。 */
data class OutlineScene(
    val goal: String,
    val conflict: String,
    val hook: String,
    val summary: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("goal", goal)
        put("conflict", conflict)
        put("hook", hook)
        put("summary", summary)
    }

    companion object {
        fun fromJson(o: JSONObject): OutlineScene = OutlineScene(
            goal = o.optString("goal", ""),
            conflict = o.optString("conflict", ""),
            hook = o.optString("hook", ""),
            summary = o.optString("summary", ""),
        )
    }
}

/** 正文稿产出（Markdown 纯文本，与 Phase 1 章节 .md 文件同格式）。 */
data class ChapterDraft(
    val title: String,
    val content: String,
) : PhaseOutput {
    /** 字数口径与 Phase 1 数据层一致（[countWords]），避免双口径。 */
    val wordCount: Int get() = countWords(content)

    override fun toJson(): JSONObject = JSONObject().apply {
        put("title", title)
        put("content", content)
    }

    companion object {
        fun fromJson(o: JSONObject): ChapterDraft = ChapterDraft(
            title = o.optString("title", ""),
            content = o.optString("content", ""),
        )
    }
}

/** 自审产出：问题清单 + 放行结论 + 本章摘要（摘要交付后写入中期记忆）。 */
data class SelfReview(
    val passed: Boolean,
    val summary: String,
    val issues: List<ReviewIssue>,
) : PhaseOutput {
    override fun toJson(): JSONObject = JSONObject().apply {
        put("passed", passed)
        put("summary", summary)
        put("issues", JSONArray().also { a -> issues.forEach { a.put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): SelfReview {
            val arr = o.optJSONArray("issues") ?: JSONArray()
            val issues = mutableListOf<ReviewIssue>()
            for (i in 0 until arr.length()) {
                runCatching { arr.getJSONObject(i) }.getOrNull()?.let { issues.add(ReviewIssue.fromJson(it)) }
            }
            return SelfReview(
                passed = o.optBoolean("passed", false),
                summary = o.optString("summary", ""),
                issues = issues,
            )
        }
    }
}

/** 自审问题：type 取值 consistency（一致性）/shuangdian（爽点缺口）/water（水文）/style（文风）。 */
data class ReviewIssue(
    val type: String,
    val detail: String,
    val fix: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type)
        put("detail", detail)
        put("fix", fix)
    }

    companion object {
        fun fromJson(o: JSONObject): ReviewIssue = ReviewIssue(
            type = o.optString("type", ""),
            detail = o.optString("detail", ""),
            fix = o.optString("fix", ""),
        )
    }
}

/** 润色产出：精修后正文 + 变更说明（审计用，不进正文）。 */
data class PolishResult(
    val content: String,
    val changes: List<String>,
) : PhaseOutput {
    override fun toJson(): JSONObject = JSONObject().apply {
        put("content", content)
        put("changes", JSONArray().also { a -> changes.forEach { a.put(it) } })
    }

    companion object {
        fun fromJson(o: JSONObject): PolishResult = PolishResult(
            content = o.optString("content", ""),
            changes = o.optStringArray("changes"),
        )
    }
}

/** 交付产出：落盘结果回执（无 LLM 参与，由引擎写实）。 */
data class DeliveryResult(
    val chapterId: String,
    val chapterTitle: String,
    val wordCount: Int,
    val createdChapter: Boolean,
) : PhaseOutput {
    override fun toJson(): JSONObject = JSONObject().apply {
        put("chapter_id", chapterId)
        put("chapter_title", chapterTitle)
        put("word_count", wordCount)
        put("created_chapter", createdChapter)
    }

    companion object {
        fun fromJson(o: JSONObject): DeliveryResult = DeliveryResult(
            chapterId = o.optString("chapter_id", ""),
            chapterTitle = o.optString("chapter_title", ""),
            wordCount = o.optInt("word_count", 0),
            createdChapter = o.optBoolean("created_chapter", false),
        )
    }
}

// ─────────────────────────── 工作流状态 ───────────────────────────

/** 工作流请求（用户侧输入快照，随状态持久化，恢复时不依赖外部传入）。 */
data class ChapterWorkflowRequest(
    val workId: String,
    val volumeId: String,
    /** 已有章节（续写/重写）；null = 交付时新建章节。 */
    val chapterId: String? = null,
    /** 用户章节需求（一句话梗概/指令），需求理解阶段的原料。 */
    val requirement: String,
    /** 目标字数（网文章节常规 2000-3000）。 */
    val targetWords: Int = 3000,
    /** 用户直接给定的细纲文本；非空时需求理解阶段可跳过。 */
    val explicitOutline: String? = null,
    /** 作品名快照（提示词用，避免引擎反向查询仓库）。 */
    val workTitle: String = "",
    /** 流派快照（提示词用）。 */
    val genre: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("work_id", workId)
        put("volume_id", volumeId)
        put("chapter_id", chapterId ?: JSONObject.NULL)
        put("requirement", requirement)
        put("target_words", targetWords)
        put("explicit_outline", explicitOutline ?: JSONObject.NULL)
        put("work_title", workTitle)
        put("genre", genre)
    }

    companion object {
        fun fromJson(o: JSONObject): ChapterWorkflowRequest = ChapterWorkflowRequest(
            workId = o.optString("work_id", ""),
            volumeId = o.optString("volume_id", ""),
            chapterId = o.optStringOrNull("chapter_id"),
            requirement = o.optString("requirement", ""),
            targetWords = o.optInt("target_words", 3000),
            explicitOutline = o.optStringOrNull("explicit_outline"),
            workTitle = o.optString("work_title", ""),
            genre = o.optString("genre", ""),
        )
    }
}

/** 工作流级状态。 */
enum class ChapterWorkflowStatus(val key: String) {
    /** 推进中（含阶段失败但重试预算未耗尽的中间态，见阶段记录）。 */
    RUNNING("running"),

    /** 终止于失败：某阶段重试预算耗尽，断点已保留，修复后可 [ChapterWorkflowEngine.resume]。 */
    FAILED("failed"),

    /** 交付完成（终态）。 */
    COMPLETED("completed"),

    /** 用户取消（终态）。 */
    CANCELLED("cancelled"),
    ;

    companion object {
        fun fromKey(key: String?): ChapterWorkflowStatus? =
            entries.firstOrNull { it.key == key }
    }
}

/** 阶段记录状态。 */
enum class PhaseRecordStatus(val key: String) {
    PENDING("pending"),
    RUNNING("running"),
    COMPLETED("completed"),
    /** 重试预算耗尽后的失败；断点保留在本记录内（attempts/error）。 */
    FAILED("failed"),
    /** 用户/引擎显式跳过（仅 [ChapterWorkflowPhase.skippable] 阶段可达）。 */
    SKIPPED("skipped"),
    ;

    companion object {
        fun fromKey(key: String?): PhaseRecordStatus? =
            entries.firstOrNull { it.key == key }
    }
}

/**
 * 单阶段执行记录（断点续跑的最小单元）。
 *
 * @property attempts 累计尝试次数（跨 resume 累计；resume 时重置为 0，见引擎 KDoc）。
 * @property revision 重入覆盖次数（回退重写/重新生成计次，审计用）。
 * @property output 阶段产出契约（COMPLETED/SKIPPED 时为 null 或替代产出）。
 * @property error 最近一次失败原因（retry 时回注进提示词做修正通道）。
 */
data class WorkflowPhaseRecord(
    val phase: ChapterWorkflowPhase,
    val status: PhaseRecordStatus,
    val attempts: Int = 0,
    val revision: Int = 0,
    val output: PhaseOutput? = null,
    val error: String? = null,
    val updatedAt: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("phase", phase.key)
        put("status", status.key)
        put("attempts", attempts)
        put("revision", revision)
        put("output", output?.toJson() ?: JSONObject.NULL)
        put("error", error ?: JSONObject.NULL)
        put("updatedAt", updatedAt)
    }

    companion object {
        /**
         * 从 JSON 恢复记录。output 缺失/损坏时**不猜**：记录降级为 PENDING
         * （该阶段将被重新执行），phase/status 键缺失才返回 null（整条丢弃）。
         */
        fun fromJson(o: JSONObject): WorkflowPhaseRecord? {
            val phase = ChapterWorkflowPhase.fromKey(o.optString("phase", "")) ?: return null
            val status = PhaseRecordStatus.fromKey(o.optString("status", "")) ?: PhaseRecordStatus.PENDING
            val outputJson = o.optJSONObject("output")
            val output = outputJson?.let { PhaseOutputCodec.decodeOrNull(phase, it) }
            val effectiveStatus = if (status == PhaseRecordStatus.COMPLETED && output == null) {
                // 有完成态无产出 = 旧数据损坏，降级重新执行（不返回假产出）
                PhaseRecordStatus.PENDING
            } else {
                status
            }
            return WorkflowPhaseRecord(
                phase = phase,
                status = effectiveStatus,
                attempts = o.optInt("attempts", 0),
                revision = o.optInt("revision", 0),
                output = output,
                error = o.optStringOrNull("error"),
                updatedAt = o.optLong("updatedAt", 0L),
            )
        }
    }
}

/**
 * 工作流完整状态（断点续跑载体，整体落盘 `novel/workflows/{workflowId}.json`）。
 *
 * @property rewriteRounds 自审不通过→回退重写的累计轮数（受配置上限约束）。
 * @property phaseRecords 固定 7 条（[ChapterWorkflowPhase.LINEAR_FLOW] 顺序），
 *   恢复时按 phase 键对齐，缺条补 PENDING。
 */
data class ChapterWorkflowState(
    val workflowId: String,
    val workId: String,
    val volumeId: String,
    val chapterId: String?,
    val request: ChapterWorkflowRequest,
    val status: ChapterWorkflowStatus,
    val currentPhase: ChapterWorkflowPhase,
    val phaseRecords: List<WorkflowPhaseRecord>,
    val rewriteRounds: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
) {
    init {
        require(phaseRecords.size == ChapterWorkflowPhase.LINEAR_FLOW.size) {
            "阶段记录必须完整覆盖七阶段，实际 ${phaseRecords.size} 条"
        }
    }

    fun recordOf(phase: ChapterWorkflowPhase): WorkflowPhaseRecord =
        phaseRecords.first { it.phase == phase }

    /** 取某阶段已完成产出；未完成/已跳过返回 null（调用方显式处理缺失）。 */
    @Suppress("UNCHECKED_CAST")
    fun <T : PhaseOutput> completedOutput(phase: ChapterWorkflowPhase): T? =
        recordOf(phase).let { if (it.status == PhaseRecordStatus.COMPLETED) it.output as T? else null }

    fun isTerminal(): Boolean =
        status == ChapterWorkflowStatus.COMPLETED || status == ChapterWorkflowStatus.CANCELLED

    fun toJson(): JSONObject = JSONObject().apply {
        put("version", WorkflowStateFormat.VERSION)
        put("workflow_id", workflowId)
        put("work_id", workId)
        put("volume_id", volumeId)
        put("chapter_id", chapterId ?: JSONObject.NULL)
        put("request", request.toJson())
        put("status", status.key)
        put("current_phase", currentPhase.key)
        put("rewrite_rounds", rewriteRounds)
        put("phase_records", JSONArray().also { a -> phaseRecords.forEach { a.put(it.toJson()) } })
        put("created_at", createdAt)
        put("updated_at", updatedAt)
    }

    companion object {
        /**
         * 从 JSON 恢复状态。version 高于当前实现时返回 null（未来格式不猜），
         * 由调用方决定保留文件或提示用户升级。
         */
        fun fromJson(o: JSONObject): ChapterWorkflowState? {
            val version = o.optInt("version", 0)
            if (version > WorkflowStateFormat.VERSION) return null
            val workflowId = o.optString("workflow_id", "")
            if (workflowId.isBlank()) return null
            val status = ChapterWorkflowStatus.fromKey(o.optString("status", ""))
                ?: return null
            val currentPhase = ChapterWorkflowPhase.fromKey(o.optString("current_phase", ""))
                ?: return null
            val recordsArr = o.optJSONArray("phase_records") ?: JSONArray()
            val records = mutableMapOf<ChapterWorkflowPhase, WorkflowPhaseRecord>()
            for (i in 0 until recordsArr.length()) {
                runCatching { recordsArr.getJSONObject(i) }
                    .getOrNull()
                    ?.let { runCatching { WorkflowPhaseRecord.fromJson(it) }.getOrNull() }
                    ?.let { records[it.phase] = it }
            }
            // 缺条补 PENDING（自愈：半截状态文件恢复成完整七阶段记录）
            val fullRecords = ChapterWorkflowPhase.LINEAR_FLOW.map { phase ->
                records[phase] ?: WorkflowPhaseRecord(phase, PhaseRecordStatus.PENDING)
            }
            return ChapterWorkflowState(
                workflowId = workflowId,
                workId = o.optString("work_id", ""),
                volumeId = o.optString("volume_id", ""),
                chapterId = o.optStringOrNull("chapter_id"),
                request = o.optJSONObject("request")?.let { ChapterWorkflowRequest.fromJson(it) }
                    ?: return null,
                status = status,
                currentPhase = currentPhase,
                phaseRecords = fullRecords,
                rewriteRounds = o.optInt("rewrite_rounds", 0),
                createdAt = o.optLong("created_at", 0L),
                updatedAt = o.optLong("updated_at", 0L),
            )
        }
    }
}

// ─────────────────────────── 交付契约 ───────────────────────────

/** 交付目标确保章节存在后的回执（order 用于中期记忆的章节排序）。 */
data class EnsuredChapter(
    val chapterId: String,
    val order: Int,
    val created: Boolean,
)

/**
 * 章节落盘契约（Phase 5 不直接依赖 [com.ai.assistance.quro.core.novel.NovelRepository]，
 * 避免把 Android 依赖带进 JVM 可测的核心；适配器见 WorkflowEnvironment.kt）。
 */
interface ChapterDeliveryTarget {
    /** 章节不存在则按 title 新建；存在则原样返回。 */
    fun ensureChapter(workId: String, volumeId: String, chapterId: String?, title: String): EnsuredChapter

    /** 写正文，返回计数字数（口径 = [countWords]）。 */
    fun writeChapter(workId: String, chapterId: String, content: String): Int
}

// ─────────────────────────── 错误模型 ───────────────────────────

/** 工作流错误码（UI 层按 code 本地化，不依赖 message 文本）。 */
enum class WorkflowError(val code: String) {
    /** 状态不允许该操作（如在终态上 advance）。 */
    INVALID_STATE("invalid_state"),

    /** 阶段不可跳过。 */
    NOT_SKIPPABLE("not_skippable"),

    /** 阶段不可重入。 */
    NOT_REENTRANT("not_reentrant"),

    /** 工作流不可恢复（终态或断点信息不足）。 */
    NOT_RESUMABLE("not_resumable"),

    /** LLM 调用失败（网络/超时/假实现未编排）。 */
    LLM_FAILED("llm_failed"),

    /** LLM 输出解析失败（缺 JSON 块/字段缺失）。 */
    PARSE_FAILED("parse_failed"),

    /** 业务校验失败（正文过短/细纲为空等契约级下限）。 */
    VALIDATION_FAILED("validation_failed"),

    /** 落盘失败。 */
    DELIVERY_FAILED("delivery_failed"),

    /** 阶段重试预算耗尽。 */
    ATTEMPTS_EXHAUSTED("attempts_exhausted"),
}

/** 工作流异常：所有失败路径显式抛出，禁止 catch 后返回假数据（ENGINEERING_CONVENTIONS §3）。 */
class WorkflowException(
    val error: WorkflowError,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

// ─────────────────────────── 内部工具 ───────────────────────────

/** 工作流状态文件版本：写入 version 字段，读取时高于此版本则拒绝解析（防旧数据错读）。 */
object WorkflowStateFormat {
    const val VERSION = 1
}

/** 阶段产出的 JSON 编解码分派（strict：phase 与产出类型不匹配抛 [WorkflowException]）。 */
object PhaseOutputCodec {
    fun encode(output: PhaseOutput): JSONObject = output.toJson()

    /** 解码；类型不匹配/字段损坏返回 null（调用方降级该阶段为 PENDING 重跑）。 */
    fun decodeOrNull(phase: ChapterWorkflowPhase, o: JSONObject): PhaseOutput? =
        runCatching {
            when (phase) {
                ChapterWorkflowPhase.REQUIREMENT_ANALYSIS -> RequirementAnalysis.fromJson(o)
                ChapterWorkflowPhase.MATERIAL_COLLECTION -> MaterialCollection.fromJson(o)
                ChapterWorkflowPhase.OUTLINE_GENERATION -> ChapterOutline.fromJson(o)
                ChapterWorkflowPhase.CONTENT_WRITING -> ChapterDraft.fromJson(o)
                ChapterWorkflowPhase.SELF_REVIEW -> SelfReview.fromJson(o)
                ChapterWorkflowPhase.POLISHING -> PolishResult.fromJson(o)
                ChapterWorkflowPhase.DELIVERY -> DeliveryResult.fromJson(o)
            }
        }.getOrNull()
}

/** org.json 无 optStringArray/null 语义封装：JSONArray → List<String>；null/非字符串元素跳过。 */
internal fun JSONObject.optStringArray(key: String): List<String> {
    val arr = optJSONArray(key) ?: return emptyList()
    val out = mutableListOf<String>()
    for (i in 0 until arr.length()) {
        runCatching { arr.getString(i) }.getOrNull()?.let { out.add(it) }
    }
    return out
}

/** org.json optString 对 JSONObject.NULL 返回 "null" 字符串，这里显式映射为 null。 */
internal fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key, "").ifBlank { null }

/** 新工作流 ID（UUID，与基座各仓库的 ID 生成口径一致）。 */
internal fun newWorkflowId(): String = UUID.randomUUID().toString()
