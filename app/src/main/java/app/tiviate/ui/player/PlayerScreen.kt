package app.tiviate.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tiviate.ui.LocalGraph

@Composable
fun PlayerScreen() {
    val graph = LocalGraph.current
    val vm: PlayerViewModel = viewModel(factory = PlayerViewModel.factory(graph))
    KeepScreenOn()
    LifecycleStartEffect(Unit) {
        vm.ensurePlaying()
        onStopOrDispose { }
    }
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("player_screen")) {
        VideoSurface(graph.player.engine, AspectMode.FIT, Modifier.fillMaxSize())
    }
}
