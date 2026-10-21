package space.nimbusgo.sigma.grid

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import space.nimbusgo.sigma.R
import java.lang.ref.WeakReference

/** One row of a [SigmaMenu]: an action, or a submenu when it has [children]. */
internal class MenuEntry(
    val title: String,
    val icon: Int = 0,
    val checked: Boolean = false,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
    val children: List<MenuEntry> = emptyList(),
    val run: (() -> Unit)? = null,
)

/** A button in the strip across the top of the cell menu (cut, copy, paste, clear). */
internal class MenuQuick(val icon: Int, val label: String, val enabled: Boolean = true, val run: () -> Unit)

/**
 * The editor's menu, drawn by the SDK rather than the platform, so it looks
 * the same in every app and as on iOS: a rounded card by the finger or under
 * its button, with submenus that slide in place of the list.
 */
internal class SigmaMenu(
    private val context: Context,
    private val entries: List<MenuEntry>,
    private val quick: List<MenuQuick> = emptyList(),
) {
    private val shadow = context.dp(14f)
    private val gap = context.dp(4f)
    private val margin = context.dp(8f)
    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = object : ScrollView(context) {
        var maxHeight = Int.MAX_VALUE
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
        }
    }.apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(list)
    }
    private val strip = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val stripRule = View(context).apply { setBackgroundColor(GridColors.gridline) }
    private val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Color.WHITE, context.dp(14f).toFloat(), Color.argb(22, 0, 0, 0), 1)
        elevation = context.dp(10f).toFloat()
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            outlineAmbientShadowColor = Color.argb(70, 0, 0, 0)
            outlineSpotShadowColor = Color.argb(90, 0, 0, 0)
        }
    }
    private val shell = FrameLayout(context).apply {
        setPadding(shadow, shadow, shadow, shadow)
        clipToPadding = false
        addView(card, FrameLayout.LayoutParams(context.dp(232f), ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private val popup = PopupWindow(shell, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        isOutsideTouchable = true
        animationStyle = 0
        isClippingEnabled = false
    }
    private var anchor: View? = null
    private var target = Rect()
    private var below = true
    private val stack = ArrayList<MenuEntry>()

    init {
        if (quick.isNotEmpty()) {
            quick.forEach { q -> strip.addView(quickButton(q), LinearLayout.LayoutParams(0, context.dp(58f), 1f)) }
            strip.setPadding(context.dp(6f), context.dp(4f), context.dp(6f), 0)
            card.addView(strip, matchWidth())
            card.addView(stripRule, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { setMargins(0, context.dp(4f), 0, 0) })
        }
        list.setPadding(context.dp(6f), context.dp(6f), context.dp(6f), context.dp(6f))
        card.addView(scroll, matchWidth())
        render(entries, null)
        popup.setOnDismissListener { if (showing?.get() === this) showing = null }
    }

    /** Opens at a point in [view] (where a finger pressed), below it unless there is no room. */
    fun showAt(view: View, x: Float, y: Float) {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        show(view, Rect(loc[0] + x.toInt(), loc[1] + y.toInt(), loc[0] + x.toInt(), loc[1] + y.toInt()))
    }

    /** Opens under a button, lined up with its nearer edge. */
    fun showUnder(button: View) {
        val loc = IntArray(2)
        button.getLocationOnScreen(loc)
        show(button, Rect(loc[0], loc[1], loc[0] + button.width, loc[1] + button.height))
    }

    fun dismiss() = popup.dismiss()

    private fun show(view: View, rect: Rect) {
        showing?.get()?.dismiss()
        showing = WeakReference(this)
        anchor = view
        target = rect
        val (x, y) = place()
        popup.showAtLocation(view.rootView, Gravity.NO_GRAVITY, x, y)
        card.pivotX = if (x + shadow > target.centerX() - context.dp(40f)) 0f else context.dp(232f).toFloat()
        card.pivotY = if (below) 0f else card.height.toFloat().coerceAtLeast(1f)
        card.alpha = 0f
        card.scaleX = 0.96f
        card.scaleY = 0.96f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(120).setInterpolator(EASE).start()
    }

    /** Where the window goes: by the target, flipped above when below would not fit, always on screen. */
    private fun place(): Pair<Int, Int> {
        val view = anchor ?: return 0 to 0
        val frame = Rect()
        view.getWindowVisibleDisplayFrame(frame)
        scroll.maxHeight = (frame.height() * 0.7f).toInt() - (if (stack.isEmpty() && quick.isNotEmpty()) context.dp(63f) else 0)
        shell.measure(
            View.MeasureSpec.makeMeasureSpec(frame.width(), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(frame.height(), View.MeasureSpec.AT_MOST),
        )
        val w = shell.measuredWidth - 2 * shadow
        val h = shell.measuredHeight - 2 * shadow
        val point = target.width() == 0
        var left = when {
            point -> target.left
            target.centerX() > frame.centerX() -> target.right - w
            else -> target.left
        }
        left = left.coerceIn(frame.left + margin, maxOf(frame.left + margin, frame.right - margin - w))
        below = target.bottom + gap + h <= frame.bottom - margin || target.top - gap - h < frame.top + margin
        var top = if (below) target.bottom + gap else target.top - gap - h
        top = top.coerceIn(frame.top + margin, maxOf(frame.top + margin, frame.bottom - margin - h))
        // The popup is placed in its parent window's coordinates, not the screen's: a
        // dialog (a panel's dropdown) starts part way down the screen.
        val origin = IntArray(2)
        view.rootView.getLocationOnScreen(origin)
        return (left - shadow - origin[0]) to (top - shadow - origin[1])
    }

    private fun render(items: List<MenuEntry>, parent: MenuEntry?) {
        list.removeAllViews()
        strip.visibility = if (parent == null && quick.isNotEmpty()) View.VISIBLE else View.GONE
        stripRule.visibility = strip.visibility
        if (parent != null) list.addView(backRow(parent), matchWidth())
        val icons = items.any { it.icon != 0 }
        items.forEach { list.addView(row(it, icons), matchWidth()) }
    }

    private fun open(entry: MenuEntry) {
        stack.add(entry)
        slide(entry.children, entry, forward = true)
    }

    private fun back() {
        stack.removeAt(stack.size - 1)
        slide(stack.lastOrNull()?.children ?: entries, stack.lastOrNull(), forward = false)
    }

    private fun slide(items: List<MenuEntry>, parent: MenuEntry?, forward: Boolean) {
        render(items, parent)
        list.translationX = (if (forward) 1 else -1) * context.dp(40f).toFloat()
        list.alpha = 0f
        list.animate().translationX(0f).alpha(1f).setDuration(160).setInterpolator(EASE).start()
        // The card changes height: keep it where it opened (its top below the finger, or its bottom above it).
        shell.post {
            if (!popup.isShowing) return@post
            val (x, y) = place()
            popup.update(x, y, -1, -1)
        }
    }

    private fun row(e: MenuEntry, icons: Boolean): View {
        val ink = if (e.destructive) GridColors.error else GridColors.ink
        val r = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(40f)
            setPadding(context.dp(10f), 0, context.dp(10f), 0)
            background = pressable(context, Color.TRANSPARENT, 9f)
            isEnabled = e.enabled
            alpha = if (e.enabled) 1f else 0.4f
        }
        if (icons) {
            r.addView(glyph(e.icon, if (e.destructive) GridColors.error else GridColors.secondary),
                LinearLayout.LayoutParams(context.dp(18f), context.dp(18f)).apply { rightMargin = context.dp(12f) })
        }
        r.addView(context.label(e.title, 14f, ink).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        when {
            e.children.isNotEmpty() -> r.addView(glyph(R.drawable.nimbussigma_ic_chevron_right, GridColors.secondary),
                LinearLayout.LayoutParams(context.dp(18f), context.dp(18f)))
            e.checked -> r.addView(glyph(R.drawable.nimbussigma_ic_check, GridColors.accent),
                LinearLayout.LayoutParams(context.dp(18f), context.dp(18f)))
        }
        if (e.enabled) {
            r.isClickable = true
            r.contentDescription = if (e.checked) "${e.title}, selected" else e.title
            r.setOnClickListener {
                if (e.children.isNotEmpty()) open(e)
                else {
                    popup.dismiss()
                    e.run?.invoke()
                }
            }
        }
        return r
    }

    private fun backRow(parent: MenuEntry) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dp(40f)
        setPadding(context.dp(10f), 0, context.dp(10f), 0)
        background = pressable(context, Color.TRANSPARENT, 9f)
        addView(glyph(R.drawable.nimbussigma_ic_arrow_back, GridColors.ink),
            LinearLayout.LayoutParams(context.dp(18f), context.dp(18f)).apply { rightMargin = context.dp(12f) })
        addView(context.label(parent.title, 14f, GridColors.ink, bold = true))
        contentDescription = "Back"
        isClickable = true
        setOnClickListener { back() }
    }

    private fun quickButton(q: MenuQuick) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = pressable(context, Color.TRANSPARENT, 9f)
        alpha = if (q.enabled) 1f else 0.4f
        addView(glyph(q.icon, GridColors.ink), LinearLayout.LayoutParams(context.dp(20f), context.dp(20f)))
        addView(context.label(q.label, 11f, GridColors.secondary).apply { setPadding(0, context.dp(4f), 0, 0) })
        contentDescription = q.label
        if (q.enabled) {
            isClickable = true
            setOnClickListener {
                popup.dismiss()
                q.run()
            }
        }
    }

    private fun glyph(res: Int, tint: Int) = ImageView(context).apply {
        if (res != 0) setImageResource(res)
        setColorFilter(tint)
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    companion object {
        /** One menu at a time, so a new one replaces the last. */
        private var showing: WeakReference<SigmaMenu>? = null
        private val EASE = PathInterpolator(0.32f, 0.72f, 0f, 1f)

        fun dismissShowing() { showing?.get()?.dismiss() }
    }
}

/** A choice among a few options, as a field that opens a [SigmaMenu]: the editor's own dropdown. */
internal class Picker(context: Context, private val items: List<String>) : LinearLayout(context) {
    private val text = context.label(items.firstOrNull() ?: "", 15f).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    var onChange: (() -> Unit)? = null
    var selected = 0
        set(v) {
            field = v.coerceIn(0, maxOf(0, items.size - 1))
            text.text = items.getOrNull(field) ?: ""
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dp(42f)
        setPadding(context.dp(12f), 0, context.dp(8f), 0)
        background = pressable(context, GridColors.surface, 9f)
        addView(text, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(ImageView(context).apply {
            setImageResource(R.drawable.nimbussigma_ic_keyboard_arrow_down)
            setColorFilter(GridColors.secondary)
        }, LayoutParams(context.dp(20f), context.dp(20f)))
        isClickable = true
        setOnClickListener {
            SigmaMenu(context, items.mapIndexed { i, t ->
                MenuEntry(t, checked = i == selected) {
                    selected = i
                    onChange?.invoke()
                }
            }).showUnder(this)
        }
    }
}
