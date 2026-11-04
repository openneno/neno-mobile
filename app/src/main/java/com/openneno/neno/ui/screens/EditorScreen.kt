package com.openneno.neno.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.FormatStrikethrough
import androidx.compose.material.icons.outlined.FormatUnderlined
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import com.openneno.neno.ui.components.htmlColors
import com.openneno.neno.ui.components.noteTypography
import com.openneno.neno.data.Note
import com.openneno.neno.data.NoteImage
import com.openneno.neno.data.NoteRepository
import com.openneno.neno.ui.Screen
import com.openneno.neno.ui.components.ConfirmDialog
import com.openneno.neno.ui.components.repository
import com.openneno.neno.ui.theme.Neno
import com.openneno.neno.ui.theme.NoteTextStyle
import kotlinx.coroutines.launch

/** 编辑器中的图片: 已保存的, 或者刚选择还没有导入的 */
private sealed interface EditorImage {
    data class Saved(val image: NoteImage) : EditorImage
    data class Pending(val uri: Uri) : EditorImage
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun EditorScreen(screen: Screen.Editor, onClose: () -> Unit) {
    val c = Neno.colors
    val repo = repository()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rich = rememberRichTextState()
    val images = remember { mutableStateListOf<EditorImage>() }
    var loaded by remember { mutableStateOf(screen.id.isEmpty()) }
    var parent by remember { mutableStateOf<Note?>(null) }
    var allTags by remember { mutableStateOf(emptyList<String>()) }
    var sending by remember { mutableStateOf(false) }
    var linkDialog by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        rich.config.linkColor = c.accentText
        rich.config.linkTextDecoration = TextDecoration.Underline
        rich.config.listIndent = 21
        allTags = repo.allTags()
        if (screen.parentId.isNotEmpty()) parent = repo.note(screen.parentId)
        if (screen.id.isNotEmpty()) {
            repo.note(screen.id)?.let { note ->
                rich.setHtml(note.content.replace("&nbsp;", " "))
                images += note.images.map { EditorImage.Saved(it) }
            }
            loaded = true
        } else if (screen.initialText.isNotEmpty()) {
            rich.setText(screen.initialText)
        }
        focus.requestFocus()
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) { uris ->
        images += uris.map { EditorImage.Pending(it) }
    }

    val text = rich.annotatedString.text
    val isEmpty = text.isBlank() && images.isEmpty()
    val dirty = !isEmpty && screen.id.isEmpty()

    fun close() {
        if (dirty && !sending) confirmDiscard = true else onClose()
    }
    BackHandler { close() }

    fun send() {
        if (isEmpty || sending) return
        sending = true
        scope.launch {
            try {
                val html = rich.toHtml().replace("&nbsp;", " ")
                val pure = Note.htmlToText(html)
                val saved = images.map { image ->
                    when (image) {
                        is EditorImage.Saved -> image.image
                        is EditorImage.Pending -> repo.importImage(image.uri)
                    }
                }
                repo.save(
                    id = screen.id, parentId = screen.parentId, content = html, pureContent = pure,
                    tags = NoteRepository.extractTags(pure), images = saved,
                )
                onClose()
            } catch (e: Exception) {
                sending = false
                Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // #标签 自动补全: 光标前最近的 # 到光标之间没有空白
    val suggestions = remember(text, rich.selection, allTags) {
        val cursor = rich.selection.min
        if (!rich.selection.collapsed || cursor > text.length) return@remember null
        val start = text.lastIndexOf('#', (cursor - 1).coerceAtLeast(0))
        if (start < 0 || cursor <= start) return@remember null
        val typed = text.substring(start, cursor)
        if (typed.any { it.isWhitespace() }) return@remember null
        val list = allTags.filter { it.startsWith(typed) && it != typed }.sortedBy { it.length }.take(20)
        if (list.isEmpty()) null else Triple(start, cursor, list)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { close() }) { Icon(Icons.Outlined.Close, "取消", tint = c.ink) }
            Text(
                when {
                    screen.parentId.isNotEmpty() -> "批注"
                    screen.id.isNotEmpty() -> "编辑"
                    else -> "记录"
                },
                fontWeight = FontWeight.SemiBold, fontSize = 18.sp, modifier = Modifier.weight(1f)
            )
            Button(
                onClick = { send() }, enabled = !isEmpty && !sending && loaded,
                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = if (c.dark) Color(0xFF0F1013) else Color.White),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.padding(end = 8.dp).height(38.dp),
            ) {
                if (sending) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                else {
                    Icon(Icons.AutoMirrored.Outlined.Send, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (screen.id.isNotEmpty()) "保存" else "发送")
                }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            parent?.let { p ->
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.surface2).padding(10.dp)) {
                    Box(Modifier.width(2.dp).height(36.dp).background(c.accent))
                    Spacer(Modifier.width(8.dp))
                    Text(p.summary(120), fontSize = 13.sp, color = c.ink2, maxLines = 2)
                }
            }
            // 与笔记展示使用相同的字号和行高, 所见即所得
            val body = noteTypography().body
            val tagColors = htmlColors()
            var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
            Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                if (text.isEmpty()) {
                    Text(if (screen.parentId.isNotEmpty()) "写下你的批注..." else "现在的想法是...", style = body.copy(color = c.ink3))
                }
                BasicRichTextEditor(
                    state = rich,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp).focusRequester(focus)
                        .drawWithContent {
                            // #标签 着色: 只改变绘制, 不写入笔记内容(与网页端 ::highlight 相同)
                            val l = layout
                            val ranges = l?.let { tagRanges(it.layoutInput.text.text) }.orEmpty()
                            val paths = ranges.mapNotNull { r -> runCatching { l!!.getPathForRange(r.first, r.last + 1) }.getOrNull() }
                            paths.forEach { drawPath(it, tagColors.tagBackground) }
                            drawContent()
                            if (l != null) paths.forEach { p -> clipPath(p) { drawText(l, color = tagColors.tag) } }
                        },
                    textStyle = body,
                    cursorBrush = SolidColor(c.accent),
                    onTextLayout = { layout = it },
                    contentPadding = PaddingValues(0.dp),
                )
            }
            if (images.isNotEmpty()) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    images.forEachIndexed { index, image ->
                        Box {
                            AsyncImage(
                                model = when (image) {
                                    is EditorImage.Saved -> repo.picFile(image.image)
                                    is EditorImage.Pending -> image.uri
                                },
                                contentDescription = null, contentScale = ContentScale.Crop,
                                modifier = Modifier.size(84.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, c.line, RoundedCornerShape(10.dp)),
                            )
                            Box(
                                Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.55f)).clickable { images.removeAt(index) },
                                contentAlignment = Alignment.Center
                            ) { Icon(Icons.Outlined.Close, "移除图片", tint = Color.White, modifier = Modifier.size(14.dp)) }
                        }
                    }
                }
            }
        }

        suggestions?.let { (start, end, list) ->
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                list.forEach { tag ->
                    Text(
                        tag,
                        Modifier.clip(RoundedCornerShape(8.dp)).background(c.accentSoft)
                            .clickable {
                                rich.replaceTextRange(TextRange(start, end), "$tag ")
                                rich.selection = TextRange(start + tag.length + 1)
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        color = c.accentText, fontSize = 14.sp
                    )
                }
            }
        }
        HorizontalDivider(color = c.line)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val span = rich.currentSpanStyle
            ToolButton(Icons.Outlined.Tag, "插入标签") {
                val sel = rich.selection
                rich.addTextAtIndex(sel.max, "#")
                rich.selection = TextRange(sel.max + 1)
            }
            ToolButton(Icons.Outlined.Image, "添加图片") {
                pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            ToolButton(Icons.Outlined.FormatBold, "加粗", span.fontWeight == FontWeight.Bold) {
                rich.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))
            }
            ToolButton(Icons.Outlined.FormatItalic, "斜体", span.fontStyle == FontStyle.Italic) {
                rich.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic))
            }
            ToolButton(Icons.Outlined.FormatUnderlined, "下划线", span.textDecoration?.contains(TextDecoration.Underline) == true) {
                rich.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.Underline))
            }
            ToolButton(Icons.Outlined.FormatStrikethrough, "删除线", span.textDecoration?.contains(TextDecoration.LineThrough) == true) {
                rich.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
            }
            ToolButton(Icons.Outlined.FormatListBulleted, "无序列表", rich.isUnorderedList) { rich.toggleUnorderedList() }
            ToolButton(Icons.Outlined.FormatListNumbered, "有序列表", rich.isOrderedList) { rich.toggleOrderedList() }
            ToolButton(Icons.Outlined.Link, "链接", rich.isLink) { linkDialog = true }
        }
    }

    if (linkDialog) {
        LinkDialog(
            initialText = rich.selectedLinkText ?: if (!rich.selection.collapsed) text.substring(rich.selection.min, rich.selection.max) else "",
            initialUrl = rich.selectedLinkUrl ?: "",
            isLink = rich.isLink,
            hasSelection = !rich.selection.collapsed,
            onDismiss = { linkDialog = false },
            onRemove = { rich.removeLink() },
            onSave = { label, url ->
                when {
                    rich.isLink -> rich.updateLink(url)
                    !rich.selection.collapsed -> rich.addLinkToSelection(url)
                    else -> rich.addLink(label.ifBlank { url }, url)
                }
            },
        )
    }
    if (confirmDiscard) {
        ConfirmDialog(
            title = "放弃这条笔记?", message = "还没有发送的内容将会丢失。", confirmText = "放弃", danger = true,
            onDismiss = { confirmDiscard = false }, onConfirm = onClose,
        )
    }
}

private val TAG_RE = Regex("#\\S+")

/** 文字中 #标签 的位置, 与保存时提取标签的规则一致 */
private fun tagRanges(text: String): List<IntRange> = if ('#' !in text) emptyList() else TAG_RE.findAll(text).map { it.range }.toList()

@Composable
private fun ToolButton(icon: ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
    val c = Neno.colors
    Box(
        Modifier.size(42.dp).padding(3.dp).clip(RoundedCornerShape(10.dp))
            .background(if (active) c.accentSoft else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, label, tint = if (active) c.accentText else c.ink2, modifier = Modifier.size(21.dp)) }
}

private fun normalizeHref(value: String): String {
    val text = value.trim()
    if (text.isEmpty()) return ""
    return if (Regex("^(https?:|mailto:)", RegexOption.IGNORE_CASE).containsMatchIn(text)) text else "https://$text"
}

@Composable
private fun LinkDialog(
    initialText: String,
    initialUrl: String,
    isLink: Boolean,
    hasSelection: Boolean,
    onDismiss: () -> Unit,
    onRemove: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    val c = Neno.colors
    var label by remember { mutableStateOf(initialText) }
    var url by remember { mutableStateOf(initialUrl) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text(if (isLink) "编辑链接" else "插入链接", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isLink && !hasSelection) {
                    OutlinedTextField(label, { label = it }, label = { Text("显示文字(可选)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                OutlinedTextField(url, { url = it }, label = { Text("网址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = url.isNotBlank(), onClick = { onSave(label, normalizeHref(url)); onDismiss() }) { Text("确定") }
        },
        dismissButton = {
            Row {
                if (isLink) TextButton(onClick = { onRemove(); onDismiss() }) { Text("移除链接", color = c.danger) }
                TextButton(onClick = onDismiss) { Text("取消", color = c.ink2) }
            }
        },
    )
}
