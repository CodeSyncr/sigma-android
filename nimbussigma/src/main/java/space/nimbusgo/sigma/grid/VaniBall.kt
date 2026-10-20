package space.nimbusgo.sigma.grid

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import space.nimbusgo.sigma.R
import kotlin.math.abs

/**
 * Vani AI as a floating ball over the grid: tap opens the Vani panel; drag
 * it anywhere and it snaps to the nearer side. Its spot is remembered for
 * the app. Add it to a FrameLayout that covers the grid.
 */
@SuppressLint("ViewConstructor")
internal class VaniBall(context: Context, onTap: () -> Unit) : ImageView(context) {
    private val prefs = context.getSharedPreferences("nimbussigma", Context.MODE_PRIVATE)
    private val size = context.dp(52f)
    private val margin = context.dp(12f)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    /** Which side it rests on, and how far down (0 top … 1 bottom) of the room it has. */
    private var right = prefs.getBoolean("vaniBall.right", true)
    private var height = prefs.getFloat("vaniBall.height", 1f)

    private var downX = 0f
    private var downY = 0f
    private var startX = 0f
    private var startY = 0f
    private var dragging = false

    init {
        setImageResource(R.drawable.nimbussigma_ic_auto_awesome)
        setColorFilter(Color.WHITE)
        scaleType = ScaleType.CENTER_INSIDE
        val p = context.dp(13f)
        setPadding(p, p, p, p)
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(232, 77, 61), Color.rgb(250, 115, 23))).apply {
            shape = GradientDrawable.OVAL
        }
        elevation = context.dp(6f).toFloat()
        contentDescription = "Vani AI"
        tooltipText = "Vani AI"
        isClickable = true
        setOnClickListener { onTap() }
        layoutParams = FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.START)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        (parent as? ViewGroup)?.addOnLayoutChangeListener(relayout)
    }

    override fun onDetachedFromWindow() {
        (parent as? ViewGroup)?.removeOnLayoutChangeListener(relayout)
        super.onDetachedFromWindow()
    }

    private val relayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (!dragging) rest(animate = false) }

    private fun bounds(): Pair<Int, Int> {
        val p = parent as? ViewGroup ?: return 0 to 0
        return p.width to p.height
    }

    private fun rest(animate: Boolean) {
        val (w, h) = bounds()
        if (w == 0 || h == 0) return
        val x = (if (right) w - size - margin else margin).toFloat()
        val room = (h - size - margin * 2).coerceAtLeast(0)
        val y = margin + room * height
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
                    translationX = (startX + dx).coerceIn(margin.toFloat(), (w - size - margin).coerceAtLeast(margin).toFloat())
                    translationY = (startY + dy).coerceIn(margin.toFloat(), (h - size - margin).coerceAtLeast(margin).toFloat())
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                val (w, h) = bounds()
                val room = (h - size - margin * 2).coerceAtLeast(1)
                right = translationX + size / 2f > w / 2f
                height = ((translationY - margin) / room).coerceIn(0f, 1f)
                prefs.edit().putBoolean("vaniBall.right", right).putFloat("vaniBall.height", height).apply()
                rest(animate = true)
                return true
            }
        }
        return super.onTouchEvent(e)
    }
}
