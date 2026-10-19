package space.nimbusgo.sigma.engine

import android.content.Context
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import kotlinx.coroutines.guava.await
import space.nimbusgo.sigma.SigmaException

/** Sigma's engine in androidx.javascriptengine: a V8 isolate in a sandboxed process. */
internal class SandboxRuntime private constructor(
    private val sandbox: JavaScriptSandbox,
    private val isolate: JavaScriptIsolate,
) : JsRuntime {

    override suspend fun evaluate(code: String): String = try {
        isolate.evaluateJavaScriptAsync(code).await()
    } catch (e: Exception) {
        throw SigmaException.Engine(cleanMessage(e.message))
    }

    override fun close() {
        runCatching { isolate.close() }
        runCatching { sandbox.close() }
    }

    companion object {
        suspend fun createOrNull(context: Context): SandboxRuntime? {
            if (!JavaScriptSandbox.isSupported()) return null
            return try {
                val sandbox = JavaScriptSandbox.createConnectedInstanceAsync(context.applicationContext).await()
                // Awaited promises are how async engine calls (file import and export) return.
                if (!sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROMISE_RETURN)) {
                    sandbox.close()
                    return null
                }
                SandboxRuntime(sandbox, sandbox.createIsolate())
            } catch (e: Exception) {
                null
            }
        }

        fun cleanMessage(raw: String?): String =
            (raw ?: "The engine failed.").substringAfterLast("Error: ").trim().ifEmpty { "The engine failed." }
    }
}
