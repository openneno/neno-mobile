package com.openneno.neno.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.openneno.neno.BuildConfig
import com.openneno.neno.app
import com.openneno.neno.data.GithubConfig
import com.openneno.neno.data.NotionConfig
import com.openneno.neno.data.ThemeColor
import com.openneno.neno.data.ThemeMode
import com.openneno.neno.sync.AccountApi
import com.openneno.neno.sync.GithubAccount
import com.openneno.neno.sync.GithubRepo
import com.openneno.neno.ui.components.ConfirmDialog
import com.openneno.neno.ui.components.NenoCard
import com.openneno.neno.ui.components.copyText
import com.openneno.neno.ui.components.repository
import com.openneno.neno.ui.theme.Neno
import com.openneno.neno.ui.theme.PALETTES
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(openDrawer: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopBar("设置", navIcon = Icons.Outlined.Menu, onNav = openDrawer)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp).navigationBarsPadding().padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Section("外观", "选择浅色, 深色或跟随系统, 以及喜欢的主题色。") { AppearanceSettings() }
            Section("同步", "笔记保存在你自己的 GitHub 私有仓库中, 无需服务器, 也可以完全离线使用。") { SyncSettings() }
            val github by LocalContext.current.app.settings.github.collectAsStateWithLifecycle()
            if (github.configured) Section(
                "同步到 Notion",
                "由仓库中的 GitHub Action 完成: 每次保存时增量同步。受 Notion API 限制, 只单向同步新增的笔记(不含图片)。"
            ) { NotionSettings() }
            Section("数据", "把所有笔记和图片导出为一个备份文件, 或者从备份文件恢复。备份格式与网页端相同。") { DataSettings() }
            Section("关于", "") { About() }
        }
    }
}

@Composable
private fun Section(title: String, desc: String, content: @Composable ColumnScope.() -> Unit) {
    val c = Neno.colors
    NenoCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (desc.isNotEmpty()) Text(desc, fontSize = 13.sp, color = c.ink3, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun primaryColors() = ButtonDefaults.buttonColors(
    containerColor = Neno.colors.accent,
    contentColor = if (Neno.colors.dark) Color(0xFF0F1013) else Color.White,
)

// ---------- 外观 ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearanceSettings() {
    val c = Neno.colors
    val settings = LocalContext.current.app.settings
    val appearance by settings.appearance.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.surface2).padding(3.dp)) {
        ThemeMode.entries.forEach { mode ->
            val selected = appearance.mode == mode
            Box(
                Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (selected) c.surface else Color.Transparent)
                    .clickable { settings.setAppearance(appearance.copy(mode = mode)) },
                contentAlignment = Alignment.Center
            ) { Text(mode.label, fontSize = 14.sp, color = if (selected) c.ink else c.ink2, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal) }
        }
    }
    Spacer(Modifier.height(14.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ThemeColor.entries.forEach { color ->
            val selected = appearance.color == color
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { settings.setAppearance(appearance.copy(color = color)) }) {
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(Color(PALETTES.getValue(color)[5]))
                        .then(if (selected) Modifier.border(3.dp, c.surface, CircleShape) else Modifier),
                    contentAlignment = Alignment.Center
                ) { if (selected) Icon(Icons.Outlined.Check, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
                Text(color.label, fontSize = 11.sp, color = if (selected) c.ink else c.ink3, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

// ---------- 同步 ----------

@Composable
private fun SyncSettings() {
    val app = LocalContext.current.app
    val github by app.settings.github.collectAsStateWithLifecycle()
    var reconfigureRepo by remember { mutableStateOf("") }
    if (github.configured) SyncStatusPanel(github, onReconfigure = { reconfigureRepo = it })
    else SyncSetupPanel(preferredRepo = reconfigureRepo)
}

/** 保存配置并开始同步 */
private fun applyConfig(app: com.openneno.neno.NenoApp, config: GithubConfig) {
    val old = app.settings.github.value
    if (old.fullName != config.fullName) app.sync.reset()
    app.settings.setGithub(config.copy(lastCommitSha = ""))
    app.sync.clearAuthError()
    app.sync.launch { app.sync.syncNow() }
}

@Composable
private fun SyncSetupPanel(preferredRepo: String) {
    val c = Neno.colors
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var scopeWarning by remember { mutableStateOf("") }
    var account by remember { mutableStateOf<GithubAccount?>(null) }
    var repos by remember { mutableStateOf(emptyList<GithubRepo>()) }
    var createNew by remember { mutableStateOf(true) }
    var newName by remember { mutableStateOf(AccountApi.DEFAULT_REPO_NAME) }
    var selected by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf("") }
    var pendingImport by remember { mutableStateOf<GithubConfig?>(null) }

    fun verify() {
        if (token.isBlank() || checking) return
        checking = true; error = ""; scopeWarning = ""; account = null
        scope.launch {
            try {
                val acc = AccountApi.fetchAccount(token.trim())
                if (acc.scopes != null && "repo" !in acc.scopes) scopeWarning = "这个 Token 没有勾选 repo 权限, 将无法读写笔记仓库"
                repos = AccountApi.listRepos(token.trim())
                val existing = repos.firstOrNull { it.fullName == preferredRepo }
                    ?: repos.firstOrNull { it.repo == AccountApi.DEFAULT_REPO_NAME || it.repo == "neno" }
                if (existing != null) {
                    createNew = false; selected = existing.fullName
                } else {
                    createNew = true; selected = repos.firstOrNull()?.fullName ?: ""
                }
                account = acc
            } catch (e: Exception) {
                error = AccountApi.describeError(e)
            }
            checking = false
        }
    }

    fun finish() {
        saving = true; error = ""
        scope.launch {
            try {
                val target = if (createNew) {
                    val name = newName.trim()
                    if (!Regex("^[A-Za-z0-9._-]+$").matches(name)) throw IllegalArgumentException("仓库名只能包含字母, 数字, '.', '-' 和 '_'")
                    AccountApi.createRepo(token.trim(), name)
                } else repos.firstOrNull { it.fullName == selected } ?: throw IllegalArgumentException("请选择一个仓库")
                applyConfig(app, GithubConfig(token.trim(), target.owner, target.repo))
            } catch (e: IllegalArgumentException) {
                error = e.message ?: ""
            } catch (e: Exception) {
                error = AccountApi.describeError(e)
            }
            saving = false
        }
    }

    fun tryImport(text: String) {
        importError = ""
        try {
            pendingImport = AccountApi.decodeNenoToken(text)
        } catch (e: AccountApi.UnsupportedConfig) {
            importError = e.message ?: ""
        } catch (e: Exception) {
            importError = "无法识别, 请粘贴完整的 neno token 或同步链接"
        }
    }

    val scan = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { tryImport(it) }
    }

    Text("1. 生成一个 GitHub Token(已预选所需权限), 然后粘贴到下面", fontSize = 14.sp, color = c.ink2)
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AccountApi.TOKEN_URL))) }) { Text("生成 GitHub Token") }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = token, onValueChange = { token = it; account = null },
        label = { Text("GitHub Token") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { showToken = !showToken }) {
                Icon(if (showToken) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (showToken) "隐藏" else "显示")
            }
        },
    )
    Spacer(Modifier.height(8.dp))
    Button(onClick = { verify() }, enabled = token.isNotBlank() && !checking, colors = primaryColors()) {
        if (checking) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("验证 Token")
    }
    if (scopeWarning.isNotEmpty()) Notice(scopeWarning, warning = true)

    account?.let { acc ->
        Spacer(Modifier.height(16.dp))
        Text("已登录 ${acc.name} (@${acc.login})", fontSize = 14.sp, color = c.ink)
        Spacer(Modifier.height(10.dp))
        Text("2. 选择保存笔记的仓库", fontSize = 14.sp, color = c.ink2)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { createNew = true }) {
            RadioButton(createNew, { createNew = true }); Text("新建私有仓库")
        }
        if (createNew) OutlinedTextField(newName, { newName = it }, label = { Text("仓库名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (repos.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { createNew = false }) {
                RadioButton(!createNew, { createNew = false }); Text("使用已有仓库")
            }
            if (!createNew) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, c.line, RoundedCornerShape(10.dp))) {
                repos.take(30).forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().clickable { selected = r.fullName }
                            .background(if (selected == r.fullName) c.accentSoft else Color.Transparent).padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Text(r.fullName, fontSize = 14.sp, modifier = Modifier.weight(1f), color = if (selected == r.fullName) c.accentText else c.ink)
                        if (r.private) Text("私有", fontSize = 12.sp, color = c.ink3)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = { finish() }, enabled = !saving, colors = primaryColors(), modifier = Modifier.fillMaxWidth()) {
            if (saving) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("完成设置并开始同步")
        }
    }
    if (error.isNotEmpty()) Notice(error)

    Spacer(Modifier.height(20.dp))
    Text("已经在其他设备上设置过?", fontSize = 14.sp, fontWeight = FontWeight.Medium)
    Text("扫描网页端设置页中的二维码, 或粘贴 neno token / 同步链接。", fontSize = 13.sp, color = c.ink3)
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = {
        scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("扫描 NENO 同步二维码").setBeepEnabled(false).setOrientationLocked(false))
    }) {
        Icon(Icons.Outlined.QrCodeScanner, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("扫码导入")
    }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(importText, { importText = it }, label = { Text("neno token 或同步链接") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = { tryImport(importText) }, enabled = importText.isNotBlank()) { Text("导入配置") }
    if (importError.isNotEmpty()) Notice(importError)

    pendingImport?.let { config ->
        var verifying by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            containerColor = c.surface,
            title = { Text("导入同步配置?") },
            text = { Text("将使用 GitHub 仓库 ${config.fullName} 同步笔记。") },
            confirmButton = {
                TextButton(enabled = !verifying, onClick = {
                    verifying = true
                    scope.launch {
                        try {
                            AccountApi.fetchAccount(config.token)
                            applyConfig(app, config)
                            pendingImport = null
                        } catch (e: Exception) {
                            importError = AccountApi.describeError(e); pendingImport = null
                        }
                    }
                }) { Text(if (verifying) "验证中..." else "导入并同步") }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun Notice(text: String, warning: Boolean = false) {
    val color = if (warning) Color(0xFFB45309) else Neno.colors.danger
    Row(
        Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.1f)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.ErrorOutline, null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 13.sp, color = color)
    }
}

@Composable
private fun SyncStatusPanel(github: GithubConfig, onReconfigure: (String) -> Unit) {
    val c = Neno.colors
    val context = LocalContext.current
    val app = context.app
    val status by app.sync.status.collectAsStateWithLifecycle()
    var account by remember { mutableStateOf<GithubAccount?>(null) }
    var accountError by remember { mutableStateOf("") }
    var showQr by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(github.token) {
        try {
            account = AccountApi.fetchAccount(github.token)
        } catch (e: Exception) {
            accountError = AccountApi.describeError(e)
        }
    }

    if (status.authError || accountError.isNotEmpty()) {
        Notice(if (status.authError) "Token 已失效, 同步已暂停, 本地笔记不受影响" else accountError)
        if (status.authError) TextButton(onClick = {
            onReconfigure(github.fullName)
            app.settings.setGithub(github.copy(token = ""))
        }) { Text("重新填写 Token") }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(if (status.error.isEmpty()) Icons.Outlined.CloudDone else Icons.Outlined.ErrorOutline, null, tint = if (status.error.isEmpty()) c.accent else c.danger)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(github.fullName, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.clickable {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AccountApi.repoUrl(github.fullName))))
            })
            val sub = when {
                status.syncing -> "正在同步..."
                status.error.isNotEmpty() -> "同步失败: ${status.error}"
                status.lastSyncAt > 0 -> "上次同步 " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(status.lastSyncAt))
                else -> account?.let { "已登录 ${it.name}" } ?: ""
            }
            Text(sub, fontSize = 13.sp, color = if (status.error.isNotEmpty()) c.danger else c.ink3)
        }
        IconButton(onClick = { scope.launch { app.sync.syncNow() } }, enabled = !status.syncing) {
            if (status.syncing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Outlined.Sync, "立即同步", tint = c.ink2)
        }
    }
    Spacer(Modifier.height(14.dp))
    Text("在其他设备或插件中使用同一个仓库", fontSize = 14.sp, fontWeight = FontWeight.Medium)
    Text("二维码, 同步链接和 neno token 中包含你的 GitHub Token, 请不要分享给他人。", fontSize = 12.sp, color = c.ink3)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { showQr = true }) { Text("二维码") }
        OutlinedButton(onClick = { copyText(context, AccountApi.encodeNenoToken(github), "已复制 neno token") }) { Text("复制 token") }
        OutlinedButton(onClick = { copyText(context, AccountApi.buildSyncLink(github), "已复制同步链接") }) { Text("复制链接") }
    }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = { confirmLogout = true }) { Text("退出登录", color = c.danger) }

    if (showQr) {
        val bitmap = remember(github) { qrBitmap(AccountApi.buildSyncLink(github), 720) }
        AlertDialog(
            onDismissRequest = { showQr = false },
            containerColor = c.surface,
            title = { Text("扫码同步") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Image(bitmap.asImageBitmap(), "同步二维码", Modifier.size(240.dp).clip(RoundedCornerShape(8.dp)))
                    Spacer(Modifier.height(8.dp))
                    Text("在另一台设备的 NENO 中扫码, 或用相机扫码打开网页端。", fontSize = 13.sp, color = c.ink3)
                }
            },
            confirmButton = { TextButton(onClick = { showQr = false }) { Text("关闭") } },
        )
    }
    if (confirmLogout) {
        ConfirmDialog(
            title = "退出登录?", message = "本地笔记会保留, 但不再与 GitHub 仓库同步。之后可以随时重新登录。",
            confirmText = "退出登录", danger = true, onDismiss = { confirmLogout = false },
            onConfirm = {
                app.settings.setGithub(GithubConfig())
                app.settings.setNotion(NotionConfig())
                app.sync.reset()
            },
        )
    }
}

fun qrBitmap(text: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

// ---------- Notion ----------

@Composable
private fun NotionSettings() {
    val c = Neno.colors
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val saved by app.settings.notion.collectAsStateWithLifecycle()
    var enabled by remember(saved) { mutableStateOf(saved.enabled) }
    var token by remember(saved) { mutableStateOf(saved.token) }
    var db by remember(saved) { mutableStateOf(saved.databaseId) }
    var writing by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("同步到 Notion", Modifier.weight(1f), fontSize = 14.sp)
        Switch(enabled, {
            enabled = it; app.settings.setNotion(saved.copy(enabled = it))
        }, colors = SwitchDefaults.colors(checkedTrackColor = c.accent))
    }
    if (enabled) {
        OutlinedTextField(token, { token = it }, label = { Text("Internal Integration Token") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(db, { db = it }, label = { Text("Database ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { app.settings.setNotion(NotionConfig(true, token.trim(), db.trim())); Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show() }) { Text("保存") }
            Button(enabled = !writing && token.isNotBlank() && db.isNotBlank(), colors = primaryColors(), onClick = {
                writing = true
                app.settings.setNotion(NotionConfig(true, token.trim(), db.trim()))
                scope.launch {
                    try {
                        val github = app.settings.github.value
                        fun fill(name: String) = context.assets.open(name).bufferedReader().readText()
                            .replace("[GITHUB_TOKEN]", github.token)
                            .replace("[NOTION_TOKEN]", token.trim())
                            .replace("[NOTION_DATABASEID]", db.trim())
                        val files = withContext(Dispatchers.IO) {
                            listOf(
                                ".github/workflows/syncAllNenoToNotion.yml" to fill("syncAllNenoToNotion.yml"),
                                ".github/workflows/syncNenoSingleItem.yml" to fill("syncNenoSingleItem.yml"),
                            )
                        }
                        app.sync.commitTextFiles(files, "add notion sync actions")
                        Toast.makeText(context, "已写入 GitHub Action, 之后新增的笔记会自动同步到 Notion", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, "写入失败: ${AccountApi.describeError(e)}", Toast.LENGTH_LONG).show()
                    }
                    writing = false
                }
            }) { Text(if (writing) "写入中..." else "写入 GitHub Action") }
        }
    }
}

// ---------- 数据 ----------

@Composable
private fun DataSettings() {
    val context = LocalContext.current
    val repo = repository()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            try {
                val text = repo.exportBackup()
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) } }
                Toast.makeText(context, "已导出", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
            busy = false
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            try {
                val text = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) } }
                val n = repo.importBackup(text)
                Toast.makeText(context, "已导入 $n 条笔记", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "导入失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
            busy = false
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(enabled = !busy, onClick = { export.launch("nenodata.txt") }) { Text("导出备份") }
        OutlinedButton(enabled = !busy, onClick = { import.launch(arrayOf("text/plain", "application/json", "*/*")) }) { Text("从备份恢复") }
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun About() {
    val c = Neno.colors
    val context = LocalContext.current
    Text("NENO for Android ${BuildConfig.VERSION_NAME}", fontSize = 14.sp)
    Text("开源的卡片笔记应用, 与网页端共用同一个 GitHub 笔记仓库。", fontSize = 13.sp, color = c.ink3)
    Spacer(Modifier.height(6.dp))
    Row {
        TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/openneno/neno-mobile"))) }) { Text("GitHub") }
        TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AccountApi.WEB_URL))) }) { Text("网页版") }
    }
}
