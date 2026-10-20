package space.nimbusgo.sigma.grid

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Where every row and column of a sheet sits, and which cell is under a point
 * on screen, with frozen panes. Pure arithmetic in pixels, so it is tested on
 * its own; the grid view only asks it questions.
 *
 * The screen has the row-number strip on the left ([rowHeaderWidth]) and the
 * column-letter strip on top ([columnHeaderHeight]). Frozen rows and columns
 * never scroll; the rest scroll by the offset.
 */
internal class GridGeometry(
    rows: Int,
    columns: Int,
    frozenRows: Int,
    frozenColumns: Int,
    val rowHeaderWidth: Float,
    val columnHeaderHeight: Float,
    columnWidth: (Int) -> Float,
    rowHeight: (Int) -> Float,
) {
    val rows = max(1, rows)
    val columns = max(1, columns)
    val frozenRows = min(max(0, frozenRows), this.rows - 1)
    val frozenColumns = min(max(0, frozenColumns), this.columns - 1)

    /** x of each column's left edge from the first column; `columns + 1` entries. */
    private val columnX = FloatArray(this.columns + 1).also { x -> for (c in 0 until this.columns) x[c + 1] = x[c] + columnWidth(c) }
    /** y of each row's top edge from the first row; `rows + 1` entries. */
    private val rowY = FloatArray(this.rows + 1).also { y -> for (r in 0 until this.rows) y[r + 1] = y[r] + rowHeight(r) }

    val frozenWidth: Float get() = columnX[frozenColumns]
    val frozenHeight: Float get() = rowY[frozenRows]
    val sheetWidth: Float get() = columnX[columns]
    val sheetHeight: Float get() = rowY[rows]

    fun left(c: Int): Float = columnX[c]
    fun top(r: Int): Float = rowY[r]
    fun width(c: Int): Float = columnX[c + 1] - columnX[c]
    fun height(r: Int): Float = rowY[r + 1] - rowY[r]

    /** The column whose span contains x (sheet coordinates), clamped. */
    fun columnAt(x: Float): Int = index(columnX, x)
    fun rowAt(y: Float): Int = index(rowY, y)

    /** How far the sheet can scroll in a viewport. */
    fun maxOffsetX(viewportWidth: Float): Float = max(0f, rowHeaderWidth + sheetWidth - viewportWidth)
    fun maxOffsetY(viewportHeight: Float): Float = max(0f, columnHeaderHeight + sheetHeight + EXTRA_BOTTOM - viewportHeight)

    /** The scrolling rows and columns visible at a scroll offset in a viewport. */
    fun visibleScrolling(offX: Float, offY: Float, width: Float, height: Float): Pair<IntRange, IntRange> {
        val left = offX + frozenWidth
        val right = offX + width - rowHeaderWidth
        val top = offY + frozenHeight
        val bottom = offY + height - columnHeaderHeight
        val c1 = max(frozenColumns, columnAt(left))
        val c2 = max(c1, columnAt(max(left, right)))
        val r1 = max(frozenRows, rowAt(top))
        val r2 = max(r1, rowAt(max(top, bottom)))
        return (r1..r2) to (c1..c2)
    }

    sealed class Hit {
        data class Cell(val row: Int, val column: Int) : Hit()
        data class ColumnHeader(val column: Int) : Hit()
        data class RowHeader(val row: Int) : Hit()
        data object Corner : Hit()
    }

    /** Sheet x of a viewport x, frozen columns included. */
    fun sheetX(viewX: Float, offX: Float): Float {
        val x = viewX - rowHeaderWidth
        return if (x < frozenWidth) x else x + offX
    }

    fun sheetY(viewY: Float, offY: Float): Float {
        val y = viewY - columnHeaderHeight
        return if (y < frozenHeight) y else y + offY
    }

    /** What is under a point in the viewport at a scroll offset. */
    fun hit(x: Float, y: Float, offX: Float, offY: Float): Hit {
        val inColumnStrip = y < columnHeaderHeight
        val inRowStrip = x < rowHeaderWidth
        if (inColumnStrip && inRowStrip) return Hit.Corner
        val sx = sheetX(x, offX)
        val sy = sheetY(y, offY)
        if (inColumnStrip) return Hit.ColumnHeader(columnAt(sx))
        if (inRowStrip) return Hit.RowHeader(rowAt(sy))
        return Hit.Cell(rowAt(sy), columnAt(sx))
    }

    /** Where a cell's left edge appears in the viewport. */
    fun screenX(c: Int, offX: Float): Float = rowHeaderWidth + columnX[c] - if (c < frozenColumns) 0f else offX
    fun screenY(r: Int, offY: Float): Float = columnHeaderHeight + rowY[r] - if (r < frozenRows) 0f else offY

    /** The column edge within [slop] of x (sheet coordinates): the column to its left, for resizing. */
    fun columnEdgeNear(x: Float, slop: Float): Int? {
        val c = columnAt(x)
        if (abs(columnX[c + 1] - x) <= slop) return c
        if (c > 0 && abs(columnX[c] - x) <= slop) return c - 1
        return null
    }

    /** The row edge within [slop] of y (sheet coordinates): the row above it, for resizing. */
    fun rowEdgeNear(y: Float, slop: Float): Int? {
        val r = rowAt(y)
        if (abs(rowY[r + 1] - y) <= slop) return r
        if (r > 0 && abs(rowY[r] - y) <= slop) return r - 1
        return null
    }

    companion object {
        /** Room under the last row, so it can be scrolled clear of the screen's edge. */
        const val EXTRA_BOTTOM = 80f

        private fun index(edges: FloatArray, v: Float): Int {
            var lo = 0
            var hi = edges.size - 2
            if (v <= 0) return 0
            if (v >= edges[hi + 1]) return hi
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (edges[mid] <= v) lo = mid else hi = mid - 1
            }
            return lo
        }
    }
}
