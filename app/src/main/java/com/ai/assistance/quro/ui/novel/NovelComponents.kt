package com.ai.assistance.quro.ui.novel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
