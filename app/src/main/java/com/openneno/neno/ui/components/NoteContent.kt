package com.openneno.neno.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.openneno.neno.ui.HtmlColors
import com.openneno.neno.ui.HtmlText
import com.openneno.neno.ui.NoteBlock
import com.openneno.neno.ui.theme.Neno

/** 笔记正文的排版, 与网页端 .neno-content 一致: 15px, 行高 1.75 */
data class NoteTypography(
    val body: TextStyle,
    val secondary: Color,
    val marker: Color,
    val quoteBar: Color,
    val codeBackground: Color,
)

val NOTE_FONT_SIZE = 15.sp
val NOTE_LINE_HEIGHT = 1.75.em

@Composable
fun noteTypography(): NoteTypography {
    val c = Neno.colors
    return NoteTypography(
        body = TextStyle(fontSize = NOTE_FONT_SIZE, lineHeight = NOTE_LINE_HEIGHT, color = c.ink),
        secondary = c.ink2,
        marker = c.ink3,
        quoteBar = c.primary.p500.copy(alpha = 0.5f),
        codeBackground = c.surface2,
    )
}

@Composable
fun NoteContent(
    html: String,
    modifier: Modifier = Modifier,
    tags: List<String> = emptyList(),
    keyword: String = "",
    onTagClick: ((String) -> Unit)? = null,
    colors: HtmlColors = htmlColors(),
    typography: NoteTypography = noteTypography(),
) {
    val blocks = remember(html, tags, keyword, colors) { HtmlText.parse(html, colors, tags, keyword, onTagClick) }
    NoteBlocks(blocks, typography, modifier)
}

@Composable
fun NoteBlocks(blocks: List<NoteBlock>, t: NoteTypography, modifier: Modifier = Modifier) {
    val lineHeight = with(LocalDensity.current) { (t.body.fontSize.value * 1.75f).sp.toDp() }
    Column(modifier) {
        var i = 0
        while (i < blocks.size) {
            when (val b = blocks[i]) {
                is NoteBlock.Paragraph -> {
                    val style = when (b.heading) {
                        1 -> t.body.copy(fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                        2 -> t.body.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        0 -> t.body
                        else -> t.body.copy(fontWeight = FontWeight.SemiBold)
                    }
                    Text(b.text, style = style)
                    i++
                }

                NoteBlock.Blank -> {
                    Spacer(Modifier.height(lineHeight)); i++
                }

                is NoteBlock.ListItem -> {
                    // 列表上下留出 0.2em, 与网页端 ul { margin: .2em 0 } 一致
                    Column(Modifier.padding(vertical = 3.dp)) {
                        while (i < blocks.size && blocks[i] is NoteBlock.ListItem) {
                            ListItemRow(blocks[i] as NoteBlock.ListItem, t)
                            i++
                        }
                    }
                }

                is NoteBlock.Quote -> {
                    // 连续的引用画成一组, 左侧一条竖线
                    Column(
                        Modifier.padding(vertical = 2.dp).drawBehind {
                            drawRect(t.quoteBar, topLeft = Offset.Zero, size = size.copy(width = 2.5.dp.toPx()))
                        }.padding(start = 13.dp)
                    ) {
                        while (i < blocks.size && blocks[i] is NoteBlock.Quote) {
                            val q = blocks[i] as NoteBlock.Quote
                            if (q.text.isEmpty()) Spacer(Modifier.height(lineHeight))
                            else Text(q.text, style = t.body.copy(color = t.secondary))
                            i++
                        }
                    }
                }

                is NoteBlock.Code -> {
                    Box(
                        Modifier.padding(vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(t.codeBackground).horizontalScroll(rememberScrollState())
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(b.text, style = t.body.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 1.6.em), softWrap = false)
                    }
                    i++
                }
            }
        }
    }
}

/** 列表项: 符号单独一列, 换行后的文字与第一行对齐(悬挂缩进) */
@Composable
private fun ListItemRow(item: NoteBlock.ListItem, t: NoteTypography) {
    Row(Modifier.padding(start = (item.level * 21).dp)) {
        Box(Modifier.widthIn(min = 21.dp).padding(end = 4.dp)) {
            Text(
                item.marker,
                style = t.body.copy(color = t.marker, fontWeight = if (item.ordered) FontWeight.Normal else FontWeight.Bold),
            )
        }
        Text(item.text, style = t.body, modifier = Modifier.weight(1f))
    }
}

/**
 * 内容过长时折叠: 超过 [maxHeight] 时只显示这个高度, 底部渐隐. 只超出一点(tolerance)时不折叠.
 * @param onOverflow 内容是否超出, 用于显示 "展开全文"
 */
@Composable
fun Collapsible(
    collapsed: Boolean,
    fade: Color,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 260.dp,
    tolerance: Dp = 60.dp,
    onOverflow: (Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val target = if (collapsed) maxHeight else Dp.Infinity
    val limit by animateDpAsState(if (target == Dp.Infinity) 100_000.dp else target, label = "collapse")
    Box(modifier.clipToBounds()) {
        Layout(content = content) { measurables, constraints ->
            val placeable = measurables.first().measure(constraints.copy(maxHeight = Constraints.Infinity))
            val full = placeable.height
            val overflow = full > with(density) { (maxHeight + tolerance).toPx() }
            onOverflow(overflow)
            val height = if (overflow) minOf(full, with(density) { limit.toPx() }.toInt()) else full
            layout(placeable.width, height) { placeable.place(0, 0) }
        }
        if (collapsed) {
            Box(
                Modifier.matchParentSize().drawBehind {
                    val h = 64.dp.toPx()
                    if (size.height >= with(density) { maxHeight.toPx() } - 1) {
                        drawRect(
                            Brush.verticalGradient(listOf(fade.copy(alpha = 0f), fade), startY = size.height - h, endY = size.height),
                            topLeft = Offset(0f, size.height - h), size = size.copy(height = h)
                        )
                    }
                }
            )
        }
    }
}

@Suppress("unused")
private fun unusedWidth() = Modifier.width(1.dp)
