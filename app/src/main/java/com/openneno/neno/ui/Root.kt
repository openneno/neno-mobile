package com.openneno.neno.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openneno.neno.ui.components.NoteActions
import com.openneno.neno.ui.screens.DailyScreen
import com.openneno.neno.ui.screens.DetailScreen
import com.openneno.neno.ui.screens.DrawerContent
import com.openneno.neno.ui.screens.EditorScreen
import com.openneno.neno.ui.screens.ImageViewer
import com.openneno.neno.ui.screens.NotesScreen
import com.openneno.neno.ui.screens.SettingsScreen
import com.openneno.neno.ui.screens.ShareScreen
import com.openneno.neno.ui.screens.WalkScreen
import com.openneno.neno.ui.theme.Neno
import kotlinx.coroutines.launch

@Composable
fun NenoRoot(state: AppState, onExit: () -> Unit) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val holder = rememberSaveableStateHolder()
    val actions = NoteActions(open = { state.open(it) }, onTag = { state.showTag(it) })
    val openDrawer: () -> Unit = { scope.launch { drawer.open() } }

    BackHandler {
        if (drawer.isOpen) scope.launch { drawer.close() }
        else if (!state.back()) onExit()
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = state.stack.size == 1,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = Neno.colors.canvas, drawerShape = androidx.compose.foundation.shape.RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp)) {
                DrawerContent(state, close = { scope.launch { drawer.close() } })
            }
        },
    ) {
        Box(Modifier.fillMaxSize().background(Neno.colors.canvas)) {
            AnimatedContent(
                targetState = state.main,
                transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
                label = "main",
            ) { main ->
                holder.SaveableStateProvider(main.toString()) {
                    when (main) {
                        Screen.Notes -> NotesScreen(state, actions, openDrawer)
                        Screen.Daily -> DailyScreen(state, actions, openDrawer)
                        Screen.Walk -> WalkScreen(state, actions, openDrawer)
                        Screen.Settings -> SettingsScreen(openDrawer)
                    }
                }
            }
            val overlay = state.stack.lastOrNull()?.takeIf { it !is Screen.Main }
            AnimatedContent(
                targetState = overlay,
                transitionSpec = {
                    (slideInHorizontally(tween(220)) { it / 5 } + fadeIn(tween(200))) togetherWith
                        (slideOutHorizontally(tween(180)) { it / 5 } + fadeOut(tween(160)))
                },
                label = "overlay",
            ) { screen ->
                if (screen != null) holder.SaveableStateProvider(screen.toString()) {
                    Box(Modifier.fillMaxSize().background(Neno.colors.canvas)) {
                        when (screen) {
                            is Screen.Detail -> DetailScreen(screen.id, actions, onBack = { state.close(screen) })
                            is Screen.Editor -> EditorScreen(screen, onClose = { state.close(screen) })
                            is Screen.Share -> ShareScreen(screen.id, onBack = { state.close(screen) })
                            is Screen.Images -> ImageViewer(screen.images, screen.index, onBack = { state.close(screen) })
                            is Screen.Main -> {}
                        }
                    }
                }
            }
        }
    }
}
