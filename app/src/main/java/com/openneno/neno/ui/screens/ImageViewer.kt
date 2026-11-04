package com.openneno.neno.ui.screens

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import coil3.compose.AsyncImage
import com.openneno.neno.data.NoteImage
import com.openneno.neno.ui.components.repository
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 图片查看: 左右滑动切换, 双指或双击缩放, 放大后拖动查看细节, 下滑关闭, 单击显示/隐藏按钮.
 */
@Composable
fun ImageViewer(images: List<NoteImage>, index: Int, onBack: () -> Unit) {
    val repo = repository()
    val pager = rememberPagerState(initialPage = index.coerceIn(0, (images.size - 1).coerceAtLeast(0))) { images.size }
    var chrome by remember { mutableStateOf(true) }
    // 下滑关闭的进度 0..1, 用于背景渐隐
    var dismissProgress by remember { mutableFloatStateOf(0f) }

    BackHandler(onBack = onBack)
    DarkSystemBars()

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 1f - dismissProgress * 0.85f))) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            pageSpacing = 16.dp,
        ) { page ->
            ZoomableImage(
                model = repo.picFile(images[page]),
                active = page == pager.currentPage,
                onTap = { chrome = !chrome },
                // 放大时图片自己消费拖动, 拖到边缘后不再消费, 交给翻页
                onDismissProgress = { dismissProgress = it },
                onDismiss = onBack,
            )
        }

        AnimatedVisibility(chrome && dismissProgress == 0f, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent))
                    ).statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack, colors = IconButtonDefaults.iconButtonColors(containerColor = Color.White.copy(alpha = 0.12f))) {
                        Icon(Icons.Outlined.Close, "关闭", tint = Color.White)
                    }
                    Spacer(Modifier.weight(1f))
                    if (images.size > 1) {
                        Text(
                            "${pager.currentPage + 1} / ${images.size}", color = Color.White, fontSize = 15.sp,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.12f)).padding(horizontal = 12.dp, vertical = 5.dp)
                        )
                        Spacer(Modifier.size(8.dp))
                    }
                }
                if (images.size in 2..12) {
                    Row(
                        Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        repeat(images.size) { i ->
                            val selected = i == pager.currentPage
                            val dot by animateFloatAsState(if (selected) 1f else 0.4f, label = "dot")
                            Box(Modifier.size(if (selected) 7.dp else 6.dp).clip(CircleShape).background(Color.White.copy(alpha = dot)))
                        }
                    }
                }
            }
        }
    }
}

/** 查看图片时状态栏和导航栏使用浅色图标, 离开时恢复 */
@Composable
private fun DarkSystemBars() {
    val view = LocalView.current
    val activity = LocalContext.current as? Activity ?: return
    DisposableEffect(Unit) {
        val controller = WindowCompat.getInsetsController(activity.window, view)
        val status = controller.isAppearanceLightStatusBars
        val nav = controller.isAppearanceLightNavigationBars
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        onDispose {
            controller.isAppearanceLightStatusBars = status
            controller.isAppearanceLightNavigationBars = nav
        }
    }
}

private const val MAX_SCALE = 5f
private const val DOUBLE_TAP_SCALE = 2.5f

@Composable
private fun ZoomableImage(
    model: Any,
    active: Boolean,
    onTap: () -> Unit,
    onDismissProgress: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val scale = remember { Animatable(1f) }
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    val dismissY = remember { Animatable(0f) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val dismissDistance = with(LocalDensity.current) { 140.dp.toPx() }

    // 切换到其他图片时还原
    LaunchedEffect(active) {
        if (!active) {
            scale.snapTo(1f); offsetX.snapTo(0f); offsetY.snapTo(0f)
        }
    }

    fun bounds(s: Float) = Offset(size.width * (s - 1) / 2f, size.height * (s - 1) / 2f)

    AsyncImage(
        model = model,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tap ->
                        scope.launch {
                            if (scale.value > 1.01f) {
                                launch { scale.animateTo(1f, tween(220)) }
                                launch { offsetX.animateTo(0f, tween(220)) }
                                offsetY.animateTo(0f, tween(220))
                            } else {
                                // 以点击位置为中心放大
                                val b = bounds(DOUBLE_TAP_SCALE)
                                val tx = ((size.width / 2f - tap.x) * (DOUBLE_TAP_SCALE - 1)).coerceIn(-b.x, b.x)
                                val ty = ((size.height / 2f - tap.y) * (DOUBLE_TAP_SCALE - 1)).coerceIn(-b.y, b.y)
                                launch { scale.animateTo(DOUBLE_TAP_SCALE, tween(240)) }
                                launch { offsetX.animateTo(tx, tween(240)) }
                                offsetY.animateTo(ty, tween(240))
                            }
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var dismissing = false
                    var decided = false
                    do {
                        val event = awaitPointerEvent()
                        val pointers = event.changes.count { it.pressed }
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        if (pointers > 1 || scale.value > 1.01f) {
                            // 缩放和放大后的拖动
                            val newScale = (scale.value * zoom).coerceIn(1f, MAX_SCALE)
                            val b = bounds(newScale)
                            val nx = (offsetX.value + pan.x).coerceIn(-b.x, b.x)
                            val ny = (offsetY.value + pan.y).coerceIn(-b.y, b.y)
                            val atEdge = pointers == 1 && nx == offsetX.value && abs(pan.x) > abs(pan.y)
                            scope.launch {
                                scale.snapTo(newScale); offsetX.snapTo(nx); offsetY.snapTo(ny)
                            }
                            // 已经拖到边缘: 不消费事件, 让翻页接管
                            if (!atEdge) event.changes.forEach { if (it.positionChanged()) it.consume() }

                        } else if (pointers == 1) {
                            // 未放大时竖直下滑关闭, 水平滑动交给翻页
                            if (!decided && (abs(pan.x) > 0.5f || abs(pan.y) > 0.5f)) {
                                decided = true
                                dismissing = pan.y > 0 && abs(pan.y) > abs(pan.x) * 1.2f
                            }
                            if (dismissing) {
                                val y = (dismissY.value + pan.y).coerceAtLeast(0f)
                                scope.launch { dismissY.snapTo(y) }
                                onDismissProgress((y / (dismissDistance * 2)).coerceIn(0f, 1f))
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    if (dismissing) {
                        if (dismissY.value > dismissDistance) onDismiss()
                        else scope.launch {
                            dismissY.animateTo(0f, tween(200)); onDismissProgress(0f)
                        }
                    }
                }
            }
            .graphicsLayer {
                val shrink = 1f - (dismissY.value / (dismissDistance * 6)).coerceIn(0f, 0.25f)
                scaleX = scale.value * shrink
                scaleY = scale.value * shrink
                translationX = offsetX.value
                translationY = offsetY.value + dismissY.value
            },
    )
}
