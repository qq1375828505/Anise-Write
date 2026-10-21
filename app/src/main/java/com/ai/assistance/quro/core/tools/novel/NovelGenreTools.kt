package com.ai.assistance.quro.core.tools.novel

import android.content.Context
import com.ai.assistance.quro.core.novel.NovelRepository
import com.ai.assistance.quro.core.novel.asset.AssetType
import com.ai.assistance.quro.core.novel.asset.NovelAssetRepository
import com.ai.assistance.quro.core.novel.graph.GraphStore
import com.ai.assistance.quro.core.novel.graph.hook.NovelHookStatus
import com.ai.assistance.quro.core.tools.QuroTool
import org.json.JSONArray
import org.json.JSONObject

/**
 * PenAi 网文写作域 · 流派域 + 上下文域工具族（Phase 4a）。
 *
 * 覆盖规划书 §6.1 三行：流派（list_genres / apply_genre）、上下文（build_context）、
 * 以及 4a 原料（25 个番茄流派 SKILL.md，已随 Phase 4a 合并入库 assets/skills/penai/）。
 *
 * ## 流派资产契约
 * `assets/skills/penai/manifest.json`：{version, suite, skills:[{id,name,description,
 * file,genre_id,category,…}]}；`file` 相对 `skills/penai/`（如 `urban_zhuixu/SKILL.md`）。
 * SKILL.md = YAML frontmatter + 正文（核心卖点 / 番茄原文规约 / 技法清单 / 节奏参数）。
 * apply 时以「番茄原文规约」节为主要回灌内容（模型直接可执行），整篇截断上限见常量。
 *
 * ## build_context 输出对齐
 * 输出键名与 `assets/prompts/subagents/DELEGATION.md` §2.1 请求信封 `context` 字段
 * 对齐（outline / previous_chapters / materials.characters / materials.hooks /
 * materials.relationships / notes）——委派预取（Phase 5/§3）直接复用本工具输出。
 */

/** 流派资产根（assets 相对路径）。 */
private const val GENRE_ASSET_ROOT = "skills/penai"

/** apply 时回灌给模型的规约正文上限（超出截断，truncated=true）。 */
private const val GENRE_GUIDANCE_MAX_CHARS = 5000

/** 流派条目（manifest.json 的 skills[] 子集）。 */
private data class GenreEntry(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val file: String,
)

/** 从 assets 读流派清单；解析失败抛出（无兜底——资产损坏应在 CI 期暴露）。 */
private fun gLoadGenres(context: Context): List<GenreEntry> {
    val root = context.assets.open("$GENRE_ASSET_ROOT/manifest.json").bufferedReader().use { it.readText() }
    val arr = JSONObject(root).getJSONArray("skills")
    return (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        GenreEntry(
            id = o.optString("genre_id", o.optString("id", "")),
            name = o.optString("name", ""),
            description = o.optString("description", ""),
            category = o.optString("category", ""),
            file = o.optString("file", ""),
        )
    }.filter { it.id.isNotBlank() && it.file.isNotBlank() }
}

/** 读单个流派 SKILL.md 正文（frontmatter 保留，规约内容完整）。 */
private fun gLoadGenreSkill(context: Context, file: String): String =
    context.assets.open("$GENRE_ASSET_ROOT/$file").bufferedReader().use { it.readText() }

/** 抽取「## 2. 番茄原文规约」节（模型直接可执行的硬规约）；找不到时返回整篇。 */
private fun gExtractRules(skillText: String): String {
    val regex = Regex("(?m)^## 2\\..*?(?=\\n## \\d|\\z)", RegexOption.DOT_MATCHES_ALL)
    return regex.find(skillText)?.value?.trim().takeUnless { it.isNullOrBlank() } ?: skillText
}

/**
 * novel_list_genres：查询 25 个番茄流派预设。
 */
class NovelListGenresTool : QuroTool {
    override val name = "novel_list_genres"
    override val readOnly = true
    override val description =
        "列出全部可选流派预设（25 个番茄流派：id/名称/分类/简介）。" +
            "何时用：用户开新书问「写什么流派」；apply 前先查 genre_id。" +
            "可用 category（fantasy/urban/…）或 keyword 过滤。"
    override val parametersJson = """{
      "type": "object",
      "properties": {
        "category": { "type": "string", "description": "按分类过滤（可选，如 fantasy / urban / xiuxian）" },
        "keyword": { "type": "string", "description": "按名称/简介关键词过滤（可选，如「赘婿」「修仙」）" }
      },
      "required": []
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = runCatching { JSONObject(arguments) }.getOrNull()
            ?: return gError(GErr.INVALID_ARGS, "参数不是合法 JSON", "本工具参数均可选，传 {} 即可")
        val category = args.optString("category", "").trim()
        val keyword = args.optString("keyword", "").trim()
        val genres = gLoadGenres(context).filter { g ->
            (category.isBlank() || g.category.equals(category, ignoreCase = true)) &&
                (keyword.isBlank() || g.name.contains(keyword, ignoreCase = true) || g.description.contains(keyword, ignoreCase = true))
        }
        val arr = JSONArray().also { a ->
            genres.forEach { g ->
                a.put(JSONObject().apply {
                    put("genre_id", g.id)
                    put("name", g.name)
                    put("category", g.category)
                    put("description", g.description.take(90) + if (g.description.length > 90) "…" else "")
                })
            }
        }
        return JSONObject().apply {
            put("ok", true)
            put("total", genres.size)
            put("genres", arr)
            put("message", if (genres.isEmpty()) "没有匹配的流派（放宽 category/keyword 再试）。" else "共 ${genres.size} 个流派，apply 时用 genre_id。")
        }.toString()
    }
}

/**
 * novel_apply_genre：把流派预设应用到作品（改 work.genre + 回灌流派规约）。
 */
class NovelApplyGenreTool : QuroTool {
    override val name = "novel_apply_genre"
    override val description =
        "应用流派预设：把作品的类型/流派字段设为所选流派，并返回该流派的写作规约" +
            "（番茄原文规约节：设定公式/系统/爽点/节奏/描写重点/情感共鸣）供立即遵循。" +
            "何时用：开新书定流派；用户说「这本书按 XX 流派写」。"
    override val parametersJson = """{
      "type": "object",
      "properties": {
        "work_id": { "type": "string", "description": "作品 id（novel_list_works 获取）" },
        "genre_id": { "type": "string", "description": "流派 id（novel_list_genres 获取）", "example": "urban_zhuixu" }
      },
      "required": ["work_id", "genre_id"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = runCatching { JSONObject(arguments) }.getOrNull()
            ?: return gError(GErr.INVALID_ARGS, "参数不是合法 JSON", "形如 {\"work_id\":\"…\",\"genre_id\":\"urban_zhuixu\"}")
        val workId = args.optString("work_id", "").trim()
        val genreId = args.optString("genre_id", "").trim()
        if (workId.isBlank() || genreId.isBlank()) {
            return gError(GErr.INVALID_ARGS, "work_id 与 genre_id 均不能为空", "genre_id 用 novel_list_genres 查询")
        }
        val genre = gLoadGenres(context).firstOrNull { it.id == genreId }
            ?: return gError(GErr.INVALID_ARGS, "未知 genre_id：$genreId", "用 novel_list_genres 查可用流派")
        val repo = NovelRepository.fromContext(context)
        val before = repo.loadWorks().firstOrNull { it.id == workId } ?: return gWorkNotFound(repo, workId)
        repo.updateWork(workId, genre = genre.name)
        val skillText = gLoadGenreSkill(context, genre.file)
        val rules = gExtractRules(skillText)
        val truncated = rules.length > GENRE_GUIDANCE_MAX_CHARS
        return JSONObject().apply {
            put("ok", true)
            put("genre", JSONObject().apply {
                put("genre_id", genre.id)
                put("name", genre.name)
                put("category", genre.category)
            })
            put("work", JSONObject().apply {
                put("workId", workId)
                put("title", before.title)
                put("genreBefore", before.genre)
                put("genreAfter", genre.name)
            })
            put("guidance", if (truncated) rules.take(GENRE_GUIDANCE_MAX_CHARS) else rules)
            put("truncated", truncated)
            put(
                "message",
                "《${before.title}》流派已设为「${genre.name}」（原：${before.genre.ifBlank { "（空）" }}）。" +
                    "后续写章请遵循上方规约；完整流派卡可用 assets 侧 novel_list_genres 查看简介。",
            )
        }.toString()
    }
}

/**
 * novel_build_context：按字符预算组装作品上下文（输出键名对齐委派信封 context 字段）。
 *
 * 组装优先级（贪心装填，超预算即停）：大纲摘要 → 最近已写章尾部 → 人物卡 →
 * 未回收伏笔（按优先级）→ 关系（按强度）。各段实际用量在 sections 里如实回报。
 */
class NovelBuildContextTool : QuroTool {
    override val name = "novel_build_context"
    override val readOnly = true
    override val description =
        "按预算（默认 6000 字符）组装作品上下文：大纲摘要 + 最近已写章尾部 + 人物卡 + 未回收伏笔 + 强关系。" +
            "输出键名与子代理委派信封 context 字段对齐（outline/previous_chapters/materials.*），可直接填入委派。" +
            "何时用：写新章前组装上文；委派子代理前的预取（DELEGATION §3）。"
    override val parametersJson = """{
      "type": "object",
      "properties": {
        "work_id": { "type": "string", "description": "作品 id（novel_list_works 获取）" },
        "chapter_id": { "type": "string", "description": "当前要写的章节 id（可选：大纲段会标注它的位置）" },
        "budget_chars": { "type": "integer", "description": "上下文预算，默认 6000，范围 2000-12000" },
        "focus": { "type": "array", "items": { "type": "string" }, "description": "关注关键词（可选；命中章节优先入选）" }
      },
      "required": ["work_id"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = runCatching { JSONObject(arguments) }.getOrNull()
            ?: return gError(GErr.INVALID_ARGS, "参数不是合法 JSON", "形如 {\"work_id\":\"…\"}")
        val workId = args.optString("work_id", "").trim()
        if (workId.isBlank()) return gError(GErr.INVALID_ARGS, "work_id 不能为空", "用 novel_list_works 获取作品 id")
        val budget = args.optInt("budget_chars", 6000).coerceIn(2000, 12000)
        val focus = args.optJSONArray("focus")?.let { a ->
            (0 until a.length()).map { a.optString(it, "").trim() }.filter { it.isNotBlank() }
        } ?: emptyList()
        val repo = NovelRepository.fromContext(context)
        val work = repo.loadWorks().firstOrNull { it.id == workId } ?: return gWorkNotFound(repo, workId)
        val store = GraphStore.fromContext(context)
        var used = 0
        val sections = JSONObject()

        // 1) 大纲摘要（卷 → 章节标题+状态，紧凑单行）
        run {
            val sb = StringBuilder()
            work.volumes.sortedBy { it.order }.forEach { v ->
                sb.append("【卷${v.order + 1}·${v.title}】")
                work.chaptersOfVolume(v.id).forEach { c ->
                    sb.append(c.title).append('(').append(c.status.key).append(')').append(' ')
                }
                sb.append('\n')
            }
            val text = sb.toString().trim().take(budget / 2)
            sections.put("outline", text)
            used += text.length
        }

        // 2) 最近已写章尾部（含正文的最后 2 章，各取尾部 700 字；focus 命中章节优先）
        run {
            val written = work.chapters.filter { it.wordCount > 0 }
                .sortedWith(compareByDescending<com.ai.assistance.quro.core.novel.NovelChapter> { it.order })
            val picked = mutableListOf<com.ai.assistance.quro.core.novel.NovelChapter>()
            written.forEach { c ->
                if (picked.size >= 2) return@forEach
                val content = repo.readChapterContent(workId, c.id)
                if (focus.isEmpty() || focus.any { content.contains(it) }) picked.add(c)
            }
            if (picked.isEmpty() && written.isNotEmpty()) picked.add(written.first())
            val arr = JSONArray()
            picked.forEach { c ->
                if (used >= budget) return@forEach
                val content = repo.readChapterContent(workId, c.id)
                val tail = content.takeLast(700)
                used += tail.length
                arr.put(JSONObject().apply {
                    put("chapter_id", c.id)
                    put("title", c.title)
                    put("order", c.order)
                    put("tail", tail)
                })
            }
            sections.put("previous_chapters", arr)
        }

        // 3) 人物卡（最多 8 张：name+role+背景前 60 字）
        run {
            val arr = JSONArray()
            val chars = runCatching {
                NovelAssetRepository.fromContext(context).list(workId, AssetType.CHARACTER).take(8)
            }.getOrDefault(emptyList())
            chars.forEach { c ->
                if (used >= budget) return@forEach
                val text = "${c.name}（${c.description.take(60)}）"
                used += text.length
                arr.put(JSONObject().apply {
                    put("name", c.name)
                    put("key_fields", c.description.take(60))
                })
            }
            val materials = JSONObject().put("characters", arr)
            sections.put("materials", materials)
        }

        // 4) 未回收伏笔（OPEN，按优先级降序最多 5 条）
        run {
            val open = store.listHooks(workId)
                .filter { it.status == NovelHookStatus.OPEN }
                .sortedByDescending { it.priority.level }
                .take(5)
            val arr = JSONArray().also { a ->
                open.forEach { h ->
                    a.put(JSONObject().apply {
                        put("hook_id", h.id)
                        put("title", h.title)
                        put("status", h.status.key)
                        h.plantedAt?.let { put("plant_chapter", it.order) }
                    })
                }
            }
            sections.optJSONObject("materials")?.put("hooks", arr)
        }

        // 5) 关系（按强度降序最多 10 条）
        run {
            val rels = store.listRelationships(workId).sortedByDescending { it.strength }.take(10)
            val arr = JSONArray().also { a ->
                rels.forEach { r ->
                    a.put(JSONObject().apply {
                        put("from", r.fromName); put("to", r.toName)
                        put("type", r.type); put("strength", r.strength)
                    })
                }
            }
            sections.optJSONObject("materials")?.put("relationships", arr)
        }

        return JSONObject().apply {
            put("ok", true)
            put("charsUsed", used)
            put("budget", budget)
            put("context", sections)
            put(
                "message",
                "上下文已组装：大纲 + 最近章 + 人物${if (used > budget) "（已达预算，低优先段被截断）" else ""}；键名对齐委派信封，可直接作 context 字段。",
            )
        }.toString()
    }
}
