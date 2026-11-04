package com.openneno.neno.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.openneno.neno.MainActivity
import com.openneno.neno.R
import com.openneno.neno.app
import com.openneno.neno.data.Note
import com.openneno.neno.data.NoteStats
import com.openneno.neno.data.Times
import com.openneno.neno.ui.components.HeatmapData
import com.openneno.neno.ui.theme.nenoColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

/** 桌面组件的配色: 跟随应用的主题色, 浅色/深色跟随系统 */
private class WidgetColors(context: Context) {
    private val color = context.app.settings.appearance.value.color
    private val light = nenoColors(false, color)
    private val dark = nenoColors(true, color)

    private fun p(l: Color, d: Color) = ColorProvider(day = l, night = d)

    val background = p(light.surface, dark.surface)
    val surface2 = p(light.surface2, dark.surface2)
    val ink = p(light.ink, dark.ink)
    val ink2 = p(light.ink2, dark.ink2)
    val ink3 = p(light.ink3, dark.ink3)
    val accent = p(light.accent, dark.accent)
    val accentText = p(light.accentText, dark.accentText)
    val accentSoft = p(light.primary.p100, dark.primary.p900)
    val brand = p(light.primary.p500, dark.primary.p500)
    val white = p(Color.White, Color.White)

    fun heat(count: Int, future: Boolean): ColorProvider =
        if (future) p(Color.Transparent, Color.Transparent)
        else p(HeatmapData.color(light, count), HeatmapData.color(dark, count))
}

private fun open(context: Context, action: String, noteId: String? = null, day: String? = null) =
    actionStartActivity(MainActivity.intent(context, action, noteId, day))

@Composable
private fun Brand(c: WidgetColors) {
    Box(
        GlanceModifier.size(22.dp).cornerRadius(6.dp).background(c.brand),
        contentAlignment = Alignment.Center
    ) {
        Image(ImageProvider(R.drawable.ic_quill), null, GlanceModifier.size(14.dp), colorFilter = ColorFilter.tint(c.white))
    }
}

// ---------- 快速记录 ----------

class QuickNoteWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val c = WidgetColors(context)
        provideContent { GlanceTheme { QuickNote(context, c) } }
    }

    @Composable
    private fun QuickNote(context: Context, c: WidgetColors) {
        val wide = LocalSize.current.width >= 220.dp
        Row(
            GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(20.dp).background(c.background).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                GlanceModifier.defaultWeight().height(44.dp).cornerRadius(14.dp).background(c.surface2)
                    .clickable(open(context, MainActivity.ACTION_COMPOSE)).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Brand(c)
                Spacer(GlanceModifier.width(10.dp))
                Text("现在的想法是...", style = TextStyle(color = c.ink3, fontSize = 14.sp), maxLines = 1)
            }
            if (wide) {
                Spacer(GlanceModifier.width(6.dp))
                IconButton(context, R.drawable.ic_calendar, MainActivity.ACTION_DAILY, c)
                IconButton(context, R.drawable.ic_walk, MainActivity.ACTION_WALK, c)
            }
        }
    }

    @Composable
    private fun IconButton(context: Context, icon: Int, action: String, c: WidgetColors) {
        Box(
            GlanceModifier.size(40.dp).cornerRadius(12.dp).clickable(open(context, action)),
            contentAlignment = Alignment.Center
        ) {
            Image(ImageProvider(icon), null, GlanceModifier.size(22.dp), colorFilter = ColorFilter.tint(c.ink2))
        }
    }
}

class QuickNoteWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickNoteWidget()
}

// ---------- 随机漫步 / 那年今日 ----------

private val NOTE_ID = stringPreferencesKey("note_id")

class RandomNoteWidget : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = context.app.repository
        // 第一次显示或者笔记已被删除时重新挑选
        var noteId: String? = null
        updateAppWidgetState(context, id) { prefs ->
            val current = prefs[NOTE_ID]
            if (current == null || repo.db.getNote(current) == null) {
                pickNote(context)?.let { prefs[NOTE_ID] = it } ?: prefs.remove(NOTE_ID)
            }
            noteId = prefs[NOTE_ID]
        }
        val c = WidgetColors(context)
        provideContent {
            val prefs = currentState<Preferences>()
            val note = (prefs[NOTE_ID] ?: noteId)?.let { repo.db.getNote(it) }
            GlanceTheme { Content(context, c, note) }
        }
    }

    @Composable
    private fun Content(context: Context, c: WidgetColors, note: Note?) {
        val today = LocalDate.now()
        val day = note?.let { Times.localDate(it.createdAt) }
        val years = if (day != null && day.monthValue == today.monthValue && day.dayOfMonth == today.dayOfMonth) today.year - day.year else 0
        Column(
            GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(20.dp).background(c.background).padding(16.dp)
        ) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Brand(c)
                Spacer(GlanceModifier.width(8.dp))
                Text(
                    if (years > 0) "那年今日 · $years 年前" else "随机漫步",
                    style = TextStyle(color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                    modifier = GlanceModifier.defaultWeight()
                )
                Box(
                    GlanceModifier.size(32.dp).cornerRadius(10.dp).clickable(actionRunCallback<NextNoteAction>()),
                    contentAlignment = Alignment.Center
                ) {
                    Image(ImageProvider(R.drawable.ic_refresh), "换一条", GlanceModifier.size(20.dp), colorFilter = ColorFilter.tint(c.ink3))
                }
            }
            Spacer(GlanceModifier.height(8.dp))
            if (note == null) {
                Box(
                    GlanceModifier.fillMaxWidth().defaultWeight().clickable(open(context, MainActivity.ACTION_COMPOSE)),
                    contentAlignment = Alignment.Center
                ) { Text("还没有笔记, 点击写下第一条想法", style = TextStyle(color = c.ink3, fontSize = 14.sp)) }
            } else {
                val lines = ((LocalSize.current.height.value - 90) / 21).toInt().coerceIn(2, 14)
                Column(GlanceModifier.fillMaxWidth().defaultWeight().clickable(open(context, MainActivity.ACTION_DETAIL, note.id))) {
                    Text(
                        note.plainText.trim().ifEmpty { "(图片)" },
                        style = TextStyle(color = c.ink, fontSize = 15.sp),
                        maxLines = lines,
                        modifier = GlanceModifier.defaultWeight()
                    )
                    Text(
                        Times.display(note.createdAt, "yyyy-MM-dd") + if (note.tags.isNotEmpty()) "  " + note.tags.take(3).joinToString(" ") else "",
                        style = TextStyle(color = c.accentText, fontSize = 12.sp), maxLines = 1
                    )
                }
            }
        }
    }

    companion object {
        /** 随机挑选一条笔记, 尽量不和当前的重复 */
        fun pickNote(context: Context, exclude: String? = null): String? {
            val db = context.app.repository.db
            val ids = db.allIds()
            if (ids.isEmpty()) return null
            val pool = ids.filter { it != exclude }.ifEmpty { ids }
            return pool.random()
        }
    }
}

class NextNoteAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        updateAppWidgetState(context, glanceId) { prefs ->
            RandomNoteWidget.pickNote(context, prefs[NOTE_ID])?.let { prefs[NOTE_ID] = it }
        }
        RandomNoteWidget().update(context, glanceId)
    }
}

class RandomNoteWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RandomNoteWidget()
}

// ---------- 记录热力图 ----------

class HeatmapWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val stats = context.app.repository.stats()
        val c = WidgetColors(context)
        provideContent { GlanceTheme { Content(context, c, stats) } }
    }

    @Composable
    private fun Content(context: Context, c: WidgetColors, stats: NoteStats) {
        val size = LocalSize.current
        val w = size.width.value
        val h = size.height.value
        // 宽而矮(4×2)时统计数字放在左侧, 热力图占满右侧的高度; 否则上下排列
        val sideBySide = w / h > 1.5f
        val padding = 16f
        val gap = 3f
        val gridW = w - padding * 2 - if (sideBySide) 76f else 0f
        val gridH = h - padding * 2 - if (sideBySide) 0f else 54f
        // 每个格子四周各有 gap/2 的边距
        val cell = (gridH / 7 - gap).coerceIn(6f, 20f)
        val weeks = (gridW / (cell + gap)).toInt().coerceIn(4, 20)

        val modifier = GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(20.dp).background(c.background)
            .padding(padding.dp).clickable(open(context, MainActivity.ACTION_COMPOSE))
        if (sideBySide) {
            Row(modifier, verticalAlignment = Alignment.CenterVertically) {
                Column(GlanceModifier.width(76.dp).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                    Stat("${stats.noteCount}", "笔记", c)
                    Spacer(GlanceModifier.height(6.dp))
                    Stat("${stats.dayCount}", "天", c)
                }
                Grid(context, c, stats, weeks, cell.dp, gap.dp, GlanceModifier.defaultWeight())
            }
        } else {
            Column(modifier) {
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(GlanceModifier.defaultWeight()) { Stat("${stats.noteCount}", "笔记", c) }
                    Column(GlanceModifier.defaultWeight()) { Stat("${stats.tagCount}", "标签", c) }
                    Column(GlanceModifier.defaultWeight()) { Stat("${stats.dayCount}", "天", c) }
                }
                Spacer(GlanceModifier.height(10.dp))
                Grid(context, c, stats, weeks, cell.dp, gap.dp, GlanceModifier.fillMaxWidth().defaultWeight())
            }
        }
    }

    /** 热力图: 每列一周, 最右一列包含今天 */
    @Composable
    private fun Grid(context: Context, c: WidgetColors, stats: NoteStats, weeks: Int, cell: androidx.compose.ui.unit.Dp, gap: androidx.compose.ui.unit.Dp, modifier: GlanceModifier) {
        // 桌面组件的视图数量有限制(每个容器最多 10 个子元素, 总数也有上限), 热力图画成一张图片
        val bitmap = heatmapBitmap(context, stats.countByDay, weeks, cell, gap.value)
        val width = (weeks * (cell.value + gap.value) - gap.value).dp
        val height = (7 * (cell.value + gap.value) - gap.value).dp
        Row(modifier, horizontalAlignment = Alignment.End, verticalAlignment = Alignment.CenterVertically) {
            Image(ImageProvider(bitmap), "最近 $weeks 周的记录", GlanceModifier.size(width, height))
        }
    }

    @Composable
    private fun Stat(value: String, label: String, c: WidgetColors) {
        Column {
            Text(value, style = TextStyle(color = c.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold))
            Text(label, style = TextStyle(color = c.ink3, fontSize = 11.sp))
        }
    }
}

class HeatmapWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HeatmapWidget()
}

/**
 * 热力图图片. 颜色使用半透明的灰色和主题色, 在浅色和深色背景上都清晰.
 */
private fun heatmapBitmap(context: Context, counts: Map<String, Int>, weeks: Int, cell: androidx.compose.ui.unit.Dp, gapDp: Float): android.graphics.Bitmap {
    val density = context.resources.displayMetrics.density
    val cellPx = cell.value * density
    val gapPx = gapDp * density
    val width = (weeks * (cellPx + gapPx) - gapPx).toInt().coerceAtLeast(1)
    val height = (7 * (cellPx + gapPx) - gapPx).toInt().coerceAtLeast(1)
    val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    val color = context.app.settings.appearance.value.color
    val accent = com.openneno.neno.ui.theme.Palette(com.openneno.neno.ui.theme.PALETTES.getValue(color)).p500.toArgb()
    val today = LocalDate.now()
    val radius = 3 * density
    HeatmapData.grid(today, weeks).forEachIndexed { x, week ->
        week.forEachIndexed { y, date ->
            if (date.isAfter(today)) return@forEachIndexed
            val count = counts[date.toString()] ?: 0
            paint.color = when (HeatmapData.level(count)) {
                0 -> android.graphics.Color.argb(46, 128, 128, 128)
                1 -> withAlpha(accent, 0.35f)
                2 -> withAlpha(accent, 0.65f)
                else -> accent
            }
            val left = x * (cellPx + gapPx)
            val top = y * (cellPx + gapPx)
            canvas.drawRoundRect(left, top, left + cellPx, top + cellPx, radius, radius, paint)
            if (date == today) {
                paint.style = android.graphics.Paint.Style.STROKE
                paint.strokeWidth = 1.5f * density
                paint.color = accent
                canvas.drawRoundRect(left, top, left + cellPx, top + cellPx, radius, radius, paint)
                paint.style = android.graphics.Paint.Style.FILL
            }
        }
    }
    return bitmap
}

private fun withAlpha(color: Int, alpha: Float) =
    android.graphics.Color.argb((alpha * 255).toInt(), android.graphics.Color.red(color), android.graphics.Color.green(color), android.graphics.Color.blue(color))

/** 笔记变化后刷新桌面组件(合并短时间内的多次变化) */
object WidgetUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    fun requestUpdate(context: Context) {
        val app = context.applicationContext
        job?.cancel()
        job = scope.launch {
            delay(800)
            updateNow(app)
        }
    }

    suspend fun updateNow(context: Context) {
        val manager = GlanceAppWidgetManager(context)
        if (manager.getGlanceIds(RandomNoteWidget::class.java).isNotEmpty()) RandomNoteWidget().updateAll(context)
        if (manager.getGlanceIds(HeatmapWidget::class.java).isNotEmpty()) HeatmapWidget().updateAll(context)
        if (manager.getGlanceIds(QuickNoteWidget::class.java).isNotEmpty()) QuickNoteWidget().updateAll(context)
    }
}

@Suppress("unused")
private fun unused(m: GlanceModifier) = m.fillMaxHeight()
