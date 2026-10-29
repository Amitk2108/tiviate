package app.tiviate.data.backup

import android.content.ContentValues
import android.database.Cursor
import app.tiviate.data.SettingsStore
import app.tiviate.data.db.TiviateDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * One file with everything a viewer set up: playlists (with their logins), favourites, recent channels,
 * own groups, group choices, hidden / renamed / reordered channels and manual guide links, extra guide
 * sources and all settings. Channels and programmes themselves are not saved — they are loaded again
 * from the provider when the backup is restored.
 */
class Backup(private val db: TiviateDatabase, private val settings: SettingsStore, private val appVersion: String) {

    /** Tables copied row by row. */
    private val tables = listOf("playlist", "favorite", "history", "group_pref", "custom_group", "custom_group_member")

    suspend fun toJson(nowMs: Long = System.currentTimeMillis()): String = withContext(Dispatchers.IO) {
        val sql = db.openHelper.readableDatabase
        val out = JSONObject()
            .put("app", "Tiviate")
            .put("format", FORMAT)
            .put("version", appVersion)
            .put("createdAt", nowMs)
        val t = JSONObject()
        for (table in tables) t.put(table, rows(sql.query("SELECT * FROM `$table`")))
        // Only the guide links the viewer added: the playlists' own guides come back with the playlists.
        t.put("epg_source", rows(sql.query("SELECT * FROM epg_source WHERE origin = 'MANUAL'")))
        out.put("tables", t)
        // The playlists' own guide sources are recreated (with new ids) when they reload; hand-assigned guide
        // channels point at them by id, so keep which old id belonged to which playlist.
        out.put("autoSources", rows(sql.query("SELECT id, playlistId FROM epg_source WHERE origin = 'AUTO' AND playlistId IS NOT NULL")))
        out.put(
            "channels",
            rows(
                sql.query(
                    "SELECT playlistId, stableKey, hidden, customSort, name, renamed, epgOverride FROM channel " +
                        "WHERE hidden = 1 OR customSort IS NOT NULL OR renamed = 1 OR epgOverride IS NOT NULL",
                ),
            ),
        )
        out.put("settings", encodeSettings(settings.exportRaw()))
        out.toString(1)
    }

    /**
     * Replaces everything with [json]. [reloadPlaylist] fetches a playlist's channels and guide again
     * (the real sync); the channel choices are applied once the channels are back.
     */
    suspend fun restore(json: String, reloadPlaylist: suspend (Long) -> Unit) {
        val root = JSONObject(json)
        require(root.optString("app") == "Tiviate") { "Not a Tiviate backup" }
        require(root.optInt("format") in 1..FORMAT) { "This backup was made by a newer Tiviate" }
        val t = root.getJSONObject("tables")
        withContext(Dispatchers.IO) {
            val sql = db.openHelper.writableDatabase
            // One transaction for clearing and filling: if any row can't be written, nothing is lost.
            sql.beginTransaction()
            try {
                for (table in CLEAR_ORDER) sql.execSQL("DELETE FROM `$table`")
                for (table in tables) insertAll(sql, table, t.optJSONArray(table))
                insertAll(sql, "epg_source", t.optJSONArray("epg_source")) { it.put("activeGeneration", 0); it.putNull("lastSyncAt") }
                sql.setTransactionSuccessful()
            } finally {
                sql.endTransaction()
            }
        }
        settings.importRaw(decodeSettings(root.optJSONObject("settings") ?: JSONObject()))

        val playlistIds = (0 until (t.optJSONArray("playlist")?.length() ?: 0)).map { t.getJSONArray("playlist").getJSONObject(it).getLong("id") }
        for (id in playlistIds) runCatching { reloadPlaylist(id) }

        val channels = root.optJSONArray("channels") ?: JSONArray()
        val oldAutoSource = HashMap<Long, Long>() // old guide source id -> its playlist
        root.optJSONArray("autoSources")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { oldAutoSource[it.getLong("id")] = it.getLong("playlistId") } }
        withContext(Dispatchers.IO) {
            val sql = db.openHelper.writableDatabase
            for (i in 0 until channels.length()) {
                val c = channels.getJSONObject(i)
                val override = c.optString("epgOverride").takeIf { !c.isNull("epgOverride") && it.isNotEmpty() }
                    ?.let { remapGuideRef(it, oldAutoSource) }
                sql.execSQL(
                    "UPDATE channel SET hidden = ?, customSort = ?, renamed = ?, " +
                        "name = CASE WHEN ? = 1 THEN ? ELSE name END, epgOverride = ?, epgRef = COALESCE(?, epgRef) " +
                        "WHERE playlistId = ? AND stableKey = ?",
                    arrayOf<Any?>(
                        c.optInt("hidden"), if (c.isNull("customSort")) null else c.getInt("customSort"), c.optInt("renamed"),
                        c.optInt("renamed"), c.optString("name"), override, override,
                        c.getLong("playlistId"), c.getString("stableKey"),
                    ),
                )
            }
        }
    }

    private fun rows(cursor: Cursor): JSONArray = cursor.use { c ->
        val arr = JSONArray()
        while (c.moveToNext()) {
            val o = JSONObject()
            for (i in 0 until c.columnCount) {
                val name = c.getColumnName(i)
                when (c.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> o.put(name, JSONObject.NULL)
                    Cursor.FIELD_TYPE_INTEGER -> o.put(name, c.getLong(i))
                    Cursor.FIELD_TYPE_FLOAT -> o.put(name, c.getDouble(i))
                    Cursor.FIELD_TYPE_STRING -> o.put(name, c.getString(i))
                    else -> Unit // no blobs in these tables
                }
            }
            arr.put(o)
        }
        arr
    }

    /** "oldSourceId:xmltvId" pointing at a playlist's own guide → the same channel in that guide's new source. */
    private suspend fun remapGuideRef(ref: String, oldAutoSource: Map<Long, Long>): String {
        val sourceId = ref.substringBefore(':').toLongOrNull() ?: return ref
        val playlistId = oldAutoSource[sourceId] ?: return ref
        val newId = db.epg().autoSource(playlistId)?.id ?: return ref
        return "$newId:${ref.substringAfter(':')}"
    }

    private fun insertAll(sql: androidx.sqlite.db.SupportSQLiteDatabase, table: String, arr: JSONArray?, adjust: (ContentValues) -> Unit = {}) {
        if (arr == null) return
        // Columns this version has: a backup from a newer Tiviate may carry extra ones.
        val columns = sql.query("PRAGMA table_info(`$table`)").use { c -> buildSet { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) } }
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val cv = ContentValues()
            for (k in o.keys()) {
                if (k !in columns) continue
                when (val v = o.get(k)) {
                    JSONObject.NULL -> cv.putNull(k)
                    is Int -> cv.put(k, v.toLong())
                    is Long -> cv.put(k, v)
                    is Double -> cv.put(k, v)
                    is Boolean -> cv.put(k, if (v) 1L else 0L)
                    else -> cv.put(k, v.toString())
                }
            }
            adjust(cv)
            sql.insert(table, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE, cv)
        }
    }

    companion object {
        const val FORMAT = 1

        /** Every table, children before parents, cleared before a restore. */
        private val CLEAR_ORDER = listOf(
            "custom_group_member", "custom_group", "group_pref", "history", "favorite",
            "programme", "epg_channel", "epg_source", "channel", "playlist",
        )

        /** Settings with their types, so numbers come back as the same kind (Int vs Long). */
        fun encodeSettings(values: Map<String, Any>): JSONObject {
            val o = JSONObject()
            for ((k, v) in values) {
                val (type, value) = when (v) {
                    is Boolean -> "b" to v
                    is Int -> "i" to v
                    is Long -> "l" to v
                    is Float -> "f" to v.toDouble()
                    is Double -> "d" to v
                    is String -> "s" to v
                    is Set<*> -> "ss" to JSONArray(v.map { it.toString() })
                    else -> continue
                }
                o.put(k, JSONObject().put("t", type).put("v", value))
            }
            return o
        }

        fun decodeSettings(o: JSONObject): Map<String, Any> = buildMap {
            for (k in o.keys()) {
                val e = o.getJSONObject(k)
                val value: Any = when (e.getString("t")) {
                    "b" -> e.getBoolean("v")
                    "i" -> e.getInt("v")
                    "l" -> e.getLong("v")
                    "f" -> e.getDouble("v").toFloat()
                    "d" -> e.getDouble("v")
                    "s" -> e.getString("v")
                    "ss" -> e.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                    else -> continue
                }
                put(k, value)
            }
        }
    }
}
