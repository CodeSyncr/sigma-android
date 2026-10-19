package space.nimbusgo.sigma.grid

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextUtils
import space.nimbusgo.sigma.SigmaChart
import space.nimbusgo.sigma.SigmaChartData
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Draws one chart as a card: its title, the plot and a legend. Series colours
 * are Sigma's own, so a chart looks the same as on the web.
 */
internal class ChartRenderer(private val d: Float, private val paints: TextPaints) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()

    fun draw(c: Canvas, chart: SigmaChart, data: SigmaChartData, box: RectF) {
        // The card.
        fill.color = Color.argb(20, 0, 0, 0)
        c.drawRoundRect(RectF(box.left, box.top + 2 * d, box.right, box.bottom + 2 * d), 10 * d, 10 * d, fill)
        fill.color = Color.WHITE
        c.drawRoundRect(box, 10 * d, 10 * d, fill)
        stroke.color = GridColors.gridline
        stroke.strokeWidth = 1 * d
        c.drawRoundRect(box, 10 * d, 10 * d, stroke)

        c.save()
        c.clipRect(box)
        val inner = RectF(box.left + 12 * d, box.top + 12 * d, box.right - 12 * d, box.bottom - 12 * d)
        val title = chart.title
        if (!title.isNullOrEmpty()) {
            val p = paints.plain(14f, GridColors.ink, bold = true)
            c.drawText(ellipsize(title, p, inner.width()), inner.left, inner.top - p.ascent(), p)
            inner.top += p.descent() - p.ascent() + 6 * d
        }
        val error = data.error ?: if (data.series.isEmpty()) "No numbers in ${chart.data}" else null
        if (error != null) {
            val p = paints.plain(12f, GridColors.secondary)
            val t = ellipsize(error, p, inner.width())
            c.drawText(t, inner.centerX() - p.measureText(t) / 2, inner.centerY(), p)
        } else if (chart.kind == SigmaChart.Kind.PIE || chart.kind == SigmaChart.Kind.DOUGHNUT) {
            pie(c, data, inner, chart.kind == SigmaChart.Kind.DOUGHNUT)
        } else {
            if (data.series.size > 1) inner.bottom -= legend(c, chart, data, inner)
            plot(c, chart, data, inner)
        }
        c.restore()
    }

    private fun seriesColor(chart: SigmaChart, data: SigmaChartData, i: Int): Int {
        val e = chart.emphasis
        if (e != null && e in data.series.indices && e != i) return Color.rgb(191, 191, 191)
        return GridColors.hex(data.series[i].color) ?: GridColors.accent
    }

    /** The legend under the plot; returns the height it took. */
    private fun legend(c: Canvas, chart: SigmaChart, data: SigmaChartData, r: RectF): Float {
        val p = paints.plain(11f, GridColors.secondary)
        val h = 18 * d
        var x = r.left
        val y = r.bottom - h / 2
        for (i in data.series.indices) {
            val name = data.series[i].name
            val w = 14 * d + p.measureText(name) + 12 * d
            if (x + w > r.right && x > r.left) break
            fill.color = seriesColor(chart, data, i)
            c.drawCircle(x + 4 * d, y, 4 * d, fill)
            c.drawText(name, x + 12 * d, y - (p.ascent() + p.descent()) / 2, p)
            x += w
        }
        return h + 4 * d
    }

    private fun plot(c: Canvas, chart: SigmaChart, data: SigmaChartData, r: RectF) {
        val n = data.labels.size
        if (n == 0) return
        val stacked = chart.stacked == true && chart.kind.canStack
        // The value range, with zero in it for bars and areas.
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        if (stacked) {
            for (i in 0 until n) {
                var pos = 0.0; var neg = 0.0
                for (s in data.series) { val v = s.values.getOrNull(i) ?: continue; if (v >= 0) pos += v else neg += v }
                hi = max(hi, pos); lo = min(lo, neg)
            }
        } else {
            for (s in data.series) for (v in s.values) if (v != null) { lo = min(lo, v); hi = max(hi, v) }
        }
        if (lo == Double.MAX_VALUE) return
        if (chart.kind != SigmaChart.Kind.LINE && chart.kind != SigmaChart.Kind.SCATTER) { lo = min(lo, 0.0); hi = max(hi, 0.0) }
        if (hi == lo) { hi += 1.0; lo -= if (lo == 0.0) 0.0 else 1.0 }
        val ticks = niceTicks(lo, hi)
        lo = min(lo, ticks.first()); hi = max(hi, ticks.last())

        val axis = paints.plain(10f, GridColors.secondary)
        val horizontal = chart.kind == SigmaChart.Kind.BAR
        val labelW = if (horizontal) min(r.width() * 0.3f, data.labels.maxOf { axis.measureText(it) } + 6 * d)
        else ticks.maxOf { axis.measureText(format(it)) } + 6 * d
        val plot = RectF(r.left + labelW, r.top + 4 * d, r.right - 4 * d, r.bottom - 16 * d)
        if (plot.width() <= 0 || plot.height() <= 0) return

        fun vx(v: Double) = plot.left + ((v - lo) / (hi - lo)).toFloat() * plot.width()
        fun vy(v: Double) = plot.bottom - ((v - lo) / (hi - lo)).toFloat() * plot.height()

        // Gridlines and value labels.
        stroke.color = GridColors.gridline
        stroke.strokeWidth = 1f
        for (t in ticks) {
            val s = format(t)
            if (horizontal) {
                val x = vx(t)
                c.drawLine(x, plot.top, x, plot.bottom, stroke)
                c.drawText(s, x - axis.measureText(s) / 2, plot.bottom + 12 * d, axis)
            } else {
                val y = vy(t)
                c.drawLine(plot.left, y, plot.right, y, stroke)
                c.drawText(s, plot.left - 4 * d - axis.measureText(s), y - (axis.ascent() + axis.descent()) / 2, axis)
            }
        }

        // Category labels, thinned out when they would collide.
        val slot = (if (horizontal) plot.height() else plot.width()) / n
        val every = max(1, ceil((if (horizontal) 14 * d else (data.labels.maxOf { axis.measureText(it) } + 8 * d)) / slot).toInt())
        if (chart.kind != SigmaChart.Kind.SCATTER) {
            for (i in 0 until n step every) {
                val label = data.labels[i]
                if (horizontal) {
                    val t = ellipsize(label, axis, labelW - 6 * d)
                    c.drawText(t, plot.left - 4 * d - axis.measureText(t), plot.top + slot * (i + 0.5f) - (axis.ascent() + axis.descent()) / 2, axis)
                } else {
                    val t = ellipsize(label, axis, slot * every - 4 * d)
                    c.drawText(t, plot.left + slot * (i + 0.5f) - axis.measureText(t) / 2, plot.bottom + 12 * d, axis)
                }
            }
        }

        val k = data.series.size
        when (chart.kind) {
            SigmaChart.Kind.COLUMN, SigmaChart.Kind.BAR -> {
                val base = 0.0.coerceIn(lo, hi)
                val pos = DoubleArray(n); val neg = DoubleArray(n)
                for ((si, s) in data.series.withIndex()) {
                    fill.color = seriesColor(chart, data, si)
                    for (i in 0 until n) {
                        val v = s.values.getOrNull(i) ?: continue
                        val from: Double; val to: Double
                        if (stacked) {
                            if (v >= 0) { from = pos[i]; to = pos[i] + v; pos[i] = to } else { from = neg[i]; to = neg[i] + v; neg[i] = to }
                        } else { from = base; to = v }
                        val bandStart = slot * i + slot * 0.15f
                        val band = slot * 0.7f
                        val w = if (stacked) band else band / k
                        val o = if (stacked) 0f else w * si
                        if (horizontal) {
                            val top = plot.top + bandStart + o
                            c.drawRect(min(vx(from), vx(to)), top, max(vx(from), vx(to)), top + w - 1, fill)
                        } else {
                            val left = plot.left + bandStart + o
                            c.drawRect(left, min(vy(from), vy(to)), left + w - 1, max(vy(from), vy(to)), fill)
                        }
                    }
                }
            }
            SigmaChart.Kind.LINE, SigmaChart.Kind.AREA -> {
                val acc = DoubleArray(n)
                for ((si, s) in data.series.withIndex()) {
                    val color = seriesColor(chart, data, si)
                    path.reset()
                    val area = Path()
                    var started = false
                    var firstX = 0f; var lastX = 0f
                    val lower = acc.copyOf()
                    for (i in 0 until n) {
                        val v = s.values.getOrNull(i) ?: continue
                        val value = if (stacked) acc[i] + v else v
                        if (stacked) acc[i] = value
                        val x = plot.left + slot * (i + 0.5f)
                        val y = vy(value)
                        if (!started) { path.moveTo(x, y); area.moveTo(x, y); firstX = x; started = true } else { path.lineTo(x, y); area.lineTo(x, y) }
                        lastX = x
                    }
                    if (!started) continue
                    if (chart.kind == SigmaChart.Kind.AREA) {
                        if (stacked) {
                            for (i in n - 1 downTo 0) if (s.values.getOrNull(i) != null) area.lineTo(plot.left + slot * (i + 0.5f), vy(lower[i]))
                        } else {
                            area.lineTo(lastX, vy(0.0.coerceIn(lo, hi))); area.lineTo(firstX, vy(0.0.coerceIn(lo, hi)))
                        }
                        area.close()
                        fill.color = (color and 0x00FFFFFF) or ((if (stacked) 217 else 115) shl 24)
                        c.drawPath(area, fill)
                    }
                    stroke.color = color
                    stroke.strokeWidth = 2 * d
                    c.drawPath(path, stroke)
                    if (chart.kind == SigmaChart.Kind.LINE) {
                        fill.color = color
                        for (i in 0 until n) { val v = s.values.getOrNull(i) ?: continue; c.drawCircle(plot.left + slot * (i + 0.5f), vy(v), 2.5f * d, fill) }
                    }
                }
            }
            SigmaChart.Kind.SCATTER -> {
                val xs = data.labels.mapIndexed { i, l -> l.toDoubleOrNull() ?: (i + 1).toDouble() }
                val x0 = xs.min(); val x1 = max(xs.max(), x0 + 1)
                for ((si, s) in data.series.withIndex()) {
                    fill.color = seriesColor(chart, data, si)
                    for (i in 0 until n) {
                        val v = s.values.getOrNull(i) ?: continue
                        c.drawCircle(plot.left + ((xs[i] - x0) / (x1 - x0)).toFloat() * plot.width(), vy(v), 3.5f * d, fill)
                    }
                }
            }
            else -> Unit
        }
    }

    /** Pie and doughnut: the first series, with a legend beside it. */
    private fun pie(c: Canvas, data: SigmaChartData, r: RectF, hole: Boolean) {
        val palette = Palette.rows[1] + Palette.rows[3]
        val slices = data.labels.indices.mapNotNull { i ->
            val v = data.series[0].values.getOrNull(i) ?: return@mapNotNull null
            if (v <= 0) null else Triple(data.labels[i], v, GridColors.hex(palette[i % palette.size]) ?: Color.GRAY)
        }
        val total = slices.sumOf { it.second }
        if (total <= 0) return
        val size = min(r.height(), r.width() * 0.55f)
        val oval = RectF(r.left, r.centerY() - size / 2, r.left + size, r.centerY() + size / 2)
        var start = -90f
        stroke.color = Color.WHITE
        stroke.strokeWidth = 2 * d
        for ((_, v, color) in slices) {
            val sweep = (v / total * 360).toFloat()
            fill.color = color
            c.drawArc(oval, start, sweep, true, fill)
            c.drawArc(oval, start, sweep, true, stroke)
            start += sweep
        }
        if (hole) {
            fill.color = Color.WHITE
            c.drawCircle(oval.centerX(), oval.centerY(), size * 0.275f, fill)
        }
        val p = paints.plain(11f, GridColors.ink)
        val pct = paints.plain(11f, GridColors.secondary)
        val lx = oval.right + 12 * d
        val shown = slices.take(8)
        var y = r.centerY() - shown.size * 9 * d + 9 * d
        for ((label, v, color) in shown) {
            fill.color = color
            c.drawCircle(lx + 4 * d, y, 4 * d, fill)
            val share = String.format(java.util.Locale.ROOT, "%.0f%%", v / total * 100)
            val room = r.right - lx - 14 * d - pct.measureText(share) - 6 * d
            val t = ellipsize(label, p, max(0f, room))
            val baseline = y - (p.ascent() + p.descent()) / 2
            c.drawText(t, lx + 12 * d, baseline, p)
            c.drawText(share, lx + 12 * d + p.measureText(t) + 6 * d, baseline, pct)
            y += 18 * d
        }
    }

    private fun ellipsize(s: String, p: Paint, width: Float): String =
        TextUtils.ellipsize(s, android.text.TextPaint(p), max(0f, width), TextUtils.TruncateAt.END).toString()

    companion object {
        /** About five round steps covering lo..hi. */
        fun niceTicks(lo: Double, hi: Double): List<Double> {
            val span = hi - lo
            val raw = span / 4
            val mag = 10.0.pow(floor(log10(raw)))
            val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= raw }
            val start = floor(lo / step) * step
            val out = ArrayList<Double>()
            var t = start
            while (t <= hi + step * 1e-9 && out.size < 12) { out.add(if (abs(t) < step * 1e-9) 0.0 else t); t += step }
            if (out.last() < hi) out.add(out.last() + step)
            return out
        }

        fun format(v: Double): String {
            val a = abs(v)
            return when {
                a >= 1e9 -> trim(v / 1e9) + "B"
                a >= 1e6 -> trim(v / 1e6) + "M"
                a >= 1e4 -> trim(v / 1e3) + "K"
                v == Math.rint(v) -> v.toLong().toString()
                else -> trim(v)
            }
        }

        private fun trim(v: Double): String = String.format(java.util.Locale.ROOT, "%.2f", v).trimEnd('0').trimEnd('.')
    }
}
