package com.ai.assistance.quro.ui.novel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 小说工作台基础组件（iOS 分组列表语汇）。
 *
 * 规范：[docs/design/AniseWrite_UI_SPEC.md] §5。
 * **所有尺寸只从 [NovelDesign] 取，禁止在本文件（或任何界面）里写裸 dp。**
 */

/** 分组标题：accent 色 + footnote 字级（中文不强制大写）。 */
@Composable
fun NovelSectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = NovelType.footnote,
        color = novelPalette.accent,
        modifier = modifier.padding(horizontal = 4.dp, vertical = 8.dp),
    )
}

/** 分组脚注：次级文字。 */
@Composable
fun NovelSectionFooter(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = NovelType.footnote,
        color = novelPalette.textTertiary,
        modifier = modifier.padding(horizontal = 4.dp, vertical = 8.dp),
    )
}

/**
 * 分组卡片：圆角 12dp、中性 cardSurface、**无阴影**（iOS 分组卡片靠底色差分层）。
 * 内容由调用方自行插入 [NovelDivider] 分隔行。
 */
@Composable
fun NovelGroupedCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(NovelDesign.cardCornerRadius),
        color = novelPalette.cardSurface,
        tonalElevation = 0.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(content = content)
    }
}

/** 行首彩色圆角图标：29dp 见方、6.5dp 圆角、纯色填充。 */
@Composable
fun NovelRowIcon(
    color: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .size(NovelDesign.rowIconSize)
            .clip(RoundedCornerShape(NovelDesign.rowIconRadius))
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** 行分隔线：0.5dp、自卡片左缘内缩（避开圆角）。 */
@Composable
fun NovelDivider(
    modifier: Modifier = Modifier,
    inset: Dp = NovelDesign.dividerInset,
) {
    HorizontalDivider(
        modifier = modifier.padding(start = inset),
        thickness = 0.5.dp,
        color = novelPalette.separator,
    )
}

/**
 * 分组行：行首图标 + 标题 + 副标题 + 右侧值 + chevron。
 *
 * @param title 主文（headline 字级）
 * @param subtitle 次级说明（footnote）
 * @param trailing 右侧值（footnote，secondary 色）
 * @param showChevron 右侧是否显示 chevron（iOS「可进入下一级」信号）
 */
@Composable
fun NovelGroupedRow(
    title: String,
    modifier: Modifier = Modifier,
    iconColor: Color? = null,
    icon: (@Composable () -> Unit)? = null,
    subtitle: String? = null,
    trailing: String? = null,
    showChevron: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = NovelDesign.screenHorizontalPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null && iconColor != null) {
            NovelRowIcon(color = iconColor) { icon() }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = NovelType.headline,
                color = novelPalette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = NovelType.footnote,
                    color = novelPalette.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!trailing.isNullOrBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = trailing,
                style = NovelType.footnote,
                color = novelPalette.textSecondary,
                maxLines = 1,
            )
        }
        if (showChevron) {
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = novelPalette.textTertiary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 数据卡：标签 + 数值（等宽风格由调用方决定字号）。 */
@Composable
fun NovelStatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(NovelDesign.cardCornerRadius),
        color = novelPalette.cardSurface,
        tonalElevation = 0.dp,
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = value,
                style = NovelType.title2,
                color = novelPalette.textPrimary,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                style = NovelType.footnote,
                color = novelPalette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 空态：大标题 + 一句引导。 */
@Composable
fun NovelEmptyState(
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = NovelDesign.screenHorizontalPadding, vertical = NovelDesign.page),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = title, style = NovelType.title3, color = novelPalette.textPrimary)
        Spacer(Modifier.height(8.dp))
        Text(
            text = hint,
            style = NovelType.callout,
            color = novelPalette.textSecondary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 小圆点（状态标记：伏笔、章节状态等）。 */
@Composable
fun NovelStatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(color),
    )
}

/**
 * ── 以下为 Minis 组件对照表中"待补"的组件（Phase 6c 补齐） ──
 * 数值同样取自 Minis 实测值：底部 Sheet 顶部圆角 10dp、拖拽把手 36×5dp、
 * 按钮圆角 14dp / 高 48dp、侧滑阈值 96dp、对话框圆角 20dp。
 */

/** 底部抽屉（Minis Sheet）：顶部圆角 10dp + 拖拽把手 + 选中项 accent + 对勾。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun NovelSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(topStart = NovelDesign.sheetCornerRadius, topEnd = NovelDesign.sheetCornerRadius),
        containerColor = novelPalette.cardSurface,
        modifier = modifier,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(width = 36.dp, height = 5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(novelPalette.separator),
            )
        }
        content()
        Spacer(Modifier.height(NovelDesign.page))
    }
}

/** 抽屉里的一行（选中项 accent + 对勾）。 */
@Composable
fun NovelSheetRow(
    title: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    showCheck: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = NovelDesign.screenHorizontalPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = NovelType.body,
            color = if (selected) novelPalette.accent else novelPalette.textPrimary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (showCheck && selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = novelPalette.accent,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 主按钮：accent 实心、圆角 14dp、高 48dp。 */
@Composable
fun NovelPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    androidx.compose.material3.Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = novelPalette.accent,
            contentColor = novelPalette.onAccent,
        ),
        modifier = modifier.height(48.dp),
    ) {
        Text(text = text, style = NovelType.body)
    }
}

/** 次级按钮：描边、圆角 14dp、高 48dp。 */
@Composable
fun NovelSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.height(48.dp),
    ) {
        Text(text = text, style = NovelType.body, color = novelPalette.accent)
    }
}

/**
 * 侧滑行：向左滑露出动作区（阈值 96dp）。
 *
 * @param actions 动作列表，按从右到左的顺序排列（第一个离内容最近）。
 */
@Composable
fun NovelSwipeRow(
    modifier: Modifier = Modifier,
    actions: List<NovelSwipeAction>,
    content: @Composable () -> Unit,
) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val thresholdPx = with(density) { NovelDesign.swipeThreshold.toPx() }
    val actionWidthPx = with(density) { 84.dp.toPx() }
    val maxOffset = -(actionWidthPx * actions.size)

    Box(modifier) {
        // 动作区（右对齐，铺在内容下面）
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actions.forEach { action ->
                Box(
                    modifier = Modifier
                        .width(84.dp)
                        .fillMaxHeight()
                        .background(action.color)
                        .clickable {
                            offsetX = 0f
                            action.onClick()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = action.label,
                        style = NovelType.footnote,
                        color = Color.White,
                    )
                }
            }
        }
        // 内容层（可左滑）
        Box(
            Modifier
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .pointerInput(actions.size) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            offsetX = if (offsetX < -thresholdPx) maxOffset else 0f
                        },
                        onDragCancel = { offsetX = 0f },
                        onHorizontalDrag = { _, delta ->
                            offsetX = (offsetX + delta).coerceIn(maxOffset, 0f)
                        },
                    )
                }
                .background(novelPalette.cardSurface),
        ) {
            content()
        }
    }
}

/** 侧滑动作。 */
data class NovelSwipeAction(
    val label: String,
    val color: Color,
    val onClick: () -> Unit,
)

/** 输入对话框（重命名等）：圆角 20dp、cardSurface、单行输入。 */
@Composable
fun NovelPromptDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = novelPalette.cardSurface,
        title = { Text(title, style = NovelType.headline, color = novelPalette.textPrimary) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onConfirm(text.trim()) }) {
                Text("确定", style = NovelType.subhead, color = novelPalette.accent)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("取消", style = NovelType.subhead, color = novelPalette.textSecondary)
            }
        },
    )
}

/** 确认对话框：圆角 20dp、cardSurface、危险操作用 danger 色。 */
@Composable
fun NovelConfirmDialog(
    title: String,
    message: String,
    confirmText: String = "确定",
    dismissText: String = "取消",
    danger: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = novelPalette.cardSurface,
        title = { Text(title, style = NovelType.headline, color = novelPalette.textPrimary) },
        text = { Text(message, style = NovelType.callout, color = novelPalette.textSecondary) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onConfirm) {
                Text(
                    confirmText,
                    style = NovelType.subhead,
                    color = if (danger) novelPalette.danger else novelPalette.accent,
                )
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(dismissText, style = NovelType.subhead, color = novelPalette.textSecondary)
            }
        },
    )
}
