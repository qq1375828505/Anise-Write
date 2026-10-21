package com.ai.assistance.quro.core.novel.graph.analysis

import com.ai.assistance.quro.core.novel.NovelChapterStatus
import com.ai.assistance.quro.core.novel.NovelWork
import com.ai.assistance.quro.core.novel.graph.NovelMemory
import com.ai.assistance.quro.core.novel.graph.NovelRelationship
import com.ai.assistance.quro.core.novel.graph.hook.NovelHook
import com.ai.assistance.quro.core.novel.graph.hook.NovelHookPriority
import com.ai.assistance.quro.core.novel.graph.hook.NovelHookStatus

/**
 * 图谱域一致性分析器（Phase 3c · novel_consistency_check 的规则引擎）。
 *
 * 纯 JVM、无 Android 依赖：输入全部为领域对象，输出结构化 [AnalysisReport]，
 * 工具层只做参数解析与 JSON 序列化。规则集映射规划书「分析域」的最小闭环，
 * 全部为**确定性规则**（不做 LLM 语义审查——那是后续派生子代理的活，见规划书 §6.6）。
 *
 * ## 规则集（v1）
 * | code | 级别 | 规则 |
 * |---|---|---|
 * | hook_dangling_chapter | WARN | 伏笔埋设/回收锚定的章节已不存在（被删除） |
 * | hook_idle | WARN | OPEN 伏笔自埋设起闲置章数 ≥ 阈值（按优先级降序，HIGH 先报） |
 * | chapter_status_wordcount | WARN | 章节标为 completed 但字数为 0（状态与产物矛盾） |
 * | relationship_unknown_character | INFO | 关系端点人物未在资料库建档（允许，仅提示） |
 * | memory_dangling_chapter | INFO | 记忆锚定的章节已不存在 |
 */
object NovelAnalyzer {

    /** 闲置伏笔默认告警阈值（章）。 */
    const val DEFAULT_IDLE_HOOK_THRESHOLD = 10

    /** 问题级别：error 阻断写作流（不该出现），warn 应尽快处理，info 仅提示。 */
    enum class Severity(val key: String) { ERROR("error"), WARN("warn"), INFO("info") }

    data class AnalysisIssue(
        val severity: Severity,
        val code: String,
        val message: String,
    )

    data class AnalysisReport(
        val workId: String,
        val issues: List<AnalysisIssue>,
        val checkedAt: Long,
        val idleHookThreshold: Int,
    ) {
        val errorCount: Int get() = issues.count { it.severity == Severity.ERROR }
        val warnCount: Int get() = issues.count { it.severity == Severity.WARN }
        val infoCount: Int get() = issues.count { it.severity == Severity.INFO }
    }

    /**
     * 跑全部规则。
     *
     * @param characterNames 资料库中已建档的人物名（novel_materials 的 character 类）
     * @param idleHookThreshold OPEN 伏笔闲置告警阈值（章）
     */
    fun analyze(
        work: NovelWork,
        hooks: List<NovelHook>,
        relationships: List<NovelRelationship>,
        memories: List<NovelMemory>,
        characterNames: Set<String>,
        idleHookThreshold: Int = DEFAULT_IDLE_HOOK_THRESHOLD,
    ): AnalysisReport {
        val issues = mutableListOf<AnalysisIssue>()
        val chapterIds = work.chapters.map { it.id }.toSet()
        val maxOrder = work.chapters.maxOfOrNull { it.order } ?: 0

        // R1 伏笔锚定的章节已删除
        hooks.forEach { h ->
            h.plantedAt?.takeIf { it.chapterId.isNotBlank() && it.chapterId !in chapterIds }?.let {
                issues += AnalysisIssue(
                    Severity.WARN, "hook_dangling_chapter",
                    "伏笔「${h.title}」的埋设章节已不存在（快照：${it.title}·第${it.order}章），台账失去回溯锚点。",
                )
            }
            h.resolvedAt?.takeIf { it.chapterId.isNotBlank() && it.chapterId !in chapterIds }?.let {
                issues += AnalysisIssue(
                    Severity.WARN, "hook_dangling_chapter",
                    "伏笔「${h.title}」的回收章节已不存在（快照：${it.title}·第${it.order}章）。",
                )
            }
        }

        // R2 OPEN 伏笔闲置（优先级高的排前面；idleChapters 由模型按当前最大章序计算）
        hooks.filter { it.status == NovelHookStatus.OPEN }
            .map { it to it.idleChapters(maxOrder) }
            .filter { (_, idle) -> idle != null && idle >= idleHookThreshold }
            .sortedWith(
                compareByDescending<Pair<NovelHook, Int?>> { it.first.priority.level }
                    .thenByDescending { it.second ?: 0 },
            )
            .forEach { (h, idle) ->
                issues += AnalysisIssue(
                    Severity.WARN, "hook_idle",
                    "伏笔「${h.title}」（${h.priority.label}优先级）已闲置 $idle 章未回收" +
                        "（埋于「${h.plantedAt?.title ?: "?"}」）——考虑在近几章安排回收，或明确废弃。",
                )
            }

        // R3 章节状态与产物矛盾
        work.chapters.filter { it.status == NovelChapterStatus.COMPLETED && it.wordCount <= 0 }
            .forEach { c ->
                issues += AnalysisIssue(
                    Severity.WARN, "chapter_status_wordcount",
                    "章节「${c.title}」已标为完稿但字数为 0——状态与产物矛盾，请补写正文或改回 draft。",
                )
            }

        // R4 关系端点未建档（INFO：允许引用未建档人物，写章时别张冠李戴即可）
        relationships.forEach { r ->
            listOf(r.fromName, r.toName).filter { it.isNotBlank() && it !in characterNames }
                .distinct()
                .forEach { name ->
                    issues += AnalysisIssue(
                        Severity.INFO, "relationship_unknown_character",
                        "关系「${r.fromName}—${r.type}—${r.toName}」中的「$name」未在资料库建档" +
                            "（novel_materials_add(type=character)）。",
                    )
                }
        }

        // R5 记忆锚定的章节已删除
        memories.forEach { m ->
            m.chapterRef?.takeIf { it.chapterId.isNotBlank() && it.chapterId !in chapterIds }?.let {
                issues += AnalysisIssue(
                    Severity.INFO, "memory_dangling_chapter",
                    "记忆「${m.content.take(30)}…」锚定的章节已不存在（快照：${it.title}·第${it.order}章）。",
                )
            }
        }

        // 稳定输出：级别 → code → 文本
        val order = mapOf(Severity.ERROR to 0, Severity.WARN to 1, Severity.INFO to 2)
        return AnalysisReport(
            workId = work.id,
            issues = issues.sortedWith(
                compareBy({ order[it.severity]!! }, { it.code }, { it.message }),
            ),
            checkedAt = System.currentTimeMillis(),
            idleHookThreshold = idleHookThreshold,
        )
    }

    /** 伏笔优先级的展示序（HIGH 最先提示回收）。 */
    fun priorityOrder(): List<NovelHookPriority> =
        listOf(NovelHookPriority.HIGH, NovelHookPriority.NORMAL, NovelHookPriority.LOW)
}
