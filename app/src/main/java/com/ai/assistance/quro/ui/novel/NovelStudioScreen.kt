package com.ai.assistance.quro.ui.novel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.quro.core.novel.NovelChapter
import com.ai.assistance.quro.core.novel.NovelChapterStatus
import com.ai.assistance.quro.core.novel.asset.AssetType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 写作工作台（Phase 6 主界面）。
 *
 * 规范：[docs/design/AniseWrite_UI_SPEC.md] §5。四个 Tab：**章节 / 资料库 / 关系图 / 统计**。
 * 数据全部经 [NovelStudioData]（仓储在 Phase 5 已与 Phase 1/3a/3b 同源），界面不直接碰文件。
 */
private enum class NovelTab(val label: String, val icon: ImageVector) {
    CHAPTERS("章节", Icons.Filled.List),
    ASSETS("资料库", Icons.Filled.Folder),
    GRAPH("关系图", Icons.Filled.Share),
    STATS("统计", Icons.Filled.DateRange),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelStudioScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val data = remember { NovelStudioData(ctx) }
    val scope = rememberCoroutineScope()

    var snapshot by remember { mutableStateOf(NovelStudioSnapshot(loading = true)) }
    var tab by remember { mutableStateOf(NovelTab.CHAPTERS) }
    var editing by remember { mutableStateOf<NovelEditTarget?>(null) }

    // 首次加载 + 每次刷新：仓储 IO 放到 IO 线程
    LaunchedEffect(Unit) {
        snapshot = NovelStudioSnapshot(loading = true)
        snapshot = withContext(Dispatchers.IO) { data.load() }
    }

    ProvideNovelPalette {
        if (editing != null) {
            val target = editing!!
            NovelChapterEditor(
                workId = target.workId,
                chapter = target.chapter,
                onClose = { editing = null },
                onSave = { content ->
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            data.writeChapterContent(target.workId, target.chapter.id, content)
                        }
                        editing = null
                        snapshot = withContext(Dispatchers.IO) { data.load(snapshot.selectedWorkId) }
                    }
                },
                onReadContent = { workId, chapterId ->
                    withContext(Dispatchers.IO) { data.readChapterContent(workId, chapterId) }
                },
            )
            return@ProvideNovelPalette
        }

        Scaffold(
            containerColor = novelPalette.pageBackground,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = snapshot.selectedWork?.title ?: "写作工作台",
                            style = NovelType.title3,
                            color = novelPalette.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(
                                imageVector = Icons.Filled.ArrowBack,
                                contentDescription = "返回",
                                tint = novelPalette.accent,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = novelPalette.pageBackground,
                    ),
                )
            },
            bottomBar = { NovelTabBar(current = tab) { tab = it } },
        ) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                when (tab) {
                    NovelTab.CHAPTERS -> ChaptersTab(
                        snapshot = snapshot,
                        onSelectWork = { workId ->
                            scope.launch {
                                snapshot = withContext(Dispatchers.IO) { data.load(workId) }
                            }
                        },
                        onOpenChapter = { chapter -> editing = NovelEditTarget(snapshot.selectedWorkId ?: "", chapter) },
                        onCreateChapter = { volumeId ->
                            scope.launch {
                                val workId = snapshot.selectedWorkId
                                if (workId != null && volumeId != null) {
                                    withContext(Dispatchers.IO) { data.createChapter(workId, volumeId, "新章节") }
                                    snapshot = withContext(Dispatchers.IO) { data.load(workId) }
                                }
                            }
                        },
                        onCreateWork = {
                            scope.launch {
                                withContext(Dispatchers.IO) { data.createWork("新作品") }
                                snapshot = withContext(Dispatchers.IO) { data.load() }
                            }
                        },
                    )

                    NovelTab.ASSETS -> AssetsTab(snapshot)

                    NovelTab.GRAPH -> GraphTab(snapshot)

                    NovelTab.STATS -> StatsTab(snapshot)
                }
            }
        }
    }
}

/** 底部 Tab 栏：49dp、不透明 pageBackground、选中项 accent。 */
@Composable
private fun NovelTabBar(current: NovelTab, onSelect: (NovelTab) -> Unit) {
    Surface(color = novelPalette.pageBackground) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(NovelDesign.tabBarHeight),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (t in NovelTab.entries) {
                val selected = t == current
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable { onSelect(t) }
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Icon(
                        imageVector = t.icon,
                        contentDescription = t.label,
                        tint = if (selected) novelPalette.accent else novelPalette.textTertiary,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(
                        text = t.label,
                        style = NovelType.caption1,
                        color = if (selected) novelPalette.accent else novelPalette.textTertiary,
                    )
                }
            }
        }
    }
}

/** 编辑目标（作品 id + 章节）。 */
private data class NovelEditTarget(val workId: String, val chapter: NovelChapter)

/**
 * 章节 Tab：作品选择 + 按卷分组的章节列表。
 * 章节行显示状态与字数（规范 §5.4）。
 */
@Composable
private fun ChaptersTab(
    snapshot: NovelStudioSnapshot,
    onSelectWork: (String) -> Unit,
    onOpenChapter: (NovelChapter) -> Unit,
    onCreateChapter: (String) -> Unit,
    onCreateWork: () -> Unit,
) {
    if (snapshot.works.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            NovelEmptyState(
                title = "还没有作品",
                hint = "新建一本作品，开始你的第一章。",
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = onCreateWork,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NovelDesign.screenHorizontalPadding, vertical = 16.dp),
            ) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("新建作品")
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
    ) {
        if (snapshot.works.size > 1) {
            item {
                Column(Modifier.padding(horizontal = NovelDesign.screenHorizontalPadding)) {
                    Spacer(Modifier.height(NovelDesign.firstSectionTopGap))
                    NovelSectionHeader("作品")
                }
            }
            item {
                NovelGroupedCard(
                    modifier = Modifier.padding(horizontal = NovelDesign.screenHorizontalPadding),
                ) {
                    snapshot.works.forEachIndexed { index, work ->
                        if (index > 0) NovelDivider()
                        NovelGroupedRow(
                            title = work.title,
                            subtitle = "${work.genre.ifBlank { "未设流派" }} · ${work.chapters.size} 章",
                            iconColor = novelPalette.accent,
                            icon = {
                                Icon(
                                    Icons.Filled.List,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            showChevron = work.id != snapshot.selectedWorkId,
                            onClick = { onSelectWork(work.id) },
                        )
                    }
                }
            }
        }

        snapshot.volumes.forEach { volume ->
            item(key = "vol-${volume.id}") {
                Column(Modifier.padding(horizontal = NovelDesign.screenHorizontalPadding)) {
                    Spacer(Modifier.height(NovelDesign.sectionTopGap))
                    NovelSectionHeader(volume.title)
                }
            }
            item(key = "chapters-${volume.id}") {
                val chapters = snapshot.chaptersOf(volume.id)
                NovelGroupedCard(
                    modifier = Modifier.padding(horizontal = NovelDesign.screenHorizontalPadding),
                ) {
                    if (chapters.isEmpty()) {
                        Text(
                            text = "本卷还没有章节",
                            style = NovelType.callout,
                            color = novelPalette.textTertiary,
                            modifier = Modifier.padding(NovelDesign.screenHorizontalPadding),
                        )
                    } else {
                        chapters.forEachIndexed { index, chapter ->
                            if (index > 0) NovelDivider()
                            NovelGroupedRow(
                                title = chapter.title,
                                subtitle = "${chapterStatusLabel(chapter.status)} · ${chapter.wordCount} 字",
                                trailing = chapter.updatedAt.takeIf { it > 0 }?.let { formatDate(it) },
                                onClick = { onOpenChapter(chapter) },
                            )
                        }
                    }
                }
            }
            item(key = "add-${volume.id}") {
                TextButton(
                    onClick = { onCreateChapter(volume.id) },
                    modifier = Modifier.padding(horizontal = NovelDesign.screenHorizontalPadding),
                ) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp), tint = novelPalette.accent)
                    Spacer(Modifier.width(4.dp))
                    Text("新建章节", color = novelPalette.accent, style = NovelType.subhead)
                }
            }
        }
    }
}

/** 章节状态文案。 */
private fun chapterStatusLabel(status: NovelChapterStatus): String = when (status) {
    NovelChapterStatus.UNWRITTEN -> "未写"
    NovelChapterStatus.DRAFT -> "草稿"
    NovelChapterStatus.POLISHING -> "润色中"
    NovelChapterStatus.COMPLETED -> "已完成"
}

/** 时间戳 → MM-dd。 */
private fun formatDate(ts: Long): String {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = ts
    val m = cal.get(java.util.Calendar.MONTH) + 1
    val d = cal.get(java.util.Calendar.DAY_OF_MONTH)
    return "%02d-%02d".format(m, d)
}

/**
 * 资料库 Tab：八类资料分组列表（规范 §5.4）。
 */
@Composable
private fun AssetsTab(snapshot: NovelStudioSnapshot) {
    if (snapshot.assets.isEmpty()) {
        NovelEmptyState(
            title = "资料库还是空的",
            hint = "让 AI 帮你建人物、地点、势力与世界观设定，资料会归到这里。",
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
    ) {
        for (type in AssetType.entries) {
            val items = snapshot.assetsOf(type)
            if (items.isEmpty()) continue
            item(key = "asset-head-${type.key}") {
                Column(Modifier.padding(horizontal = NovelDesign.screenHorizontalPadding)) {
                    Spacer(Modifier.height(NovelDesign.sectionTopGap))
                    NovelSectionHeader("${type.displayName} · ${items.size}")
                }
            }
            item(key = "asset-body-${type.key}") {
                NovelGroupedCard(
                    modifier = Modifier.padding(horizontal = NovelDesign.screenHorizontalPadding),
                ) {
                    items.forEachIndexed { index, asset ->
                        if (index > 0) NovelDivider()
                        NovelGroupedRow(
                            title = asset.name,
                            subtitle = asset.description.ifBlank { type.displayName },
                            iconColor = categoryColor(type),
                            icon = {
                                Icon(
                                    Icons.Filled.Folder,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            showChevron = false,
                        )
                    }
                }
            }
        }
    }
}

/** 分类色：八类等饱和度等明度（规范 §5.2）。 */
private fun categoryColor(type: AssetType): Color = when (type) {
    AssetType.CHARACTER -> NovelCategoryColors.character
    AssetType.FACTION -> NovelCategoryColors.faction
    AssetType.LOCATION -> NovelCategoryColors.location
    AssetType.ITEM -> NovelCategoryColors.item
    AssetType.ABILITY -> NovelCategoryColors.ability
    AssetType.PLOTLINE -> NovelCategoryColors.plotline
    AssetType.SETTING -> NovelCategoryColors.setting
    AssetType.REFERENCE -> NovelCategoryColors.reference
}

/**
 * 关系图 Tab：人物关系可视化（规范 §5.8）。
 *
 * 数据：人物卡（姓名/身份/势力）+ 关系表 + 人物卡内关系，三源并入同一张图；
 * 布局：力导向（斥力 + 弹簧 + 阻尼），平移缩放，点选高亮一跳邻居。
 */
@Composable
private fun GraphTab(snapshot: NovelStudioSnapshot) {
    val model = remember(snapshot.assets, snapshot.relationships) {
        buildGraphModel(snapshot.assets, snapshot.relationships)
    }
    if (model.nodes.isEmpty()) {
        NovelEmptyState(
            title = "还没有人物关系",
            hint = "让 AI 记录人物之间的关系，这里会生成关系图。",
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    var selected by remember { mutableStateOf<String?>(null) }
    val selectedNode = selected?.let { model.byName[it] }

    Box(Modifier.fillMaxSize()) {
        NovelGraphView(
            model = model,
            modifier = Modifier.fillMaxSize(),
            onSelect = { selected = it },
        )

        NovelGraphLegend(
            model = model,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp),
        )

        if (selectedNode != null) {
            NovelGraphNodeDetail(
                node = selectedNode,
                neighbors = model.neighborsOf(selectedNode.name).toList(),
                modifier = Modifier.align(Alignment.BottomCenter),
                onDismiss = { selected = null },
            )
        }
    }
}

/** 统计 Tab：三个数据卡 + 概况分组。 */
@Composable
private fun StatsTab(snapshot: NovelStudioSnapshot) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NovelDesign.screenHorizontalPadding)
                    .padding(top = NovelDesign.firstSectionTopGap),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                NovelStatCard(
                    label = "总字数",
                    value = snapshot.totalWords.toString(),
                    modifier = Modifier.weight(1f),
                )
                NovelStatCard(
                    label = "章节数",
                    value = snapshot.chapterCount.toString(),
                    modifier = Modifier.weight(1f),
                )
                NovelStatCard(
                    label = "近 7 天",
                    value = snapshot.recentWords().toString(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item {
            Column(Modifier.padding(horizontal = NovelDesign.screenHorizontalPadding)) {
                Spacer(Modifier.height(NovelDesign.sectionTopGap))
                NovelSectionHeader("概况")
            }
        }
        item {
            NovelGroupedCard(
                modifier = Modifier.padding(horizontal = NovelDesign.screenHorizontalPadding),
            ) {
                NovelGroupedRow(
                    title = "卷",
                    trailing = snapshot.volumes.size.toString(),
                    showChevron = false,
                )
                NovelDivider()
                NovelGroupedRow(
                    title = "伏笔已埋 / 已回收",
                    trailing = "${snapshot.openHookCount} / ${snapshot.closedHookCount}",
                    showChevron = false,
                )
                NovelDivider()
                NovelGroupedRow(
                    title = "资料条目",
                    trailing = snapshot.assets.size.toString(),
                    showChevron = false,
                )
            }
        }
    }
}

/**
 * 章节编辑器：纯文本阅读面（规范 §5.5）。
 *
 * 富文本模块（`:novel-editor`，WebView 承载）尚未接线时，这里就是降级后的纯文本路径——
 * 与计划书一致：模块缺失不阻塞功能。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NovelChapterEditor(
    workId: String,
    chapter: NovelChapter,
    onClose: () -> Unit,
    onSave: (String) -> Unit,
    onReadContent: suspend (String, String) -> String,
) {
    var text by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(workId, chapter.id) {
        text = onReadContent(workId, chapter.id)
        loaded = true
    }

    ProvideNovelPalette {
        Scaffold(
            containerColor = novelPalette.pageBackground,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = chapter.title,
                            style = NovelType.headline,
                            color = novelPalette.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(
                                imageVector = Icons.Filled.ArrowBack,
                                contentDescription = "返回",
                                tint = novelPalette.accent,
                            )
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = { onSave(text) },
                            enabled = loaded,
                        ) {
                            Text("保存", color = novelPalette.accent, style = NovelType.subhead)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = novelPalette.pageBackground,
                    ),
                )
            },
        ) { padding ->
            androidx.compose.foundation.text.BasicTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = NovelDesign.screenHorizontalPadding, vertical = 12.dp),
                textStyle = NovelType.reading.copy(color = novelPalette.textPrimary),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(novelPalette.accent),
            )
        }
    }
}
