package space.nimbusgo.sigma.engine

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import space.nimbusgo.sigma.SigmaException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sigma's engine in a hidden WebView, for devices whose WebView does not
 * offer the sandboxed runtime. Nothing is shown; the page is blank and only
 * runs the engine. Results come back through a JavaScript interface, so
 * promises are awaited the same way as in the sandbox.
 */
internal class WebViewRuntime private constructor(private val webView: WebView) : JsRuntime {
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<String>>()
    private val ids = AtomicInteger()

    inner class Host {
        @JavascriptInterface
        fun resolve(id: Int, value: String) {
            pending.remove(id)?.complete(value)
        }

        @JavascriptInterface
        fun reject(id: Int, message: String) {
            pending.remove(id)?.completeExceptionally(SigmaException.Engine(SandboxRuntime.cleanMessage(message)))
        }
    }

    override suspend fun evaluate(code: String): String {
        val id = ids.incrementAndGet()
        val done = CompletableDeferred<String>()
        pending[id] = done
        // Global eval runs `code` as a script (so the engine's declarations
        // become globals) and returns its last value; quoting it means a
        // syntax error rejects the promise instead of hanging it.
        val wrapped = "Promise.resolve().then(() => (0, eval)(" + quote(code) + ")).then(" +
            "v => __sigmaHost.resolve($id, String(v)), " +
            "e => __sigmaHost.reject($id, String(e && e.message || e)))"
        withContext(Dispatchers.Main) { webView.evaluateJavascript(wrapped, null) }
        return done.await()
    }

    override fun close() {
        pending.values.forEach { it.completeExceptionally(SigmaException.Engine("The engine was closed.")) }
        pending.clear()
        webView.post { webView.destroy() }
    }

    companion object {
        @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
        suspend fun create(context: Context): WebViewRuntime = withContext(Dispatchers.Main) {
            val webView = WebView(context.applicationContext)
            webView.settings.javaScriptEnabled = true
            webView.settings.allowFileAccess = false
            webView.settings.allowContentAccess = false
            val runtime = WebViewRuntime(webView)
            webView.addJavascriptInterface(runtime.Host(), "__sigmaHost")
            val loaded = CompletableDeferred<Unit>()
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    loaded.complete(Unit)
                }
            }
            // A blank page with no origin to speak of: the engine needs no network.
            webView.loadDataWithBaseURL(null, "<!doctype html><title>Sigma</title>", "text/html", "utf-8", null)
            loaded.await()
            runtime
        }

        /** JSON-quotes a string for embedding in JavaScript source. */
        fun quote(s: String): String = JSONObject.quote(s)
    }
}
