package com.ai.assistance.quro.ui.novel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.ai.assistance.quro.core.novel.asset.CharacterAsset
import com.ai.assistance.quro.core.novel.asset.FactionAsset
import com.ai.assistance.quro.core.novel.asset.NovelAsset
import com.ai.assistance.quro.core.novel.graph.NovelRelationship
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 人物关系图 · 数据模型与物理引擎（规范见 docs/design/AniseWrite_UI_SPEC.md §5.8）。
 *
 * 三层信息来源，全部并入同一张图：
 * 1. **人物卡**（`CharacterAsset`）：姓名、身份（role）、标签；
 * 2. **关系表**（`GraphStore.listRelationships`）：from→to、类型、强度 1-5、自定义颜色；
 * 3. **人物卡内关系**（`CharacterAsset.relations`）：卡内以名字引用的关系人。
 *
 * 势力聚类来自 `FactionAsset.members`（成员名列表）。
 */

/** 关系类型色板：八类语义色，等饱和度等明度，彼此可区分。 */
object NovelRelationColors {
    val love = Color(0xFFFF7A9C)      // 恋人
    val kin = Color(0xFFFFB340)       // 亲人
    val mentor = Color(0xFF6E8BFF)    // 师徒
    val ally = Color(0xFF4ADE80)      // 盟友
    val rival = Color(0xFFC084FC)     // 竞争
    val foe = Color(0xFFFF5C5C)       // 仇敌
    val hierarchy = Color(0xFF8FA9BE) // 上下级
    val other = Color(0xFF9AA4B2)     // 其他 / 自由文本

    /** 图例顺序（先情感后立场，符合阅读直觉）。 */
    val ordered: List<String> = listOf("恋人", "亲人", "师徒", "盟友", "竞争", "仇敌", "上下级", "其他")

    /** 类型 → 颜色。未登记的自由文本一律走「其他」灰。 */
    fun of(type: String): Color = when (type.trim()) {
        "恋人" -> love
        "亲人" -> kin
        "师徒" -> mentor
        "盟友" -> ally
        "竞争" -> rival
        "仇敌" -> foe
        "上下级" -> hierarchy
        else -> other
    }
}

/** 势力聚类色板（与资料八类色板同源但独立，互不干扰）。 */
object NovelFactionColors {
    private val palette = listOf(
        Color(0xFF4ADE80), Color(0xFF6E8BFF), Color(0xFFF59E42), Color(0xFFC084FC),
        Color(0xFF2BB8C4), Color(0xFFF2626B), Color(0xFF3FB984), Color(0xFF8FA9BE),
    )

    fun of(index: Int): Color = palette[((index % palette.size) + palette.size) % palette.size]
}

/** 图节点。位置与速度由物理引擎更新。 */
class GraphNode(
    val name: String,
    val role: String = "",
    var faction: String = "",
    var factionColor: Color = Color(0xFF8A8A8E),
    var degree: Int = 0,
    var x: Float = 0f,
    var y: Float = 0f,
    var vx: Float = 0f,
    var vy: Float = 0f,
    var pinned: Boolean = false,
) {
    /** 头像首字（中文取首字，英文取首字母大写）。 */
    val initial: String
        get() = name.trim().take(1).uppercase()

    /** 节点半径（dp）：度数越高越大，三档。 */
    fun radiusDp(): Float = when {
        degree <= 1 -> 24f
        degree <= 3 -> 31f
        else -> 38f
    }
}

/** 图边（有向：from → to）。 */
class GraphEdge(
    val from: String,
    val to: String,
    val type: String,
    val strength: Int,
    val color: Color,
) {
    /** 线宽（dp）：强度 1~5 → 1.2~5.4。 */
    fun widthDp(): Float = 1.2f + strength.coerceIn(1, 5) * 1.05f
}

/** 图数据：节点 + 边 + 势力分组。 */
class GraphModel(
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
) {
    val byName: Map<String, GraphNode> = nodes.associateBy { it.name }

    /** 势力 → 成员名。 */
    val factions: Map<String, List<String>> = nodes
        .filter { it.faction.isNotBlank() }
        .groupBy { it.faction }
        .mapValues { (_, v) -> v.map { it.name } }

    /** 与某节点直接相连的节点名（一跳邻居）。 */
    fun neighborsOf(name: String): Set<String> {
        val out = HashSet<String>()
        for (e in edges) {
            if (e.from == name) out.add(e.to)
            if (e.to == name) out.add(e.from)
        }
        return out
    }
}

/**
 * 从工作台快照构建关系图。
 *
 * @param assets 全部资料（人物卡与势力卡在此取）
 * @param relationships 关系表（from→to、类型、强度、自定义颜色）
 */
fun buildGraphModel(assets: List<NovelAsset>, relationships: List<NovelRelationship>): GraphModel {
    // 1) 人物节点
    val nodeMap = LinkedHashMap<String, GraphNode>()
    val characters = assets.filterIsInstance<CharacterAsset>()
    for (c in characters) {
        nodeMap[c.name] = GraphNode(name = c.name, role = c.role.ifBlank { c.tags.firstOrNull() ?: "" })
    }

    // 2) 势力聚类：成员名 → 势力名
    val factionOf = HashMap<String, String>()
    val factionIndex = HashMap<String, Int>()
    val factions = assets.filterIsInstance<FactionAsset>()
    for ((i, f) in factions.withIndex()) {
        factionIndex[f.name] = i
        for (m in f.members) factionOf[m.trim()] = f.name
    }
    for (c in characters) {
        val f = factionOf[c.name]
        if (!f.isNullOrBlank()) {
            val n = nodeMap[c.name] ?: continue
            n.faction = f
            n.factionColor = NovelFactionColors.of(factionIndex[f] ?: 0)
        }
    }

    // 3) 边：关系表 + 人物卡内关系
    val edges = ArrayList<GraphEdge>()
    val seen = HashSet<String>()
    for (r in relationships) {
        val from = r.fromName.trim()
        val to = r.toName.trim()
        if (from.isBlank() || to.isBlank()) continue
        val key = "$from>$to>${r.type}"
        if (!seen.add(key)) continue
        edges.add(
            GraphEdge(
                from = from,
                to = to,
                type = r.type.ifBlank { "其他" },
                strength = r.strength.coerceIn(1, 5),
                color = NovelRelationColors.of(r.type),
            )
        )
    }
    for (c in characters) {
        for (rel in c.relations) {
            val other = rel.name.trim()
            if (other.isBlank()) continue
            val key = "$other>${c.name}>${rel.relation}"
            if (!seen.add(key)) continue
            edges.add(
                GraphEdge(
                    from = other,
                    to = c.name,
                    type = rel.relation.ifBlank { "其他" },
                    strength = 3,
                    color = NovelRelationColors.of(rel.relation),
                )
            )
        }
    }

    // 4) 补齐只在关系里出现、没建档的人物
    for (e in edges) {
        for (n in listOf(e.from, e.to)) {
            if (!nodeMap.containsKey(n)) {
                nodeMap[n] = GraphNode(name = n, role = "未建档")
            }
        }
    }

    // 5) 度数
    for (e in edges) {
        nodeMap[e.from]?.degree = (nodeMap[e.from]?.degree ?: 0) + 1
        nodeMap[e.to]?.degree = (nodeMap[e.to]?.degree ?: 0) + 1
    }

    return GraphModel(nodes = nodeMap.values.toList(), edges = edges)
}

/**
 * 力导向布局引擎：节点斥力 + 边弹簧 + 阻尼 + 向心力。
 *
 * 参数按「一张手机上 10~40 个人物」调过：理想边长 150dp，斥力常数 5200，
 * 弹簧系数 0.035，阻尼 0.86，向心 0.012。迭代 300 步或动能低于阈值即判定收敛。
 */
class GraphSimulator(private val model: GraphModel) {

    private val nodes = model.nodes
    private val edges = model.edges
    private var cooling = 1.0f
    var steps = 0
        private set

    /** 初始环形布局（保证第一帧就有可读布局，再交给物理收敛）。 */
    fun seed(width: Float, height: Float) {
        if (nodes.isEmpty()) return
        val r = minOf(width, height) * 0.32f
        val cx = width / 2f
        val cy = height / 2f
        nodes.forEachIndexed { i, n ->
            val a = (2.0 * Math.PI * i / nodes.size) - Math.PI / 2.0
            n.x = cx + (r * cos(a)).toFloat()
            n.y = cy + (r * sin(a)).toFloat()
        }
    }

    /** 单步推进。dt 为秒。 */
    fun step(dt: Float) {
        if (nodes.isEmpty()) return
        val k = 5200f
        // 斥力（所有节点对）
        for (i in nodes.indices) {
            val a = nodes[i]
            for (j in i + 1 until nodes.size) {
                val b = nodes[j]
                var dx = a.x - b.x
                var dy = a.y - b.y
                var d2 = dx * dx + dy * dy
                if (d2 < 1f) {
                    // 完全重合时给个确定性的微小偏移，避免除零
                    dx = (i - j).toFloat() * 0.5f + 0.1f
                    dy = 0.1f
                    d2 = dx * dx + dy * dy
                }
                val d = sqrt(d2)
                val f = (k * cooling) / d2
                val fx = (dx / d) * f
                val fy = (dy / d) * f
                a.vx += fx; a.vy += fy
                b.vx -= fx; b.vy -= fy
            }
        }
        // 弹簧（边）
        val ideal = 150f
        for (e in edges) {
            val a = model.byName[e.from] ?: continue
            val b = model.byName[e.to] ?: continue
            val dx = b.x - a.x
            val dy = b.y - a.y
            val d = hypot(dx, dy).coerceAtLeast(0.01f)
            val f = (d - ideal) * 0.035f * (e.strength.coerceIn(1, 5) / 3f)
            val fx = (dx / d) * f
            val fy = (dy / d) * f
            a.vx += fx; a.vy += fy
            b.vx -= fx; b.vy -= fy
        }
        // 向心力 + 阻尼 + 积分
        var energy = 0f
        for (n in nodes) {
            if (n.pinned) {
                n.vx = 0f; n.vy = 0f
                continue
            }
            n.vx -= n.x * 0.012f
            n.vy -= n.y * 0.012f
            n.vx *= 0.86f
            n.vy *= 0.86f
            n.x += n.vx * dt
            n.y += n.vy * dt
            energy += n.vx * n.vx + n.vy * n.vy
        }
        cooling = (cooling * 0.995f).coerceAtLeast(0.12f)
        steps++
        lastEnergy = energy / nodes.size
    }

    var lastEnergy: Float = 0f
        private set

    /** 是否收敛（动能足够小或迭代超限）。 */
    fun settled(): Boolean = steps >= 300 || (steps > 30 && lastEnergy < 0.02f)
}

/**
 * 关系图视图：Canvas 绘制 + 平移缩放 + 点选高亮。
 *
 * 绘制顺序（自底向上）：势力圈层 → 边（线 + 箭头 + 关系名）→ 节点（头像 + 名字 + 身份）。
 * 所有坐标在**图坐标系**内计算，屏幕变换（pan/zoom）只在最外层应用一次。
 */
@Composable
fun NovelGraphView(
    model: GraphModel,
    modifier: Modifier = Modifier,
    onSelect: (String?) -> Unit = {},
) {
    val palette = novelPalette
    val sim = remember(model) { GraphSimulator(model) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var selected by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var canvasSize by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current

    // 物理推进：等画布拿到尺寸后播环形种子布局，再每帧一步直到收敛
    LaunchedEffect(model, canvasSize) {
        if (canvasSize == Offset.Zero) return@LaunchedEffect
        sim.seed(canvasSize.x, canvasSize.y)
        var last = 0L
        while (!sim.settled()) {
            withFrameNanos { t ->
                if (last == 0L) last = t
                val dt = ((t - last) / 1_000_000_000f).coerceIn(0.001f, 0.033f)
                last = t
                sim.step(dt)
                tick++
            }
        }
    }

    val highlight = selected?.let { model.neighborsOf(it)?.plus(it) }

    Box(
        modifier = modifier
            .background(palette.pageBackground)
            .onSizeChanged { canvasSize = Offset(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val gx = (offset.x - pan.x) / zoom
                    val gy = (offset.y - pan.y) / zoom
                    val name = hitTest(model, Offset(gx, gy), pan, zoom, density)?.name
                    if (name == selected) selected = null else selected = name
                    onSelect(name)
                }
            }
            .pointerInput(model) {
                detectTransformGestures { centroid, panChange, zoomChange, _ ->
                    zoom = (zoom * zoomChange).coerceIn(0.4f, 3f)
                    // 命中节点 → 拖动节点（并钉住，物理引擎不再移动它）；否则平移画布
                    val hit = hitTest(model, centroid, pan, zoom, density)
                    if (hit != null && zoomChange == 1f) {
                        hit.x += panChange.x / zoom
                        hit.y += panChange.y / zoom
                        hit.pinned = true
                    } else {
                        pan += panChange
                    }
                }
            },
    ) {
        Canvas(
            Modifier.fillMaxSize(),
        ) {
            val scale = zoom
            val offset = pan
            val nodeColor = palette.textPrimary
            val edgeLabel = palette.textSecondary

            // 图坐标 → 画布坐标
            fun px(x: Float): Float = x * scale + offset.x
            fun py(y: Float): Float = y * scale + offset.y

            @Suppress("UNUSED_VARIABLE")
            val tickRead = tick // 建立重绘依赖

            // ① 势力圈层
            for ((faction, members) in model.factions) {
                val pts = members.mapNotNull { model.byName[it] }
                if (pts.size < 2) continue
                val minX = pts.minOf { it.x }
                val maxX = pts.maxOf { it.x }
                val minY = pts.minOf { it.y }
                val maxY = pts.maxOf { it.y }
                val padPx = 34f * density.density
                val c = Offset(px((minX + maxX) / 2f), py((minY + maxY) / 2f))
                val rx = (maxX - minX) / 2f * scale + padPx
                val ry = (maxY - minY) / 2f * scale + padPx
                val color = pts.first().factionColor
                drawOval(
                    color = color.copy(alpha = 0.10f),
                    topLeft = Offset(c.x - rx, c.y - ry),
                    size = androidx.compose.ui.geometry.Size(rx * 2, ry * 2),
                )
                drawOval(
                    color = color.copy(alpha = 0.45f),
                    topLeft = Offset(c.x - rx, c.y - ry),
                    size = androidx.compose.ui.geometry.Size(rx * 2, ry * 2),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 1.2f * density.density,
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                            floatArrayOf(8f * density.density, 6f * density.density)
                        ),
                    ),
                )
            }

            // ② 边
            for (e in model.edges) {
                val a = model.byName[e.from] ?: continue
                val b = model.byName[e.to] ?: continue
                val dimmed = selected != null && !(highlight?.contains(e.from) == true && highlight.contains(e.to))
                val alpha = if (dimmed) 0.16f else 0.9f
                val ax = px(a.x); val ay = py(a.y)
                val bx = px(b.x); val by = py(b.y)
                val dx = bx - ax
                val dy = by - ay
                val len = hypot(dx, dy).coerceAtLeast(0.01f)
                val ux = dx / len
                val uy = dy / len
                val ra = with(density) { a.radiusDp().dp.toPx() } * scale
                val rb = with(density) { b.radiusDp().dp.toPx() } * scale
                val start = Offset(ax + ux * ra, ay + uy * ra)
                val end = Offset(bx - ux * (rb + 6f * density.density), by - uy * (rb + 6f * density.density))
                drawLine(
                    color = e.color.copy(alpha = alpha),
                    start = start,
                    end = end,
                    strokeWidth = e.widthDp() * density.density * scale.coerceAtLeast(0.5f),
                )
                // 方向箭头
                val arrow = 7f * density.density
                val tip = Offset(bx - ux * rb, by - uy * rb)
                val p1 = Offset(tip.x - ux * arrow - uy * arrow * 0.6f, tip.y - uy * arrow + ux * arrow * 0.6f)
                val p2 = Offset(tip.x - ux * arrow + uy * arrow * 0.6f, tip.y - uy * arrow - ux * arrow * 0.6f)
                drawPath(
                    path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(tip.x, tip.y)
                        lineTo(p1.x, p1.y)
                        lineTo(p2.x, p2.y)
                        close()
                    },
                    color = e.color.copy(alpha = alpha),
                )
                // 关系名（只标强度高的，避免糊）
                if (e.strength >= 3 && scale > 0.6f) {
                    val mid = Offset((start.x + end.x) / 2f, (start.y + end.y) / 2f)
                    drawGraphText(
                        text = e.type,
                        center = mid,
                        color = edgeLabel.copy(alpha = if (dimmed) 0.2f else 0.95f),
                        textSizePx = 10f * density.density,
                        density = density.density,
                    )
                }
            }

            // ③ 节点
            for (n in model.nodes) {
                val dimmed = selected != null && highlight?.contains(n.name) != true
                val alpha = if (dimmed) 0.18f else 1f
                val r = with(density) { n.radiusDp().dp.toPx() } * scale
                val c = Offset(px(n.x), py(n.y))
                val isSel = selected == n.name
                // 选中环
                if (isSel) {
                    drawCircle(
                        color = palette.accent.copy(alpha = 0.35f),
                        radius = r + 6f * density.density,
                        center = c,
                    )
                }
                drawCircle(color = n.factionColor.copy(alpha = alpha), radius = r, center = c)
                drawCircle(
                    color = Color.White.copy(alpha = 0.35f * alpha),
                    radius = r,
                    center = c,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.2f * density.density),
                )
                // 首字
                drawGraphText(
                    text = n.initial,
                    center = Offset(c.x, c.y + (n.initial.length.coerceAtMost(1) * 0f)),
                    color = Color.White.copy(alpha = alpha),
                    textSizePx = (r * 0.78f),
                    density = density.density,
                    bold = true,
                    offsetY = r * 0.28f,
                )
                // 姓名 + 身份
                drawGraphText(
                    text = n.name,
                    center = Offset(c.x, c.y + r + 12f * density.density),
                    color = nodeColor.copy(alpha = alpha),
                    textSizePx = 11f * density.density,
                    density = density.density,
                    bold = true,
                )
                if (n.role.isNotBlank()) {
                    drawGraphText(
                        text = n.role,
                        center = Offset(c.x, c.y + r + 26f * density.density),
                        color = palette.textTertiary.copy(alpha = alpha),
                        textSizePx = 9.5f * density.density,
                        density = density.density,
                    )
                }
            }
        }
    }
}

/**
 * 在 Canvas 上绘制居中文本。
 * Compose 的 Canvas 没有 drawText，需借 [androidx.compose.ui.graphics.nativeCanvas]。
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGraphText(
    text: String,
    center: Offset,
    color: Color,
    textSizePx: Float,
    density: Float,
    bold: Boolean = false,
    offsetY: Float = 0f,
) {
    if (text.isBlank()) return
    drawIntoCanvas { canvas ->
        val paint = android.graphics.Paint().apply {
            this.color = color.toArgb()
            this.textSize = textSizePx
            this.isAntiAlias = true
            this.textAlign = android.graphics.Paint.Align.CENTER
            if (bold) this.typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        canvas.nativeCanvas.drawText(text, center.x, center.y + offsetY, paint)
    }
}

/** 关系图图例：当前图中出现过的关系类型。 */
@Composable
fun NovelGraphLegend(
    model: GraphModel,
    modifier: Modifier = Modifier,
) {
    val used = model.edges.map { it.type }.distinct()
    if (used.isEmpty()) return
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = novelPalette.cardSurface.copy(alpha = 0.94f),
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                text = "关系类型",
                style = NovelType.caption1,
                color = novelPalette.textTertiary,
            )
            Spacer(Modifier.height(6.dp))
            for (type in used.sortedBy { NovelRelationColors.ordered.indexOf(it).let { i -> if (i < 0) 99 else i } }) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                    Box(
                        Modifier
                            .size(width = 14.dp, height = 2.5.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(NovelRelationColors.of(type)),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = type,
                        style = NovelType.caption1,
                        color = novelPalette.textSecondary,
                    )
                }
            }
        }
    }
}

/** 选中人物的详情卡（底部弹出）。 */
@Composable
fun NovelGraphNodeDetail(
    node: GraphNode?,
    neighbors: List<String>,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit = {},
) {
    if (node == null) return
    Surface(
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        color = novelPalette.cardSurface,
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(node.factionColor),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = node.initial,
                        style = NovelType.footnote,
                        color = Color.White,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = node.name,
                    style = NovelType.headline,
                    color = novelPalette.textPrimary,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text("关闭", color = novelPalette.accent, style = NovelType.footnote)
                }
            }
            if (node.role.isNotBlank()) {
                Text(
                    text = node.role,
                    style = NovelType.footnote,
                    color = novelPalette.textSecondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (node.faction.isNotBlank()) {
                Text(
                    text = "所属势力：${node.faction}",
                    style = NovelType.caption1,
                    color = novelPalette.textTertiary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (neighbors.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "关系人：${neighbors.joinToString("、")}",
                    style = NovelType.caption1,
                    color = novelPalette.textSecondary,
                )
            }
        }
    }
}

/**
 * 命中检测：把屏幕坐标换算到图坐标，找最近且在半径内的节点。
 * 半径按节点度数分档（与绘制一致），并随缩放同步缩放。
 */
private fun hitTest(
    model: GraphModel,
    screen: Offset,
    pan: Offset,
    zoom: Float,
    density: Density,
): GraphNode? {
    if (model.nodes.isEmpty()) return null
    val gx = (screen.x - pan.x) / zoom
    val gy = (screen.y - pan.y) / zoom
    var best: GraphNode? = null
    var bestDist = Float.MAX_VALUE
    for (n in model.nodes) {
        val r = with(density) { n.radiusDp().dp.toPx() }
        val dx = n.x - gx
        val dy = n.y - gy
        val d = dx * dx + dy * dy
        if (d <= r * r && d < bestDist) {
            best = n
            bestDist = d
        }
    }
    return best
}
