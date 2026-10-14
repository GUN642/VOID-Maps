package app.voidmaps.data

import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class Release(
    val version: String,
    val title: String,
    val notes: String,
    val publishedAt: String,
    val apkUrl: String?,
    val apkSize: Long,
)

/** Checks the GitHub releases of this app and downloads new APKs. */
object Updater {
    private const val REPO = "GUN642/VOID-Maps"
    private const val API = "https://api.github.com/repos/$REPO/releases?per_page=30"

    /** Parses "v1.0.12" / "1.0.12" into comparable numbers. */
    fun parts(version: String): List<Int> =
        version.trim().removePrefix("v").split('.', '-').mapNotNull { it.toIntOrNull() }

    fun isNewer(candidate: String, current: String): Boolean {
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "VOID-Maps")
        return conn
    }

    /** All published releases (no pre-releases), newest version first. */
    fun fetchReleases(): List<Release> {
        val conn = open(API)
        try {
            if (conn.responseCode != 200) throw IOException("GitHub antwortet mit ${conn.responseCode}")
            val json = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
            val out = ArrayList<Release>()
            for (i in 0 until json.length()) {
                val r = json.getJSONObject(i)
                if (r.optBoolean("draft") || r.optBoolean("prerelease")) continue
                val tag = r.optString("tag_name")
                if (parts(tag).isEmpty()) continue
                val assets = r.optJSONArray("assets") ?: JSONArray()
                var apkUrl: String? = null
                var apkSize = 0L
                for (j in 0 until assets.length()) {
                    val a = assets.getJSONObject(j)
                    if (a.optString("name").endsWith(".apk")) {
                        apkUrl = a.optString("browser_download_url")
                        apkSize = a.optLong("size")
                        break
                    }
                }
                out += Release(
                    version = tag.removePrefix("v"),
                    title = r.optString("name").ifBlank { tag },
                    notes = cleanNotes(r.optString("body")),
                    publishedAt = r.optString("published_at"),
                    apkUrl = apkUrl,
                    apkSize = apkSize,
                )
            }
            return out.sortedWith { x, y -> if (isNewer(x.version, y.version)) -1 else if (isNewer(y.version, x.version)) 1 else 0 }
        } finally {
            conn.disconnect()
        }
    }

    /** Keeps only the human readable change notes from a release body. */
    private fun cleanNotes(body: String): String = body.lines()
        .filterNot { it.startsWith("Co-Authored-By", ignoreCase = true) || it.startsWith("Claude-Session") }
        .joinToString("\n").trim()

    fun download(url: String, target: File, onProgress: (done: Long, total: Long) -> Unit, isCancelled: () -> Boolean) {
        val conn = open(url)
        conn.setRequestProperty("Accept", "application/octet-stream")
        try {
            if (conn.responseCode !in 200..299) throw IOException("Download fehlgeschlagen (${conn.responseCode})")
            val total = conn.contentLengthLong
            target.parentFile?.mkdirs()
            conn.inputStream.use { input ->
                FileOutputStream(target).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        if (isCancelled()) throw IOException("Abgebrochen")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
        } catch (e: Throwable) {
            target.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }
}
