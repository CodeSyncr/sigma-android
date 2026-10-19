package space.nimbusgo.sigma.demo

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import space.nimbusgo.sigma.SigmaAddress
import space.nimbusgo.sigma.SigmaCell
import space.nimbusgo.sigma.SigmaSession
import space.nimbusgo.sigma.SigmaWorkbook

/**
 * One sheet, opened through SigmaSession. The table here is a stand-in: the
 * SDK's native grid replaces it.
 */
class SheetActivity : ComponentActivity() {
    private lateinit var session: SigmaSession
    private lateinit var status: TextView
    private lateinit var table: LinearLayout
    private val rows = 40
    private val cols = 8

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra("id", 0)
        val client = Settings.client(this)!!
        // In a real app this asks your server, which mints the link with its key.
        session = SigmaSession(this) { client.link(id).url }

        status = TextView(this).apply { setPadding(dp(16), dp(8), dp(16), dp(8)); setTextColor(Color.DKGRAY) }
        table = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(ScrollView(this@SheetActivity).apply {
                addView(HorizontalScrollView(this@SheetActivity).apply { addView(table) })
            })
        })

        lifecycleScope.launch {
            combine(session.phase, session.saveState, session.title, session.document) { p, s, t, d -> listOf(p, s, t, d) }.collect {
                title = session.title.value.ifEmpty { "Sheet" }
                status.text = when (val p = session.phase.value) {
                    SigmaSession.Phase.Loading -> "Opening…"
                    is SigmaSession.Phase.Failed -> "Could not open: ${p.error.message}"
                    SigmaSession.Phase.Ready -> buildString {
                        if (session.document.value?.test == true) append("TEST MODE · ")
                        append(
                            when (val s = session.saveState.value) {
                                SigmaSession.SaveState.Saved -> "Saved"
                                SigmaSession.SaveState.Unsaved -> "Unsaved changes"
                                SigmaSession.SaveState.Saving -> "Saving…"
                                is SigmaSession.SaveState.Failed -> "Save failed: ${s.error.message}"
                            }
                        )
                        append(" · tap a cell to edit")
                    }
                }
            }
        }
        lifecycleScope.launch {
            session.workbook.collect { book -> book?.let { b -> b.revision.collect { draw(b) } } }
        }
        session.start()
    }

    private suspend fun draw(book: SigmaWorkbook) {
        val cells = book.cells(0 until rows, 0 until cols).associateBy { it.row to it.column }
        table.removeAllViews()
        table.addView(LinearLayout(this).apply {
            addView(header(""))
            for (c in 0 until cols) addView(header(SigmaAddress.columnName(c)))
        })
        for (r in 0 until rows) {
            table.addView(LinearLayout(this).apply {
                addView(header("${r + 1}"))
                for (c in 0 until cols) addView(cell(book, r, c, cells[r to c]))
            })
        }
    }

    private fun header(text: String) = TextView(this).apply {
        this.text = text
        gravity = Gravity.CENTER
        setTextColor(Color.DKGRAY)
        setBackgroundColor(Color.rgb(241, 243, 244))
        layoutParams = LinearLayout.LayoutParams(if (text.isEmpty() || text[0].isDigit()) dp(44) else dp(100), dp(30)).apply { setMargins(0, 0, 1, 1) }
    }

    private fun cell(book: SigmaWorkbook, r: Int, c: Int, shown: SigmaCell?) = TextView(this).apply {
        text = shown?.text ?: ""
        maxLines = 1
        setPadding(dp(6), 0, dp(6), 0)
        gravity = Gravity.CENTER_VERTICAL or if (shown?.isNumber == true) Gravity.END else Gravity.START
        setTextColor(if (shown?.isError == true) Color.RED else Color.BLACK)
        if (shown?.style?.bold == true) setTypeface(typeface, Typeface.BOLD)
        setBackgroundColor(shown?.style?.fill?.let { runCatching { Color.parseColor(it) }.getOrNull() } ?: Color.WHITE)
        layoutParams = LinearLayout.LayoutParams(dp(100), dp(30)).apply { setMargins(0, 0, 1, 1) }
        setOnClickListener { edit(book, SigmaAddress(r, c)) }
    }

    private fun edit(book: SigmaWorkbook, at: SigmaAddress) {
        if (session.document.value?.mode?.value != "edit") return
        lifecycleScope.launch {
            val field = EditText(this@SheetActivity).apply { setText(book.input(at)); setSelection(text.length) }
            AlertDialog.Builder(this@SheetActivity).setTitle(at.toString()).setView(field)
                .setPositiveButton("Set") { _, _ -> lifecycleScope.launch { runCatching { book.set(field.text.toString(), at) } } }
                .setNegativeButton("Cancel", null).show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Undo").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, 2, 0, "Bold A1:Z1")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val book = session.workbook.value ?: return true
        lifecycleScope.launch {
            when (item.itemId) {
                1 -> book.undo()
                2 -> book.applyStyle(space.nimbusgo.sigma.SigmaStyle(bold = true), space.nimbusgo.sigma.SigmaSelection.parse("A1:Z1")!!)
            }
        }
        return true
    }

    override fun onDestroy() {
        // Save anything typed in the last moment, then stop the engine.
        val s = session
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            runCatching { s.save() }
            s.close()
        }
        super.onDestroy()
    }
}
