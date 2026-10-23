package app.tiviate

import android.app.Application
import app.tiviate.ui.images.buildImageLoader
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader

class TiviateApp : Application(), SingletonImageLoader.Factory {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        app.tiviate.ui.components.LogoUrls.init(this)
        return buildImageLoader(this, graph.http)
    }
}
