package space.nimbusgo.sigma

import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime

/**
 * The file format a Sigma sheet is kept in. Every save in the editor rewrites
 * the document's file in its own format, so what you uploaded is what you
 * download — with the edits.
 */
enum class SigmaFormat(val value: String, val mimeType: String) {
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    CSV("csv", "text/csv");

    val fileExtension: String get() = value

    companion object {
        fun of(value: String): SigmaFormat? = entries.firstOrNull { it.value == value }
    }
}

/** What a signed link lets the person holding it do. */
enum class SigmaLinkMode(val value: String) {
    /** Read the sheet; no editing and no saving. */
    VIEW("view"),
    /** Edit the sheet; every change is saved back to the document. */
    EDIT("edit");

    companion object {
        fun of(value: String): SigmaLinkMode? = entries.firstOrNull { it.value == value }
    }
}

/**
 * A signed link to a document. It is a door, not a leash: the link only has
 * to be valid when the editor opens it, and an editing session it starts
 * lasts eight hours.
 */
data class SigmaLink(val url: String, val mode: SigmaLinkMode, val expiresAt: Instant?) {
    internal companion object {
        fun from(o: JSONObject) = SigmaLink(
            url = o.getString("url"),
            mode = SigmaLinkMode.of(o.optString("mode")) ?: SigmaLinkMode.VIEW,
            expiresAt = Dates.parse(o.optString("expires_at")),
        )
    }
}

/** A spreadsheet stored in Nimbus Cloud through the Sigma API. */
data class SigmaDocument(
    val id: Int,
    val title: String,
    val filename: String,
    val mime: String,
    val size: Long,
    val checksum: String,
    val createdAt: Instant?,
    val expiresAt: Instant?,
    /** A signed link, when the call that returned the document minted one. */
    val link: SigmaLink?,
) {
    /** The format the document is kept in, from its file name and type. */
    val format: SigmaFormat
        get() {
            val name = filename.lowercase()
            return if (name.endsWith(".xlsx") || name.endsWith(".xlsm") || mime.contains("spreadsheetml")) SigmaFormat.XLSX else SigmaFormat.CSV
        }

    internal companion object {
        fun from(o: JSONObject): SigmaDocument {
            val url = o.optString("url")
            return SigmaDocument(
                id = o.getInt("id"),
                title = o.optString("title"),
                filename = o.optString("filename"),
                mime = o.optString("mime"),
                size = o.optLong("size"),
                checksum = o.optString("checksum"),
                createdAt = Dates.parse(o.optString("created_at")),
                expiresAt = Dates.parse(o.optString("expires_at")),
                link = if (url.isEmpty()) null else SigmaLink(
                    url, SigmaLinkMode.of(o.optString("mode")) ?: SigmaLinkMode.VIEW, Dates.parse(o.optString("url_expires_at")),
                ),
            )
        }
    }
}

/** This month's allowance of sheet opens. */
data class SigmaUsage(
    val period: String,
    val limit: Int,
    val used: Int,
    val remaining: Int,
    val overage: Int,
    val allowed: Boolean,
    val warn: Boolean,
    val paid: Boolean,
) {
    internal companion object {
        fun from(o: JSONObject) = SigmaUsage(
            o.optString("period"), o.optInt("limit"), o.optInt("used"), o.optInt("remaining"),
            o.optInt("overage"), o.optBoolean("allowed"), o.optBoolean("warn"), o.optBoolean("paid"),
        )
    }
}

/** What one cell computes to. */
sealed class SigmaValue {
    data class Number(val value: Double) : SigmaValue() { override fun toString() = shown() }
    data class Text(val value: String) : SigmaValue() { override fun toString() = shown() }
    data class Bool(val value: Boolean) : SigmaValue() { override fun toString() = shown() }
    data object Empty : SigmaValue() { override fun toString() = shown() }

    /** What the value reads as: "1200", "1.5", "TRUE", text, or "". */
    fun shown(): String = when (this) {
        is Number -> if (value == Math.rint(value) && kotlin.math.abs(value) < 1e15) value.toLong().toString() else value.toString()
        is Text -> value
        is Bool -> if (value) "TRUE" else "FALSE"
        Empty -> ""
    }

    internal companion object {
        fun of(raw: Any?): SigmaValue = when (raw) {
            is Boolean -> Bool(raw)
            is kotlin.Number -> Number(raw.toDouble())
            is String -> if (raw.isEmpty()) Empty else Text(raw)
            else -> Empty
        }
    }
}

/** A sheet exported from the editor. */
class SigmaFile(val data: ByteArray, val filename: String, val format: SigmaFormat)

internal object Dates {
    /** Nimbus Cloud writes RFC 3339 dates, usually with fractional seconds. */
    fun parse(raw: String?): Instant? {
        if (raw.isNullOrEmpty() || raw == "null") return null
        return runCatching { OffsetDateTime.parse(raw).toInstant() }.getOrNull()
    }
}
