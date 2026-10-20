package space.nimbusgo.sigma.grid

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * Nimbo, Vani's mascot, as Website Studio draws him (public/js/nimbo.js and
 * public/css/nimbo.css in Nimbus Cloud): the same shapes, colours and motion,
 * in the same 170 × 160 box. The moods Vani's launcher and panel use.
 */
internal class NimboView(context: Context, mood: Mood, private val widthDp: Float) : View(context) {
    enum class Mood { HELLO, THINKING, LOADING }

    var mood: Mood = mood
        set(v) { if (field != v) { field = v; invalidate() } }

    private val still = !ValueAnimator.areAnimatorsEnabled()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = context.dp(widthDp)
        setMeasuredDimension(w, (w * 160f / 170f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val t = if (still) 0.0 else System.nanoTime() / 1e9
        canvas.save()
        canvas.scale(width / 170f, height / 160f)
        NimboArt.draw(canvas, mood, t)
        canvas.restore()
        if (!still && isShown) postInvalidateOnAnimation()
    }
}

internal object NimboArt {
    const val INK = 0xFF1F1A15.toInt()
    const val CREAM = 0xFFFFF8EF.toInt()
    const val EMBER = 0xFFF04E28.toInt()
    const val AMBER = 0xFFF5A623.toInt()
    const val SKY = 0xFFBCD9F5.toInt()
    const val SOCKET = 0xFFE9DFD2.toInt()

    // Timing, as CSS keyframes with ease-in-out between them.
    fun ease(x: Double): Double { val s = x.coerceIn(0.0, 1.0); return s * s * (3 - 2 * s) }
    fun phase(t: Double, period: Double, delay: Double = 0.0): Double { val p = (t - delay) / period; return p - Math.floor(p) }
    /** 0 → 1 at the middle → 0: every "50% { … }" keyframe. */
    fun swing(p: Double): Double = ease(if (p < 0.5) p * 2 else (1 - p) * 2)
    /** A value through keyframes (offset 0…1 to value), eased between each pair. */
    fun keyframes(p: Double, vararg frames: Pair<Double, Double>): Double {
        if (p <= frames.first().first) return frames.first().second
        for (i in 1 until frames.size) {
            val a = frames[i - 1]; val b = frames[i]
            if (p <= b.first) return a.second + (b.second - a.second) * ease((p - a.first) / maxOf(b.first - a.first, 0.0001))
        }
        return frames.last().second
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    private val shape = Path().apply {
        addCircle(46f, 96f, 24f, Path.Direction.CW); addCircle(66f, 72f, 26f, Path.Direction.CW)
        addCircle(96f, 62f, 30f, Path.Direction.CW); addCircle(124f, 86f, 24f, Path.Direction.CW)
        addRoundRect(RectF(30f, 92f, 142f, 126f), 17f, 17f, Path.Direction.CW)
    }
    private val band = Path().apply { moveTo(0f, 104f); quadTo(86f, 122f, 172f, 104f); lineTo(172f, 115f); quadTo(86f, 133f, 0f, 115f); close() }
    private val bandLines = Path().apply { moveTo(0f, 104f); quadTo(86f, 122f, 172f, 104f); moveTo(0f, 115f); quadTo(86f, 133f, 172f, 115f) }
    private val strap = Path().apply { moveTo(50f, 58f); quadTo(88f, 42f, 126f, 58f) }
    // The SVG arcs "M70 47 A7 7 0 0 1 77 43" and "M96 45 A7 7 0 0 1 103 41".
    private val glint = Path().apply {
        arcTo(RectF(76.34f - 7, 49.97f - 7, 76.34f + 7, 49.97f + 7), 205.1f, 70.3f, true)
        arcTo(RectF(102.34f - 7, 47.97f - 7, 102.34f + 7, 47.97f + 7), 205.1f, 70.3f, true)
    }
    private val browsUp = Path().apply { moveTo(69f, 74f); quadTo(75f, 70f, 81f, 73f); moveTo(95f, 73f); quadTo(101f, 70f, 107f, 74f) }
    private val browsLifting = Path().apply { moveTo(69f, 75f); quadTo(75f, 72f, 81f, 75f); moveTo(95f, 75f); quadTo(101f, 72f, 107f, 75f) }
    private val grin = Path().apply { moveTo(78f, 99f); quadTo(89f, 114f, 100f, 98f); quadTo(89f, 104f, 78f, 99f); close() }
    private val tongue = Path().apply { moveTo(84f, 106f); quadTo(89f, 109f, 94f, 105f) }
    private val pursed = Path().apply { moveTo(84f, 102f); quadTo(89f, 100f, 94f, 102f) }

    private fun fill(c: Canvas, color: Int, draw: (Paint) -> Unit) { fill.color = color; draw(fill) }
    private fun stroke(c: Canvas, color: Int, width: Float, draw: (Paint) -> Unit) { stroke.color = color; stroke.strokeWidth = width; draw(stroke) }
    private fun about(c: Canvas, x: Float, y: Float, apply: () -> Unit) { c.translate(x, y); apply(); c.translate(-x, -y) }

    private fun hand(c: Canvas, x: Float, y: Float) {
        fill(c, CREAM) { c.drawCircle(x, y, 9f, it) }
        stroke(c, INK, 4.5f) { c.drawCircle(x, y, 9f, it) }
    }

    private fun pupils(c: Canvas) {
        fill(c, INK) { p -> for (x in listOf(76f, 100f)) c.drawOval(x - 5, 89 - 6.5f, x + 5, 89 + 6.5f, p) }
        fill(c, Color.WHITE) { p -> for (x in listOf(78f, 102f)) c.drawCircle(x, 86.5f, 1.8f, p) }
    }

    fun draw(c: Canvas, mood: NimboView.Mood, t: Double) {
        c.save()
        if (mood == NimboView.Mood.LOADING) {
            // nb-wait: he looks one way, the other, then up, leaning as he looks.
            val p = phase(t, 3.6)
            val rot = keyframes(p, 0.0 to 0.0, 0.06 to 0.0, 0.16 to -5.0, 0.30 to -5.0, 0.42 to 5.0, 0.58 to 5.0, 0.70 to -1.0, 0.86 to -1.0, 1.0 to 0.0)
            val dx = keyframes(p, 0.0 to 0.0, 0.06 to 0.0, 0.16 to -4.0, 0.30 to -4.0, 0.42 to 4.0, 0.58 to 4.0, 0.70 to 0.0, 1.0 to 0.0)
            val dy = keyframes(p, 0.0 to 0.0, 0.58 to 0.0, 0.70 to -4.0, 0.86 to -4.0, 1.0 to 0.0)
            about(c, 89f, 85f) { c.rotate(rot.toFloat()); c.translate(dx.toFloat(), dy.toFloat()) }
        } else {
            // nb-bob: 5 up and back every 2.8 s.
            c.translate(0f, (-5 * swing(phase(t, 2.8))).toFloat())
        }
        // nb-breathe, about the middle of him.
        val br = swing(phase(t, 3.6)).toFloat()
        about(c, if (mood == NimboView.Mood.THINKING) 85f else 89f, 79f) { c.scale(1 + 0.025f * br, 1 - 0.025f * br) }

        // Body: an ink outline, cream inside, an ember band clipped to it.
        stroke(c, INK, 10f) { c.drawPath(shape, it) }
        fill(c, INK) { c.drawPath(shape, it) }
        fill(c, CREAM) { c.drawPath(shape, it) }
        c.save(); c.clipPath(shape)
        fill(c, EMBER) { c.drawPath(band, it) }
        stroke(c, INK, 4f) { c.drawPath(bandLines, it) }
        c.restore()

        // Goggles, up on his head; nb-glint dims them at 80% of 2.6 s.
        stroke(c, INK, 6f) { c.drawPath(strap, it) }
        for ((x, y) in listOf(76f to 52f, 102f to 50f)) {
            fill(c, SKY) { c.drawCircle(x, y, 12f, it) }
            stroke(c, INK, 5f) { c.drawCircle(x, y, 12f, it) }
        }
        val g = keyframes(phase(t, 2.6), 0.0 to 0.9, 0.7 to 0.9, 0.8 to 0.2, 1.0 to 0.9)
        stroke(c, Color.argb((g * 255).toInt(), 255, 255, 255), 3f) { c.drawPath(glint, it) }

        // Eyes.
        c.save()
        if (mood == NimboView.Mood.LOADING) {
            // nb-look: off to one side, the other, then up; nb-lids blink at 38% and 90%.
            val p = phase(t, 3.6)
            val lx = keyframes(p, 0.0 to 0.0, 0.06 to 0.0, 0.14 to -8.0, 0.30 to -8.0, 0.40 to 9.0, 0.58 to 9.0, 0.68 to 3.0, 0.86 to 3.0, 1.0 to 0.0)
            val ly = keyframes(p, 0.0 to 0.0, 0.06 to 0.0, 0.14 to 1.0, 0.30 to 1.0, 0.40 to 0.0, 0.58 to 0.0, 0.68 to -7.0, 0.86 to -7.0, 1.0 to 0.0)
            c.translate(lx.toFloat(), ly.toFloat())
            val lid = keyframes(p, 0.0 to 1.0, 0.36 to 1.0, 0.38 to 0.1, 0.40 to 1.0, 0.88 to 1.0, 0.90 to 0.1, 0.92 to 1.0, 1.0 to 1.0)
            about(c, 88f, 89f) { c.scale(1f, lid.toFloat()) }
        } else {
            if (mood == NimboView.Mood.THINKING) c.translate(2f, -4f)
            // nb-blink: shut at 47% of 4.2 s.
            val sy = keyframes(phase(t, 4.2), 0.0 to 1.0, 0.44 to 1.0, 0.47 to 0.08, 0.50 to 1.0, 1.0 to 1.0)
            about(c, 88f, 89f) { c.scale(1f, sy.toFloat()) }
        }
        pupils(c)
        c.restore()

        // Brows: raised; while loading they lift as each dot lands.
        if (mood == NimboView.Mood.LOADING) {
            c.save()
            c.translate(0f, keyframes(phase(t, 1.2), 0.0 to 0.0, 0.7 to 0.0, 0.8 to -3.0, 1.0 to 0.0).toFloat())
            stroke(c, INK, 4f) { c.drawPath(browsLifting, it) }
            c.restore()
        } else {
            stroke(c, INK, 4f) { c.drawPath(browsUp, it) }
        }
        // Mouth.
        when (mood) {
            NimboView.Mood.HELLO -> {
                fill(c, INK) { c.drawPath(grin, it) }
                stroke(c, INK, 3f) { c.drawPath(grin, it) }
                stroke(c, EMBER, 3f) { c.drawPath(tongue, it) }
            }
            NimboView.Mood.THINKING -> fill(c, INK) { c.drawOval(85f, 98f, 93f, 108f, it) }
            NimboView.Mood.LOADING -> stroke(c, INK, 4f) { c.drawPath(pursed, it) }
        }
        // Cheeks.
        fill(c, Color.argb(140, 0xF5, 0xA6, 0x23)) { p -> c.drawCircle(64f, 100f, 5f, p); c.drawCircle(112f, 100f, 5f, p) }

        // Hands.
        when (mood) {
            NimboView.Mood.HELLO -> {
                // nb-wave: -8° → 26° → -8° every 1.1 s, about 20% 90% of the hand.
                c.save()
                about(c, 141f + 18 * 0.2f, 61f + 18 * 0.9f) { c.rotate((-8 + 34 * swing(phase(t, 1.1))).toFloat()) }
                hand(c, 150f, 70f)
                c.restore()
                hand(c, 28f, 112f)
            }
            NimboView.Mood.THINKING -> hand(c, 96f, 116f)
            NimboView.Mood.LOADING -> {
                // nb-tap: a hand taps; nb-shade: the other comes up to shade his eyes.
                c.save(); c.translate(0f, (5 * swing(phase(t, 0.6))).toFloat()); hand(c, 142f, 112f); c.restore()
                val k = keyframes(phase(t, 3.6), 0.0 to 0.0, 0.34 to 0.0, 0.42 to 1.0, 0.58 to 1.0, 0.64 to 0.0, 1.0 to 0.0).toFloat()
                if (k > 0.01f) {
                    c.saveLayerAlpha(0f, 0f, 170f, 160f, (k * 255).toInt())
                    c.translate(-12 * k, -42 * k)
                    hand(c, 128f, 120f)
                    c.restore()
                }
            }
        }
        c.restore()

        when (mood) {
            NimboView.Mood.THINKING -> {
                // Three dots rise beside him.
                for ((i, d) in listOf(Triple(140f, 40f, 5f), Triple(153f, 28f, 6.5f), Triple(166f, 14f, 8f)).withIndex()) {
                    val s = swing(phase(t, 1.2, delay = 0.2 * i)).toFloat()
                    val base = if (i == 2) EMBER else AMBER
                    fill(c, Color.argb(((0.25f + 0.75f * s) * 255).toInt(), Color.red(base), Color.green(base), Color.blue(base))) {
                        c.drawCircle(d.first, d.second, d.third * (0.8f + 0.35f * s), it)
                    }
                }
            }
            NimboView.Mood.LOADING -> {
                // nb-fill: three dots below him fill in turn.
                for ((i, x) in listOf(66f, 86f, 106f).withIndex()) {
                    val k = keyframes(phase(t, 1.2, delay = 0.4 * i), 0.0 to 0.0, 0.4 to 1.0, 0.6 to 1.0, 1.0 to 0.0).toFloat()
                    val r = 6f * (0.85f + 0.3f * k)
                    fill(c, SOCKET) { c.drawCircle(x, 138f, r, it) }
                    fill(c, Color.argb((k * 255).toInt(), 0xF5, 0xA6, 0x23)) { c.drawCircle(x, 138f, r, it) }
                }
            }
            NimboView.Mood.HELLO -> Unit
        }
    }
}
