package space.nimbusgo.sigma

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * One sheet from the Sigma API, open natively: the signed link your server
 * minted becomes a [SigmaWorkbook] here, and every change is saved back to the
 * document — the file in its own format, and Sigma's workbook beside it.
 *
 * ```kotlin
 * val session = SigmaSession(context) { myApi.sheetLink(id = 42) }
 * ```
 *
 * @param link Asked for a signed link when the sheet opens, and again when one
 *   expires. Your server mints it with its Sigma key.
 */
class SigmaSession(context: Context, private val link: suspend () -> String) {
    sealed class Phase {
        data object Loading : Phase()
        data object Ready : Phase()
        data class Failed(val error: SigmaException) : Phase()
    }

    /** The sheet that is open, as Sigma described it. */
    data class Document(
        val id: Int,
        val title: String,
        val filename: String,
        /** The format the file is kept in; every save rewrites it in this format. */
        val format: SigmaFormat,
        /** Whether the link can edit, or only view. */
        val mode: SigmaLinkMode,
        /** Opened with a test-mode API key (capped, and deleted after a week). */
        val test: Boolean,
    )

    sealed class SaveState {
        data object Saved : SaveState()
        data object Unsaved : SaveState()
        data object Saving : SaveState()
        data class Failed(val error: SigmaException) : SaveState()
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _phase = MutableStateFlow<Phase>(Phase.Loading)
    private val _workbook = MutableStateFlow<SigmaWorkbook?>(null)
    private val _document = MutableStateFlow<Document?>(null)
    private val _title = MutableStateFlow("")
    private val _saveState = MutableStateFlow<SaveState>(SaveState.Saved)
    private val _lastSaved = MutableStateFlow<Long?>(null)
    private val _aiAvailable = MutableStateFlow(false)

    val phase: StateFlow<Phase> = _phase.asStateFlow()
    val workbook: StateFlow<SigmaWorkbook?> = _workbook.asStateFlow()
    val document: StateFlow<Document?> = _document.asStateFlow()
    /** The sheet's name; set it with [rename]. */
    val title: StateFlow<String> = _title.asStateFlow()
    val saveState: StateFlow<SaveState> = _saveState.asStateFlow()
    /** When the last save landed, in epoch milliseconds. */
    val lastSaved: StateFlow<Long?> = _lastSaved.asStateFlow()
    /** Whether the document's owner can use Vani AI here. */
    val aiAvailable: StateFlow<Boolean> = _aiAvailable.asStateFlow()

    /** Milliseconds between the last change and the save. */
    var saveDelayMillis: Long = 1_200

    private var access: Access? = null
    private var saveJob: Job? = null
    private var openJob: Job? = null
    private var started = false
    private val saving = Mutex()

    /** Where the editor's API lives, and the token that opens it. */
    internal data class Access(val base: String, val id: Int, val token: String)

    /** Opens the sheet. [SigmaSheetView] calls this when it is attached. */
    fun start() {
        if (started) return
        started = true
        openJob = scope.launch { open() }
    }

    /** Opens the sheet again from a fresh link. */
    fun reload() {
        started = true
        openJob?.cancel()
        openJob = scope.launch { open() }
    }

    /** Renames the sheet; the new title is saved with the next save. */
    fun rename(title: String) {
        if (_title.value == title) return
        _title.value = title
        if (_phase.value == Phase.Ready) scheduleSave()
    }

    /** Saves now, without waiting for the pause after typing. */
    suspend fun save() {
        saveJob?.cancel()
        persist()
    }

    /** Stops the engine and any pending work. Save first if a change may be pending. */
    fun close() {
        scope.cancel()
        _workbook.value?.close()
        _workbook.value = null
    }

    // Opening

    private suspend fun open() {
        _phase.value = Phase.Loading
        try {
            val a = resolve()
            access = a
            val info = try {
                JSONObject(send(request(a, "")).text)
            } catch (e: org.json.JSONException) {
                throw SigmaException.Server(200, "Sigma sent an answer this version cannot read.")
            }
            val expected = info.optJSONObject("engine")
                ?: throw SigmaException.Server(200, "This Sigma server is too old for NimbusSigma ${NimbusSigma.VERSION}.")
            val source = loadEngine(a, expected.getString("version"), expected.getString("sha256"))
            val format = SigmaFormat.of(info.optString("format")) ?: SigmaFormat.CSV
            val data = info.optString("data")
            val filename = info.optString("filename")
            val book = if (data.isEmpty()) {
                val file = send(request(a, "/source")).body
                SigmaWorkbook.open(appContext, source, null, file, format, filename)
            } else {
                SigmaWorkbook.open(appContext, source, data, null, format, filename)
            }
            book.onChange = { scheduleSave() }
            _workbook.value?.close()
            _document.value = Document(
                id = info.getInt("id"),
                title = info.optString("title"),
                filename = filename,
                format = format,
                mode = SigmaLinkMode.of(info.optString("mode")) ?: SigmaLinkMode.VIEW,
                test = info.optBoolean("test"),
            )
            _workbook.value = book
            _title.value = info.optString("title")
            _aiAvailable.value = info.optBoolean("ai")
            _saveState.value = SaveState.Saved
            _phase.value = Phase.Ready
        } catch (e: SigmaException) {
            _phase.value = Phase.Failed(e)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _phase.value = Phase.Failed(SigmaException.Network(e.message ?: "The sheet could not open."))
        }
    }

    /**
     * Follows the signed link to the cloud, which checks it (and counts the
     * open) and answers with a redirect to Sigma carrying an editing token.
     */
    private suspend fun resolve(): Access {
        val url = link()
        val res = Http.request(url, followRedirects = false)
        val location = res.header("Location")
        if (res.status in 300..399 && location != null) {
            val target = runCatching { URI(url).resolve(location).toString() }.getOrNull()
            target?.let { accessFrom(it) }?.let { return it }
        }
        throw when (res.status) {
            402 -> SigmaException.QuotaExceeded("This account has used this month's allowance of sheet opens (or a test key's cap). Its owner can upgrade on Nimbus Cloud.")
            410 -> SigmaException.LinkExpired("This link has expired.")
            403 -> SigmaException.LinkInvalid("This link is not valid or was revoked.")
            404 -> SigmaException.NotFound("This sheet does not exist or was deleted.")
            else -> SigmaException.Server(res.status, "The link did not open a Sigma sheet.")
        }
    }

    /**
     * Sigma's engine for this session: downloaded from Sigma with the
     * session's token and checked against the checksum Sigma reported. It is
     * kept in memory only, never written to disk.
     */
    private suspend fun loadEngine(a: Access, version: String, sha256: String): String {
        EngineCache.source(version, sha256)?.let { return it }
        val data = send(request(a, "/engine")).body
        if (sha256Hex(data) != sha256.lowercase()) throw SigmaException.Engine("The Sigma engine did not arrive intact. Try again.")
        val source = data.toString(Charsets.UTF_8)
        EngineCache.store(source, version, sha256)
        return source
    }

    // Saving

    private fun scheduleSave() {
        if (_document.value?.mode != SigmaLinkMode.EDIT) return
        _saveState.value = SaveState.Unsaved
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(saveDelayMillis)
            runCatching { persist() }
        }
    }

    private suspend fun persist(retried: Boolean = false): Unit = saving.withLock { persistLocked(retried) }

    private suspend fun persistLocked(retried: Boolean) {
        val workbook = _workbook.value ?: throw SigmaException.NotReady()
        val document = _document.value ?: throw SigmaException.NotReady()
        val a = access ?: throw SigmaException.NotReady()
        if (document.mode != SigmaLinkMode.EDIT) throw SigmaException.Editor("This link can view the sheet but not change it.")
        val revision = workbook.revision.value
        _saveState.value = SaveState.Saving
        try {
            val file = workbook.export(document.format)
            val body = JSONObject()
                .put("title", _title.value)
                .put("data", workbook.serialize())
                .put("file", Base64.encodeToString(file, Base64.NO_WRAP))
            send(request(a, "", "PUT", body.toString().toByteArray(), "application/json"))
            _lastSaved.value = System.currentTimeMillis()
            // Changes made while the request was out are still unsaved.
            if (workbook.revision.value == revision) {
                _saveState.value = SaveState.Saved
            } else {
                scheduleSave()
            }
        } catch (e: SigmaException.LinkExpired) {
            if (retried) {
                _saveState.value = SaveState.Failed(e)
                throw e
            }
            // The editing session ended (it lasts eight hours): get a fresh
            // token and save again. The workbook in memory is kept.
            try {
                access = resolve()
                persistLocked(retried = true)
            } catch (e2: SigmaException) {
                _saveState.value = SaveState.Failed(e2)
                throw e2
            }
        } catch (e: SigmaException) {
            _saveState.value = SaveState.Failed(e)
            throw e
        }
    }

    /** The sheet as a file in the format it is kept in (or another), named after its title. */
    suspend fun export(format: SigmaFormat? = null): SigmaFile {
        val workbook = _workbook.value ?: throw SigmaException.NotReady()
        val document = _document.value ?: throw SigmaException.NotReady()
        val f = format ?: document.format
        val data = workbook.export(f)
        val base = _title.value.ifEmpty { document.filename }
            .replace(Regex("\\.(xlsx|xlsm|csv)$", RegexOption.IGNORE_CASE), "")
            .replace("/", "-")
        return SigmaFile(data, "$base.${f.fileExtension}", f)
    }

    // HTTP

    internal val currentAccess: Access? get() = access

    internal class EmbedRequest(val url: String, val method: String, val headers: Map<String, String>, val body: ByteArray?)

    internal fun request(a: Access, path: String, method: String = "GET", body: ByteArray? = null, contentType: String? = null): EmbedRequest {
        val headers = mutableMapOf("X-Sigma-Embed" to a.token)
        contentType?.let { headers["Content-Type"] = it }
        return EmbedRequest(a.base.trimEnd('/') + "/api/embed/${a.id}" + path, method, headers, body)
    }

    internal suspend fun send(req: EmbedRequest, timeoutMillis: Int = 30_000): HttpResponse {
        val res = Http.request(req.url, req.method, req.headers, req.body, timeoutMillis = timeoutMillis)
        if (res.status !in 200..299) {
            val message = res.errorMessage
            throw SigmaException.from(res.status, message.ifEmpty { "Sigma answered ${res.status}." })
        }
        return res
    }

    internal companion object {
        /** Reads ".../embed/<id>?t=<token>". */
        fun accessFrom(url: String): Access? {
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            val parts = (uri.rawPath ?: "").split("/").filter { it.isNotEmpty() }
            val i = parts.lastIndexOf("embed")
            if (i < 0 || i + 1 >= parts.size) return null
            val id = parts[i + 1].toIntOrNull() ?: return null
            val token = (uri.rawQuery ?: "").split("&").firstNotNullOfOrNull { pair ->
                val kv = pair.split("=", limit = 2)
                if (kv[0] == "t" && kv.size == 2) URLDecoder.decode(kv[1], "UTF-8") else null
            } ?: return null
            val prefix = if (i == 0) "" else "/" + parts.subList(0, i).joinToString("/")
            val port = if (uri.port == -1) "" else ":${uri.port}"
            return Access("${uri.scheme}://${uri.host}$port$prefix", id, token)
        }

        fun sha256Hex(data: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    }
}

/**
 * The engine sessions downloaded in this process, by version: in memory only,
 * so it is fetched again with a valid session after every launch.
 */
internal object EngineCache {
    private data class Entry(val version: String, val sha256: String, val source: String)

    @Volatile private var entry: Entry? = null

    fun source(version: String, sha256: String): String? = entry?.takeIf { it.version == version && it.sha256 == sha256 }?.source

    fun store(source: String, version: String, sha256: String) {
        entry = Entry(version, sha256, source)
    }

    @androidx.annotation.VisibleForTesting
    fun cachedSourceForTests(): String? = entry?.source
}
