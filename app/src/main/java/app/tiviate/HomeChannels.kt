package app.tiviate

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.provider.BaseColumns
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.tvprovider.media.tv.Channel
import androidx.tvprovider.media.tv.ChannelLogoUtils
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import app.tiviate.data.db.ChannelEntity
import app.tiviate.ui.isTvDevice
import app.tiviate.ui.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Rows on the Android TV home screen: "Favorites" and "Recent" from the active playlist. Picking a
 * channel there opens Tiviate fullscreen on it. Android TV 8.0+ only: Fire TV and phones have no such rows.
 */
object HomeChannels {
    private const val PREFS = "home_channels"
    private const val MAX = 20
    private val started = AtomicBoolean(false)

    data class Rows(val favorites: List<ChannelEntity> = emptyList(), val recent: List<ChannelEntity> = emptyList())

    /** The link a home-screen channel opens. */
    fun link(playlistId: Long, key: String): String = "tiviate://play?p=$playlistId&k=" + URLEncoder.encode(key, "UTF-8")

    /** The channel a link asks for (playlist id, channel key); null for anything else. */
    fun parse(link: String?): Pair<Long, String>? {
        val uri = runCatching { URI(link ?: return null) }.getOrNull() ?: return null
        if (uri.scheme != "tiviate" || uri.host != "play") return null
        val query = uri.rawQuery?.split('&')?.associate {
            it.substringBefore('=') to runCatching { URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }.getOrDefault("")
        } ?: return null
        val playlistId = query["p"]?.toLongOrNull() ?: return null
        val key = query["k"]?.takeIf { it.isNotEmpty() } ?: return null
        return playlistId to key
    }

    /** Keeps the rows in step with the favourites and history, from now until the app's process ends. */
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    fun start(context: Context, graph: AppGraph) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !isTvDevice(context)) return
        if (context.packageManager.hasSystemFeature("amazon.hardware.fire_tv")) return // Fire TV shows no app rows
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        graph.appScope.launch(Dispatchers.IO) {
            graph.settings.settings.map { it.activePlaylistId }.distinctUntilChanged()
                .flatMapLatest { pid ->
                    if (pid == null) flowOf(Rows())
                    else combine(graph.db.favorites().favoriteChannels(pid), graph.db.history().recent(pid, MAX)) { f, r -> Rows(f.take(MAX), r) }
                }
                .distinctUntilChanged()
                .debounce(1_500) // a burst of changes (zapping, a sync) updates the home screen once
                .collect { rows -> runCatching { publish(app, rows) }.onFailure { Log.w("HomeChannels", "home screen rows not updated", it) } }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun publish(context: Context, rows: Rows) {
        // Favorites first: the first row an app adds appears on the home screen without asking.
        row(context, "favorites", t("Favorites"), rows.favorites)
        row(context, "recent", t("Recent"), rows.recent)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun row(context: Context, id: String, title: String, channels: List<ChannelEntity>) {
        val resolver = context.contentResolver
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val channel = Channel.Builder()
            .setType(TvContractCompat.Channels.TYPE_PREVIEW)
            .setDisplayName(title)
            .setAppLinkIntentUri(Uri.parse("tiviate://play"))
            .setInternalProviderId(id)
            .build()
        var channelId = prefs.getLong(id, -1L)
        // The row may have been removed (data cleared on the TV): then it is added again.
        if (channelId != -1L && resolver.update(TvContractCompat.buildChannelUri(channelId), channel.toContentValues(), null, null) < 1) channelId = -1L
        if (channelId == -1L) {
            val uri = resolver.insert(TvContractCompat.Channels.CONTENT_URI, channel.toContentValues()) ?: return
            channelId = ContentUris.parseId(uri)
            prefs.edit().putLong(id, channelId).apply()
            ChannelLogoUtils.storeChannelLogo(context, channelId, logo(context))
            runCatching { TvContractCompat.requestChannelBrowsable(context, channelId) }
        }
        // At most 20 entries: replacing them is simpler than matching them up, and just as quick.
        resolver.query(TvContractCompat.buildPreviewProgramsUriForChannel(channelId), arrayOf(BaseColumns._ID), null, null, null)?.use { c ->
            while (c.moveToNext()) resolver.delete(TvContractCompat.buildPreviewProgramUri(c.getLong(0)), null, null)
        }
        val fallbackPoster = Uri.parse("android.resource://${context.packageName}/${R.mipmap.ic_launcher_foreground}")
        channels.forEachIndexed { i, ch ->
            val poster = (ch.logoUrl ?: ch.logoFallback)?.takeIf { it.startsWith("http") }?.let(Uri::parse) ?: fallbackPoster
            val program = PreviewProgram.Builder()
                .setChannelId(channelId)
                .setType(TvContractCompat.PreviewPrograms.TYPE_CHANNEL)
                .setTitle(ch.name)
                .setDescription(ch.groupName)
                .setPosterArtUri(poster)
                .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_1_1)
                .setIntentUri(Uri.parse(link(ch.playlistId, ch.stableKey)))
                .setInternalProviderId(ch.stableKey)
                .setWeight(channels.size - i) // keeps the app's order
                .build()
            resolver.insert(TvContractCompat.PreviewPrograms.CONTENT_URI, program.toContentValues())
        }
    }

    /** The row's logo: the app icon on its own dark background (the home screen wants it opaque). */
    private fun logo(context: Context): Bitmap {
        val size = 160
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF070E1A.toInt())
        BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher_foreground)
            ?.let { canvas.drawBitmap(it, null, Rect(0, 0, size, size), Paint(Paint.FILTER_BITMAP_FLAG)) }
        return bitmap
    }
}
