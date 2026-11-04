package com.openneno.neno.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.openneno.neno.data.NoteImage

/** 页面 */
sealed interface Screen {
    /** 抽屉中的主页面 */
    sealed interface Main : Screen

    data object Notes : Main
    data object Daily : Main
    data object Walk : Main
    data object Settings : Main

    data class Detail(val id: String) : Screen

    /** id 为空时新建; parentId 不为空时是批注 */
    data class Editor(val id: String = "", val parentId: String = "", val initialText: String = "") : Screen
    data class Share(val id: String) : Screen
    data class Images(val images: List<NoteImage>, val index: Int) : Screen
}

/** 全部笔记页面的筛选条件 */
data class NoteFilter(val tag: String = "", val day: String = "", val keyword: String = "") {
    val active get() = tag.isNotEmpty() || day.isNotEmpty() || keyword.isNotBlank()
}

class AppState : ViewModel() {
    val stack = mutableStateListOf<Screen>(Screen.Notes)

    var filter by mutableStateOf(NoteFilter())

    val current: Screen get() = stack.last()

    /** 最底层的主页面 */
    val main: Screen.Main get() = stack.first() as Screen.Main

    fun open(screen: Screen) {
        if (screen is Screen.Main) {
            stack.clear(); stack.add(screen)
        } else stack.add(screen)
    }

    fun back(): Boolean {
        if (stack.size <= 1) {
            if (main != Screen.Notes) {
                open(Screen.Notes); return true
            }
            if (filter.active) {
                filter = NoteFilter(); return true
            }
            return false
        }
        stack.removeAt(stack.lastIndex)
        return true
    }

    /** 关闭当前覆盖页面(编辑器等) */
    fun close(screen: Screen) {
        val i = stack.lastIndexOf(screen)
        if (i > 0) stack.removeAt(i)
    }

    fun showTag(tag: String) {
        filter = NoteFilter(tag = tag)
        open(Screen.Notes)
    }

    fun showDay(day: String) {
        filter = NoteFilter(day = day)
        open(Screen.Notes)
    }
}
