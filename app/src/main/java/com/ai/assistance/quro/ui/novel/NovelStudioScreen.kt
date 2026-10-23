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
import androidx.compose.material.icons.filled.Star
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
import com.ai.assistance.quro.core.novel.graph.hook.NovelHook
import com.ai.assistance.quro.core.novel.NovelVolume
import com.ai.assistance.quro.core.novel.NovelWork
import com.ai.assistance.quro.core.novel.NovelChapterStatus
import com.ai.assistance.quro.core.novel.asset.AssetType
import com.ai.assistance.quro.core.novel.graph.NovelMemory
import com.ai.assistance.quro.core.tools.novel.NovelConsistencyCheckTool
import com.ai.assistance.quro.core.tools.novel.NovelShuangdianReportTool
import com.ai.assistance.quro.core.tools.novel.NovelTitleCandidatesTool
import com.ai.assistance.quro.core.tools.novel.NovelWaterReportTool
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
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
                        onRenameChapter = { chapter, newTitle ->
                            scope.launch {
                                val workId = snapshot.selectedWorkId
                                if (workId != null) {
                                    withContext(Dispatchers.IO) { data.renameChapter(workId, chapter.id, newTitle) }
                                    snapshot = withContext(Dispatchers.IO) { data.load(workId) }
                                }
                            }
                        },
                        onMoveChapter = { chapter, volumeId ->
                            scope.launch {
                                val workId = snapshot.selectedWorkId
                                if (workId != null) {
                                    withContext(Dispatchers.IO) { data.moveChapter(workId, chapter.id, volumeId) }
                                    snapshot = withContext(Dispatchers.IO) { data.load(workId) }
                                }
                            }
                        },
                        onDeleteChapter = { chapter ->
                            scope.launch {
                                val workId = snapshot.selectedWorkId
                                if (workId != null) {
                                    withContext(Dispatchers.IO) { data.deleteChapter(workId, chapter.id) }
                                    snapshot = withContext(Dispatchers.IO) { data.load(workId) }
                                }
                            }
                        },
                        onRenameWork = { work, newTitle ->
                            scope.launch {
                                withContext(Dispatchers.IO) { data.renameWork(work.id, newTitle) }
                                snapshot = withContext(Dispatchers.IO) { data.load(snapshot.selectedWorkId) }
                            }
                        },
                        onDeleteWork = { work ->
                            scope.launch {
                                withContext(Dispatchers.IO) { data.deleteWork(work.id) }
                                snapshot = withContext(Dispatchers.IO) { data.load() }
                            }
                        },
                        onRenameVolume = { volume, newTitle ->
                            scope.launch {
                                val workId = snapshot.selectedWorkId
                                if (workId != null) {
                                    withContext(Dispatchers.IO) { data.renameVolume(workId, volume.id, newTitle) }
                                    snapshot = withContext(Dispatchers.IO) { data.load(workId) }
                                }
                            }
                        },
                        onDeleteVolume = { volume ->
                            scope.launch {
                                val workId = snapshot.selectedWorkId
                                if (workId != null) {
                                    withContext(Dispatchers.IO) { data.deleteVolume(workId, volume.id) }
                                    snapshot = withContext(Dispatchers.IO) { data.load(workId) }
                                }
                            }
                        },
                        onCreateVolume = {
                            scope.launch {
                                val workId = snapshot.selectedWorkId
                                if (workId != null) {
                                    withContext(Dispatchers.IO) { data.createVolume(workId, "新卷") }
                                    snapshot = withContext(Dispatchers.IO) { data.load(workId) }
                                }
                            }
                        },
                        onSetChapterStatus = { chapter, status ->
                            scope.launch {
                                val workId = snapshot.selectedWorkId
                                if (workId != null) {
                                    withContext(Dispatchers.IO) { data.setChapterStatus(workId, chapter.id, status) }
                                    snapshot = withContext(Dispatchers.IO) { data.load(workId) }
                                }
                            }
                        },
                        onReorderChapters = { volumeId, orderedIds ->
                            scope.launch {
                                val workId = snapshot.selectedWorkId
                                if (workId != null) {
                                    withContext(Dispatchers.IO) { data.reorderChapters(workId, volumeId, orderedIds) }
                                    snapshot = withContext(Dispatchers.IO) { data.load(workId) }
                                }
                            }
                        },
                    )

                    NovelTab.ASSETS -> AssetsTab(snapshot)

                    NovelTab.GRAPH -> GraphTab(snapshot)

                    NovelTab.STATS -> StatsTab(
                        snapshot = snapshot,
                        data = data,
                        onRefresh = {
                            scope.launch {
                                snapshot = withContext(Dispatchers.IO) { data.load(snapshot.selectedWorkId) }
                            }
                        },
                    )
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
    onRenameChapter: (NovelChapter, String) -> Unit,
    onMoveChapter: (NovelChapter, String) -> Unit,
    onDeleteChapter: (NovelChapter) -> Unit,
    onRenameWork: (NovelWork, String) -> Unit,
    onDeleteWork: (NovelWork) -> Unit,
    onRenameVolume: (NovelVolume, String) -> Unit,
    onDeleteVolume: (NovelVolume) -> Unit,
    onCreateVolume: () -> Unit,
    onSetChapterStatus: (NovelChapter, NovelChapterStatus) -> Unit,
    onReorderChapters: (String, List<String>) -> Unit,
) {
    // 侧滑操作的目标章节
    var renaming by remember { mutableStateOf<NovelChapter?>(null) }
    var moving by remember { mutableStateOf<NovelChapter?>(null) }
    var deleting by remember { mutableStateOf<NovelChapter?>(null) }
    // 作品级操作
    var renamingWork by remember { mutableStateOf<NovelWork?>(null) }
    var deletingWork by remember { mutableStateOf<NovelWork?>(null) }
    // 卷级操作
    var renamingVolume by remember { mutableStateOf<NovelVolume?>(null) }
    var deletingVolume by remember { mutableStateOf<NovelVolume?>(null) }
    // 章节状态 / 伏笔详情
    var statusTarget by remember { mutableStateOf<NovelChapter?>(null) }
    var hookTarget by remember { mutableStateOf<NovelHook?>(null) }
    // 打磨-⑨ 卷内拖拽排序：拖拽卷 / 起点 / 悬停位 / 指尖偏移（px）
    var dragVolumeId by remember { mutableStateOf<String?>(null) }
    var dragFrom by remember { mutableStateOf(-1) }
    var dragTo by remember { mutableStateOf(-1) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val dragDensity = LocalDensity.current
    val dragStepPx = with(dragDensity) { 60.dp.toPx() }
    fun resetDrag() {
        dragVolumeId = null
        dragFrom = -1
        dragTo = -1
        dragOffsetY = 0f
    }

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
        if (snapshot.hooks.isNotEmpty()) {
            item {
                Column(Modifier.padding(horizontal = NovelDesign.screenHorizontalPadding)) {
                    Spacer(Modifier.height(NovelDesign.sectionTopGap))
                    NovelSectionHeader("伏笔 · ${snapshot.openHookCount} 已埋 / ${snapshot.closedHookCount} 已回收")
                }
            }
            item {
                NovelGroupedCard(
                    modifier = Modifier.padding(horizontal = NovelDesign.screenHorizontalPadding),
                ) {
                    snapshot.hooks.forEachIndexed { index, hook ->
                        if (index > 0) NovelDivider()
                        NovelGroupedRow(
                            title = hook.title,
                            subtitle = "${hook.status.label} · ${hook.priority.label}" +
                                (hook.characterNames.takeIf { it.isNotEmpty() }?.let { " · ${it.joinToString("、")}" } ?: ""),
                            iconColor = if (hook.status.key == "closed") Color(0xFF4ADE80) else Color(0xFFFFB340),
                            icon = {
                                Icon(
                                    Icons.Filled.Star,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            onClick = { hookTarget = hook },
                        )
                    }
                }
            }
        }
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
                    NovelSwipeRow(
                        actions = listOf(
                            NovelSwipeAction("改名", Color(0xFF8A8A8E)) { renamingWork = work },
                            NovelSwipeAction("删除", novelPalette.danger) { deletingWork = work },
                        ),
                    ) {
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
            item(key = "vol-act-${volume.id}") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = NovelDesign.screenHorizontalPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { renamingVolume = volume }) {
                        Text("卷改名", color = novelPalette.accent, style = NovelType.caption1)
                    }
                    Spacer(Modifier.width(10.dp))
                    TextButton(onClick = { deletingVolume = volume }) {
                        Text("删卷", color = novelPalette.danger, style = NovelType.caption1)
                    }
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
                            // 打磨-⑨：长按拖动排序（卷内）。拖过半格即与相邻行换位，松手落库。
                            val isDragging = dragVolumeId == volume.id && dragFrom == index
                            val shiftedY = when {
                                dragVolumeId != volume.id -> 0f
                                isDragging -> dragOffsetY
                                dragTo > dragFrom && index in (dragFrom + 1)..dragTo -> -dragStepPx
                                dragTo < dragFrom && index in dragTo until dragFrom -> dragStepPx
                                else -> 0f
                            }
                            Box(
                                Modifier
                                    .zIndex(if (isDragging) 1f else 0f)
                                    .graphicsLayer { translationY = shiftedY }
                                    .pointerInput(volume.id, chapters.map { it.id }) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                dragVolumeId = volume.id
                                                dragFrom = index
                                                dragTo = index
                                                dragOffsetY = 0f
                                            },
                                            onDragEnd = {
                                                if (dragVolumeId == volume.id && dragFrom >= 0 && dragTo != dragFrom) {
                                                    val ordered = chapters.toMutableList().apply {
                                                        val item = removeAt(dragFrom)
                                                        add(dragTo.coerceIn(0, size), item)
                                                    }.map { it.id }
                                                    onReorderChapters(volume.id, ordered)
                                                }
                                                resetDrag()
                                            },
                                            onDragCancel = { resetDrag() },
                                            onDrag = { change, delta ->
                                                change.consume()
                                                dragOffsetY += delta.y
                                                while (dragOffsetY > dragStepPx / 2f && dragTo < chapters.lastIndex) {
                                                    dragTo += 1
                                                    dragOffsetY -= dragStepPx
                                                }
                                                while (dragOffsetY < -dragStepPx / 2f && dragTo > 0) {
                                                    dragTo -= 1
                                                    dragOffsetY += dragStepPx
                                                }
                                            },
                                        )
                                    },
                            ) {
                                NovelSwipeRow(
                                    actions = listOf(
                                        NovelSwipeAction("移动", novelPalette.accent) { moving = chapter },
                                        NovelSwipeAction("改名", Color(0xFF8A8A8E)) { renaming = chapter },
                                        NovelSwipeAction("状态", Color(0xFF4ADE80)) { statusTarget = chapter },
                                        NovelSwipeAction("删除", novelPalette.danger) { deleting = chapter },
                                    ),
                                ) {
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
        item(key = "add-volume") {
            TextButton(
                onClick = { onCreateVolume() },
                modifier = Modifier.padding(horizontal = NovelDesign.screenHorizontalPadding),
            ) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp), tint = novelPalette.accent)
                Spacer(Modifier.width(4.dp))
                Text("新建卷", color = novelPalette.accent, style = NovelType.subhead)
            }
        }
    }

    // 侧滑操作对应的弹层：重命名（输入框）/ 移动（选卷抽屉）/ 删除（确认）
    renaming?.let { chapter ->
        NovelPromptDialog(
            title = "重命名章节",
            initial = chapter.title,
            onDismiss = { renaming = null },
            onConfirm = { newTitle ->
                if (newTitle.isNotBlank()) onRenameChapter(chapter, newTitle)
                renaming = null
            },
        )
    }
    moving?.let { chapter ->
        NovelSheet(onDismissRequest = { moving = null }) {
            NovelSectionHeader("移动到")
            snapshot.volumes.forEach { v ->
                NovelSheetRow(
                    title = v.title,
                    selected = v.id == chapter.volumeId,
                    showCheck = false,
                    onClick = {
                        if (v.id != chapter.volumeId) onMoveChapter(chapter, v.id)
                        moving = null
                    },
                )
                NovelDivider()
            }
        }
    }
    renamingVolume?.let { volume ->
        NovelPromptDialog(
            title = "重命名卷",
            initial = volume.title,
            onDismiss = { renamingVolume = null },
            onConfirm = { newTitle ->
                if (newTitle.isNotBlank()) onRenameVolume(volume, newTitle)
                renamingVolume = null
            },
        )
    }
    deletingVolume?.let { volume ->
        val chapterCount = snapshot.chaptersOf(volume.id).size
        NovelConfirmDialog(
            title = "删除卷",
            message = "卷「${volume.title}」将被删除" +
                (if (chapterCount > 0) "，其下 $chapterCount 章也会一并移除" else "") + "。此操作不可撤销。",
            confirmText = "删除",
            danger = true,
            onDismiss = { deletingVolume = null },
            onConfirm = {
                onDeleteVolume(volume)
                deletingVolume = null
            },
        )
    }
    statusTarget?.let { chapter ->
        NovelSheet(onDismissRequest = { statusTarget = null }) {
            NovelSectionHeader("章节状态")
            for (st in listOf(
                NovelChapterStatus.UNWRITTEN,
                NovelChapterStatus.DRAFT,
                NovelChapterStatus.POLISHING,
                NovelChapterStatus.COMPLETED,
            )) {
                NovelSheetRow(
                    title = chapterStatusLabel(st),
                    selected = chapter.status == st,
                    onClick = {
                        if (chapter.status != st) onSetChapterStatus(chapter, st)
                        statusTarget = null
                    },
                )
                NovelDivider()
            }
        }
    }
    hookTarget?.let { hook ->
        NovelSheet(onDismissRequest = { hookTarget = null }) {
            NovelSectionHeader("伏笔")
            Column(Modifier.padding(horizontal = NovelDesign.screenHorizontalPadding)) {
                Text(
                    text = hook.title,
                    style = NovelType.headline,
                    color = novelPalette.textPrimary,
                )
                if (hook.description.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = hook.description,
                        style = NovelType.callout,
                        color = novelPalette.textSecondary,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "状态：${hook.status.label} · 优先级：${hook.priority.label}",
                    style = NovelType.footnote,
                    color = novelPalette.textSecondary,
                )
                hook.plantedAt?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "埋设于：${it.title.ifBlank { it.chapterId }}",
                        style = NovelType.caption1,
                        color = novelPalette.textTertiary,
                    )
                }
                hook.resolvedAt?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "回收于：${it.title.ifBlank { it.chapterId }}",
                        style = NovelType.caption1,
                        color = novelPalette.textTertiary,
                    )
                }
                if (hook.characterNames.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "关联人物：${hook.characterNames.joinToString("、")}",
                        style = NovelType.caption1,
                        color = novelPalette.textTertiary,
                    )
                }
            }
            Spacer(Modifier.height(NovelDesign.sectionTopGap))
        }
    }
    renamingWork?.let { work ->
        NovelPromptDialog(
            title = "重命名作品",
            initial = work.title,
            onDismiss = { renamingWork = null },
            onConfirm = { newTitle ->
                if (newTitle.isNotBlank()) onRenameWork(work, newTitle)
                renamingWork = null
            },
        )
    }
    deletingWork?.let { work ->
        NovelConfirmDialog(
            title = "删除作品",
            message = "《${work.title}》将被删除，含 ${work.volumes.size} 卷 / ${work.chapters.size} 章与全部正文。此操作不可撤销。",
            confirmText = "删除",
            danger = true,
            onDismiss = { deletingWork = null },
            onConfirm = {
                onDeleteWork(work)
                deletingWork = null
            },
        )
    }
    deleting?.let { chapter ->
        NovelConfirmDialog(
            title = "删除章节",
            message = "「${chapter.title}」将被删除，正文一并移除。此操作不可撤销。",
            confirmText = "删除",
            danger = true,
            onDismiss = { deleting = null },
            onConfirm = {
                onDeleteChapter(chapter)
                deleting = null
            },
        )
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

/** 统计 Tab：三个数据卡 + 概况分组 + AI 分析（P1-6）+ 记忆（P1-7）。 */
@Composable
private fun StatsTab(
    snapshot: NovelStudioSnapshot,
    data: NovelStudioData,
    onRefresh: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<NovelAnalysisReportState?>(null) }
    var memoryDetail by remember { mutableStateOf<NovelMemory?>(null) }
    var memoryToDelete by remember { mutableStateOf<NovelMemory?>(null) }
    // 打磨-⑩ 二级指标（从快照现算，无额外 IO）
    val statChapters = snapshot.selectedWork?.chapters ?: emptyList()
    val avgChapterWords = if (statChapters.isEmpty()) 0 else snapshot.totalWords / statChapters.size
    val hookTotal = snapshot.openHookCount + snapshot.closedHookCount
    val hookRecovery = if (hookTotal == 0) "—" else "${snapshot.closedHookCount * 100 / hookTotal}%"
    val completedCount = statChapters.count { it.status == NovelChapterStatus.COMPLETED }
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NovelDesign.screenHorizontalPadding)
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                NovelStatCard(
                    label = "平均章字数",
                    value = avgChapterWords.toString(),
                    modifier = Modifier.weight(1f),
                )
                NovelStatCard(
                    label = "伏笔回收率",
                    value = hookRecovery,
                    modifier = Modifier.weight(1f),
                )
                NovelStatCard(
                    label = "已完成",
                    value = completedCount.toString(),
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
                NovelDivider()
                NovelGroupedRow(
                    title = "章节状态",
                    subtitle = "已完成 $completedCount · 润色中 ${statChapters.count { it.status == NovelChapterStatus.POLISHING }} · 草稿 ${statChapters.count { it.status == NovelChapterStatus.DRAFT }} · 未写 ${statChapters.count { it.status == NovelChapterStatus.UNWRITTEN }}",
                    showChevron = false,
                )
            }
        }

        // ── P1-6：AI 分析入口（爽点 / 水文 / 标题 / 一致性）──────────────
        if (snapshot.selectedWork != null) {
            item(key = "analysis-head") {
                Column(Modifier.padding(horizontal = NovelDesign.screenHorizontalPadding)) {
                    Spacer(Modifier.height(NovelDesign.sectionTopGap))
                    NovelSectionHeader("AI 分析")
                }
            }
            item(key = "analysis-body") {
                NovelGroupedCard(
                    modifier = Modifier.padding(horizontal = NovelDesign.screenHorizontalPadding),
                ) {
                    for ((index, kind) in NovelAnalysisKind.entries.withIndex()) {
                        if (index > 0) NovelDivider()
                        NovelGroupedRow(
                            title = kind.title,
                            subtitle = kind.hint,
                            showChevron = false,
                            onClick = {
                                val work = snapshot.selectedWork
                                if (work != null) {
                                    report = NovelAnalysisReportState(kind.title, "正在生成…", loading = true)
                                    scope.launch {
                                        val text = runNovelAnalysisTool(ctx.applicationContext, kind, work.id)
                                        report = NovelAnalysisReportState(kind.title, text, loading = false)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }

        // ── P1-7：记忆（memories）──────────────────────────────────
        item(key = "memory-head") {
            Column(Modifier.padding(horizontal = NovelDesign.screenHorizontalPadding)) {
                Spacer(Modifier.height(NovelDesign.sectionTopGap))
                NovelSectionHeader("记忆 · ${snapshot.memories.size}")
            }
        }
        item(key = "memory-body") {
            NovelGroupedCard(
                modifier = Modifier.padding(horizontal = NovelDesign.screenHorizontalPadding),
            ) {
                if (snapshot.memories.isEmpty()) {
                    NovelGroupedRow(
                        title = "暂无记忆",
                        subtitle = "AI 写作中会自动沉淀关键事实，这里可以随时查看与清理。",
                        showChevron = false,
                    )
                } else {
                    for ((index, m) in snapshot.memories.sortedByDescending { it.updatedAt }.take(20).withIndex()) {
                        if (index > 0) NovelDivider()
                        NovelGroupedRow(
                            title = memoryTitle(m),
                            subtitle = memorySubtitle(m),
                            showChevron = true,
                            onClick = { memoryDetail = m },
                        )
                    }
                }
            }
        }
    }

    report?.let { r ->
        NovelAnalysisReportDialog(report = r, onDismiss = { report = null })
    }
    memoryDetail?.let { m ->
        NovelSheet(onDismissRequest = { memoryDetail = null }) {
            Column(Modifier.padding(horizontal = NovelDesign.screenHorizontalPadding)) {
                Text("记忆详情", style = NovelType.headline, color = novelPalette.textPrimary)
                Spacer(Modifier.height(10.dp))
                SelectionContainer {
                    Text(
                        text = m.content,
                        style = NovelType.callout,
                        color = novelPalette.textPrimary,
                    )
                }
                m.chapterRef?.let { ref ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "锚定章节：${ref.title.ifBlank { ref.chapterId }}",
                        style = NovelType.footnote,
                        color = novelPalette.textSecondary,
                    )
                }
                Spacer(Modifier.height(18.dp))
                NovelSecondaryButton(
                    text = "删除这条记忆",
                    onClick = { memoryToDelete = m },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
    memoryToDelete?.let { m ->
        NovelConfirmDialog(
            title = "删除这条记忆？",
            message = memoryTitle(m) + "\n\n删除后 AI 将不再引用这条记忆。",
            confirmText = "删除",
            danger = true,
            onDismiss = { memoryToDelete = null },
            onConfirm = {
                scope.launch {
                    withContext(Dispatchers.IO) { data.deleteMemory(m.workId, m.id) }
                    memoryToDelete = null
                    memoryDetail = null
                    onRefresh()
                }
            },
        )
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


// ───────────────────────── P1-6：AI 分析工具入口 ─────────────────────────

/** 四个分析工具（统计页入口）。 */
private enum class NovelAnalysisKind(val title: String, val hint: String) {
    SHUANGDIAN("爽点报告", "分析最近 5 章的爽点密度与分布"),
    WATER("水文检测", "检测最近 5 章的水字、废话与冗余"),
    TITLE("标题候选", "按流派与人物生成书名 / 章节标题候选"),
    CONSISTENCY("一致性检查", "规则级检查（伏笔 / 关系 / 记忆），不耗模型额度"),
}

private data class NovelAnalysisReportState(
    val title: String,
    val content: String,
    val loading: Boolean,
)

/**
 * 直接调用分析工具（不经聊天主循环）。
 * 工具内部为同步 LLM 调用（runBlocking），必须放 IO 线程执行。
 */
private suspend fun runNovelAnalysisTool(
    context: android.content.Context,
    kind: NovelAnalysisKind,
    workId: String,
): String = withContext(Dispatchers.IO) {
    val args = org.json.JSONObject().put("work_id", workId).toString()
    runCatching {
        when (kind) {
            NovelAnalysisKind.SHUANGDIAN -> NovelShuangdianReportTool().run(context, args)
            NovelAnalysisKind.WATER -> NovelWaterReportTool().run(context, args)
            NovelAnalysisKind.TITLE -> NovelTitleCandidatesTool().run(context, args)
            NovelAnalysisKind.CONSISTENCY -> NovelConsistencyCheckTool().run(context, args)
        }
    }.getOrElse { e -> "分析失败：${e.message ?: e.javaClass.simpleName}" }
}

/** 报告对话框：加载中转圈，完成后可滚动的可选文本。 */
@Composable
private fun NovelAnalysisReportDialog(
    report: NovelAnalysisReportState,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = novelPalette.cardSurface,
        title = { Text(report.title, style = NovelType.headline, color = novelPalette.textPrimary) },
        text = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
            ) {
                if (report.loading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = novelPalette.accent,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(report.content, style = NovelType.callout, color = novelPalette.textSecondary)
                    }
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        SelectionContainer {
                            Text(report.content, style = NovelType.callout, color = novelPalette.textPrimary)
                        }
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("关闭", style = NovelType.subhead, color = novelPalette.accent)
            }
        },
    )
}

/** 记忆行标题：无独立 title 字段，用内容首段。 */
private fun memoryTitle(m: NovelMemory): String {
    val t = m.content.replace('\n', ' ').trim()
    return if (t.length > 22) t.take(22) + "…" else t.ifBlank { "（空记忆）" }
}

/** 记忆行副标题：种类 + 最近更新。 */
private fun memorySubtitle(m: NovelMemory): String {
    val kind = when (m.kind) {
        "fact" -> "事实"
        "event" -> "事件"
        "setting" -> "设定"
        else -> m.kind
    }
    return "$kind · ${formatDate(m.updatedAt)}"
}
