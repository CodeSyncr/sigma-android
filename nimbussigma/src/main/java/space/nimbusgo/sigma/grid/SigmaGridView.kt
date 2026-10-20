package space.nimbusgo.sigma.grid

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.Menu
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.OverScroller
import android.widget.PopupMenu
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import space.nimbusgo.sigma.SigmaAddress
import space.nimbusgo.sigma.SigmaCell
import space.nimbusgo.sigma.SigmaChart
import space.nimbusgo.sigma.SigmaChartData
import space.nimbusgo.sigma.SigmaException
import space.nimbusgo.sigma.SigmaGridController
import space.nimbusgo.sigma.SigmaSelection
import space.nimbusgo.sigma.SigmaSheetLayout
import space.nimbusgo.sigma.SigmaWorkbook
import space.nimbusgo.sigma.charts
import space.nimbusgo.sigma.removeChart
import space.nimbusgo.sigma.updateChart
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The spreadsheet, drawn natively on a canvas: cells, headers, frozen panes,
 * selection with handles, in-cell editing, column resizing, charts and a
 * long-press menu.
 *
 * The engine runs in another process, so the grid never waits on it while
 * drawing: it draws from a cache of what the cells around the screen show,
 * and refills the cache in the background as the sheet scrolls or changes.
 */
@SuppressLint("ViewConstructor")
internal class SigmaGridView(context: Context, val controller: SigmaGridController) : FrameLayout(context) {
    enum class Move { DOWN, UP, RIGHT, LEFT, NONE }
    enum class Action {
        UNDO, REDO, CUT, COPY, PASTE, CLEAR, FILL_DOWN, FILL_RIGHT,
        INSERT_ROW_ABOVE, INSERT_ROW_BELOW, DELETE_ROWS, INSERT_COLUMN_LEFT, INSERT_COLUMN_RIGHT, DELETE_COLUMNS,
        EDIT, FREEZE_HERE, UNFREEZE, SORT_ASCENDING, SORT_DESCENDING, NOTE, CONDITIONAL_FORMATTING, FIND, INSERT_CHART,
        EDIT_CHART, DELETE_CHART, FILL, PASTE_VALUES,
        FREEZE_ONE_ROW, FREEZE_TWO_ROWS, FREEZE_ONE_COLUMN,
    }

    var workbook: SigmaWorkbook? = null
        private set
    internal var scope = newScope()
        private set
    private var watching: Job? = null

    private val d = resources.displayMetrics.density
    /** Pinch zoom, 50% to 200%. Cells, headers, text and charts scale with it; handles and touch slop do not. */
    private var zoom = 1f
    /** Pixels per sheet point at the current zoom. */
    private val pt: Float get() = d * zoom
    private val paints = TextPaints(d)
    private val chartRenderer = ChartRenderer(d, paints)
    private val fill = Paint()
    private val line = Paint().apply { strokeWidth = 1f }
    private val knobFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val knobStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = GridColors.accent }
    private val notePath = Path()

    private var geometry = makeGeometry(null, null)
    private var layout: SigmaSheetLayout? = null
    private var layoutKey: Pair<Int, Int>? = null
    /** A column width being dragged, in pixels, before it is saved. */
    private var liveColumnWidth: Pair<Int, Float>? = null
    /** A row height being dragged, in pixels, before it is saved. */
    private var liveRowHeight: Pair<Int, Float>? = null
    /** Fill mode: the corner handle drags out a fill instead of the selection. */
    private var filling = false
    /** The block a fill drag would fill, while it is being dragged. */
    private var fillTarget: SigmaSelection? = null

    private var offX = 0f
    private var offY = 0f

    // What the engine said each cell shows, for the block around the screen.
    private var cells: Map<Long, SigmaCell> = emptyMap()
    private var block: Block? = null
    private data class Block(val rows: IntRange, val columns: IntRange, val revision: Int, val sheet: Int)
    private var loadJob: Job? = null
    private var reload = false

    // Charts over the body.
    private var chartModels: List<Pair<SigmaChart, SigmaChartData>> = emptyList()
    private var chartDrag: ChartDrag? = null
    private data class ChartDrag(val id: String, val resize: Boolean, val startX: Float, val startY: Float, var dx: Float = 0f, var dy: Float = 0f)

    // Editing.
    private val editor = CellEditor(context)
    private var editing: SigmaAddress? = null
    private var editStart = ""
    private var settingEditor = false
    private var clip: SigmaWorkbook.Clip? = null

    // Touch.
    private val scroller = OverScroller(context)
    private var lock = 0 // 0 free, 1 horizontal, 2 vertical
    private var dragging: Drag = Drag.None
    private sealed class Drag {
        data object None : Drag()
        data class Handle(val bottomRight: Boolean) : Drag()
        data class Resize(val column: Int, val startWidth: Float, val startX: Float) : Drag()
        data class RowResize(val row: Int, val startHeight: Float, val startY: Float) : Drag()
        data object Fill : Drag()
        data class Chart(val drag: ChartDrag) : Drag()
    }
    private val menuAnchor = View(context)

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            setZoom(zoom * detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            lock = 0
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (lock == 0) lock = when {
                abs(dx) > abs(dy) * 2 -> 1
                abs(dy) > abs(dx) * 2 -> 2
                else -> 3
            }
            scrollBy(if (lock == 2) 0f else dx, if (lock == 1) 0f else dy)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val g = geometry
            scroller.fling(
                offX.toInt(), offY.toInt(),
                if (lock == 2) 0 else -vx.toInt(), if (lock == 1) 0 else -vy.toInt(),
                0, g.maxOffsetX(width.toFloat()).toInt(), 0, g.maxOffsetY(height.toFloat()).toInt(),
            )
            postInvalidateOnAnimation()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            tapped(e.x, e.y)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            longPressed(e.x, e.y)
        }
    })

    init {
        setWillNotDraw(false)
        setBackgroundColor(Color.WHITE)
        isFocusable = true
        isFocusableInTouchMode = true
        // The grid draws its own selection; the system's focus wash would grey the sheet.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) defaultFocusHighlightEnabled = false
        controller.grid = this
        addView(editor, LayoutParams(0, 0))
        editor.visibility = GONE
        addView(menuAnchor, LayoutParams(1, 1))
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (settingEditor || editing == null) return
                controller.setEditText(s?.toString() ?: "")
                sizeEditor()
            }
        })
        editor.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_NEXT || action == EditorInfo.IME_ACTION_DONE) { commitEditing(Move.DOWN); true } else false
        }
        editor.onKey = { move -> if (move == null) cancelEditing() else commitEditing(move) }
    }

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Workbook

    fun show(book: SigmaWorkbook, readOnly: Boolean) {
        controller.setReadOnly(readOnly)
        if (book === workbook) return
        workbook = book
        watch()
        workbookChanged()
        refreshActive()
    }

    private fun watch() {
        val book = workbook ?: return
        watching?.cancel()
        watching = scope.launch {
            launch { book.revision.drop(1).collect { workbookChanged() } }
            // Only a real switch of sheet resets the selection.
            launch {
                book.activeSheet.drop(1).collect {
                    cancelEditing()
                    offX = 0f; offY = 0f
                    controller.setSelectedChart(null)
                    select(SigmaSelection(SigmaAddress(0, 0)), scroll = false, force = true)
                    workbookChanged()
                }
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!scope.isActive) {
            scope = newScope()
            watch()
            workbookChanged()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scope.cancel()
    }

    private fun workbookChanged() {
        block = null
        requestLoad()
        refreshActive()
        invalidate()
    }

    private fun makeGeometry(info: space.nimbusgo.sigma.SigmaSheetInfo?, layout: SigmaSheetLayout?): GridGeometry {
        val live = liveColumnWidth
        val liveRow = liveRowHeight
        val pt = pt
        return GridGeometry(
            info?.rows ?: 1000, info?.columns ?: 26, info?.frozenRows ?: 0, info?.frozenColumns ?: 0,
            46 * pt, 28 * pt,
            columnWidth = { c -> if (live?.first == c) live.second else ((layout?.widthOfColumn(c) ?: 96.0) * pt).toFloat() },
            rowHeight = { r -> if (liveRow?.first == r) liveRow.second else ((layout?.heightOfRow(r) ?: 26.0) * pt).toFloat() },
        )
    }

    private fun rebuildGeometry() {
        val book = workbook ?: return
        geometry = makeGeometry(book.sheets.value.getOrNull(book.activeSheet.value), layout)
        clampOffsets()
    }

    /** Zooms about a point on screen, keeping what is under it in place. */
    private fun setZoom(next: Float, focusX: Float, focusY: Float) {
        val old = zoom
        val nz = next.coerceIn(0.5f, 2f)
        if (abs(nz - old) < 0.001f) return
        val g = geometry
        // The sheet point under the focus, in unzoomed points.
        val sx = (offX + focusX - g.rowHeaderWidth) / (d * old)
        val sy = (offY + focusY - g.columnHeaderHeight) / (d * old)
        zoom = nz
        paints.scale = pt
        chartRenderer.d = pt
        rebuildGeometry()
        val ng = geometry
        offX = sx * pt - (focusX - ng.rowHeaderWidth)
        offY = sy * pt - (focusY - ng.columnHeaderHeight)
        clampOffsets()
        editing?.let { editor.setTextSize(TypedValue.COMPLEX_UNIT_DIP, (controller.activeStyle.value.fontSize ?: 13.0).toFloat() * zoom) }
        positionEditor()
        requestLoad()
        invalidate()
    }

    /** Refills what the grid draws from: layout and charts when the workbook changed, cells around the screen. */
    private fun requestLoad() {
        if (loadJob?.isActive == true) { reload = true; return }
        loadJob = scope.launch {
            do {
                reload = false
                runCatching { loadOnce() }
            } while (reload && isActive)
        }
    }

    private suspend fun loadOnce() {
        val book = workbook ?: return
        val revision = book.revision.value
        val sheet = book.activeSheet.value
        if (layoutKey != revision to sheet) {
            layout = book.layout(sheet)
            chartModels = book.charts(sheet)
            layoutKey = revision to sheet
            rebuildGeometry()
            if (controller.selectedChart.value != null && chartModels.none { it.first.id == controller.selectedChart.value }) controller.setSelectedChart(null)
        }
        val g = geometry
        val (vr, vc) = g.visibleScrolling(offX, offY, width.toFloat(), height.toFloat())
        val b = block
        if (b != null && b.revision == revision && b.sheet == sheet && vr.first in b.rows && vr.last in b.rows && vc.first in b.columns && vc.last in b.columns) return
        val rr = max(0, vr.first - 30)..min(g.rows - 1, vr.last + 60)
        val cc = max(0, vc.first - 6)..min(g.columns - 1, vc.last + 10)
        val next = HashMap<Long, SigmaCell>()
        suspend fun load(r: IntRange, c: IntRange) { for (cell in book.cells(r, c, sheet)) next[key(cell.row, cell.column)] = cell }
        load(rr, cc)
        if (g.frozenRows > 0) load(0 until g.frozenRows, cc)
        if (g.frozenColumns > 0) load(rr, 0 until g.frozenColumns)
        if (g.frozenRows > 0 && g.frozenColumns > 0) load(0 until g.frozenRows, 0 until g.frozenColumns)
        // A change that landed while this was loading makes it stale; the next pass loads again.
        if (book.revision.value != revision || book.activeSheet.value != sheet) { reload = true; return }
        cells = next
        block = Block(rr, cc, revision, sheet)
        invalidate()
        positionEditor()
    }

    private fun key(r: Int, c: Int): Long = r.toLong() * 16384 + c

    // Drawing

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clampOffsets()
        editing?.let { scrollToVisible(it) }
        requestLoad()
    }

    override fun onDraw(c: Canvas) {
        if (workbook == null) return
        val g = geometry
        val w = width.toFloat()
        val h = height.toFloat()
        val hw = g.rowHeaderWidth
        val hh = g.columnHeaderHeight
        val (vr, vc) = g.visibleScrolling(offX, offY, w, h)
        val b = block
        if (b == null || vr.first !in b.rows || vr.last !in b.rows || vc.first !in b.columns || vc.last !in b.columns) requestLoad()

        val fr = if (g.frozenRows > 0) 0 until g.frozenRows else IntRange.EMPTY
        val fc = if (g.frozenColumns > 0) 0 until g.frozenColumns else IntRange.EMPTY
        val bodyClip = RectF(hw + g.frozenWidth, hh + g.frozenHeight, w, h)
        pane(c, vr, vc, bodyClip, g.frozenRows until g.rows, g.frozenColumns until g.columns)
        c.save(); c.clipRect(bodyClip); drawCharts(c); c.restore()
        pane(c, fr, vc, RectF(hw + g.frozenWidth, hh, w, hh + g.frozenHeight), 0 until g.frozenRows, g.frozenColumns until g.columns)
        pane(c, vr, fc, RectF(hw, hh + g.frozenHeight, hw + g.frozenWidth, h), g.frozenRows until g.rows, 0 until g.frozenColumns)
        pane(c, fr, fc, RectF(hw, hh, hw + g.frozenWidth, hh + g.frozenHeight), 0 until g.frozenRows, 0 until g.frozenColumns)

        headers(c, vc, fc, vr, fr)

        if (g.frozenRows > 0) { fill.color = GridColors.frozenLine; c.drawRect(0f, hh + g.frozenHeight - d, w, hh + g.frozenHeight + d, fill) }
        if (g.frozenColumns > 0) { fill.color = GridColors.frozenLine; c.drawRect(hw + g.frozenWidth - d, 0f, hw + g.frozenWidth + d, h, fill) }

        if (editing == null && !controller.readOnly.value && controller.selectedChart.value == null) {
            val (tl, br) = handlePoints()
            c.save()
            c.clipRect(hw - 12 * d, hh - 12 * d, w, h)
            if (filling) {
                fillKnob(c, br.first, br.second)
                fillTarget?.let { t -> dashed(c, t) }
            } else {
                val sel = controller.selection.value
                if (handleVisible(sel.top, sel.left, tl)) knob(c, tl.first, tl.second)
                if (handleVisible(sel.bottom, sel.right, br)) knob(c, br.first, br.second)
            }
            c.restore()
        }

        if (!scroller.isFinished) postInvalidateOnAnimation()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            offX = scroller.currX.toFloat()
            offY = scroller.currY.toFloat()
            clampOffsets()
            positionEditor()
            postInvalidateOnAnimation()
        }
    }

    /** One region of cells (the body or a frozen pane): cells, then the selection over them. */
    private fun pane(c: Canvas, rows: IntRange, cols: IntRange, clip: RectF, paneRows: IntRange, paneCols: IntRange) {
        if (rows.isEmpty() || cols.isEmpty() || clip.width() <= 0 || clip.height() <= 0) return
        val g = geometry
        c.save()
        c.clipRect(clip)
        for (r in rows) {
            val y = g.screenY(r, offY)
            val rh = g.height(r)
            for (col in cols) cell(c, cells[key(r, col)], g.screenX(col, offX), y, g.width(col), rh)
        }
        // The selection: one box across panes, so only its real edges get a border.
        val s = controller.selection.value
        if (controller.selectedChart.value == null) {
            val r1 = max(s.top, paneRows.first); val r2 = min(s.bottom, paneRows.last)
            val c1 = max(s.left, paneCols.first); val c2 = min(s.right, paneCols.last)
            if (r1 <= r2 && c1 <= c2 && !paneRows.isEmpty() && !paneCols.isEmpty()) {
                val left = g.screenX(c1, offX); val top = g.screenY(r1, offY)
                val right = g.screenX(c2, offX) + g.width(c2); val bottom = g.screenY(r2, offY) + g.height(r2)
                fill.color = GridColors.accentSoft
                c.drawRect(left, top, right, bottom, fill)
                fill.color = GridColors.accent
                val e = 2 * d
                if (r1 == s.top) c.drawRect(left, top, right, top + e, fill)
                if (r2 == s.bottom) c.drawRect(left, bottom - e, right, bottom, fill)
                if (c1 == s.left) c.drawRect(left, top, left + e, bottom, fill)
                if (c2 == s.right) c.drawRect(right - e, top, right, bottom, fill)
            }
            val a = s.active
            if (!s.isSingle && a.row in paneRows && a.column in paneCols) {
                line.color = GridColors.accent
                line.style = Paint.Style.STROKE
                line.strokeWidth = d
                c.drawRect(g.screenX(a.column, offX), g.screenY(a.row, offY), g.screenX(a.column, offX) + g.width(a.column), g.screenY(a.row, offY) + g.height(a.row), line)
                line.style = Paint.Style.FILL
                line.strokeWidth = 1f
            }
        }
        c.restore()
    }

    /** One cell: its fill, the gridlines on its right and bottom, text, borders and a note mark. */
    private fun cell(c: Canvas, cell: SigmaCell?, x: Float, y: Float, w: Float, h: Float) {
        val style = cell?.style
        GridColors.hex(style?.fill)?.let { fill.color = it; c.drawRect(x, y, x + w, y + h, fill) }
        fill.color = GridColors.gridline
        c.drawRect(x + w - 1, y, x + w, y + h, fill)
        c.drawRect(x, y + h - 1, x + w, y + h, fill)
        if (cell == null) return
        if (cell.text.isNotEmpty()) {
            val color = if (cell.isError) GridColors.error else GridColors.hex(style?.textColor) ?: GridColors.ink
            val p = paints.forCell(style, color)
            val text = cell.text.replace('\n', ' ')
            val tw = p.measureText(text)
            val pad = 5 * d
            val align = style?.align ?: when {
                cell.isNumber -> space.nimbusgo.sigma.SigmaStyle.Align.RIGHT
                cell.isError -> space.nimbusgo.sigma.SigmaStyle.Align.CENTER
                else -> space.nimbusgo.sigma.SigmaStyle.Align.LEFT
            }
            val tx = when (align) {
                space.nimbusgo.sigma.SigmaStyle.Align.LEFT -> x + pad
                space.nimbusgo.sigma.SigmaStyle.Align.CENTER -> x + (w - tw) / 2
                space.nimbusgo.sigma.SigmaStyle.Align.RIGHT -> x + w - pad - tw
            }
            val ty = y + h / 2 - (p.ascent() + p.descent()) / 2
            if (tw > w - 2 * pad) {
                c.save(); c.clipRect(x + 1, y, x + w - 1, y + h); c.drawText(text, tx, ty, p); c.restore()
            } else {
                c.drawText(text, tx, ty, p)
            }
        }
        if (style != null) {
            fill.color = GridColors.border
            val bw = max(1f, d)
            if (style.borderTop == true) c.drawRect(x, y, x + w, y + bw, fill)
            if (style.borderBottom == true) c.drawRect(x, y + h - bw, x + w, y + h, fill)
            if (style.borderLeft == true) c.drawRect(x, y, x + bw, y + h, fill)
            if (style.borderRight == true) c.drawRect(x + w - bw, y, x + w, y + h, fill)
        }
        if (cell.hasNote) {
            fill.color = GridColors.note
            notePath.reset()
            notePath.moveTo(x + w - 7 * d, y); notePath.lineTo(x + w, y); notePath.lineTo(x + w, y + 7 * d); notePath.close()
            fill.isAntiAlias = true
            c.drawPath(notePath, fill)
            fill.isAntiAlias = false
        }
    }

    private fun headers(c: Canvas, vc: IntRange, fc: IntRange, vr: IntRange, fr: IntRange) {
        val g = geometry
        val s = controller.selection.value
        val hw = g.rowHeaderWidth
        val hh = g.columnHeaderHeight
        val w = width.toFloat()
        val h = height.toFloat()
        fun header(text: String, l: Float, t: Float, r: Float, b: Float, on: Boolean) {
            fill.color = if (on) GridColors.headerSelected else GridColors.header
            c.drawRect(l, t, r, b, fill)
            fill.color = GridColors.gridline
            c.drawRect(r - 1, t, r, b, fill)
            c.drawRect(l, b - 1, r, b, fill)
            val p = paints.plain(11f, if (on) GridColors.accent else GridColors.headerText, bold = on)
            c.drawText(text, (l + r) / 2 - p.measureText(text) / 2, (t + b) / 2 - (p.ascent() + p.descent()) / 2, p)
        }
        // Column letters: scrolling, then frozen over them.
        c.save(); c.clipRect(hw + g.frozenWidth, 0f, w, hh)
        for (col in vc) { val x = g.screenX(col, offX); header(SigmaAddress.columnName(col), x, 0f, x + g.width(col), hh, col in s.left..s.right) }
        c.restore()
        for (col in fc) { val x = g.screenX(col, offX); header(SigmaAddress.columnName(col), x, 0f, x + g.width(col), hh, col in s.left..s.right) }
        c.save(); c.clipRect(0f, hh + g.frozenHeight, hw, h)
        for (r in vr) { val y = g.screenY(r, offY); header((r + 1).toString(), 0f, y, hw, y + g.height(r), r in s.top..s.bottom) }
        c.restore()
        for (r in fr) { val y = g.screenY(r, offY); header((r + 1).toString(), 0f, y, hw, y + g.height(r), r in s.top..s.bottom) }
        fill.color = GridColors.header
        c.drawRect(0f, 0f, hw, hh, fill)
        fill.color = GridColors.gridline
        c.drawRect(hw - 1, 0f, hw, hh, fill)
        c.drawRect(0f, hh - 1, hw, hh, fill)
    }

    private fun knob(c: Canvas, x: Float, y: Float) {
        c.drawCircle(x, y, 7 * d, knobFill)
        knobStroke.strokeWidth = 2.5f * d
        c.drawCircle(x, y, 7 * d, knobStroke)
    }

    /** A handle on a scrolling cell is hidden once that cell has scrolled under a frozen pane. */
    private fun handleVisible(row: Int, column: Int, at: Pair<Float, Float>): Boolean {
        val g = geometry
        val tolerance = 2 * d
        if (row >= g.frozenRows && at.second < g.columnHeaderHeight + g.frozenHeight - tolerance) return false
        if (column >= g.frozenColumns && at.first < g.rowHeaderWidth + g.frozenWidth - tolerance) return false
        return true
    }

    /** The fill handle: a square with a plus, so it reads differently from the round selection handles. */
    private fun fillKnob(c: Canvas, x: Float, y: Float) {
        val r = 9 * d
        knobFill.color = GridColors.accent
        c.drawRect(x - r, y - r, x + r, y + r, knobFill)
        knobFill.color = Color.WHITE
        val t = 1.5f * d
        c.drawRect(x - r * 0.55f, y - t, x + r * 0.55f, y + t, knobFill)
        c.drawRect(x - t, y - r * 0.55f, x + t, y + r * 0.55f, knobFill)
    }

    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = GridColors.accent
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 4f), 0f)
    }

    /** The block a fill would reach, while it is dragged. */
    private fun dashed(c: Canvas, t: SigmaSelection) {
        val g = geometry
        dash.strokeWidth = 2 * d
        c.drawRect(g.screenX(t.left, offX), g.screenY(t.top, offY), g.screenX(t.right, offX) + g.width(t.right), g.screenY(t.bottom, offY) + g.height(t.bottom), dash)
    }

    /** Where the selection's two handles are on screen. */
    private fun handlePoints(): Pair<Pair<Float, Float>, Pair<Float, Float>> {
        val g = geometry
        val s = controller.selection.value
        val tl = g.screenX(s.left, offX) to g.screenY(s.top, offY)
        val br = (g.screenX(s.right, offX) + g.width(s.right)) to (g.screenY(s.bottom, offY) + g.height(s.bottom))
        return tl to br
    }

    // Charts

    private fun chartRect(chart: SigmaChart): RectF {
        val g = geometry
        val r = min(chart.row, g.rows - 1)
        val col = min(chart.column, g.columns - 1)
        val x = g.screenX(col, offX)
        val y = g.screenY(r, offY)
        val rect = RectF(x, y, x + (chart.width * pt).toFloat(), y + (chart.height * pt).toFloat())
        val drag = chartDrag
        if (drag != null && drag.id == chart.id) {
            if (drag.resize) {
                rect.right = max(rect.left + 200 * pt, rect.right + drag.dx)
                rect.bottom = max(rect.top + 140 * pt, rect.bottom + drag.dy)
            } else rect.offset(drag.dx, drag.dy)
        }
        return rect
    }

    private fun drawCharts(c: Canvas) {
        val selected = controller.selectedChart.value
        for ((chart, data) in chartModels) {
            val r = chartRect(chart)
            if (r.right < 0 || r.bottom < 0 || r.left > width || r.top > height) continue
            chartRenderer.draw(c, chart, data, r)
            if (chart.id == selected) {
                knobStroke.strokeWidth = 2 * d
                c.drawRoundRect(r, 10 * d, 10 * d, knobStroke)
                if (!controller.readOnly.value) knob(c, r.right, r.bottom)
            }
        }
    }

    private fun chartAt(x: Float, y: Float): SigmaChart? {
        val g = geometry
        if (x < g.rowHeaderWidth + g.frozenWidth || y < g.columnHeaderHeight + g.frozenHeight) return null
        return chartModels.lastOrNull { chartRect(it.first).contains(x, y) }?.first
    }

    /** Scrolls so a chart is in view, the whole of it when it fits. */
    fun revealChart(chart: SigmaChart) {
        val g = geometry
        val viewW = width - g.rowHeaderWidth - g.frozenWidth
        val viewH = height - g.columnHeaderHeight - g.frozenHeight
        val l = g.left(min(chart.column, g.columns - 1)) - g.frozenWidth
        val t = g.top(min(chart.row, g.rows - 1)) - g.frozenHeight
        val r = l + (chart.width * pt).toFloat()
        val b = t + (chart.height * pt).toFloat()
        if (l < offX || r > offX + viewW) offX = if (r - l <= viewW) min(l, max(offX, r - viewW)) else l - 8 * d
        if (t < offY || b > offY + viewH) offY = if (b - t <= viewH) min(t, max(offY, b - viewH)) else t - 8 * d
        clampOffsets()
        positionEditor()
        invalidate()
    }

    fun selectChart(id: String?) {
        if (editing != null) commitEditing(Move.NONE)
        controller.setSelectedChart(id)
        invalidate()
    }

    // Touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (workbook == null) return false
        scaler.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                dragging = startDrag(e.x, e.y)
                if (dragging != Drag.None) parent?.requestDisallowInterceptTouchEvent(true)
            }
            // A second finger means a pinch: whatever one finger started is dropped.
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (dragging is Drag.Chart) { chartDrag = null }
                if (dragging is Drag.Fill) fillTarget = null
                dragging = Drag.None
                liveColumnWidth = null; liveRowHeight = null
                rebuildGeometry(); invalidate()
            }
        }
        if (scaler.isInProgress || e.pointerCount > 1) {
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
        val drag = dragging
        if (drag != Drag.None) {
            val ended = e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL
            when (drag) {
                is Drag.Handle -> dragHandle(drag.bottomRight, e.x, e.y, ended)
                is Drag.Resize -> resize(drag, e.x, ended)
                is Drag.RowResize -> resizeRow(drag, e.y, ended)
                Drag.Fill -> dragFill(e.x, e.y, ended, e.actionMasked == MotionEvent.ACTION_CANCEL)
                is Drag.Chart -> moveChart(drag.drag, e.x, e.y, ended, e.actionMasked == MotionEvent.ACTION_CANCEL)
                Drag.None -> Unit
            }
            if (ended) dragging = Drag.None
            return true
        }
        gestures.onTouchEvent(e)
        return true
    }

    /** A drag that starts on a handle, a column edge or the chosen chart moves that; anything else scrolls. */
    private fun startDrag(x: Float, y: Float): Drag {
        val g = geometry
        if (controller.readOnly.value) return Drag.None
        val slop = 22 * d
        val chartId = controller.selectedChart.value
        if (chartId != null) {
            val chart = chartModels.firstOrNull { it.first.id == chartId }?.first
            if (chart != null) {
                val r = chartRect(chart)
                if (hypot(x - r.right, y - r.bottom) < slop) return Drag.Chart(ChartDrag(chartId, true, x, y).also { chartDrag = it })
                if (r.contains(x, y)) return Drag.Chart(ChartDrag(chartId, false, x, y).also { chartDrag = it })
            }
        } else if (editing == null) {
            val (tl, br) = handlePoints()
            val sel = controller.selection.value
            val brShown = handleVisible(sel.bottom, sel.right, br)
            if (filling && brShown && hypot(x - br.first, y - br.second) < slop) return Drag.Fill
            if (brShown && hypot(x - br.first, y - br.second) < slop) return Drag.Handle(bottomRight = true)
            if (handleVisible(sel.top, sel.left, tl) && hypot(x - tl.first, y - tl.second) < slop) return Drag.Handle(bottomRight = false)
        }
        if (y < g.columnHeaderHeight && x > g.rowHeaderWidth) {
            val col = g.columnEdgeNear(g.sheetX(x, offX), 14 * d)
            if (col != null) return Drag.Resize(col, g.width(col), x)
        }
        if (x < g.rowHeaderWidth && y > g.columnHeaderHeight) {
            val row = g.rowEdgeNear(g.sheetY(y, offY), 10 * d)
            if (row != null) return Drag.RowResize(row, g.height(row), y)
        }
        return Drag.None
    }

    private fun scrollBy(dx: Float, dy: Float) {
        offX += dx
        offY += dy
        clampOffsets()
        positionEditor()
        invalidate()
    }

    private fun clampOffsets() {
        val g = geometry
        offX = offX.coerceIn(0f, g.maxOffsetX(width.toFloat()))
        offY = offY.coerceIn(0f, g.maxOffsetY(height.toFloat()))
    }

    private fun tapped(x: Float, y: Float) {
        if (filling) { filling = false; fillTarget = null; invalidate() }
        val chart = chartAt(x, y)
        if (chart != null) {
            selectChart(chart.id)
            showChartMenu(x, y)
            return
        }
        when (val hit = geometry.hit(x, y, offX, offY)) {
            is GridGeometry.Hit.Cell -> {
                val a = SigmaAddress(hit.row, hit.column)
                if (editing == a) return
                if (controller.selectedChart.value != null) selectChart(null)
                val s = controller.selection.value
                // A tap on the cell already chosen opens it for typing; anywhere else chooses.
                if (s.isSingle && s.active == a && !controller.readOnly.value) beginEditing(null, focus = true)
                else select(SigmaSelection(a), scroll = false)
            }
            is GridGeometry.Hit.ColumnHeader -> {
                select(SigmaSelection(SigmaAddress(0, hit.column), SigmaAddress(geometry.rows - 1, hit.column)), scroll = false)
                showMenu(x, y)
            }
            is GridGeometry.Hit.RowHeader -> {
                select(SigmaSelection(SigmaAddress(hit.row, 0), SigmaAddress(hit.row, geometry.columns - 1)), scroll = false)
                showMenu(x, y)
            }
            GridGeometry.Hit.Corner -> select(SigmaSelection(SigmaAddress(0, 0), SigmaAddress(geometry.rows - 1, geometry.columns - 1)), scroll = false)
        }
    }

    private fun longPressed(x: Float, y: Float) {
        val chart = chartAt(x, y)
        if (chart != null) {
            selectChart(chart.id)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showChartMenu(x, y)
            return
        }
        val hit = geometry.hit(x, y, offX, offY)
        if (hit is GridGeometry.Hit.Cell) {
            val a = SigmaAddress(hit.row, hit.column)
            if (!controller.selection.value.contains(a)) select(SigmaSelection(a), scroll = false)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showMenu(x, y)
        }
    }

    private fun dragHandle(bottomRight: Boolean, x: Float, y: Float, ended: Boolean) {
        // Near an edge, the sheet scrolls under the finger.
        val g = geometry
        val edge = 36 * d
        val step = 14 * d
        var dx = 0f
        var dy = 0f
        if (x > width - edge) dx = step else if (x < g.rowHeaderWidth + g.frozenWidth + edge) dx = -step
        if (y > height - edge) dy = step else if (y < g.columnHeaderHeight + g.frozenHeight + edge) dy = -step
        if (dx != 0f || dy != 0f) scrollBy(dx, dy)
        val hit = g.hit(max(x, g.rowHeaderWidth + 1), max(y, g.columnHeaderHeight + 1), offX, offY)
        if (hit is GridGeometry.Hit.Cell) {
            val s = controller.selection.value
            val fixed = if (bottomRight) s.topLeft else s.bottomRight
            val next = SigmaSelection(fixed, SigmaAddress(hit.row, hit.column))
            if (next != s) {
                controller.setSelection(next)
                refreshActive()
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                invalidate()
            }
        }
        if (ended) showMenu(x, y)
    }

    private fun resize(drag: Drag.Resize, x: Float, ended: Boolean) {
        val w = max(24 * pt, drag.startWidth + x - drag.startX)
        if (!ended) {
            liveColumnWidth = drag.column to w
            rebuildGeometry()
            invalidate()
            return
        }
        val book = workbook ?: return
        scope.launch {
            runCatching { book.setColumnWidth(drag.column, (w / pt).toDouble()) }
            liveColumnWidth = null
        }
    }

    private fun resizeRow(drag: Drag.RowResize, y: Float, ended: Boolean) {
        val h = max(14 * pt, drag.startHeight + y - drag.startY)
        if (!ended) {
            liveRowHeight = drag.row to h
            rebuildGeometry()
            invalidate()
            return
        }
        val book = workbook ?: return
        scope.launch {
            runCatching { book.setRowHeight(drag.row, (h / pt).toDouble()) }
            liveRowHeight = null
        }
    }

    /** Fill mode: the target grows from the selection in the direction dragged furthest. */
    private fun dragFill(x: Float, y: Float, ended: Boolean, cancelled: Boolean) {
        val g = geometry
        val s = controller.selection.value
        val hit = g.hit(max(x, g.rowHeaderWidth + 1), max(y, g.columnHeaderHeight + 1), offX, offY)
        if (hit is GridGeometry.Hit.Cell) {
            val down = when { hit.row > s.bottom -> hit.row - s.bottom; hit.row < s.top -> s.top - hit.row; else -> 0 }
            val across = when { hit.column > s.right -> hit.column - s.right; hit.column < s.left -> s.left - hit.column; else -> 0 }
            fillTarget = when {
                down == 0 && across == 0 -> null
                down >= across -> SigmaSelection(SigmaAddress(min(s.top, hit.row), s.left), SigmaAddress(max(s.bottom, hit.row), s.right))
                else -> SigmaSelection(SigmaAddress(s.top, min(s.left, hit.column)), SigmaAddress(s.bottom, max(s.right, hit.column)))
            }
            invalidate()
        }
        if (!ended) return
        val target = fillTarget
        fillTarget = null
        filling = false
        invalidate()
        val book = workbook ?: return
        if (cancelled || target == null) return
        scope.launch {
            runCatching { book.fill(s, target) }.onFailure { flash(it) }
            controller.setSelection(SigmaSelection(target.topLeft, target.bottomRight))
            refreshActive()
            invalidate()
        }
    }

    private fun moveChart(drag: ChartDrag, x: Float, y: Float, ended: Boolean, cancelled: Boolean) {
        drag.dx = x - drag.startX
        drag.dy = y - drag.startY
        if (!ended) { invalidate(); return }
        val moved = hypot(drag.dx, drag.dy) > 8 * d
        val book = workbook
        val chart = chartModels.firstOrNull { it.first.id == drag.id }?.first
        if (cancelled || !moved || book == null || chart == null) {
            chartDrag = null
            invalidate()
            // A touch that did not move is a tap on the chart.
            if (!cancelled && !moved) showChartMenu(x, y)
            return
        }
        val g = geometry
        val next = if (drag.resize) {
            chart.copy(width = (max(200 * pt, (chart.width * pt).toFloat() + drag.dx) / pt).toDouble().let { Math.round(it).toDouble() },
                height = (max(140 * pt, (chart.height * pt).toFloat() + drag.dy) / pt).toDouble().let { Math.round(it).toDouble() })
        } else {
            // The chart settles on the cell under its corner.
            val sx = g.left(chart.column) + drag.dx
            val sy = g.top(chart.row) + drag.dy
            chart.copy(row = g.rowAt(max(0f, sy)), column = g.columnAt(max(0f, sx)))
        }
        scope.launch {
            runCatching { book.updateChart(next) }.onFailure { flash(it) }
            chartDrag = null
            invalidate()
        }
    }

    // Selection

    fun select(s: SigmaSelection, scroll: Boolean, force: Boolean = false) {
        if (!force && s == controller.selection.value) return
        if (editing != null) commitEditing(Move.NONE)
        controller.setSelection(s)
        refreshActive()
        if (scroll) scrollToVisible(s.corner)
        invalidate()
        if (controller.panel.value == null && !hasFocus()) requestFocus()
    }

    private var activeJob: Job? = null

    /** Reads what the active cell holds, its style and note, for the formula and format bars. */
    fun refreshActive() {
        val book = workbook ?: return
        activeJob?.cancel()
        activeJob = scope.launch {
            val a = controller.selection.value.active
            runCatching {
                val input = book.input(a)
                val style = book.style(a)
                val note = book.note(a)
                if (controller.selection.value.active == a) controller.setActive(input, style, note)
            }
        }
    }

    private fun scrollToVisible(a: SigmaAddress) {
        val g = geometry
        if (a.column >= g.frozenColumns) {
            val viewW = width - g.rowHeaderWidth - g.frozenWidth
            val l = g.left(a.column) - g.frozenWidth
            val r = l + g.width(a.column)
            if (l < offX) offX = l else if (r > offX + viewW) offX = r - viewW
        }
        if (a.row >= g.frozenRows) {
            val viewH = height - g.columnHeaderHeight - g.frozenHeight
            val t = g.top(a.row) - g.frozenHeight
            val b = t + g.height(a.row)
            if (t < offY) offY = t else if (b > offY + viewH) offY = b - viewH
        }
        clampOffsets()
        positionEditor()
        invalidate()
    }

    // Editing

    fun beginEditing(initial: String?, focus: Boolean) {
        if (controller.readOnly.value || workbook == null) return
        if (editing != null) {
            if (focus) showKeyboard()
            return
        }
        val s = controller.selection.value
        val a = s.active
        if (!s.isSingle) controller.setSelection(SigmaSelection(a))
        editing = a
        editStart = controller.activeInput.value
        val raw = initial ?: editStart
        val style = controller.activeStyle.value
        settingEditor = true
        editor.setText(raw)
        editor.setSelection(raw.length)
        settingEditor = false
        editor.setTextSize(TypedValue.COMPLEX_UNIT_DIP, (style.fontSize ?: 13.0).toFloat() * zoom)
        editor.setTypeface(null, if (style.bold == true) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        editor.visibility = VISIBLE
        controller.setEditing(true)
        controller.setEditText(raw)
        scrollToVisible(a)
        positionEditor()
        if (focus) showKeyboard()
        invalidate()
    }

    private fun showKeyboard() {
        editor.requestFocus()
        editor.post {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken, 0)
    }

    fun setEditorText(text: String) {
        if (editing == null) beginEditing(text, focus = false)
        else if (editor.text.toString() != text) {
            settingEditor = true
            editor.setText(text)
            editor.setSelection(text.length)
            settingEditor = false
        }
        controller.setEditText(text)
        sizeEditor()
    }

    fun insertIntoEditor(text: String) {
        if (editing == null) beginEditing(null, focus = true)
        val start = max(0, editor.selectionStart)
        val end = max(0, editor.selectionEnd)
        editor.text.replace(min(start, end), max(start, end), text)
    }

    private fun positionEditor() {
        val a = editing ?: return
        val g = geometry
        editor.translationX = g.screenX(a.column, offX)
        editor.translationY = g.screenY(a.row, offY)
        sizeEditor()
    }

    /** The editor covers its cell, and grows to the right only when the text needs the room. */
    private fun sizeEditor() {
        val a = editing ?: return
        val g = geometry
        val cw = g.width(a.column)
        val textW = editor.paint.measureText(editor.text.toString()) + 18 * d
        val room = max(cw, width - g.rowHeaderWidth - 16 * d)
        val w = min(max(cw, textW), room).toInt()
        val h = g.height(a.row).toInt()
        val lp = editor.layoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            editor.layoutParams = lp
        }
    }

    fun commitEditing(move: Move) {
        val a = editing ?: return
        val book = workbook ?: return
        val text = controller.editText.value
        editing = null
        controller.setEditing(false)
        // Focus first: hiding a focused editor hands focus to the next view, the formula bar.
        requestFocus()
        editor.visibility = GONE
        if (move != Move.NONE) hideKeyboard()
        if (text != editStart) {
            scope.launch { runCatching { book.set(text, a) }.onFailure { flash(it) } }
        }
        val g = geometry
        val next = when (move) {
            Move.DOWN -> SigmaAddress(min(g.rows - 1, a.row + 1), a.column)
            Move.UP -> SigmaAddress(max(0, a.row - 1), a.column)
            Move.RIGHT -> SigmaAddress(a.row, min(g.columns - 1, a.column + 1))
            Move.LEFT -> SigmaAddress(a.row, max(0, a.column - 1))
            Move.NONE -> a
        }
        controller.setSelection(SigmaSelection(next))
        refreshActive()
        scrollToVisible(next)
        requestFocus()
        invalidate()
    }

    fun cancelEditing() {
        if (editing == null) return
        editing = null
        controller.setEditing(false)
        requestFocus()
        editor.visibility = GONE
        hideKeyboard()
        invalidate()
    }

    // Actions

    fun perform(action: Action) {
        val book = workbook ?: return
        val s = controller.selection.value
        if (controller.readOnly.value && action != Action.COPY && action != Action.FIND) return
        when (action) {
            Action.EDIT -> return beginEditing(null, focus = true)
            Action.SORT_ASCENDING -> return controller.sort(true)
            Action.SORT_DESCENDING -> return controller.sort(false)
            Action.NOTE -> return controller.showPanel(SigmaGridController.Panel.NOTE)
            Action.CONDITIONAL_FORMATTING -> return controller.showPanel(SigmaGridController.Panel.CONDITIONAL_FORMATTING)
            Action.FIND -> return controller.showPanel(SigmaGridController.Panel.FIND)
            Action.INSERT_CHART -> return controller.insertChart()
            Action.EDIT_CHART -> return controller.showPanel(SigmaGridController.Panel.CHART)
            Action.FILL -> {
                // The corner handle becomes a fill handle until the next fill or tap.
                if (editing != null) commitEditing(Move.NONE)
                filling = true
                invalidate()
                return
            }
            else -> Unit
        }
        if (action == Action.UNDO && editing != null) return cancelEditing()
        if (editing != null) commitEditing(Move.NONE)
        val rows = s.rowCount
        val cols = s.columnCount
        scope.launch {
            try {
                when (action) {
                    Action.UNDO -> book.undo()
                    Action.REDO -> book.redo()
                    Action.COPY, Action.CUT -> {
                        val c = if (action == Action.CUT) book.cut(s) else book.copy(s)
                        clip = c
                        clipboard().setPrimaryClip(ClipData.newPlainText("Sigma", c.text))
                    }
                    Action.PASTE -> {
                        val text = clipboard().primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString() ?: return@launch
                        val (r, c) = book.paste(text, s.topLeft, clip)
                        if (clip?.isCut == true) clip = null
                        if (r > 0 && c > 0) controller.setSelection(SigmaSelection(s.topLeft, SigmaAddress(s.top + r - 1, s.left + c - 1)))
                    }
                    Action.PASTE_VALUES -> {
                        val c = clip ?: return@launch
                        val (r, cc) = book.pasteValues(c, s.topLeft)
                        if (r > 0 && cc > 0) controller.setSelection(SigmaSelection(s.topLeft, SigmaAddress(s.top + r - 1, s.left + cc - 1)))
                    }
                    Action.CLEAR -> book.clear(s)
                    Action.FILL_DOWN -> book.fill(SigmaSelection(s.topLeft, SigmaAddress(s.top, s.right)), s)
                    Action.FILL_RIGHT -> book.fill(SigmaSelection(s.topLeft, SigmaAddress(s.bottom, s.left)), s)
                    Action.INSERT_ROW_ABOVE -> book.insertRows(s.top, rows)
                    Action.INSERT_ROW_BELOW -> book.insertRows(s.bottom + 1, rows)
                    Action.DELETE_ROWS -> book.deleteRows(s.top, rows)
                    Action.INSERT_COLUMN_LEFT -> book.insertColumns(s.left, cols)
                    Action.INSERT_COLUMN_RIGHT -> book.insertColumns(s.right + 1, cols)
                    Action.DELETE_COLUMNS -> book.deleteColumns(s.left, cols)
                    Action.FREEZE_HERE -> book.freeze(s.active.row + 1, s.active.column + 1)
                    Action.FREEZE_ONE_ROW -> book.freeze(1, geometry.frozenColumns)
                    Action.FREEZE_TWO_ROWS -> book.freeze(2, geometry.frozenColumns)
                    Action.FREEZE_ONE_COLUMN -> book.freeze(geometry.frozenRows, 1)
                    Action.UNFREEZE -> book.freeze(0, 0)
                    Action.DELETE_CHART -> controller.selectedChart.value?.let { book.removeChart(it); controller.setSelectedChart(null) }
                    else -> Unit
                }
            } catch (e: Exception) {
                flash(e)
            }
            refreshActive()
            invalidate()
        }
    }

    private fun clipboard() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    fun flash(error: Throwable) {
        performHapticFeedback(HapticFeedbackConstants.REJECT)
        val message = (error as? SigmaException)?.message ?: error.message ?: return
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    // Menus

    private fun popup(x: Float, y: Float): PopupMenu {
        menuAnchor.translationX = x
        menuAnchor.translationY = y
        return PopupMenu(context, menuAnchor, Gravity.NO_GRAVITY)
    }

    private fun showChartMenu(x: Float, y: Float) {
        if (controller.readOnly.value) return
        val m = popup(x, y)
        m.menu.add(0, Action.EDIT_CHART.ordinal, 0, "Edit chart")
        m.menu.add(0, Action.DELETE_CHART.ordinal, 1, "Delete chart")
        m.setOnMenuItemClickListener { perform(Action.entries[it.itemId]); true }
        m.show()
    }

    fun showMenu(x: Float, y: Float) {
        if (editing != null) return
        val s = controller.selection.value
        val g = geometry
        val wholeRows = s.left == 0 && s.right == g.columns - 1
        val wholeColumns = s.top == 0 && s.bottom == g.rows - 1
        val ro = controller.readOnly.value
        val m = popup(x, y)
        val menu = m.menu
        var order = 0
        fun add(to: Menu, title: String, a: Action) { to.add(0, a.ordinal, order++, title) }
        if (!ro) {
            if (s.isSingle) add(menu, "Edit", Action.EDIT)
            add(menu, "Cut", Action.CUT)
        }
        add(menu, "Copy", Action.COPY)
        if (!ro) {
            if (clipboard().hasPrimaryClip()) add(menu, "Paste", Action.PASTE)
            // Values only needs Sigma's own copy, which knows what the cells computed.
            if (clip != null) add(menu, "Paste values only", Action.PASTE_VALUES)
            add(menu, "Clear", Action.CLEAR)
            if (!wholeRows && !wholeColumns) {
                val fill = menu.addSubMenu(0, 1004, order++, "Fill")
                add(fill, "Drag to fill…", Action.FILL)
                if (s.bottom > s.top) add(fill, "Fill down", Action.FILL_DOWN)
                if (s.right > s.left) add(fill, "Fill right", Action.FILL_RIGHT)
            }
            val rowWord = if (s.bottom > s.top) "rows" else "row"
            val colWord = if (s.right > s.left) "columns" else "column"
            fun rowItems(to: Menu) {
                add(to, "Insert $rowWord above", Action.INSERT_ROW_ABOVE)
                add(to, "Insert $rowWord below", Action.INSERT_ROW_BELOW)
                add(to, "Delete ${rowWord}", Action.DELETE_ROWS)
            }
            fun colItems(to: Menu) {
                add(to, "Insert $colWord left", Action.INSERT_COLUMN_LEFT)
                add(to, "Insert $colWord right", Action.INSERT_COLUMN_RIGHT)
                add(to, "Delete $colWord", Action.DELETE_COLUMNS)
            }
            when {
                wholeRows -> rowItems(menu)
                wholeColumns -> colItems(menu)
                else -> {
                    rowItems(menu.addSubMenu(0, 1000, order++, "Rows"))
                    colItems(menu.addSubMenu(0, 1001, order++, "Columns"))
                }
            }
            if (!wholeRows) {
                val sort = menu.addSubMenu(0, 1002, order++, "Sort")
                add(sort, "Sort A → Z", Action.SORT_ASCENDING)
                add(sort, "Sort Z → A", Action.SORT_DESCENDING)
            }
            if (s.isSingle) add(menu, if (controller.activeNote.value.isEmpty()) "Add note" else "Edit note", Action.NOTE)
            val freeze = menu.addSubMenu(0, 1003, order++, "Freeze")
            add(freeze, "1 row", Action.FREEZE_ONE_ROW)
            add(freeze, "2 rows", Action.FREEZE_TWO_ROWS)
            add(freeze, "1 column", Action.FREEZE_ONE_COLUMN)
            add(freeze, "Up to ${s.active}", Action.FREEZE_HERE)
            if (g.frozenRows > 0 || g.frozenColumns > 0) add(freeze, "No frozen panes", Action.UNFREEZE)
        }
        m.setOnMenuItemClickListener {
            if (it.itemId < Action.entries.size) perform(Action.entries[it.itemId])
            true
        }
        m.show()
    }

    // Hardware keyboard

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if (editing != null && !editor.hasFocus()) editor.onKeyUp(keyCode, event) else super.onKeyUp(keyCode, event)

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Keys typed fast after the first can reach the grid before the editor has focus: they belong to the edit.
        if (editing != null && !editor.hasFocus()) return editor.onKeyDown(keyCode, event)
        if (editing != null || controller.panel.value != null || workbook == null) return super.onKeyDown(keyCode, event)
        val s = controller.selection.value
        val g = geometry
        if (event.isCtrlPressed || event.isMetaPressed) {
            when (keyCode) {
                KeyEvent.KEYCODE_C -> perform(Action.COPY)
                KeyEvent.KEYCODE_X -> perform(Action.CUT)
                KeyEvent.KEYCODE_V -> perform(Action.PASTE)
                KeyEvent.KEYCODE_Z -> perform(if (event.isShiftPressed) Action.REDO else Action.UNDO)
                KeyEvent.KEYCODE_Y -> perform(Action.REDO)
                KeyEvent.KEYCODE_D -> perform(Action.FILL_DOWN)
                KeyEvent.KEYCODE_R -> perform(Action.FILL_RIGHT)
                KeyEvent.KEYCODE_B -> controller.toggleBold()
                KeyEvent.KEYCODE_I -> controller.toggleItalic()
                KeyEvent.KEYCODE_U -> controller.toggleUnderline()
                KeyEvent.KEYCODE_F -> perform(Action.FIND)
                // Zoom about the middle of the sheet, as a pinch would.
                KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_NUMPAD_ADD -> setZoom(zoom * 1.25f, width / 2f, height / 2f)
                KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> setZoom(zoom / 1.25f, width / 2f, height / 2f)
                KeyEvent.KEYCODE_0 -> setZoom(1f, width / 2f, height / 2f)
                KeyEvent.KEYCODE_A -> select(SigmaSelection(SigmaAddress(0, 0), SigmaAddress(g.rows - 1, g.columns - 1)), scroll = false)
                else -> return super.onKeyDown(keyCode, event)
            }
            return true
        }
        val extend = event.isShiftPressed
        fun moved(dr: Int, dc: Int) {
            val from = if (extend) s.corner else s.active
            val to = SigmaAddress((from.row + dr).coerceIn(0, g.rows - 1), (from.column + dc).coerceIn(0, g.columns - 1))
            select(if (extend) SigmaSelection(s.active, to) else SigmaSelection(to), scroll = true)
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> moved(-1, 0)
            KeyEvent.KEYCODE_DPAD_DOWN -> moved(1, 0)
            KeyEvent.KEYCODE_DPAD_LEFT -> moved(0, -1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> moved(0, 1)
            KeyEvent.KEYCODE_TAB -> moved(0, if (extend) -1 else 1)
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> if (extend) moved(-1, 0) else beginEditing(null, focus = true)
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL -> perform(Action.CLEAR)
            else -> {
                val ch = event.unicodeChar
                if (ch == 0 || controller.readOnly.value || Character.isISOControl(ch)) return super.onKeyDown(keyCode, event)
                beginEditing(String(Character.toChars(ch)), focus = true)
            }
        }
        return true
    }
}

/**
 * The in-cell text field. Tab and the arrow keys of a hardware keyboard
 * finish the edit and move, as in a desktop spreadsheet; Escape cancels.
 */
internal class CellEditor(context: Context) : EditText(context) {
    /** A move to commit with, or null to cancel. */
    var onKey: ((SigmaGridView.Move?) -> Unit)? = null

    init {
        val d = resources.displayMetrics.density
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            setStroke((2 * d).toInt(), GridColors.accent)
        }
        setPadding((5 * d).toInt(), 0, (5 * d).toInt(), 0)
        gravity = Gravity.CENTER_VERTICAL
        setTextColor(GridColors.ink)
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        includeFontPadding = false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val move = when (keyCode) {
            KeyEvent.KEYCODE_TAB -> if (event.isShiftPressed) SigmaGridView.Move.LEFT else SigmaGridView.Move.RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> SigmaGridView.Move.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> SigmaGridView.Move.DOWN
            KeyEvent.KEYCODE_ESCAPE -> { onKey?.invoke(null); return true }
            else -> return super.onKeyDown(keyCode, event)
        }
        onKey?.invoke(move)
        return true
    }
}
