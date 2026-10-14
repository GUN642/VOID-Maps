package app.voidmaps.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Tiny HTTP helper – the app only needs a handful of JSON GET/POST calls. */
object Http {
    const val USER_AGENT = "VOID-Maps/1.0 (private Android navigation app)"

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun get(url: String, timeoutMs: Int = 15_000): String = request(url, null, null, timeoutMs)

    fun post(url: String, body: String, contentType: String, timeoutMs: Int = 20_000): String =
        request(url, body, contentType, timeoutMs)

    private fun request(url: String, body: String?, contentType: String?, timeoutMs: Int): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs * 2
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Accept-Language", "de-DE,de;q=0.9")
            if (body != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", contentType ?: "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw HttpException(code, text.take(300))
            return text
        } finally {
            conn.disconnect()
        }
    }
}

class HttpException(val code: Int, val body: String) : IOException("HTTP $code")
