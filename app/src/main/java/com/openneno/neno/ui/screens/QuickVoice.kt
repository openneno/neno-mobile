package com.openneno.neno.ui.screens

import android.text.TextUtils
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openneno.neno.app
import com.openneno.neno.data.NoteRepository
import com.openneno.neno.ui.components.Recording
import com.openneno.neno.ui.components.RecordingDot
import com.openneno.neno.ui.components.Waveform
import com.openneno.neno.ui.components.formatDuration
import com.openneno.neno.ui.components.rememberVoiceCapture
import com.openneno.neno.ui.theme.Neno
import com.openneno.neno.voice.AsrClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 保存不随界面关闭而取消 */
private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

/**
 * 把一段录音识别为文字, 保存为一条新笔记(正文是识别出的文字, 附带原始录音), 结束后在主线程调用 onDone(是否转成了文字).
 * 快速语音记录面板和按住加号说话共用. 保存不随界面关闭而取消.
 */
fun saveVoiceNote(context: android.content.Context, recording: Recording, onDone: (Boolean) -> Unit = {}) {
    val app = context.app
    saveScope.launch {
        val apiKey = app.settings.asrApiKey.value
        var text = ""
        var problem: String? = null
        if (apiKey.isBlank()) problem = "未设置 DeepSeek API Key"
        else try {
            text = AsrClient.transcribe(recording.file, apiKey)
            if (text.isEmpty()) problem = "没有识别到文字"
        } catch (e: Exception) {
            problem = e.message ?: "识别失败"
        }
        try {
            val audio = app.repository.importRecording(recording.file, recording.durationMs, text)
            val html = text.lines().filter { it.isNotBlank() }.joinToString("") { "<p>${TextUtils.htmlEncode(it)}</p>" }
            app.repository.save(
                id = "", parentId = "", content = html, pureContent = text,
                tags = NoteRepository.extractTags(text), images = emptyList(), audios = listOf(audio),
            )
            Toast.makeText(context, if (problem == null) "已保存" else "已保存录音, 未转成文字: $problem", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
        onDone(problem == null)
    }
}

/**
 * 快速语音记录: 打开即开始录音, 说完点完成, 识别后直接保存为一条笔记(正文是识别出的文字, 附带原始录音).
 * 入口: 首页输入框的麦克风, 桌面图标长按菜单, 快速记录桌面组件.
 */
@Composable
fun QuickVoiceSheet(onClose: () -> Unit) {
    val c = Neno.colors
    val context = LocalContext.current
    val app = context.app
    var saving by remember { mutableStateOf(false) }

    fun save(recording: Recording?) {
        if (recording == null) {
            // 太短(误触), 不保存
            onClose(); return
        }
        saveVoiceNote(context, recording) { onClose() }
    }

    lateinit var finish: () -> Unit
    val capture = rememberVoiceCapture(onLimit = { finish() }, onUnavailable = onClose)
    finish = {
        if (!saving) {
            saving = true
            saveScope.launch { save(capture.finish()) }
        }
    }
    LaunchedEffect(Unit) { capture.start() }
    BackHandler { if (!saving) { capture.cancel(); onClose() } }

    Box(Modifier.fillMaxSize()) {
        // 遮罩: 点击空白处取消(保存中不响应)
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (c.dark) 0.5f else 0.28f))
                .clickable(remember { MutableInteractionSource() }, null) { if (!saving) { capture.cancel(); onClose() } }
        )
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(c.surface)
                .clickable(remember { MutableInteractionSource() }, null) {}
                .navigationBarsPadding().padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.width(36.dp).height(4.dp).clip(CircleShape).background(c.surface3))
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (saving) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = c.ink3)
                    Spacer(Modifier.width(8.dp))
                    Text("正在转成文字...", fontSize = 14.sp, color = c.ink2)
                } else {
                    RecordingDot()
                    Spacer(Modifier.width(8.dp))
                    Text(if (capture.recording) "正在聆听" else "准备录音", fontSize = 14.sp, color = c.ink2)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                formatDuration(capture.elapsedMs), fontSize = 32.sp, color = c.ink,
                style = TextStyle(fontFeatureSettings = "tnum"),
            )
            Spacer(Modifier.height(16.dp))
            Waveform(capture.levels, Modifier.height(44.dp), maxHeight = 36.dp, barWidth = 3.dp)
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "取消", fontSize = 15.sp, color = if (saving) c.ink3.copy(alpha = 0.5f) else c.ink2,
                    modifier = Modifier.width(72.dp).clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = !saving) { capture.cancel(); onClose() }.padding(vertical = 10.dp),
                    textAlign = TextAlign.Center,
                )
                Box(
                    Modifier.size(64.dp).clip(CircleShape).background(c.accent)
                        .clickable(enabled = capture.recording && !saving) { finish() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.Check, "完成", tint = if (c.dark) Color(0xFF0F1013) else Color.White, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.width(72.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text("说完点 ✓, 自动保存为一条笔记", fontSize = 12.sp, color = c.ink3)
        }
    }
}
