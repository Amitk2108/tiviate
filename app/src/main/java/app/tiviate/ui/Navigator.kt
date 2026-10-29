package app.tiviate.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf
import app.tiviate.AppGraph
import app.tiviate.data.db.PlaylistType

sealed interface Screen {
    data object Loading : Screen
    data object Welcome : Screen
    /** Adds a playlist, or edits the one with [editId]. */
    data class AddPlaylist(val type: PlaylistType, val editId: Long? = null) : Screen
    /** [isEdit]: reloading a playlist the viewer edited (it must never be deleted from here). */
    data class Syncing(val playlistId: Long, val isEdit: Boolean = false) : Screen
    data object Player : Screen
    data object Guide : Screen
    data object Search : Screen
    data object Settings : Screen
    data class ManageChannels(val playlistId: Long, val category: app.tiviate.data.Category, val reorder: Boolean) : Screen
    data class ManageGroups(val playlistId: Long) : Screen
    data object OptionCards : Screen
}

/** A tiny back stack; screens are cheap to recreate and state that must survive lives in view models/DB. */
class Navigator(initial: Screen) {
    private val stack = mutableStateListOf(initial)

    val current: Screen get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1
    /** The screen under the current one, if any. */
    val previous: Screen? get() = stack.getOrNull(stack.lastIndex - 1)

    fun push(screen: Screen) {
        if (stack.last() != screen) stack.add(screen)
    }

    fun replaceAll(screen: Screen) {
        stack.clear()
        stack.add(screen)
    }

    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }
}

val LocalGraph = staticCompositionLocalOf<AppGraph> { error("AppGraph not provided") }
val LocalNavigator = staticCompositionLocalOf<Navigator> { error("Navigator not provided") }

/** False for a screen drawn underneath another (Settings over the guide): it must not take Back or focus. */
val LocalScreenActive = androidx.compose.runtime.compositionLocalOf { true }
