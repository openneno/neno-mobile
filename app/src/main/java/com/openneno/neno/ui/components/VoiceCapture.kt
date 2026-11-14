package com.openneno.neno.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.openneno.neno.ui.theme.Neno
import com.openneno.neno.voice.VoiceRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.withResumed
import java.io.File

/** 一次录音的结果: 临时 WAV 文件和时长 */
data class Recording(val file: File, val durationMs: Long)

/**
 * 录音状态, 编辑器和快速语音记录共用: 申请麦克风权限, 开始/结束/取消录音, 计时和音量.
 */
@Stable
class VoiceCapture internal constructor(
    private val scope: CoroutineScope,
    private val newFile: () -> File,
    private val onError: (String) -> Unit,
) {
    var recorder by mutableStateOf<VoiceRecorder?>(null)
        private set
    var elapsedMs by mutableLongStateOf(0L)
        internal set
    val levels = mutableStateListOf<Float>().apply { repeat(LEVEL_COUNT) { add(0f) } }
    val recording: Boolean get() = recorder != null

    /** 达到最长时长时自动结束, 由使用方处理结果 */
    internal var onLimit: (() -> Unit)? = null
    internal var requestPermission: (() -> Unit)? = null
    internal var checkPermission: () -> Boolean = { true }
    internal var launchPermission: () -> Unit = {}

    /** 是否已经有麦克风权限 */
    fun hasPermission(): Boolean = checkPermission()

    /** 只申请权限, 授权后不自动开始(用于按住说话: 弹出授权框时手指已经离开) */
    fun requestPermissionOnly() = launchPermission()

    fun startNow() {
        if (recorder != null) return
        // 录音时停止正在播放的语音
        AudioPlayback.stopAll()
        val r = VoiceRecorder(newFile())
        try {
            r.start(
                scope,
                onLevel = { level -> scope.launch { levels.removeAt(0); levels.add(level) } },
                onLimit = { scope.launch { onLimit?.invoke() } },
            )
            elapsedMs = 0
            recorder = r
        } catch (e: Exception) {
            r.cancel()
            onError("无法录音: ${e.message}")
        }
    }

    /** 开始录音, 没有权限时先申请 */
    fun start() {
        val request = requestPermission
        if (request != null) request() else startNow()
    }

    /** 结束录音. 太短(误触)时丢弃并返回 null */
    suspend fun finish(): Recording? {
        val r = recorder ?: return null
        recorder = null
        val duration = r.stop()
        reset()
        if (duration < 500) {
            r.file.delete(); return null
        }
        return Recording(r.file, duration)
    }

    fun cancel() {
        recorder?.cancel()
        recorder = null
        reset()
    }

    private fun reset() {
        for (i in levels.indices) levels[i] = 0f
    }

    companion object {
        const val LEVEL_COUNT = 32
    }
}

@Composable
fun rememberVoiceCapture(
    onLimit: suspend (VoiceCapture) -> Unit,
    onUnavailable: () -> Unit = {},
    startAfterGrant: Boolean = true,
): VoiceCapture {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = repository()
    val capture = remember {
        VoiceCapture(scope, { repo.newRecordingFile() }, { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); onUnavailable() })
    }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // 授权回调可能早于页面回到前台, 这时系统给出的是没有声音、也不按实时节奏的数据, 等回到前台再开始录音
        if (granted) {
            if (startAfterGrant) scope.launch { lifecycle.withResumed { capture.startNow() } }
        }
        else {
            Toast.makeText(context, "需要麦克风权限才能语音输入", Toast.LENGTH_LONG).show()
            onUnavailable()
        }
    }
    capture.onLimit = { scope.launch { onLimit(capture) } }
    capture.checkPermission = {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
    capture.launchPermission = { permission.launch(Manifest.permission.RECORD_AUDIO) }
    capture.requestPermission = {
        if (capture.hasPermission()) capture.startNow() else capture.launchPermission()
    }
    val recorder = capture.recorder
    LaunchedEffect(recorder) {
        while (recorder != null && capture.recorder === recorder) {
            capture.elapsedMs = recorder.durationMs
            delay(200)
        }
    }
    DisposableEffect(Unit) { onDispose { capture.cancel() } }
    return capture
}

/** 正在录音的小圆点, 缓慢呼吸 */
@Composable
fun RecordingDot(size: Dp = 8.dp) {
    val transition = rememberInfiniteTransition(label = "dot")
    val alpha by transition.animateFloat(1f, 0.35f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "alpha")
    Box(Modifier.size(size).alpha(alpha).clip(CircleShape).background(Neno.colors.accent))
}

/** 音量波形: 最近的音量从左到右排列 */
@Composable
fun Waveform(levels: List<Float>, modifier: Modifier = Modifier, maxHeight: Dp = 18.dp, barWidth: Dp = 2.dp) {
    val color = Neno.colors.accent.copy(alpha = 0.6f)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        levels.forEach { level ->
            Box(Modifier.width(barWidth).height(3.dp + maxHeight * level).clip(CircleShape).background(color))
        }
    }
}

/**
 * 编辑器中录音时替换工具栏的一条状态栏, 高度与工具栏相同, 只使用主题色.
 */
@Composable
fun RecordingStrip(capture: VoiceCapture, onCancel: () -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val c = Neno.colors
    val remaining = VoiceRecorder.MAX_DURATION_MS - capture.elapsedMs
    Row(modifier.fillMaxWidth().height(50.dp).padding(start = 18.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        RecordingDot()
        Spacer(Modifier.width(10.dp))
        Text(formatDuration(capture.elapsedMs), fontSize = 14.sp, color = c.ink2, style = TextStyle(fontFeatureSettings = "tnum"))
        Spacer(Modifier.width(12.dp))
        Waveform(capture.levels.takeLast(24), Modifier.weight(1f))
        if (remaining < 20_000) {
            Text("剩余 ${formatDuration(remaining + 999)}", fontSize = 12.sp, color = c.ink3, modifier = Modifier.padding(end = 4.dp))
        }
        Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onCancel), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Close, "取消录音", tint = c.ink3, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(2.dp))
        Box(Modifier.size(40.dp).clip(CircleShape).background(c.accentSoft).clickable(onClick = onDone), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Check, "完成录音", tint = c.accentText, modifier = Modifier.size(20.dp))
        }
    }
}
