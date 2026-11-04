package com.openneno.neno.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openneno.neno.app
import com.openneno.neno.data.NoteRepository
import com.openneno.neno.ui.HtmlColors
import com.openneno.neno.ui.theme.Neno

@Composable
fun repository(): NoteRepository = LocalContext.current.app.repository

/** 本地数据版本, 数据变化时重新读取 */
@Composable
fun dataVersion(): Long {
    val repo = repository()
    val v by repo.version.collectAsState()
    return v
}

/** 根据 keys 异步读取数据, 数据版本变化时自动重新读取 */
@Composable
fun <T> load(vararg keys: Any?, block: suspend () -> T): State<T?> {
    val version = dataVersion()
    return produceState<T?>(initialValue = null, version, *keys) { value = block() }
}

@Composable
fun htmlColors(): HtmlColors {
    val c = Neno.colors
    return HtmlColors(
        link = c.accentText,
        tag = c.accentText,
        // 与网页端 .neno-tag / ::highlight(neno-tag) 相同: 浅色 primary-50 底, 深色 primary-950 底
        tagBackground = if (c.dark) c.primary.p950 else c.primary.p50,
        quote = c.ink2,
        code = c.surface2,
        // 搜索命中: 黄色底
        highlight = Color(0xFFFACC15).copy(alpha = if (c.dark) 0.30f else 0.45f),
    )
}

/** 标签前面的图标: 设置了 emoji 时显示 emoji, 否则显示默认图标 */
@Composable
fun TagIcon(tag: String, icons: Map<String, String>, fallback: ImageVector = Icons.Outlined.Tag, size: Dp = 16.dp) {
    val emoji = icons[tag]
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (!emoji.isNullOrEmpty()) Text(emoji, fontSize = (size.value * 0.85f).sp, lineHeight = (size.value).sp)
        else Icon(fallback, null, Modifier.size(size * 0.85f), tint = Neno.colors.ink3)
    }
}

fun String.tagName() = if (startsWith("#")) substring(1) else this

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Neno.colors.ink3, letterSpacing = 0.5.sp
    )
}

@Composable
fun EmptyState(icon: ImageVector, text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 64.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, Modifier.size(40.dp), tint = Neno.colors.ink3.copy(alpha = 0.6f))
        Spacer(Modifier.height(10.dp))
        Text(text, color = Neno.colors.ink3, fontSize = 14.sp, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(16.dp)); action()
        }
    }
}

/** 卡片容器: 白色卡片, 细边框 */
@Composable
fun NenoCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val c = Neno.colors
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.line, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) { content() }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String = "确定",
    danger: Boolean = false,
    preview: String? = null,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val c = Neno.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(message, color = c.ink2, fontSize = 14.sp)
                if (!preview.isNullOrBlank()) {
                    Text(
                        preview, Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.surface2).padding(10.dp),
                        fontSize = 13.sp, color = c.ink2, maxLines = 4
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirmText, color = if (danger) c.danger else c.accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = c.ink2) } },
    )
}

private val EMOJIS = listOf(
    "📚", "📖", "✍️", "💡", "🎯", "📝", "🧠", "🔖",
    "💼", "📈", "💰", "🛠️", "💻", "📅", "✅", "📌",
    "🌱", "🌿", "🌸", "🌙", "☀️", "🌊", "⛰️", "🍂",
    "❤️", "😊", "🎵", "🎬", "📷", "🎨", "🎮", "☕",
    "🍜", "✈️", "🏃", "🏠", "🐱", "🎁", "⭐", "🔥",
)

/** 只取第一个字形(一个 emoji 可能由多个码位组成) */
private fun firstGrapheme(text: String): String {
    val value = text.trim()
    if (value.isEmpty()) return ""
    val it = java.text.BreakIterator.getCharacterInstance()
    it.setText(value)
    return value.substring(0, it.next())
}

/** 标签图标选择: 常用 emoji, 也可以输入任意 emoji */
@Composable
fun EmojiPickerDialog(tag: String, current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val c = Neno.colors
    var custom by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text("${tag.tagName()} 的图标", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                LazyVerticalGrid(GridCells.Fixed(8), Modifier.height(220.dp)) {
                    items(EMOJIS) { emoji ->
                        Box(
                            Modifier.aspectRatio(1f).padding(2.dp).clip(RoundedCornerShape(8.dp))
                                .background(if (emoji == current) c.accentSoft else c.surface)
                                .clickable { onPick(emoji); onDismiss() },
                            contentAlignment = Alignment.Center
                        ) { Text(emoji, fontSize = 20.sp) }
                    }
                }
                OutlinedTextField(
                    value = custom, onValueChange = { custom = it },
                    placeholder = { Text("其他 emoji") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        firstGrapheme(custom).takeIf { it.isNotEmpty() }?.let { onPick(it); onDismiss() }
                    }),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = custom.isNotBlank(), onClick = { onPick(firstGrapheme(custom)); onDismiss() }) { Text("使用") }
        },
        dismissButton = {
            Row {
                if (current.isNotEmpty()) TextButton(onClick = { onPick(""); onDismiss() }) { Text("移除", color = c.ink2) }
                TextButton(onClick = onDismiss) { Text("取消", color = c.ink2) }
            }
        },
    )
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    label: String = "",
    prefix: String = "",
    confirmText: String = "确定",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val c = Neno.colors
    var value by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            OutlinedTextField(
                value = value, onValueChange = { value = it }, singleLine = true,
                label = if (label.isNotEmpty()) ({ Text(label) }) else null,
                prefix = if (prefix.isNotEmpty()) ({ Text(prefix) }) else null,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(value); onDismiss() }) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = c.ink2) } },
    )
}
