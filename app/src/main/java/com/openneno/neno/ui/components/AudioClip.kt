package com.openneno.neno.ui.components

import android.media.MediaPlayer
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.ui.draw.scale
import kotlin.math.abs
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import com.openneno.neno.ui.theme.Neno
import kotlinx.coroutines.delay
import java.io.File

enum class ClipStatus { IDLE, TRANSCRIBING, ERROR }

/**
 * 同一时间只播放一段语音. 开始录音, 切换页面, 应用退到后台时调用 [stopAll] 暂停正在播放的语音.
 */
object AudioPlayback {
    internal var stop: (() -> Unit)? = null

    fun stopAll() {
        stop?.invoke()
        stop = null
    }
}

/** 倍速: 设置后如果原来是暂停的, 保持暂停(有的设备设置倍速会直接开始播放) */
private fun MediaPlayer.applySpeed(speed: Float) {
    val wasPlaying = isPlaying
    runCatching { playbackParams = playbackParams.setSpeed(speed) }
    if (!wasPlaying && isPlaying) pause()
}

fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

/**
 * 一段语音: 播放原始录音, 展开查看识别出的原文. 与网页端 AudioClip.svelte 一致.
 * 平时不显示进度条: 播放时进度画在播放按钮外的细圈上; 在语音条上左右拖动时才出现进度, 松手跳到对应位置.
 * 笔记中的语音条宽度随时长变化; 编辑器中传入 onRetry / onDelete, 铺满宽度并显示识别状态, 重新识别和移除按钮.
 */
@Composable
fun AudioClip(
    file: File,
    durationMs: Long,
    text: String,
    modifier: Modifier = Modifier,
    status: ClipStatus = ClipStatus.IDLE,
    error: String = "",
    onRetry: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    val c = Neno.colors
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var player by remember(file) { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember(file) { mutableStateOf(false) }
    var position by remember(file) { mutableLongStateOf(0L) }
    var showText by rememberSaveable(file.path) { mutableStateOf(false) }
    // 拖动中的目标位置(0~1), null 表示没有在拖动
    var scrub by remember(file) { mutableStateOf<Float?>(null) }
    // 长按加速: boosting 按住期间临时 2x; speedLocked 上滑松手后固定 2x; speedZone 1 上滑区(固定) -1 下滑区(取消) 0 其他
    var boosting by remember(file) { mutableStateOf(false) }
    var speedLocked by remember(file) { mutableStateOf(false) }
    var speedZone by remember(file) { mutableStateOf(0) }
    val editable = onDelete != null

    fun release() {
        player?.release()
        player = null
        playing = false
    }
    DisposableEffect(file) { onDispose { release() } }
    LaunchedEffect(playing) {
        while (playing) {
            player?.let { position = it.currentPosition.toLong() }
            delay(50)
        }
    }

    fun ensurePlayer(): MediaPlayer? {
        player?.let { return it }
        if (!file.exists()) {
            Toast.makeText(context, "语音还没有同步到这台设备", Toast.LENGTH_SHORT).show()
            return null
        }
        val created = runCatching {
            MediaPlayer().apply {
                setDataSource(file.path)
                prepare()
                setOnCompletionListener { playing = false; position = 0L; seekTo(0) }
            }
        }.getOrNull()
        if (created == null) Toast.makeText(context, "无法播放这段语音", Toast.LENGTH_SHORT).show()
        player = created
        return created
    }

    fun startPlayback(speed: Float): MediaPlayer? {
        AudioPlayback.stopAll()
        val mp = ensurePlayer() ?: return null
        mp.start()
        mp.applySpeed(speed)
        playing = true
        AudioPlayback.stop = { if (player === mp && playing) { mp.pause(); playing = false } }
        return mp
    }

    fun toggle() {
        val p = player
        if (p != null && playing) {
            p.pause(); playing = false
            return
        }
        startPlayback(if (speedLocked) 2f else 1f)
    }

    val progress = scrub ?: if (durationMs > 0) (position.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val scrubAlpha by animateFloatAsState(if (scrub != null) 1f else 0f, tween(160), label = "scrub")
    val shape = RoundedCornerShape(12.dp)

    BoxWithConstraints(modifier.fillMaxWidth()) {
        // 笔记中: 宽度随时长变化(像聊天里的语音消息); 展开原文时铺满, 让文字有足够的横向空间
        val compact = (150.dp + (durationMs / 1000f * 2.4f).dp).coerceAtMost(maxWidth)
        val width by animateDpAsState(if (editable || showText) maxWidth else compact, tween(220), label = "width")
        Column(Modifier.width(width).clip(shape).background(c.surface2.copy(alpha = 0.6f)).border(1.dp, c.line, shape).animateContentSize()) {
            Row(
                Modifier.fillMaxWidth().height(44.dp)
                    .drawBehind {
                        if (scrubAlpha > 0f) {
                            drawRect(c.accent.copy(alpha = 0.14f * scrubAlpha), size = Size(size.width * progress, size.height))
                        }
                    }
                    .pointerInput(file, durationMs) {
                        // 左右拖动: 调整进度; 长按: 临时 2x 倍速, 上滑松手固定 2x, 下滑松手取消 2x
                        val zoneDistance = 36.dp.toPx()
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (durationMs <= 0) return@awaitEachGesture
                            val deadline = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis
                            var lastTime = down.uptimeMillis
                            var seeking = false
                            while (true) {
                                val event = withTimeoutOrNull((deadline - lastTime).coerceAtLeast(1)) { awaitPointerEvent() } ?: break
                                val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                                lastTime = change.uptimeMillis
                                // 点击交给按钮和卡片; 已被列表滚动消费时放弃
                                if (!change.pressed || change.isConsumed) return@awaitEachGesture
                                val d = change.position - down.position
                                if (abs(d.x) > viewConfiguration.touchSlop && abs(d.x) > abs(d.y)) {
                                    seeking = true; break
                                }
                                if (abs(d.y) > viewConfiguration.touchSlop) return@awaitEachGesture
                                if (lastTime >= deadline) break
                            }
                            if (ensurePlayer() == null) return@awaitEachGesture
                            if (seeking) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                scrub = (currentEvent.changes.first().position.x / size.width).coerceIn(0f, 1f)
                                while (true) {
                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                    change.consume()
                                    if (!change.pressed) break
                                    scrub = (change.position.x / size.width).coerceIn(0f, 1f)
                                }
                                val target = scrub
                                if (target != null) {
                                    val ms = (target * durationMs).toLong()
                                    player?.seekTo(ms.toInt())
                                    position = ms
                                }
                                scrub = null
                            } else {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (!playing) startPlayback(2f) else player?.applySpeed(2f)
                                boosting = true
                                speedZone = 0
                                while (true) {
                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                    change.consume()
                                    if (!change.pressed) break
                                    val dy = change.position.y - down.position.y
                                    val zone = when {
                                        dy < -zoneDistance -> 1
                                        dy > zoneDistance -> -1
                                        else -> 0
                                    }
                                    if (zone != speedZone) {
                                        speedZone = zone
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                }
                                speedLocked = when (speedZone) {
                                    1 -> true
                                    -1 -> false
                                    else -> speedLocked
                                }
                                boosting = false
                                speedZone = 0
                                player?.applySpeed(if (speedLocked) 2f else 1f)
                            }
                        }
                    }
                    .padding(start = 7.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(32.dp).clip(CircleShape).clickable { toggle() }, contentAlignment = Alignment.Center) {
                    // 播放进度画在按钮外圈, 不单独占一条进度条
                    Canvas(Modifier.size(32.dp)) {
                        drawCircle(c.accentSoft, radius = size.minDimension / 2 - 2.dp.toPx())
                        if (progress > 0f) {
                            val stroke = 2.dp.toPx()
                            drawArc(
                                c.accent, -90f, 360f * progress, useCenter = false,
                                topLeft = Offset(stroke / 2, stroke / 2),
                                size = Size(size.width - stroke, size.height - stroke),
                                style = Stroke(stroke, cap = StrokeCap.Round),
                            )
                        }
                    }
                    Icon(
                        if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (playing) "暂停" else "播放语音",
                        tint = c.accentText, modifier = Modifier.size(19.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                val target = scrub
                val shown = when {
                    target != null -> (target * durationMs).toLong()
                    playing || position > 0 -> position
                    else -> durationMs
                }
                Text(
                    formatDuration(shown), fontSize = 13.sp,
                    color = if (target != null) c.accentText else c.ink3,
                    style = TextStyle(fontFeatureSettings = "tnum"),
                )
                if (boosting || speedLocked) {
                    Spacer(Modifier.width(8.dp))
                    SpeedBadge(active = boosting, onClick = if (!boosting) ({
                        speedLocked = false
                        player?.applySpeed(1f)
                    }) else null)
                }
                Spacer(Modifier.weight(1f))
                when {
                    status == ClipStatus.TRANSCRIBING -> Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = c.ink3)
                        Spacer(Modifier.width(4.dp))
                        Text("识别中", fontSize = 12.sp, color = c.ink3)
                    }

                    // 原文: 只用图标表示, 展开时高亮
                    text.isNotEmpty() -> Box(
                        Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))
                            .background(if (showText) c.accentSoft else Color.Transparent)
                            .clickable { showText = !showText },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.Notes, if (showText) "收起原文" else "展开原文",
                            tint = if (showText) c.accentText else c.ink3, modifier = Modifier.size(18.dp)
                        )
                    }

                    else -> Spacer(Modifier.width(6.dp))
                }
                if (onRetry != null && status != ClipStatus.TRANSCRIBING && (status == ClipStatus.ERROR || text.isEmpty())) {
                    SmallIcon(Icons.Outlined.Refresh, "重新识别", onRetry)
                }
                if (onDelete != null) SmallIcon(Icons.Outlined.Close, "移除语音", onDelete)
            }
            if (boosting) {
                HorizontalDivider(color = c.line)
                Text(
                    when (speedZone) {
                        1 -> "松开固定 2x"
                        -1 -> "松开恢复 1x"
                        else -> if (speedLocked) "上滑保持 2x · 下滑恢复 1x" else "上滑固定 2x · 下滑取消"
                    },
                    fontSize = 12.sp, color = if (speedZone != 0) c.accentText else c.ink3,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                )
            } else if (status == ClipStatus.ERROR && error.isNotEmpty()) {
                HorizontalDivider(color = c.line)
                Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
                    // 提示保持安静: 不用红色, 重新识别按钮就在上面
                    Icon(Icons.Outlined.Info, null, tint = c.ink3, modifier = Modifier.size(14.dp).padding(top = 2.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(error, fontSize = 12.sp, color = c.ink3, lineHeight = 17.sp)
                }
            } else if (showText && text.isNotEmpty()) {
                HorizontalDivider(color = c.line)
                SelectionContainer {
                    Text(text, fontSize = 14.sp, color = c.ink2, lineHeight = 22.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp))
                }
            }
        }
    }
}

/** 倍速标记: 按住时轻轻跳动; 固定 2x 时点击恢复 1x */
@Composable
private fun SpeedBadge(active: Boolean, onClick: (() -> Unit)?) {
    val c = Neno.colors
    val pulse = rememberInfiniteTransition(label = "speed")
    val scale by pulse.animateFloat(1f, if (active) 1.12f else 1f, infiniteRepeatable(tween(420), RepeatMode.Reverse), label = "scale")
    Row(
        Modifier.scale(scale).clip(RoundedCornerShape(6.dp)).background(c.accentSoft)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.FastForward, null, tint = c.accentText, modifier = Modifier.size(13.dp))
        Text("2x", fontSize = 12.sp, color = c.accentText, modifier = Modifier.padding(start = 1.dp))
    }
}

@Composable
private fun SmallIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, label, tint = Neno.colors.ink3, modifier = Modifier.size(18.dp)) }
}
