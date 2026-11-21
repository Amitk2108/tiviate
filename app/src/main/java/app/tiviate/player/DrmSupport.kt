package app.tiviate.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.drm.MediaDrmCallback
import app.tiviate.core.drm.Drm
import app.tiviate.core.drm.DrmLicense
import app.tiviate.data.db.ChannelEntity

/** A protected channel: its scheme ([Drm.WIDEVINE], [Drm.CLEARKEY], [Drm.PLAYREADY]) and how to unlock it. */
data class DrmSpec(val scheme: String, val license: DrmLicense)

/**
 * DRM as playlists describe it: the provider's licence server is asked, or the ClearKey keys the playlist
 * gives are used, through Android's own DRM. Nothing is worked around.
 */
@OptIn(UnstableApi::class)
object DrmSupport {

    /** The channel's DRM, when its playlist names one. */
    fun of(channel: ChannelEntity): DrmSpec? {
        val scheme = channel.drmScheme ?: return null
        val license = Drm.license(channel.drmLicense) ?: return null
        return DrmSpec(scheme, license)
    }

    /** The stream type a playlist states (`manifest_type`), for links that don't show it. */
    fun mime(manifestType: String?): String? = when (manifestType?.lowercase()) {
        "mpd", "dash" -> MimeTypes.APPLICATION_MPD
        "hls" -> MimeTypes.APPLICATION_M3U8
        else -> null
    }

    /** True for channels the recorder can't save (it records MPEG-TS and HLS, never protected video). */
    fun unrecordable(channel: ChannelEntity): Boolean =
        channel.drmScheme != null || mime(channel.manifestType) == MimeTypes.APPLICATION_MPD || StreamType.mimeFromUrl(channel.streamUrl) == MimeTypes.APPLICATION_MPD

    fun sessionManager(spec: DrmSpec, http: DataSource.Factory): DrmSessionManager {
        val uuid = when (spec.scheme) {
            Drm.WIDEVINE -> C.WIDEVINE_UUID
            Drm.PLAYREADY -> C.PLAYREADY_UUID
            else -> C.CLEARKEY_UUID
        }
        val keys = spec.license.clearKeys
        val callback: MediaDrmCallback = if (keys.isNotEmpty()) {
            LocalMediaDrmCallback(Drm.clearKeyResponse(keys).toByteArray())
        } else {
            // The playlist's licence server wins over one a manifest may name.
            HttpMediaDrmCallback(spec.license.url, true, http).apply { spec.license.headers.forEach { (k, v) -> setKeyRequestProperty(k, v) } }
        }
        val manager = DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(uuid, FrameworkMediaDrm.DEFAULT_PROVIDER)
            .setMultiSession(true)
            .build(callback)
        if (keys.isEmpty()) return manager
        // Many ClearKey streams only say "ClearKey" in their manifest, without the key IDs the session
        // needs to start: name the playlist's key IDs for it.
        return KeyIdsFiller(manager, DrmInitData(DrmInitData.SchemeData(C.COMMON_PSSH_UUID, MimeTypes.VIDEO_MP4, Drm.commonPssh(keys.keys.toList()))))
    }

    private class KeyIdsFiller(private val inner: DrmSessionManager, private val keyIds: DrmInitData) : DrmSessionManager by inner {
        // Java default methods aren't delegated by "by": pass them on, or the inner manager never starts.
        override fun prepare() = inner.prepare()
        override fun release() = inner.release()

        /** An encrypted track without usable ClearKey data gets the playlist's key IDs. */
        private fun fill(format: Format): Format {
            val d = format.drmInitData ?: return format
            val usable = (0 until d.schemeDataCount).any { i ->
                d.get(i).let { s -> s.hasData() && (s.matches(C.CLEARKEY_UUID) || s.matches(C.COMMON_PSSH_UUID)) }
            }
            return if (usable) format else format.buildUpon().setDrmInitData(keyIds).build()
        }

        override fun acquireSession(eventDispatcher: DrmSessionEventListener.EventDispatcher?, format: Format) =
            inner.acquireSession(eventDispatcher, fill(format))

        override fun preacquireSession(eventDispatcher: DrmSessionEventListener.EventDispatcher?, format: Format) =
            inner.preacquireSession(eventDispatcher, fill(format))

        override fun getCryptoType(format: Format) = inner.getCryptoType(fill(format))
    }
}
