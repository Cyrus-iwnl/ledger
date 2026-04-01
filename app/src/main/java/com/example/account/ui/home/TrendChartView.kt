package com.example.account.ui.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.example.account.R
import com.example.account.data.ChartPoint
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class TrendChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val incomePath = Path()
    private val expensePath = Path()
    private val incomeLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val expenseLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(11f)
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val axisTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(9f)
    }
    private val selectionGuidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val selectionPointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val selectionPointRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }

    private var points: List<ChartPoint> = emptyList()
    private var drawIncomeLine: Boolean = true
    private var drawExpenseLine: Boolean = true
    private var selectedDayLabel: String? = null
    private var onPointSelectedListener: ((ChartPoint?) -> Unit)? = null

    fun submitData(
        newPoints: List<ChartPoint>,
        showIncome: Boolean = true,
        showExpense: Boolean = true
    ) {
        points = newPoints
        drawIncomeLine = showIncome
        drawExpenseLine = showExpense
        if (selectedDayLabel != null && points.none { it.label == selectedDayLabel }) {
            selectedDayLabel = null
        }
        if (points.isEmpty()) {
            selectedDayLabel = null
        }
        invalidate()
    }

    fun setOnPointSelectedListener(listener: ((ChartPoint?) -> Unit)?) {
        onPointSelectedListener = listener
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.isEmpty() || (!drawIncomeLine && !drawExpenseLine)) {
            emptyPaint.color = context.getColor(R.color.text_hint)
            canvas.drawText(context.getString(R.string.trend_no_data), width / 2f, height / 2f, emptyPaint)
            return
        }

        val geometry = buildGeometry() ?: return
        val x = geometry.x

        if (drawIncomeLine) {
            incomeLinePaint.color = context.getColor(R.color.income_color)
            drawSeriesLine(
                canvas = canvas,
                x = x,
                values = points.map { it.income },
                path = incomePath,
                paint = incomeLinePaint,
                y = geometry.incomeY
            )
        }
        if (drawExpenseLine) {
            expenseLinePaint.color = context.getColor(R.color.expense_color)
            drawSeriesLine(
                canvas = canvas,
                x = x,
                values = points.map { it.expense },
                path = expensePath,
                paint = expenseLinePaint,
                y = geometry.expenseY
            )
        }

        axisPaint.color = context.getColor(R.color.card_stroke)
        axisTextPaint.color = context.getColor(R.color.text_hint)
        canvas.drawLine(geometry.left, geometry.axisY, geometry.right, geometry.axisY, axisPaint)

        points.indices.forEach { index ->
            val dayLabel = dayLabel(points[index].label)
            canvas.drawLine(x[index], geometry.axisY - dp(2f), x[index], geometry.axisY + dp(2f), axisPaint)
            canvas.drawText(dayLabel, x[index], geometry.labelY, axisTextPaint)
        }

        val selectedIndex = points.indexOfFirst { it.label == selectedDayLabel }
        if (selectedIndex >= 0) {
            selectionGuidePaint.color = context.getColor(R.color.text_hint)
            selectionGuidePaint.alpha = 140
            canvas.drawLine(
                x[selectedIndex],
                geometry.top,
                x[selectedIndex],
                geometry.axisY,
                selectionGuidePaint
            )
            drawSelectionPoint(
                canvas = canvas,
                x = x[selectedIndex],
                y = geometry.incomeY[selectedIndex],
                color = context.getColor(R.color.income_color),
                visible = drawIncomeLine
            )
            drawSelectionPoint(
                canvas = canvas,
                x = x[selectedIndex],
                y = geometry.expenseY[selectedIndex],
                color = context.getColor(R.color.expense_color),
                visible = drawExpenseLine
            )
        }
    }

    private fun drawSeriesLine(
        canvas: Canvas,
        x: FloatArray,
        values: List<Double>,
        path: Path,
        paint: Paint,
        y: FloatArray
    ) {
        if (x.isEmpty() || values.isEmpty() || y.isEmpty()) {
            return
        }

        if (x.size == 1) {
            canvas.drawCircle(x.first(), y.first(), dp(3f), paint)
            return
        }

        path.reset()
        path.moveTo(x.first(), y.first())
        for (index in 1 until x.size) {
            path.lineTo(x[index], y[index])
        }
        canvas.drawPath(path, paint)
    }

    private fun drawSelectionPoint(canvas: Canvas, x: Float, y: Float, color: Int, visible: Boolean) {
        if (!visible) {
            return
        }
        selectionPointPaint.color = color
        selectionPointRingPaint.color = color
        canvas.drawCircle(x, y, dp(3.5f), selectionPointPaint)
        canvas.drawCircle(x, y, dp(6f), selectionPointRingPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (points.isEmpty() || (!drawIncomeLine && !drawExpenseLine)) {
            return super.onTouchEvent(event)
        }
        val geometry = buildGeometry() ?: return super.onTouchEvent(event)
        val hit = isAxisHit(event, geometry) || isCurveHit(event, geometry)
        if (!hit) {
            if (event.actionMasked == MotionEvent.ACTION_UP && selectedDayLabel != null) {
                clearSelectedPoint()
                performClick()
                return true
            }
            return super.onTouchEvent(event)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                val nearestIndex = nearestIndexByX(event.x, geometry.x)
                selectIndex(nearestIndex)
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    performClick()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun selectIndex(index: Int) {
        val point = points.getOrNull(index) ?: return
        if (selectedDayLabel == point.label) {
            return
        }
        selectedDayLabel = point.label
        onPointSelectedListener?.invoke(point)
        invalidate()
    }

    fun clearSelectedPoint() {
        if (selectedDayLabel == null) {
            return
        }
        selectedDayLabel = null
        onPointSelectedListener?.invoke(null)
        invalidate()
    }

    private fun isAxisHit(event: MotionEvent, geometry: ChartGeometry): Boolean {
        val xHit = event.x in (geometry.left - dp(12f))..(geometry.right + dp(12f))
        val yHit = abs(event.y - geometry.axisY) <= dp(14f)
        return xHit && yHit
    }

    private fun isCurveHit(event: MotionEvent, geometry: ChartGeometry): Boolean {
        val tolerance = dp(14f)
        if (drawIncomeLine && isNearPolyline(event.x, event.y, geometry.x, geometry.incomeY, tolerance)) {
            return true
        }
        if (drawExpenseLine && isNearPolyline(event.x, event.y, geometry.x, geometry.expenseY, tolerance)) {
            return true
        }
        return false
    }

    private fun isNearPolyline(
        touchX: Float,
        touchY: Float,
        x: FloatArray,
        y: FloatArray,
        tolerance: Float
    ): Boolean {
        if (x.isEmpty() || y.isEmpty() || x.size != y.size) {
            return false
        }
        for (i in x.indices) {
            if (hypot((touchX - x[i]).toDouble(), (touchY - y[i]).toDouble()) <= tolerance) {
                return true
            }
        }
        for (i in 1 until x.size) {
            if (distanceToSegment(touchX, touchY, x[i - 1], y[i - 1], x[i], y[i]) <= tolerance) {
                return true
            }
        }
        return false
    }

    private fun distanceToSegment(
        px: Float,
        py: Float,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float
    ): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        if (dx == 0f && dy == 0f) {
            return hypot((px - x1).toDouble(), (py - y1).toDouble()).toFloat()
        }
        val t = (((px - x1) * dx) + ((py - y1) * dy)) / (dx * dx + dy * dy)
        val clamped = min(1f, max(0f, t))
        val projectionX = x1 + clamped * dx
        val projectionY = y1 + clamped * dy
        return hypot((px - projectionX).toDouble(), (py - projectionY).toDouble()).toFloat()
    }

    private fun nearestIndexByX(touchX: Float, x: FloatArray): Int {
        var nearest = 0
        var minDistance = Float.MAX_VALUE
        for (i in x.indices) {
            val distance = abs(touchX - x[i])
            if (distance < minDistance) {
                minDistance = distance
                nearest = i
            }
        }
        return nearest
    }

    private fun buildGeometry(): ChartGeometry? {
        if (points.isEmpty()) {
            return null
        }
        val left = paddingLeft + dp(8f)
        val top = paddingTop + dp(8f)
        val right = width - paddingRight - dp(8f)
        val chartBottom = height - paddingBottom - dp(24f)
        val axisY = height - paddingBottom - dp(14f)
        val labelY = height - paddingBottom - dp(2f)

        val values = buildList {
            if (drawIncomeLine) {
                addAll(points.map { it.income })
            }
            if (drawExpenseLine) {
                addAll(points.map { it.expense })
            }
        }.ifEmpty { listOf(0.0) }
        val minValue = values.minOrNull() ?: 0.0
        val maxValue = values.maxOrNull() ?: 1.0
        val valueRange = max(1.0, maxValue - minValue)
        fun pointY(value: Double): Float {
            val ratio = ((value - minValue) / valueRange).toFloat()
            return chartBottom - ratio * (chartBottom - top)
        }

        val stepX = if (points.size <= 1) 0f else (right - left) / (points.size - 1)
        val x = FloatArray(points.size)
        val incomeY = FloatArray(points.size)
        val expenseY = FloatArray(points.size)
        points.indices.forEach { index ->
            x[index] = left + stepX * index
            incomeY[index] = pointY(points[index].income)
            expenseY[index] = pointY(points[index].expense)
        }
        return ChartGeometry(
            left = left,
            top = top,
            right = right,
            axisY = axisY,
            labelY = labelY,
            x = x,
            incomeY = incomeY,
            expenseY = expenseY
        )
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun dayLabel(label: String): String {
        val normalized = label.replace('/', '.')
        val day = normalized.substringAfterLast('.', normalized)
        return day.takeLast(2)
    }

    private fun sp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value,
        resources.displayMetrics
    )

    private data class ChartGeometry(
        val left: Float,
        val top: Float,
        val right: Float,
        val axisY: Float,
        val labelY: Float,
        val x: FloatArray,
        val incomeY: FloatArray,
        val expenseY: FloatArray
    )
}

