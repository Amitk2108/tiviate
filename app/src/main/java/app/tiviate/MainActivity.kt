package app.tiviate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
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
        SyncScheduler.schedulePeriodic(this)
    }

    override fun onStop() {
        super.onStop()
        // Free the provider connection (many accounts allow only one) whenever the app leaves the screen.
        if (!isChangingConfigurations) graph.player.stop()
    }
}
