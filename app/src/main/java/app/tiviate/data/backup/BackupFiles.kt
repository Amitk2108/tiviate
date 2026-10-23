package app.tiviate.data.backup

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A backup file found on the TV. */
data class BackupFile(val name: String, val uri: Uri, val modifiedMs: Long)

/**
 * Backups live in the TV's Download/Tiviate folder, so they survive reinstalling Tiviate. Android 10+
 * lists only the files this install wrote; older ones are opened with "Choose a file…" (system picker).
 */
object BackupFiles {
    private const val FOLDER = "Tiviate"

    fun newName(nowMs: Long = System.currentTimeMillis()): String =
        "tiviate-backup-" + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date(nowMs)) + ".json"

    /** Writes [json]; returns where it went ("Download/Tiviate/…"). */
    suspend fun save(context: Context, json: String): String = withContext(Dispatchers.IO) {
        val name = newName()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Cannot create file")
            context.contentResolver.openOutputStream(uri)!!.use { it.write(json.toByteArray()) }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER).apply { mkdirs() }
            File(dir, name).writeText(json)
        }
        "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/$name"
    }

    /** Backups in Download/Tiviate, newest first. */
    suspend fun list(context: Context): List<BackupFile> = withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val out = ArrayList<BackupFile>()
                context.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.DATE_MODIFIED),
                    "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?", arrayOf("tiviate-backup-%"),
                    "${MediaStore.Downloads.DATE_MODIFIED} DESC",
                )?.use { c ->
                    while (c.moveToNext()) {
                        val uri = android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0))
                        out += BackupFile(c.getString(1), uri, c.getLong(2) * 1000)
                    }
                }
                out
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
                dir.listFiles { f -> f.name.startsWith("tiviate-backup-") && f.name.endsWith(".json") }.orEmpty()
                    .sortedByDescending { it.lastModified() }.map { BackupFile(it.name, Uri.fromFile(it), it.lastModified()) }
            }
        }.getOrDefault(emptyList())
    }

    suspend fun read(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
    }
}
