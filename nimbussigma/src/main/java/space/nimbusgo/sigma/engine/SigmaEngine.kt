package space.nimbusgo.sigma.engine

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import space.nimbusgo.sigma.SigmaException

/**
 * Sigma's own spreadsheet engine — the same code the web editor runs. The SDK
 * does not contain it: a session downloads it from Sigma for a sheet opened
 * through the API, and it lives in memory only. Calls run one at a time, in
 * order, because the workbook they act on is shared.
 */
internal class SigmaEngine private constructor(private val runtime: JsRuntime) {
    private val lock = Mutex()
    var version: String = ""
        private set

    /** Calls SigmaCore.<method>(args…) and returns its result as JSON text ("null" for none). */
    suspend fun call(method: String, vararg args: Any?): String = lock.withLock {
        val list = JSONArray()
        args.forEach { list.put(JSONObject.wrap(it) ?: JSONObject.NULL) }
        val code = "(async () => { const r = await SigmaCore[" + JSONObject.quote(method) + "](..." + list.toString() +
            "); return JSON.stringify(r === undefined ? null : r) })()"
        runtime.evaluate(code)
    }

    suspend fun callObject(method: String, vararg args: Any?): JSONObject = JSONObject(call(method, *args))
    suspend fun callArray(method: String, vararg args: Any?): JSONArray = JSONArray(call(method, *args))
    suspend fun callString(method: String, vararg args: Any?): String = JSONArray("[" + call(method, *args) + "]").optString(0)
    suspend fun callInt(method: String, vararg args: Any?): Int = JSONArray("[" + call(method, *args) + "]").optInt(0)
    suspend fun callBool(method: String, vararg args: Any?): Boolean = JSONArray("[" + call(method, *args) + "]").optBoolean(0)

    fun close() = runtime.close()

    companion object {
        /** Starts the engine from its source, as the server handed it to a session. */
        suspend fun start(context: Context, source: String): SigmaEngine {
            val runtime = JsRuntime.create(context)
            try {
                runtime.evaluate(source + "\n;typeof SigmaCore")
                val engine = SigmaEngine(runtime)
                engine.version = engine.callStringProperty("version")
                if (engine.version.isEmpty()) throw SigmaException.Engine("The Sigma engine did not start.")
                return engine
            } catch (e: SigmaException) {
                runtime.close()
                throw SigmaException.Engine("The Sigma engine could not start: ${e.message}")
            }
        }
    }

    private suspend fun callStringProperty(name: String): String =
        JSONArray("[" + runtime.evaluate("JSON.stringify(SigmaCore[" + JSONObject.quote(name) + "])") + "]").optString(0)
}
