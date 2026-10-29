package app.tiviate

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import app.tiviate.data.sync.SyncScheduler
import app.tiviate.ui.AppRoot
import app.tiviate.ui.LocalGraph
import app.tiviate.ui.theme.TiviateTheme

class MainActivity : ComponentActivity() {

    private val graph get() = (application as TiviateApp).graph

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settings by graph.settings.screenSettings.collectAsState(initial = app.tiviate.data.AppSettings())
            TiviateTheme(settings) {
                CompositionLocalProvider(LocalGraph provides graph) { AppRoot() }
            }
        }
        lifecycleScope.launch(Dispatchers.IO) {
            SyncScheduler.schedulePeriodic(this@MainActivity, graph.settings.settings.first().updateIntervalHours)
        }
        // Sleep timer fired: playback has stopped; leave the app so the TV can go to sleep.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                graph.player.sleepDue.collect { due -> if (due) { graph.player.consumeSleepDue(); finish() } }
            }
        }
    }

    /**
     * The remote's Back always goes to the app's back handling. Without this, Compose may spend Back
     * on moving focus out of a list (focus "exit"), so Back would not close an open channel list.
     */
    private var longBackFired = false

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) longBackFired = false
                    // Held Back (about half a second): straight back to the player.
                    if (!longBackFired && event.eventTime - event.downTime >= LONG_BACK_MS) {
                        longBackFired = true
                        graph.longBack.tryEmit(Unit)
                    }
                }
                KeyEvent.ACTION_UP -> if (!event.isCanceled && !longBackFired) onBackPressedDispatcher.onBackPressed()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Picture-in-picture (16:9); Android 8+. */
    fun enterPip(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return runCatching {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        }.getOrDefault(false)
    }

    /** "Switch to picture-in-picture on Home": only from fullscreen, only while something plays. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val player = graph.player
        if (player.pipOnHome && player.fullscreenVisible && player.engine.state.value.isPlaying) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        graph.player.inPip.value = isInPictureInPictureMode
    }

    private companion object {
        const val LONG_BACK_MS = 600L
    }

    override fun onStart() {
        super.onStart()
        graph.player.foreground = true
    }

    override fun onStop() {
        super.onStop()
        // Free the provider connection (many accounts allow only one) whenever the app leaves the screen.
        if (!isChangingConfigurations) graph.player.onAppHidden()
    }
}
