package space.nimbusgo.sigma

import org.json.JSONObject

/** A cell's place on a sheet. Rows and columns count from zero; "A1" is (0, 0). */
data class SigmaAddress(val row: Int, val column: Int) {
    override fun toString(): String = columnName(column) + (row + 1)

    companion object {
        /** Reads "B12" or "$B$12"; null if it is not an address. */
        fun parse(a1: String): SigmaAddress? {
            var letters = 0
            var digits = 0
            var sawDigit = false
            for (ch in a1.uppercase()) {
                if (ch == '$') continue
                when {
                    ch in 'A'..'Z' && !sawDigit -> letters = letters * 26 + (ch - 'A' + 1)
                    ch in '0'..'9' -> { sawDigit = true; digits = digits * 10 + (ch - '0') }
                    else -> return null
                }
            }
            if (letters == 0 || digits == 0) return null
            return SigmaAddress(digits - 1, letters - 1)
        }

        fun columnName(c: Int): String {
            var n = c + 1
            val out = StringBuilder()
            while (n > 0) {
                val r = (n - 1) % 26
                out.insert(0, ('A' + r))
                n = (n - 1) / 26
            }
            return out.toString()
        }
    }
}

/** A block of cells: the cell the selection started at, and the opposite corner. */
data class SigmaSelection(val active: SigmaAddress, val corner: SigmaAddress = active) {
    val top: Int get() = minOf(active.row, corner.row)
    val bottom: Int get() = maxOf(active.row, corner.row)
    val left: Int get() = minOf(active.column, corner.column)
    val right: Int get() = maxOf(active.column, corner.column)
    val topLeft: SigmaAddress get() = SigmaAddress(top, left)
    val bottomRight: SigmaAddress get() = SigmaAddress(bottom, right)
    val isSingle: Boolean get() = active == corner
    val rowCount: Int get() = bottom - top + 1
    val columnCount: Int get() = right - left + 1

    fun contains(a: SigmaAddress): Boolean = a.row in top..bottom && a.column in left..right

    override fun toString(): String = if (isSingle) active.toString() else "$topLeft:$bottomRight"

    companion object {
        /** Reads "A1" or "A1:C9". */
        fun parse(ref: String): SigmaSelection? {
            val parts = ref.split(":")
            val a = SigmaAddress.parse(parts[0]) ?: return null
            val b = if (parts.size > 1) SigmaAddress.parse(parts[1]) ?: return null else a
            return SigmaSelection(a, b)
        }
    }
}

/** A cell's look. Every field is optional; null means the sheet's default. */
data class SigmaStyle(
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    val strikethrough: Boolean? = null,
    val align: Align? = null,
    /** Points. */
    val fontSize: Double? = null,
    /** "#rrggbb". */
    val textColor: String? = null,
    val fill: String? = null,
    val numberFormat: NumberFormat? = null,
    val decimals: Int? = null,
    val borderTop: Boolean? = null,
    val borderBottom: Boolean? = null,
    val borderLeft: Boolean? = null,
    val borderRight: Boolean? = null,
) {
    enum class Align(val value: String) { LEFT("l"), CENTER("c"), RIGHT("r") }
    enum class NumberFormat(val value: String) { AUTO("auto"), NUMBER("num"), PERCENT("pct"), CURRENCY("cur"), TEXT("text") }

    /** Sigma's own short keys, so the JSON is the workbook's own format: only the fields that are set. */
    internal fun toJson(): JSONObject = JSONObject().apply {
        bold?.let { put("b", it) }
        italic?.let { put("i", it) }
        underline?.let { put("u", it) }
        strikethrough?.let { put("st", it) }
        align?.let { put("al", it.value) }
        fontSize?.let { put("fs", it) }
        textColor?.let { put("tc", it) }
        fill?.let { put("bg", it) }
        numberFormat?.let { put("nf", it.value) }
        decimals?.let { put("dp", it) }
        borderTop?.let { put("bT", it) }
        borderBottom?.let { put("bB", it) }
        borderLeft?.let { put("bL", it) }
        borderRight?.let { put("bR", it) }
    }

    internal companion object {
        fun from(o: JSONObject?): SigmaStyle? {
            if (o == null) return null
            fun bool(k: String) = if (o.has(k) && !o.isNull(k)) o.optBoolean(k) else null
            fun str(k: String) = if (o.has(k) && !o.isNull(k)) o.optString(k) else null
            return SigmaStyle(
                bold = bool("b"), italic = bool("i"), underline = bool("u"), strikethrough = bool("st"),
                align = str("al")?.let { v -> Align.entries.firstOrNull { it.value == v } },
                fontSize = if (o.has("fs")) o.optDouble("fs") else null,
                textColor = str("tc"), fill = str("bg"),
                numberFormat = str("nf")?.let { v -> NumberFormat.entries.firstOrNull { it.value == v } },
                decimals = if (o.has("dp")) o.optInt("dp") else null,
                borderTop = bool("bT"), borderBottom = bool("bB"), borderLeft = bool("bL"), borderRight = bool("bR"),
            )
        }
    }
}

/** What one cell shows, as the grid draws it. */
data class SigmaCell(
    val row: Int,
    val column: Int,
    /** The formatted text, formulas computed. */
    val text: String,
    val isNumber: Boolean,
    val isError: Boolean,
    /** The cell's style with conditional formatting applied. */
    val style: SigmaStyle?,
    val hasNote: Boolean,
) {
    internal companion object {
        fun from(o: JSONObject) = SigmaCell(
            o.getInt("r"), o.getInt("c"), o.optString("t"), o.optInt("n") == 1, o.optInt("e") == 1,
            SigmaStyle.from(o.optJSONObject("s")), o.optInt("note") == 1,
        )
    }
}

/** One sheet of a workbook. */
data class SigmaSheetInfo(val name: String, val rows: Int, val columns: Int, val frozenRows: Int, val frozenColumns: Int) {
    internal companion object {
        fun from(o: JSONObject) = SigmaSheetInfo(o.getString("name"), o.getInt("rows"), o.getInt("cols"), o.optInt("fr"), o.optInt("fc"))
    }
}

/** Column widths and row heights, in Sigma's points (one point is one dp on Android). */
data class SigmaSheetLayout(
    val defaultColumnWidth: Double,
    val defaultRowHeight: Double,
    val columnWidths: Map<Int, Double>,
    val rowHeights: Map<Int, Double>,
) {
    fun widthOfColumn(c: Int): Double = columnWidths[c] ?: defaultColumnWidth
    fun heightOfRow(r: Int): Double = rowHeights[r] ?: defaultRowHeight

    internal companion object {
        fun from(o: JSONObject): SigmaSheetLayout {
            fun map(k: String): Map<Int, Double> {
                val m = o.optJSONObject(k) ?: return emptyMap()
                return m.keys().asSequence().mapNotNull { key -> key.toIntOrNull()?.let { it to m.getDouble(key) } }.toMap()
            }
            return SigmaSheetLayout(o.getDouble("defaultColW"), o.getDouble("defaultRowH"), map("colW"), map("rowH"))
        }
    }
}

/**
 * A conditional formatting rule: cells in a range that meet a condition take
 * on a fill, a text colour, or bold.
 */
data class SigmaConditionalRule(
    val range: SigmaSelection,
    val condition: Condition,
    val value: String = "",
    /** The upper bound, for [Condition.BETWEEN]. */
    val value2: String? = null,
    val fill: String? = null,
    val textColor: String? = null,
    val bold: Boolean? = null,
) {
    enum class Condition(val value: String, val label: String) {
        GREATER_THAN("gt", "Greater than"),
        LESS_THAN("lt", "Less than"),
        AT_LEAST("ge", "Greater than or equal to"),
        AT_MOST("le", "Less than or equal to"),
        EQUAL("eq", "Equal to"),
        NOT_EQUAL("ne", "Not equal to"),
        CONTAINS("contains", "Text contains"),
        BETWEEN("between", "Between"),
        NOT_EMPTY("notempty", "Is not empty");

        val needsValue: Boolean get() = this != NOT_EMPTY
    }

    internal fun toJson(): JSONObject = JSONObject().apply {
        put("r1", range.top); put("c1", range.left); put("r2", range.bottom); put("c2", range.right)
        put("op", condition.value)
        put("v1", value)
        value2?.let { put("v2", it) }
        fill?.let { put("bg", it) }
        textColor?.let { put("tc", it) }
        if (bold == true) put("b", true)
    }

    internal companion object {
        fun from(o: JSONObject) = SigmaConditionalRule(
            range = SigmaSelection(SigmaAddress(o.getInt("r1"), o.getInt("c1")), SigmaAddress(o.getInt("r2"), o.getInt("c2"))),
            condition = Condition.entries.firstOrNull { it.value == o.optString("op") } ?: Condition.NOT_EMPTY,
            value = o.optString("v1"),
            value2 = if (o.has("v2") && !o.isNull("v2")) o.optString("v2") else null,
            fill = if (o.has("bg") && !o.isNull("bg")) o.optString("bg") else null,
            textColor = if (o.has("tc") && !o.isNull("tc")) o.optString("tc") else null,
            bold = if (o.has("b")) o.optBoolean("b") else null,
        )
    }
}

/** A function formulas can use, as Sigma describes it. */
data class SigmaFunction(
    val name: String,
    /** "Math", "Text", "Lookup", "Logical", "Date", "Statistics" or "Info". */
    val category: String,
    /** "SUMIF(range, criterion, [sum_range])" */
    val syntax: String,
    val description: String,
)
