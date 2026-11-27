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
import app.tiviate.data.LibraryRepository
import app.tiviate.data.MetaRepository
import app.tiviate.data.SubQuery
import app.tiviate.core.meta.SubHit
import app.tiviate.player.ExternalSubtitle
import androidx.compose.runtime.rememberCoroutineScope
import java.io.File
import java.util.Locale
import app.tiviate.data.db.TitlePrefEntity
import app.tiviate.data.VodPlay
import app.tiviate.ui.FormFactor
import app.tiviate.ui.LocalFormFactor
import app.tiviate.ui.LocalGraph
import app.tiviate.ui.LocalNavigator
import app.tiviate.ui.Screen
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
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
        // A subtitle chosen from the internet for this title comes back with it.
        val saved = if (item.itemKey.startsWith("recording:")) null
            else graph.library.pref(item.playlistId, LibraryRepository.titleKey(item))?.subtitlePath?.let(::File)?.takeIf { it.exists() }
        graph.player.playVod(item.url, item.itemKey, item.startAtMs, item.userAgent, saved?.let { ExternalSubtitle(it.path, null, ONLINE_LABEL) })
        while (true) { delay(10_000); save() }
    }
    DisposableEffect(item) {
        onDispose { save(); graph.player.stopVod() }
    }
    LaunchedEffect(ended) {
        if (!ended) return@LaunchedEffect
        if (!item.itemKey.startsWith("recording:")) engine.seekInfo()?.let { graph.library.saveProgress(item, it.durationMs, it.durationMs) }
        // A series goes on with its next episode (unless turned off in Settings).
        val next = if (item.seriesId != null && graph.settings.settings.first().autoNextEpisode) runCatching { graph.library.nextEpisode(item) }.getOrNull() else null
        graph.player.vodEnded.value = false // the next screen mustn't see this end as its own
        nav.back()
        if (next != null) nav.push(Screen.VodPlayer(next))
    }
    // Per film or series (episodes share it): the sound and subtitles chosen last time, and where the intro ends.
    val remembers = !item.itemKey.startsWith("recording:")
    val titleKey = LibraryRepository.titleKey(item)
    fun rememberChoice(change: (TitlePrefEntity) -> TitlePrefEntity) {
        if (remembers) graph.appScope.launch { graph.library.updatePref(item.playlistId, titleKey, change) }
    }
    val services by remember { graph.meta.services }.collectAsStateWithLifecycle(emptySet())
    var introEnd by remember(item) { mutableStateOf<Long?>(null) }
    LaunchedEffect(item) {
        if (!remembers) return@LaunchedEffect
        val pref = graph.library.pref(item.playlistId, titleKey) ?: return@LaunchedEffect
        if (item.seriesId != null) introEnd = pref.introEndMs
        if (pref.audioLang == null && pref.textLang == null && pref.subtitlePath == null) return@LaunchedEffect
        // shortcut: polls until the stream's tracks are known (at most 15 s); a "tracks changed" signal from the engine would be neater.
        withTimeoutOrNull(15_000) { while (engine.audioTracks().isEmpty() && engine.textTracks().isEmpty()) delay(300) }
        pref.audioLang?.let { lang -> engine.audioTracks().firstOrNull { it.language == lang && !it.selected }?.let(engine::selectAudio) }
        val online = pref.subtitlePath?.let { engine.textTracks().firstOrNull { it.label.contains(ONLINE_LABEL) } }
        if (online != null) {
            if (!online.selected) engine.selectText(online)
        } else when (val lang = pref.textLang) {
            null -> Unit
            "off" -> engine.selectText(null)
            else -> engine.textTracks().firstOrNull { it.language == lang && !it.selected }?.let(engine::selectText)
        }
    }
    // Skip intro: shown while an episode is still before where its series' intro ends.
    var inIntro by remember(item) { mutableStateOf(false) }
    var hadSkip by remember(item) { mutableStateOf(false) }
    val skip = remember { FocusRequester() }
    LaunchedEffect(introEnd) {
        val end = introEnd ?: return@LaunchedEffect
        while (true) {
            val at = engine.seekInfo()?.positionMs
            inIntro = at != null && at < end - 1_000
            if (at != null && at >= end - 1_000) break
            delay(500)
        }
    }
    LaunchedEffect(inIntro) {
        if (inIntro) { hadSkip = true; focusSoon(skip) } else if (hadSkip) { hadSkip = false; focusSoon(root) }
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
                    if (item.seriesId != null) {
                        ActionButton(t("Intro ends here"), {
                            engine.seekInfo()?.positionMs?.let { at -> introEnd = at; rememberChoice { it.copy(introEndMs = at) } }
                            touched()
                        }, tag = "vod_intro_set")
                    }
                    if (touch) ActionButton(t("Close"), { nav.back() }, tag = "vod_close")
                }
                SeekBar(engine, onUse = ::touched)
            }
        }
        if (inIntro) {
            ActionButton(
                t("Skip intro"), { introEnd?.let { engine.seekTo(it) }; inIntro = false },
                Modifier.align(Alignment.BottomEnd).padding(end = Tokens.SafeMargin, bottom = if (controls) 150.dp else Tokens.SafeMargin).focusRequester(skip),
                primary = true, tag = "vod_skip_intro",
            )
        }
        when (picker) {
            "online" -> OnlineSubtitles(item, onDone = { picker = null }) { file, hit ->
                rememberChoice { it.copy(subtitlePath = file.path) }
                val at = engine.seekInfo()?.positionMs ?: 0
                graph.player.playVod(item.url, item.itemKey, at, item.userAgent, ExternalSubtitle(file.path, hit.language, ONLINE_LABEL))
                // Shown as soon as the player knows it.
                graph.appScope.launch {
                    withTimeoutOrNull(15_000) { while (engine.textTracks().none { it.label.contains(ONLINE_LABEL) }) delay(300) }
                    engine.textTracks().firstOrNull { it.label.contains(ONLINE_LABEL) && !it.selected }?.let(engine::selectText)
                }
            }
            "audio" -> ChoiceDialog(
                t("Audio"),
                engine.audioTracks().map { o -> Choice(o.label, o.selected) { engine.selectAudio(o); picker = null; rememberChoice { it.copy(audioLang = o.language) } } },
                empty = t("This stream has one sound track."), onDismiss = { picker = null },
            )
            "subtitles" -> ChoiceDialog(
                t("Subtitles"),
                listOf(Choice(t("Off"), engine.textTracks().none { it.selected }) { engine.selectText(null); picker = null; rememberChoice { it.copy(textLang = "off") } }) +
                    engine.textTracks().map { o -> Choice(o.label, o.selected) { engine.selectText(o); picker = null; rememberChoice { it.copy(textLang = o.language) } } } +
                    // With the viewer's OpenSubtitles key.
                    (if (MetaRepository.Service.OPENSUBTITLES in services) listOf(Choice(t("Search online…"), false) { picker = "online" }) else emptyList()),
                onDismiss = { picker = null },
            )
        }
        if (picker != null) BackHandler { picker = null }
    }
}

/** How a subtitle file from the internet is named in the subtitles list (and found again). */
private const val ONLINE_LABEL = "OpenSubtitles"

/** Subtitles for this film or episode from OpenSubtitles, in the viewer's language first; [onChosen] gets the downloaded file. */
@Composable
private fun OnlineSubtitles(item: VodPlay, onDone: () -> Unit, onChosen: (File, SubHit) -> Unit) {
    val graph = LocalGraph.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by graph.settings.screenSettings.collectAsStateWithLifecycle(AppSettings())
    var hits by remember { mutableStateOf<List<SubHit>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(item) {
        val langs = listOf((settings.language ?: Locale.getDefault().language).substringBefore('-').lowercase(), "en").distinct()
        hits = runCatching { graph.meta.searchSubtitles(SubQuery(item.seriesName ?: item.title, null, item.season, item.episode, langs)) }
            .getOrElse { problem = it.message; emptyList() }
    }
    fun name(code: String) = Locale.forLanguageTag(code).displayLanguage.ifEmpty { code }
    ChoiceDialog(
        t("Subtitles from the internet"),
        hits.orEmpty().take(30).map { h ->
            Choice("${name(h.language)}   ${h.release}", false) {
                scope.launch {
                    val file = runCatching { graph.meta.downloadSubtitle(h, File(context.filesDir, "subtitles")) }
                    // Back on the main thread before touching the player (it lives there).
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        file.onSuccess { onChosen(it, h); onDone() }
                            .onFailure { problem = t(it.message ?: "The subtitle couldn't be downloaded.") }
                    }
                }
            }
        },
        empty = problem ?: if (hits == null) t("Searching…") else t("No subtitles found for this title."),
        onDismiss = onDone,
    )
}
