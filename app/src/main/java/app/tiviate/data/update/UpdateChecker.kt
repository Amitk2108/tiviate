package app.tiviate.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/** A newer Tiviate on GitHub Releases. */
data class Release(val version: String, val apkUrl: String, val apkSize: Long, val notes: String)

/**
 * Updates for sideloaded installs: asks the project's GitHub Releases for the latest version, downloads
 * its APK and hands it to the system installer. Nothing else is sent — just one request to GitHub.
 */
object UpdateChecker {
    const val LATEST_URL = "https://api.github.com/repos/Amitk2108/tiviate/releases/latest"
    private const val DAY_MS = 24 * 3_600_000L

    /** True when [remote] is a plain release (x.y.z) newer than [current]. Pre-releases are never offered. */
    fun isNewer(remote: String, current: String): Boolean {
        val r = parts(remote) ?: return false
        val c = parts(current) ?: return false
        for (i in 0 until maxOf(r.size, c.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = c.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun parts(v: String): List<Int>? {
        val clean = v.trim().removePrefix("v").removePrefix("V")
        if (clean.isEmpty() || !clean.all { it.isDigit() || it == '.' }) return null
        return clean.split('.').map { it.toIntOrNull() ?: return null }
    }

    fun dueForCheck(enabled: Boolean, lastCheckAt: Long, nowMs: Long): Boolean = enabled && nowMs - lastCheckAt >= DAY_MS

    /** Offer [remote] unless the viewer chose "Later" for exactly that version. */
    fun shouldOffer(remote: String, current: String, skipped: String?): Boolean =
        isNewer(remote, current) && remote.removePrefix("v") != skipped?.removePrefix("v")

    /** The latest release from GitHub's JSON, or null for drafts, pre-releases or a release without an APK. */
    fun parseLatest(json: String): Release? = runCatching {
        val o = JSONObject(json)
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
        val tag = o.getString("tag_name").removePrefix("v")
        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                return Release(tag, a.getString("browser_download_url"), a.optLong("size"), o.optString("body"))
            }
        }
        null
    }.getOrNull()

    suspend fun latest(http: OkHttpClient): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(LATEST_URL).header("Accept", "application/vnd.github+json").build()
            http.newCall(req).execute().use { if (it.isSuccessful) parseLatest(it.body.string()) else null }
        }.getOrNull()
    }

    /** Downloads the APK into the app's cache; [onProgress] gets 0–100. */
    suspend fun download(context: Context, http: OkHttpClient, release: Release, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val file = File(dir, "Tiviate-${release.version}.apk")
        http.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
            val total = resp.body.contentLength().takeIf { it > 0 } ?: release.apkSize
            resp.body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
                        if (pct != last) { last = pct; onProgress(pct) }
                    }
                }
            }
            if (release.apkSize > 0) check(file.length() == release.apkSize) { "Incomplete download" }
        }
        file
    }

    /**
     * Opens the system installer for [apk]. On Android 8+ the TV first needs "Install unknown apps" for
     * Tiviate: when it isn't granted yet, that settings page opens instead and this returns false.
     */
    fun install(context: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val pages = listOf(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), // Fire OS: Developer options → Install unknown apps
                Intent(Settings.ACTION_SETTINGS),
            )
            for (page in pages) {
                if (runCatching { context.startActivity(page.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) break
            }
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return true
    }
}
