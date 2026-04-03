package com.example.account.ui.insights

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class InsightsDonutChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val segmentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(9f)
        isFakeBoldText = true
    }
    private val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val arcRect = RectF()
    private val linePath = Path()
    private var slices: List<InsightsCategorySlice> = emptyList()
    private var selectedCategoryId: String? = null
    private var onSelectionChanged: ((String?) -> Unit)? = null
    private var lastSegments: List<Segment> = emptyList()

    fun submitData(slices: List<InsightsCategorySlice>, selectedCategoryId: String?) {
        this.slices = slices
        this.selectedCategoryId = selectedCategoryId
        invalidate()
    }

    fun setOnSelectionChangedListener(listener: ((String?) -> Unit)?) {
        onSelectionChanged = listener
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (slices.isEmpty()) return

        val chartSize = min(dp(180f), min(width.toFloat(), height.toFloat() - dp(12f)))
        if (chartSize <= 0f) return
        val scale = chartSize / dp(180f)
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = dp(63f) * scale
        val strokeWidth = dp(27f) * scale
        val holeRadius = dp(60f) * scale
        val trackColor = Color.parseColor("#E5E7EB")
        val labelColor = ContextCompat.getColor(context, com.example.account.R.color.insights_on_surface_variant)

        trackPaint.strokeWidth = strokeWidth
        trackPaint.color = trackColor
        segmentPaint.strokeWidth = strokeWidth
        linePaint.strokeWidth = dp(1.5f) * scale
        textPaint.color = labelColor

        arcRect.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
        canvas.drawArc(arcRect, 0f, 360f, false, trackPaint)

        val ratios = slices.map { if (it.ratio > 0.0) it.ratio else 0.0 }
        val ratioSum = ratios.sum()
        val normalized = if (ratioSum > 0.0) ratios.map { it / ratioSum } else slices.mapIndexed { index, _ ->
            if (index == 0) 1.0 else 0.0
        }
        val overlapDegrees = if (slices.size > 1) 0.8f * 360f / (2f * PI.toFloat() * 42f) else 0f
        var startAngle = -90f
        val segments = mutableListOf<Segment>()

        slices.forEachIndexed { index, slice ->
            val sweep = if (index == slices.lastIndex) {
                270f - startAngle
            } else {
                (normalized[index] * 360.0).toFloat()
            }
            val color = slice.accentColor
            val active = selectedCategoryId == null || selectedCategoryId == slice.categoryId
            segmentPaint.color = color
            segmentPaint.alpha = if (active) 255 else 184
            canvas.drawArc(arcRect, startAngle, min(360f, sweep + overlapDegrees), false, segmentPaint)
            segments += Segment(slice, startAngle, sweep, color, active)
            startAngle += sweep
        }
        lastSegments = segments

        val minLabelRatio = 0.03
        val callouts = segments.filter { it.slice.ratio > minLabelRatio }.map { segment ->
            val midAngle = Math.toRadians((segment.startAngle + (segment.sweep / 2f)).toDouble())
            val ux = cos(midAngle).toFloat()
            val uy = sin(midAngle).toFloat()
            val sx = centerX + ux * (radius + dp(13.5f) * scale)
            val sy = centerY + uy * (radius + dp(13.5f) * scale)
            val mx = centerX + ux * (radius + dp(24f) * scale)
            val my = centerY + uy * (radius + dp(24f) * scale)
            Callout(
                segment = segment,
                side = if (ux >= 0f) Side.RIGHT else Side.LEFT,
                sx = sx,
                sy = sy,
                mx = mx,
                my = my,
                targetY = my
            )
        }.toMutableList()

        spreadCallouts(callouts.filter { it.side == Side.RIGHT })
        spreadCallouts(callouts.filter { it.side == Side.LEFT })

        val edgeOffset = dp(81f) * scale
        val labelOffset = dp(2.7f) * scale
        val textYOffset = dp(1.2f) * scale
        callouts.forEach { callout ->
            val ex = if (callout.side == Side.RIGHT) centerX + edgeOffset else centerX - edgeOffset
            val ey = callout.labelY
            val tx = if (callout.side == Side.RIGHT) ex + labelOffset else ex - labelOffset
            val label = "${callout.segment.slice.name} ${formatPercent(callout.segment.slice.ratio)}"
            linePaint.color = callout.segment.color
            linePaint.alpha = if (callout.segment.active) 178 else 115
            linePath.reset()
            linePath.moveTo(callout.sx, callout.sy)
            linePath.lineTo(callout.mx, callout.my)
            linePath.lineTo(ex, ey)
            canvas.drawPath(linePath, linePaint)
            textPaint.alpha = if (callout.segment.active) 255 else 115
            textPaint.textAlign = if (callout.side == Side.RIGHT) Paint.Align.LEFT else Paint.Align.RIGHT
            canvas.drawText(label, tx, ey - textYOffset, textPaint)
        }

        holePaint.color = Color.WHITE
        canvas.drawCircle(centerX, centerY, holeRadius, holePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (slices.isEmpty()) return super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_DOWN) {
            val segment = hitTest(event.x, event.y)
            val newSelection = segment?.slice?.categoryId
            if (selectedCategoryId != newSelection) {
                selectedCategoryId = newSelection
                onSelectionChanged?.invoke(newSelection)
                invalidate()
            } else if (segment == null && selectedCategoryId != null) {
                selectedCategoryId = null
                onSelectionChanged?.invoke(null)
                invalidate()
            }
            performClick()
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun hitTest(x: Float, y: Float): Segment? {
        if (lastSegments.isEmpty()) return null
        val centerX = width / 2f
        val centerY = height / 2f
        val chartSize = min(dp(180f), min(width.toFloat(), height.toFloat() - dp(12f)))
        val scale = chartSize / dp(180f)
        val radius = dp(63f) * scale
        val strokeWidth = dp(27f) * scale
        val dx = x - centerX
        val dy = y - centerY
        val distance = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        if (distance < radius - (strokeWidth / 2f) || distance > radius + (strokeWidth / 2f)) {
            return null
        }
        // Keep hit-test angle in the same coordinate system as Canvas#drawArc:
        // 0 deg at 3 o'clock, increasing clockwise.
        var angle = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
        if (angle < 0f) angle += 360f
        return lastSegments.firstOrNull {
            val start = normalizeAngle(it.startAngle)
            val end = normalizeAngle(it.startAngle + it.sweep)
            if (end < start) angle >= start || angle <= end else angle in start..end
        }
    }

    private fun normalizeAngle(angle: Float): Float {
        var normalized = angle
        while (normalized < 0f) normalized += 360f
        while (normalized > 360f) normalized -= 360f
        return normalized
    }

    private fun spreadCallouts(entries: List<Callout>) {
        if (entries.isEmpty()) return
        val minGap = dp(10.5f)
        val minY = (height / 2f) - dp(78f)
        val maxY = (height / 2f) + dp(78f)
        val sorted = entries.sortedBy { it.targetY }
        sorted.forEachIndexed { index, entry ->
            entry.labelY = if (index == 0) max(minY, entry.targetY) else max(entry.targetY, sorted[index - 1].labelY + minGap)
        }
        val last = sorted.last()
        if (last.labelY > maxY) {
            val shift = last.labelY - maxY
            sorted.forEach { it.labelY -= shift }
            if (sorted.first().labelY < minY) {
                val push = minY - sorted.first().labelY
                sorted.forEach { it.labelY += push }
            }
        }
    }

    private fun formatPercent(value: Double): String {
        val percent = ((value * 1000).toInt() / 10.0)
        return "${percent.toString().removeSuffix(".0")}%"
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun sp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value,
        resources.displayMetrics
    )

    private data class Segment(
        val slice: InsightsCategorySlice,
        val startAngle: Float,
        val sweep: Float,
        val color: Int,
        val active: Boolean
    )

    private data class Callout(
        val segment: Segment,
        val side: Side,
        val sx: Float,
        val sy: Float,
        val mx: Float,
        val my: Float,
        val targetY: Float,
        var labelY: Float = 0f
    )

    private enum class Side {
        LEFT,
        RIGHT
    }
}
