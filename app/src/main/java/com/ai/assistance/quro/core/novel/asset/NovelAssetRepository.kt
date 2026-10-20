package com.ai.assistance.quro.core.novel.asset

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * PenAi 小说资料库（Phase 3b）：八类资料的 JSON 文件持久化。
 *
 * ## 目录布局（`filesDir/novel/assets/`，与 Phase 1 `core/novel/NovelRepository.kt` 同风格）
 * ```
 * filesDir/novel/assets/
 * ├── character/{id}.json        # 人物卡（八类各一个子目录，文件内嵌 workId）
 * ├── faction/{id}.json          # 势力卡
 * ├── location/{id}.json         # 地点卡
 * ├── item/{id}.json             # 物品卡
 * ├── ability/{id}.json          # 能力卡
 * ├── plotline/{id}.json         # 情节线卡
 * ├── setting/{id}.json          # 世界观设定卡
 * ├── reference/{id}.json        # 参考资料卡
 * └── index-<workId>.json        # 作品级索引：该作品的 id → 类别 + 名字 + 更新时间
 * ```
 *
 * ## 设计约定（对齐 Phase 1）
 * - 单条记录以 `<类别>/<id>.json` 为唯一事实源；索引只是「这个作品有哪些资料」的
 *   id→类别映射，**读取任何作品都不扫全目录**（一条索引 + N 个已知路径文件）。
 * - 所有写操作先进 [atomicWrite] 落盘再更新索引（先资料后索引，同 Phase 1
 *   「先正文后索引」策略），崩溃不留半截文件；索引与文件不一致时（写一半被杀），
 *   下次读取按目录**重建索引**找回孤儿文件（[rebuildIndex]，记日志，不是假数据兜底）。
 * - 所有公开方法线程安全（内部同一把锁），UI 与 AI 工具可并发调用。
 * - Phase 5 上下文预算按作品取全量/模糊查走 [listAll] / [search]，无需新增存储。
 */
class NovelAssetRepository(private val rootDir: File) {

    private val lock = Any()

    /** 删除结果：deleted=是否确有删除；warnings=引用完整性提醒（只警告不阻断）。 */
    data class AssetDeleteResult(val deleted: Boolean, val warnings: List<String>)

    /** 作品级索引条目（不含记录正文，正文在 `<类别>/<id>.json`）。 */
    private data class AssetIndexEntry(
        val id: String,
        val type: AssetType,
        val name: String,
        val updatedAt: Long,
    )

    private data class AssetIndex(val workId: String, val entries: List<AssetIndexEntry>)

    // ─────────────────────────── 读 ───────────────────────────

    /** 某作品某类别的全部资料（按更新时间倒序，同刻按名字，输出稳定）。 */
    fun list(workId: String, type: AssetType): List<NovelAsset> = synchronized(lock) {
        loadIndex(workId).entries.asSequence()
            .filter { it.type == type }
            .mapNotNull { readAsset(type, it.id) }
            .sortedWith(compareByDescending<NovelAsset> { it.updatedAt }.thenBy { it.name })
            .toList()
    }

    /** 某作品全部资料（八类合并，按更新时间倒序）。 */
    fun listAll(workId: String): List<NovelAsset> = synchronized(lock) {
        loadIndex(workId).entries.asSequence()
            .sortedByDescending { it.updatedAt }
            .mapNotNull { readAsset(it.type, it.id) }
            .toList()
    }

    /** 按 id 精确取一条；不存在返回 null（不含损坏文件——损坏文件按跳过策略处置，见 [readAsset]）。 */
    fun get(workId: String, type: AssetType, id: String): NovelAsset? = synchronized(lock) {
        if (id.isBlank()) return null
        loadIndex(workId).entries.firstOrNull { it.id == id && it.type == type }
            ?.let { readAsset(type, id) }
    }

    /** 按名字精确匹配（去空白）；同名多条时全部返回，由调用方决定是否报歧义。 */
    fun findByName(workId: String, type: AssetType, name: String): List<NovelAsset> =
        synchronized(lock) {
            val target = name.trim()
            if (target.isEmpty()) return emptyList()
            loadIndex(workId).entries.asSequence()
                .filter { it.type == type && it.name == target }
                .mapNotNull { readAsset(type, it.id) }
                .toList()
        }

    /**
     * 模糊查：关键字（子串、忽略大小写）命中 name / tags / description 任一即返回。
     * 供资料工具与 Phase 5 上下文预算检索使用；type 为空时查全类。
     */
    fun search(workId: String, keyword: String, type: AssetType? = null): List<NovelAsset> =
        synchronized(lock) {
            val kw = keyword.trim().lowercase()
            if (kw.isEmpty()) return emptyList()
            val base = if (type != null) list(workId, type) else listAll(workId)
            base.filter { a ->
                a.name.lowercase().contains(kw) ||
                    a.description.lowercase().contains(kw) ||
                    a.tags.any { it.lowercase().contains(kw) }
            }
        }

    // ─────────────────────────── 写 ───────────────────────────

    /**
     * 新建资料：从 [fields]（工具参数）构造记录，id / workId / createdAt / updatedAt 由本层强制生成。
     * 未提供的字段按各类默认值（空串 / 空列表 / ONGOING）；未知字段由 fromJson 自然忽略
     * （字段白名单校验在工具层，报错信息面向模型可自纠正）。
     *
     * @throws IllegalArgumentException name 或 workId 为空（工具层已预检，双保险）
     */
    fun add(type: AssetType, workId: String, fields: JSONObject): NovelAsset = synchronized(lock) {
        require(workId.isNotBlank()) { "workId 不能为空" }
        require(fields.optString("name", "").trim().isNotEmpty()) { "资料名不能为空" }
        val now = System.currentTimeMillis()
        val base = JSONObject(fields.toString()) // 拷贝，避免调用方对象被就地改写
        base.put("id", UUID.randomUUID().toString())
        base.put("workId", workId)
        base.put("createdAt", now)
        base.put("updatedAt", now)
        val record = type.parse(base)
        writeAssetFile(record)
        saveIndex(
            workId,
            loadIndex(workId).let { it.copy(entries = it.entries + AssetIndexEntry(record.id, type, record.name, now)) },
        )
        record
    }

    /**
     * 更新资料：[patch] 浅合并进既有记录（提供的键覆盖，未提供的键保持原值），
     * id / workId / createdAt 永久保持旧值，updatedAt 由本层刷新。
     *
     * @return 更新后的记录；目标不存在或文件损坏返回 null
     */
    fun update(type: AssetType, workId: String, id: String, patch: JSONObject): NovelAsset? =
        synchronized(lock) {
            val old = get(workId, type, id) ?: return null
            val merged = old.toJson()
            patch.keys().forEach { k -> merged.put(k, patch.get(k)) }
            val now = System.currentTimeMillis()
            merged.put("id", old.id)
            merged.put("workId", old.workId)
            merged.put("createdAt", old.createdAt)
            merged.put("updatedAt", now)
            val record = type.parse(merged)
            writeAssetFile(record)
            saveIndex(
                workId,
                loadIndex(workId).let { idx ->
                    idx.copy(
                        entries = idx.entries.map {
                            if (it.id == id) it.copy(name = record.name, updatedAt = now) else it
                        },
                    )
                },
            )
            record
        }

    /**
     * 删除资料，并返回**引用完整性提醒**（只警告不阻断，由工具层转述给模型/用户）。
     *
     * 判定方式：扫描同作品其他资料的 [NovelAsset.referencedNames]（领袖 / 持有者 / 成员 /
     * 关系人 / 相关人物，均为名字精确匹配），命中即生成一条警告。按名字匹配是刻意选择——
     * 资料库是 LLM 驱动的自由文本库，ID 级外键归 Phase 3c 关系图三表。
     */
    fun delete(workId: String, type: AssetType, id: String): AssetDeleteResult = synchronized(lock) {
        val index = loadIndex(workId)
        val entry = index.entries.firstOrNull { it.id == id && it.type == type }
            ?: return AssetDeleteResult(false, emptyList())
        val record = readAsset(type, id)
        if (record == null) {
            // 索引有条目但文件已丢失：清掉悬挂条目，按「未找到」回报
            saveIndex(workId, index.copy(entries = index.entries.filterNot { it == entry }))
            return AssetDeleteResult(false, emptyList())
        }
        val warnings = referenceWarnings(workId, record)
        assetFile(type, id).delete()
        saveIndex(workId, index.copy(entries = index.entries.filterNot { it == entry }))
        AssetDeleteResult(true, warnings)
    }

    /**
     * 作品级联删除：删除该作品全部资料文件（含索引内文件 + 写一半留下的孤儿文件）与作品索引。
     *
     * 供作品删除路径调用（Phase 1 `NovelRepository.deleteWork` 的接线由主编排层裁决，见报告「边界」节）。
     *
     * @return 实际删除的文件数
     */
    fun deleteWork(workId: String): Int = synchronized(lock) {
        var count = 0
        loadIndex(workId).entries.forEach { e ->
            if (assetFile(e.type, e.id).delete()) count++
        }
        // 索引写入前崩溃留下的孤儿文件按内嵌 workId 全量清扫（否则重建索引会把它们复活）
        AssetType.entries.forEach { t ->
            assetDir(t).listFiles()?.forEach { f ->
                if (!f.isFile || !f.name.endsWith(".json")) return@forEach
                val embedded = runCatching {
                    JSONObject(f.readText(Charsets.UTF_8)).optString("workId", "")
                }.getOrNull()
                if (embedded == workId && f.delete()) count++
            }
        }
        indexFile(workId).delete()
        count
    }

    // ─────────────────────────── 内部 ───────────────────────────

    private fun assetDir(type: AssetType): File = File(rootDir, type.dirName)

    private fun assetFile(type: AssetType, id: String): File = File(assetDir(type), "$id.json")

    private fun indexFile(workId: String): File = File(rootDir, "index-$workId.json")

    private fun readAsset(type: AssetType, id: String): NovelAsset? {
        val f = assetFile(type, id)
        if (!f.exists()) return null
        return runCatching { type.parse(JSONObject(f.readText(Charsets.UTF_8))) }
            .getOrElse { e ->
                // 单条损坏跳过（同 Phase 1 loadWorks 的逐条容错）：不闪退、不假数据，
                // 该文件仍留在磁盘上，用户可直接编辑修复。
                Log.w(TAG, "readAsset: ${type.dirName}/$id.json 损坏，本次跳过", e)
                null
            }
    }

    /** 引用提醒：同作品其他资料里以名字引用 [target] 的记录，逐条生成中文警告。 */
    private fun referenceWarnings(workId: String, target: NovelAsset): List<String> {
        val name = target.name.trim()
        if (name.isEmpty()) return emptyList()
        return listAll(workId)
            .filter { it.id != target.id }
            .filter { it.referencedNames.any { n -> n.trim() == name } }
            .map { "${it.type.displayName}「${it.name}」" }
    }

    // ─────────────────────────── 索引 ───────────────────────────

    private fun loadIndex(workId: String): AssetIndex {
        val f = indexFile(workId)
        if (!f.exists()) return rebuildIndex(workId)
        return runCatching { parseIndex(JSONObject(f.readText(Charsets.UTF_8)), workId) }
            .getOrElse { e ->
                Log.w(TAG, "loadIndex: index-$workId.json 损坏，按目录重建", e)
                rebuildIndex(workId)
            }
    }

    private fun parseIndex(o: JSONObject, workId: String): AssetIndex {
        val arr = o.optJSONArray("entries") ?: JSONArray()
        val entries = mutableListOf<AssetIndexEntry>()
        for (i in 0 until arr.length()) {
            val e = runCatching { arr.getJSONObject(i) }.getOrNull() ?: continue
            val type = AssetType.fromKey(e.optString("type", "")) ?: continue
            entries.add(
                AssetIndexEntry(
                    id = e.optString("id", ""),
                    type = type,
                    name = e.optString("name", ""),
                    updatedAt = e.optLong("updatedAt", 0L),
                ),
            )
        }
        return AssetIndex(workId, entries.filter { it.id.isNotEmpty() })
    }

    /**
     * 索引重建：按类别目录读全部文件，收内嵌 workId 匹配的记录。
     * 用于索引缺失（首次）或损坏（写一半被杀 / 用户误删）——是崩溃恢复，
     * 不是「读不到就给假数据」的兜底。
     */
    private fun rebuildIndex(workId: String): AssetIndex {
        Log.w(TAG, "rebuildIndex: 重建 workId=$workId 的资料索引（扫类别目录）")
        val entries = mutableListOf<AssetIndexEntry>()
        AssetType.entries.forEach { t ->
            assetDir(t).listFiles()?.forEach { f ->
                if (!f.isFile || !f.name.endsWith(".json")) return@forEach
                val id = f.nameWithoutExtension
                val record = readAsset(t, id) ?: return@forEach
                if (record.workId == workId) {
                    entries.add(AssetIndexEntry(record.id, t, record.name, record.updatedAt))
                }
            }
        }
        val idx = AssetIndex(workId, entries)
        saveIndex(workId, idx)
        return idx
    }

    private fun saveIndex(workId: String, index: AssetIndex) {
        val arr = JSONArray()
        index.entries.forEach { e ->
            arr.put(
                JSONObject().apply {
                    put("id", e.id)
                    put("type", e.type.key)
                    put("name", e.name)
                    put("updatedAt", e.updatedAt)
                },
            )
        }
        val root = JSONObject().apply {
            put("version", INDEX_VERSION)
            put("workId", workId)
            put("entries", arr)
        }
        rootDir.mkdirs()
        atomicWrite(indexFile(workId), root.toString())
    }

    private fun writeAssetFile(record: NovelAsset) {
        val f = assetFile(record.type, record.id)
        f.parentFile?.mkdirs()
        atomicWrite(f, record.toJson().toString())
    }

    /** 原子写：先写临时文件再 rename，避免进程被杀时留下半截文件（用户资料资产优先）。 */
    private fun atomicWrite(target: File, content: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(content, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            // rename 失败（极少见：目标被占用/跨卷）：退回直接覆盖，保证写入不丢
            target.writeText(content, Charsets.UTF_8)
            tmp.delete()
        }
    }

    companion object {
        private const val TAG = "NovelAssetRepo"

        /** 索引格式版本：资料结构变更（增字段/增类别）时递增并做迁移。 */
        const val INDEX_VERSION = 1

        /** 资料根目录名（相对 `filesDir/novel/`，与 Phase 1 NovelRepository 的 "novel" 同级）。 */
        const val ASSETS_DIR_NAME = "assets"

        /** 基座约定：数据落 `filesDir/novel/assets/`（同 Phase 1 的 filesDir JSON 风格）。 */
        fun fromContext(context: Context): NovelAssetRepository =
            NovelAssetRepository(File(File(context.filesDir, "novel"), ASSETS_DIR_NAME))
    }
}
