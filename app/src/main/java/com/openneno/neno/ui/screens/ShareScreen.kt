package com.openneno.neno.ui.screens

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import com.openneno.neno.data.Note
import com.openneno.neno.data.NoteImage
import com.openneno.neno.data.NoteStats
import com.openneno.neno.data.Times
import com.openneno.neno.ui.HtmlColors
import com.openneno.neno.ui.components.NoteContent
import com.openneno.neno.ui.components.NoteTypography
import androidx.compose.ui.unit.em
import com.openneno.neno.ui.components.load
import com.openneno.neno.ui.components.repository
import com.openneno.neno.ui.theme.Neno
import com.openneno.neno.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class ShareTemplate(val label: String) { PAPER("纸笺"), AURORA("极光"), NIGHT("夜空") }

private val WEEK_FULL = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")

@Composable
fun ShareScreen(id: String, onBack: () -> Unit) {
    val c = Neno.colors
    val context = LocalContext.current
    val repo = repository()
    val scope = rememberCoroutineScope()
    val note by load(id) { repo.note(id) }
    val stats by load { repo.stats() }
    var template by remember { mutableStateOf(ShareTemplate.PAPER) }
    var busy by remember { mutableStateOf(false) }
    val layer = rememberGraphicsLayer()

    fun capture(action: suspend (Bitmap) -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                action(layer.toImageBitmap().asAndroidBitmap())
            } catch (e: Exception) {
                Toast.makeText(context, "生成图片失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("分享卡片", navIcon = Icons.AutoMirrored.Outlined.ArrowBack, onNav = onBack)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            note?.let { n ->
                Box(Modifier.drawWithContent {
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                }) {
                    ShareCard(n, template, c.primary, stats ?: NoteStats())
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ShareTemplate.entries.forEach { t ->
                val selected = t == template
                Box(
                    Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(10.dp))
                        .background(if (selected) c.accentSoft else c.surface2)
                        .clickable { template = t },
                    contentAlignment = Alignment.Center
                ) { Text(t.label, color = if (selected) c.accentText else c.ink2, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal) }
            }
        }
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(onClick = { capture { saveToGallery(context, it) } }, enabled = !busy && note != null, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("保存图片")
            }
            Button(
                onClick = { capture { shareBitmap(context, it) } }, enabled = !busy && note != null, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = if (c.dark) Color(0xFF0F1013) else Color.White)
            ) {
                Icon(Icons.Outlined.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("分享")
            }
        }
    }
}

private data class CardStyle(
    val background: Brush,
    val ink: Color,
    val ink2: Color,
    val accent: Color,
    val line: Color,
    val glow: Color? = null,
)

private fun cardStyle(t: ShareTemplate, p: Palette) = when (t) {
    ShareTemplate.PAPER -> CardStyle(
        Brush.verticalGradient(listOf(Color(0xFFFBF8F1), Color(0xFFF5EFE3))),
        Color(0xFF2B2622), Color(0xFF8A7F72), Color(0xFFB08D57), Color(0x1A2B2622)
    )

    ShareTemplate.AURORA -> CardStyle(
        Brush.linearGradient(listOf(p.p100, Color(0xFFF3F0FF), p.p200), start = Offset(0f, 0f), end = Offset(900f, 1400f)),
        Color(0xFF1D1F23), Color(0xFF565B63), p.p700, Color(0x14000000)
    )

    ShareTemplate.NIGHT -> CardStyle(
        Brush.verticalGradient(listOf(Color(0xFF0E1324), Color(0xFF161D38))),
        Color(0xFFE8EAF2), Color(0xFF9AA0B4), p.p300, Color(0x1FFFFFFF), glow = p.p500
    )
}

@Composable
private fun ShareCard(note: Note, template: ShareTemplate, palette: Palette, stats: NoteStats) {
    val repo = repository()
    val s = cardStyle(template, palette)
    val date = Times.parse(note.createdAt)
    val today = LocalDate.now()
    val day = date?.toLocalDate() ?: today
    val daysAgo = ChronoUnit.DAYS.between(day, today).coerceAtLeast(0)
    val yearsAgo = today.year - day.year
    val sameDay = day.monthValue == today.monthValue && day.dayOfMonth == today.dayOfMonth
    val age = when {
        daysAgo == 0L -> "今天的想法"
        daysAgo == 1L -> "昨天的想法"
        sameDay && yearsAgo > 0 -> "$yearsAgo 年前的今天"
        else -> "$daysAgo 天前的想法"
    }
    val htmlColors = HtmlColors(s.accent, s.accent, Color.Transparent, s.ink2, s.line, Color.Transparent)
    val typography = NoteTypography(
        body = TextStyle(fontSize = 16.sp, lineHeight = 1.75.em, color = s.ink),
        secondary = s.ink2, marker = s.ink2, quoteBar = s.accent.copy(alpha = 0.5f), codeBackground = s.line,
    )

    Box(
        Modifier.width(340.dp).clip(RoundedCornerShape(20.dp)).background(s.background)
            .drawBehind {
                s.glow?.let { g ->
                    drawCircle(Brush.radialGradient(listOf(g.copy(alpha = 0.45f), Color.Transparent), center = Offset(size.width * 0.85f, 0f), radius = size.width * 0.8f))
                }
            }
    ) {
        Column(Modifier.padding(28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("%02d".format(day.dayOfMonth), fontSize = 40.sp, fontWeight = FontWeight.Bold, color = s.ink, fontFamily = FontFamily.Serif)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("%d.%02d".format(day.year, day.monthValue), fontSize = 13.sp, color = s.ink, fontWeight = FontWeight.Medium)
                    Text("${WEEK_FULL[day.dayOfWeek.value - 1]} · ${Times.display(note.createdAt, "HH:mm")}", fontSize = 12.sp, color = s.ink2)
                }
                Text(age, fontSize = 12.sp, color = s.accent, modifier = Modifier.clip(RoundedCornerShape(20.dp)).border(1.dp, s.accent.copy(alpha = 0.4f), RoundedCornerShape(20.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
            }
            Spacer(Modifier.height(18.dp))
            Text("“", fontSize = 48.sp, lineHeight = 40.sp, color = s.accent.copy(alpha = 0.5f), fontFamily = FontFamily.Serif)
            NoteContent(note.content, tags = note.tags, colors = htmlColors, typography = typography)
            val images = note.images.take(4)
            if (images.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                if (images.size == 1) {
                    AsyncImage(repo.picFile(images[0]), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(12.dp)))
                } else {
                    images.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                            row.forEach { img ->
                                AsyncImage(repo.picFile(img), null, contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(10.dp)))
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(s.line))
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(20.dp).clip(RoundedCornerShape(5.dp)).background(palette.p500), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Draw, null, tint = Color.White, modifier = Modifier.size(13.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text("NENO", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = s.ink, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
                Text("记录的第 ${stats.dayCount} 天 · 共 ${stats.noteCount} 条笔记", fontSize = 11.sp, color = s.ink2)
            }
        }
    }
}

private suspend fun saveToGallery(context: Context, bitmap: Bitmap) = withContext(Dispatchers.IO) {
    val name = "neno-${System.currentTimeMillis()}.png"
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/NENO")
    }
    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("无法创建图片")
    context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    withContext(Dispatchers.Main) { Toast.makeText(context, "已保存到相册", Toast.LENGTH_SHORT).show() }
}

private suspend fun shareBitmap(context: Context, bitmap: Bitmap) {
    val file = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        File(dir, "neno-${System.currentTimeMillis()}.png").also { f -> f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "分享卡片"))
}

