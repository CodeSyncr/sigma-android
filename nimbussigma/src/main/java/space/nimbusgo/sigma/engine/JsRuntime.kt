package space.nimbusgo.sigma.engine

import android.content.Context

/**
 * Somewhere Sigma's engine can run: a JavaScript runtime that evaluates an
 * expression and returns the JSON text of its (awaited) result.
 */
internal interface JsRuntime {
    /** Evaluates `code` and returns its result as a string. Promises are awaited. */
    suspend fun evaluate(code: String): String
    fun close()

    companion object {
        /**
         * Android's sandboxed V8 (androidx.javascriptengine) where the device's
         * WebView provides it with promise support; a hidden WebView's V8
         * otherwise. Both have Intl, which Sigma's number formats need.
         */
        suspend fun create(context: Context): JsRuntime =
            SandboxRuntime.createOrNull(context) ?: WebViewRuntime.create(context)
    }
}
