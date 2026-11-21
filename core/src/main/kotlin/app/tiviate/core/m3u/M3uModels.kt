package app.tiviate.core.m3u

/** Attributes from the `#EXTM3U` header line, e.g. `url-tvg` EPG locations. */
data class M3uHeader(val epgUrls: List<String>, val attrs: Map<String, String>)

/** One playable playlist entry. Blank attribute values are normalised to null. */
data class M3uEntry(
    val name: String,
    val url: String,
    val tvgId: String?,
    val tvgName: String?,
    val tvgLogo: String?,
    val groupTitle: String?,
    val tvgChno: Int?,
    val catchup: String?,
    val catchupSource: String?,
    val catchupDays: Int?,
    val userAgent: String?,
    val referrer: String?,
    val attrs: Map<String, String>,
    /** DRM from `#KODIPROP` lines: the scheme (widevine, clearkey, playready) and its `license_key`. */
    val drmScheme: String? = null,
    val drmLicense: String? = null,
    /** `inputstream.adaptive.manifest_type` (mpd, hls…), for links that don't show it. */
    val manifestType: String? = null,
)

data class M3uStats(val entries: Int, val skipped: Int)
