package space.nimbusgo.sigma

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import space.nimbusgo.sigma.engine.SigmaEngine

/**
 * A Sigma workbook, open in Sigma's own engine: read what cells show, change
 * them, undo, and save it as .xlsx or CSV. Everything that changes the
 * workbook bumps [revision] and calls [onChange].
 *
 * A workbook only ever comes from a [SigmaSession]: the engine it runs on is
 * handed to a session for a sheet opened through the API.
 */
class SigmaWorkbook internal constructor(internal val engine: SigmaEngine, internal val handle: Int) {
    private val _revision = MutableStateFlow(0)
    private val _sheets = MutableStateFlow<List<SigmaSheetInfo>>(emptyList())
    private val _activeSheet = MutableStateFlow(0)

    val revision: StateFlow<Int> = _revision.asStateFlow()
    val sheets: StateFlow<List<SigmaSheetInfo>> = _sheets.asStateFlow()
    val activeSheet: StateFlow<Int> = _activeSheet.asStateFlow()

    /** Called after every change, for autosaving. */
    var onChange: (() -> Unit)? = null

    /** The engine version, "sigma@<commit>". */
    val engineVersion: String get() = engine.version

    private fun sh(sheet: Int?) = sheet ?: _activeSheet.value

    // Reading

    /** Cells in a block that show something or are styled. Ask for what is on screen. */
    suspend fun cells(rows: IntRange, columns: IntRange, sheet: Int? = null): List<SigmaCell> {
        val a = engine.callArray("cells", handle, sh(sheet), rows.first, columns.first, rows.last, columns.last)
        return (0 until a.length()).map { SigmaCell.from(a.getJSONObject(it)) }
    }

    suspend fun layout(sheet: Int? = null): SigmaSheetLayout = SigmaSheetLayout.from(engine.callObject("layout", handle, sh(sheet)))

    /** What was typed into a cell: a value or a formula. */
    suspend fun input(at: SigmaAddress, sheet: Int? = null): String = engine.callString("raw", handle, sh(sheet), at.row, at.column)

    /** What a cell computes to. */
    suspend fun value(at: SigmaAddress, sheet: Int? = null): SigmaValue =
        SigmaValue.of(JSONArray("[" + engine.call("value", handle, sh(sheet), at.row, at.column) + "]").opt(0))

    suspend fun note(at: SigmaAddress, sheet: Int? = null): String = engine.callString("note", handle, sh(sheet), at.row, at.column)

    suspend fun style(at: SigmaAddress, sheet: Int? = null): SigmaStyle =
        SigmaStyle.from(runCatching { JSONObject(callMethod("style", sh(sheet), at.row, at.column)) }.getOrNull()) ?: SigmaStyle()

    // Changing

    /** Types into a cell: a number, text, or a formula starting with "=". */
    suspend fun set(input: String, at: SigmaAddress, sheet: Int? = null) = invoke("setCell", sh(sheet), at.row, at.column, input)

    /** Types into several cells as one undo step. */
    suspend fun set(inputs: Map<SigmaAddress, String>, sheet: Int? = null) {
        val updates = JSONArray()
        inputs.forEach { (a, v) -> updates.put(JSONObject().put("r", a.row).put("c", a.column).put("raw", v)) }
        invoke("setCells", sh(sheet), updates)
    }

    suspend fun applyStyle(style: SigmaStyle, range: SigmaSelection, sheet: Int? = null) =
        invoke("applyStyle", sh(sheet), range.top, range.left, range.bottom, range.right, style.toJson())

    /** Applies Sigma's own style keys; a value of `false` or `""` removes that key. */
    internal suspend fun applyStylePatch(patch: JSONObject, range: SigmaSelection, sheet: Int? = null) =
        invoke("applyStyle", sh(sheet), range.top, range.left, range.bottom, range.right, patch)

    suspend fun clear(range: SigmaSelection, sheet: Int? = null) =
        invoke("clearRange", sh(sheet), range.top, range.left, range.bottom, range.right)

    suspend fun insertRows(at: Int, count: Int = 1, sheet: Int? = null) = invoke("insertRows", sh(sheet), at, count)
    suspend fun deleteRows(at: Int, count: Int = 1, sheet: Int? = null) = invoke("deleteRows", sh(sheet), at, count)
    suspend fun insertColumns(at: Int, count: Int = 1, sheet: Int? = null) = invoke("insertCols", sh(sheet), at, count)
    suspend fun deleteColumns(at: Int, count: Int = 1, sheet: Int? = null) = invoke("deleteCols", sh(sheet), at, count)

    suspend fun freeze(rows: Int, columns: Int, sheet: Int? = null) = invoke("setFreeze", sh(sheet), rows, columns)

    /** Sets a column's width in points; null goes back to the default. */
    suspend fun setColumnWidth(column: Int, width: Double?, sheet: Int? = null) {
        engine.call("setColWidth", handle, sh(sheet), column, width)
        changed()
    }

    /** Sets a row's height in points; null goes back to the default. */
    suspend fun setRowHeight(row: Int, height: Double?, sheet: Int? = null) {
        engine.call("setRowHeight", handle, sh(sheet), row, height)
        changed()
    }

    // Formatting, notes, sort, find

    enum class Borders(val value: String) { ALL("all"), OUTER("outer"), TOP("top"), BOTTOM("bottom"), LEFT("left"), RIGHT("right"), CLEAR("clear") }

    suspend fun applyBorders(kind: Borders, range: SigmaSelection, sheet: Int? = null) =
        invoke("applyBorders", sh(sheet), range.top, range.left, range.bottom, range.right, kind.value)

    /** Sets a cell's note; an empty string removes it. */
    suspend fun setNote(text: String, at: SigmaAddress, sheet: Int? = null) = invoke("setNote", sh(sheet), at.row, at.column, text)

    /**
     * Sorts the rows of a block by one of its columns. A single cell sorts the
     * block of data around it, leaving a header row in place, as the web editor
     * does. Returns the rows sorted.
     */
    suspend fun sort(range: SigmaSelection, byColumn: Int, ascending: Boolean, sheet: Int? = null): SigmaSelection {
        val s = sh(sheet)
        var block = range
        if (range.isSingle) {
            val r = engine.callObject("region", handle, s, range.active.row, range.active.column)
            val top = if (r.optBoolean("header")) r.getInt("r1") + 1 else r.getInt("r1")
            block = SigmaSelection(SigmaAddress(top, r.getInt("c1")), SigmaAddress(r.getInt("r2"), r.getInt("c2")))
        }
        invoke("sortRange", s, block.top, block.left, block.bottom, block.right, byColumn, ascending)
        return block
    }

    /** The block of data around a cell, header row included (what a chart of it should read). */
    suspend fun dataBlock(around: SigmaAddress, sheet: Int? = null): SigmaSelection? = runCatching {
        val r = engine.callObject("region", handle, sh(sheet), around.row, around.column)
        SigmaSelection(SigmaAddress(r.getInt("r1"), r.getInt("c1")), SigmaAddress(r.getInt("r2"), r.getInt("c2")))
    }.getOrNull()

    /** Cells whose input or shown text contains [query], row by row. */
    suspend fun find(query: String, matchCase: Boolean = false, sheet: Int? = null): List<SigmaAddress> {
        val a = JSONArray(callMethod("findAll", sh(sheet), query, matchCase))
        return (0 until a.length()).map { a.getJSONObject(it).let { h -> SigmaAddress(h.getInt("r"), h.getInt("c")) } }
    }

    /** Replaces [query] in every cell's input, as one undo step. Returns how many cells changed. */
    suspend fun replaceAll(query: String, replacement: String, matchCase: Boolean = false, sheet: Int? = null): Int {
        val n = callMethod("replaceAll", sh(sheet), query, replacement, matchCase).toDoubleOrNull()?.toInt() ?: 0
        if (n > 0) changed()
        return n
    }

    suspend fun conditionalRules(sheet: Int? = null): List<SigmaConditionalRule> {
        val a = engine.callArray("cfRules", handle, sh(sheet))
        return (0 until a.length()).map { SigmaConditionalRule.from(a.getJSONObject(it)) }
    }

    suspend fun addConditionalRule(rule: SigmaConditionalRule, sheet: Int? = null) = invoke("addCfRule", sh(sheet), rule.toJson())

    suspend fun removeConditionalRule(index: Int, sheet: Int? = null) = invoke("removeCfRule", sh(sheet), index)

    // Clipboard and fill

    /**
     * A copied block: tab-separated text for other apps, and Sigma's own copy,
     * so pasting inside the workbook shifts formulas like the web does.
     */
    class Clip internal constructor(
        val text: String,
        /** Cut cells are cleared when cut, as on the web, and pasted without shifting formulas. */
        val isCut: Boolean,
        internal val payload: Any,
        internal val sheet: Int,
    )

    suspend fun copy(range: SigmaSelection, sheet: Int? = null): Clip = clip(range, sh(sheet), cut = false)

    /** Copies and clears, as one undo step for the clear. */
    suspend fun cut(range: SigmaSelection, sheet: Int? = null): Clip {
        val c = clip(range, sh(sheet), cut = true)
        clear(range, c.sheet)
        return c
    }

    private suspend fun clip(range: SigmaSelection, sheet: Int, cut: Boolean): Clip {
        val out = JSONObject(callMethod("copyRange", sheet, range.top, range.left, range.bottom, range.right))
        return Clip(out.optString("tsv"), cut, out.opt("internal") ?: JSONObject.NULL, sheet)
    }

    /**
     * Pastes at a cell. With the clip that produced [text] (copied here),
     * formulas shift as on the web; otherwise [text] is read as tab-separated
     * values. Returns how many rows and columns landed.
     */
    suspend fun paste(text: String, at: SigmaAddress, clip: Clip? = null, sheet: Int? = null): Pair<Int, Int> {
        val s = sh(sheet)
        val out = if (clip != null && clip.text == text) {
            JSONObject(callMethod("pasteInternal", s, at.row, at.column, clip.payload, clip.isCut && clip.sheet == s))
        } else {
            JSONObject(callMethod("pasteTSV", s, at.row, at.column, text))
        }
        changed()
        return out.optInt("rows") to out.optInt("cols")
    }

    /** Fills [target] (which contains [source]) from the block at its start: series continue (1, 2, 3…) and formulas shift. */
    suspend fun fill(source: SigmaSelection, target: SigmaSelection, sheet: Int? = null) {
        // Sigma fills the cells beyond the source, in one direction.
        val dst = when {
            target.bottom > source.bottom -> listOf(source.bottom + 1, source.left, target.bottom, source.right)
            target.right > source.right -> listOf(source.top, source.right + 1, source.bottom, target.right)
            target.top < source.top -> listOf(target.top, source.left, source.top - 1, source.right)
            target.left < source.left -> listOf(source.top, target.left, source.bottom, source.left - 1)
            else -> return
        }
        fun box(v: List<Int>) = JSONObject().put("r1", v[0]).put("c1", v[1]).put("r2", v[2]).put("c2", v[3])
        invoke("fill", sh(sheet), box(listOf(source.top, source.left, source.bottom, source.right)), box(dst))
    }

    suspend fun undo(): Boolean = invokeBool("undo")
    suspend fun redo(): Boolean = invokeBool("redo")

    // Sheets

    suspend fun selectSheet(index: Int) {
        if (index !in _sheets.value.indices) return
        engine.call("setActive", handle, index)
        _activeSheet.value = index
    }

    suspend fun addSheet(): Int {
        val i = callMethod("addSheet").toDoubleOrNull()?.toInt() ?: 0
        changed()
        selectSheet(i)
        return i
    }

    suspend fun renameSheet(index: Int, name: String) {
        if (callMethod("renameSheet", index, name) != "true") {
            throw SigmaException.Editor("A sheet name must be unique and cannot contain ! or '.")
        }
        changed()
    }

    suspend fun deleteSheet(index: Int) {
        if (callMethod("deleteSheet", index) != "true") throw SigmaException.Editor("A workbook needs at least one sheet.")
        changed()
        selectSheet(minOf(index, _sheets.value.size - 1))
    }

    // Saving

    /** Sigma's own JSON: every sheet, formula, style and chart. */
    suspend fun serialize(): String = engine.callString("serialize", handle)

    /** The workbook as a file: .xlsx (every sheet), or one sheet as CSV. */
    suspend fun export(format: SigmaFormat, sheet: Int = 0): ByteArray {
        val b64 = engine.callString("exportFile", handle, format.value, sheet)
        return try {
            Base64.decode(b64, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            throw SigmaException.Engine("The export could not be read.")
        }
    }

    /** Stops the engine. A closed workbook cannot be used again. */
    fun close() = engine.close()

    // Internals

    /** SigmaCore.call(handle, method, args) — the editor's own commands. Returns the JSON text. */
    internal suspend fun callMethod(method: String, vararg args: Any?): String {
        val list = JSONArray()
        args.forEach { list.put(JSONObject.wrap(it) ?: JSONObject.NULL) }
        return engine.call("call", handle, method, list)
    }

    private suspend fun invoke(method: String, vararg args: Any?) {
        callMethod(method, *args)
        changed()
    }

    private suspend fun invokeBool(method: String): Boolean {
        val did = callMethod(method) == "true"
        if (did) changed()
        return did
    }

    internal suspend fun refreshInfo() {
        val info = engine.callObject("info", handle)
        val list = info.getJSONArray("sheets")
        val sheets = (0 until list.length()).map { SigmaSheetInfo.from(list.getJSONObject(it)) }
        if (_sheets.value != sheets) _sheets.value = sheets
        val active = info.optInt("active").coerceIn(0, maxOf(0, sheets.size - 1))
        if (_activeSheet.value != active) _activeSheet.value = active
    }

    /** For other files: a change was made through the engine directly. */
    internal suspend fun changed() {
        refreshInfo()
        _revision.value += 1
        onChange?.invoke()
    }

    internal companion object {
        /** Opens Sigma's JSON, or reads a file into a new workbook, on a session's engine. */
        suspend fun open(context: Context, source: String, json: String?, file: ByteArray?, format: SigmaFormat, name: String): SigmaWorkbook {
            val engine = SigmaEngine.start(context, source)
            try {
                val data = json ?: engine.callString("importFile", Base64.encodeToString(file ?: ByteArray(0), Base64.NO_WRAP), format.value, name)
                val handle = engine.callInt("create", data)
                return SigmaWorkbook(engine, handle).also { it.refreshInfo() }
            } catch (e: Exception) {
                engine.close()
                throw e
            }
        }
    }
}
