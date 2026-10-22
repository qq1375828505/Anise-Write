package com.ai.assistance.quro.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.quro.core.model.QuroModelGroup
import com.ai.assistance.quro.core.model.QuroModelGroupRepository
import com.ai.assistance.quro.core.model.QuroModelGroupRole
import com.ai.assistance.quro.core.model.QuroMultiProviderRepository
import com.ai.assistance.quro.core.model.QuroProviderConfig
import com.ai.assistance.quro.core.network.QuroModelListFetcher
import com.ai.assistance.quro.core.network.QuroModelListResult
import com.ai.assistance.quro.ui.novel.NovelCategoryColors
import com.ai.assistance.quro.ui.novel.NovelDesign
import com.ai.assistance.quro.ui.novel.NovelDivider
import com.ai.assistance.quro.ui.novel.NovelEmptyState
import com.ai.assistance.quro.ui.novel.NovelGroupedCard
import com.ai.assistance.quro.ui.novel.NovelGroupedRow
import com.ai.assistance.quro.ui.novel.NovelSectionFooter
import com.ai.assistance.quro.ui.novel.NovelSectionHeader
import com.ai.assistance.quro.ui.novel.NovelType
import com.ai.assistance.quro.ui.novel.ProvideNovelPalette
import com.ai.assistance.quro.ui.novel.novelPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 模型组 / 写作档 · 设置页（Minis 界面布局：分组列表 + inset 分隔线 + 彩色圆角图标）。
 *
 * 规范：[docs/design/ModelGroup_SPEC.md]；组件对照表：[docs/design/AniseWrite_UI_SPEC.md]。
 * 入口：设置 → 独立条目「模型组」。
 */

/** 六个写作子代理（与 assets/prompts/subagents/ 下的提示词一一对应）。 */
private val SUBAGENTS = listOf(
    "outline" to "大纲",
    "character" to "人物",
    "foreshadow" to "伏笔",
    "polish" to "润色",
    "review" to "审读",
    "consistency" to "一致性",
)

/** 角色徽标文案。 */
private fun roleLabel(role: QuroModelGroupRole): String? = when (role) {
    QuroModelGroupRole.DEFAULT -> "默认组"
    QuroModelGroupRole.LIGHT -> "轻量组"
    QuroModelGroupRole.CUSTOM -> null
}

/**
 * 模型组列表页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuroModelGroupScreen(
    onClose: () -> Unit,
    onOpenDetail: (String) -> Unit,
    onOpenAgentBinding: () -> Unit,
) {
    val ctx = LocalContext.current
    val repo = remember { QuroModelGroupRepository(ctx) }
    var settings by remember { mutableStateOf(repo.load()) }
    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    ProvideNovelPalette {
        Scaffold(
            containerColor = novelPalette.pageBackground,
            topBar = {
                TopAppBar(
                    title = { Text("模型组", style = NovelType.title3, color = novelPalette.textPrimary) },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.Filled.ArrowBack, "返回", tint = novelPalette.accent)
                        }
                    },
                    actions = {
                        IconButton(onClick = { showCreate = true }) {
                            Icon(Icons.Filled.Add, "新建组", tint = novelPalette.accent)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = novelPalette.pageBackground),
                )
            },
        ) { padding ->
            if (settings.groups.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(padding)) {
                    NovelEmptyState(
                        title = "还没有模型组",
                        hint = "模型组把「用哪个模型」变成按任务可配置的策略：细纲用强模型，润色用轻量模型。",
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
                ) {
                    item {
                        Column(Modifier.padding(horizontal = NovelDesign.screenHorizontalPadding)) {
                            Spacer(Modifier.height(NovelDesign.firstSectionTopGap))
                            NovelSectionHeader("组")
                        }
                    }
                    item {
                        NovelGroupedCard(
                            modifier = Modifier.padding(horizontal = NovelDesign.screenHorizontalPadding),
                        ) {
                            settings.groups.forEachIndexed { index, g ->
                                if (index > 0) NovelDivider()
                                NovelGroupedRow(
                                    title = g.name,
                                    subtitle = groupSubtitle(g),
                                    iconColor = roleColor(g.role),
                                    icon = {
                                        Icon(
                                            Icons.Filled.Layers,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    },
                                    trailing = roleLabel(g.role),
                                    showChevron = true,
                                    onClick = { onOpenDetail(g.id) },
                                )
                            }
                        }
                    }
                    item {
                        Column(Modifier.padding(horizontal = NovelDesign.screenHorizontalPadding)) {
                            Spacer(Modifier.height(NovelDesign.sectionTopGap))
                            NovelSectionHeader("子代理")
                        }
                    }
                    item {
                        NovelGroupedCard(
                            modifier = Modifier.padding(horizontal = NovelDesign.screenHorizontalPadding),
                        ) {
                            NovelGroupedRow(
                                title = "子代理模型",
                                subtitle = "为大纲 / 人物 / 伏笔 / 润色 / 审读 / 一致性分别指定用哪一档",
                                iconColor = NovelCategoryColors.plotline,
                                icon = {
                                    Icon(
                                        Icons.Filled.Edit,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                                onClick = { onOpenAgentBinding() },
                            )
                        }
                        NovelSectionFooter(
                            "组内成员按顺序取用；某个成员不可用时自动切下一个。" +
                                "子代理若钉死了某组，委派请求里的模型意图会被忽略。"
                        )
                    }
                }
            }
        }

        if (showCreate) {
            QuroNameDialog(
                title = "新建模型组",
                initial = "",
                onDismiss = { showCreate = false },
                onConfirm = { name ->
                    if (name.isNotBlank()) {
                        repo.addGroup(QuroModelGroup(name = name.trim()))
                        settings = repo.load()
                    }
                    showCreate = false
                },
            )
        }
    }
}

/** 组副标题：成员数 + 上下文上限。 */
private fun groupSubtitle(g: QuroModelGroup): String {
    val members = if (g.members.isEmpty()) "尚未添加模型" else "${g.members.size} 个模型"
    val ctxLimit = if (g.contextLimitTokens > 0) " · 上下文上限 ${g.contextLimitTokens / 1024}K" else ""
    return members + ctxLimit
}

/** 角色色：默认组 = accent，轻量组 = 轻量蓝，普通组 = 灰。 */
private fun roleColor(role: QuroModelGroupRole): Color = when (role) {
    QuroModelGroupRole.DEFAULT -> Color(0xFF6E8BFF)
    QuroModelGroupRole.LIGHT -> Color(0xFF4ADE80)
    QuroModelGroupRole.CUSTOM -> Color(0xFF8A8A8E)
}

/**
 * 组详情页：改名 / 角色 / 成员增删 / 上下文上限 / 思考档。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuroModelGroupDetailScreen(
    groupId: String,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val repo = remember { QuroModelGroupRepository(ctx) }
    var group by remember { mutableStateOf(repo.load().groupById(groupId)) }
    var name by remember { mutableStateOf(group?.name ?: "") }
    var showPicker by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var contextLimit by remember { mutableStateOf((group?.contextLimitTokens ?: 0).toString()) }
    var thinking by remember { mutableStateOf(group?.defaultThinkingLevel ?: "") }

    if (group == null) {
        Box(Modifier.fillMaxSize()) {
            NovelEmptyState(
                title = "模型组不存在",
                hint = "它可能已被删除，返回列表重新选择。",
                modifier = Modifier.fillMaxSize(),
            )
        }
        return
    }

    ProvideNovelPalette {
        Scaffold(
            containerColor = novelPalette.pageBackground,
            topBar = {
                TopAppBar(
                    title = { Text("模型组", style = NovelType.title3, color = novelPalette.textPrimary) },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.Filled.ArrowBack, "返回", tint = novelPalette.accent)
                        }
                    },
                    actions = {
                        IconButton(onClick = { showDelete = true }) {
                            Icon(Icons.Filled.Delete, "删除组", tint = novelPalette.danger)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = novelPalette.pageBackground),
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp),
            ) {
                item {
                    Column(Modifier.padding(horizontal = NovelDesign.screenHorizontalPadding)) {
                        Spacer(Modifier.height(NovelDesign.firstSectionTopGap))
                        NovelSectionHeader("名称")
                    }
                }
                item {
                    Column(Modifier.padding(horizontal = NovelDesign.screenHorizontalPadding)) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                item {
                    Column(Modifier.padding(horizontal = NovelDesign.screenHorizontalPadding)) {
                        Spacer(Modifier.height(NovelDesign.sectionTopGap))
                        NovelSectionHeader("角色")
                    }
                }
                item {
                    NovelGroupedCard(
                        modifier = Modifier.padding(horizontal = NovelDesign.screenHorizontalPadding),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(NovelDesign.screenHorizontalPadding),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RoleChip("普通", group!!.role == QuroModelGroupRole.CUSTOM) {
                                repo.setRole(groupId, QuroModelGroupRole.CUSTOM)
                                group = repo.load().groupById(groupId)
                            }
                            RoleChip("默认组", group!!.role == QuroModelGroupRole.DEFAULT) {
                                repo.setRole(groupId, QuroModelGroupRole.DEFAULT)
                                group = repo.load().groupById(groupId)
                            }
                            RoleChip("轻量组", group!!.role == QuroModelGroupRole.LIGHT) {
                                repo.setRole(groupId, QuroModelGroupRole.LIGHT)
                                group = repo.load().groupById(groupId)
                            }
                        }
                    }
                }
                item {
                    Column(Modifier.padding(horizontal = NovelDesign.screenHorizontalPadding)) {
                        Spacer(Modifier.height(NovelDesign.sectionTopGap))
                        NovelSectionHeader("成员 · ${group!!.members.size}")
                    }
                }
                item {
                    NovelGroupedCard(
                        modifier = Modifier.padding(horizontal = NovelDesign.screenHorizontalPadding),
                    ) {
                        if (group!!.members.isEmpty()) {
                            Text(
                                text = "尚未添加模型",
                                style = NovelType.callout,
                                color = novelPalette.textTertiary,
                                modifier = Modifier.padding(NovelDesign.screenHorizontalPadding),
                            )
                        } else {
                            group!!.members.forEachIndexed { index, m ->
                                if (index > 0) NovelDivider()
                                NovelGroupedRow(
                                    title = m.displayName.ifBlank { m.model },
                                    subtitle = m.model,
                                    showChevron = false,
                                    trailing = "移除",
                                    onClick = {
                                        val g = repo.load().groupById(groupId) ?: return@NovelGroupedRow
                                        repo.updateGroup(g.copy(members = g.members.filterNot { it.model == m.model }))
                                        group = repo.load().groupById(groupId)
                                    },
                                )
                            }
                        }
                    }
                }
                item {
                    TextButton(
                        onClick = { showPicker = true },
                        modifier = Modifier.padding(horizontal = NovelDesign.screenHorizontalPadding),
                    ) {
                        Icon(Icons.Filled.Add, null, Modifier.size(18.dp), tint = novelPalette.accent)
                        Spacer(Modifier.width(4.dp))
                        Text("添加模型", color = novelPalette.accent, style = NovelType.subhead)
                    }
                }
                item {
                    Column(Modifier.padding(horizontal = NovelDesign.screenHorizontalPadding)) {
                        Spacer(Modifier.height(NovelDesign.sectionTopGap))
                        NovelSectionHeader("组级参数")
                    }
                }
                item {
                    NovelGroupedCard(
                        modifier = Modifier.padding(horizontal = NovelDesign.screenHorizontalPadding),
                    ) {
                        Column(Modifier.padding(NovelDesign.screenHorizontalPadding)) {
                            OutlinedTextField(
                                value = contextLimit,
                                onValueChange = { contextLimit = it.filter { ch -> ch.isDigit() }.take(8) },
                                singleLine = true,
                                label = { Text("上下文上限（token，0 = 不限）", style = NovelType.footnote) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = "默认思考档",
                                style = NovelType.footnote,
                                color = novelPalette.textSecondary,
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (t in listOf("", "off", "low", "medium", "high", "xhigh")) {
                                    RoleChip(t.ifBlank { "不覆盖" } else t, thinking == t) { thinking = t }
                                }
                            }
                        }
                    }
                }
                item {
                    TextButton(
                        onClick = {
                            val g = repo.load().groupById(groupId) ?: return@TextButton
                            repo.updateGroup(
                                g.copy(
                                    name = name.trim().ifBlank { g.name },
                                    contextLimitTokens = contextLimit.toIntOrNull() ?: 0,
                                    defaultThinkingLevel = thinking,
                                )
                            )
                            onClose()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = NovelDesign.screenHorizontalPadding, vertical = 16.dp),
                    ) {
                        Text("保存", color = novelPalette.accent, style = NovelType.headline)
                    }
                }
            }
        }

        if (showPicker) {
            QuroModelPickerSheet(
                onDismiss = { showPicker = false },
                onPick = { providerId, modelId ->
                    val g = repo.load().groupById(groupId)
                    if (g != null && g.members.none { it.model == modelId }) {
                        val provider = QuroMultiProviderRepository(ctx).getConfigById(providerId)
                        repo.updateGroup(
                            g.copy(
                                members = g.members + com.ai.assistance.quro.core.model.QuroModelGroupMember(
                                    providerId = providerId,
                                    model = modelId,
                                    displayName = provider?.name?.let { p -> "$p · $modelId" } ?: modelId,
                                )
                            )
                        )
                    }
                    group = repo.load().groupById(groupId)
                    showPicker = false
                },
            )
        }

        if (showDelete) {
            QuroConfirmDialog(
                title = "删除模型组",
                message = "「${group?.name}」将被删除，指向它的子代理绑定会一并解除。",
                confirmText = "删除",
                onDismiss = { showDelete = false },
                onConfirm = {
                    repo.removeGroup(groupId)
                    showDelete = false
                    onClose()
                },
            )
        }
    }
}

/** 角色芯片（选中态 accent 实心，未选中描边）。 */
@Composable
private fun RoleChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) novelPalette.accent else Color.Transparent,
        modifier = Modifier.clickable { onClick() },
    ) {
        Text(
            text = label,
            style = NovelType.footnote,
            color = if (selected) novelPalette.onAccent else novelPalette.textSecondary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/**
 * 子代理模型绑定页：为每个写作子代理指定用哪一档模型。
 *
 * 规则（ModelGroup_SPEC §4.2）：**用户钉住 > 委派意图 > 父会话模型**——
 * 这里钉死之后，委派请求里带的 `model_group` 意图会被忽略。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuroAgentModelScreen(
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val repo = remember { QuroModelGroupRepository(ctx) }
    var settings by remember { mutableStateOf(repo.load()) }
    var editing by remember { mutableStateOf<String?>(null) }

    ProvideNovelPalette {
        Scaffold(
            containerColor = novelPalette.pageBackground,
            topBar = {
                TopAppBar(
                    title = { Text("子代理模型", style = NovelType.title3, color = novelPalette.textPrimary) },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.Filled.ArrowBack, "返回", tint = novelPalette.accent)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = novelPalette.pageBackground),
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
            ) {
                item {
                    Column(Modifier.padding(horizontal = NovelDesign.screenHorizontalPadding)) {
                        Spacer(Modifier.height(NovelDesign.firstSectionTopGap))
                        NovelSectionHeader("写作子代理")
                    }
                }
                item {
                    NovelGroupedCard(
                        modifier = Modifier.padding(horizontal = NovelDesign.screenHorizontalPadding),
                    ) {
                        SUBAGENTS.forEachIndexed { index, (key, label) ->
                            if (index > 0) NovelDivider()
                            val bound = settings.groupForAgent(key)
                            NovelGroupedRow(
                                title = label,
                                subtitle = key,
                                trailing = bound?.name ?: "跟随委派请求",
                                showChevron = true,
                                onClick = { editing = key },
                            )
                        }
                    }
                }
                item {
                    NovelSectionFooter(
                        "「跟随委派请求」= 由调用方在委派时指定意图（同款 / 默认组 / 轻量组）；" +
                            "指定具体组后，调用方的意图对该子代理不再生效。"
                    )
                }
            }
        }

        val editingKey = editing
        if (editingKey != null) {
            QuroGroupChoiceSheet(
                title = SUBAGENTS.firstOrNull { it.first == editingKey }?.second ?: editingKey,
                groups = settings.groups,
                currentId = settings.agentBindings[editingKey],
                onDismiss = { editing = null },
                onPick = { groupId ->
                    repo.bindAgent(editingKey, groupId)
                    settings = repo.load()
                    editing = null
                },
            )
        }
    }
}

/**
 * 选择组（子代理绑定用）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuroGroupChoiceSheet(
    title: String,
    groups: List<QuroModelGroup>,
    currentId: String?,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = novelPalette.cardSurface) {
        Column(Modifier.padding(bottom = 24.dp)) {
            NovelSectionHeader(title)
            NovelGroupedRow(
                title = "跟随委派请求",
                subtitle = "由调用方指定意图",
                showChevron = false,
                trailing = if (currentId.isNullOrBlank()) "当前" else null,
                onClick = { onPick("") },
            )
            NovelDivider()
            groups.forEach { g ->
                NovelGroupedRow(
                    title = g.name,
                    subtitle = "${g.members.size} 个模型",
                    showChevron = false,
                    trailing = if (currentId == g.id) "当前" else null,
                    onClick = { onPick(g.id) },
                )
                NovelDivider()
            }
        }
    }
}

/**
 * 模型选择器：先选 provider（取自基座多 provider 配置），再选该 provider 的模型。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuroModelPickerSheet(
    onDismiss: () -> Unit,
    onPick: (providerId: String, modelId: String) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val providers = remember { QuroMultiProviderRepository(ctx).getAllConfigs() }
    var selectedProvider by remember { mutableStateOf<QuroProviderConfig?>(null) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = novelPalette.cardSurface) {
        Column(Modifier.padding(bottom = 24.dp)) {
            val provider = selectedProvider
            if (provider == null) {
                NovelSectionHeader("选择提供商")
                if (providers.isEmpty()) {
                    NovelEmptyState(
                        title = "还没有配置提供商",
                        hint = "先到「设置 → 多提供商」添加至少一个提供商，再回到这里选模型。",
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    providers.forEach { p ->
                        NovelGroupedRow(
                            title = p.name.ifBlank { p.providerType.name },
                            subtitle = p.defaultModel.ifBlank { p.baseUrl },
                            showChevron = true,
                            onClick = {
                                selectedProvider = p
                                models = emptyList()
                                errorMsg = ""
                                if (p.apiKey.isBlank()) {
                                    errorMsg = "该提供商没有 API Key，无法拉取模型列表"
                                    return@NovelGroupedRow
                                }
                                loading = true
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        QuroModelListFetcher().fetch(p.baseUrl, p.apiKey)
                                    }
                                    when (result) {
                                        is QuroModelListResult.Success -> {
                                            models = result.models.map { it.id }
                                            errorMsg = ""
                                        }
                                        is QuroModelListResult.Error -> {
                                            models = emptyList()
                                            errorMsg = result.message
                                        }
                                    }
                                    loading = false
                                }
                            },
                        )
                        NovelDivider()
                    }
                }
            } else {
                NovelSectionHeader(provider.name.ifBlank { provider.providerType.name })
                if (loading) {
                    Text(
                        text = "正在拉取模型列表…",
                        style = NovelType.callout,
                        color = novelPalette.textTertiary,
                        modifier = Modifier.padding(NovelDesign.screenHorizontalPadding),
                    )
                } else if (errorMsg.isNotBlank()) {
                    NovelEmptyState(
                        title = "拉取失败",
                        hint = errorMsg,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else if (models.isEmpty()) {
                    NovelEmptyState(
                        title = "该提供商没有返回模型",
                        hint = "确认 baseUrl 与 API Key 后重试。",
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    models.forEach { m ->
                        NovelGroupedRow(
                            title = m,
                            showChevron = false,
                            onClick = { onPick(provider.id, m) },
                        )
                        NovelDivider()
                    }
                }
            }
        }
    }
}

/** 单行输入对话框（新建/改名）。 */
@Composable
private fun QuroNameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = NovelType.headline, color = novelPalette.textPrimary) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
                Text("确定", color = novelPalette.accent, style = NovelType.subhead)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = novelPalette.textSecondary, style = NovelType.subhead)
            }
        },
        containerColor = novelPalette.cardSurface,
    )
}

/** 确认对话框。 */
@Composable
private fun QuroConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = NovelType.headline, color = novelPalette.textPrimary) },
        text = { Text(message, style = NovelType.callout, color = novelPalette.textSecondary) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText, color = novelPalette.danger, style = NovelType.subhead)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = novelPalette.textSecondary, style = NovelType.subhead)
            }
        },
        containerColor = novelPalette.cardSurface,
    )
}
