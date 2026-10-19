package space.nimbusgo.sigma.demo

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import space.nimbusgo.sigma.SigmaDocument

/** Your sheets: list them, create one, open one. */
class MainActivity : ComponentActivity() {
    private lateinit var list: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // `adb shell am start … --es cloud http://localhost:3343 --es key sgm_test_…` fills in the settings.
        intent.getStringExtra("key")?.let { Settings.save(this, intent.getStringExtra("cloud") ?: Settings.cloud(this), it) }
        title = "Sigma sheets"

        val pad = dp(16)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(this).apply { setPadding(pad, pad, pad, pad); setTextColor(Color.GRAY) }
        val newSheet = Button(this).apply { text = "New sheet"; setOnClickListener { createSheet() } }
        val upload = Button(this).apply { text = "Sample budget"; setOnClickListener { uploadSample() } }
        val buttons = LinearLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(newSheet)
            addView(upload)
        }
        setContentView(ScrollView(this).apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(buttons)
                addView(status)
                addView(list)
            })
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val client = Settings.client(this)
        list.removeAllViews()
        if (client == null) {
            status.text = "Tap Settings and enter a Sigma key (sgm_test_… from Nimbus Cloud › API & CLI Keys)."
            return
        }
        status.text = "Loading…"
        lifecycleScope.launch {
            try {
                val docs = client.documents()
                status.text = if (docs.isEmpty()) "No sheets yet. Create one." else "${docs.size} sheets on ${Settings.cloud(this@MainActivity)}"
                docs.forEach { list.addView(row(it)) }
            } catch (e: Exception) {
                status.text = "Could not load sheets: ${e.message}"
            }
        }
    }

    private fun row(doc: SigmaDocument) = TextView(this).apply {
        text = "${doc.title.ifEmpty { doc.filename }}\n${doc.filename} · #${doc.id}"
        textSize = 16f
        setPadding(dp(16), dp(12), dp(16), dp(12))
        setBackgroundResource(android.R.drawable.list_selector_background)
        setOnClickListener { open(doc.id) }
    }

    private fun open(id: Int) {
        startActivity(Intent(this, SheetActivity::class.java).putExtra("id", id))
    }

    private fun createSheet() {
        val client = Settings.client(this) ?: return settings()
        val name = EditText(this).apply { hint = "Sheet name"; setText("Untitled sheet") }
        AlertDialog.Builder(this).setTitle("New sheet").setView(name)
            .setPositiveButton("Create") { _, _ ->
                lifecycleScope.launch {
                    runCatching { client.createBlank(name.text.toString()) }
                        .onSuccess { open(it.id) }
                        .onFailure { status.text = "Could not create: ${it.message}" }
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun uploadSample() {
        val client = Settings.client(this) ?: return settings()
        val csv = "Item,Category,Cost\nRent,Home,1200\nGroceries,Food,340\nInternet,Home,45\nCinema,Fun,28\nTotal,,=SUM(C2:C5)\n"
        lifecycleScope.launch {
            runCatching { client.upload(csv.toByteArray(), "budget.csv", title = "Budget") }
                .onSuccess { open(it.id) }
                .onFailure { status.text = "Could not upload: ${it.message}" }
        }
    }

    private fun settings() {
        val cloud = EditText(this).apply { setText(Settings.cloud(this@MainActivity)); inputType = InputType.TYPE_TEXT_VARIATION_URI }
        val key = EditText(this).apply { setText(Settings.key(this@MainActivity)); hint = "sgm_test_…" }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(TextView(this@MainActivity).apply { text = "Nimbus Cloud address" })
            addView(cloud)
            addView(TextView(this@MainActivity).apply { text = "Sigma key" })
            addView(key)
        }
        AlertDialog.Builder(this).setTitle("Settings").setView(box)
            .setPositiveButton("Save") { _, _ -> Settings.save(this, cloud.text.toString(), key.text.toString()); refresh() }
            .setNegativeButton("Cancel", null).show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Settings").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) settings()
        return true
    }
}

internal fun Activity.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
