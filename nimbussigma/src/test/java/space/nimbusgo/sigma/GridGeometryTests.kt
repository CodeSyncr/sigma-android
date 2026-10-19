package space.nimbusgo.sigma

import org.junit.Assert.assertEquals
import org.junit.Test
import space.nimbusgo.sigma.grid.ChartRenderer
import space.nimbusgo.sigma.grid.GridGeometry

class GridGeometryTests {
    // 100 rows of 20, 10 columns of 50 (column 2 is 100), headers 40×30, one frozen row and column.
    private val g = GridGeometry(100, 10, 1, 1, 40f, 30f, { c -> if (c == 2) 100f else 50f }, { 20f })

    @Test fun edgesAndSizes() {
        assertEquals(550f, g.sheetWidth)
        assertEquals(2000f, g.sheetHeight)
        assertEquals(50f, g.frozenWidth)
        assertEquals(20f, g.frozenHeight)
        assertEquals(100f, g.left(2))
        assertEquals(100f, g.width(2))
    }

    @Test fun findsColumnsAndRows() {
        assertEquals(0, g.columnAt(0f))
        assertEquals(2, g.columnAt(150f))
        assertEquals(3, g.columnAt(200f))
        assertEquals(9, g.columnAt(10_000f))
        assertEquals(5, g.rowAt(105f))
    }

    @Test fun hitsWithFrozenPanes() {
        assertEquals(GridGeometry.Hit.Corner, g.hit(10f, 10f, 0f, 0f))
        assertEquals(GridGeometry.Hit.ColumnHeader(0), g.hit(60f, 10f, 500f, 0f))
        // Past the frozen column, the offset counts.
        assertEquals(GridGeometry.Hit.Cell(0, 3), g.hit(100f, 35f, 150f, 0f))
        assertEquals(GridGeometry.Hit.Cell(6, 0), g.hit(45f, 60f, 0f, 90f))
        assertEquals(GridGeometry.Hit.RowHeader(0), g.hit(10f, 40f, 0f, 400f))
    }

    @Test fun screenPositionsIgnoreScrollForFrozen() {
        assertEquals(40f, g.screenX(0, 300f))
        assertEquals(40f + 100f - 300f, g.screenX(2, 300f))
        assertEquals(30f, g.screenY(0, 300f))
    }

    @Test fun visibleRangeStartsAfterFrozen() {
        val (rows, cols) = g.visibleScrolling(0f, 0f, 240f, 130f)
        assertEquals(1, rows.first)
        assertEquals(5, rows.last)
        assertEquals(1, cols.first)
        assertEquals(3, cols.last)
    }

    @Test fun columnEdges() {
        assertEquals(1, g.columnEdgeNear(98f, 5f))
        assertEquals(2, g.columnEdgeNear(203f, 5f))
        assertEquals(null, g.columnEdgeNear(150f, 5f))
    }

    @Test fun chartTicks() {
        assertEquals(listOf(0.0, 500.0, 1000.0, 1500.0), ChartRenderer.niceTicks(0.0, 1200.0))
        assertEquals("1200", ChartRenderer.format(1200.0))
        assertEquals("25K", ChartRenderer.format(25_000.0))
        assertEquals("2.5", ChartRenderer.format(2.5))
    }
}
