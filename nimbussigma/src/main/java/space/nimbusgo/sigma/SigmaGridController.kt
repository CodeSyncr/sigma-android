package space.nimbusgo.sigma

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import space.nimbusgo.sigma.grid.SigmaGridView

/**
 * Selection, editing and the formula bar's text, shared by the grid and the
 * controls around it. [SigmaSheetView] makes one; pass your own to read the
 * selection or drive the grid from your own controls.
 */
class SigmaGridController {
    private val _selection = MutableStateFlow(SigmaSelection(SigmaAddress(0, 0)))
    private val _isEditing = MutableStateFlow(false)
    private val _editText = MutableStateFlow("")
    private val _activeInput = MutableStateFlow("")
    private val _readOnly = MutableStateFlow(false)
    private val _activeStyle = MutableStateFlow(SigmaStyle())
    private val _activeNote = MutableStateFlow("")
    private val _panel = MutableStateFlow<Panel?>(null)
    private val _selectedChart = MutableStateFlow<String?>(null)
    private val _aiAvailable = MutableStateFlow(false)
    private val _aiMessages = MutableStateFlow<List<SigmaAIMessage>>(emptyList())

    val selection: StateFlow<SigmaSelection> = _selection.asStateFlow()
    val isEditing: StateFlow<Boolean> = _isEditing.asStateFlow()
    /** What the cell being edited holds, as typed. The formula bar shows it. */
    val editText: StateFlow<String> = _editText.asStateFlow()
    /** What the active cell holds when nothing is being edited. */
    val activeInput: StateFlow<String> = _activeInput.asStateFlow()
    val readOnly: StateFlow<Boolean> = _readOnly.asStateFlow()
    /** The active cell's style, for the format bar. */
    val activeStyle: StateFlow<SigmaStyle> = _activeStyle.asStateFlow()
    val activeNote: StateFlow<String> = _activeNote.asStateFlow()
    /** A panel the sheet view shows over the grid. */
    val panel: StateFlow<Panel?> = _panel.asStateFlow()
    /** The chart tapped on, if one is. */
    val selectedChart: StateFlow<String?> = _selectedChart.asStateFlow()
    /** Whether Vani AI is offered (the document's owner has it). */
    val aiAvailable: StateFlow<Boolean> = _aiAvailable.asStateFlow()
    /** The conversation with Vani AI, kept while the sheet is open. */
    val aiMessages: StateFlow<List<SigmaAIMessage>> = _aiMessages.asStateFlow()

    enum class Panel { FIND, CONDITIONAL_FORMATTING, NOTE, CHART, AI }

    internal var grid: SigmaGridView? = null
    internal val workbook: SigmaWorkbook? get() = grid?.workbook

    // State the grid and sheet view set.
    internal fun setSelection(s: SigmaSelection) { _selection.value = s }
    internal fun setEditing(on: Boolean) { _isEditing.value = on }
    internal fun setEditText(t: String) { _editText.value = t }
    internal fun setActive(input: String, style: SigmaStyle, note: String) {
        _activeInput.value = input
        _activeStyle.value = style
        _activeNote.value = note
    }
    internal fun setReadOnly(on: Boolean) { _readOnly.value = on }
    internal fun setSelectedChart(id: String?) { _selectedChart.value = id }
    internal fun setAIAvailable(on: Boolean) { _aiAvailable.value = on }
    internal fun setAIMessages(m: List<SigmaAIMessage>) { _aiMessages.value = m }

    /** Opens a panel over the grid, or closes it with null. */
    fun showPanel(p: Panel?) { _panel.value = p }

    // Formatting the selection

    fun toggleBold() = patch("b", !(activeStyle.value.bold ?: false))
    fun toggleItalic() = patch("i", !(activeStyle.value.italic ?: false))
    fun toggleUnderline() = patch("u", !(activeStyle.value.underline ?: false))
    fun toggleStrikethrough() = patch("st", !(activeStyle.value.strikethrough ?: false))
    /** null returns text to its usual colour. */
    fun setTextColor(hex: String?) = patch("tc", hex ?: "")
    /** null removes the fill. */
    fun setFill(hex: String?) = patch("bg", hex ?: "")
    /** null aligns numbers right and text left, as by default. */
    fun setAlign(align: SigmaStyle.Align?) = patch("al", align?.value ?: "")
    fun setNumberFormat(format: SigmaStyle.NumberFormat) = patch("nf", if (format == SigmaStyle.NumberFormat.AUTO) "" else format.value)
    /** Shows one more (+1) or one fewer (-1) decimal place. */
    fun stepDecimals(by: Int) = patch("dp", ((activeStyle.value.decimals ?: 2) + by).coerceIn(0, 10))
    /** Sets the text size; 13, the default, removes the setting. */
    fun setFontSize(size: Double) {
        val s = size.coerceIn(6.0, 96.0)
        patch("fs", if (s == DEFAULT_FONT_SIZE) "" else s)
    }

    fun setBorders(kind: SigmaWorkbook.Borders) = run { book, s -> book.applyBorders(kind, s) }

    /** Sorts by the active cell's column; a single cell sorts the block of data around it. */
    fun sort(ascending: Boolean) = run { book, s ->
        val block = book.sort(s, s.active.column, ascending)
        grid?.select(SigmaSelection(block.topLeft, block.bottomRight), scroll = false)
    }

    /** Sets the active cell's note; an empty string removes it. */
    fun setNote(text: String) = run { book, s -> book.setNote(text.trim(), s.active) }

    /** Charts the selection: a single cell charts the block of data around it. */
    fun insertChart(kind: SigmaChart.Kind = SigmaChart.Kind.COLUMN) = run { book, s ->
        val range = if (s.isSingle) book.dataBlock(s.active) ?: s else s
        val chart = book.addChart(kind, range.toString())
        _selectedChart.value = chart.id
        grid?.revealChart(chart)
        _panel.value = Panel.CHART
    }

    fun select(s: SigmaSelection) { grid?.select(s, scroll = true) }
    /** Starts editing the active cell, from the formula bar or a key. */
    fun beginEditing(text: String? = null) { grid?.beginEditing(text, focus = true) }
    /** The formula bar changed the text being edited. */
    fun edit(text: String) { grid?.setEditorText(text) }
    internal fun formulaBarFocused() { if (!isEditing.value) grid?.beginEditing(null, focus = false) }
    fun commit() { grid?.commitEditing(SigmaGridView.Move.DOWN) }
    fun cancel() { grid?.cancelEditing() }
    fun undo() { grid?.perform(SigmaGridView.Action.UNDO) }
    fun redo() { grid?.perform(SigmaGridView.Action.REDO) }

    private fun patch(key: String, value: Any) = run { book, s -> book.applyStylePatch(JSONObject().put(key, value), s) }

    /** Runs a change on the selection, finishing an edit in progress first so it lands on that cell. */
    private fun run(block: suspend (SigmaWorkbook, SigmaSelection) -> Unit) {
        val g = grid ?: return
        val book = g.workbook ?: return
        if (readOnly.value) return
        if (isEditing.value) g.commitEditing(SigmaGridView.Move.NONE)
        val s = selection.value
        g.scope.launch {
            runCatching { block(book, s) }.onFailure { g.flash(it) }
            g.refreshActive()
        }
    }

    internal companion object {
        const val DEFAULT_FONT_SIZE = 13.0
    }
}
