package space.nimbusgo.sigma.grid

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import space.nimbusgo.sigma.SigmaFormat
import space.nimbusgo.sigma.SigmaGridController
import space.nimbusgo.sigma.SigmaStyle
import space.nimbusgo.sigma.SigmaWorkbook

/**
 * Bold, colours, alignment, number formats, text size, borders, sort, charts
 * and the panels: one row that scrolls sideways, over the grid.
 */
internal class FormatBar(
    context: Context,
    private val controller: SigmaGridController,
    /** Shares the sheet as a file; null hides sharing. */
    private val share: ((SigmaFormat) -> Unit)?,
) : HorizontalScrollView(context) {
    private val strip = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val ai = context.label("✦ Vani AI", 14f, Color.WHITE, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(12f), 0, dp(12f), 0)
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.rgb(232, 77, 61), Color.rgb(250, 115, 23))).apply { cornerRadius = dp(16f).toFloat() }
        setOnClickListener { controller.showPanel(SigmaGridController.Panel.AI) }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32f))
    }
    private val aiDivider = context.divider(vertical = true)
    private val bold = toggle("B", "Bold") { controller.toggleBold() }.apply { typeface = Typeface.DEFAULT_BOLD }
    private val italic = toggle("I", "Italic") { controller.toggleItalic() }.apply { setTypeface(Typeface.SERIF, Typeface.ITALIC) }
    private val underline = toggle("U", "Underline") { controller.toggleUnderline() }.apply { paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG }
    private val strike = toggle("S", "Strikethrough") { controller.toggleStrikethrough() }.apply { paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG }
    private val textColor = Swatch(context, "A", "Text colour") { pickColor(true) }
    private val fillColor = Swatch(context, "◩", "Fill colour") { pickColor(false) }
    private val align = context.button("≡", 18f) { alignMenu(it) }.apply { contentDescription = "Alignment" }
    private val number = context.button("123", 13f) { numberMenu(it) }.apply { contentDescription = "Number format" }
    private val size = context.label("13", 14f).apply { gravity = Gravity.CENTER; minWidth = dp(26f) }
    private val note = context.button("✎", 17f) { controller.showPanel(SigmaGridController.Panel.NOTE) }

    init {
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(Color.WHITE)
        addView(strip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44f)))
        strip.setPadding(dp(8f), 0, dp(8f), 0)
        listOf(
            ai, aiDivider, bold, italic, underline, strike, context.divider(true),
            textColor, fillColor, context.divider(true),
            align, number,
            context.button(".0←", 13f) { controller.stepDecimals(-1) }.apply { contentDescription = "Fewer decimal places" },
            context.button(".00→", 13f) { controller.stepDecimals(1) }.apply { contentDescription = "More decimal places" },
            context.divider(true),
            context.button("−", 18f) { controller.setFontSize((controller.activeStyle.value.fontSize ?: 13.0) - 1) }.apply { contentDescription = "Smaller text" },
            size,
            context.button("+", 18f) { controller.setFontSize((controller.activeStyle.value.fontSize ?: 13.0) + 1) }.apply { contentDescription = "Larger text" },
            context.divider(true),
            context.button("▦", 18f) { bordersMenu(it) }.apply { contentDescription = "Borders" },
            context.divider(true),
            context.button("⇅", 18f) { sortMenu(it) }.apply { contentDescription = "Sort" },
            context.button("▥", 18f) { controller.insertChart() }.apply { contentDescription = "Insert chart" },
            context.button("⌕", 20f) { controller.showPanel(SigmaGridController.Panel.FIND) }.apply { contentDescription = "Find and replace" },
            context.button("◐", 17f) { controller.showPanel(SigmaGridController.Panel.CONDITIONAL_FORMATTING) }.apply { contentDescription = "Conditional formatting" },
            note,
        ).forEach { strip.addView(it) }
        if (share != null) {
            strip.addView(context.divider(true))
            strip.addView(context.button("Share", 14f, GridColors.accent, bold = true) { shareMenu(it) })
        }
    }

    fun watch(scope: CoroutineScope) {
        scope.launch {
            combine(controller.activeStyle, controller.activeNote, controller.aiAvailable) { s, n, a -> Triple(s, n, a) }.collect { (s, n, a) ->
                ai.visibility = if (a) VISIBLE else GONE
                aiDivider.visibility = if (a) VISIBLE else GONE
                on(bold, s.bold == true)
                on(italic, s.italic == true)
                on(underline, s.underline == true)
                on(strike, s.strikethrough == true)
                textColor.color = GridColors.hex(s.textColor) ?: GridColors.ink
                fillColor.color = GridColors.hex(s.fill)
                align.text = when (s.align) { SigmaStyle.Align.CENTER -> "☰"; SigmaStyle.Align.RIGHT -> "⫶≡"; else -> "≡" }
                number.text = short(s.numberFormat ?: SigmaStyle.NumberFormat.AUTO)
                size.text = (s.fontSize ?: 13.0).toInt().toString()
                note.contentDescription = if (n.isEmpty()) "Add note" else "Edit note"
                note.setTextColor(if (n.isEmpty()) GridColors.ink else GridColors.note)
            }
        }
    }

    private fun toggle(text: String, label: String, action: () -> Unit) = context.button(text, 17f) { action() }.apply { contentDescription = label }

    private fun on(v: TextView, on: Boolean) {
        v.isSelected = on
        v.setTextColor(if (on) GridColors.accent else GridColors.ink)
        v.background = if (on) pressable(context, GridColors.headerSelected) else pressable(context)
    }

    private fun pickColor(text: Boolean) {
        val s = controller.activeStyle.value
        ColorPicker.show(context, if (text) "Text colour" else "Fill colour", if (text) s.textColor else s.fill, if (text) "Automatic" else "No fill") { hex ->
            if (text) controller.setTextColor(hex) else controller.setFill(hex)
        }
    }

    private fun alignMenu(anchor: View) = menu(anchor, listOf("Left", "Centre", "Right", "Automatic")) { i ->
        controller.setAlign(listOf(SigmaStyle.Align.LEFT, SigmaStyle.Align.CENTER, SigmaStyle.Align.RIGHT, null)[i])
    }

    private fun numberMenu(anchor: View) {
        val formats = SigmaStyle.NumberFormat.entries
        val current = controller.activeStyle.value.numberFormat ?: SigmaStyle.NumberFormat.AUTO
        menu(anchor, formats.map { (if (it == current) "✓ " else "    ") + name(it) }) { controller.setNumberFormat(formats[it]) }
    }

    private fun bordersMenu(anchor: View) {
        val kinds = SigmaWorkbook.Borders.entries
        menu(anchor, listOf("All borders", "Outer border", "Top", "Bottom", "Left", "Right", "No borders")) { controller.setBorders(kinds[it]) }
    }

    private fun sortMenu(anchor: View) = menu(anchor, listOf("Sort A → Z", "Sort Z → A")) { controller.sort(it == 0) }

    private fun shareMenu(anchor: View) = menu(anchor, listOf("Excel workbook (.xlsx)", "CSV of this sheet")) {
        share?.invoke(if (it == 0) SigmaFormat.XLSX else SigmaFormat.CSV)
    }

    private fun menu(anchor: View, items: List<String>, pick: (Int) -> Unit) {
        val m = PopupMenu(context, anchor)
        items.forEachIndexed { i, t -> m.menu.add(0, i, i, t) }
        m.setOnMenuItemClickListener { pick(it.itemId); true }
        m.show()
    }

    private fun name(f: SigmaStyle.NumberFormat) = when (f) {
        SigmaStyle.NumberFormat.AUTO -> "Automatic"
        SigmaStyle.NumberFormat.NUMBER -> "Number  1,234.00"
        SigmaStyle.NumberFormat.PERCENT -> "Percent  12%"
        SigmaStyle.NumberFormat.CURRENCY -> "Currency  ₹1,234.00"
        SigmaStyle.NumberFormat.TEXT -> "Plain text"
    }

    private fun short(f: SigmaStyle.NumberFormat) = when (f) {
        SigmaStyle.NumberFormat.AUTO -> "123"
        SigmaStyle.NumberFormat.NUMBER -> "0.00"
        SigmaStyle.NumberFormat.PERCENT -> "%"
        SigmaStyle.NumberFormat.CURRENCY -> "₹"
        SigmaStyle.NumberFormat.TEXT -> "Aa"
    }
}

/** A colour button: its glyph over a bar of the current colour. */
internal class Swatch(context: Context, glyph: String, label: String, onClick: () -> Unit) : LinearLayout(context) {
    private val bar = View(context)
    var color: Int? = null
        set(v) {
            field = v
            bar.background = rounded(v ?: Color.TRANSPARENT, dp(1.5f).toFloat(), if (v == null) GridColors.frozenLine else null, dp(1f))
        }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        minimumWidth = dp(36f)
        minimumHeight = dp(36f)
        background = pressable(context)
        contentDescription = label
        isClickable = true
        setOnClickListener { onClick() }
        addView(context.label(glyph, 15f, bold = true).apply { gravity = Gravity.CENTER })
        addView(bar, LayoutParams(dp(18f), dp(4f)))
        color = null
    }
}

/** The palette, as a small dialog: one tap picks a colour. */
internal object ColorPicker {
    fun show(context: Context, title: String, current: String?, clearTitle: String, pick: (String?) -> Unit) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val grid = context.column().apply { setPadding(dp(20f), dp(18f), dp(20f), dp(14f)) }
        grid.addView(context.label(title, 17f, bold = true).apply { setPadding(0, 0, 0, dp(12f)) })
        for (row in Palette.rows) {
            val line = context.row()
            for (hex in row) {
                val c = GridColors.hex(hex) ?: Color.BLACK
                val dot = View(context).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(c)
                        when {
                            current?.lowercase() == hex -> setStroke(dp(3f), GridColors.accent)
                            hex == "#ffffff" -> setStroke(dp(1f), GridColors.frozenLine)
                        }
                    }
                    contentDescription = hex
                    setOnClickListener { pick(hex); dialog.dismiss() }
                }
                line.addView(dot, LinearLayout.LayoutParams(context.dp(34f), context.dp(34f)).apply { setMargins(context.dp(4f), context.dp(4f), context.dp(4f), context.dp(4f)) })
            }
            grid.addView(line)
        }
        grid.addView(context.button(clearTitle, 15f, GridColors.accent, bold = true) { pick(null); dialog.dismiss() }.apply {
            layoutParams = matchWidth().apply { topMargin = dp(10f) }
        })
        dialog.setContentView(grid)
        dialog.window?.setBackgroundDrawable(rounded(Color.WHITE, context.dp(16f).toFloat()))
        dialog.show()
    }
}
