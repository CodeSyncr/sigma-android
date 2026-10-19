package space.nimbusgo.sigma.grid

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Small helpers for building the editor's views in code, with its own look whatever the app's theme. */
internal fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
internal fun View.dp(v: Float): Int = context.dp(v)

internal fun rounded(color: Int, radius: Float, strokeColor: Int? = null, strokeWidth: Int = 0) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
    if (strokeColor != null) setStroke(strokeWidth, strokeColor)
}

/** A ripple over a shape, so buttons answer a touch. */
internal fun pressable(context: Context, color: Int = Color.TRANSPARENT, radiusDp: Float = 8f): RippleDrawable {
    val r = context.dp(radiusDp).toFloat()
    return RippleDrawable(ColorStateList.valueOf(Color.argb(40, 0, 0, 0)), rounded(color, r), rounded(Color.WHITE, r))
}

internal fun Context.label(text: String, sizeSp: Float = 14f, color: Int = GridColors.ink, bold: Boolean = false) = TextView(this).apply {
    this.text = text
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    setTextColor(color)
    if (bold) typeface = Typeface.DEFAULT_BOLD
}

/** A tappable text button. */
internal fun Context.button(text: String, sizeSp: Float = 15f, color: Int = GridColors.ink, bold: Boolean = false, onClick: (View) -> Unit) =
    label(text, sizeSp, color, bold).apply {
        gravity = Gravity.CENTER
        minWidth = dp(36f)
        minHeight = dp(36f)
        setPadding(dp(8f), 0, dp(8f), 0)
        background = pressable(context)
        isClickable = true
        setOnClickListener(onClick)
    }

/** A filled, rounded button for the main action of a panel. */
internal fun Context.primaryButton(text: String, onClick: (View) -> Unit) = label(text, 15f, Color.WHITE, bold = true).apply {
    gravity = Gravity.CENTER
    setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
    background = RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), rounded(GridColors.accent, dp(10f).toFloat()), null)
    isClickable = true
    setOnClickListener(onClick)
}

internal fun Context.field(hint: String, text: String = "") = EditText(this).apply {
    this.hint = hint
    setText(text)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    setTextColor(GridColors.ink)
    setHintTextColor(GridColors.secondary)
    background = rounded(GridColors.surface, dp(9f).toFloat())
    setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
    isSingleLine = true
}

internal fun Context.row(vararg views: View, gravity: Int = Gravity.CENTER_VERTICAL) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    this.gravity = gravity
    views.forEach { addView(it) }
}

internal fun Context.column(vararg views: View) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    views.forEach { addView(it) }
}

internal fun Context.divider(vertical: Boolean = false) = View(this).apply {
    setBackgroundColor(GridColors.gridline)
    layoutParams = if (vertical) LinearLayout.LayoutParams(dp(1f), dp(22f)).apply { setMargins(dp(5f), 0, dp(5f), 0) }
    else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
}

internal fun weighted(weight: Float = 1f) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
internal fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
