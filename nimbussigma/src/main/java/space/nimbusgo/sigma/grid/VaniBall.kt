package space.nimbusgo.sigma.grid

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.pow

/**
 * Vani AI's launcher over the grid, as Website Studio draws it: a frosted
 * pill with Nimbo saying hello and "Ask Vani"; on a phone, Nimbo alone.
 * While Vani works Nimbo thinks, the line says so and a ring pulses, even
 * with the panel closed. Tap opens the Vani panel; drag it anywhere and it
 * snaps to the nearer side. Its spot is remembered for the app. Add it to a
 * FrameLayout that covers the grid.
 */
@SuppressLint("ViewConstructor")
internal class VaniBall(context: Context, onTap: () -> Unit) : LinearLayout(context) {
    private val prefs = context.getSharedPreferences("nimbussigma", Context.MODE_PRIVATE)
    /** Room around the pill for Nimbo to spill into and the ring to pulse in; it is the gap to the grid's edges too. */
    private val gutter = context.dp(12f)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private val nimbo = NimboView(context, NimboView.Mood.HELLO, 56f)
    private val title = context.label("Ask Vani", 15f, NimboArt.INK, bold = true)
    private val line = context.label("Describe a change", 12f, MUTED)
    private val words = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(title)
        addView(line, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(1f) })
    }

    var working = false
        set(v) {
            if (field == v) return
            field = v
            nimbo.mood = if (v) NimboView.Mood.THINKING else NimboView.Mood.HELLO
            line.text = if (v) "Vani is working…" else "Describe a change"
            line.setTextColor(if (v) PURPLE else MUTED)
            contentDescription = if (v) "Vani AI, working" else "Ask Vani"
            invalidate()
        }

    /** Studio drops the words below 640 dp wide, as here. */
    private var full = false
        set(v) {
            if (field == v) return
            field = v
            words.visibility = if (v) VISIBLE else GONE
            pad()
        }

    /** Which side it rests on, and how far down (0 top … 1 bottom) of the room it has. */
    private var right = prefs.getBoolean("vaniBall.right", true)
    private var lower = prefs.getFloat("vaniBall.height", 1f)

    private var downX = 0f
    private var downY = 0f
    private var startX = 0f
    private var startY = 0f
    private var dragging = false

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pill = RectF()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        // Nimbo spills over the pill's edges, as in Studio.
        addView(nimbo, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(-context.dp(4f), -context.dp(12f), -context.dp(2f), -context.dp(8f))
        })
        addView(words, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = context.dp(10f) })
        words.visibility = GONE
        pad()
        contentDescription = "Ask Vani"
        tooltipText = "Vani AI"
        isClickable = true
        isFocusable = true
        setOnClickListener { onTap() }
        elevation = context.dp(8f).toFloat()
        if (Build.VERSION.SDK_INT >= 28) {
            outlineSpotShadowColor = Color.rgb(58, 44, 30)
            outlineAmbientShadowColor = Color.rgb(58, 44, 30)
        }
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val g = gutter
                outline.setRoundRect(g, g, view.width - g, view.height - g, (view.height - 2 * g) / 2f)
                outline.alpha = 0.35f
            }
        }
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
    }

    private fun pad() {
        val g = gutter
        val d = context.dp(1f)
        if (full) setPadding(g + context.dp(8f) + d, g + context.dp(6f) + d, g + context.dp(18f) + d, g + context.dp(6f) + d)
        else setPadding(g + context.dp(4f) + d, g + context.dp(4f) + d, g + context.dp(4f) + d, g + context.dp(4f) + d)
    }

    override fun onDraw(canvas: Canvas) {
        val g = gutter.toFloat()
        pill.set(g, g, width - g, height - g)
        val r = pill.height() / 2
        if (working && android.animation.ValueAnimator.areAnimatorsEnabled()) {
            // stOrbPulse: a ring 4 dp out, swelling and fading every 1.8 s.
            val p = NimboArt.phase(System.nanoTime() / 1e9, 1.8)
            val e = (1 - (1 - p).pow(2.2)).toFloat()
            val s = 0.97f + 0.11f * e
            val out = context.dp(4f).toFloat()
            canvas.save()
            canvas.scale(s, s, pill.centerX(), pill.centerY())
            edge.color = Color.argb(((1 - e) * 0.35f * 255).toInt(), 122, 95, 214)
            edge.strokeWidth = context.dp(1.5f).toFloat()
            canvas.drawRoundRect(pill.left - out, pill.top - out, pill.right + out, pill.bottom + out, r + out, r + out, edge)
            canvas.restore()
            postInvalidateOnAnimation()
        }
        fill.color = Color.argb(240, 255, 252, 247)
        canvas.drawRoundRect(pill, r, r, fill)
        val d = context.dp(1f).toFloat()
        edge.strokeWidth = d
        edge.color = Color.argb(153, 255, 255, 255)
        canvas.drawRoundRect(pill.left + d * 1.5f, pill.top + d * 1.5f, pill.right - d * 1.5f, pill.bottom - d * 1.5f, r - d * 1.5f, r - d * 1.5f, edge)
        edge.color = if (working) Color.argb(89, 122, 95, 214) else Color.argb(26, 38, 30, 22)
        canvas.drawRoundRect(pill.left + d / 2, pill.top + d / 2, pill.right - d / 2, pill.bottom - d / 2, r, r, edge)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        (parent as? ViewGroup)?.addOnLayoutChangeListener(relayout)
    }

    override fun onDetachedFromWindow() {
        (parent as? ViewGroup)?.removeOnLayoutChangeListener(relayout)
        super.onDetachedFromWindow()
    }

    private val relayout = View.OnLayoutChangeListener { p, _, _, _, _, _, _, _, _ ->
        full = p.width / resources.displayMetrics.density > 640
        if (!dragging) post { rest(animate = false) }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        invalidateOutline()
        if (!dragging) rest(animate = false)
    }

    private fun bounds(): Pair<Int, Int> {
        val p = parent as? ViewGroup ?: return 0 to 0
        return p.width to p.height
    }

    private fun rest(animate: Boolean) {
        val (w, h) = bounds()
        if (w == 0 || h == 0 || width == 0) return
        val x = (if (right) w - width else 0).toFloat()
        val room = (h - height).coerceAtLeast(0)
        val y = room * lower
        if (animate) {
            animate().translationX(x).translationY(y).setDuration(260).setInterpolator(OvershootInterpolator(1.2f)).start()
        } else {
            translationX = x
            translationY = y
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animate().cancel()
                downX = e.rawX; downY = e.rawY
                startX = translationX; startY = translationY
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                    dragging = true
                    isPressed = false
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) {
                    val (w, h) = bounds()
                    translationX = (startX + dx).coerceIn(0f, (w - width).coerceAtLeast(0).toFloat())
                    translationY = (startY + dy).coerceIn(0f, (h - height).coerceAtLeast(0).toFloat())
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                val (w, h) = bounds()
                val room = (h - height).coerceAtLeast(1)
                right = translationX + width / 2f > w / 2f
                lower = (translationY / room).coerceIn(0f, 1f)
                prefs.edit().putBoolean("vaniBall.right", right).putFloat("vaniBall.height", lower).apply()
                rest(animate = true)
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private companion object {
        val MUTED = Color.rgb(0x9b, 0x91, 0x86)
        val PURPLE = Color.rgb(122, 95, 214)
    }
}
