package app.tiviate.data

import androidx.sqlite.db.SimpleSQLiteQuery

/**
 * One place that decides which channels a category shows and in what order, so the list, the
 * guide, zapping and "position of the playing channel" always agree.
 */
object ChannelQuery {

    /** Channels the viewer hasn't hidden, in groups they haven't hidden. */
    private const val VISIBLE =
        "c.hidden = 0 AND (c.groupName IS NULL OR c.groupName NOT IN " +
            "(SELECT gp.name FROM group_pref gp WHERE gp.playlistId = c.playlistId AND gp.hidden = 1))"

    data class Parts(val from: String, val where: String, val orderBy: String, val args: List<Any>)

    fun parts(playlistId: Long, category: Category, sort: ChannelSort, includeHidden: Boolean = false): Parts {
        val visible = if (includeHidden) "1 = 1" else VISIBLE
        val hiddenOnly = if (includeHidden) "1 = 1" else "c.hidden = 0"
        return when (category) {
            Category.All -> Parts(fromFor(sort), "c.playlistId = ? AND $visible", orderFor(sort), listOf(playlistId))
            is Category.Group -> Parts(fromFor(sort), "c.playlistId = ? AND $hiddenOnly AND c.groupName = ?", orderFor(sort), listOf(playlistId, category.name))
            Category.Favorites -> Parts(
                "channel c JOIN favorite f ON f.playlistId = c.playlistId AND f.stableKey = c.stableKey",
                "c.playlistId = ? AND $hiddenOnly", "f.position, f.addedAt", listOf(playlistId),
            )
            Category.Recent -> Parts(
                "channel c JOIN history h ON h.playlistId = c.playlistId AND h.stableKey = c.stableKey",
                "c.playlistId = ? AND $hiddenOnly", "h.lastWatchedAt DESC", listOf(playlistId),
            )
            is Category.Custom -> Parts(
                "channel c JOIN custom_group_member m ON m.stableKey = c.stableKey",
                "m.groupId = ? AND c.playlistId = ? AND $hiddenOnly", "m.position, c.sortIndex", listOf(category.id, playlistId),
            )
        }
    }

    fun select(playlistId: Long, category: Category, sort: ChannelSort, columns: String = "c.*", includeHidden: Boolean = false): SimpleSQLiteQuery {
        val p = parts(playlistId, category, sort, includeHidden)
        return SimpleSQLiteQuery("SELECT $columns FROM ${p.from} WHERE ${p.where} ORDER BY ${p.orderBy}", p.args.toTypedArray())
    }

    private fun fromFor(sort: ChannelSort) =
        if (sort == ChannelSort.MOST_WATCHED) "channel c LEFT JOIN history h ON h.playlistId = c.playlistId AND h.stableKey = c.stableKey" else "channel c"

    fun orderFor(sort: ChannelSort): String = when (sort) {
        ChannelSort.PLAYLIST -> "c.sortIndex"
        ChannelSort.NAME -> "c.name COLLATE NOCASE, c.sortIndex"
        ChannelSort.NUMBER -> "c.number, c.sortIndex"
        ChannelSort.NEWEST -> "c.addedAt DESC, c.sortIndex"
        ChannelSort.MOST_WATCHED -> "COALESCE(h.totalWatchSec, 0) DESC, c.sortIndex"
        ChannelSort.MANUAL -> "COALESCE(c.customSort, c.sortIndex), c.sortIndex"
    }
}
