package com.ai.assistance.quro.core.novel.graph.memory

import com.ai.assistance.quro.core.novel.graph.NovelMemory
import kotlin.math.sqrt

/**
 * 记忆混合检索（P1-4 双轨记忆 · 图谱轨）：多路召回 + 倒数排名融合（RRF）。
 *
 * 逐条移植自 novelIDE `memory_repository.dart`（`searchMemories` 与 `_splitSearchKeywords`
 * 一族辅助函数），常数与权重一字不改：
 * - RRF 常数 K = 60（`_searchRrfK`）；
 * - 关键词覆盖加成 0.6（`_searchKeywordCoverageBonus`）；
 * - 默认权重：关键词 10.0 / 标签 0.0 / 语义 0.0 / 边 0.4；
 * - 评分模式乘数：均衡 1.0；关键词优先（关键词 1.3 / 语义 0.8 / 边 0.9）；语义优先（1.3 / 1.3 / 1.1）。
 *
 * ## 与 Dart 版的差异（有意为之，各在下方注释标「本仓差异」）
 * 1. 语义路走注入的向量提供者（[embeddingProvider]）：端侧没有 embedding 服务时整路自动跳过，
 *    不影响其余三路（Dart 版同理，`generateEmbedding == null` 时跳过）。
 * 2. 边权重在 Dart 版服务于图谱邻域展开检索；本仓把邻域展开做成**独立的第五路召回**：
 *    取已得分的种子节点，沿 [linksOf] 的边给邻居加 `边权 × 边乘数 ÷ (K + 种子排名)`。
 * 3. 纯函数、无 IO、无 Android 依赖，可直接 JVM 单测。
 */
object NovelMemorySearch {

    /** RRF 常数 K（Dart `_searchRrfK`）。 */
    const val RRF_K = 60.0

    /** 关键词覆盖加成系数（Dart `_searchKeywordCoverageBonus`）。 */
    const val KEYWORD_COVERAGE_BONUS = 0.6

    const val DEFAULT_KEYWORD_WEIGHT = 10.0
    const val DEFAULT_TAG_WEIGHT = 0.0
    const val DEFAULT_VECTOR_WEIGHT = 0.0
    const val DEFAULT_EDGE_WEIGHT = 0.4

    /** 词元长度上下限（Dart `_shouldKeepLexicalToken`）。 */
    const val MIN_TOKEN_LENGTH = 2
    const val MAX_TOKEN_LENGTH = 24

    /** 图谱邻域展开的种子个数（本仓差异 2）。 */
    const val DEFAULT_EDGE_SEED_LIMIT = 3

    /** 评分模式（Dart `MemoryScoreMode`）。 */
    enum class ScoreMode {
        BALANCED,
        KEYWORD_FIRST,
        SEMANTIC_FIRST;

        companion object {
            fun fromKey(key: String?): ScoreMode = when (key?.trim()?.lowercase()) {
                "keywordfirst", "keyword_first", "keyword-first" -> KEYWORD_FIRST
                "semanticfirst", "semantic_first", "semantic-first" -> SEMANTIC_FIRST
                else -> BALANCED
            }
        }
    }

    /** 检索配置（Dart `MemorySearchConfig`）。 */
    data class Config(
        val scoreMode: ScoreMode = ScoreMode.BALANCED,
        val keywordWeight: Double = DEFAULT_KEYWORD_WEIGHT,
        val tagWeight: Double = DEFAULT_TAG_WEIGHT,
        val vectorWeight: Double = DEFAULT_VECTOR_WEIGHT,
        val edgeWeight: Double = DEFAULT_EDGE_WEIGHT,
    ) {
        /** 负权重归一为 0（Dart `normalized`）。 */
        fun normalized(): Config = Config(
            scoreMode = scoreMode,
            keywordWeight = keywordWeight.coerceAtLeast(0.0),
            tagWeight = tagWeight.coerceAtLeast(0.0),
            vectorWeight = vectorWeight.coerceAtLeast(0.0),
            edgeWeight = edgeWeight.coerceAtLeast(0.0),
        )

        val keywordMultiplier: Double
            get() = when (scoreMode) {
                ScoreMode.BALANCED -> 1.0
                ScoreMode.KEYWORD_FIRST -> 1.3
                ScoreMode.SEMANTIC_FIRST -> 0.8
            }

        val semanticMultiplier: Double
            get() = when (scoreMode) {
                ScoreMode.BALANCED -> 1.0
                ScoreMode.KEYWORD_FIRST -> 0.8
                ScoreMode.SEMANTIC_FIRST -> 1.3
            }

        val edgeMultiplier: Double
            get() = when (scoreMode) {
                ScoreMode.BALANCED -> 1.0
                ScoreMode.KEYWORD_FIRST -> 0.9
                ScoreMode.SEMANTIC_FIRST -> 1.1
            }
    }

    /**
     * 一条命中。
     *
     * @param keywordRank 关键词路排名（1 起；0 = 未进关键词榜）
     * @param matchedTokens 命中的词元总数（标题 + 正文）
     * @param viaLink 是否由图谱邻域（第五路）带出
     */
    data class Hit(
        val memory: NovelMemory,
        val score: Double,
        val keywordRank: Int = 0,
        val matchedTokens: Int = 0,
        val viaLink: Boolean = false,
    )

    /**
     * 切词：查询含 `|` 时按 `|` 切，否则按空白切（Dart `_splitSearchKeywords`）。
     * 计划书 §6.1 的检索语法即用 `|` 表达「或」。
     */
    fun splitKeywords(query: String): List<String> {
        val parts = if (query.contains('|')) query.split('|') else query.split(Regex("\\s+"))
        return parts.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** 词元是否值得保留：2~24 字符且含数字、拉丁字母或汉字（Dart `_shouldKeepLexicalToken`）。 */
    fun shouldKeepToken(token: String): Boolean {
        val t = token.trim()
        if (t.isEmpty()) return false
        if (t.length < MIN_TOKEN_LENGTH || t.length > MAX_TOKEN_LENGTH) return false
        return t.any { c ->
            c in '0'..'9' || c in 'A'..'Z' || c in 'a'..'z' || (c >= '\u4E00' && c <= '\u9FFF')
        }
    }

    /** 文本是否命中词元：支持 `*` 通配（Dart `_textMatchesLexicalToken`）。 */
    fun textMatchesToken(text: String, token: String): Boolean {
        val t = token.trim()
        if (t.isEmpty()) return false
        if (t.contains('*') && t != "*") {
            val parts = t.split('*').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) return false
            val pattern = parts.joinToString(".*") { Regex.escape(it) }
            return Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(text)
        }
        return text.lowercase().contains(t.lowercase())
    }

    /** 由查询串构造词元表（去重后按长度降序，长词优先命中）（Dart `_buildLexicalQueryTokens`）。 */
    fun buildLexicalTokens(query: String, keywords: List<String>): List<String> {
        val merged = LinkedHashSet<String>()
        val source = if (keywords.isNotEmpty()) keywords else emptyList()
        source.forEach { kw ->
            val normalized = kw.trim().lowercase()
            if (shouldKeepToken(normalized)) merged.add(normalized)
        }
        if (merged.isEmpty()) {
            val normalized = query.trim().lowercase()
            if (shouldKeepToken(normalized)) merged.add(normalized)
        }
        return merged.sortedByDescending { it.length }
    }

    /** 余弦相似度（Dart `Embedding.cosineSimilarity`）；空向量或维度不等返回 0.0。 */
    fun cosineSimilarity(left: List<Double>, right: List<Double>): Double {
        if (left.isEmpty() || right.isEmpty() || left.size != right.size) return 0.0
        var dot = 0.0
        var leftNorm = 0.0
        var rightNorm = 0.0
        for (i in left.indices) {
            dot += left[i] * right[i]
            leftNorm += left[i] * left[i]
            rightNorm += right[i] * right[i]
        }
        if (leftNorm <= 0.0 || rightNorm <= 0.0) return 0.0
        return dot / (sqrt(leftNorm) * sqrt(rightNorm))
    }

    /**
     * 主检索：四路关键词（标题 + 正文）按 RRF 融合，标签路与语义路直接加权，
     * 再沿图谱边做一轮邻域展开。
     *
     * @param query 检索串（`*` 表示全量，调用方自行短路；`|` 表达「或」）
     * @param candidates 该作品的记忆节点（调用方先行过滤 kind 等硬条件）
     * @param tagsOf 取节点的标签名（用于标签路）
     * @param embeddingProvider 语义路的查询向量提供者（返回 null = 该词跳过）；整路可空
     * @param linksOf 取节点的边（用于邻域展开；空 = 不做第五路）
     * @param threshold 得分下限（Dart `relevanceThreshold`）
     */
    fun search(
        query: String,
        candidates: List<NovelMemory>,
        config: Config = Config(),
        tagsOf: (String) -> List<String> = { emptyList() },
        embeddingProvider: ((String) -> List<Double>?)? = null,
        linksOf: (String) -> List<NovelMemoryLink> = { emptyList() },
        threshold: Double = 0.0,
        edgeSeedLimit: Int = DEFAULT_EDGE_SEED_LIMIT,
    ): List<Hit> {
        if (candidates.isEmpty()) return emptyList()
        val trimmed = query.trim()
        if (trimmed.isEmpty() || trimmed == "*") {
            return candidates.map { Hit(memory = it, score = 0.0) }
        }
        val keywords = splitKeywords(trimmed)
        if (keywords.isEmpty()) return emptyList()

        val cfg = config.normalized()
        val scores = HashMap<String, Double>()
        val keywordRanks = HashMap<String, Int>()
        val matchedTokenCounts = HashMap<String, Int>()
        val byId = candidates.associateBy { it.id }

        // ── 路 1：关键词（标题 + 正文），RRF 倒数排名 ──
        val tokens = buildLexicalTokens(trimmed, keywords)
        if (tokens.isNotEmpty() && cfg.keywordWeight > 0) {
            val titleMatches = HashMap<String, Int>()
            val contentMatches = HashMap<String, Int>()
            for (memory in candidates) {
                val titleHit = tokens.count { textMatchesToken(memory.title, it) }
                val contentHit = tokens.count { textMatchesToken(memory.content, it) }
                if (titleHit > 0) titleMatches[memory.id] = titleHit
                if (contentHit > 0) contentMatches[memory.id] = contentHit
            }
            val rankedIds = (titleMatches.keys + contentMatches.keys).distinct()
                .sortedWith(
                    compareByDescending<String> { titleMatches[it] ?: 0 }
                        .thenByDescending { contentMatches[it] ?: 0 }
                        .thenBy { it }
                )
            rankedIds.forEachIndexed { index, id ->
                val rank = index + 1
                val baseScore = 1.0 / (RRF_K + rank)
                val matchedTokenCount = (titleMatches[id] ?: 0) + (contentMatches[id] ?: 0)
                val coverageRatio = matchedTokenCount.toDouble() / tokens.size
                val coverageMultiplier = 1.0 + (KEYWORD_COVERAGE_BONUS * coverageRatio)
                val weighted = baseScore * cfg.keywordWeight * cfg.keywordMultiplier * coverageMultiplier
                scores[id] = (scores[id] ?: 0.0) + weighted
                keywordRanks[id] = rank
                matchedTokenCounts[id] = matchedTokenCount
            }
        }

        // ── 路 2：标签（命中标签数 × 标签权重 × 关键词乘数）──
        if (cfg.tagWeight > 0) {
            for (memory in candidates) {
                val tags = tagsOf(memory.id)
                if (tags.isEmpty()) continue
                val matchedTagCount = keywords.count { kw ->
                    tags.any { textMatchesToken(it, kw) }
                }
                if (matchedTagCount > 0) {
                    val tagScore = matchedTagCount * cfg.tagWeight * cfg.keywordMultiplier
                    scores[memory.id] = (scores[memory.id] ?: 0.0) + tagScore
                }
            }
        }

        // ── 路 3：语义（需要向量提供者；无向量则整路跳过）──
        if (cfg.vectorWeight > 0 && embeddingProvider != null) {
            for (keyword in keywords) {
                val queryVector = embeddingProvider(keyword) ?: continue
                for (memory in candidates) {
                    if (memory.embedding.isEmpty()) continue
                    val similarity = cosineSimilarity(queryVector, memory.embedding)
                    if (similarity > 0) {
                        scores[memory.id] = (scores[memory.id] ?: 0.0) +
                            similarity * cfg.vectorWeight * cfg.semanticMultiplier
                    }
                }
            }
        }

        // ── 路 4：图谱邻域展开（本仓差异 2）──
        val linkedIds = HashSet<String>()
        if (cfg.edgeWeight > 0) {
            val seeds = scores.entries
                .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
                .take(edgeSeedLimit)
            seeds.forEachIndexed { seedIndex, entry ->
                val seedRank = seedIndex + 1
                linksOf(entry.key).forEach { link ->
                    val otherId = link.other(entry.key)
                    if (otherId.isEmpty() || byId[otherId] == null) return@forEach
                    val bonus = link.weight * cfg.edgeWeight * cfg.edgeMultiplier * (1.0 / (RRF_K + seedRank))
                    if (bonus <= 0.0) return@forEach
                    scores[otherId] = (scores[otherId] ?: 0.0) + bonus
                    linkedIds.add(otherId)
                }
            }
        }

        return scores.entries
            .filter { it.value >= threshold }
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .mapNotNull { entry ->
                val memory = byId[entry.key] ?: return@mapNotNull null
                Hit(
                    memory = memory,
                    score = entry.value,
                    keywordRank = keywordRanks[entry.key] ?: 0,
                    matchedTokens = matchedTokenCounts[entry.key] ?: 0,
                    viaLink = linkedIds.contains(entry.key) && (keywordRanks[entry.key] == null),
                )
            }
    }
}
