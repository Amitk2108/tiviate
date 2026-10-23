package app.tiviate.core.xtream

data class XtreamCredentials(val server: String, val username: String, val password: String)

data class XtreamAccount(
    val status: String?,
    val expiresAtMs: Long?,
    val maxConnections: Int?,
    val activeConnections: Int?,
    val authorized: Boolean,
    val allowedFormats: List<String>,
)

data class XtreamCategory(val id: String, val name: String)

data class XtreamLiveStream(
    val streamId: Long,
    val num: Int?,
    val name: String,
    val icon: String?,
    val epgChannelId: String?,
    val categoryId: String?,
    val tvArchive: Boolean,
    val tvArchiveDays: Int?,
)
