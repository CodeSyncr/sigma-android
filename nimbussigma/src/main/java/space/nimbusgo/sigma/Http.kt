package space.nimbusgo.sigma

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal class HttpResponse(val status: Int, val body: ByteArray, val headers: Map<String, List<String>>) {
    val text: String get() = body.toString(Charsets.UTF_8)
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

    /** The server's "error" message, if the body is JSON with one. */
    val errorMessage: String
        get() = runCatching { JSONObject(text).optString("error") }.getOrDefault("")
}

/** A small HTTP client on HttpURLConnection, so the SDK pulls in no networking library. */
internal object Http {
    const val USER_AGENT = "nimbus-sigma-android/${NimbusSigma.VERSION}"

    suspend fun request(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        followRedirects: Boolean = true,
        timeoutMillis: Int = 30_000,
    ): HttpResponse = withContext(Dispatchers.IO) {
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw SigmaException.Network(e.message ?: "Could not reach $url")
        }
        try {
            conn.requestMethod = method
            conn.instanceFollowRedirects = followRedirects
            conn.connectTimeout = 15_000
            conn.readTimeout = timeoutMillis
            conn.setRequestProperty("User-Agent", USER_AGENT)
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            HttpResponse(status, bytes, conn.headerFields.filterKeys { it != null })
        } catch (e: IOException) {
            throw SigmaException.Network(e.message ?: "The request failed.")
        } finally {
            conn.disconnect()
        }
    }
}

/** Facts about the SDK itself. */
object NimbusSigma {
    const val VERSION = "1.0.0"
}
