package space.nimbusgo.sigma.grid

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import space.nimbusgo.sigma.R
import space.nimbusgo.sigma.SigmaFormat
import space.nimbusgo.sigma.SigmaGridController
import space.nimbusgo.sigma.SigmaStyle
import space.nimbusgo.sigma.SigmaWorkbook

/**
 * Styles, colours, alignment, number formats, text size, borders, formulas,
 * sort, charts and the panels: one row of icons that scrolls sideways, over
 * the grid.
 */
internal class FormatBar(
    context: Context,
    private val controller: SigmaGridController,
    /** Shares the sheet as a file; null hides sharing. */
    private val share: ((SigmaFormat) -> Unit)?,
) : HorizontalScrollView(context) {
    private val strip = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val bold = icon(R.drawable.nimbussigma_ic_format_bold, "Bold") { controller.toggleBold() }
    private val italic = icon(R.drawable.nimbussigma_ic_format_italic, "Italic") { controller.toggleItalic() }
    private val underline = icon(R.drawable.nimbussigma_ic_format_underlined, "Underline") { controller.toggleUnderline() }
    private val strike = icon(R.drawable.nimbussigma_ic_format_strikethrough, "Strikethrough") { controller.toggleStrikethrough() }
    private val textColor = Swatch(context, R.drawable.nimbussigma_ic_format_color_text, "Text colour") { pickColor(true) }
    private val fillColor = Swatch(context, R.drawable.nimbussigma_ic_format_color_fill, "Fill colour") { pickColor(false) }
    private val align = icon(R.drawable.nimbussigma_ic_format_align_left, "Alignment") { alignMenu(it) }
    private val number = context.button("123", 13f, bold = true) { numberMenu(it) }.apply {
        contentDescription = "Number format"
        minWidth = dp(44f)
    }
    private val size = context.label("13", 14f, bold = true).apply { gravity = Gravity.CENTER; minWidth = dp(24f) }
    private val note = icon(R.drawable.nimbussigma_ic_sticky_note_2, "Add note") { controller.showPanel(SigmaGridController.Panel.NOTE) }

    init {
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(Color.WHITE)
        addView(strip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48f)))
        strip.setPadding(dp(8f), 0, dp(8f), 0)
        listOf(
            bold, italic, underline, strike, context.groupGap(),
            textColor, fillColor, align, context.groupGap(),
            number,
            icon(R.drawable.nimbussigma_ic_decimal_decrease, "Fewer decimal places") { controller.stepDecimals(-1) },
            icon(R.drawable.nimbussigma_ic_decimal_increase, "More decimal places") { controller.stepDecimals(1) },
            context.groupGap(),
            icon(R.drawable.nimbussigma_ic_text_decrease, "Smaller text") { controller.setFontSize((controller.activeStyle.value.fontSize ?: 13.0) - 1) },
            size,
            icon(R.drawable.nimbussigma_ic_text_increase, "Larger text") { controller.setFontSize((controller.activeStyle.value.fontSize ?: 13.0) + 1) },
            context.groupGap(),
            icon(R.drawable.nimbussigma_ic_border_all, "Borders") { bordersMenu(it) },
            icon(R.drawable.nimbussigma_ic_format_clear, "Clear formatting") { controller.clearFormatting() },
            context.groupGap(),
            icon(R.drawable.nimbussigma_ic_functions, "AutoSum") { controller.autoSum() },
            context.button("ƒx", 15f, bold = true) { controller.showPanel(SigmaGridController.Panel.FUNCTIONS) }.apply { contentDescription = "Insert function"; minWidth = dp(40f) },
            icon(R.drawable.nimbussigma_ic_sort, "Sort") { sortMenu(it) },
            icon(R.drawable.nimbussigma_ic_bar_chart, "Insert chart") { controller.insertChart() },
            context.groupGap(),
            icon(R.drawable.nimbussigma_ic_search, "Find and replace") { controller.showPanel(SigmaGridController.Panel.FIND) },
            icon(R.drawable.nimbussigma_ic_palette, "Conditional formatting") { controller.showPanel(SigmaGridController.Panel.CONDITIONAL_FORMATTING) },
            note,
        ).forEach { strip.addView(it) }
        if (share != null) {
            strip.addView(context.groupGap())
            strip.addView(icon(R.drawable.nimbussigma_ic_share, "Share") { shareMenu(it) })
        }
    }

    fun watch(scope: CoroutineScope) {
        scope.launch {
            combine(controller.activeStyle, controller.activeNote) { s, n -> s to n }.collect { (s, n) ->
                bold.on = s.bold == true
                italic.on = s.italic == true
                underline.on = s.underline == true
                strike.on = s.strikethrough == true
                textColor.color = GridColors.hex(s.textColor) ?: GridColors.ink
                fillColor.color = GridColors.hex(s.fill)
                align.setImageResource(when (s.align) {
                    SigmaStyle.Align.CENTER -> R.drawable.nimbussigma_ic_format_align_center
                    SigmaStyle.Align.RIGHT -> R.drawable.nimbussigma_ic_format_align_right
                    else -> R.drawable.nimbussigma_ic_format_align_left
                })
                number.text = short(s.numberFormat ?: SigmaStyle.NumberFormat.AUTO)
                size.text = (s.fontSize ?: 13.0).toInt().toString()
                note.contentDescription = if (n.isEmpty()) "Add note" else "Edit note"
                note.tint = if (n.isEmpty()) GridColors.ink else GridColors.note
            }
        }
    }

    private fun icon(res: Int, label: String, action: (View) -> Unit) = IconButton(context, res, label, action)

    private fun pickColor(text: Boolean) {
        val s = controller.activeStyle.value
        ColorPicker.show(context, if (text) "Text colour" else "Fill colour", if (text) s.textColor else s.fill, if (text) "Automatic" else "No fill") { hex ->
            if (text) controller.setTextColor(hex) else controller.setFill(hex)
        }
    }

    private fun alignMenu(anchor: View) = menu(anchor, listOf("Left", "Centre", "Right", "Automatic"), current = when (controller.activeStyle.value.align) {
        SigmaStyle.Align.LEFT -> 0; SigmaStyle.Align.CENTER -> 1; SigmaStyle.Align.RIGHT -> 2; null -> 3
    }) { i ->
        controller.setAlign(listOf(SigmaStyle.Align.LEFT, SigmaStyle.Align.CENTER, SigmaStyle.Align.RIGHT, null)[i])
    }

    private fun numberMenu(anchor: View) {
        val formats = SigmaStyle.NumberFormat.entries
        val current = formats.indexOf(controller.activeStyle.value.numberFormat ?: SigmaStyle.NumberFormat.AUTO)
        menu(anchor, formats.map { name(it) }, current) { controller.setNumberFormat(formats[it]) }
    }

    private fun bordersMenu(anchor: View) {
        val kinds = SigmaWorkbook.Borders.entries
        menu(anchor, listOf("All borders", "Outer border", "Top", "Bottom", "Left", "Right", "No borders")) { controller.setBorders(kinds[it]) }
    }

    private fun sortMenu(anchor: View) = menu(anchor, listOf("Sort A → Z", "Sort Z → A")) { controller.sort(it == 0) }

    private fun shareMenu(anchor: View) = menu(anchor, listOf("Excel workbook (.xlsx)", "CSV of this sheet")) {
        share?.invoke(if (it == 0) SigmaFormat.XLSX else SigmaFormat.CSV)
    }

    /** A popup menu; [current] marks the chosen item with a check. */
    private fun menu(anchor: View, items: List<String>, current: Int = -1, pick: (Int) -> Unit) {
        val m = PopupMenu(context, anchor)
        items.forEachIndexed { i, t -> m.menu.add(1, i, i, t).apply { if (current >= 0) { isCheckable = true; isChecked = i == current } } }
        if (current >= 0) m.menu.setGroupCheckable(1, true, true)
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

/** A colour button: its icon over a bar of the current colour. */
internal class Swatch(context: Context, icon: Int, label: String, onClick: () -> Unit) : LinearLayout(context) {
    private val bar = View(context)
    var color: Int? = null
        set(v) {
            field = v
            bar.background = rounded(v ?: Color.TRANSPARENT, dp(1.5f).toFloat(), if (v == null) GridColors.frozenLine else null, dp(1f))
        }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        background = pressable(context)
        contentDescription = label
        tooltipText = label
        isClickable = true
        setOnClickListener { onClick() }
        addView(IconButton(context, icon, label) {}.apply {
            background = null; isClickable = false; isFocusable = false
            setPadding(0, 0, 0, 0)
            layoutParams = LayoutParams(dp(20f), dp(20f))
        })
        addView(bar, LayoutParams(dp(18f), dp(4f)).apply { topMargin = dp(2f) })
        layoutParams = LayoutParams(dp(40f), dp(40f))
        color = null
    }
}

/** The palette, as a bottom sheet: one tap picks a colour. */
internal object ColorPicker {
    fun show(context: Context, title: String, current: String?, clearTitle: String, pick: (String?) -> Unit) {
        val panel = Panel(context, title)
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
                    setOnClickListener { pick(hex); panel.dismiss() }
                }
                line.addView(dot, LinearLayout.LayoutParams(0, context.dp(38f), 1f).apply { setMargins(context.dp(5f), context.dp(5f), context.dp(5f), context.dp(5f)) })
            }
            panel.body.addView(line, matchWidth())
        }
        panel.action(clearTitle, bold = true) { pick(null); panel.dismiss() }
        panel.show()
    }
}

