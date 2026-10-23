package app.tiviate

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
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
            TiviateTheme {
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
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        // Free the provider connection (many accounts allow only one) whenever the app leaves the screen.
        if (!isChangingConfigurations) graph.player.stop()
    }
}
