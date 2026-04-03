package com.example.account.ui.insights

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.example.account.R
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class InsightsTrendChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val axisTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        textSize = sp(9f)
    }
    private val labelTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(10f)
        isFakeBoldText = true
    }
    private val tooltipTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(9f)
    }
    private val tooltipValuePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(10f)
        isFakeBoldText = true
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val tooltipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val tooltipRect = RectF()

    private var buckets: List<InsightsTrendBucket> = emptyList()
    private var metric: InsightsMetric = InsightsMetric.EXPENSE
    private var granularity: InsightsGranularity = InsightsGranularity.MONTH
    private var selectedIndex: Int? = null
    private var numberLocale: Locale = Locale.US
    private var onSelectionChanged: ((Int?) -> Unit)? = null

    fun submitData(
        buckets: List<InsightsTrendBucket>,
        metric: InsightsMetric,
        granularity: InsightsGranularity,
        selectedIndex: Int?
    ) {
        this.buckets = buckets
        this.metric = metric
        this.granularity = granularity
        this.selectedIndex = selectedIndex?.takeIf { it in buckets.indices }
        invalidate()
    }

    fun setNumberLocale(locale: Locale) {
        numberLocale = locale
        invalidate()
    }

    fun setOnSelectionChangedListener(listener: ((Int?) -> Unit)?) {
        onSelectionChanged = listener
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (buckets.isEmpty()) return

        val colors = colors()
        axisTextPaint.color = colors.textVariant
        labelTextPaint.color = colors.textVariant
        tooltipTitlePaint.color = colors.tooltipTitle
        tooltipValuePaint.color = colors.tooltipValue
        axisPaint.color = colors.axis
        gridPaint.color = colors.grid
        tooltipPaint.color = colors.tooltipBg

        val values = buckets.map(::metricValue)
        val rawMin = values.minOrNull() ?: 0.0
        val rawMax = values.maxOrNull() ?: 0.0
        val isBalanceMetric = metric == InsightsMetric.BALANCE
        val maxValue = if (isBalanceMetric) {
            if (rawMax > 0.0) niceAxisMax(rawMax) else 0.0
        } else {
            niceAxisMax(max(1.0, rawMax))
        }
        val minValue = if (isBalanceMetric) {
            if (rawMin < 0.0) -niceAxisMax(abs(rawMin)) else 0.0
        } else {
            0.0
        }
        val axisTicks = when {
            !isBalanceMetric -> listOf(maxValue, maxValue / 2.0, 0.0)
            minValue < 0.0 && maxValue > 0.0 -> listOf(maxValue, 0.0, minValue)
            maxValue == 0.0 -> listOf(0.0, minValue / 2.0, minValue)
            else -> listOf(maxValue, maxValue / 2.0, 0.0)
        }
        val maxAxisLabelWidth = axisTicks.maxOfOrNull { axisTextPaint.measureText(axisMoney(it)) } ?: 0f
        val layout = buildLayout(maxAxisLabelWidth) ?: return
        val axisRange = max(1.0, maxValue - minValue)
        val zeroRatio = ((0.0 - minValue) / axisRange).toFloat()
        val zeroY = layout.chartBottom - (layout.chartHeight * zeroRatio)

        canvas.drawLine(layout.chartLeft, layout.chartTop, layout.chartLeft, layout.chartBottom, axisPaint)
        canvas.drawLine(layout.chartLeft, layout.chartBottom, layout.chartRight, layout.chartBottom, axisPaint)

        axisTicks.forEachIndexed { index, tick ->
            val ratio = ((tick - minValue) / axisRange).toFloat()
            val y = layout.chartBottom - (layout.chartHeight * ratio)
            if (y != layout.chartBottom) {
                gridPaint.color = if (isBalanceMetric && abs(tick) < 0.001) colors.axis else colors.grid
                canvas.drawLine(layout.chartLeft, y, layout.chartRight, y, gridPaint)
            }
            canvas.drawText(axisMoney(tick), layout.axisTextX, y + (axisTextPaint.textSize / 3f), axisTextPaint)
        }

        val selected = selectedIndex
        val barColor = when (metric) {
            InsightsMetric.EXPENSE -> colors.expense
            InsightsMetric.INCOME -> colors.income
            InsightsMetric.BALANCE -> colors.balance
        }
        val minVisibleHeight = layout.chartHeight * 0.06f
        val slotWidth = layout.chartWidth / buckets.size.toFloat()

        val xPositions = FloatArray(buckets.size) { index ->
            layout.chartLeft + slotWidth * (index + 0.5f)
        }
        fun yForValue(value: Double): Float {
            val ratio = ((value - minValue) / axisRange).toFloat()
            return layout.chartBottom - (layout.chartHeight * ratio)
        }

        val barWidth = when {
            buckets.size <= 1 -> slotWidth * 0.82f
            buckets.size <= 5 -> slotWidth * 0.74f
            buckets.size <= 12 -> slotWidth * 0.64f
            granularity == InsightsGranularity.YEAR -> min(slotWidth * 0.52f, dp(16f))
            else -> min(slotWidth * 0.45f, dp(10f))
        }
        buckets.forEachIndexed { index, bucket ->
            val value = metricValue(bucket)
            val absValue = abs(value)
            if (absValue <= 0.0) return@forEachIndexed

            val cx = xPositions[index]
            val heightPx = max(minVisibleHeight, ((absValue / axisRange) * layout.chartHeight).toFloat())
            val left = cx - (barWidth / 2f)
            val right = cx + (barWidth / 2f)
            val top: Float
            val bottom: Float
            if (metric == InsightsMetric.BALANCE) {
                if (value >= 0.0) {
                    top = zeroY - heightPx
                    bottom = zeroY
                } else {
                    top = zeroY
                    bottom = zeroY + heightPx
                }
            } else {
                top = layout.chartBottom - heightPx
                bottom = layout.chartBottom
            }
            val rect = RectF(left, top, right, bottom)
            if (selected == index) {
                selectionPaint.color = colors.selection
                canvas.drawRoundRect(
                    RectF(rect.left - dp(2f), rect.top - dp(2f), rect.right + dp(2f), rect.bottom + dp(2f)),
                    barWidth,
                    barWidth,
                    selectionPaint
                )
            }
            barPaint.color = barColor
            canvas.drawRoundRect(rect, barWidth, barWidth, barPaint)
        }

        val maxLabelWidth = buckets.maxOfOrNull { labelTextPaint.measureText(it.label) } ?: 0f
        val minLabelSpacing = maxLabelWidth + dp(8f)
        val dynamicStep = kotlin.math.ceil(minLabelSpacing / slotWidth).toInt().coerceAtLeast(1)
        val monthLabelIndices = run {
            val indices = linkedSetOf<Int>()
            var cursor = 0
            while (cursor <= buckets.lastIndex) {
                indices.add(cursor)
                cursor += dynamicStep
            }
            indices.add(buckets.lastIndex)
            indices
        }
        if (buckets.isNotEmpty()) {
            val edgeGap = dp(4f)
            val lastIndex = buckets.lastIndex
            val firstHalf = labelTextPaint.measureText(buckets.first().label) / 2f
            val firstCx = xPositions.first().coerceIn(
                layout.chartLeft + firstHalf + dp(1f),
                layout.chartRight - firstHalf - dp(6f)
            )
            val firstLeft = firstCx - firstHalf
            val firstRight = firstCx + firstHalf

            val lastHalf = labelTextPaint.measureText(buckets.last().label) / 2f
            val lastCx = xPositions.last().coerceIn(
                layout.chartLeft + lastHalf + dp(1f),
                layout.chartRight - lastHalf - dp(6f)
            )
            val lastLeft = lastCx - lastHalf
            val lastRight = lastCx + lastHalf

            labelTextPaint.color = if (selected == 0) barColor else colors.textVariant
            canvas.drawText(buckets.first().label, firstCx, layout.labelBaseline, labelTextPaint)
            var lastDrawnRight = firstRight

            monthLabelIndices.forEach { index ->
                if (index <= 0 || index >= lastIndex) return@forEach
                val bucket = buckets[index]
                val textHalfWidth = labelTextPaint.measureText(bucket.label) / 2f
                val cx = xPositions[index].coerceIn(
                    layout.chartLeft + textHalfWidth + dp(1f),
                    layout.chartRight - textHalfWidth - dp(6f)
                )
                val labelLeft = cx - textHalfWidth
                val labelRight = cx + textHalfWidth
                if (labelLeft <= lastDrawnRight + edgeGap) return@forEach
                if (labelRight >= lastLeft - edgeGap) return@forEach
                labelTextPaint.color = if (selected == index) barColor else colors.textVariant
                canvas.drawText(bucket.label, cx, layout.labelBaseline, labelTextPaint)
                lastDrawnRight = labelRight
            }

            if (lastIndex > 0) {
                labelTextPaint.color = if (selected == lastIndex) barColor else colors.textVariant
                canvas.drawText(buckets.last().label, lastCx, layout.labelBaseline, labelTextPaint)
            }
        }

        selected?.takeIf { it in buckets.indices }?.let { index ->
            val value = metricValue(buckets[index])
            if (abs(value) > 0.0) {
                val cx = layout.chartLeft + slotWidth * (index + 0.5f)
                val title = buckets[index].detailLabel
                val amount = money(value)
                val width = max(
                    tooltipTitlePaint.measureText(title),
                    tooltipValuePaint.measureText(amount)
                ) + dp(20f)
                val height = dp(34f)
                val top = max(dp(2f), layout.chartTop - dp(2f))
                tooltipRect.set(
                    cx - width / 2f,
                    top,
                    cx + width / 2f,
                    top + height
                )
                val shift = when {
                    tooltipRect.left < layout.chartLeft -> layout.chartLeft - tooltipRect.left
                    tooltipRect.right > layout.chartRight -> layout.chartRight - tooltipRect.right
                    else -> 0f
                }
                tooltipRect.offset(shift, 0f)
                canvas.drawRoundRect(tooltipRect, dp(12f), dp(12f), tooltipPaint)
                canvas.drawText(title, tooltipRect.centerX(), tooltipRect.top + dp(12f), tooltipTitlePaint)
                canvas.drawText(amount, tooltipRect.centerX(), tooltipRect.top + dp(25f), tooltipValuePaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (buckets.isEmpty()) return super.onTouchEvent(event)
        val layout = buildLayout(axisLabelWidthForCurrentData()) ?: return super.onTouchEvent(event)
        val slotWidth = layout.chartWidth / buckets.size.toFloat()
        val inChart = event.x in layout.chartLeft..layout.chartRight && event.y in layout.chartTop..layout.chartBottom
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                if (inChart) {
                    val index = (((event.x - layout.chartLeft) / slotWidth).toInt()).coerceIn(0, buckets.lastIndex)
                    if (selectedIndex != index) {
                        selectedIndex = index
                        onSelectionChanged?.invoke(index)
                        invalidate()
                    }
                    if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                    return true
                }
                if (selectedIndex != null) {
                    selectedIndex = null
                    onSelectionChanged?.invoke(null)
                    invalidate()
                    if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun axisLabelWidthForCurrentData(): Float {
        if (buckets.isEmpty()) return 0f
        val values = buckets.map(::metricValue)
        val rawMin = values.minOrNull() ?: 0.0
        val rawMax = values.maxOrNull() ?: 0.0
        val isBalanceMetric = metric == InsightsMetric.BALANCE
        val maxValue = if (isBalanceMetric) {
            if (rawMax > 0.0) niceAxisMax(rawMax) else 0.0
        } else {
            niceAxisMax(max(1.0, rawMax))
        }
        val minValue = if (isBalanceMetric) {
            if (rawMin < 0.0) -niceAxisMax(abs(rawMin)) else 0.0
        } else {
            0.0
        }
        val axisTicks = when {
            !isBalanceMetric -> listOf(maxValue, maxValue / 2.0, 0.0)
            minValue < 0.0 && maxValue > 0.0 -> listOf(maxValue, 0.0, minValue)
            maxValue == 0.0 -> listOf(0.0, minValue / 2.0, minValue)
            else -> listOf(maxValue, maxValue / 2.0, 0.0)
        }
        return axisTicks.maxOfOrNull { axisTextPaint.measureText(axisMoney(it)) } ?: 0f
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun metricValue(bucket: InsightsTrendBucket): Double {
        return when (metric) {
            InsightsMetric.EXPENSE -> bucket.expense
            InsightsMetric.INCOME -> bucket.income
            InsightsMetric.BALANCE -> bucket.income - bucket.expense
        }
    }

    private fun buildLayout(maxAxisLabelWidth: Float): Layout? {
        val frameInset = dp(1f)
        val frameLeft = paddingLeft + frameInset
        val frameRight = width - paddingRight - frameInset
        val axisInset = max(dp(8f), maxAxisLabelWidth + dp(4f))
        val chartLeft = frameLeft + axisInset
        val chartRight = frameRight - dp(14f)
        val chartTop = paddingTop + dp(8f)
        val chartBottom = height - paddingBottom - dp(26f)
        if (chartRight <= chartLeft || chartBottom <= chartTop) return null
        return Layout(
            frameLeft = frameLeft,
            frameRight = frameRight,
            axisTextX = chartLeft - dp(2f),
            chartLeft = chartLeft,
            chartRight = chartRight,
            chartTop = chartTop,
            chartBottom = chartBottom,
            chartWidth = chartRight - chartLeft,
            chartHeight = chartBottom - chartTop,
            labelBaseline = height - paddingBottom - dp(6f)
        )
    }

    private fun niceAxisMax(value: Double): Double {
        if (value <= 0.0) return 1.0
        val exponent = kotlin.math.floor(kotlin.math.log10(value)).toInt()
        val base = 10.0.pow(exponent)
        val normalized = value / base
        return when {
            normalized <= 1.0 -> 1.0 * base
            normalized <= 2.0 -> 2.0 * base
            normalized <= 5.0 -> 5.0 * base
            else -> 10.0 * base
        }
    }

    private fun axisMoney(value: Double): String {
        val absValue = abs(value)
        val text = when {
            absValue >= 1_000_000_000 -> "${kotlin.math.round(absValue / 1_000_000_000).toInt()}b"
            absValue >= 1_000_000 -> "${kotlin.math.round(absValue / 1_000_000).toInt()}m"
            absValue >= 1_000 -> "${kotlin.math.round(absValue / 1_000).toInt()}k"
            else -> kotlin.math.round(absValue).toInt().toString()
        }
        return if (value < 0.0) "-$text" else text
    }

    private fun money(value: Double): String {
        val symbols = DecimalFormatSymbols.getInstance(numberLocale)
        val sign = if (value < 0.0) "-" else ""
        return "$sign$CNY_SYMBOL${DecimalFormat("#,##0.00", symbols).format(abs(value))}"
    }

    companion object {
        private const val CNY_SYMBOL = "\uFFE5"
    }

    private fun colors(): Colors {
        return Colors(
            axis = ContextCompat.getColor(context, R.color.insights_surface_variant),
            grid = ContextCompat.getColor(context, R.color.insights_surface_container_highest),
            expense = ContextCompat.getColor(context, R.color.insights_red_400),
            income = ContextCompat.getColor(context, R.color.insights_green_500),
            balance = ContextCompat.getColor(context, R.color.insights_blue_500),
            selection = 0xB3FFFFFF.toInt(),
            textVariant = ContextCompat.getColor(context, R.color.insights_on_surface_variant),
            tooltipBg = ContextCompat.getColor(context, R.color.insights_on_surface),
            tooltipValue = ContextCompat.getColor(context, android.R.color.white),
            tooltipTitle = 0xD9FFFFFF.toInt()
        )
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun sp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value,
        resources.displayMetrics
    )

    private data class Layout(
        val frameLeft: Float,
        val frameRight: Float,
        val axisTextX: Float,
        val chartLeft: Float,
        val chartRight: Float,
        val chartTop: Float,
        val chartBottom: Float,
        val chartWidth: Float,
        val chartHeight: Float,
        val labelBaseline: Float
    )

    private data class Colors(
        val axis: Int,
        val grid: Int,
        val expense: Int,
        val income: Int,
        val balance: Int,
        val selection: Int,
        val textVariant: Int,
        val tooltipBg: Int,
        val tooltipValue: Int,
        val tooltipTitle: Int
    )
}

private fun Double.pow(exponent: Int): Double = Math.pow(this, exponent.toDouble())
