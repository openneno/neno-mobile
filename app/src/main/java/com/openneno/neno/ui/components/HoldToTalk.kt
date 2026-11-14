package com.openneno.neno.ui.components

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openneno.neno.ui.screens.saveVoiceNote
import com.openneno.neno.ui.theme.Neno
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class FabState { IDLE, HOLDING, SAVING, SAVED }

/**
 * 首页右下角的加号: 点击写笔记; 按住说话, 松开保存为语音笔记, 上滑取消.
 * 放在页面最上层并铺满(只有加号本身响应触摸), 按住时显示遮罩和录音气泡.
 */
@Composable
fun BoxScope.HoldToTalkFab(onClick: () -> Unit) {
    val c = Neno.colors
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val cancelDistance = with(LocalDensity.current) { 90.dp.toPx() }

    var state by remember { mutableStateOf(FabState.IDLE) }
    var cancelling by remember { mutableStateOf(false) }

    fun save(recording: Recording?) {
        if (recording == null) {
            state = FabState.IDLE; return
        }
        state = FabState.SAVING
        saveVoiceNote(context, recording) { ok ->
            if (ok) {
                state = FabState.SAVED
                scope.launch { delay(900); if (state == FabState.SAVED) state = FabState.IDLE }
            } else state = FabState.IDLE
        }
    }

    val capture = rememberVoiceCapture(
        // 达到最长时长: 手指还按着也直接保存
        onLimit = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); save(it.finish()) },
        startAfterGrant = false,
    )
    val holding = state == FabState.HOLDING

    // ---------- 遮罩与录音气泡 ----------
    AnimatedVisibility(holding, enter = fadeIn(tween(180)), exit = fadeOut(tween(200))) {
        Box(Modifier.fillMaxSize().background(c.canvas.copy(alpha = 0.72f)))
    }
    AnimatedVisibility(
        holding || state == FabState.SAVING,
        modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 20.dp, bottom = 108.dp),
        enter = fadeIn(tween(160)) + scaleIn(spring(0.6f, Spring.StiffnessMediumLow), initialScale = 0.4f, transformOrigin = TransformOrigin(1f, 1f)),
        exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.6f, transformOrigin = TransformOrigin(1f, 1f)),
    ) {
        Column(horizontalAlignment = Alignment.End) {
            Row(
                Modifier.shadow(10.dp, RoundedCornerShape(20.dp), ambientColor = c.ink.copy(alpha = 0.2f))
                    .background(c.surface, RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state == FabState.SAVING) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = c.accent)
                    Spacer(Modifier.width(10.dp))
                    Text("正在转成文字...", fontSize = 14.sp, color = c.ink2)
                } else {
                    RecordingDot()
                    Spacer(Modifier.width(10.dp))
                    Text(formatDuration(capture.elapsedMs), fontSize = 15.sp, color = c.ink, style = TextStyle(fontFeatureSettings = "tnum"))
                    Spacer(Modifier.width(12.dp))
                    Waveform(capture.levels.takeLast(20), Modifier.height(24.dp), maxHeight = 20.dp, barWidth = 2.5.dp)
                }
            }
            if (holding) {
                Spacer(Modifier.height(8.dp))
                AnimatedContent(cancelling, transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(120)) }, label = "hint") { cancel ->
                    Text(
                        if (cancel) "松开取消" else "松开保存 · 上滑取消",
                        fontSize = 12.sp, color = if (cancel) c.ink else c.ink3,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }
        }
    }

    // ---------- 加号 ----------
    val level = capture.levels.lastOrNull() ?: 0f
    val scale by animateFloatAsState(
        when {
            holding && cancelling -> 0.92f
            holding -> 1.22f
            state == FabState.SAVED -> 1.08f
            else -> 1f
        },
        spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium), label = "scale",
    )
    val glow by animateFloatAsState(if (holding && !cancelling) level else 0f, spring(stiffness = Spring.StiffnessMediumLow), label = "glow")
    val color by animateColorAsState(if (holding && cancelling) c.ink3 else c.accent, tween(160), label = "color")
    val ripple = rememberInfiniteTransition(label = "ripple")
    val wave by ripple.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "wave")
    val onAccent = if (c.dark) Color(0xFF0F1013) else Color.White

    Box(
        Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp).size(56.dp)
            .drawBehind {
                if (holding && !cancelling) {
                    val r = size.minDimension / 2
                    // 两圈向外扩散的波纹, 再加一层随音量起伏的光晕
                    for (i in 0..1) {
                        val t = (wave + i * 0.5f) % 1f
                        drawCircle(c.accent.copy(alpha = 0.28f * (1 - t)), radius = r * (1.1f + t * 1.1f), style = Stroke(2.dp.toPx()))
                    }
                    drawCircle(c.accent.copy(alpha = 0.18f), radius = r * (1.15f + glow * 0.75f), center = Offset(size.width / 2, size.height / 2))
                }
            }
            .scale(scale)
            .shadow(if (holding) 12.dp else 6.dp, RoundedCornerShape(if (holding) 28.dp else 18.dp))
            .background(color, RoundedCornerShape(if (holding) 28.dp else 18.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    // 在长按时间内松开就是点击
                    var released = false
                    val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        waitForUpOrCancellation().also { released = true }
                    }
                    if (released) {
                        if (up != null) onClick()
                        return@awaitEachGesture
                    }
                    if (state == FabState.SAVING) return@awaitEachGesture
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (!capture.hasPermission()) {
                        Toast.makeText(context, "允许使用麦克风后, 再按住加号说话", Toast.LENGTH_SHORT).show()
                        capture.requestPermissionOnly()
                        return@awaitEachGesture
                    }
                    capture.startNow()
                    if (!capture.recording) return@awaitEachGesture
                    state = FabState.HOLDING
                    cancelling = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val nowCancelling = down.position.y - change.position.y > cancelDistance
                        if (nowCancelling != cancelling) {
                            cancelling = nowCancelling
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        change.consume()
                        if (!change.pressed) break
                    }
                    // 达到最长时长时已经自动保存
                    if (state != FabState.HOLDING) return@awaitEachGesture
                    if (cancelling) {
                        capture.cancel()
                        state = FabState.IDLE
                    } else {
                        scope.launch { save(capture.finish()) }
                    }
                    cancelling = false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val icon = when {
            holding && cancelling -> Icons.Outlined.Close
            holding -> Icons.Outlined.Mic
            state == FabState.SAVED -> Icons.Outlined.Check
            else -> Icons.Outlined.Add
        }
        AnimatedContent(
            icon,
            transitionSpec = {
                (fadeIn(tween(140)) + scaleIn(spring(0.5f), initialScale = 0.5f)) togetherWith fadeOut(tween(100))
            },
            label = "icon",
        ) { Icon(it, if (it == Icons.Outlined.Add) "记录 (按住说话)" else null, tint = onAccent, modifier = Modifier.graphicsLayer { }) }
    }
}
