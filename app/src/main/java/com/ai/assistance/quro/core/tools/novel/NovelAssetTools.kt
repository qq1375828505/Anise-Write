package com.ai.assistance.quro.core.tools.novel

import android.content.Context
import com.ai.assistance.quro.core.novel.asset.AssetType
import com.ai.assistance.quro.core.novel.asset.NovelAsset
import com.ai.assistance.quro.core.novel.asset.NovelAssetRepository
import com.ai.assistance.quro.core.tools.QuroTool
import org.json.JSONObject

/**
 * PenAi 网文资料域工具（Phase 3b）：八类资料的统一入口（规划书 §6.1 工具族清单「资料（8 类）」行）。
 *
 * ## 工具清单
 * | 工具 | 读/写 | 说明 |
 * | --- | --- | --- |
 * | [NovelMaterialsGetTool] | 只读 | 按 id/name 取一条资料完整 JSON |
 * | [NovelMaterialsListTool] | 只读 | 列某类/全部资料的一行摘要 |
 * | [NovelMaterialsSearchTool] | 只读 | 关键字模糊查（名称/标签/简介） |
 * | [NovelMaterialsAddTool] | 写 | 建档 |
 * | [NovelMaterialsUpdateTool] | 写 | 浅合并更新 |
 * | [NovelMaterialsDeleteTool] | 写 | 删除 + 引用完整性警告（不阻断） |
 *
 * 规划书原文只列 get/add/update/delete 四个，list/search 为任务书要求的「list/查询类」补充
 * （资料库没有 list 就无法在不扫盘的前提下让模型盘点；search 服务 Phase 5 上下文预算的资料召回）。
 *
 * ## 范式说明
 * - 全部执行逻辑在 [NovelAssetToolExecutor]（纯函数：参数为仓库 + 参数 JSON），
 *   工具类只是薄壳（name/description/parametersJson/run 委托），与基座
 *   `CardCatalogTool.query` 的可测性范式一致——JVM 单测不经 Context 即可覆盖全部分支。
 * - 报错面向模型可自纠正：缺 workId 提示先用 `novel_list_works`（结构域/Phase 2），
 *   字段名错误列出该类别可用字段，同名歧义列出全部 id。
 * -  workId 的存在性校验不做（作品域归 Phase 1 `NovelRepository`，本分支不依赖其编译单元），
 *   由上层创作流程保证；乱传 workId 的后果仅是资料挂在一个空作品索引下，不污染其他作品。
 */

// ─────────────────────────── 执行体（纯函数，工具与单测共用） ───────────────────────────

internal object NovelAssetToolExecutor {

    /** 八类共用的可写字段；type.fields 为类别特有字段（见 AssetType）。 */
    private val COMMON_FIELDS = setOf("type", "workId", "name", "description", "tags")

    /** 更新时禁止改动的托管字段（id 是定位参数不算在内）。 */
    private val IMMUTABLE_FIELDS = setOf("workId", "createdAt", "updatedAt")

    // ── add ──

    fun add(repo: NovelAssetRepository, arguments: String): String {
        val args = parseArgs(arguments) ?: return badJson(arguments)
        val type = parseType(args) ?: return unknownTypeError(args)
        requireWorkId(args)?.let { return it }
        if (args.optString("name", "").trim().isEmpty()) {
            return "novel_materials_add 需要 name（资料名，如人物名/势力名）。"
        }
        validateFields(args, type, "novel_materials_add")?.let { return it }
        val record = try {
            repo.add(type, args.optString("workId", "").trim(), args)
        } catch (e: IllegalArgumentException) {
            return "建档失败：${e.message}"
        }
        return recordText("已建档 ${type.displayName}「${record.name}」（id=${record.id}）", record)
    }

    // ── get ──

    fun get(repo: NovelAssetRepository, arguments: String): String {
        val args = parseArgs(arguments) ?: return badJson(arguments)
        val type = parseType(args) ?: return unknownTypeError(args)
        val workId = requireWorkId(args) ?: return "缺少 workId（作品 id）。先用 novel_list_works 查看作品列表，再用作品 id 调用。"
        val id = args.optString("id", "").trim()
        val name = args.optString("name", "").trim()
        if (id.isEmpty() && name.isEmpty()) {
            return "novel_materials_get 需要 id 或 name（定位资料；同名多条时请改用 id）。"
        }
        val r = resolve(repo, workId, type, id, name, "novel_materials_get")
        r.error?.let { return it }
        return recordText("${type.displayName}资料：", r.record!!)
    }

    // ── update ──

    fun update(repo: NovelAssetRepository, arguments: String): String {
        val args = parseArgs(arguments) ?: return badJson(arguments)
        val type = parseType(args) ?: return unknownTypeError(args)
        val workId = requireWorkId(args) ?: return "缺少 workId（作品 id）。先用 novel_list_works 查看作品列表，再用作品 id 调用。"
        val id = args.optString("id", "").trim()
        val name = args.optString("name", "").trim()
        if (id.isEmpty() && name.isEmpty()) {
            return "novel_materials_update 需要 id 或 name（定位要修改的资料）。"
        }
        immutableError(args, "novel_materials_update")?.let { return it }
        validateFields(args, type, "novel_materials_update", extraAllowed = setOf("id"))?.let { return it }
        if (args.has("name") && args.optString("name", "").trim().isEmpty()) {
            return "name 不能为空（资料名不可清空；要改名请直接传新名字）。"
        }
        val r = resolve(repo, workId, type, id, name, "novel_materials_update")
        r.error?.let { return it }
        val target = r.record!!
        val patch = JSONObject()
        args.keys().asSequence()
            .filter { it != "type" && it != "id" && it != "workId" }
            .forEach { patch.put(it, args.get(it)) }
        if (patch.length() == 0) {
            return "novel_materials_update 未提供任何要修改的字段（name/description/tags 或「${type.displayName}」特有字段至少给一个）。"
        }
        val updated = repo.update(type, workId, target.id, patch)
            ?: return "修改失败：${type.displayName}资料不存在或文件损坏（id=${target.id}）。"
        return recordText("已更新 ${type.displayName}「${updated.name}」（id=${updated.id}）", updated)
    }

    // ── delete ──

    fun delete(repo: NovelAssetRepository, arguments: String): String {
        val args = parseArgs(arguments) ?: return badJson(arguments)
        val type = parseType(args) ?: return unknownTypeError(args)
        val workId = requireWorkId(args) ?: return "缺少 workId（作品 id）。先用 novel_list_works 查看作品列表，再用作品 id 调用。"
        val id = args.optString("id", "").trim()
        val name = args.optString("name", "").trim()
        if (id.isEmpty() && name.isEmpty()) {
            return "novel_materials_delete 需要 id 或 name（定位要删除的资料）。"
        }
        val r = resolve(repo, workId, type, id, name, "novel_materials_delete")
        r.error?.let { return it }
        val target = r.record!!
        val result = repo.delete(workId, type, target.id)
        if (!result.deleted) {
            return "删除失败：${type.displayName}资料不存在或文件已损坏（id=${target.id}）。"
        }
        val sb = StringBuilder("已删除 ${type.displayName}「${target.name}」（id=${target.id}）")
        if (result.warnings.isNotEmpty()) {
            sb.append("\n⚠️ 引用提醒（已删除；以下资料仍按名字引用它，建议同步更新，避免正文与资料库脱节）：")
            result.warnings.forEach { sb.append("\n- ").append(it) }
        }
        return sb.toString()
    }

    // ── list ──

    fun list(repo: NovelAssetRepository, arguments: String): String {
        val args = parseArgs(arguments) ?: return badJson(arguments)
        requireWorkId(args)?.let { return it }
        val workId = args.optString("workId", "").trim()
        val type = args.optString("type", "").trim()
            .let { if (it.isEmpty()) null else (AssetType.fromKey(it) ?: return unknownTypeError(args)) }
        val items = if (type != null) repo.list(workId, type) else repo.listAll(workId)
        if (items.isEmpty()) {
            val scope = type?.displayName ?: "任何资料"
            return "作品下暂无$scope（共 0 条）。"
        }
        return buildString {
            append("共 ${items.size} 条")
            append(type?.displayName ?: "资料")
            append("（按更新时间倒序）：")
            items.forEach { append("\n").append(oneLine(it)) }
        }
    }

    // ── search ──

    fun search(repo: NovelAssetRepository, arguments: String): String {
        val args = parseArgs(arguments) ?: return badJson(arguments)
        requireWorkId(args)?.let { return it }
        val workId = args.optString("workId", "").trim()
        val keyword = args.optString("keyword", "").trim()
        if (keyword.isEmpty()) {
            return "novel_materials_search 需要 keyword（检索关键字，匹配名称/标签/简介）。"
        }
        val type = args.optString("type", "").trim()
            .let { if (it.isEmpty()) null else (AssetType.fromKey(it) ?: return unknownTypeError(args)) }
        val items = repo.search(workId, keyword, type)
        val scope = type?.displayName ?: "资料"
        if (items.isEmpty()) {
            return "未找到包含「$keyword」的$scope。可换关键字，或用 novel_materials_list 浏览全量。"
        }
        return buildString {
            append("关键字「$keyword」命中 ${items.size} 条$scope：")
            items.forEach { append("\n").append(oneLine(it)) }
        }
    }

    // ── 内部 ──

    private fun parseArgs(arguments: String): JSONObject? =
        runCatching { JSONObject(arguments) }.getOrNull()

    private fun badJson(arguments: String): String =
        "参数不是合法 JSON（请检查引号与括号是否完整）：${arguments.take(200)}"

    private fun parseType(args: JSONObject): AssetType? =
        AssetType.fromKey(args.optString("type", "").trim())

    private fun unknownTypeError(args: JSONObject): String {
        val given = args.optString("type", "").trim()
        val valid = AssetType.entries.joinToString(" / ") { it.key }
        return "需要合法的 type（资料类别），收到「$given」。可用：$valid。"
    }

    private fun requireWorkId(args: JSONObject): String? =
        if (args.optString("workId", "").trim().isEmpty()) {
            "缺少 workId（作品 id）。先用 novel_list_works 查看作品列表，再用作品 id 调用。"
        } else {
            null
        }

    /** 字段白名单校验：拒绝 LLM 传错字段名，报错列出该类别可用字段（可自纠正）。 */
    private fun validateFields(args: JSONObject, type: AssetType, op: String, extraAllowed: Set<String> = emptySet()): String? {
        val allowed = COMMON_FIELDS + type.fields + extraAllowed
        val unknown = args.keys().asSequence().filter { it !in allowed }.toList()
        if (unknown.isEmpty()) return null
        return "$op 类别「${type.displayName}」不支持字段：${unknown.joinToString("、")}。" +
            "可用字段：公共（name/description/tags）" +
            (if (type.fields.isEmpty()) "" else " + 特有（${type.fields.joinToString("、")}）")
    }

    private fun immutableError(args: JSONObject, op: String): String? {
        val bad = args.keys().asSequence().filter { it in IMMUTABLE_FIELDS }.sorted().toList()
        if (bad.isEmpty()) return null
        return "$op 不允许修改 ${bad.joinToString("、")}（建档时确定或由系统维护）。"
    }

    /** id 或 name 定位一条资料；失败时 error 非空（含同名歧义时列出全部 id）。 */
    private class Resolution(val record: NovelAsset? = null, val error: String? = null)

    private fun resolve(
        repo: NovelAssetRepository,
        workId: String,
        type: AssetType,
        id: String,
        name: String,
        op: String,
    ): Resolution {
        if (id.isNotEmpty()) {
            val r = repo.get(workId, type, id)
            return if (r != null) {
                Resolution(r)
            } else {
                Resolution(error = "未找到${type.displayName}资料（id=$id）。可先用 novel_materials_list 或 novel_materials_search 确认。")
            }
        }
        val matches = repo.findByName(workId, type, name)
        return when (matches.size) {
            0 -> Resolution(
                error = "未找到${type.displayName}「$name」。可先用 novel_materials_list 或 novel_materials_search 确认名称。",
            )
            1 -> Resolution(matches[0])
            else -> Resolution(
                error = "$op 发现 ${matches.size} 条同名${type.displayName}「$name」，请改用 id 指定：" +
                    matches.joinToString("、") { it.id },
            )
        }
    }

    private fun recordText(prefix: String, record: NovelAsset): String = "$prefix\n${record.toJson()}"

    /** 一行摘要（list/search 用）：类别 + 名称 + id + 简介截断 + 标签。 */
    private fun oneLine(a: NovelAsset): String = buildString {
        append("- ")
        append(a.type.displayName)
        append(" ")
        append(a.name)
        append("（id=")
        append(a.id)
        append("）")
        if (a.description.isNotBlank()) {
            append("：")
            append(if (a.description.length > 50) a.description.take(50) + "…" else a.description)
        }
        if (a.tags.isNotEmpty()) {
            append("（tags：")
            append(a.tags.joinToString("、"))
            append("）")
        }
    }
}

// ─────────────────────────── 工具（薄壳） ───────────────────────────

/** 资料查询：按 id/name 取一条完整 JSON。 */
class NovelMaterialsGetTool : QuroTool {
    override val name = "novel_materials_get"
    override val readOnly = true
    override val description = "查询作品资料库中的一条资料，返回完整 JSON（含全部字段）。" +
        "八类资料：人物(character)/势力(faction)/地点(location)/物品(item)/能力(ability)/情节线(plotline)/世界观设定(setting)/参考资料(reference)，用 type 分流。" +
        "何时用：写章节前核对某个人物/势力/设定/情节线的档案；用户问「xx 的设定是什么」；更新资料前先看现状。" +
        "触发词：查资料、人物卡、设定是什么、看一下情节线。" +
        "参数：type（类别，必填）、workId（作品 id，必填，先用 novel_list_works 获取）、id 或 name 至少一个（同名多条时用 id）。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "type":{"type":"string","description":"资料类别（必填）","enum":["character","faction","location","item","ability","plotline","setting","reference"]},
            "workId":{"type":"string","description":"作品 id（必填，先用 novel_list_works 获取）"},
            "id":{"type":"string","description":"资料 id（与 name 二选一，优先）"},
            "name":{"type":"string","description":"资料名（精确匹配；同名多条时会列出全部 id 请改用 id）"}
        },
        "required":["type","workId"]
    }"""

    override fun run(context: Context, arguments: String): String =
        NovelAssetToolExecutor.get(NovelAssetRepository.fromContext(context), arguments)
}

/** 资料清单：列某类/全部资料的一行摘要（不返回完整字段，省 token）。 */
class NovelMaterialsListTool : QuroTool {
    override val name = "novel_materials_list"
    override val readOnly = true
    override val description = "列出作品下某类（或全部八类）资料清单，每条一行摘要（类别/名称/id/简介/标签），按更新时间倒序。" +
        "何时用：不知道资料 id 时先浏览盘点；写作前看看有哪些人物/设定；检查资料库完整性。" +
        "触发词：有哪些人物、资料列表、设定清单、全部资料。" +
        "参数：workId（必填）、type（类别，可选，缺省列全部八类）。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "workId":{"type":"string","description":"作品 id（必填，先用 novel_list_works 获取）"},
            "type":{"type":"string","description":"资料类别（可选，缺省列全部八类）","enum":["character","faction","location","item","ability","plotline","setting","reference"]}
        },
        "required":["workId"]
    }"""

    override fun run(context: Context, arguments: String): String =
        NovelAssetToolExecutor.list(NovelAssetRepository.fromContext(context), arguments)
}

/** 资料检索：关键字模糊查（命中名称/标签/简介，忽略大小写）。 */
class NovelMaterialsSearchTool : QuroTool {
    override val name = "novel_materials_search"
    override val readOnly = true
    override val description = "模糊检索作品资料库：关键字命中名称/标签/简介任一即返回（忽略大小写），可限定类别。" +
        "何时用：资料多时按关键字找；快速定位「涉及修炼体系的情节线」「带某标签的人物」；为写作任务召回相关资料。" +
        "触发词：搜一下、找找、有没有关于xx的资料、检索资料。" +
        "参数：workId（必填）、keyword（必填）、type（可选，缺省查全部八类）。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "workId":{"type":"string","description":"作品 id（必填，先用 novel_list_works 获取）"},
            "keyword":{"type":"string","description":"检索关键字（必填）：命中名称/标签/简介，忽略大小写"},
            "type":{"type":"string","description":"限定资料类别（可选，缺省查全部八类）","enum":["character","faction","location","item","ability","plotline","setting","reference"]}
        },
        "required":["workId","keyword"]
    }"""

    override fun run(context: Context, arguments: String): String =
        NovelAssetToolExecutor.search(NovelAssetRepository.fromContext(context), arguments)
}

/** 资料建档：新建一条资料。 */
class NovelMaterialsAddTool : QuroTool {
    override val name = "novel_materials_add"
    override val description = "新建一条作品资料（八类统一入口，type 分流）：" +
        "人物=定位/性别/年龄/外貌/性格/出身/目标/关系；势力=分类/领袖/地盘/实力/成员；地点=分类/特征/规则；" +
        "物品=分类/品阶/持有者/功效/是否关键；能力=分类/品阶/效果/消耗/修习条件；情节线=状态/涉及章节/相关人物；" +
        "世界观设定=分类/正文/铁律清单；参考资料=分类/正文/出处/链接。" +
        "何时用：用户或创作流程要把新角色/势力/地点/物品/能力/情节线/设定/参考资料写进资料库；AI 读完前文补登新实体。" +
        "触发词：建档、新建人物、加个设定、登记势力、补充资料、创建角色卡、记一下。" +
        "参数：type（必填）、workId（作品 id，必填，先用 novel_list_works 获取）、name（资料名，必填）、" +
        "description（一句话简介，可选）、tags（标签数组，可选）+ 各类特有字段（见 schema，未传的字段取空/默认值）。" +
        "返回：新建资料的完整 JSON（含 id）。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "type":{"type":"string","description":"资料类别（必填）","enum":["character","faction","location","item","ability","plotline","setting","reference"]},
            "workId":{"type":"string","description":"作品 id（必填，先用 novel_list_works 获取）"},
            "name":{"type":"string","description":"资料名（必填）：人物名/势力名/地点名/物品名/能力名/情节线名/设定名/资料标题"},
            "description":{"type":"string","description":"一句话简介（可选）"},
            "tags":{"type":"array","items":{"type":"string"},"description":"标签（可选），如 [\"主角\",\"重生\"]"},
            "role":{"type":"string","description":"[人物] 定位：主角/配角/反派/龙套"},
            "gender":{"type":"string","description":"[人物] 性别"},
            "age":{"type":"string","description":"[人物] 年龄（可为“三千岁”这类文本）"},
            "appearance":{"type":"string","description":"[人物] 外貌"},
            "personality":{"type":"string","description":"[人物] 性格"},
            "background":{"type":"string","description":"[人物] 出身与经历"},
            "goal":{"type":"string","description":"[人物] 目标与动机"},
            "relations":{"type":"array","description":"[人物] 关系速记","items":{"type":"object","properties":{"name":{"type":"string","description":"关系人名字"},"relation":{"type":"string","description":"关系，如 师父/宿敌/道侣"},"note":{"type":"string","description":"备注"}}}},
            "category":{"type":"string","description":"[势力/地点/物品/能力/设定/参考] 分类：势力=正道/魔道/中立/宗门/帝国/家族；地点=城市/秘境/宗门/国家/遗迹；物品=武器/法宝/丹药/秘籍/材料；能力=攻击/防御/身法/治疗/辅助/法则；设定=修炼体系/世界规则/历史/社会/铁律；参考=文风参考/素材/资料/引文"},
            "leader":{"type":"string","description":"[势力] 领袖（人物名）"},
            "territory":{"type":"string","description":"[势力] 地盘/据点"},
            "strength":{"type":"string","description":"[势力] 实力层级，如 一流/顶尖/上古遗族"},
            "members":{"type":"array","items":{"type":"string"},"description":"[势力] 成员人名列表"},
            "features":{"type":"string","description":"[地点] 地理特征与资源"},
            "rules":{"type":"string","description":"[地点] 特殊规则与限制"},
            "grade":{"type":"string","description":"[物品/能力] 品阶/等级，如 天阶上品"},
            "owner":{"type":"string","description":"[物品] 持有者（人物名）"},
            "effect":{"type":"string","description":"[物品/能力] 功效/效果"},
            "isKey":{"type":"boolean","description":"[物品] 是否关键道具"},
            "cost":{"type":"string","description":"[能力] 消耗与代价"},
            "requirements":{"type":"string","description":"[能力] 修习条件"},
            "status":{"type":"string","description":"[情节线] 状态","enum":["ongoing","completed","abandoned"]},
            "chapters":{"type":"array","items":{"type":"string"},"description":"[情节线] 涉及章节 id 列表"},
            "characters":{"type":"array","items":{"type":"string"},"description":"[情节线] 相关人物名列表"},
            "content":{"type":"string","description":"[世界观设定/参考资料] 正文"},
            "constraints":{"type":"array","items":{"type":"string"},"description":"[世界观设定] 铁律/硬约束，如 \"主角不能死\"、\"修炼上限是渡劫期\""},
            "source":{"type":"string","description":"[参考资料] 出处"},
            "sourceUrl":{"type":"string","description":"[参考资料] 链接"}
        },
        "required":["type","workId","name"]
    }"""

    override fun run(context: Context, arguments: String): String =
        NovelAssetToolExecutor.add(NovelAssetRepository.fromContext(context), arguments)
}

/** 资料更新：按 id/name 定位，浅合并（只改传入字段）。 */
class NovelMaterialsUpdateTool : QuroTool {
    override val name = "novel_materials_update"
    override val description = "更新一条已有资料：按 id 或 name 定位，浅合并（只改传入的字段，未传的保持原值）。" +
        "何时用：补写人物性格/目标、势力实力变化、情节线状态推进、设定修订。" +
        "触发词：更新、改一下、补充设定、状态改为已完成。" +
        "参数：type（必填）、workId（必填）、id 或 name（定位）+ 要改的字段（name 可改名，description/tags/各类特有字段见 schema）。" +
        "不允许修改 id/workId/createdAt；更新后返回完整 JSON。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "type":{"type":"string","description":"资料类别（必填）","enum":["character","faction","location","item","ability","plotline","setting","reference"]},
            "workId":{"type":"string","description":"作品 id（必填，先用 novel_list_works 获取）"},
            "id":{"type":"string","description":"资料 id（定位用；与 name 二选一，优先）"},
            "name":{"type":"string","description":"资料名（定位用；也可作为改名后的新名字）"},
            "description":{"type":"string","description":"一句话简介（可选）"},
            "tags":{"type":"array","items":{"type":"string"},"description":"标签（可选），如 [\"主角\",\"重生\"]"},
            "role":{"type":"string","description":"[人物] 定位：主角/配角/反派/龙套"},
            "gender":{"type":"string","description":"[人物] 性别"},
            "age":{"type":"string","description":"[人物] 年龄"},
            "appearance":{"type":"string","description":"[人物] 外貌"},
            "personality":{"type":"string","description":"[人物] 性格"},
            "background":{"type":"string","description":"[人物] 出身与经历"},
            "goal":{"type":"string","description":"[人物] 目标与动机"},
            "relations":{"type":"array","description":"[人物] 关系速记","items":{"type":"object","properties":{"name":{"type":"string","description":"关系人名字"},"relation":{"type":"string","description":"关系"},"note":{"type":"string","description":"备注"}}}},
            "category":{"type":"string","description":"[势力/地点/物品/能力/设定/参考] 分类"},
            "leader":{"type":"string","description":"[势力] 领袖（人物名）"},
            "territory":{"type":"string","description":"[势力] 地盘/据点"},
            "strength":{"type":"string","description":"[势力] 实力层级"},
            "members":{"type":"array","items":{"type":"string"},"description":"[势力] 成员人名列表"},
            "features":{"type":"string","description":"[地点] 地理特征与资源"},
            "rules":{"type":"string","description":"[地点] 特殊规则与限制"},
            "grade":{"type":"string","description":"[物品/能力] 品阶/等级"},
            "owner":{"type":"string","description":"[物品] 持有者（人物名）"},
            "effect":{"type":"string","description":"[物品/能力] 功效/效果"},
            "isKey":{"type":"boolean","description":"[物品] 是否关键道具"},
            "cost":{"type":"string","description":"[能力] 消耗与代价"},
            "requirements":{"type":"string","description":"[能力] 修习条件"},
            "status":{"type":"string","description":"[情节线] 状态","enum":["ongoing","completed","abandoned"]},
            "chapters":{"type":"array","items":{"type":"string"},"description":"[情节线] 涉及章节 id 列表"},
            "characters":{"type":"array","items":{"type":"string"},"description":"[情节线] 相关人物名列表"},
            "content":{"type":"string","description":"[世界观设定/参考资料] 正文"},
            "constraints":{"type":"array","items":{"type":"string"},"description":"[世界观设定] 铁律/硬约束"},
            "source":{"type":"string","description":"[参考资料] 出处"},
            "sourceUrl":{"type":"string","description":"[参考资料] 链接"}
        },
        "required":["type","workId"]
    }"""

    override fun run(context: Context, arguments: String): String =
        NovelAssetToolExecutor.update(NovelAssetRepository.fromContext(context), arguments)
}

/** 资料删除：按 id/name 定位；有其他资料按名字引用它时警告（不阻断）。 */
class NovelMaterialsDeleteTool : QuroTool {
    override val name = "novel_materials_delete"
    override val description = "删除一条作品资料（按 id 或 name 定位）。" +
        "删除前自动检查引用完整性：其他资料若按名字引用它（如情节线的相关人物、势力的领袖/成员、物品的持有者、其他人物的关系人），" +
        "会列出引用提醒——警告但不阻断，需人工/模型确认后自行决定是否同步更新那些资料。" +
        "何时用：用户明确要求删除；建档错误需要移除。" +
        "触发词：删除资料、删掉这个人物、移除设定。" +
        "参数：type（必填）、workId（必填）、id 或 name。返回：删除结果 + 引用提醒（若有）。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "type":{"type":"string","description":"资料类别（必填）","enum":["character","faction","location","item","ability","plotline","setting","reference"]},
            "workId":{"type":"string","description":"作品 id（必填，先用 novel_list_works 获取）"},
            "id":{"type":"string","description":"资料 id（与 name 二选一，优先）"},
            "name":{"type":"string","description":"资料名（精确匹配；同名多条时会列出全部 id 请改用 id）"}
        },
        "required":["type","workId"]
    }"""

    override fun run(context: Context, arguments: String): String =
        NovelAssetToolExecutor.delete(NovelAssetRepository.fromContext(context), arguments)
}
