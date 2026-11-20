package app.tiviate.ui.library

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import app.tiviate.data.AppSettings
import app.tiviate.data.VodPlay
import app.tiviate.ui.FormFactor
import app.tiviate.ui.LocalFormFactor
import app.tiviate.ui.LocalGraph
import app.tiviate.ui.LocalNavigator
import app.tiviate.ui.components.ActionButton
import app.tiviate.ui.components.Spinner
import app.tiviate.ui.player.Choice
import app.tiviate.ui.player.ChoiceDialog
import app.tiviate.ui.player.SeekBar
import app.tiviate.ui.player.VideoSlot
import app.tiviate.ui.player.focusSoon
import app.tiviate.ui.t
import app.tiviate.ui.theme.Tokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val STEP_MS = 10_000L

/**
 * A film or episode, fullscreen. Remote: Left/Right move 10 s, OK shows the controls (seek bar, pause,
 * sound, subtitles), Play/Pause and rewind/fast-forward keys work; Back leaves. Touch: tap for the
 * controls. Where it stopped is saved every few seconds, so it can carry on later.
 */
@Composable
fun VodPlayerScreen(item: VodPlay) {
    val graph = LocalGraph.current
    val nav = LocalNavigator.current
    val engine = graph.player.engine
    val context = LocalContext.current
    val touch = LocalFormFactor.current != FormFactor.TV
    val settings by graph.settings.screenSettings.collectAsStateWithLifecycle(AppSettings())
    val playback by engine.state.collectAsStateWithLifecycle()
    val ended by graph.player.vodEnded.collectAsState()
    var controls by remember { mutableStateOf(true) }
    var touchedAt by remember { mutableLongStateOf(0L) }
    var picker by remember { mutableStateOf<String?>(null) }
    val root = remember { FocusRequester() }
    val pause = remember { FocusRequester() }
    fun touched() { touchedAt = android.os.SystemClock.uptimeMillis() }

    // Phones turn sideways for films; the system bars hide (AppRoot).
    if (LocalFormFactor.current == FormFactor.PHONE) {
        DisposableEffect(Unit) {
            val activity = context as? Activity
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
    }

    fun save() {
        if (item.itemKey.startsWith("recording:")) return // recordings aren't in Continue watching
        val info = engine.seekInfo() ?: return
        graph.appScope.launch { graph.library.saveProgress(item, info.positionMs, info.durationMs) }
    }
    LaunchedEffect(item) {
        graph.player.playVod(item.url, item.itemKey, item.startAtMs, item.userAgent)
        while (true) { delay(10_000); save() }
    }
    DisposableEffect(item) {
        onDispose { save(); graph.player.stopVod() }
    }
    LaunchedEffect(ended) {
        if (!ended) return@LaunchedEffect
        if (!item.itemKey.startsWith("recording:")) engine.seekInfo()?.let { graph.library.saveProgress(item, it.durationMs, it.durationMs) }
        nav.back()
    }
    LaunchedEffect(controls, touchedAt, picker) {
        if (controls && picker == null) { delay(4_000); controls = false }
    }
    // Seeking keeps the focus on the picture (so Left/Right go on seeking); OK, Up and Down move it to the buttons.
    var toButtons by remember { mutableIntStateOf(0) }
    var rootFocused by remember { mutableStateOf(false) }
    LaunchedEffect(controls) { if (!controls) focusSoon(root) }
    LaunchedEffect(toButtons) { if (toButtons > 0) focusSoon(pause) }
    LaunchedEffect(Unit) { focusSoon(root) }
    BackHandler(enabled = picker == null) { nav.back() }

    Box(
        Modifier.fillMaxSize().testTag("vod_player")
            .focusRequester(root)
            .onFocusChanged { rootFocused = it.isFocused }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                val code = e.nativeKeyEvent.keyCode
                when (code) {
                    AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> { engine.seekBy(-STEP_MS); controls = true; touched(); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { engine.seekBy(STEP_MS); controls = true; touched(); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AndroidKeyEvent.KEYCODE_MEDIA_PLAY, AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> { engine.togglePause(); controls = true; touched(); true }
                    // Left/Right seek while the picture has the focus; on the buttons they move between them.
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> if (rootFocused) { engine.seekBy(-STEP_MS); controls = true; touched(); true } else { touched(); false }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> if (rootFocused) { engine.seekBy(STEP_MS); controls = true; touched(); true } else { touched(); false }
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN,
                    AndroidKeyEvent.KEYCODE_MENU -> if (rootFocused) { controls = true; touched(); toButtons++; true } else { touched(); false }
                    else -> false
                }
            }
            .focusable()
            .pointerInput(Unit) { detectTapGestures { controls = !controls; touched() } },
    ) {
        VideoSlot(settings.displayMode, settings.zoomPercent, Modifier.fillMaxSize())
        when {
            playback.issue != null -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(t(playback.issue!!.title), style = TextStyle(color = Tokens.Text, fontSize = Tokens.BodySize))
                ActionButton(t("Try again"), { engine.retryNow() }, tag = "vod_retry")
            }
            playback.isBuffering && !playback.isPaused -> Box(Modifier.align(Alignment.Center)) { Spinner(48.dp) }
        }
        if (controls) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Tokens.Scrim).padding(horizontal = Tokens.SafeMargin, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Tokens.Text, fontSize = Tokens.HeadingSize, fontWeight = FontWeight.SemiBold))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    ActionButton(if (playback.isPaused) t("Play") else t("Pause"), { engine.togglePause(); touched() }, Modifier.focusRequester(pause), primary = true, tag = "vod_pause")
                    ActionButton(t("Audio"), { picker = "audio" }, tag = "vod_audio")
                    ActionButton(t("Subtitles"), { picker = "subtitles" }, tag = "vod_subtitles")
                    if (touch) ActionButton(t("Close"), { nav.back() }, tag = "vod_close")
                }
                SeekBar(engine, onUse = ::touched)
            }
        }
        when (picker) {
            "audio" -> ChoiceDialog(t("Audio"), engine.audioTracks().map { o -> Choice(o.label, o.selected) { engine.selectAudio(o); picker = null } }, empty = t("This stream has one sound track."))
            "subtitles" -> ChoiceDialog(
                t("Subtitles"),
                listOf(Choice(t("Off"), engine.textTracks().none { it.selected }) { engine.selectText(null); picker = null }) +
                    engine.textTracks().map { o -> Choice(o.label, o.selected) { engine.selectText(o); picker = null } },
            )
        }
        if (picker != null) BackHandler { picker = null }
    }
}
