package space.nimbusgo.sigma.grid

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import space.nimbusgo.sigma.SigmaAddress
import space.nimbusgo.sigma.SigmaChart
import space.nimbusgo.sigma.SigmaConditionalRule
import space.nimbusgo.sigma.SigmaGridController
import space.nimbusgo.sigma.SigmaSelection
import space.nimbusgo.sigma.SigmaWorkbook
import space.nimbusgo.sigma.charts
import space.nimbusgo.sigma.removeChart
import space.nimbusgo.sigma.updateChart

/** A dialog in the editor's own look: a title row with actions, then its content. */
internal open class Panel(context: Context, title: String, tall: Boolean = false) : Dialog(context) {
    val body = context.column().apply { setPadding(dp(18f), dp(4f), dp(18f), dp(18f)) }
    val actions = context.row()
    private val root = context.column()

    init {
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val head = context.row(context.label(title, 17f, bold = true).apply { layoutParams = weighted() }, actions)
        head.setPadding(context.dp(18f), context.dp(14f), context.dp(8f), context.dp(6f))
        root.addView(head)
        root.addView(ScrollView(context).apply {
            addView(body)
            isFillViewport = true
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (tall) 0 else ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        setContentView(root)
        window?.setBackgroundDrawable(rounded(Color.WHITE, context.dp(16f).toFloat()))
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val w = (context.resources.displayMetrics.widthPixels * 0.94f).toInt().coerceAtMost(context.dp(560f))
        val h = if (tall) (context.resources.displayMetrics.heightPixels * 0.78f).toInt() else ViewGroup.LayoutParams.WRAP_CONTENT
        window?.setLayout(w, h)
    }

    fun action(text: String, bold: Boolean = false, color: Int = GridColors.accent, onClick: () -> Unit): TextView =
        context.button(text, 15f, color, bold) { onClick() }.also { actions.addView(it) }

    fun dp(v: Float): Int = context.dp(v)

    fun section(text: String) = body.addView(context.label(text.uppercase(), 12f, GridColors.secondary, bold = true).apply {
        setPadding(0, dp(14f), 0, dp(6f))
    })
}

/**
 * Find (and replace) in the sheet, as a bar over the grid: each hit is
 * selected in turn, so the grid scrolls to it.
 */
internal class FindBar(
    context: Context,
    private val book: SigmaWorkbook,
    private val controller: SigmaGridController,
    private val readOnly: Boolean,
    private val scope: CoroutineScope,
) : LinearLayout(context) {
    private val query = context.field("Find in sheet").apply { imeOptions = EditorInfo.IME_ACTION_SEARCH }
    private val count = context.label("", 12f, GridColors.secondary)
    private val replacement = context.field("Replace with")
    private val replaceRow: LinearLayout
    private val replaceAll: TextView
    private var matchCase = false
    private var hits: List<SigmaAddress> = emptyList()
    private var at = 0
    private var searching: Job? = null

    init {
        orientation = VERTICAL
        setPadding(dp(12f), dp(8f), dp(8f), dp(8f))
        setBackgroundColor(Color.WHITE)
        val box = context.row(query.apply { layoutParams = weighted(); background = null; setPadding(0, 0, 0, 0) }, count)
        box.background = rounded(GridColors.surface, dp(9f).toFloat())
        box.setPadding(dp(10f), dp(2f), dp(10f), dp(2f))
        addView(context.row(
            box.apply { layoutParams = LayoutParams(0, dp(38f), 1f) },
            context.button("↑", 17f) { step(-1) }.apply { contentDescription = "Previous" },
            context.button("↓", 17f) { step(1) }.apply { contentDescription = "Next" },
            context.button("⋯", 18f) { options(it) }.apply { contentDescription = "Options" },
            context.button("Done", 15f, GridColors.accent, bold = true) { controller.showPanel(null) },
        ))
        replaceAll = context.button("Replace all", 14f, GridColors.accent, bold = true) { doReplace() }
        replaceRow = context.row(replacement.apply { layoutParams = weighted() }, replaceAll).apply {
            visibility = GONE
            setPadding(0, dp(8f), 0, 0)
        }
        addView(replaceRow)
        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { search(); replaceAll.text = "Replace all" }
        })
        query.setOnEditorActionListener { _, _, _ -> step(1); true }
        scope.launch { book.revision.drop(1).collect { search(keepPlace = true) } }
        query.requestFocus()
    }

    private fun options(anchor: View) {
        val m = PopupMenu(context, anchor)
        m.menu.add(0, 0, 0, "Match case").setCheckable(true).isChecked = matchCase
        if (!readOnly) m.menu.add(0, 1, 1, "Replace").setCheckable(true).isChecked = replaceRow.visibility == VISIBLE
        m.setOnMenuItemClickListener {
            if (it.itemId == 0) { matchCase = !matchCase; search() } else replaceRow.visibility = if (replaceRow.visibility == VISIBLE) GONE else VISIBLE
            true
        }
        m.show()
    }

    private fun search(keepPlace: Boolean = false) {
        val q = query.text.toString()
        searching?.cancel()
        searching = scope.launch {
            delay(120)
            hits = if (q.isEmpty()) emptyList() else runCatching { book.find(q, matchCase) }.getOrDefault(emptyList())
            at = if (keepPlace) at.coerceIn(0, maxOf(0, hits.size - 1)) else 0
            show()
            if (!keepPlace) hits.firstOrNull()?.let { controller.select(SigmaSelection(it)) }
        }
    }

    private fun show() {
        count.text = when {
            query.text.isEmpty() -> ""
            hits.isEmpty() -> "No results"
            else -> "${at + 1} of ${hits.size}"
        }
    }

    private fun step(by: Int) {
        if (hits.isEmpty()) return
        at = (at + by + hits.size) % hits.size
        show()
        controller.select(SigmaSelection(hits[at]))
    }

    private fun doReplace() {
        val q = query.text.toString()
        if (q.isEmpty()) return
        scope.launch {
            val n = runCatching { book.replaceAll(q, replacement.text.toString(), matchCase) }.getOrDefault(0)
            replaceAll.text = "Replaced $n"
        }
    }
}

/** A cell's note, to read or change. */
internal class NotePanel(context: Context, controller: SigmaGridController, readOnly: Boolean) :
    Panel(context, "Note on ${controller.selection.value.active}") {
    init {
        val text = context.field("Write a note", controller.activeNote.value).apply {
            isSingleLine = false
            minLines = 4
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            isEnabled = !readOnly
        }
        body.addView(text, matchWidth())
        if (readOnly) {
            action("Close", bold = true) { dismiss() }
        } else {
            if (controller.activeNote.value.isNotEmpty()) action("Delete", color = GridColors.error) { controller.setNote(""); dismiss() }
            action("Cancel", color = GridColors.secondary) { dismiss() }
            action("Save", bold = true) { controller.setNote(text.text.toString()); dismiss() }
            text.requestFocus()
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }
}

/** The sheet's conditional formatting rules: see, add and delete them. */
internal class ConditionalFormattingPanel(
    context: Context,
    private val book: SigmaWorkbook,
    private val controller: SigmaGridController,
    private val readOnly: Boolean,
    private val scope: CoroutineScope,
) : Panel(context, "Conditional formatting", tall = true) {
    private val list = context.column()

    init {
        if (!readOnly) action("Add rule", bold = true) { RuleEditor(context, controller.selection.value) { rule -> change { book.addConditionalRule(rule) } }.show() }
        action("Done", color = GridColors.secondary) { dismiss() }
        body.addView(list, matchWidth())
        load()
    }

    private fun change(block: suspend () -> Unit): Job = scope.launch {
        runCatching { block() }.onFailure { controller.grid?.flash(it) }
        load()
    }

    private fun load(): Job = scope.launch {
        val rules = runCatching { book.conditionalRules() }.getOrDefault(emptyList())
        list.removeAllViews()
        if (rules.isEmpty()) {
            list.addView(context.label("Colour cells by their value — for example, fill scores under 50 in red.", 15f, GridColors.secondary).apply {
                setPadding(0, dp(10f), 0, dp(10f))
            })
        }
        rules.forEachIndexed { i, rule ->
            val sample = context.label("Aa", 13f, GridColors.hex(rule.textColor) ?: GridColors.ink, bold = rule.bold == true).apply {
                gravity = Gravity.CENTER
                background = rounded(GridColors.hex(rule.fill) ?: GridColors.surface, dp(5f).toFloat())
                layoutParams = LinearLayout.LayoutParams(dp(38f), dp(28f)).apply { rightMargin = dp(12f) }
            }
            val text = context.column(
                context.label(rule.range.toString(), 15f, bold = true),
                context.label(describe(rule), 13f, GridColors.secondary),
            ).apply { layoutParams = weighted() }
            val line = context.row(sample, text).apply { setPadding(0, dp(10f), 0, dp(10f)) }
            if (!readOnly) line.addView(context.button("Delete", 14f, GridColors.error) { change { book.removeConditionalRule(i) } })
            list.addView(line, matchWidth())
            list.addView(context.divider())
        }
    }

    private fun describe(r: SigmaConditionalRule) = when (r.condition) {
        SigmaConditionalRule.Condition.NOT_EMPTY -> r.condition.label
        SigmaConditionalRule.Condition.BETWEEN -> "${r.condition.label} ${r.value} and ${r.value2 ?: ""}"
        SigmaConditionalRule.Condition.CONTAINS -> "${r.condition.label} “${r.value}”"
        else -> "${r.condition.label} ${r.value}"
    }
}

/** A new conditional formatting rule. */
internal class RuleEditor(context: Context, range: SigmaSelection, add: (SigmaConditionalRule) -> Unit) : Panel(context, "New rule") {
    /** Ready-made looks, as fill and text colour. */
    private val looks = listOf(
        Triple("Green", "#d9ead3", "#1e5e2b"), Triple("Red", "#f4cccc", "#99231b"), Triple("Yellow", "#fff2cc", "#7a5b00"),
        Triple("Blue", "#cfe2f3", "#1c4a73"), Triple("Bold red text", null, "#c0392b"),
    )

    init {
        val conditions = SigmaConditionalRule.Condition.entries
        val rangeField = context.field("Range, e.g. B2:B20", range.toString()).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS }
        val condition = spinner(conditions.map { it.label })
        val value = context.field("Value")
        val value2 = context.field("And")
        val look = spinner(looks.map { it.first })
        val bold = CheckBox(context).apply { text = "Bold" }
        val preview = context.label("1,234", 15f).apply { setPadding(dp(14f), dp(6f), dp(14f), dp(6f)) }
        fun refresh() {
            val c = conditions[condition.selectedItemPosition]
            value.visibility = if (c.needsValue) View.VISIBLE else View.GONE
            value.hint = if (c == SigmaConditionalRule.Condition.CONTAINS) "Text" else "Value"
            value2.visibility = if (c == SigmaConditionalRule.Condition.BETWEEN) View.VISIBLE else View.GONE
            val l = looks[look.selectedItemPosition]
            preview.setTextColor(GridColors.hex(l.third) ?: GridColors.ink)
            preview.background = rounded(GridColors.hex(l.second) ?: Color.TRANSPARENT, dp(5f).toFloat())
            preview.typeface = if (bold.isChecked || l.second == null) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = refresh()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        condition.onItemSelectedListener = listener
        look.onItemSelectedListener = listener
        bold.setOnCheckedChangeListener { _, _ -> refresh() }

        section("Cells"); body.addView(rangeField, matchWidth())
        section("When the value is"); body.addView(condition, matchWidth())
        body.addView(value, matchWidth().apply { topMargin = dp(8f) })
        body.addView(value2, matchWidth().apply { topMargin = dp(8f) })
        section("Format them"); body.addView(look, matchWidth())
        body.addView(context.row(bold.apply { layoutParams = weighted() }, context.label("Preview  ", 14f, GridColors.secondary), preview))
        refresh()

        action("Cancel", color = GridColors.secondary) { dismiss() }
        action("Add", bold = true) {
            val r = SigmaSelection.parse(rangeField.text.toString().replace(" ", ""))
            val c = conditions[condition.selectedItemPosition]
            if (r == null) { rangeField.error = "Not a range"; return@action }
            if (c.needsValue && value.text.isEmpty()) { value.error = "Needed"; return@action }
            val l = looks[look.selectedItemPosition]
            add(SigmaConditionalRule(
                r, c, value.text.toString(),
                if (c == SigmaConditionalRule.Condition.BETWEEN) value2.text.toString() else null,
                l.second, l.third,
                // The text-only look is bold, or it hardly shows.
                if (bold.isChecked || l.second == null) true else null,
            ))
            dismiss()
        }
    }

    private fun spinner(items: List<String>) = Spinner(context).apply {
        adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, items)
        background = rounded(GridColors.surface, dp(9f).toFloat())
    }
}

/** Edit the selected chart: its type, title, data and stacking. */
internal class ChartPanel(
    context: Context,
    private val book: SigmaWorkbook,
    private val controller: SigmaGridController,
    private val scope: CoroutineScope,
) : Panel(context, "Chart") {
    private var draft: SigmaChart? = null
    private val types = context.column()
    private val title = context.field("No title")
    private val data = context.field("A1:C13").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS }
    private val stacked = CheckBox(context).apply { text = "Stacked" }

    init {
        action("Done", bold = true) { finish() }
        section("Type"); body.addView(types, matchWidth())
        section("Title"); body.addView(title, matchWidth())
        section("Data"); body.addView(data, matchWidth())
        body.addView(context.label("The chart reads these cells as they change.", 13f, GridColors.secondary).apply { setPadding(0, dp(4f), 0, 0) })
        body.addView(stacked)
        body.addView(context.button("Delete chart", 15f, GridColors.error) {
            val id = draft?.id ?: return@button
            scope.launch { runCatching { book.removeChart(id) }; controller.setSelectedChart(null) }
            dismiss()
        }.apply { layoutParams = matchWidth().apply { topMargin = dp(10f) } })
        stacked.setOnCheckedChangeListener { b, on -> if (b.isPressed) update { it.copy(stacked = if (on) true else null) } }
        scope.launch {
            draft = runCatching { book.charts() }.getOrDefault(emptyList()).firstOrNull { it.first.id == controller.selectedChart.value }?.first
            val d = draft ?: return@launch dismiss()
            title.setText(d.title ?: "")
            data.setText(d.data)
            showTypes()
        }
    }

    private fun showTypes() {
        val d = draft ?: return
        types.removeAllViews()
        SigmaChart.Kind.entries.chunked(4).forEach { row ->
            val line = context.row()
            row.forEach { kind ->
                val on = d.kind == kind
                line.addView(context.label(kind.label, 13f, if (on) GridColors.accent else GridColors.ink, bold = on).apply {
                    gravity = Gravity.CENTER
                    background = pressable(context, if (on) GridColors.headerSelected else GridColors.surface)
                    isClickable = true
                    setOnClickListener { update { it.copy(kind = kind) } }
                }, LinearLayout.LayoutParams(0, dp(42f), 1f).apply { setMargins(dp(3f), dp(3f), dp(3f), dp(3f)) })
            }
            repeat(4 - row.size) { line.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f).apply { setMargins(dp(3f), 0, dp(3f), 0) }) }
            types.addView(line, matchWidth())
        }
        stacked.visibility = if (d.kind.canStack) View.VISIBLE else View.GONE
        stacked.isChecked = d.stacked == true
    }

    private fun update(change: (SigmaChart) -> SigmaChart) {
        val d = draft ?: return
        val next = change(d)
        draft = next
        showTypes()
        scope.launch { runCatching { book.updateChart(next) }.onFailure { controller.grid?.flash(it) } }
    }

    private fun finish() {
        val d = draft
        if (d != null) {
            val t = title.text.toString().trim().ifEmpty { null }
            val range = data.text.toString().trim().uppercase()
            if (t != d.title || (range.isNotEmpty() && range != d.data)) update { it.copy(title = t, data = range.ifEmpty { it.data }) }
        }
        dismiss()
    }
}
