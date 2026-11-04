package com.openneno.neno.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReplyAll
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SubdirectoryArrowLeft
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.openneno.neno.data.NoteImage
import com.openneno.neno.data.NoteView
import com.openneno.neno.data.Times
import com.openneno.neno.ui.HtmlText
import com.openneno.neno.ui.Screen
import com.openneno.neno.ui.theme.Neno
import com.openneno.neno.ui.theme.NoteTextStyle
import kotlinx.coroutines.launch

/** 卡片上可以执行的操作 */
class NoteActions(
    val open: (Screen) -> Unit,
    val onTag: (String) -> Unit,
)

/** 内容过长时折叠的行数 */
private const val COLLAPSED_LINES = 10

/**
 * 一条笔记: 时间, 内容(过长时折叠), 图片, 批注关系, 更多操作.
 * @param collapsible 详情页中的主笔记不折叠
 * @param clickable 点击卡片打开详情
 */
@Composable
fun NoteCard(
    view: NoteView,
    actions: NoteActions,
    modifier: Modifier = Modifier,
    keyword: String = "",
    collapsible: Boolean = true,
    clickable: Boolean = true,
) {
    val note = view.note
    val c = Neno.colors
    val context = LocalContext.current
    val repo = repository()
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var expanded by rememberSaveable(note.id) { mutableStateOf(false) }
    var overflowing by remember(note.id, note.content) { mutableStateOf(false) }
    val canCollapse = collapsible && keyword.isBlank()

    NenoCard(modifier.fillMaxWidth(), onClick = if (clickable) ({ actions.open(Screen.Detail(note.id)) }) else null) {
        Column(Modifier.padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Times.display(note.createdAt) + if (note.updateAt.isNotEmpty()) " · 已编辑" else "",
                    fontSize = 12.sp, color = c.ink3, modifier = Modifier.weight(1f)
                )
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Outlined.MoreHoriz, "更多操作", tint = c.ink3, modifier = Modifier.size(20.dp))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = c.surface) {
                        MenuItem(Icons.Outlined.Share, "分享") { menu = false; actions.open(Screen.Share(note.id)) }
                        MenuItem(Icons.Outlined.ContentCopy, "复制") {
                            menu = false
                            copyText(context, note.plainText.trim())
                        }
                        MenuItem(Icons.Outlined.Edit, "编辑") { menu = false; actions.open(Screen.Editor(id = note.id)) }
                        MenuItem(Icons.Outlined.ChatBubbleOutline, "批注") {
                            menu = false; actions.open(Screen.Editor(parentId = note.id))
                        }
                        HorizontalDivider(color = c.line)
                        DropdownMenuItem(
                            text = { Text("删除", color = c.danger) },
                            leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = c.danger) },
                            onClick = { menu = false; confirmDelete = true },
                        )
                    }
                }
            }
            Collapsible(
                collapsed = canCollapse && !expanded,
                fade = c.surface,
                modifier = Modifier.padding(end = 12.dp),
                onOverflow = { if (overflowing != it) overflowing = it },
            ) {
                NoteContent(note.content, tags = note.tags, keyword = keyword, onTagClick = actions.onTag)
            }
            if (canCollapse && overflowing) {
                Row(
                    Modifier.padding(top = 4.dp).clip(RoundedCornerShape(6.dp)).clickable { expanded = !expanded }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (expanded) "收起" else "展开全文", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.accentText)
                    Icon(Icons.Outlined.ExpandMore, null, tint = c.accentText, modifier = Modifier.size(18.dp).rotate(if (expanded) 180f else 0f))
                }
            }
            if (note.images.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                ImageRow(note.images, onClick = { i -> actions.open(Screen.Images(note.images, i)) })
            }
            if (view.parent != null || view.children.isNotEmpty()) {
                Column(
                    Modifier.padding(top = 10.dp, end = 12.dp).fillMaxWidth()
                ) {
                    Row {
                        Box(Modifier.width(2.dp).height(26.dp * ((if (view.parent != null) 1 else 0) + view.children.size)).background(c.line))
                        Column(Modifier.padding(start = 10.dp)) {
                            view.parent?.let { parent ->
                                RelationRow(Icons.Outlined.SubdirectoryArrowLeft, parent.summary(80)) { actions.open(Screen.Detail(parent.id)) }
                            }
                            view.children.forEach { child ->
                                RelationRow(Icons.Outlined.FormatQuote, child.summary(80)) { actions.open(Screen.Detail(child.id)) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        val preview = note.plainText.replace(Regex("\\s+"), " ").trim()
        ConfirmDialog(
            title = "删除这条笔记?",
            message = "删除后无法恢复, 同步后其他设备上的这条笔记也会被删除。",
            preview = if (preview.length > 120) preview.take(120) + "..." else preview,
            confirmText = "删除", danger = true,
            onDismiss = { confirmDelete = false },
            onConfirm = { scope.launch { repo.delete(note.id) } },
        )
    }
}

@Composable
private fun RelationRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit) {
    val c = Neno.colors
    Row(
        Modifier.height(26.dp).fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = c.ink3, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(text.ifEmpty { "(图片)" }, fontSize = 13.sp, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun MenuItem(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = { Icon(icon, null, tint = Neno.colors.ink2, modifier = Modifier.size(20.dp)) },
        onClick = onClick,
    )
}

/** 笔记中的图片缩略图 */
@Composable
fun ImageRow(images: List<NoteImage>, size: androidx.compose.ui.unit.Dp = 96.dp, onClick: (Int) -> Unit) {
    val repo = repository()
    val c = Neno.colors
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        images.forEachIndexed { index, image ->
            AsyncImage(
                model = repo.picFile(image),
                contentDescription = "查看图片",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(RoundedCornerShape(10.dp)).border(1.dp, c.line, RoundedCornerShape(10.dp))
                    .background(c.surface2).clickable { onClick(index) },
            )
        }
    }
}

fun copyText(context: Context, text: String, toast: String = "已复制") {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("neno", text))
    Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
}

@Suppress("unused")
private val unusedIcon = Icons.AutoMirrored.Outlined.ReplyAll
