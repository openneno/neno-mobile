package com.openneno.neno.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openneno.neno.data.Note
import com.openneno.neno.data.NoteView
import com.openneno.neno.data.Times
import com.openneno.neno.ui.AppState
import com.openneno.neno.ui.Screen
import com.openneno.neno.ui.components.EmptyState
import com.openneno.neno.ui.components.NoteActions
import com.openneno.neno.ui.components.NoteCard
import com.openneno.neno.ui.components.dataVersion
import com.openneno.neno.ui.components.load
import com.openneno.neno.ui.components.repository
import com.openneno.neno.ui.theme.Neno
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// ---------- 通用顶栏 ----------

@Composable
fun TopBar(title: String, subtitle: String = "", navIcon: ImageVector, onNav: () -> Unit, actions: @Composable () -> Unit = {}) {
    val c = Neno.colors
    Row(Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onNav) { Icon(navIcon, null, tint = c.ink) }
        Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            if (subtitle.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(subtitle, fontSize = 13.sp, color = c.ink3, modifier = Modifier.padding(bottom = 2.dp))
            }
        }
        actions()
    }
}

@Composable
private fun SectionTitle(icon: ImageVector, text: String, extra: String = "") {
    val c = Neno.colors
    Row(Modifier.padding(top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = c.ink3, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.ink2)
        if (extra.isNotEmpty()) {
            Spacer(Modifier.width(8.dp)); Text(extra, fontSize = 12.sp, color = c.ink3)
        }
    }
}

@Composable
private fun GoWrite(actions: NoteActions) {
    val c = Neno.colors
    EmptyState(Icons.Outlined.Draw, "还没有笔记") {
        Button(
            onClick = { actions.open(Screen.Editor()) },
            colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = if (c.dark) Color(0xFF0F1013) else Color.White)
        ) { Text("去记录") }
    }
}

// ---------- 笔记详情 ----------

@Composable
fun DetailScreen(id: String, actions: NoteActions, onBack: () -> Unit) {
    val c = Neno.colors
    val repo = repository()
    // null: 还在读取; listOf(null): 笔记不存在(已被删除)
    val result by load(id) { listOf(repo.view(id)) }
    val view = result?.firstOrNull()
    LaunchedEffect(result) { if (result != null && result!![0] == null) onBack() }
    val childViews by load(view) { view?.let { v -> repo.views(v.children) } }

    Column(Modifier.fillMaxSize()) {
        TopBar("笔记详情", navIcon = Icons.AutoMirrored.Outlined.ArrowBack, onNav = onBack)
        val v = view ?: return@Column
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { NoteCard(v.copy(children = emptyList()), actions, collapsible = false, clickable = false) }
            item { SectionTitle(Icons.Outlined.ChatBubbleOutline, "批注", if (v.children.isNotEmpty()) "${v.children.size}" else "") }
            items(childViews.orEmpty(), key = { it.note.id }) { child ->
                Row {
                    Box(Modifier.padding(end = 10.dp).width(2.dp).height(16.dp))
                    NoteCard(child, actions, Modifier.weight(1f))
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface2)
                        .clickable { actions.open(Screen.Editor(parentId = v.note.id)) }.padding(16.dp)
                ) { Text("写下你的批注...", color = c.ink3, fontSize = 15.sp) }
            }
        }
    }
}

// ---------- 每日回顾 ----------

private const val REVIEW_COUNT = 5
private val WEEK_DAYS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/** 与网页端 random.js 相同的可复现伪随机数, 保证同一天看到同一批笔记 */
class SeededRandom(seed: String) {
    private var a: Int

    init {
        var h = 1779033703 xor seed.length
        for (ch in seed) {
            h = (h xor ch.code) * -862048943 // 3432918353 的 int 表示
            h = (h shl 13) or (h ushr 19)
        }
        a = h
    }

    fun next(): Double {
        a += 0x6d2b79f5
        var t = (a xor (a ushr 15)) * (1 or a)
        t = (t + ((t xor (t ushr 7)) * (61 or t))) xor t
        return ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL) / 4294967296.0
    }
}

fun <T> shuffle(list: List<T>, random: () -> Double = { Math.random() }): List<T> {
    val result = list.toMutableList()
    for (i in result.size - 1 downTo 1) {
        val j = (random() * (i + 1)).toInt()
        val tmp = result[i]; result[i] = result[j]; result[j] = tmp
    }
    return result
}

@Composable
fun DailyScreen(state: AppState, actions: NoteActions, openDrawer: () -> Unit) {
    val c = Neno.colors
    val repo = repository()
    val today = LocalDate.now()
    val todayText = today.toString()
    var batch by rememberSaveable { mutableIntStateOf(0) }

    val all by load { repo.notes() }
    val onThisDay by load(all) {
        val md = today.format(DateTimeFormatter.ofPattern("MM-dd"))
        val notes = all.orEmpty().filter { n ->
            val d = Times.localDate(n.createdAt) ?: return@filter false
            today.year - d.year > 0 && d.format(DateTimeFormatter.ofPattern("MM-dd")) == md
        }
        repo.views(notes).groupBy { today.year - (Times.localDate(it.note.createdAt)?.year ?: today.year) }.toSortedMap()
    }
    val review by load(all, batch, onThisDay) {
        val notes = all ?: return@load null
        val shown = onThisDay.orEmpty().values.flatten().map { it.note.id }.toSet()
        val older = notes.filter { it.createdAt.take(10) != todayText }
        val fresh = older.filter { it.id !in shown }
        val pool = fresh.ifEmpty { older.ifEmpty { notes } }
        val random = SeededRandom("$todayText#$batch")
        repo.views(shuffle(pool) { random.next() }.take(REVIEW_COUNT))
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(
            "每日回顾", "${today.monthValue}月${today.dayOfMonth}日 ${WEEK_DAYS[today.dayOfWeek.value - 1]}",
            Icons.Outlined.Menu, openDrawer
        ) {
            if ((all?.size ?: 0) > REVIEW_COUNT) TextButton(onClick = { batch++ }) {
                Icon(Icons.Outlined.Refresh, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("换一批")
            }
        }
        when {
            all == null -> Loading()
            all!!.isEmpty() -> GoWrite(actions)
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                onThisDay?.forEach { (years, items) ->
                    item(key = "y$years") { SectionTitle(Icons.Outlined.History, "那年今日", "$years 年前的今天") }
                    items(items, key = { "otd" + it.note.id }) { NoteCard(it, actions) }
                }
                item(key = "review") {
                    SectionTitle(Icons.Outlined.AutoAwesome, "今日回顾", "从 ${all!!.size} 条笔记中为你挑选")
                }
                items(review.orEmpty(), key = { "r" + it.note.id }) { NoteCard(it, actions) }
            }
        }
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}

// ---------- 随机漫步 ----------

@Composable
fun WalkScreen(state: AppState, actions: NoteActions, openDrawer: () -> Unit) {
    val c = Neno.colors
    val repo = repository()
    var filterTag by rememberSaveable { mutableStateOf("") }
    val all by load { repo.notes() }
    val tags by load { repo.allTags() }
    val candidates = remember(all, filterTag) { all.orEmpty().filter { filterTag.isEmpty() || filterTag in it.tags } }

    // 随机顺序的队列, 一轮走完前不会重复; history 支持返回上一条
    val deck = remember(filterTag) { mutableStateListOf<String>() }
    val history = remember(filterTag) { mutableStateListOf<String>() }
    var index by remember(filterTag) { mutableIntStateOf(-1) }
    var direction by remember { mutableIntStateOf(1) }
    var tagMenu by remember { mutableStateOf(false) }

    fun next() {
        direction = 1
        if (index < history.size - 1) {
            index++; return
        }
        if (candidates.isEmpty()) return
        val ids = candidates.map { it.id }.toSet()
        deck.removeAll { it !in ids }
        if (deck.isEmpty()) {
            val shuffled = shuffle(candidates.map { it.id }).toMutableList()
            // 新一轮的第一条不和刚看过的重复
            val current = history.getOrNull(index)
            if (shuffled.size > 1 && shuffled[0] == current) shuffled.add(shuffled.removeAt(0))
            deck += shuffled
        }
        history += deck.removeAt(0)
        index = history.size - 1
    }

    fun prev() {
        if (index > 0) {
            direction = -1; index--
        }
    }

    LaunchedEffect(candidates.isNotEmpty(), filterTag) { if (index < 0 && candidates.isNotEmpty()) next() }

    val version = dataVersion()
    val currentId = history.getOrNull(index)
    val current by load(currentId) { currentId?.let { repo.view(it) } }
    // 当前笔记被删除: 跳到下一条
    LaunchedEffect(current, version) {
        if (currentId != null && current == null && all != null && candidates.none { it.id == currentId }) {
            history.remove(currentId); index = (index).coerceAtMost(history.size - 1)
            if (index < 0) next()
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("随机漫步", if (candidates.isNotEmpty()) "共 ${candidates.size} 条" else "", Icons.Outlined.Menu, openDrawer) {
            if (!tags.isNullOrEmpty()) Box {
                TextButton(onClick = { tagMenu = true }) {
                    Text(if (filterTag.isEmpty()) "全部笔记" else filterTag, color = c.ink2, maxLines = 1)
                    Icon(Icons.Outlined.ArrowDropDown, null, tint = c.ink2)
                }
                DropdownMenu(tagMenu, { tagMenu = false }, containerColor = c.surface) {
                    DropdownMenuItem({ Text("全部笔记") }, onClick = { filterTag = ""; tagMenu = false })
                    tags!!.forEach { t -> DropdownMenuItem({ Text(t) }, onClick = { filterTag = t; tagMenu = false }) }
                }
            }
        }
        when {
            all == null -> Loading()
            candidates.isEmpty() -> GoWrite(actions)
            else -> Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
                    AnimatedContent(
                        targetState = current,
                        transitionSpec = {
                            (slideInHorizontally(tween(220)) { it / 4 * direction } + fadeIn(tween(200))) togetherWith
                                (slideOutHorizontally(tween(180)) { -it / 4 * direction } + fadeOut(tween(160)))
                        },
                        contentKey = { it?.note?.id },
                        label = "walk",
                    ) { v -> if (v != null) NoteCard(v, actions) else Spacer(Modifier.height(1.dp)) }
                }
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
                ) {
                    IconButton(onClick = { prev() }, enabled = index > 0) {
                        Icon(Icons.Outlined.ChevronLeft, "上一条", tint = if (index > 0) c.ink2 else c.ink3.copy(alpha = 0.4f))
                    }
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = { next() }, shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = if (c.dark) Color(0xFF0F1013) else Color.White),
                        modifier = Modifier.height(46.dp)
                    ) {
                        Icon(Icons.Outlined.DirectionsWalk, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("再走一步")
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("已漫步 ${history.size} 条", fontSize = 12.sp, color = c.ink3)
                }
            }
        }
    }
}

@Suppress("unused")
private fun unusedRefs(n: Note, v: NoteView) = n to v
