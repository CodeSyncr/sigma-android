package space.nimbusgo.sigma.demo

import android.content.Context
import space.nimbusgo.sigma.SigmaClient

/**
 * Where the demo's cloud is and its Sigma key. The demo keeps the key on the
 * device so it runs on its own; a real app keeps it on its server and asks
 * the server for links.
 */
object Settings {
    private fun prefs(c: Context) = c.getSharedPreferences("sigma-demo", Context.MODE_PRIVATE)

    fun cloud(c: Context): String = prefs(c).getString("cloud", null) ?: "http://localhost:3333"
    fun key(c: Context): String = prefs(c).getString("key", null) ?: ""

    fun save(c: Context, cloud: String, key: String) {
        prefs(c).edit().putString("cloud", cloud.trim()).putString("key", key.trim()).apply()
    }

    fun client(c: Context): SigmaClient? = key(c).takeIf { it.isNotEmpty() }?.let { SigmaClient(it, cloud(c)) }
}
