package space.nimbusgo.sigma

import org.json.JSONArray
import org.json.JSONObject

/**
 * A chart floating over a sheet. It holds a reference to its data, never a
 * copy: change a cell and the chart redraws.
 */
data class SigmaChart(
    val id: String,
    val kind: Kind,
    val title: String?,
    /** The source block, in A1 notation ("A1:C13", or "Sheet2!A1:B9"). */
    val data: String,
    /** The anchor cell: the chart's top-left corner sits on it. */
    val row: Int,
    val column: Int,
    /** Points (pixels on the web; dp on Android). */
    val width: Double,
    val height: Double,
    val stacked: Boolean? = null,
    val headerRow: Boolean? = null,
    val headerColumn: Boolean? = null,
    /** The one series that carries the story; the rest go grey. */
    val emphasis: Int? = null,
) {
    enum class Kind(val value: String) {
        COLUMN("column"), BAR("bar"), LINE("line"), AREA("area"), PIE("pie"), DOUGHNUT("doughnut"), SCATTER("scatter");

        val label: String get() = value.replaceFirstChar { it.uppercase() }
        val canStack: Boolean get() = this == COLUMN || this == BAR || this == AREA

        companion object {
            fun of(v: String): Kind = entries.firstOrNull { it.value == v } ?: COLUMN
        }
    }

    internal fun toPatch(): JSONObject = JSONObject().apply {
        put("type", kind.value)
        // A cleared title or stack setting has to reach the engine as a removal.
        put("title", title ?: JSONObject.NULL)
        put("data", data)
        put("r", row); put("c", column); put("w", width); put("h", height)
        put("stacked", stacked ?: false)
        headerRow?.let { put("headerRow", it) }
        headerColumn?.let { put("headerCol", it) }
        emphasis?.let { put("emphasis", it) }
    }

    internal companion object {
        fun from(o: JSONObject) = SigmaChart(
            id = o.getString("id"),
            kind = Kind.of(o.optString("type")),
            title = if (o.isNull("title")) null else o.optString("title").ifEmpty { null },
            data = o.optString("data"),
            row = o.optInt("r"), column = o.optInt("c"),
            width = o.optDouble("w", 460.0), height = o.optDouble("h", 280.0),
            stacked = if (o.has("stacked") && !o.isNull("stacked")) o.optBoolean("stacked") else null,
            headerRow = if (o.has("headerRow") && !o.isNull("headerRow")) o.optBoolean("headerRow") else null,
            headerColumn = if (o.has("headerCol") && !o.isNull("headerCol")) o.optBoolean("headerCol") else null,
            emphasis = if (o.has("emphasis") && !o.isNull("emphasis")) o.optInt("emphasis") else null,
        )
    }
}

/** What a chart shows: its categories and its series of numbers. */
data class SigmaChartData(
    val labels: List<String>,
    val series: List<Series>,
    /** Series beyond the most a chart shows. */
    val omitted: Int,
    val error: String?,
) {
    data class Series(
        val name: String,
        /** null where a cell holds no number. */
        val values: List<Double?>,
        /** "#rrggbb", from Sigma's palette. */
        val color: String,
    )

    internal companion object {
        fun from(o: JSONObject): SigmaChartData {
            val labels = o.optJSONArray("labels") ?: JSONArray()
            val series = o.optJSONArray("series") ?: JSONArray()
            return SigmaChartData(
                labels = (0 until labels.length()).map { labels.optString(it) },
                series = (0 until series.length()).map { i ->
                    val s = series.getJSONObject(i)
                    val v = s.optJSONArray("values") ?: JSONArray()
                    Series(
                        s.optString("name"),
                        (0 until v.length()).map { j -> if (v.isNull(j)) null else v.optDouble(j).takeUnless { it.isNaN() } },
                        s.optString("color"),
                    )
                },
                omitted = o.optInt("omitted"),
                error = if (o.isNull("error")) null else o.optString("error").ifEmpty { null },
            )
        }
    }
}

/** What applying a Vani AI patch changed. */
data class SigmaPatchResult(
    val cells: Int,
    val styles: Int,
    val charts: Int,
    val skipped: Int,
    /** "Applied 12 cells, 3 formats". */
    val summary: String,
)

/** One turn in a conversation with Vani AI. */
data class SigmaAIMessage(
    val role: Role,
    val text: String,
    /** The change Vani AI proposes, as Sigma's patch JSON; applied only when the person says so. */
    val patch: String? = null,
    /** "12 cells · 3 formats · 1 chart". */
    val patchSummary: String? = null,
    /** Set once the patch has been applied, so it cannot be applied twice. */
    val applied: String? = null,
) {
    enum class Role(val value: String) { USER("user"), ASSISTANT("assistant") }
}

/** The sheet's charts, each with the data it currently shows. */
suspend fun SigmaWorkbook.charts(sheet: Int? = null): List<Pair<SigmaChart, SigmaChartData>> {
    val a = engine.callArray("charts", handle, sheet ?: activeSheet.value)
    return (0 until a.length()).map { i ->
        val e = a.getJSONObject(i)
        SigmaChart.from(e.getJSONObject("chart")) to SigmaChartData.from(e.getJSONObject("data"))
    }
}

/** Adds a chart of a block of cells ("A1:C13"), beside the data unless told where. */
suspend fun SigmaWorkbook.addChart(
    kind: SigmaChart.Kind = SigmaChart.Kind.COLUMN,
    data: String,
    title: String? = null,
    at: SigmaAddress? = null,
    sheet: Int? = null,
): SigmaChart {
    val sh = sheet ?: activeSheet.value
    val place = at ?: engine.callObject("anchor", handle, sh).let { SigmaAddress(it.getInt("r"), it.getInt("c")) }
    val spec = JSONObject().put("type", kind.value).put("data", data).put("r", place.row).put("c", place.column).put("w", 460).put("h", 280)
    if (!title.isNullOrEmpty()) spec.put("title", title)
    val made = SigmaChart.from(JSONObject(callMethod("addChart", sh, spec)))
    changed()
    return made
}

/** Saves a chart's new type, title, data, place or size. */
suspend fun SigmaWorkbook.updateChart(chart: SigmaChart, sheet: Int? = null) {
    callMethod("updateChart", sheet ?: activeSheet.value, chart.id, chart.toPatch())
    changed()
}

suspend fun SigmaWorkbook.removeChart(id: String, sheet: Int? = null) {
    callMethod("removeChart", sheet ?: activeSheet.value, id)
    changed()
}

/** Applies a Vani AI patch as one undo step. */
suspend fun SigmaWorkbook.applyPatch(json: String, sheet: Int? = null): SigmaPatchResult {
    val o = engine.callObject("applyOps", handle, sheet ?: activeSheet.value, json)
    changed()
    return SigmaPatchResult(o.optInt("cells"), o.optInt("styles"), o.optInt("charts"), o.optInt("skipped"), o.optString("summary"))
}

/** "12 cells · 3 formats · 1 chart". */
internal suspend fun SigmaWorkbook.describePatch(json: String): String = runCatching { engine.callString("describeOps", json) }.getOrDefault("")

/**
 * Asks Vani AI about the open sheet. The answer may carry a patch, which
 * [applyPatch] applies as one undo step. Uses the document owner's plan;
 * [SigmaSession.aiAvailable] says whether they have it.
 */
suspend fun SigmaSession.ask(prompt: String, history: List<SigmaAIMessage> = emptyList(), selection: SigmaSelection? = null): SigmaAIMessage {
    val book = workbook.value ?: throw SigmaException.NotReady()
    val access = currentAccess ?: throw SigmaException.NotReady()
    val context = engine(book).callObject("context", book.handle, book.activeSheet.value)
    val sheets = book.sheets.value
    val body = JSONObject()
        .put("prompt", prompt)
        .put("workbook_title", title.value)
        .put("sheet_name", sheets.getOrNull(book.activeSheet.value)?.name ?: "")
        .put("sheets", JSONArray(sheets.map { it.name }))
        .put("selection", selection?.toString() ?: "")
        .put("grid", context.optString("grid"))
        .put("used_range", context.optString("used"))
        .put("history", JSONArray(history.takeLast(10).map { JSONObject().put("role", it.role.value).put("text", it.text) }))
        .put("chart_mode", "auto")
    // Building a whole sheet is a long generation.
    val res = send(request(access, "/ai", "POST", body.toString().toByteArray(), "application/json"), timeoutMillis = 240_000)
    val answer = runCatching { JSONObject(res.text) }.getOrNull()
        ?: throw SigmaException.Server(200, "Vani AI sent an answer this version cannot read.")
    val ops = answer.optJSONObject("ops")
    val patch = if (ops != null && ops.length() > 0) ops.toString() else null
    return SigmaAIMessage(
        SigmaAIMessage.Role.ASSISTANT,
        answer.optString("reply").ifEmpty { "Done." },
        patch,
        patch?.let { book.describePatch(it) },
    )
}

private fun engine(book: SigmaWorkbook) = book.engine
