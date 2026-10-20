package space.nimbusgo.sigma

import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID

/**
 * The Sigma documents API: upload spreadsheets, mint signed links, and
 * download them again with their edits.
 *
 * **Do not ship this client inside an Android app.** An `sgm_` key can read
 * and delete every document on its account, and anything compiled into an app
 * can be pulled back out of it. Keep the key on your server, mint links there,
 * and hand the app only the link — which is all [SigmaSession] needs.
 * `SigmaClient` is for server-side Kotlin (Ktor, Spring), tools, and prototypes.
 *
 * @param apiKey A Sigma key (`sgm_live_…` or `sgm_test_…`) from Nimbus Cloud › API & CLI Keys.
 * @param baseUrl Where Nimbus Cloud is served.
 */
class SigmaClient(private val apiKey: String, val baseUrl: String = "https://nimbusgo.space") {

    /**
     * Uploads a spreadsheet (.xlsx or .csv, up to 5 MB) and returns it with a signed link.
     *
     * @param idempotencyKey Send the same key when retrying, and a retried upload returns the original document.
     * @param retainDays Delete the document automatically after this many days.
     * @param ai Whether the returned link offers Vani (it still needs a plan that includes it).
     */
    suspend fun upload(
        data: ByteArray,
        filename: String,
        title: String? = null,
        mode: SigmaLinkMode = SigmaLinkMode.EDIT,
        linkLifetimeSeconds: Int? = null,
        retainDays: Int? = null,
        idempotencyKey: String? = null,
        ai: Boolean = true,
    ): SigmaDocument {
        val boundary = "sigma-" + UUID.randomUUID()
        val out = java.io.ByteArrayOutputStream()
        fun write(s: String) = out.write(s.toByteArray())
        title?.let { write("--$boundary\r\nContent-Disposition: form-data; name=\"title\"\r\n\r\n$it\r\n") }
        val mime = if (filename.lowercase().endsWith(".csv")) SigmaFormat.CSV.mimeType else SigmaFormat.XLSX.mimeType
        write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${filename.replace("\"", "'")}\"\r\nContent-Type: $mime\r\n\r\n")
        out.write(data)
        write("\r\n--$boundary--\r\n")
        val headers = mutableMapOf("Content-Type" to "multipart/form-data; boundary=$boundary")
        idempotencyKey?.let { headers["Idempotency-Key"] = it }
        return SigmaDocument.from(json("documents", linkQuery(mode, linkLifetimeSeconds, retainDays, ai), "POST", out.toByteArray(), headers))
    }

    /** Creates an empty sheet, ready to open in the editor. */
    suspend fun createBlank(
        title: String,
        format: SigmaFormat = SigmaFormat.XLSX,
        mode: SigmaLinkMode = SigmaLinkMode.EDIT,
        linkLifetimeSeconds: Int? = null,
        retainDays: Int? = null,
        ai: Boolean = true,
    ): SigmaDocument {
        val body = JSONObject().put("title", title).put("blank", format.value).toString().toByteArray()
        return SigmaDocument.from(json("documents", linkQuery(mode, linkLifetimeSeconds, retainDays, ai), "POST", body, mapOf("Content-Type" to "application/json")))
    }

    /** The account's most recent documents, newest first. */
    suspend fun documents(limit: Int = 50): List<SigmaDocument> {
        val list = json("documents", mapOf("limit" to limit.toString())).getJSONArray("documents")
        return (0 until list.length()).map { SigmaDocument.from(list.getJSONObject(it)) }
    }

    /** One document's details. */
    suspend fun document(id: Int): SigmaDocument = SigmaDocument.from(json("documents/$id"))

    /**
     * A fresh signed link for a document — what your server hands the app.
     *
     * @param lifetimeSeconds How long the link may be used to open the sheet:
     *   one minute to seven days, fifteen minutes by default.
     * @param ai Whether the person the link is for gets Vani. Pass false for
     *   someone who has not paid you for AI; it is signed into the link. Vani
     *   also needs a Nimbus Cloud plan that includes it.
     */
    suspend fun link(id: Int, mode: SigmaLinkMode = SigmaLinkMode.EDIT, lifetimeSeconds: Int? = null, ai: Boolean = true): SigmaLink =
        SigmaLink.from(json("documents/$id/link", linkQuery(mode, lifetimeSeconds, null, ai), "POST"))

    /** The document's file as it is now, edits included, in its own format. */
    suspend fun content(id: Int): ByteArray = raw("documents/$id/content").body

    /**
     * Sigma's own JSON for a sheet that has been opened in the editor. Fails
     * with a server error (409) for a sheet that has never been opened.
     */
    suspend fun workbook(id: Int): String = raw("documents/$id/content", mapOf("format" to "workbook")).text

    /** Deletes a document and its content. */
    suspend fun delete(id: Int) {
        raw("documents/$id", method = "DELETE")
    }

    /** Invalidates every link minted so far for this account. */
    suspend fun revokeAllLinks() {
        raw("links/revoke", method = "POST")
    }

    /** This month's allowance. */
    suspend fun usage(): SigmaUsage = SigmaUsage.from(json("usage"))

    // Plumbing

    private fun linkQuery(mode: SigmaLinkMode, lifetime: Int?, retainDays: Int?, ai: Boolean): Map<String, String> = buildMap {
        put("mode", mode.value)
        if (!ai) put("ai", "off")
        lifetime?.let { put("ttl", it.toString()) }
        retainDays?.let { put("retain_days", it.toString()) }
    }

    private suspend fun json(
        path: String,
        query: Map<String, String> = emptyMap(),
        method: String = "GET",
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
    ): JSONObject {
        val res = raw(path, query, method, body, headers)
        return try {
            JSONObject(res.text)
        } catch (e: org.json.JSONException) {
            throw SigmaException.Server(200, "Nimbus Cloud sent an answer this version cannot read.")
        }
    }

    private suspend fun raw(
        path: String,
        query: Map<String, String> = emptyMap(),
        method: String = "GET",
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse {
        val q = if (query.isEmpty()) "" else "?" + query.entries.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
        val all = mapOf("Authorization" to "Bearer $apiKey", "Accept" to "application/json") + headers
        val res = Http.request(baseUrl.trimEnd('/') + "/api/v1/sigma/" + path + q, method, all, body)
        if (res.status !in 200..299) throw SigmaException.from(res.status, res.errorMessage)
        return res
    }
}
