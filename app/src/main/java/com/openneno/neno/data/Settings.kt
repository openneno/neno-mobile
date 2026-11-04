package com.openneno.neno.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GithubConfig(
    val token: String = "",
    val owner: String = "",
    val repo: String = "",
    val lastCommitSha: String = "",
) {
    val configured get() = token.isNotBlank() && owner.isNotBlank() && repo.isNotBlank()
    val fullName get() = "$owner/$repo"
}

data class NotionConfig(val enabled: Boolean = false, val token: String = "", val databaseId: String = "")

enum class ThemeMode(val label: String) { LIGHT("浅色"), DARK("深色"), SYSTEM("跟随系统") }

enum class ThemeColor(val label: String) {
    GREEN("薄荷绿"), TEAL("青碧"), BLUE("海湾蓝"), VIOLET("薰衣草紫"), ROSE("樱花粉"), ORANGE("暖阳橙")
}

data class Appearance(val mode: ThemeMode = ThemeMode.SYSTEM, val color: ThemeColor = ThemeColor.GREEN)

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("neno", Context.MODE_PRIVATE)

    private val _github = MutableStateFlow(loadGithub())
    val github: StateFlow<GithubConfig> = _github.asStateFlow()

    private val _appearance = MutableStateFlow(loadAppearance())
    val appearance: StateFlow<Appearance> = _appearance.asStateFlow()

    private val _notion = MutableStateFlow(loadNotion())
    val notion: StateFlow<NotionConfig> = _notion.asStateFlow()

    private fun loadGithub() = GithubConfig(
        token = prefs.getString("gh_token", "")!!,
        owner = prefs.getString("gh_owner", "")!!,
        repo = prefs.getString("gh_repo", "")!!,
        lastCommitSha = prefs.getString("gh_last_commit", "")!!,
    )

    fun setGithub(config: GithubConfig) {
        prefs.edit {
            putString("gh_token", config.token.trim())
            putString("gh_owner", config.owner)
            putString("gh_repo", config.repo)
            putString("gh_last_commit", config.lastCommitSha)
        }
        _github.value = config.copy(token = config.token.trim())
    }

    private fun loadAppearance() = Appearance(
        mode = runCatching { ThemeMode.valueOf(prefs.getString("theme_mode", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM),
        color = runCatching { ThemeColor.valueOf(prefs.getString("theme_color", "GREEN")!!) }.getOrDefault(ThemeColor.GREEN),
    )

    fun setAppearance(value: Appearance) {
        prefs.edit { putString("theme_mode", value.mode.name); putString("theme_color", value.color.name) }
        _appearance.value = value
    }

    private fun loadNotion() = NotionConfig(
        enabled = prefs.getBoolean("notion_enabled", false),
        token = prefs.getString("notion_token", "")!!,
        databaseId = prefs.getString("notion_db", "")!!,
    )

    fun setNotion(value: NotionConfig) {
        prefs.edit {
            putBoolean("notion_enabled", value.enabled)
            putString("notion_token", value.token)
            putString("notion_db", value.databaseId)
        }
        _notion.value = value
    }
}
