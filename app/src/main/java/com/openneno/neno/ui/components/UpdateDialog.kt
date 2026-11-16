package com.openneno.neno.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openneno.neno.BuildConfig
import com.openneno.neno.app
import com.openneno.neno.update.AppRelease
import com.openneno.neno.update.UpdateState
import com.openneno.neno.update.Updater
import com.openneno.neno.ui.theme.Neno

/** 发现新版本时的弹窗: 更新内容, 下载进度, 安装 */
@Composable
fun UpdateDialog() {
    val prompt by Updater.prompt.collectAsStateWithLifecycle()
    val state by Updater.state.collectAsStateWithLifecycle()
    val release = when (val s = state) {
        is UpdateState.Available -> s.release
        is UpdateState.Downloading -> s.release
        is UpdateState.Ready -> s.release
        is UpdateState.Failed -> s.release
        else -> null
    }
    if (!prompt || release == null) return

    val c = Neno.colors
    val context = LocalContext.current
    // 去系统设置允许"安装未知应用"后回到这里, 自动继续安装
    var waitingPermission by remember { mutableStateOf(false) }
    LifecycleResumeEffect(waitingPermission) {
        val s = Updater.state.value
        if (waitingPermission && s is UpdateState.Ready && context.packageManager.canRequestPackageInstalls()) {
            waitingPermission = false
            Updater.install(context, s.file)
        }
        onPauseOrDispose {}
    }

    val downloading = state is UpdateState.Downloading
    val close = {
        if (downloading) Updater.cancelDownload()
        Updater.dismissPrompt()
    }

    AlertDialog(
        onDismissRequest = { if (!downloading) close() },
        properties = DialogProperties(dismissOnClickOutside = !downloading),
        containerColor = c.surface,
        title = { Text("发现新版本 ${release.version}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("当前版本 ${BuildConfig.VERSION_NAME} · 安装包 ${formatSize(release.apkSize)}", fontSize = 13.sp, color = c.ink3)
                if (release.notes.isNotBlank()) {
                    Text(
                        release.notes,
                        Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(8.dp)).background(c.surface2)
                            .verticalScroll(rememberScrollState()).padding(10.dp),
                        fontSize = 13.sp, color = c.ink2, lineHeight = 20.sp,
                    )
                }
                when (val s = state) {
                    is UpdateState.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LinearProgressIndicator(
                            progress = { s.progress }, Modifier.fillMaxWidth(),
                            color = c.accent, trackColor = c.surface3,
                        )
                        Text("正在下载 ${formatSize(s.downloaded)} / ${formatSize(s.total)}", fontSize = 12.sp, color = c.ink3)
                    }
                    is UpdateState.Ready -> Text(
                        if (waitingPermission) "请在系统设置中允许 NENO 安装应用, 返回后会继续安装" else "安装包已下载并校验, 点击安装",
                        fontSize = 13.sp, color = c.ink2
                    )
                    is UpdateState.Failed -> Text(s.message, fontSize = 13.sp, color = c.danger)
                    else -> {}
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (val s = state) {
                    is UpdateState.Downloading -> TextButton(onClick = { Updater.cancelDownload() }) { Text("取消下载", color = c.ink2) }
                    is UpdateState.Ready -> TextButton(onClick = {
                        waitingPermission = !Updater.install(context, s.file)
                    }) { Text("安装", color = c.accent, fontWeight = FontWeight.SemiBold) }
                    else -> TextButton(onClick = { Updater.download(context, release) }) {
                        Text(if (state is UpdateState.Failed) "重试" else "立即更新", color = c.accent, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        },
        dismissButton = {
            if (!downloading) Row {
                TextButton(onClick = {
                    context.app.settings.ignoredUpdateVersion = release.version
                    close()
                }) { Text("忽略此版本", color = c.ink3) }
                TextButton(onClick = close) { Text("以后再说", color = c.ink2) }
            }
        },
    )
}

/** 设置页面"关于"中的检查更新入口 */
@Composable
fun UpdateStatusText(onOpen: (AppRelease) -> Unit) {
    val c = Neno.colors
    val state by Updater.state.collectAsStateWithLifecycle()
    when (val s = state) {
        UpdateState.Checking -> Text("正在检查更新…", fontSize = 13.sp, color = c.ink3)
        UpdateState.UpToDate -> Text("已是最新版本", fontSize = 13.sp, color = c.ink3)
        is UpdateState.Available -> TextButton(onClick = { onOpen(s.release) }) { Text("新版本 ${s.release.version} 可以更新", color = c.accent) }
        is UpdateState.Downloading -> TextButton(onClick = { onOpen(s.release) }) { Text("正在下载 ${s.release.version} · ${(s.progress * 100).toInt()}%", color = c.accent) }
        is UpdateState.Ready -> TextButton(onClick = { onOpen(s.release) }) { Text("${s.release.version} 已下载, 点击安装", color = c.accent) }
        is UpdateState.Failed -> Text(s.message, fontSize = 13.sp, color = c.danger)
        UpdateState.Idle -> {}
    }
}

fun formatSize(bytes: Long): String = when {
    bytes < 0 -> "-"
    bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}
