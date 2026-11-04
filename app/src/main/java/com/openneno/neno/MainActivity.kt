package com.openneno.neno

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openneno.neno.ui.AppState
import com.openneno.neno.ui.NenoRoot
import com.openneno.neno.ui.Screen
import com.openneno.neno.ui.theme.NenoTheme
import com.openneno.neno.ui.theme.isDark
import com.openneno.neno.ui.theme.nenoColors

class MainActivity : ComponentActivity() {
    private val state: AppState by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val appearance by app.settings.appearance.collectAsStateWithLifecycle()
            val dark = isDark(appearance)
            LaunchedEffect(dark, appearance.color) {
                val canvas = nenoColors(dark, appearance.color).canvas.toArgb()
                val style = if (dark) SystemBarStyle.dark(canvas) else SystemBarStyle.light(canvas, canvas)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            NenoTheme(appearance) {
                NenoRoot(state, onExit = { finish() })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) app.sync.markActivity()
        return super.dispatchTouchEvent(ev)
    }

    /** 桌面组件, 分享菜单打开应用时跳转到对应页面 */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            val text = listOfNotNull(intent.getStringExtra(Intent.EXTRA_SUBJECT), intent.getStringExtra(Intent.EXTRA_TEXT))
                .distinct().joinToString("\n")
            if (text.isNotBlank()) state.open(Screen.Editor(initialText = text))
            return
        }
        when (intent.getStringExtra(EXTRA_ACTION)) {
            ACTION_COMPOSE -> state.open(Screen.Editor())
            ACTION_DAILY -> state.open(Screen.Daily)
            ACTION_WALK -> state.open(Screen.Walk)
            ACTION_DETAIL -> intent.getStringExtra(EXTRA_NOTE_ID)?.let {
                if (state.current !is Screen.Detail || (state.current as Screen.Detail).id != it) state.open(Screen.Detail(it))
            }

            ACTION_DAY -> intent.getStringExtra(EXTRA_DAY)?.let { state.showDay(it) }
        }
        intent.removeExtra(EXTRA_ACTION)
    }

    companion object {
        const val EXTRA_ACTION = "neno_action"
        const val EXTRA_NOTE_ID = "note_id"
        const val EXTRA_DAY = "day"
        const val ACTION_COMPOSE = "compose"
        const val ACTION_DAILY = "daily"
        const val ACTION_WALK = "walk"
        const val ACTION_DETAIL = "detail"
        const val ACTION_DAY = "day"

        fun intent(context: Context, action: String, noteId: String? = null, day: String? = null) =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_ACTION, action)
                noteId?.let { putExtra(EXTRA_NOTE_ID, it) }
                day?.let { putExtra(EXTRA_DAY, it) }
                // 不同的 extra 需要不同的 data, 否则 PendingIntent 会被复用
                data = android.net.Uri.parse("neno://$action/${noteId ?: day ?: ""}")
            }
    }
}
