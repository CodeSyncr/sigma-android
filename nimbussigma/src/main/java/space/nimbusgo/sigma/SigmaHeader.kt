package space.nimbusgo.sigma

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import space.nimbusgo.sigma.grid.GridColors
import space.nimbusgo.sigma.grid.IconButton
import space.nimbusgo.sigma.grid.column
import space.nimbusgo.sigma.grid.dp
import space.nimbusgo.sigma.grid.label
import space.nimbusgo.sigma.grid.pressable

/**
 * What the editor's header shows: a back arrow, Sigma's mark, the sheet's
 * title with where its edits are, and sharing. Set it with
 * [SigmaSheetView.header]; turn it off when your screen has its own bar.
 *
 * ```kotlin
 * sheetView.header = SigmaHeader(logo = false)   // no mark
 * sheetView.header = SigmaHeader.Hidden          // no header at all
 * sheetView.onBack = { finish() }
 * ```
 */
data class SigmaHeader(
    /** Show the header at all. */
    val visible: Boolean = true,
    /** Show the back arrow. It calls [SigmaSheetView.onBack], or the activity's back when that is not set. */
    val back: Boolean = true,
    /** Show Sigma's mark beside the title. */
    val logo: Boolean = true,
) {
    companion object {
        /** No header: the editor starts at the formula bar. */
        @JvmField val Hidden = SigmaHeader(visible = false)
    }
}

/** The header itself: ← Σ  Title · Saved  share. */
internal class HeaderBar(context: Context) : LinearLayout(context) {
    var onBack: (() -> Unit)? = null
    var onShare: ((View) -> Unit)? = null
    var onRename: (() -> Unit)? = null

    private val back = IconButton(context, R.drawable.nimbussigma_ic_arrow_back, "Back") { onBack?.invoke() }
    private val logo = SigmaMark(context)
    private val title = context.label("", 15f, bold = true).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val status = context.label("", 12f, GridColors.secondary).apply { maxLines = 1 }
    private val heading = context.column(title, status).apply {
        setPadding(dp(6f), dp(4f), dp(6f), dp(4f))
        background = pressable(context, Color.TRANSPARENT, 8f)
        setOnClickListener { onRename?.invoke() }
    }
    private val share = IconButton(context, R.drawable.nimbussigma_ic_share, "Share") { onShare?.invoke(it) }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Color.WHITE)
        setPadding(dp(4f), 0, dp(6f), 0)
        addView(back, LayoutParams(dp(40f), dp(40f)))
        addView(logo, LayoutParams(dp(26f), dp(26f)).apply { setMargins(dp(4f), 0, dp(4f), 0) })
        addView(heading, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(share, LayoutParams(dp(40f), dp(40f)))
        share.visibility = GONE
    }

    fun apply(header: SigmaHeader) {
        visibility = if (header.visible) VISIBLE else GONE
        back.visibility = if (header.back) VISIBLE else GONE
        logo.visibility = if (header.logo) VISIBLE else GONE
        // Without the arrow, the mark or the title sits off the edge.
        setPadding(dp(if (header.back) 4f else 10f), 0, dp(6f), 0)
    }

    /** The sheet's name, where its edits are, and what can be done from here. */
    fun show(name: String, state: String, canShare: Boolean, canRename: Boolean) {
        title.text = name.ifEmpty { "Sheet" }
        status.text = state
        status.visibility = if (state.isEmpty()) GONE else VISIBLE
        share.visibility = if (canShare) VISIBLE else GONE
        heading.isClickable = canRename
        heading.contentDescription = if (canRename) "$name, rename" else name
    }
}

/** Sigma's mark: a green tile with Σ, as on the web. */
internal class SigmaMark(context: Context) : android.widget.TextView(context) {
    init {
        text = "Σ"
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(52, 168, 83), Color.rgb(24, 128, 56))).apply {
            cornerRadius = context.dp(7f).toFloat()
        }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
}
