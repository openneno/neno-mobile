package com.openneno.neno.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openneno.neno.app
import com.openneno.neno.ui.AppState
import com.openneno.neno.ui.NoteFilter
import com.openneno.neno.ui.Screen
import com.openneno.neno.ui.components.EmptyState
import com.openneno.neno.ui.components.NenoCard
import com.openneno.neno.ui.components.NoteActions
import com.openneno.neno.ui.components.NoteCard
import com.openneno.neno.ui.components.TagIcon
import com.openneno.neno.ui.components.load
import com.openneno.neno.ui.components.repository
import com.openneno.neno.ui.components.tagName
import com.openneno.neno.ui.theme.Neno

private const val PAGE = 40

@Composable
fun NotesScreen(state: AppState, actions: NoteActions, openDrawer: () -> Unit) {
    val c = Neno.colors
    val repo = repository()
    val filter = state.filter
    var limit by rememberSaveable(filter) { mutableIntStateOf(PAGE) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf(filter.keyword) }
    val tagSettings by load { repo.tagSettings() }

    val views by load(filter, limit) {
        repo.views(repo.notes(filter.tag, filter.day, filter.keyword, limit))
    }
    val list = views
    val listState = rememberLazyListState()
    LaunchedEffect(filter) { listState.scrollToItem(0) }

    // 滚动到底部附近时加载更多
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= info.totalItemsCount - 6
        }
    }
    LaunchedEffect(nearEnd, list?.size) {
        if (nearEnd && list != null && list.size >= limit) limit += PAGE
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "header") {
                Header(
                    state, filter, searching, query, tagSettings?.icons.orEmpty(), openDrawer,
                    onQuery = { query = it },
                    onSearch = { state.filter = filter.copy(keyword = query.trim()) },
                    onToggleSearch = {
                        searching = !searching
                        if (!searching) {
                            query = ""; state.filter = filter.copy(keyword = "")
                        }
                    },
                )
            }
            if (!filter.active) item(key = "composer") {
                NenoCard(Modifier.fillMaxWidth(), onClick = { actions.open(Screen.Editor()) }) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("现在的想法是...", color = c.ink3, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Icon(Icons.Outlined.Draw, null, tint = c.ink3, modifier = Modifier.size(18.dp))
                    }
                }
            }
            if (filter.day.isNotEmpty()) item(key = "day") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${filter.day} 的笔记 · ${list?.size ?: 0} 条", fontSize = 13.sp, color = c.ink3, modifier = Modifier.weight(1f))
                    TextButton(onClick = { state.filter = NoteFilter() }) { Text("查看全部") }
                }
            }
            if (list == null) {
                item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) } }
            } else {
                items(list, key = { it.note.id }) { view ->
                    NoteCard(view, actions, Modifier.animateItem(), keyword = filter.keyword)
                }
                if (list.isEmpty()) item {
                    if (filter.active) EmptyState(Icons.Outlined.SearchOff, "没有找到相关笔记")
                    else EmptyState(Icons.Outlined.Draw, "还没有笔记, 写下第一条想法吧")
                } else if (list.size < limit) item {
                    Text(
                        "已经到底了 · 共 ${list.size} 条", Modifier.fillMaxWidth().padding(vertical = 14.dp),
                        fontSize = 12.sp, color = c.ink3, textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
        FloatingActionButton(
            onClick = { actions.open(Screen.Editor()) },
            containerColor = c.accent, contentColor = if (c.dark) Color(0xFF0F1013) else Color.White,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp),
        ) { Icon(Icons.Outlined.Add, "记录") }
    }
}



@Composable
private fun Header(
    state: AppState,
    filter: NoteFilter,
    searching: Boolean,
    query: String,
    icons: Map<String, String>,
    openDrawer: () -> Unit,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    onToggleSearch: () -> Unit,
) {
    val c = Neno.colors
    val focus = remember { FocusRequester() }
    androidx.compose.foundation.layout.Column(Modifier.statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = openDrawer) { Icon(Icons.Outlined.Menu, "菜单", tint = c.ink) }
            Box(Modifier.weight(1f)) {
                if (filter.tag.isNotEmpty()) {
                    Row(
                        Modifier.clip(RoundedCornerShape(10.dp)).background(c.accentSoft).padding(start = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TagIcon(filter.tag, icons, size = 16.dp)
                        Spacer(Modifier.width(4.dp))
                        Text(filter.tag.tagName(), color = c.accentText, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { state.filter = NoteFilter() }, Modifier.size(34.dp)) {
                            Icon(Icons.Outlined.Close, "取消筛选", tint = c.accentText, modifier = Modifier.size(16.dp))
                        }
                    }
                } else {
                    Text("全部笔记", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                }
            }
            SyncIndicator { state.open(Screen.Settings) }
            IconButton(onClick = onToggleSearch) {
                Icon(if (searching) Icons.Outlined.Close else Icons.Outlined.Search, "搜索笔记", tint = c.ink2)
            }
        }
        AnimatedVisibility(searching) {
            LaunchedEffect(Unit) { focus.requestFocus() }
            Row(
                Modifier.fillMaxWidth().padding(bottom = 10.dp).height(44.dp).clip(RoundedCornerShape(12.dp)).background(c.surface2).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Search, null, tint = c.ink3, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("搜索笔记", color = c.ink3, fontSize = 15.sp)
                    BasicTextField(
                        value = query, onValueChange = onQuery, singleLine = true,
                        textStyle = TextStyle(color = c.ink, fontSize = 15.sp),
                        cursorBrush = SolidColor(c.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
            }
        }
    }
}

/** 同步状态: 同步中显示进度, 出错时显示提示, 点击进入设置 */
@Composable
fun SyncIndicator(onClick: () -> Unit) {
    val c = Neno.colors
    val sync = LocalContext.current.app.sync
    val status by sync.status.collectAsStateWithLifecycle()
    if (status.progressAll > 0) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp)) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = c.ink3)
            Spacer(Modifier.width(4.dp))
            Text("${status.progressDone}/${status.progressAll}", fontSize = 12.sp, color = c.ink3)
        }
    } else if (status.error.isNotEmpty() || status.authError) {
        IconButton(onClick = onClick) { Icon(Icons.Outlined.CloudOff, "同步失败: ${status.error}", tint = c.danger, modifier = Modifier.size(20.dp)) }
    }
}

@Suppress("unused")
private fun Modifier.clickableNoop() = this.clickable { }
