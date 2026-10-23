package com.ai.assistance.quro.core.novel.graph.memory

/**
 * 文本轨记忆（P1-4 双轨记忆 · 文本轨）：把整部作品的当前状态压成**十节**纯文本快照，供 AI 上下文回灌。
 *
 * 逐节移植自 novelIDE `novel_memory.dart` 的 `_generateContent()`（十节顺序、节点层级、
 * 「请勿手动编辑」页眉、页脚时间戳一致，见 [SECTION_TITLES]）；本仓各处差异：
 * - 章节没有独立 summary 字段：第三节优先用调用方给的摘要，缺省退回「标题 + 字数 + 状态」；
 * - 卷/章结构化信息取自 Phase 1 结构域（[NovelWork]），不读正文文件（正文由 novel_query_previous 按需检索）；
 * - 构建输入是**纯数据**（[Input]），取数由 tools 层的 provider 负责，故本文件零 Android 依赖、可 JVM 单测。
 *
 * 缓存：与 Dart 版同为**进程内单例 + 5 分钟 TTL**，键是作品 id；任何写入（存记忆、改章节、改资料）
 * 后调用 [NovelTextMemoryCache.invalidate] 立即失效，避免 AI 拿到过期快照。
 */
object NovelTextMemory {

    /** 十节标题（顺序即快照顺序）。 */
    val SECTION_TITLES: List<String> = listOf(
        "1. 作品信息",
        "2. 卷章结构",
        "3. 最近章节",
        "4. 角色状态",
        "5. 设定状态",
        "6. 地点",
        "7. 势力",
        "8. 重要道具",
        "9. 伏笔追踪",
        "10. 参考资料",
    )

    /** 第三节取最近几章（Dart 版同为 5）。 */
    const val RECENT_CHAPTER_LIMIT = 5

    /** 快照缓存 TTL：5 分钟（Dart `_cacheTtl`）。 */
    const val CACHE_TTL_MS = 5L * 60L * 1000L

    /** 单条资料摘要截断长度（快照是「索引」不是「档案」，全文另有工具按需取）。 */
    const val EXCERPT_LIMIT = 120

    /** 伏笔闲置多少章后打警告标记（Dart 版同为 10）。 */
    const val HOOK_IDLE_WARN = 10

    // ─────────────────────────── 输入（纯数据） ───────────────────────────

    /** 作品头信息（对应 Dart 版第 1 节）。 */
    data class WorkInfo(
        val id: String,
        val title: String,
        val genre: String = "",
        val totalWordCount: Int = 0,
        val chapterCount: Int = 0,
        val description: String = "",
    )

    /** 章节行（第 2 节）。 */
    data class ChapterLine(
        val order: Int,
        val title: String,
        val wordCount: Int = 0,
        val status: String = "",
        /** 可选摘要（第 3 节用；无则退回标题行）。 */
        val summary: String = "",
    )

    /** 卷块（第 2 节）。 */
    data class VolumeBlock(
        val title: String,
        val summary: String = "",
        val chapters: List<ChapterLine> = emptyList(),
    )

    /** 角色行（第 4 节）。 */
    data class CharacterLine(
        val name: String,
        val role: String = "",
        val description: String = "",
        val personality: String = "",
        val background: String = "",
        val tags: List<String> = emptyList(),
    )

    /** 通用资料行（第 5~8 节共用：设定 / 地点 / 势力 / 道具）。 */
    data class AssetLine(
        val name: String,
        val category: String = "",
        val description: String = "",
        /** 附加键值（如 首领=…、品阶=…、规则=…），按顺序渲染成「键: 值」。 */
        val extras: List<Pair<String, String>> = emptyList(),
        /** 道具专用：是否关键道具（渲染 ⭐，同 Dart 版）。 */
        val key: Boolean = false,
    )

    /** 伏笔行（第 9 节）。 */
    data class HookLine(
        val title: String,
        val description: String = "",
        val resolved: Boolean = false,
        val idleChapters: Int = 0,
    )

    /** 参考资料行（第 10 节）。 */
    data class ReferenceLine(
        val title: String,
        val source: String = "",
    )

    /** 快照构建输入（十节齐全）。 */
    data class Input(
        val work: WorkInfo,
        val volumes: List<VolumeBlock> = emptyList(),
        val looseChapters: List<ChapterLine> = emptyList(),
        val characters: List<CharacterLine> = emptyList(),
        val settings: List<AssetLine> = emptyList(),
        val locations: List<AssetLine> = emptyList(),
        val factions: List<AssetLine> = emptyList(),
        val items: List<AssetLine> = emptyList(),
        val hooks: List<HookLine> = emptyList(),
        val references: List<ReferenceLine> = emptyList(),
        val updatedAt: Long = 0L,
    )

    // ─────────────────────────── 生成 ───────────────────────────

    /** 生成十节快照（节序、缩进、符号与 Dart 版一致）。 */
    fun build(input: Input, now: Long = System.currentTimeMillis()): String {
        val buf = StringBuilder()
        buf.append("╔══════════════════════════════════════════╗").append('\n')
        buf.append("║     小说记忆文件 (Novel Memory File)      ║").append('\n')
        buf.append("║  此文件由系统自动生成，请勿手动编辑      ║").append('\n')
        buf.append("╚══════════════════════════════════════════╝").append('\n')
        buf.append('\n')

        // 1. 作品信息
        buf.append("═══ ").append(SECTION_TITLES[0]).append(" ═══").append('\n')
        with(input.work) {
            buf.append("书名: ").append(title).append('\n')
            buf.append("分类: ").append(genre.ifBlank { "未分类" }).append('\n')
            buf.append("总字数: ").append(totalWordCount).append('\n')
            buf.append("总章数: ").append(chapterCount).append('\n')
            if (description.isNotBlank()) buf.append("主线大纲: ").append(excerpt(description)).append('\n')
        }
        buf.append('\n')

        // 2. 卷章结构
        buf.append("═══ ").append(SECTION_TITLES[1]).append(" ═══").append('\n')
        if (input.volumes.isNotEmpty()) {
            input.volumes.forEach { vol ->
                buf.append("【").append(vol.title).append("】").append('\n')
                if (vol.summary.isNotBlank()) {
                    buf.append("  卷纲要: ").append(excerpt(vol.summary)).append('\n')
                }
                vol.chapters.forEach { buf.append("  ").append(chapterLine(it)).append('\n') }
                buf.append('\n')
            }
        } else {
            buf.append("（未分卷，共").append(input.looseChapters.size).append("章）").append('\n')
            input.looseChapters.forEach { buf.append("  ").append(chapterLine(it)).append('\n') }
            buf.append('\n')
        }

        // 3. 最近章节（有摘要用摘要，无则退回标题行）
        buf.append("═══ ").append(SECTION_TITLES[2]).append(" ═══").append('\n')
        val recent = collectChapters(input).takeLast(RECENT_CHAPTER_LIMIT)
        if (recent.isEmpty()) {
            buf.append("（还没有章节）").append('\n')
        } else {
            recent.forEach { ch ->
                buf.append("【").append(ch.title).append("】").append('\n')
                buf.append("  ").append(ch.summary.ifBlank { "（无摘要）" }).append('\n')
            }
        }
        buf.append('\n')

        // 4. 角色状态
        buf.append("═══ ").append(SECTION_TITLES[3]).append(" ═══").append('\n')
        if (input.characters.isEmpty()) {
            buf.append("（暂无角色卡）").append('\n')
        } else {
            input.characters.forEach { c ->
                buf.append("【").append(c.name).append("】")
                if (c.role.isNotBlank()) buf.append(" (").append(c.role).append(")")
                buf.append('\n')
                if (c.description.isNotBlank()) buf.append("  简介: ").append(excerpt(c.description)).append('\n')
                if (c.personality.isNotBlank()) buf.append("  性格: ").append(excerpt(c.personality)).append('\n')
                if (c.background.isNotBlank()) buf.append("  背景: ").append(excerpt(c.background)).append('\n')
                if (c.tags.isNotEmpty()) buf.append("  属性: ").append(c.tags.joinToString("、")).append('\n')
            }
        }
        buf.append('\n')

        // 5~8. 设定 / 地点 / 势力 / 道具（同构渲染）
        appendAssets(buf, SECTION_TITLES[4], input.settings, "（暂无设定卡）")
        appendAssets(buf, SECTION_TITLES[5], input.locations, "（暂无地点）")
        appendAssets(buf, SECTION_TITLES[6], input.factions, "（暂无势力）")
        appendAssets(buf, SECTION_TITLES[7], input.items, "（暂无道具）")

        // 9. 伏笔追踪
        buf.append("═══ ").append(SECTION_TITLES[8]).append(" ═══").append('\n')
        if (input.hooks.isEmpty()) {
            buf.append("（暂无伏笔）").append('\n')
        } else {
            val open = input.hooks.filter { !it.resolved }
            val done = input.hooks.filter { it.resolved }
            buf.append("【未回收伏笔】(").append(open.size).append("条)").append('\n')
            open.forEach { h ->
                val warn = if (h.idleChapters > HOOK_IDLE_WARN) " ⚠️闲置${h.idleChapters}章" else ""
                buf.append("  · ").append(h.title).append(warn).append('\n')
                if (h.description.isNotBlank()) {
                    buf.append("    ").append(excerpt(h.description)).append('\n')
                }
            }
            buf.append("【已回收伏笔】(").append(done.size).append("条)").append('\n')
            done.forEach { buf.append("  ✓ ").append(it.title).append('\n') }
        }
        buf.append('\n')

        // 10. 参考资料
        buf.append("═══ ").append(SECTION_TITLES[9]).append(" ═══").append('\n')
        if (input.references.isEmpty()) {
            buf.append("（暂无参考资料）").append('\n')
        } else {
            input.references.forEach { r ->
                buf.append("· ").append(r.title).append('\n')
                if (r.source.isNotBlank()) buf.append("  来源: ").append(r.source).append('\n')
            }
        }
        buf.append('\n')

        val stamp = input.updatedAt.takeIf { it > 0L } ?: now
        buf.append("═══════════════════════════════════════════").append('\n')
        buf.append("更新时间: ").append(formatTime(stamp)).append('\n')
        buf.append("═══════════════════════════════════════════").append('\n')
        return buf.toString()
    }

    /** 总章数统计（卷内 + 未分卷）。 */
    fun chapterCount(input: Input): Int =
        input.volumes.sumOf { it.chapters.size } + input.looseChapters.size

    private fun collectChapters(input: Input): List<ChapterLine> =
        input.volumes.flatMap { it.chapters } + input.looseChapters

    private fun chapterLine(ch: ChapterLine): String {
        val status = ch.status.ifBlank { "-" }
        return "${ch.order}. ${ch.title} (${ch.wordCount}字) [$status]"
    }

    private fun appendAssets(buf: StringBuilder, title: String, lines: List<AssetLine>, emptyText: String) {
        buf.append("═══ ").append(title).append(" ═══").append('\n')
        if (lines.isEmpty()) {
            buf.append(emptyText).append('\n')
        } else {
            lines.forEach { a ->
                val star = if (a.key) " ⭐" else ""
                buf.append("【").append(a.name).append("】")
                if (a.category.isNotBlank()) buf.append(" (").append(a.category).append(")")
                buf.append(star).append('\n')
                if (a.description.isNotBlank()) buf.append("  ").append(excerpt(a.description)).append('\n')
                a.extras.forEach { (k, v) ->
                    if (v.isNotBlank()) buf.append("  ").append(k).append(": ").append(excerpt(v)).append('\n')
                }
            }
        }
        buf.append('\n')
    }

    private fun excerpt(text: String): String {
        val flat = text.replace('\n', ' ').trim()
        return if (flat.length <= EXCERPT_LIMIT) flat else flat.take(EXCERPT_LIMIT) + "…"
    }

    private fun formatTime(ms: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(ms))
}

/**
 * 文本轨快照的进程内缓存（5 分钟 TTL，键为作品 id）。
 *
 * 与 Dart 版 `NovelMemory` 的静态缓存等价（`_cachedContent` + `_cachedNovelId` + `_cachedAt` + 5 分钟 TTL）；
 * [clock] 可注入，便于单测跨过 TTL 边界。
 */
class NovelTextMemoryCache(private val clock: () -> Long = { System.currentTimeMillis() }) {

    private data class Entry(val content: String, val at: Long)

    private val entries = HashMap<String, Entry>()

    /** 取快照：命中且未过期直接返回；否则调用 [build] 重建并记账。 */
    fun get(workId: String, build: () -> String): String {
        val now = clock()
        val cached = entries[workId]
        if (cached != null && now - cached.at < NovelTextMemory.CACHE_TTL_MS) return cached.content
        val fresh = build()
        entries[workId] = Entry(fresh, now)
        return fresh
    }

    /** 只看缓存（不触发重建）；未命中或已过期返回 null。 */
    fun peek(workId: String): String? {
        val cached = entries[workId] ?: return null
        return if (clock() - cached.at < CACHE_TTL_MS) cached.content else null
    }

    /** 失效指定作品（null = 全部）。 */
    fun invalidate(workId: String? = null) {
        if (workId == null) entries.clear() else entries.remove(workId)
    }

    /** 缓存是否有效（单测与工具回执用）。 */
    fun isFresh(workId: String): Boolean = peek(workId) != null

    companion object {
        /** 5 分钟 TTL（单一真相源在 [NovelTextMemory.CACHE_TTL_MS]）。 */
        const val CACHE_TTL_MS: Long = NovelTextMemory.CACHE_TTL_MS

        /** 全局单例（与 Dart 静态缓存同语义：进程内一份）。 */
        val shared: NovelTextMemoryCache = NovelTextMemoryCache()
    }
}
