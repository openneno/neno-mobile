package com.openneno.neno.ui.screens

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openneno.neno.app
import com.openneno.neno.data.Appearance
import com.openneno.neno.data.ThemeMode
import com.openneno.neno.ui.AppState
import com.openneno.neno.ui.Screen
import com.openneno.neno.ui.components.EmojiPickerDialog
import com.openneno.neno.ui.components.Heatmap
import com.openneno.neno.ui.components.MenuItem
import com.openneno.neno.ui.components.NenoCard
import com.openneno.neno.ui.components.SectionLabel
import com.openneno.neno.ui.components.TagIcon
import com.openneno.neno.ui.components.TextInputDialog
import com.openneno.neno.ui.components.load
import com.openneno.neno.ui.components.repository
import com.openneno.neno.ui.components.tagName
import com.openneno.neno.ui.theme.Neno
import com.openneno.neno.ui.theme.isDark
import kotlinx.coroutines.launch

/** 标签树: "#读书/小说" 拆成 读书 -> 小说 */
data class TagNode(val tag: String, val name: String, val children: List<TagNode>)

fun buildTagTree(tags: List<String>): List<TagNode> {
    class MNode(val tag: String, val name: String) { val children = linkedMapOf<String, MNode>() }
    val roots = linkedMapOf<String, MNode>()
    for (tag in tags) {
        val parts = tag.split("/")
        var path = ""
        var level = roots
        for (part in parts) {
            path = if (path.isEmpty()) part else "$path/$part"
            val node = level.getOrPut(path) { MNode(path, part) }
            level = node.children
        }
    }
    fun convert(n: MNode): TagNode = TagNode(n.tag, n.name, n.children.values.map { convert(it) })
    return roots.values.map { convert(it) }
}

@Composable
fun DrawerContent(state: AppState, close: () -> Unit) {
    val c = Neno.colors
    val context = LocalContext.current
    val repo = repository()
    val scope = rememberCoroutineScope()
    val settings = context.app.settings
    val appearance by settings.appearance.collectAsStateWithLifecycle()
    val dark = isDark(appearance)
    val stats by load { repo.stats() }
    val tags by load { repo.allTags() }
    val tagSettings by load { repo.tagSettings() }
    val pins = tagSettings?.tags.orEmpty()
    val icons = tagSettings?.icons.orEmpty()

    var iconFor by remember { mutableStateOf<String?>(null) }
    var renameFor by remember { mutableStateOf<String?>(null) }

    fun go(screen: Screen.Main) {
        state.open(screen); close()
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = 32.dp)) {
        Row(Modifier.height(56.dp).fillMaxWidth().padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(26.dp).clip(RoundedCornerShape(7.dp)).background(c.primary.p500), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Draw, null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text("NENO", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = {
                settings.setAppearance(appearance.copy(mode = if (dark) ThemeMode.LIGHT else ThemeMode.DARK))
            }) { Icon(if (dark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode, if (dark) "切换到浅色" else "切换到深色", tint = c.ink2) }
            IconButton(onClick = { go(Screen.Settings) }) {
                Icon(Icons.Outlined.Settings, "设置", tint = if (state.main == Screen.Settings) c.accent else c.ink2)
            }
        }

        NenoCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    Stat(stats?.noteCount ?: 0, "笔记", Modifier.weight(1f))
                    Stat(stats?.tagCount ?: 0, "标签", Modifier.weight(1f))
                    Stat(stats?.dayCount ?: 0, "天", Modifier.weight(1f))
                }
                Heatmap(stats?.countByDay.orEmpty(), c, onDayClick = { day ->
                    state.showDay(day); close()
                })
            }
        }

        Spacer(Modifier.height(14.dp))
        NavItem(Icons.Outlined.Draw, "全部笔记", state.main == Screen.Notes && state.filter.tag.isEmpty()) {
            state.filter = com.openneno.neno.ui.NoteFilter(); go(Screen.Notes)
        }
        NavItem(Icons.Outlined.EventAvailable, "每日回顾", state.main == Screen.Daily) { go(Screen.Daily) }
        NavItem(Icons.Outlined.DirectionsWalk, "随机漫步", state.main == Screen.Walk) { go(Screen.Walk) }

        if (pins.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            SectionLabel("置顶")
            pins.forEach { tag ->
                TagRow(
                    tag = tag, name = tag.tagName(), icons = icons, pinned = true, depth = 0, hasChildren = false,
                    expanded = false, selected = state.main == Screen.Notes && state.filter.tag == tag,
                    onClick = { state.showTag(tag); close() },
                    onToggle = {},
                    onIcon = { iconFor = tag }, onRename = { renameFor = tag },
                    onPin = { scope.launch { repo.togglePin(tag) } },
                )
            }
        }

        val tree = remember(tags, pins) { buildTagTree(tags.orEmpty().filter { it !in pins }) }
        if (tree.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            SectionLabel("标签")
            tree.forEach { node ->
                TagTree(node, 0, state, icons, pins, close, onIcon = { iconFor = it }, onRename = { renameFor = it }, onPin = { t ->
                    scope.launch { repo.togglePin(t) }
                })
            }
        }
    }

    iconFor?.let { tag ->
        EmojiPickerDialog(tag, icons[tag].orEmpty(), onDismiss = { iconFor = null }) { icon ->
            scope.launch { repo.setTagIcon(tag, icon) }
        }
    }
    renameFor?.let { tag ->
        TextInputDialog("重命名标签", tag.tagName(), prefix = "#", onDismiss = { renameFor = null }) { name ->
            val trimmed = name.trim().removePrefix("#").replace(Regex("\\s+"), "")
            if (trimmed.isNotEmpty()) scope.launch {
                repo.renameTag(tag, "#$trimmed")
                if (state.filter.tag == tag) state.showTag("#$trimmed")
            }
        }
    }
}

@Composable
private fun TagTree(
    node: TagNode, depth: Int, state: AppState, icons: Map<String, String>, pins: List<String>, close: () -> Unit,
    onIcon: (String) -> Unit, onRename: (String) -> Unit, onPin: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    TagRow(
        tag = node.tag, name = node.name.tagName(), icons = icons, pinned = node.tag in pins, depth = depth,
        hasChildren = node.children.isNotEmpty(), expanded = expanded,
        selected = state.main == Screen.Notes && state.filter.tag == node.tag,
        onClick = { state.showTag(node.tag); close() },
        onToggle = { expanded = !expanded },
        onIcon = { onIcon(node.tag) }, onRename = { onRename(node.tag) }, onPin = { onPin(node.tag) },
    )
    if (expanded) node.children.forEach { TagTree(it, depth + 1, state, icons, pins, close, onIcon, onRename, onPin) }
}

@Composable
private fun TagRow(
    tag: String, name: String, icons: Map<String, String>, pinned: Boolean, depth: Int, hasChildren: Boolean,
    expanded: Boolean, selected: Boolean, onClick: () -> Unit, onToggle: () -> Unit,
    onIcon: () -> Unit, onRename: () -> Unit, onPin: () -> Unit,
) {
    val c = Neno.colors
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = (depth * 14).dp).height(40.dp).clip(RoundedCornerShape(10.dp))
            .background(if (selected) c.accentSoft else Color.Transparent)
            .clickable(onClick = onClick).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TagIcon(tag, icons, fallback = if (pinned) Icons.Outlined.PushPin else Icons.Outlined.Tag)
        Spacer(Modifier.width(10.dp))
        Text(
            name, Modifier.weight(1f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (selected) c.accentText else c.ink2, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
        )
        Box {
            IconButton(onClick = { menu = true }, Modifier.size(36.dp)) {
                Icon(Icons.Outlined.MoreVert, "标签操作", tint = c.ink3, modifier = Modifier.size(18.dp))
            }
            DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = c.surface) {
                MenuItem(Icons.Outlined.EmojiEmotions, "设置图标") { menu = false; onIcon() }
                MenuItem(Icons.Outlined.Edit, "重命名") { menu = false; onRename() }
                MenuItem(Icons.Outlined.PushPin, if (pinned) "取消置顶" else "置顶") { menu = false; onPin() }
            }
        }
        if (hasChildren) {
            IconButton(onClick = onToggle, Modifier.size(36.dp)) {
                Icon(
                    Icons.Outlined.ChevronRight, if (expanded) "收起子标签" else "展开子标签", tint = c.ink3,
                    modifier = Modifier.size(20.dp).rotate(if (expanded) 90f else 0f)
                )
            }
        }
    }
}


@Composable
private fun Stat(value: Int, label: String, modifier: Modifier) {
    Column(modifier) {
        Text("$value", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Neno.colors.ink)
        Text(label, fontSize = 12.sp, color = Neno.colors.ink3)
    }
}

@Composable
private fun NavItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val c = Neno.colors
    Row(
        Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(10.dp))
            .background(if (selected) c.accentSoft else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, null, tint = if (selected) c.accentText else c.ink2, modifier = Modifier.size(20.dp))
        Text(label, fontSize = 15.sp, color = if (selected) c.accentText else c.ink, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
    }
}

@Suppress("unused")
private fun unused(a: Appearance) = a
