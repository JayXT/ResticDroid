package io.github.resticdroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList

sealed interface Screen {
    object Profiles : Screen
    object Destinations : Screen
    object Settings : Screen

    data class EditProfile(val profileId: String?) : Screen
    data class EditDestination(val destinationId: String?) : Screen
    data class Snapshots(val destinationId: String) : Screen
    data class Snapshot(val destinationId: String, val snapshotId: String) : Screen
    data class Diff(val destinationId: String, val from: String, val to: String) : Screen
    data class Log(val path: String) : Screen
}

class Navigator(initial: Screen) {
    private val stack: SnapshotStateList<Screen> = mutableStateListOf(initial)

    val current: Screen get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    fun go(screen: Screen) {
        stack.add(screen)
    }

    fun select(screen: Screen) {
        stack.clear()
        stack.add(screen)
    }

    fun back(): Boolean {
        if (!canGoBack) return false
        stack.removeAt(stack.lastIndex)
        return true
    }
}

@Composable
fun rememberNavigator(initial: Screen = Screen.Profiles): Navigator =
    remember { Navigator(initial) }
