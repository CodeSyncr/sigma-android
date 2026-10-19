package space.nimbusgo.sigma.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import space.nimbusgo.sigma.SigmaSession
import space.nimbusgo.sigma.SigmaSheetView

/** One sheet, in the SDK's native editor. */
class SheetActivity : ComponentActivity() {
    private lateinit var session: SigmaSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra("id", 0)
        val client = Settings.client(this)!!
        // In a real app this asks your server, which mints the link with its key.
        session = SigmaSession(this) { client.link(id).url }
        setContentView(SigmaSheetView(this).apply { open(session) })
        lifecycleScope.launch { session.title.collect { title = it.ifEmpty { "Sheet" } } }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Save anything typed in the last moment, then stop the engine.
        val s = session
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        GlobalScope.launch(Dispatchers.Main) {
            runCatching { s.save() }
            s.close()
        }
    }
}
