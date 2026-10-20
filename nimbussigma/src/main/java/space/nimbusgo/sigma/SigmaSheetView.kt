package space.nimbusgo.sigma

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.nimbusgo.sigma.grid.ChartPanel
import space.nimbusgo.sigma.grid.ConditionalFormattingPanel
import space.nimbusgo.sigma.grid.FindBar
import space.nimbusgo.sigma.grid.FormatBar
import space.nimbusgo.sigma.grid.FormulaHelpBar
import space.nimbusgo.sigma.grid.FormulaHint
import space.nimbusgo.sigma.grid.FunctionsPanel
import space.nimbusgo.sigma.grid.IconButton
import space.nimbusgo.sigma.grid.GridColors
import space.nimbusgo.sigma.grid.NotePanel
import space.nimbusgo.sigma.grid.SigmaGridView
import space.nimbusgo.sigma.grid.VaniPanel
import space.nimbusgo.sigma.grid.button
import space.nimbusgo.sigma.grid.column
import space.nimbusgo.sigma.grid.divider
import space.nimbusgo.sigma.grid.dp
import space.nimbusgo.sigma.grid.label
import space.nimbusgo.sigma.grid.matchWidth
import space.nimbusgo.sigma.grid.pressable
import space.nimbusgo.sigma.grid.primaryButton
import space.nimbusgo.sigma.grid.rounded
import space.nimbusgo.sigma.grid.row
import space.nimbusgo.sigma.grid.weighted
import java.io.File

/**
 * A Sigma sheet, native: formula bar, format bar, grid and sheet tabs, opened
 * from a [SigmaSession] and saving as people edit.
 *
 * ```kotlin
 * val session = SigmaSession(context) { myApi.sheetLink(id = 42) }
 * setContentView(SigmaSheetView(this).apply { open(session) })
 * ```
 *
 * In Compose: `AndroidView({ SigmaSheetView(it).apply { open(session) } })`.
 * The activity should use `android:windowSoftInputMode="adjustResize"`; the
 * view also keeps itself clear of the keyboard when the window does not resize.
 */
class SigmaSheetView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    // Internal, so `SigmaSheetView(this).apply { open(session) }` reads the caller's session.
    internal var session: SigmaSession? = null
        private set
    var controller: SigmaGridController = SigmaGridController()
        private set

    private var scope = newScope()
    private var editorScope: Job? = null
    private var grid: SigmaGridView? = null
    private var shownBook: SigmaWorkbook? = null
    private var dialog: Dialog? = null
    private var bottomInset = 0

    init {
        setBackgroundColor(Color.WHITE)
        // Stay clear of the keyboard and the navigation bar, whether or not the window resizes for them.
        ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            val screenBottom = v.rootView.height
            val viewBottom = loc[1] + v.height
            val covered = maxOf(ime, nav) - (screenBottom - viewBottom)
            val pad = maxOf(0, covered)
            if (pad != bottomInset) {
                bottomInset = pad
                v.setPadding(0, 0, 0, pad)
            }
            insets
        }
    }

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Shows a session's sheet, opening it if it has not started.
     *
     * @param controller Pass your own to read the selection or drive the grid
     *   from your own controls.
     */
    fun open(session: SigmaSession, controller: SigmaGridController = this.controller) {
        this.session = session
        this.controller = controller
        shownBook = null
        if (isAttachedToWindow) watch()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!scopeActive()) scope = newScope()
        watch()
        ViewCompat.requestApplyInsets(this)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        dialog?.dismiss()
        scope.cancel()
    }

    private fun scopeActive() = scope.coroutineContext[Job]?.isActive == true

    private fun watch() {
        val s = session ?: return
        scope.coroutineContext[Job]?.children?.forEach { it.cancel() }
        s.start()
        scope.launch {
            combine(s.phase, s.workbook, s.document) { p, b, d -> Triple(p, b, d) }.collect { (phase, book, doc) ->
                when (phase) {
                    SigmaSession.Phase.Loading -> showLoading()
                    is SigmaSession.Phase.Failed -> showFailure(phase.error)
                    SigmaSession.Phase.Ready -> if (book != null && doc != null && book !== shownBook) showEditor(s, book, doc)
                }
            }
        }
        scope.launch { s.aiAvailable.collect { on -> controller.setAIAvailable(on && s.document.value?.mode == SigmaLinkMode.EDIT) } }
    }

    private fun showLoading() {
        shownBook = null
        editorScope?.cancel()
        removeAllViews()
        addView(ProgressBar(context), LayoutParams(dp(40f), dp(40f), Gravity.CENTER))
    }

    private fun showFailure(error: SigmaException) {
        shownBook = null
        editorScope?.cancel()
        removeAllViews()
        val box = context.column(
            context.label("▦", 34f, GridColors.secondary).apply { gravity = Gravity.CENTER },
            context.label(error.message ?: "The sheet could not be opened.", 15f, GridColors.secondary).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(12f), 0, dp(16f))
            },
            context.primaryButton("Try again") { session?.reload() },
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(32f), 0, dp(32f), 0)
        }
        addView(box, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    private fun showEditor(session: SigmaSession, book: SigmaWorkbook, doc: SigmaSession.Document) {
        shownBook = book
        editorScope?.cancel()
        val job = SupervisorJob(scope.coroutineContext[Job])
        editorScope = job
        val es = CoroutineScope(scope.coroutineContext + job)
        removeAllViews()
        val readOnly = doc.mode != SigmaLinkMode.EDIT
        controller.setAIAvailable(session.aiAvailable.value && !readOnly)

        val g = SigmaGridView(context, controller)
        grid = g
        g.show(book, readOnly)

        val root = context.column()
        root.addView(FormulaBar(context, controller, readOnly, es), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44f)))
        root.addView(context.divider())
        if (doc.test) {
            root.addView(context.label("Test mode — opened with a test API key. Use a live key in production.", 12f, GridColors.testText, bold = true).apply {
                gravity = Gravity.CENTER
                setBackgroundColor(GridColors.testBanner)
                setPadding(dp(12f), dp(5f), dp(12f), dp(5f))
            }, matchWidth())
        }
        if (!readOnly) {
            val bar = FormatBar(context, controller) { format -> share(session, format) }
            bar.watch(es)
            root.addView(bar, matchWidth())
            root.addView(context.divider())
        }
        val findSlot = FrameLayout(context)
        root.addView(findSlot, matchWidth())
        val noteBanner = context.label("", 13f).apply {
            setPadding(dp(12f), dp(7f), dp(12f), dp(7f))
            setBackgroundColor(Color.argb(26, 242, 153, 0))
            maxLines = 2
            visibility = GONE
            setOnClickListener { controller.showPanel(SigmaGridController.Panel.NOTE) }
        }
        root.addView(noteBanner, matchWidth())
        root.addView(g, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(context.divider())
        val help = FormulaHelpBar(context) { name -> controller.insertFunction(name) }
        root.addView(help, matchWidth())
        val keys = FormulaKeys(context, { g.insertIntoEditor(it) }) { controller.showPanel(SigmaGridController.Panel.FUNCTIONS) }
        root.addView(keys, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48f)))
        val tabs = SheetTabs(context, book, controller, readOnly, es)
        root.addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46f)))
        addView(root, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Formula help: what a half-typed name could be, or the syntax of the call being filled in.
        es.launch {
            val functions = runCatching { book.functions() }.getOrDefault(emptyList())
            combine(controller.isEditing, controller.editText) { e, t -> if (e) t else "" }.collect { text ->
                help.show(FormulaHint.of(text, functions))
            }
        }
        es.launch {
            combine(controller.isEditing, controller.activeNote) { e, n -> e to n }.collect { (editing, note) ->
                keys.visibility = if (editing) VISIBLE else GONE
                tabs.visibility = if (editing) GONE else VISIBLE
                noteBanner.visibility = if (note.isNotEmpty() && !editing) VISIBLE else GONE
                noteBanner.text = "✎  $note"
            }
        }
        es.launch {
            controller.panel.collect { p ->
                if (p == SigmaGridController.Panel.FIND) {
                    if (findSlot.childCount == 0) {
                        findSlot.addView(FindBar(context, book, controller, readOnly, CoroutineScope(es.coroutineContext + SupervisorJob(job))))
                        findSlot.addView(View(context).apply { setBackgroundColor(GridColors.gridline) }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1, Gravity.BOTTOM))
                    }
                } else if (findSlot.childCount > 0) {
                    findSlot.removeAllViews()
                    g.requestFocus()
                }
                dialog?.setOnDismissListener(null)
                dialog?.dismiss()
                dialog = when (p) {
                    SigmaGridController.Panel.NOTE -> NotePanel(context, controller, readOnly)
                    SigmaGridController.Panel.CONDITIONAL_FORMATTING -> ConditionalFormattingPanel(context, book, controller, readOnly, es)
                    SigmaGridController.Panel.CHART -> if (readOnly) null else ChartPanel(context, book, controller, es)
                    SigmaGridController.Panel.AI -> if (readOnly) null else VaniPanel(context, session, controller, es)
                    SigmaGridController.Panel.FUNCTIONS -> if (readOnly) null else FunctionsPanel(context, book, controller, es)
                    else -> null
                }?.also { d ->
                    d.setOnDismissListener { if (controller.panel.value == p) controller.showPanel(null) }
                    d.show()
                }
            }
        }
        g.requestFocus()
    }

    /** Exports the sheet and hands it to the system share sheet. */
    private fun share(session: SigmaSession, format: SigmaFormat) {
        scope.launch {
            try {
                val file = session.export(format)
                val uri = withContext(Dispatchers.IO) {
                    val dir = File(context.cacheDir, "nimbussigma").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val out = File(dir, file.filename)
                    out.writeBytes(file.data)
                    FileProvider.getUriForFile(context, context.packageName + ".nimbussigma.files", out)
                }
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = format.mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TITLE, file.filename)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, file.filename).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                Toast.makeText(context, "The sheet could not be exported: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}

/** The cell's name and what it holds; edit a formula here as well as in the cell. */
private class FormulaBar(context: Context, private val controller: SigmaGridController, readOnly: Boolean, scope: CoroutineScope) : LinearLayout(context) {
    private val name = context.label("A1", 13f, GridColors.secondary, bold = true).apply {
        typeface = Typeface.MONOSPACE
        minWidth = dp(52f)
        maxLines = 1
    }
    private val field = EditText(context).apply {
        background = null
        setTextColor(GridColors.ink)
        typeface = Typeface.MONOSPACE
        textSize = 14f
        isSingleLine = true
        setPadding(dp(4f), 0, dp(4f), 0)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        isEnabled = !readOnly
    }
    private val cancel = IconButton(context, R.drawable.nimbussigma_ic_close, "Cancel") { controller.cancel(); field.clearFocus() }.apply { tint = GridColors.secondary }
    private val done = IconButton(context, R.drawable.nimbussigma_ic_check, "Done") { controller.commit(); field.clearFocus() }.apply { tint = GridColors.accent }
    private var setting = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12f), 0, dp(4f), 0)
        addView(name)
        addView(context.divider(vertical = true))
        addView(context.label("fx", 13f, GridColors.secondary).apply { setTypeface(Typeface.SERIF, Typeface.ITALIC) })
        addView(field, weighted())
        addView(cancel)
        addView(done)
        // Only a touch on the bar starts editing there; focus that merely passes through does not.
        var touched = false
        field.setOnTouchListener { _, e -> if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) touched = true; false }
        field.setOnFocusChangeListener { _, has ->
            if (has && touched) controller.formulaBarFocused()
            if (has && !touched) post { if (!controller.isEditing.value) field.clearFocus() }
            touched = false
        }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { if (!setting && field.hasFocus()) controller.edit(s.toString()) }
        })
        field.setOnEditorActionListener { _, _, _ -> controller.commit(); field.clearFocus(); true }
        scope.launch {
            combine(controller.selection, controller.isEditing, controller.editText, controller.activeInput) { s, e, t, i -> listOf(s, e, t, i) }.collect {
                val editing = controller.isEditing.value
                name.text = controller.selection.value.toString()
                val text = if (editing) controller.editText.value else controller.activeInput.value
                if (field.text.toString() != text) {
                    setting = true
                    field.setText(text)
                    if (field.hasFocus()) field.setSelection(text.length)
                    setting = false
                }
                cancel.visibility = if (editing) VISIBLE else GONE
                done.visibility = if (editing) VISIBLE else GONE
                if (!editing && field.hasFocus()) field.clearFocus()
            }
        }
    }
}

/** Keys a formula needs that the phone keyboard hides, above it while editing. */
private class FormulaKeys(context: Context, insert: (String) -> Unit, functions: () -> Unit) : LinearLayout(context) {
    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(GridColors.surface)
        setPadding(dp(4f), dp(5f), dp(4f), dp(5f))
        visibility = GONE
        for (key in listOf("=", "+", "−", "×", "÷", "(", ")", ":", ",", "Σ", "ƒx")) {
            val text = when (key) { "−" -> "-"; "×" -> "*"; "÷" -> "/"; "Σ" -> "SUM("; else -> key }
            addView(context.button(key, if (key == "ƒx") 15f else 18f, if (key == "ƒx") GridColors.accent else GridColors.ink, bold = key == "ƒx") {
                if (key == "ƒx") functions() else insert(text)
            }.apply {
                if (key == "ƒx") contentDescription = "Insert function"
                background = pressable(context, Color.WHITE, 6f)
                minWidth = 0
                setPadding(0, 0, 0, 0)
            }, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dp(3f), 0, dp(3f), 0) })
        }
    }
}

/** Sheets along the bottom, with undo and redo. */
private class SheetTabs(
    context: Context,
    private val book: SigmaWorkbook,
    private val controller: SigmaGridController,
    private val readOnly: Boolean,
    private val scope: CoroutineScope,
) : LinearLayout(context) {
    private val strip = context.row()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Color.WHITE)
        addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip)
            strip.setPadding(dp(8f), 0, dp(8f), 0)
        }, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        if (!readOnly) {
            addView(IconButton(context, R.drawable.nimbussigma_ic_undo, "Undo") { controller.undo() })
            addView(IconButton(context, R.drawable.nimbussigma_ic_redo, "Redo") { controller.redo() }.apply {
                layoutParams = LayoutParams(dp(40f), dp(40f)).apply { rightMargin = dp(6f) }
            })
        }
        scope.launch {
            combine(book.sheets, book.activeSheet) { s, a -> s to a }.collect { (sheets, active) -> render(sheets, active) }
        }
    }

    private fun render(sheets: List<SigmaSheetInfo>, active: Int) {
        strip.removeAllViews()
        sheets.forEachIndexed { i, sheet ->
            val on = i == active
            strip.addView(context.label(sheet.name, 13f, if (on) GridColors.accent else GridColors.ink, bold = on).apply {
                gravity = Gravity.CENTER
                setPadding(dp(14f), dp(7f), dp(14f), dp(7f))
                background = pressable(context, if (on) GridColors.headerSelected else Color.TRANSPARENT, 16f)
                isClickable = true
                setOnClickListener { scope.launch { book.selectSheet(i) } }
                if (!readOnly) setOnLongClickListener { menu(it, i, sheets.size); true }
            }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(4f) })
        }
        if (!readOnly) strip.addView(IconButton(context, R.drawable.nimbussigma_ic_add, "Add sheet") { scope.launch { runCatching { book.addSheet() } } })
    }

    private fun menu(anchor: View, index: Int, count: Int) {
        val m = PopupMenu(context, anchor)
        m.menu.add(0, 0, 0, "Rename")
        m.menu.add(0, 2, 1, "Duplicate")
        if (index > 0) m.menu.add(0, 3, 2, "Move left")
        if (index < count - 1) m.menu.add(0, 4, 3, "Move right")
        if (count > 1) m.menu.add(0, 1, 4, "Delete")
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                0 -> rename(index)
                else -> scope.launch {
                    runCatching {
                        when (it.itemId) {
                            1 -> book.deleteSheet(index)
                            2 -> book.duplicateSheet(index)
                            3 -> book.moveSheet(index, index - 1)
                            4 -> book.moveSheet(index, index + 1)
                            else -> Unit
                        }
                    }.onFailure { e -> controller.grid?.flash(e) }
                }
            }
            true
        }
        m.show()
    }

    private fun rename(index: Int) {
        val current = book.sheets.value.getOrNull(index)?.name ?: return
        val panel = space.nimbusgo.sigma.grid.Panel(context, "Rename sheet")
        val input = EditText(context).apply { setText(current); setSelection(current.length); isSingleLine = true }
        panel.body.addView(input, matchWidth())
        panel.body.addView(context.label("Formulas that use this sheet are updated to the new name.", 13f, GridColors.secondary).apply { setPadding(0, dp(6f), 0, 0) })
        panel.action("Cancel", color = GridColors.secondary) { panel.dismiss() }
        panel.action("Rename", bold = true) {
            val name = input.text.toString().trim()
            scope.launch { runCatching { book.renameSheet(index, name) }.onFailure { e -> controller.grid?.flash(e) } }
            panel.dismiss()
        }
        panel.show()
    }
}
