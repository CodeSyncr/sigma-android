package space.nimbusgo.sigma.grid

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import space.nimbusgo.sigma.SigmaStyle

/** Sigma's palette for the grid, matching the web editor. */
internal object GridColors {
    val ink = Color.rgb(31, 36, 41)
    val error = Color.rgb(217, 48, 38)
    val gridline = Color.rgb(226, 228, 231)
    val border = Color.rgb(61, 64, 69)
    val header = Color.rgb(246, 247, 249)
    val headerText = Color.rgb(94, 99, 107)
    val accent = Color.rgb(24, 128, 56)
    val accentSoft = Color.argb(26, 24, 128, 56)
    val headerSelected = Color.rgb(226, 241, 229)
    val note = Color.rgb(242, 153, 0)
    val frozenLine = Color.rgb(189, 194, 201)
    val secondary = Color.rgb(110, 116, 124)
    val surface = Color.rgb(244, 245, 247)
    val testBanner = Color.rgb(255, 242, 204)
    val testText = Color.rgb(138, 89, 0)

    /** "#rgb" or "#rrggbb", or null. */
    fun hex(s: String?): Int? {
        var h = s?.trim() ?: return null
        if (!h.startsWith("#")) return null
        h = h.substring(1)
        if (h.length == 3) h = h.map { "$it$it" }.joinToString("")
        if (h.length != 6) return null
        val n = h.toIntOrNull(16) ?: return null
        return Color.rgb((n shr 16) and 255, (n shr 8) and 255, n and 255)
    }
}

/** Sigma's colour swatches, the same as the web editor's pickers. */
internal object Palette {
    val rows = listOf(
        listOf("#000000", "#434343", "#666666", "#999999", "#cccccc", "#efefef", "#ffffff"),
        listOf("#c0392b", "#e67e22", "#f1c40f", "#27ae60", "#16a085", "#2980b9", "#8e44ad"),
        listOf("#f4cccc", "#fce5cd", "#fff2cc", "#d9ead3", "#d0e0e3", "#cfe2f3", "#d9d2e9"),
        listOf("#e06666", "#f6b26b", "#ffd966", "#93c47d", "#76a5af", "#6fa8dc", "#8e7cc3"),
    )
}

/** Text paints by look, so a frame never builds one twice. */
internal class TextPaints(scale: Float) {
    private val cache = HashMap<String, TextPaint>()

    /** Pixels per point; changing it (a zoom) drops the paints made at the old size. */
    var scale: Float = scale
        set(v) {
            if (v == field) return
            field = v
            cache.clear()
        }

    fun forCell(style: SigmaStyle?, color: Int): TextPaint {
        val size = (style?.fontSize ?: 13.0).toFloat()
        val bold = style?.bold == true
        val italic = style?.italic == true
        val key = "$size|$bold|$italic|${style?.underline == true}|${style?.strikethrough == true}|$color"
        return cache.getOrPut(key) {
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size * scale
                this.color = color
                typeface = Typeface.create(Typeface.DEFAULT, when {
                    bold && italic -> Typeface.BOLD_ITALIC
                    bold -> Typeface.BOLD
                    italic -> Typeface.ITALIC
                    else -> Typeface.NORMAL
                })
                isUnderlineText = style?.underline == true
                isStrikeThruText = style?.strikethrough == true
            }
        }
    }

    fun plain(sizeDp: Float, color: Int, bold: Boolean = false): TextPaint = cache.getOrPut("p|$sizeDp|$color|$bold") {
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sizeDp * scale
            this.color = color
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }
}
