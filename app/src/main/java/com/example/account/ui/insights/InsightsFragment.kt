package com.example.account.ui.insights

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.example.account.R
import com.example.account.data.CategoryLocalizer
import com.example.account.data.LedgerCategory
import com.example.account.data.LedgerTransaction
import com.example.account.data.LedgerViewModel
import com.example.account.data.TransactionType
import com.example.account.databinding.FragmentInsightsBinding
import com.example.account.databinding.ItemInsightsCategoryBinding
import com.example.account.ui.period.PeriodPickerDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.math.RoundingMode
import java.util.Currency
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class InsightsFragment : Fragment() {

    private var _binding: FragmentInsightsBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: LedgerViewModel
    private var renderJob: Job? = null
    private var transactionIndex: TransactionIndex? = null
    private var categoryMap: Map<String, LedgerCategory> = emptyMap()
    private var budgetMap: Map<YearMonth, Double> = emptyMap()
    private var localeTag: String = "en"
    private var numberLocale: Locale = Locale.US
    private var text: InsightsText = insightsText("en")
    private var uiState = UiState(year = LocalDate.now().year, monthIndex = LocalDate.now().monthValue - 1)
    private val contentEnterInterpolator = FastOutSlowInInterpolator()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentInsightsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(
            requireActivity(),
            LedgerViewModel.Factory(requireActivity().application)
        )[LedgerViewModel::class.java]

        localeTag = normalizeInsightsLocaleTag(resources.configuration.locales[0]?.toLanguageTag())
        numberLocale = insightsNumberLocale(localeTag)
        text = insightsText(localeTag)
        val defaultGranularity = arguments?.getString(ARG_DEFAULT_GRANULARITY).orEmpty()
        val now = LocalDate.now()
        val resolvedYear = arguments?.getInt(ARG_DEFAULT_YEAR, now.year) ?: now.year
        val resolvedMonthIndex = arguments?.getInt(ARG_DEFAULT_MONTH_INDEX, now.monthValue - 1) ?: (now.monthValue - 1)
        uiState = UiState(
            year = resolvedYear,
            monthIndex = resolvedMonthIndex.coerceIn(0, 11),
            granularity = when (defaultGranularity.uppercase(Locale.ROOT)) {
                "ALL" -> InsightsGranularity.ALL
                "YEAR" -> InsightsGranularity.YEAR
                else -> InsightsGranularity.MONTH
            }
        )

        applyStaticText()
        applyStaticStyles()
        applyLedgerModeUi()
        bindListeners()
        bindPeriodPickerResult()
        stabilizeInitialToggleStyles()
        loadData()
    }

    override fun onDestroyView() {
        renderJob?.cancel()
        renderJob = null
        _binding = null
        super.onDestroyView()
    }

    private fun stabilizeInitialToggleStyles() {
        updateToggleStyles()
        binding.insightsGranularityGroup.post {
            if (!isAdded || _binding == null) return@post
            updateToggleStyles()
        }
    }

    private fun loadData() {
        prepareContentEntrance()
        renderJob?.cancel()
        renderJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                val transactions = viewModel.getAllTransactions()
                val categories = viewModel.getAllCategories()
                val budgets = buildBudgetMap(transactions)
                InsightsData(
                    index = buildTransactionIndex(transactions),
                    categories = categories.associateBy { it.id },
                    budgets = budgets
                )
            }
            transactionIndex = result.index
            categoryMap = result.categories
            budgetMap = result.budgets
            render()
            animateContentEntrance()
        }
    }

    private fun prepareContentEntrance() {
        binding.insightsScroll.animate().cancel()
        binding.insightsScroll.alpha = 0f
        binding.insightsScroll.translationX = dp(16f)
        binding.insightsScroll.translationY = 0f
        binding.insightsScroll.visibility = View.INVISIBLE
    }

    private fun animateContentEntrance() {
        binding.insightsScroll.visibility = View.VISIBLE
        binding.insightsScroll.alpha = 0f
        binding.insightsScroll.translationX = dp(16f)
        binding.insightsScroll.translationY = 0f
        binding.insightsScroll.animate()
            .alpha(1f)
            .translationX(0f)
            .translationY(0f)
            .setDuration(320L)
            .setInterpolator(contentEnterInterpolator)
            .start()
    }

    private fun render() {
        val index = transactionIndex ?: return
        applyLedgerModeUi()
        val currentTransactions = currentScopeTransactions(index)
        val previousTransactions = if (uiState.granularity == InsightsGranularity.ALL) {
            emptyList()
        } else {
            getTransactionsForPeriod(index, -1)
        }
        val currentSummary = summarize(currentTransactions)
        val previousSummary = summarize(previousTransactions)
        val buckets = buildBuckets(currentTransactions)
        if (uiState.selectedBarIndex != null && uiState.selectedBarIndex !in buckets.indices) {
            uiState = uiState.copy(selectedBarIndex = null)
        }
        renderSummary(currentSummary, previousSummary)
        renderMetrics(currentSummary, currentTransactions, previousTransactions)
        renderTrend(buckets)
        renderCategories(currentTransactions)
        renderCalendar(currentTransactions)
        updateToggleStyles()
    }

    private fun applyStaticText() {
        binding.insightsPageTitle.text = text.insightsTitle
        binding.insightsGranularityMonth.text = text.month.uppercase(numberLocale)
        binding.insightsGranularityYear.text = text.year.uppercase(numberLocale)
        binding.insightsGranularityAll.text = text.all.uppercase(numberLocale)
        binding.insightsBalanceLabel.text = text.remainingBalance
        binding.insightsBalanceDeltaLabel.text = compareLabel()
        binding.insightsExpenseLabel.text = forceTwoLineLabel(text.totalExpense)
        binding.insightsIncomeLabel.text = forceTwoLineLabel(text.totalIncome)
        binding.insightsMetricsTitle.text = text.metrics
        binding.insightsMetricSavingsLabel.text = text.savingsRate
        binding.insightsMetricBudgetLabel.text = text.budgetForecast
        binding.insightsMetricExpenseActivityLabel.text = text.expenseActivity
        binding.insightsMetricIncomeActivityLabel.text = text.incomeActivity
        binding.insightsMetricAnomalyLabel.text = text.anomaly
        binding.insightsMetricSavingsSub.text = text.noIncomeData
        binding.insightsMetricBudgetSub.text = text.noBudgetSet
        binding.insightsMetricAnomalySub.text = text.noObviousAnomalies
        binding.insightsTrendTitle.text = text.trend
        binding.insightsTrendMetricExpense.text = text.expense
        binding.insightsTrendMetricIncome.text = text.income
        binding.insightsTrendMetricBalance.text = text.balance
        binding.insightsCategoryTitle.text = text.category
        binding.insightsCategoryTypeExpense.text = text.expense
        binding.insightsCategoryTypeIncome.text = text.income
        binding.insightsDonutLabel.text = text.total
        binding.insightsCategoryToggle.text = text.showMore
        binding.insightsCalendarTitle.text = text.calendar
        binding.insightsCalendarMetricExpense.text = text.expense
        binding.insightsCalendarMetricIncome.text = text.income
        binding.insightsCalendarMetricBalance.text = text.balance
    }

    private fun applyStaticStyles() {
        val white = color(R.color.insights_surface_container_lowest)
        val surfaceLow = color(R.color.insights_surface_container_low)
        val surfaceVariant = color(R.color.insights_surface_variant)
        val red50 = color(R.color.insights_red_50)
        val green50 = color(R.color.insights_green_50)
        val red400 = color(R.color.insights_red_400)
        val green500 = color(R.color.insights_green_500)

        background(binding.insightsBackButton, white, 14f, ColorUtils.setAlphaComponent(surfaceVariant, 51))
        background(binding.insightsGranularityGroup, Color.TRANSPARENT, 16f)
        background(binding.insightsPeriodButton, white, 16f)
        background(binding.insightsBalanceDeltaBadge, surfaceLow, 16f, ColorUtils.setAlphaComponent(surfaceVariant, 51))
        background(binding.insightsExpenseDeltaBadge, surfaceLow, 8f, ColorUtils.setAlphaComponent(surfaceVariant, 51))
        background(binding.insightsIncomeDeltaBadge, surfaceLow, 8f, ColorUtils.setAlphaComponent(surfaceVariant, 51))
        background(binding.insightsExpenseIconBox, red50, 6f)
        background(binding.insightsIncomeIconBox, green50, 6f)
        background(binding.insightsExpenseProgressTrack, red50, 999f)
        background(binding.insightsIncomeProgressTrack, green50, 999f)
        background(binding.insightsExpenseProgressTrack.getChildAt(0), ColorUtils.setAlphaComponent(red400, 51), 999f)
        background(binding.insightsIncomeProgressTrack.getChildAt(0), ColorUtils.setAlphaComponent(green500, 51), 999f)

        listOf(
            binding.insightsMetricSavingsCard,
            binding.insightsMetricBudgetCard,
            binding.insightsMetricExpenseActivityCard,
            binding.insightsMetricIncomeActivityCard,
            binding.insightsMetricAnomalyCard
        ).forEach { background(it, white, 16f, ColorUtils.setAlphaComponent(surfaceVariant, 64)) }

        listOf(
            binding.insightsTrendMetricGroup,
            binding.insightsCategoryTypeGroup,
            binding.insightsCalendarMetricGroup
        ).forEach { background(it, surfaceLow, 12f) }

        background(binding.insightsCategoryToggle, surfaceLow, 12f, ColorUtils.setAlphaComponent(surfaceVariant, 51))

        binding.insightsTrendChart.setNumberLocale(numberLocale)
    }

    private fun applyLedgerModeUi() {
        val isAll = uiState.granularity == InsightsGranularity.ALL
        binding.insightsBalanceDeltaBadge.isVisible = !isAll
        binding.insightsExpenseDeltaBadge.isVisible = !isAll
        binding.insightsIncomeDeltaBadge.isVisible = !isAll
        binding.insightsPeriodButton.isEnabled = !isAll
        binding.insightsPeriodButton.isClickable = !isAll
        binding.insightsPeriodIcon.isVisible = !isAll
        binding.insightsPeriodDisplay.setPaddingRelative(
            binding.insightsPeriodDisplay.paddingStart,
            binding.insightsPeriodDisplay.paddingTop,
            if (isAll) 0 else dpInt(24),
            binding.insightsPeriodDisplay.paddingBottom
        )
    }

    private fun bindListeners() {
        binding.insightsBackButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        binding.insightsGranularityMonth.setOnClickListener {
            uiState = uiState.copy(granularity = InsightsGranularity.MONTH)
            applyLedgerModeUi()
            render()
        }
        binding.insightsGranularityYear.setOnClickListener {
            uiState = uiState.copy(granularity = InsightsGranularity.YEAR)
            applyLedgerModeUi()
            render()
        }
        binding.insightsGranularityAll.setOnClickListener {
            uiState = uiState.copy(granularity = InsightsGranularity.ALL)
            applyLedgerModeUi()
            render()
        }
        binding.insightsPeriodButton.setOnClickListener {
            if (uiState.granularity == InsightsGranularity.ALL) return@setOnClickListener
            openPeriodPicker()
        }
        binding.insightsTrendMetricExpense.setOnClickListener {
            uiState = uiState.copy(trendMetric = InsightsMetric.EXPENSE)
            render()
        }
        binding.insightsTrendMetricIncome.setOnClickListener {
            uiState = uiState.copy(trendMetric = InsightsMetric.INCOME)
            render()
        }
        binding.insightsTrendMetricBalance.setOnClickListener {
            uiState = uiState.copy(trendMetric = InsightsMetric.BALANCE)
            render()
        }
        binding.insightsCategoryTypeExpense.setOnClickListener {
            uiState = uiState.copy(categoryMode = InsightsCategoryMode.EXPENSE, selectedCategoryId = null, categoryExpanded = false)
            render()
        }
        binding.insightsCategoryTypeIncome.setOnClickListener {
            uiState = uiState.copy(categoryMode = InsightsCategoryMode.INCOME, selectedCategoryId = null, categoryExpanded = false)
            render()
        }
        binding.insightsCategoryToggle.setOnClickListener {
            uiState = uiState.copy(categoryExpanded = !uiState.categoryExpanded)
            transactionIndex?.let { renderCategories(currentScopeTransactions(it)) }
        }
        binding.insightsCalendarMetricExpense.setOnClickListener {
            if (uiState.calendarMetric == InsightsMetric.EXPENSE) return@setOnClickListener
            uiState = uiState.copy(calendarMetric = InsightsMetric.EXPENSE)
            renderCalendarSection()
            updateToggleStyles()
        }
        binding.insightsCalendarMetricIncome.setOnClickListener {
            if (uiState.calendarMetric == InsightsMetric.INCOME) return@setOnClickListener
            uiState = uiState.copy(calendarMetric = InsightsMetric.INCOME)
            renderCalendarSection()
            updateToggleStyles()
        }
        binding.insightsCalendarMetricBalance.setOnClickListener {
            if (uiState.calendarMetric == InsightsMetric.BALANCE) return@setOnClickListener
            uiState = uiState.copy(calendarMetric = InsightsMetric.BALANCE)
            renderCalendarSection()
            updateToggleStyles()
        }
        binding.insightsTrendChart.setOnSelectionChangedListener { selected ->
            uiState = uiState.copy(selectedBarIndex = selected)
        }
        binding.insightsDonutChart.setOnSelectionChangedListener { selected ->
            uiState = uiState.copy(selectedCategoryId = selected)
            transactionIndex?.let { renderCategories(currentScopeTransactions(it)) }
        }
        binding.insightsScroll.setOnTouchListener { _, event ->
            if (
                event.actionMasked == MotionEvent.ACTION_DOWN &&
                uiState.selectedCategoryId != null &&
                !isTouchInsideView(binding.insightsDonutChart, event.rawX, event.rawY)
            ) {
                clearCategorySelection()
            }
            false
        }
    }

    private fun clearCategorySelection() {
        if (uiState.selectedCategoryId == null) return
        uiState = uiState.copy(selectedCategoryId = null)
        transactionIndex?.let { renderCategories(currentScopeTransactions(it)) }
    }

    private fun isTouchInsideView(target: View, rawX: Float, rawY: Float): Boolean {
        val location = IntArray(2)
        target.getLocationOnScreen(location)
        val left = location[0].toFloat()
        val top = location[1].toFloat()
        val right = left + target.width
        val bottom = top + target.height
        return rawX in left..right && rawY in top..bottom
    }

    private fun bindPeriodPickerResult() {
        parentFragmentManager.setFragmentResultListener(
            INSIGHTS_PERIOD_PICKER_REQUEST_KEY,
            viewLifecycleOwner
        ) { _, bundle ->
            val mode = bundle.getString(PeriodPickerDialogFragment.RESULT_MODE)
            val year = bundle.getInt(PeriodPickerDialogFragment.RESULT_YEAR, uiState.year)
            val monthIndex = bundle.getInt(PeriodPickerDialogFragment.RESULT_MONTH_INDEX, uiState.monthIndex)
            when (mode) {
                PeriodPickerDialogFragment.MODE_YEAR -> {
                    if (year == uiState.year) return@setFragmentResultListener
                    uiState = uiState.copy(year = year)
                }
                else -> {
                    if (year == uiState.year && monthIndex == uiState.monthIndex) return@setFragmentResultListener
                    uiState = uiState.copy(year = year, monthIndex = monthIndex)
                }
            }
            render()
        }
    }
    private fun renderSummary(current: Summary, previous: Summary) {
        binding.insightsPeriodDisplay.text = currentPeriodLabel()
        binding.insightsRemainingBalance.text = richMoney(current.balance)
        binding.insightsExpenseAmount.text = money(current.expense)
        binding.insightsIncomeAmount.text = money(current.income)

        if (uiState.granularity != InsightsGranularity.ALL) {
            val expenseDelta = calculateDelta(current.expense, previous.expense)
            val incomeDelta = calculateDelta(current.income, previous.income)
            val balanceDelta = calculateDelta(current.balance, previous.balance)
            binding.insightsBalanceDeltaLabel.text = compareLabel()
            updateDeltaBadge(
                icon = binding.insightsBalanceDeltaIcon,
                percent = binding.insightsBalanceDeltaPercent,
                delta = balanceDelta,
                positiveColor = color(R.color.insights_green_600),
                negativeColor = color(R.color.insights_error_container),
                widthPx = dpInt(34)
            )
            updateDeltaBadge(
                icon = binding.insightsExpenseDeltaIcon,
                percent = binding.insightsExpenseDeltaPercent,
                delta = expenseDelta,
                positiveColor = color(R.color.insights_error_container),
                negativeColor = color(R.color.insights_green_600),
                widthPx = dpInt(20),
                fixedTwoDigitPercentWidth = true
            )
            updateDeltaBadge(
                icon = binding.insightsIncomeDeltaIcon,
                percent = binding.insightsIncomeDeltaPercent,
                delta = incomeDelta,
                positiveColor = color(R.color.insights_green_600),
                negativeColor = color(R.color.insights_error_container),
                widthPx = dpInt(20),
                fixedTwoDigitPercentWidth = true
            )
        }
    }

    private fun calculateDelta(currentValue: Double, previousValue: Double): Double {
        return when {
            previousValue <= 0.0 && currentValue > 0.0 -> 100.0
            previousValue <= 0.0 -> 0.0
            else -> ((currentValue - previousValue) / previousValue) * 100.0
        }
    }

    private fun updateDeltaBadge(
        icon: TextView,
        percent: TextView,
        delta: Double,
        positiveColor: Int,
        negativeColor: Int,
        widthPx: Int,
        fixedTwoDigitPercentWidth: Boolean = false
    ) {
        val rising = delta >= 0.0
        icon.text = if (rising) "trending_up" else "trending_down"
        icon.setTextColor(if (rising) positiveColor else negativeColor)
        val textValue = formatDeltaBadgeValue(delta)
        val minWidth = if (fixedTwoDigitPercentWidth) {
            twoDigitPercentWidthPx(percent)
        } else {
            widthPx
        }
        val targetWidth = max(minWidth, badgeTextWidthPx(percent, textValue))
        percent.text = textValue
        if (fixedTwoDigitPercentWidth) {
            percent.updateLayoutParams<LinearLayout.LayoutParams> {
                width = targetWidth
            }
        } else {
            percent.updateLayoutParams<LinearLayout.LayoutParams> {
                width = ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }
        percent.maxWidth = targetWidth
        fitPercentTextSize(percent, textValue, targetWidth)
    }

    private fun twoDigitPercentWidthPx(percent: TextView): Int {
        percent.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
        return badgeTextWidthPx(percent, "50%")
    }

    private fun badgeTextWidthPx(percent: TextView, textValue: String): Int {
        percent.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
        return ceil(percent.paint.measureText(textValue).toDouble()).toInt() + dpInt(2)
    }

    private fun fitPercentTextSize(percent: TextView, textValue: String, targetWidth: Int) {
        var sizeSp = 9f
        while (sizeSp >= 5f) {
            percent.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            if (percent.paint.measureText(textValue) <= targetWidth) {
                return
            }
            sizeSp -= 0.5f
        }
    }
    private fun renderMetrics(
        currentSummary: Summary,
        currentTransactions: List<IndexedTransaction>,
        previousTransactions: List<IndexedTransaction>
    ) {
        val savingsRate = when {
            currentSummary.income > 0.0 -> (currentSummary.balance / currentSummary.income) * 100.0
            currentSummary.expense > 0.0 -> -100.0
            else -> null
        }
        binding.insightsMetricSavingsValue.text = savingsRate?.let { formatPercentValue(it) } ?: "--"
        binding.insightsMetricSavingsSub.text = text.format(text.netBalance, mapOf("amount" to compactMoney(currentSummary.balance, true)))

        val expenseTransactions = currentTransactions.filter { it.source.type == TransactionType.EXPENSE }
        val incomeTransactions = currentTransactions.filter { it.source.type == TransactionType.INCOME }
        val expenseDays = expenseTransactions.map { it.dayKey }.toSet().size
        val incomeDays = incomeTransactions.map { it.dayKey }.toSet().size
        val progress = getPeriodProgress()

        binding.insightsMetricExpenseActivityValue.text = text.format(text.txCount, mapOf("count" to expenseTransactions.size))
        binding.insightsMetricExpenseActivitySub.text = listOf(
            text.format(text.avgTicket, mapOf("amount" to compactMoney(currentSummary.expense / max(1, expenseTransactions.size)))),
            if (uiState.granularity == InsightsGranularity.YEAR) {
                text.format(text.monthlyAvg, mapOf("amount" to compactMoney(currentSummary.expense / max(1, progress.elapsedUnits))))
            } else {
                text.format(text.dailyAvg, mapOf("amount" to compactMoney(currentSummary.expense / max(1, progress.elapsedUnits))))
            },
            text.format(text.activeDays, mapOf("days" to expenseDays))
        ).joinToString("\n")

        binding.insightsMetricIncomeActivityValue.text = text.format(text.txCount, mapOf("count" to incomeTransactions.size))
        binding.insightsMetricIncomeActivitySub.text = listOf(
            text.format(text.avgTicket, mapOf("amount" to compactMoney(currentSummary.income / max(1, incomeTransactions.size)))),
            if (uiState.granularity == InsightsGranularity.YEAR) {
                text.format(text.monthlyAvg, mapOf("amount" to compactMoney(currentSummary.income / max(1, progress.elapsedUnits))))
            } else {
                text.format(text.dailyAvg, mapOf("amount" to compactMoney(currentSummary.income / max(1, progress.elapsedUnits))))
            },
            text.format(text.activeDays, mapOf("days" to incomeDays))
        ).joinToString("\n")

        val budgetCap = selectedBudgetCap()
        if (budgetCap == null) {
            binding.insightsMetricBudgetValue.text = "--"
            binding.insightsMetricBudgetSub.text = text.budgetNotSet
        } else {
            val projectedExpense = currentSummary.expense * (progress.totalUnits.toDouble() / max(1, progress.elapsedUnits))
            val forecastGap = budgetCap - projectedExpense
            val lines = mutableListOf(
                if (forecastGap >= 0.0) {
                    text.format(text.forecastLeft, mapOf("amount" to compactMoney(forecastGap)))
                } else {
                    text.format(text.forecastOver, mapOf("amount" to compactMoney(abs(forecastGap))))
                }
            )
            estimateBudgetOverrunMarker(currentSummary, budgetCap, progress)?.let(lines::add)
            lines += text.format(text.budget, mapOf("amount" to compactMoney(budgetCap)))
            binding.insightsMetricBudgetValue.text = compactMoney(projectedExpense)
            binding.insightsMetricBudgetSub.text = lines.joinToString("\n")
        }

        val anomalies = detectExpenseAnomalies(currentTransactions, previousTransactions)
        binding.insightsMetricAnomalyValue.text = text.format(text.txCount, mapOf("count" to anomalies.size))
        binding.insightsMetricAnomalySub.text = if (anomalies.isEmpty()) {
            text.noObviousAnomalies
        } else {
            val top = anomalies.first()
            val uplift = if (top.baseline > 0.0) {
                text.format(text.aboveBaseline, mapOf("percent" to (((top.amount / top.baseline) - 1) * 100).roundToInt()))
            } else {
                text.highAmountAlert
            }
            "${top.categoryName} ${compactMoney(top.amount)}\n$uplift"
        }
    }
    private fun renderTrend(buckets: List<InsightsTrendBucket>) {
        binding.insightsTrendChart.submitData(
            buckets = buckets,
            metric = uiState.trendMetric,
            granularity = uiState.granularity,
            selectedIndex = uiState.selectedBarIndex
        )
    }
    private fun renderCategories(currentTransactions: List<IndexedTransaction>) {
        val stats = buildCategoryStats(currentTransactions).ifEmpty {
            listOf(
                InsightsCategorySlice(
                    categoryId = "__empty__",
                    name = text.noData,
                    iconGlyph = "payments",
                    amount = 0.0,
                    ratio = 1.0,
                    accentColor = color(R.color.insights_red_400)
                )
            )
        }
        val selectedId = uiState.selectedCategoryId?.takeIf { id -> stats.any { it.categoryId == id } }
        if (selectedId != uiState.selectedCategoryId) {
            uiState = uiState.copy(selectedCategoryId = selectedId)
        }
        if (!uiState.categoryExpanded && selectedId != null) {
            val selectedIndex = stats.indexOfFirst { it.categoryId == selectedId }
            if (selectedIndex >= CATEGORY_VISIBLE_LIMIT) {
                uiState = uiState.copy(categoryExpanded = true)
            }
        }

        val total = stats.sumOf { it.amount }
        val activeCategory = stats.firstOrNull { it.categoryId == selectedId }
        binding.insightsDonutLabel.text = activeCategory?.name ?: text.total
        binding.insightsDonutTotal.text = activeCategory?.let { money(it.amount) } ?: shortMoney(total)
        binding.insightsDonutChart.submitData(stats, selectedId)

        val visibleStats = if (stats.size > CATEGORY_VISIBLE_LIMIT && !uiState.categoryExpanded) {
            stats.take(CATEGORY_VISIBLE_LIMIT)
        } else {
            stats
        }
        binding.insightsCategoryItems.removeAllViews()
        visibleStats.forEachIndexed { index, slice ->
            val itemBinding = ItemInsightsCategoryBinding.inflate(layoutInflater, binding.insightsCategoryItems, false)
            itemBinding.categoryName.text = slice.name
            itemBinding.categoryAmount.text = money(slice.amount)
            itemBinding.categoryPercent.text = formatPercent(slice.ratio)
            itemBinding.categoryIcon.text = slice.iconGlyph.ifBlank { "payments" }
            itemBinding.categoryItemRoot.alpha = if (selectedId == null || selectedId == slice.categoryId) 1f else 0.85f

            background(itemBinding.categoryIconContainer, ColorUtils.setAlphaComponent(slice.accentColor, 0x24), 16f)
            itemBinding.categoryIcon.setTextColor(slice.accentColor)
            background(itemBinding.categoryProgressTrack, color(R.color.insights_surface_container_low), 999f)
            itemBinding.categoryProgressTrack.post {
                val width = max(dpInt(4), (itemBinding.categoryProgressTrack.width * max(0.04, slice.ratio)).roundToInt())
                itemBinding.categoryProgressFill.updateLayoutParams<FrameLayout.LayoutParams> {
                    this.width = width
                }
                background(itemBinding.categoryProgressFill, slice.accentColor, 999f)
            }
            if (index < visibleStats.lastIndex) {
                itemBinding.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    bottomMargin = dpInt(24)
                }
            }
            binding.insightsCategoryItems.addView(itemBinding.root)
        }

        if (stats.size > CATEGORY_VISIBLE_LIMIT) {
            binding.insightsCategoryToggle.isVisible = true
            binding.insightsCategoryToggle.text = if (uiState.categoryExpanded) {
                text.showLess
            } else {
                "${text.showMore} (${stats.size - CATEGORY_VISIBLE_LIMIT})"
            }
        } else {
            binding.insightsCategoryToggle.isVisible = false
        }
    }

    private fun buildCategoryStats(currentTransactions: List<IndexedTransaction>): List<InsightsCategorySlice> {
        val targetType = if (uiState.categoryMode == InsightsCategoryMode.INCOME) TransactionType.INCOME else TransactionType.EXPENSE
        val totals = linkedMapOf<String, Double>()
        currentTransactions.forEach { transaction ->
            if (transaction.source.type != targetType) return@forEach
            totals[transaction.source.categoryId] = (totals[transaction.source.categoryId] ?: 0.0) + transaction.source.amount
        }
        val total = totals.values.sum()
        return totals.entries.map { (categoryId, amount) ->
            val category = categoryMap[categoryId]
            InsightsCategorySlice(
                categoryId = categoryId,
                name = category?.let { CategoryLocalizer.nameForId(requireContext(), it.id, it.name) } ?: text.other,
                iconGlyph = category?.iconGlyph.orEmpty().ifBlank { "payments" },
                amount = amount,
                ratio = if (total > 0.0) amount / total else 0.0,
                accentColor = category?.accentColor ?: color(R.color.insights_red_400)
            )
        }.sortedByDescending { it.amount }
    }

    private fun renderCalendarSection() {
        val index = transactionIndex ?: return
        renderCalendar(currentScopeTransactions(index))
    }

    private fun renderCalendar(currentTransactions: List<IndexedTransaction>) {
        binding.insightsCalendarWeekdays.removeAllViews()
        binding.insightsCalendarGrid.removeAllViews()
        applyCalendarGridOuterDivider(false)
        if (uiState.granularity == InsightsGranularity.ALL) {
            renderAllCalendar(currentTransactions)
            return
        }
        if (uiState.granularity == InsightsGranularity.MONTH) {
            binding.insightsCalendarGrid.columnCount = 7
            binding.insightsCalendarWeekdays.isVisible = true
            text.weekdays.forEach { label ->
                binding.insightsCalendarWeekdays.addView(
                    TextView(requireContext()).apply {
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        gravity = android.view.Gravity.CENTER
                        textAlignment = View.TEXT_ALIGNMENT_CENTER
                        typeface = Typeface.create(ResourcesCompat.getFont(requireContext(), R.font.inter_family), 700, false)
                        includeFontPadding = false
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                        setTextColor(color(R.color.insights_on_surface_variant))
                        text = label
                        setPadding(0, dpInt(4), 0, dpInt(4))
                    }
                )
            }
            renderMonthCalendar(currentTransactions)
            return
        }
        binding.insightsCalendarWeekdays.isVisible = false
        binding.insightsCalendarGrid.columnCount = 3
        renderYearCalendar(currentTransactions)
    }

    private fun renderAllCalendar(currentTransactions: List<IndexedTransaction>) {
        if (currentTransactions.isEmpty()) {
            binding.insightsCalendarWeekdays.isVisible = false
            binding.insightsCalendarGrid.columnCount = 7
            return
        }
        val dates = currentTransactions.map {
            Instant.ofEpochMilli(it.source.timestampMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        }
        val firstDate = dates.minOrNull() ?: return
        val lastRecordedDate = dates.maxOrNull() ?: firstDate
        val totalYears = java.time.temporal.ChronoUnit.YEARS.between(firstDate, lastRecordedDate).toInt() + 1
        if (totalYears > 3) {
            binding.insightsCalendarWeekdays.isVisible = false
            binding.insightsCalendarGrid.columnCount = 3
            renderProjectYearCalendar(currentTransactions, firstDate, lastRecordedDate)
            return
        }
        val showMonthly = firstDate.plusMonths(3).isBefore(lastRecordedDate)

        if (showMonthly) {
            binding.insightsCalendarWeekdays.isVisible = false
            binding.insightsCalendarGrid.columnCount = 3
            renderProjectMonthlyCalendar(currentTransactions, firstDate, lastRecordedDate)
            return
        }

        binding.insightsCalendarGrid.columnCount = 7
        binding.insightsCalendarWeekdays.isVisible = true
        text.weekdays.forEach { label ->
            binding.insightsCalendarWeekdays.addView(
                TextView(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    gravity = android.view.Gravity.CENTER
                    textAlignment = View.TEXT_ALIGNMENT_CENTER
                    typeface = Typeface.create(ResourcesCompat.getFont(requireContext(), R.font.inter_family), 700, false)
                    includeFontPadding = false
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                    setTextColor(color(R.color.insights_on_surface_variant))
                    text = label
                    setPadding(0, dpInt(4), 0, dpInt(4))
                }
            )
        }

        val endDate = if (java.time.temporal.ChronoUnit.DAYS.between(firstDate, lastRecordedDate).toInt() + 1 < 30) {
            firstDate.plusDays(30)
        } else {
            lastRecordedDate
        }
        renderProjectDailyCalendar(currentTransactions, firstDate, endDate)
    }

    private fun renderProjectDailyCalendar(
        currentTransactions: List<IndexedTransaction>,
        startDate: LocalDate,
        endDate: LocalDate
    ) {
        val grouped = currentTransactions.groupBy {
            Instant.ofEpochMilli(it.source.timestampMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        }
        val dayCount = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate).toInt() + 1
        val statsByDate = (0 until dayCount).associate { offset ->
            val date = startDate.plusDays(offset.toLong())
            val bucket = grouped[date].orEmpty()
            date to ProjectCalendarStat(
                date = date,
                income = bucket.sumOf { if (it.source.type == TransactionType.INCOME) it.source.amount else 0.0 },
                expense = bucket.sumOf { if (it.source.type == TransactionType.EXPENSE) it.source.amount else 0.0 }
            )
        }
        val stats = statsByDate.values.toList()
        val values = stats.map { calendarMetricValue(it.income, it.expense) }
        val scale = ToneScale(
            maxValue = values.maxOfOrNull { abs(it) } ?: 0.0,
            positiveMax = values.filter { it > 0 }.maxOrNull() ?: 0.0,
            negativeMax = values.filter { it < 0 }.maxOfOrNull { abs(it) } ?: 0.0
        )
        var cursorMonth = YearMonth.from(startDate)
        val endMonth = YearMonth.from(endDate)
        while (!cursorMonth.isAfter(endMonth)) {
            binding.insightsCalendarGrid.addView(calendarMonthDivider(cursorMonth))

            val monthStart = maxOf(startDate, cursorMonth.atDay(1))
            val monthEnd = minOf(endDate, cursorMonth.atEndOfMonth())
            val monthDays = java.time.temporal.ChronoUnit.DAYS.between(monthStart, monthEnd).toInt() + 1
            val firstWeekday = (monthStart.dayOfWeek.value - 1).coerceAtLeast(0)

            repeat(firstWeekday) {
                binding.insightsCalendarGrid.addView(calendarEmptyCell())
            }
            repeat(monthDays) { dayOffset ->
                val date = monthStart.plusDays(dayOffset.toLong())
                val stat = statsByDate[date] ?: ProjectCalendarStat(date, 0.0, 0.0)
                binding.insightsCalendarGrid.addView(
                    calendarDayCell(
                        dayLabel = stat.date.dayOfMonth.toString(),
                        value = calendarMetricValue(stat.income, stat.expense),
                        scale = scale
                    )
                )
            }

            val totalCells = firstWeekday + monthDays
            val padded = ceil(totalCells / 7.0).toInt() * 7
            repeat(padded - totalCells) {
                binding.insightsCalendarGrid.addView(calendarEmptyCell())
            }

            cursorMonth = cursorMonth.plusMonths(1)
        }
    }

    private fun renderProjectMonthlyCalendar(
        currentTransactions: List<IndexedTransaction>,
        firstDate: LocalDate,
        lastDate: LocalDate
    ) {
        val startMonth = YearMonth.from(firstDate)
        val endMonth = YearMonth.from(lastDate)
        val monthCount = java.time.temporal.ChronoUnit.MONTHS.between(startMonth, endMonth).toInt() + 1
        val grouped = currentTransactions.groupBy {
            YearMonth.from(Instant.ofEpochMilli(it.source.timestampMillis).atZone(java.time.ZoneId.systemDefault()))
        }
        val stats = (0 until monthCount).map { offset ->
            val month = startMonth.plusMonths(offset.toLong())
            val bucket = grouped[month].orEmpty()
            ProjectMonthCalendarStat(
                month = month,
                income = bucket.sumOf { if (it.source.type == TransactionType.INCOME) it.source.amount else 0.0 },
                expense = bucket.sumOf { if (it.source.type == TransactionType.EXPENSE) it.source.amount else 0.0 }
            )
        }
        val values = stats.map { calendarMetricValue(it.income, it.expense) }
        val scale = ToneScale(
            maxValue = values.maxOfOrNull { abs(it) } ?: 0.0,
            positiveMax = values.filter { it > 0 }.maxOrNull() ?: 0.0,
            negativeMax = values.filter { it < 0 }.maxOfOrNull { abs(it) } ?: 0.0
        )
        stats.forEach { stat ->
            val label = if (localeTag == "en") {
                stat.month.format(DateTimeFormatter.ofPattern("MMM yy", numberLocale))
            } else {
                stat.month.format(DateTimeFormatter.ofPattern("yyyy/M", numberLocale))
            }
            binding.insightsCalendarGrid.addView(
                calendarMonthCell(
                    label = label,
                    value = calendarMetricValue(stat.income, stat.expense),
                    scale = scale
                )
            )
        }
    }

    private fun renderProjectYearCalendar(
        currentTransactions: List<IndexedTransaction>,
        firstDate: LocalDate,
        lastDate: LocalDate
    ) {
        val grouped = currentTransactions.groupBy {
            Instant.ofEpochMilli(it.source.timestampMillis).atZone(java.time.ZoneId.systemDefault()).year
        }
        val startYear = firstDate.year
        val endYear = lastDate.year
        val stats = (startYear..endYear).map { year ->
            val bucket = grouped[year].orEmpty()
            YearCalendarStat(
                year = year,
                income = bucket.sumOf { if (it.source.type == TransactionType.INCOME) it.source.amount else 0.0 },
                expense = bucket.sumOf { if (it.source.type == TransactionType.EXPENSE) it.source.amount else 0.0 }
            )
        }
        val values = stats.map { calendarMetricValue(it.income, it.expense) }
        val scale = ToneScale(
            maxValue = values.maxOfOrNull { abs(it) } ?: 0.0,
            positiveMax = values.filter { it > 0 }.maxOrNull() ?: 0.0,
            negativeMax = values.filter { it < 0 }.maxOfOrNull { abs(it) } ?: 0.0
        )
        stats.forEach { stat ->
            binding.insightsCalendarGrid.addView(
                calendarMonthCell(
                    label = stat.year.toString(),
                    value = calendarMetricValue(stat.income, stat.expense),
                    scale = scale
                )
            )
        }
    }

    private fun renderMonthCalendar(currentTransactions: List<IndexedTransaction>) {
        val stats = buildDailyCalendarStats(currentTransactions)
        val values = stats.map { calendarMetricValue(it.income, it.expense) }
        val scale = ToneScale(
            maxValue = values.maxOfOrNull { abs(it) } ?: 0.0,
            positiveMax = values.filter { it > 0 }.maxOrNull() ?: 0.0,
            negativeMax = values.filter { it < 0 }.maxOfOrNull { abs(it) } ?: 0.0
        )
        val firstWeekday = (LocalDate.of(uiState.year, uiState.monthIndex + 1, 1).dayOfWeek.value - 1).coerceAtLeast(0)
        repeat(firstWeekday) {
            binding.insightsCalendarGrid.addView(calendarEmptyCell())
        }
        stats.forEach { stat ->
            binding.insightsCalendarGrid.addView(calendarDayCell(stat.dayLabel.toString(), calendarMetricValue(stat.income, stat.expense), scale))
        }
        val totalCells = firstWeekday + stats.size
        val padded = ceil(totalCells / 7.0).toInt() * 7
        repeat(padded - totalCells) {
            binding.insightsCalendarGrid.addView(calendarEmptyCell())
        }
    }

    private fun renderYearCalendar(currentTransactions: List<IndexedTransaction>) {
        val stats = buildMonthlyCalendarStats(currentTransactions)
        val values = stats.map { calendarMetricValue(it.income, it.expense) }
        val scale = ToneScale(
            maxValue = values.maxOfOrNull { abs(it) } ?: 0.0,
            positiveMax = values.filter { it > 0 }.maxOrNull() ?: 0.0,
            negativeMax = values.filter { it < 0 }.maxOfOrNull { abs(it) } ?: 0.0
        )
        stats.forEach { stat ->
            binding.insightsCalendarGrid.addView(
                calendarMonthCell(
                    label = monthLabel(stat.monthIndex, uiState.year),
                    value = calendarMetricValue(stat.income, stat.expense),
                    scale = scale
                )
            )
        }
    }

    private fun calendarDayCell(
        dayLabel: String,
        value: Double,
        scale: ToneScale
    ): View {
        val tone = calendarTone(value, scale)
        return SquareLinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dpInt(1), dpInt(1), dpInt(1), dpInt(1))
            }
            setPadding(dpInt(4), dpInt(4), dpInt(4), dpInt(4))
            gravity = android.view.Gravity.TOP
            background(this, tone.fillColor, 6f, tone.strokeColor)
            addView(TextView(requireContext()).apply {
                text = dayLabel
                typeface = Typeface.create(ResourcesCompat.getFont(requireContext(), R.font.manrope_family), 800, false)
                includeFontPadding = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(tone.primaryTextColor)
            })
            addView(TextView(requireContext()).apply {
                text = formatCalendarAmount(value)
                typeface = Typeface.create(ResourcesCompat.getFont(requireContext(), R.font.inter_family), 700, false)
                includeFontPadding = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, calendarAmountSize(formatCalendarAmount(value), true))
                setTextColor(tone.primaryTextColor)
            })
        }
    }

    private fun calendarMonthDivider(month: YearMonth): View {
        val monthText = month.format(DateTimeFormatter.ofPattern("yyyy-MM", numberLocale))
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = GridLayout.LayoutParams().apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(0, 7)
                setMargins(dpInt(2), dpInt(8), dpInt(2), dpInt(4))
            }
            addView(View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, dpInt(1f), 1f)
                setBackgroundColor(color(R.color.insights_surface_variant))
            })
            addView(TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = dpInt(8)
                    marginEnd = dpInt(8)
                }
                text = monthText
                typeface = Typeface.create(ResourcesCompat.getFont(requireContext(), R.font.inter_family), 700, false)
                includeFontPadding = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(color(R.color.insights_on_surface_variant))
            })
            addView(View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, dpInt(1f), 1f)
                setBackgroundColor(color(R.color.insights_surface_variant))
            })
        }
    }

    private fun calendarMonthCell(label: String, value: Double, scale: ToneScale): View {
        val tone = calendarTone(value, scale)
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dpInt(3), dpInt(2), dpInt(3), dpInt(2))
            }
            minimumHeight = dpInt(58)
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dpInt(8), dpInt(8), dpInt(8), dpInt(8))
            background(this, tone.fillColor, 16f, tone.strokeColor)
            addView(TextView(requireContext()).apply {
                text = label
                typeface = Typeface.create(ResourcesCompat.getFont(requireContext(), R.font.manrope_family), 800, false)
                includeFontPadding = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(tone.primaryTextColor)
            })
            addView(TextView(requireContext()).apply {
                text = formatCalendarAmount(value)
                typeface = Typeface.create(ResourcesCompat.getFont(requireContext(), R.font.inter_family), 700, false)
                includeFontPadding = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, calendarAmountSize(formatCalendarAmount(value), false))
                setTextColor(tone.primaryTextColor)
            })
        }
    }

    private fun calendarEmptyCell(): View {
        return View(requireContext()).apply {
            layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                height = dpInt(36)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dpInt(1), dpInt(1), dpInt(1), dpInt(1))
            }
        }
    }
    private fun openPeriodPicker() {
        if (uiState.granularity == InsightsGranularity.ALL) {
            return
        }
        val dialog = if (uiState.granularity == InsightsGranularity.MONTH) {
            PeriodPickerDialogFragment.newMonthPicker(
                requestKey = INSIGHTS_PERIOD_PICKER_REQUEST_KEY,
                selectedYear = uiState.year,
                selectedMonthIndex = uiState.monthIndex,
                displayYear = uiState.year,
                localeTag = localeTag,
                monthWord = text.month,
                title = text.selectMonth,
                closeText = text.close
            )
        } else {
            PeriodPickerDialogFragment.newYearPicker(
                requestKey = INSIGHTS_PERIOD_PICKER_REQUEST_KEY,
                selectedYear = uiState.year,
                selectedMonthIndex = uiState.monthIndex,
                availableYears = transactionIndex?.years.orEmpty().ifEmpty { listOf(LocalDate.now().year) }.toIntArray(),
                title = text.selectYear,
                closeText = text.close
            )
        }
        dialog.show(parentFragmentManager, "insights_period_picker")
    }
    private fun buildTransactionIndex(transactions: List<LedgerTransaction>): TransactionIndex {
        val currentYear = LocalDate.now().year
        val indexed = transactions.map { transaction ->
            val date = Instant.ofEpochMilli(transaction.timestampMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            IndexedTransaction(
                source = transaction,
                year = date.year,
                monthIndex = date.monthValue - 1,
                day = date.dayOfMonth,
                dayKey = "${date.year}-${date.monthValue}-${date.dayOfMonth}"
            )
        }
        val byMonth = indexed.groupBy { MonthBucket(it.year, it.monthIndex) }
        val byYear = indexed.groupBy { it.year }
        val years = (indexed.map { it.year } + currentYear).distinct().sortedDescending()
        return TransactionIndex(indexed, byMonth, byYear, years)
    }

    private fun buildBudgetMap(transactions: List<LedgerTransaction>): Map<YearMonth, Double> {
        val years = transactions.map {
            Instant.ofEpochMilli(it.timestampMillis).atZone(java.time.ZoneId.systemDefault()).year
        }.ifEmpty { listOf(LocalDate.now().year) }
        val minYear = minOf(years.minOrNull() ?: LocalDate.now().year, LocalDate.now().year)
        val maxYear = maxOf(years.maxOrNull() ?: LocalDate.now().year, LocalDate.now().year)
        val budgets = mutableMapOf<YearMonth, Double>()
        for (year in minYear..maxYear) {
            for (month in 1..12) {
                val yearMonth = YearMonth.of(year, month)
                val value = viewModel.getMonthlyBudget(yearMonth)
                if (value != null && value > 0.0) {
                    budgets[yearMonth] = value
                }
            }
        }
        return budgets
    }

    private fun getTransactionsForPeriod(index: TransactionIndex, offset: Int): List<IndexedTransaction> {
        if (uiState.granularity == InsightsGranularity.ALL) {
            return index.transactions
        }
        return if (uiState.granularity == InsightsGranularity.MONTH) {
            val month = YearMonth.of(uiState.year, uiState.monthIndex + 1).plusMonths(offset.toLong())
            index.byMonth[MonthBucket(month.year, month.monthValue - 1)].orEmpty()
        } else {
            index.byYear[uiState.year + offset].orEmpty()
        }
    }

    private fun applyCalendarGridOuterDivider(enabled: Boolean) {
        if (!enabled) {
            binding.insightsCalendarGrid.background = null
            binding.insightsCalendarGrid.setPadding(0, 0, 0, 0)
            return
        }
        background(
            binding.insightsCalendarGrid,
            color(R.color.insights_surface_container_low),
            radiusDp = 10f,
            strokeColor = color(R.color.insights_surface_variant),
            strokeWidthDp = 1f
        )
        binding.insightsCalendarGrid.setPadding(dpInt(1), dpInt(1), dpInt(1), dpInt(1))
    }

    private fun currentScopeTransactions(index: TransactionIndex): List<IndexedTransaction> {
        return if (uiState.granularity == InsightsGranularity.ALL) {
            index.transactions
        } else {
            getTransactionsForPeriod(index, 0)
        }
    }

    private fun summarize(transactions: List<IndexedTransaction>): Summary {
        var income = 0.0
        var expense = 0.0
        transactions.forEach { transaction ->
            if (transaction.source.type == TransactionType.INCOME) income += transaction.source.amount else expense += transaction.source.amount
        }
        return Summary(income, expense)
    }

    private fun buildBuckets(transactions: List<IndexedTransaction>): List<InsightsTrendBucket> {
        if (uiState.granularity == InsightsGranularity.ALL) {
            if (transactions.isEmpty()) return emptyList()
            val groupedByDate = transactions.groupBy {
                Instant.ofEpochMilli(it.source.timestampMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            }
            val firstDate = groupedByDate.keys.minOrNull() ?: return emptyList()
            val lastDate = groupedByDate.keys.maxOrNull() ?: firstDate
            val totalDays = java.time.temporal.ChronoUnit.DAYS.between(firstDate, lastDate).toInt() + 1

            if (totalDays <= 30) {
                return (0 until totalDays).map { offset ->
                    val date = firstDate.plusDays(offset.toLong())
                    val bucket = groupedByDate[date].orEmpty()
                    InsightsTrendBucket(
                        label = date.dayOfMonth.toString(),
                        income = bucket.sumOf { if (it.source.type == TransactionType.INCOME) it.source.amount else 0.0 },
                        expense = bucket.sumOf { if (it.source.type == TransactionType.EXPENSE) it.source.amount else 0.0 }
                    )
                }
            }

            if (totalDays <= 365) {
                val weekLabelFormatter = if (localeTag == "en") {
                    DateTimeFormatter.ofPattern("MM/dd", numberLocale)
                } else {
                    DateTimeFormatter.ofPattern("M/d", numberLocale)
                }
                var weekStart = firstDate.minusDays((firstDate.dayOfWeek.value - 1).toLong())
                val buckets = mutableListOf<InsightsTrendBucket>()
                while (weekStart <= lastDate) {
                    val weekEnd = weekStart.plusDays(6)
                    val displayStart = maxOf(firstDate, weekStart)
                    val displayEnd = minOf(lastDate, weekEnd)
                    var income = 0.0
                    var expense = 0.0
                    var dateCursor = weekStart
                    while (!dateCursor.isAfter(weekEnd)) {
                        if (!dateCursor.isBefore(firstDate) && !dateCursor.isAfter(lastDate)) {
                            val bucket = groupedByDate[dateCursor].orEmpty()
                            income += bucket.sumOf { if (it.source.type == TransactionType.INCOME) it.source.amount else 0.0 }
                            expense += bucket.sumOf { if (it.source.type == TransactionType.EXPENSE) it.source.amount else 0.0 }
                        }
                        dateCursor = dateCursor.plusDays(1)
                    }
                    buckets += InsightsTrendBucket(
                        label = weekStart.format(weekLabelFormatter),
                        detailLabel = "${displayStart.format(weekLabelFormatter)}-${displayEnd.format(weekLabelFormatter)}",
                        income = income,
                        expense = expense
                    )
                    weekStart = weekStart.plusDays(7)
                }
                return buckets
            }

            val totalYears = java.time.temporal.ChronoUnit.YEARS.between(firstDate, lastDate).toInt() + 1
            if (totalYears > 3) {
                val groupedByYear = transactions.groupBy {
                    Instant.ofEpochMilli(it.source.timestampMillis).atZone(java.time.ZoneId.systemDefault()).year
                }
                val startYear = firstDate.year
                val endYear = lastDate.year
                return (startYear..endYear).map { year ->
                    val bucket = groupedByYear[year].orEmpty()
                    InsightsTrendBucket(
                        label = year.toString(),
                        income = bucket.sumOf { if (it.source.type == TransactionType.INCOME) it.source.amount else 0.0 },
                        expense = bucket.sumOf { if (it.source.type == TransactionType.EXPENSE) it.source.amount else 0.0 }
                    )
                }
            } else {
                val groupedByMonth = transactions.groupBy {
                    YearMonth.from(Instant.ofEpochMilli(it.source.timestampMillis).atZone(java.time.ZoneId.systemDefault()))
                }
                val startMonth = YearMonth.from(firstDate)
                val endMonth = YearMonth.from(lastDate)
                val monthCount = java.time.temporal.ChronoUnit.MONTHS.between(startMonth, endMonth).toInt() + 1
                return (0 until monthCount).map { offset ->
                    val month = startMonth.plusMonths(offset.toLong())
                    val bucket = groupedByMonth[month].orEmpty()
                    val label = if (localeTag == "en") {
                        month.format(DateTimeFormatter.ofPattern("MMM yy", numberLocale))
                    } else {
                        month.format(DateTimeFormatter.ofPattern("yyyy/M", numberLocale))
                    }
                    InsightsTrendBucket(
                        label = label,
                        income = bucket.sumOf { if (it.source.type == TransactionType.INCOME) it.source.amount else 0.0 },
                        expense = bucket.sumOf { if (it.source.type == TransactionType.EXPENSE) it.source.amount else 0.0 }
                    )
                }
            }
        }
        return if (uiState.granularity == InsightsGranularity.YEAR) {
            MutableList(12) { monthIndex ->
                InsightsTrendBucket(
                    label = monthLabel(monthIndex, uiState.year),
                    income = 0.0,
                    expense = 0.0
                )
            }.also { buckets ->
                transactions.forEach { transaction ->
                    val current = buckets[transaction.monthIndex]
                    buckets[transaction.monthIndex] = current.copy(
                        income = current.income + if (transaction.source.type == TransactionType.INCOME) transaction.source.amount else 0.0,
                        expense = current.expense + if (transaction.source.type == TransactionType.EXPENSE) transaction.source.amount else 0.0
                    )
                }
            }
        } else {
            val days = YearMonth.of(uiState.year, uiState.monthIndex + 1).lengthOfMonth()
            MutableList(days) { day ->
                InsightsTrendBucket(
                    label = (day + 1).toString(),
                    income = 0.0,
                    expense = 0.0
                )
            }.also { buckets ->
                transactions.forEach { transaction ->
                    val index = transaction.day - 1
                    val current = buckets[index]
                    buckets[index] = current.copy(
                        income = current.income + if (transaction.source.type == TransactionType.INCOME) transaction.source.amount else 0.0,
                        expense = current.expense + if (transaction.source.type == TransactionType.EXPENSE) transaction.source.amount else 0.0
                    )
                }
            }
        }
    }

    private fun buildDailyCalendarStats(transactions: List<IndexedTransaction>): List<CalendarStat> {
        val days = YearMonth.of(uiState.year, uiState.monthIndex + 1).lengthOfMonth()
        val stats = MutableList(days) { day -> CalendarStat(day + 1, 0, 0.0, 0.0) }
        transactions.forEach { transaction ->
            val index = transaction.day - 1
            val current = stats[index]
            stats[index] = current.copy(
                income = current.income + if (transaction.source.type == TransactionType.INCOME) transaction.source.amount else 0.0,
                expense = current.expense + if (transaction.source.type == TransactionType.EXPENSE) transaction.source.amount else 0.0
            )
        }
        return stats
    }

    private fun buildMonthlyCalendarStats(transactions: List<IndexedTransaction>): List<CalendarStat> {
        val stats = MutableList(12) { monthIndex -> CalendarStat(0, monthIndex, 0.0, 0.0) }
        transactions.forEach { transaction ->
            val current = stats[transaction.monthIndex]
            stats[transaction.monthIndex] = current.copy(
                income = current.income + if (transaction.source.type == TransactionType.INCOME) transaction.source.amount else 0.0,
                expense = current.expense + if (transaction.source.type == TransactionType.EXPENSE) transaction.source.amount else 0.0
            )
        }
        return stats
    }

    private fun selectedBudgetCap(): Double? {
        if (uiState.granularity == InsightsGranularity.ALL) {
            return viewModel.getTotalBudget()
        }
        return if (uiState.granularity == InsightsGranularity.MONTH) {
            budgetMap[YearMonth.of(uiState.year, uiState.monthIndex + 1)]
        } else {
            viewModel.getYearlyBudget(uiState.year)
        }
    }

    private fun getPeriodProgress(): PeriodProgress {
        if (uiState.granularity == InsightsGranularity.ALL) {
            val dates = transactionIndex?.transactions.orEmpty().map {
                Instant.ofEpochMilli(it.source.timestampMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            }
            if (dates.isEmpty()) {
                return PeriodProgress(totalUnits = 1, elapsedUnits = 1, isCurrentPeriod = false)
            }
            val firstDate = dates.minOrNull() ?: LocalDate.now()
            val lastDate = dates.maxOrNull() ?: firstDate
            val totalDays = java.time.temporal.ChronoUnit.DAYS.between(firstDate, lastDate).toInt() + 1
            return PeriodProgress(totalUnits = totalDays, elapsedUnits = totalDays, isCurrentPeriod = false)
        }
        val now = LocalDate.now()
        return if (uiState.granularity == InsightsGranularity.MONTH) {
            val currentPeriod = now.year == uiState.year && now.monthValue == uiState.monthIndex + 1
            val total = YearMonth.of(uiState.year, uiState.monthIndex + 1).lengthOfMonth()
            PeriodProgress(total, if (currentPeriod) now.dayOfMonth else total, currentPeriod)
        } else {
            val currentPeriod = now.year == uiState.year
            PeriodProgress(12, if (currentPeriod) now.monthValue else 12, currentPeriod)
        }
    }

    private fun estimateBudgetOverrunMarker(summary: Summary, budgetCap: Double, progress: PeriodProgress): String? {
        if (!progress.isCurrentPeriod || progress.elapsedUnits <= 0 || budgetCap <= 0.0) return null
        val runRate = summary.expense / progress.elapsedUnits
        if (!runRate.isFinite() || runRate <= 0.0) return null
        val remaining = budgetCap - summary.expense
        if (remaining <= 0.0) {
            return if (uiState.granularity == InsightsGranularity.MONTH) text.alreadyOverBudget else text.alreadyOverAnnualBudget
        }
        val unitsToOverrun = ceil(remaining / runRate).toInt()
        val targetUnit = progress.elapsedUnits + unitsToOverrun
        if (targetUnit > progress.totalUnits) return null
        return if (uiState.granularity == InsightsGranularity.MONTH) {
            val targetDate = LocalDate.of(uiState.year, uiState.monthIndex + 1, targetUnit)
            text.format(text.overOn, mapOf("label" to targetDate.format(if (localeTag == "en") DateTimeFormatter.ofPattern("MMM d", numberLocale) else DateTimeFormatter.ofPattern("M/d", numberLocale))))
        } else {
            text.format(text.overIn, mapOf("label" to monthLabel(targetUnit - 1, uiState.year)))
        }
    }

    private fun detectExpenseAnomalies(
        currentTransactions: List<IndexedTransaction>,
        previousTransactions: List<IndexedTransaction>
    ): List<Anomaly> {
        val currentExpenses = currentTransactions.filter { it.source.type == TransactionType.EXPENSE }
        if (currentExpenses.isEmpty()) return emptyList()
        var baselineExpenses = previousTransactions.filter { it.source.type == TransactionType.EXPENSE }
        if (baselineExpenses.isEmpty()) {
            val startMillis = if (uiState.granularity == InsightsGranularity.ALL) {
                currentTransactions.minOfOrNull { it.source.timestampMillis } ?: 0L
            } else if (uiState.granularity == InsightsGranularity.MONTH) {
                LocalDate.of(uiState.year, uiState.monthIndex + 1, 1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            } else {
                LocalDate.of(uiState.year, 1, 1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            }
            baselineExpenses = transactionIndex?.transactions.orEmpty().filter {
                it.source.type == TransactionType.EXPENSE && it.source.timestampMillis < startMillis
            }
        }
        val fallbackSample = baselineExpenses.map { it.source.amount }.filter { it > 0.0 }
        val byCategory = baselineExpenses.groupBy { it.source.categoryId }
        return currentExpenses.mapNotNull { transaction ->
            val categorySample = byCategory[transaction.source.categoryId].orEmpty().map { it.source.amount }
            val sample = if (categorySample.size >= 3) categorySample else fallbackSample
            if (sample.isEmpty()) {
                if (transaction.source.amount < 500.0) return@mapNotNull null
                return@mapNotNull Anomaly(
                    categoryName = categoryName(transaction.source.categoryId),
                    amount = transaction.source.amount,
                    baseline = 0.0,
                    severity = transaction.source.amount / 500.0
                )
            }
            val mean = sample.average()
            val variance = sample.sumOf { (it - mean) * (it - mean) } / sample.size
            val deviation = kotlin.math.sqrt(variance)
            val threshold = max(mean * 1.8, max(mean + deviation * 2.0, mean + 40.0))
            if (transaction.source.amount < threshold) return@mapNotNull null
            Anomaly(
                categoryName = categoryName(transaction.source.categoryId),
                amount = transaction.source.amount,
                baseline = mean,
                severity = if (mean > 0.0) transaction.source.amount / mean else transaction.source.amount / threshold
            )
        }.sortedByDescending { it.severity }
    }

    private fun updateToggleStyles() {
        styleGranularityToggle(binding.insightsGranularityMonth, uiState.granularity == InsightsGranularity.MONTH)
        styleGranularityToggle(binding.insightsGranularityYear, uiState.granularity == InsightsGranularity.YEAR)
        styleGranularityToggle(binding.insightsGranularityAll, uiState.granularity == InsightsGranularity.ALL)
        styleSliderToggle(binding.insightsTrendMetricExpense, uiState.trendMetric == InsightsMetric.EXPENSE)
        styleSliderToggle(binding.insightsTrendMetricIncome, uiState.trendMetric == InsightsMetric.INCOME)
        styleSliderToggle(binding.insightsTrendMetricBalance, uiState.trendMetric == InsightsMetric.BALANCE)
        styleSliderToggle(binding.insightsCategoryTypeExpense, uiState.categoryMode == InsightsCategoryMode.EXPENSE)
        styleSliderToggle(binding.insightsCategoryTypeIncome, uiState.categoryMode == InsightsCategoryMode.INCOME)
        styleSliderToggle(binding.insightsCalendarMetricExpense, uiState.calendarMetric == InsightsMetric.EXPENSE)
        styleSliderToggle(binding.insightsCalendarMetricIncome, uiState.calendarMetric == InsightsMetric.INCOME)
        styleSliderToggle(binding.insightsCalendarMetricBalance, uiState.calendarMetric == InsightsMetric.BALANCE)
    }

    private fun styleGranularityToggle(button: AppCompatButton, active: Boolean) {
        val bg = if (active) color(R.color.insights_primary) else Color.TRANSPARENT
        background(button, bg, 16f)
        button.elevation = if (active) dp(2f) else 0f
        button.setTextColor(if (active) color(R.color.insights_on_primary) else color(R.color.insights_on_surface_variant))
        button.typeface = Typeface.create(ResourcesCompat.getFont(requireContext(), R.font.inter_family), if (active) 700 else 600, false)
        button.isAllCaps = false
    }

    private fun styleSliderToggle(button: AppCompatButton, active: Boolean) {
        val bg = if (active) color(R.color.insights_surface_container_lowest) else Color.TRANSPARENT
        val stroke = if (active) ColorUtils.setAlphaComponent(color(R.color.insights_surface_variant), 64) else Color.TRANSPARENT
        background(button, bg, 8f, stroke, if (active) 1f else 0f)
        button.elevation = 0f
        button.setTextColor(if (active) color(R.color.insights_on_surface) else color(R.color.insights_on_surface_variant))
        button.typeface = Typeface.create(ResourcesCompat.getFont(requireContext(), R.font.inter_family), if (active) 700 else 600, false)
        button.isAllCaps = false
    }

    private fun currentPeriodLabel(): String {
        if (uiState.granularity == InsightsGranularity.ALL) {
            val years = transactionIndex?.transactions.orEmpty().map { it.year }
            if (years.isEmpty()) {
                val year = LocalDate.now().year
                return "$year-$year"
            }
            return "${years.minOrNull() ?: LocalDate.now().year}-${years.maxOrNull() ?: LocalDate.now().year}"
        }
        return if (uiState.granularity == InsightsGranularity.MONTH) {
            val yearMonth = YearMonth.of(uiState.year, uiState.monthIndex + 1)
            if (localeTag == "en") {
                "${monthLabel(uiState.monthIndex, uiState.year)} ${uiState.year}"
            } else {
                yearMonth.format(DateTimeFormatter.ofPattern("yyyy/M", numberLocale))
            }
        } else {
            uiState.year.toString()
        }
    }

    private fun compareLabel(): String {
        if (uiState.granularity == InsightsGranularity.ALL) {
            return ""
        }
        return if (uiState.granularity == InsightsGranularity.MONTH) text.vsLastMonth else text.vsLastYear
    }

    private fun monthLabel(monthIndex: Int, year: Int): String {
        return if (localeTag == "en") {
            YearMonth.of(year, monthIndex + 1).month
                .getDisplayName(TextStyle.SHORT, numberLocale)
                .lowercase(numberLocale)
                .replaceFirstChar { first ->
                    if (first.isLowerCase()) first.titlecase(numberLocale) else first.toString()
                }
        } else {
            "${monthIndex + 1}${text.month}"
        }
    }

    private fun calendarMetricValue(income: Double, expense: Double): Double {
        return when (uiState.calendarMetric) {
            InsightsMetric.EXPENSE -> expense
            InsightsMetric.INCOME -> income
            InsightsMetric.BALANCE -> income - expense
        }
    }

    private fun calendarTone(value: Double, scale: ToneScale): CellTone {
        if (abs(value) <= 0.0) {
            return CellTone(color(R.color.insights_surface_container_low), Color.TRANSPARENT, color(R.color.insights_on_surface))
        }
        val reference = if (uiState.calendarMetric == InsightsMetric.BALANCE) {
            if (value >= 0) scale.positiveMax else scale.negativeMax
        } else {
            scale.maxValue
        }
        if (reference <= 0.0) {
            return CellTone(color(R.color.insights_surface_container_low), Color.TRANSPARENT, color(R.color.insights_on_surface))
        }
        val ratio = abs(value) / reference
        val depth = if (ratio > 2.0 / 3.0) 3 else if (ratio > 1.0 / 3.0) 2 else 1
        return when {
            uiState.calendarMetric == InsightsMetric.BALANCE && value < 0 || uiState.calendarMetric == InsightsMetric.EXPENSE -> when (depth) {
                1 -> CellTone(color(R.color.insights_red_100), color(R.color.insights_red_100), color(R.color.insights_brown_900))
                2 -> CellTone(color(R.color.insights_red_200), color(R.color.insights_red_200), color(R.color.insights_brown_900))
                else -> CellTone(color(R.color.insights_red_500), color(R.color.insights_red_500), Color.WHITE)
            }
            else -> when (depth) {
                1 -> CellTone(color(R.color.insights_green_100), color(R.color.insights_green_100), color(R.color.insights_green_900))
                2 -> CellTone(color(R.color.insights_green_200), color(R.color.insights_green_200), color(R.color.insights_green_900))
                else -> CellTone(color(R.color.insights_green_500), color(R.color.insights_green_500), Color.WHITE)
            }
        }
    }

    private fun formatCalendarAmount(value: Double): String {
        val absValue = abs(value)
        if (uiState.granularity == InsightsGranularity.MONTH && absValue >= 100_000) {
            val compact = if (absValue >= 1_000_000) (absValue / 1_000).roundToInt().toString() else (absValue / 1_000).formatTrimmed()
            return when (uiState.calendarMetric) {
                InsightsMetric.BALANCE -> when {
                    value > 0 -> "+${compact}k"
                    value < 0 -> "-${compact}k"
                    else -> "0"
                }
                else -> "${compact}k"
            }
        }
        val digits = absValue.formatTrimmed()
        return if (uiState.calendarMetric == InsightsMetric.BALANCE) {
            when {
                value > 0 -> "+$digits"
                value < 0 -> "-$digits"
                else -> "0"
            }
        } else {
            digits
        }
    }

    private fun calendarAmountSize(textValue: String, monthMode: Boolean): Float {
        val length = textValue.length
        return if (monthMode) {
            when {
                length >= 10 -> 6f
                length >= 8 -> 7f
                length >= 7 -> 8f
                else -> 9f
            }
        } else {
            when {
                length >= 10 -> 11f
                length >= 8 -> 12f
                length >= 7 -> 13f
                else -> 14f
            }
        }
    }

    private fun categoryName(categoryId: String): String {
        val category = categoryMap[categoryId] ?: return text.other
        return CategoryLocalizer.nameForId(requireContext(), category.id, category.name)
    }

    private fun forceTwoLineLabel(value: String): String {
        val normalized = value.trim().replace(Regex("\\s+"), " ")
        val splitIndex = normalized.indexOf(' ')
        if (splitIndex <= 0 || splitIndex >= normalized.lastIndex) return normalized
        val first = normalized.substring(0, splitIndex)
        val second = normalized.substring(splitIndex + 1)
        return "$first\n$second"
    }

    private fun money(value: Double): String {
        val symbols = DecimalFormatSymbols.getInstance(numberLocale)
        val sign = if (value < 0.0) "-" else ""
        return "$sign$CNY_SYMBOL${DecimalFormat("#,##0.00", symbols).format(abs(value))}"
    }

    private fun richMoney(value: Double): CharSequence {
        val sign = when {
            value > 0.0 -> "+"
            value < 0.0 -> "-"
            else -> ""
        }
        val absValue = abs(value)
        val parts = java.math.BigDecimal.valueOf(absValue)
            .setScale(2, RoundingMode.HALF_UP)
            .toPlainString()
            .split(".")
        val whole = parts[0].toLong().let { String.format(numberLocale, "%,d", it) }
        val decimals = parts.getOrElse(1) { "00" }
        val color = when {
            value > 0.0 -> color(R.color.insights_green_500)
            value < 0.0 -> color(R.color.insights_red_400)
            else -> color(R.color.insights_on_surface)
        }
        return SpannableStringBuilder().apply {
            append(sign)
            append(CNY_SYMBOL)
            append(whole)
            val decimalStart = length
            append(".")
            append(decimals)
            setSpan(ForegroundColorSpan(color), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(RelativeSizeSpan(0.6f), decimalStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun formatPercent(value: Double): String = "${((value * 1000).roundToInt() / 10.0).toString().removeSuffix(".0")}%"

    private fun formatPercentValue(value: Double): String = "${value.formatTrimmed()}%"

    private fun formatDeltaBadgeValue(delta: Double): String {
        val absDelta = abs(delta)
        return if (absDelta >= 1000.0) {
            "${(absDelta / 100.0).formatTrimmed()}x"
        } else {
            "${absDelta.roundToInt()}%"
        }
    }

    private fun shortMoney(value: Double): String {
        val absValue = abs(value)
        return when {
            absValue >= 1_000_000_000 -> "$CNY_SYMBOL${(absValue / 1_000_000_000).formatTrimmed()}b"
            absValue >= 1_000_000 -> "$CNY_SYMBOL${(absValue / 1_000_000).formatTrimmed()}m"
            absValue >= 1_000 -> "$CNY_SYMBOL${String.format(Locale.US, "%.1f", absValue / 1_000)}k"
            else -> money(absValue)
        }
    }

    private fun compactMoney(value: Double, alwaysShowSign: Boolean = false): String {
        val absValue = abs(value)
        val sign = when {
            value < 0.0 -> "-"
            alwaysShowSign && value > 0.0 -> "+"
            else -> ""
        }
        return when {
            absValue >= 1_000_000_000 -> "$sign$CNY_SYMBOL${if (absValue >= 10_000_000_000) (absValue / 1_000_000_000).roundToInt() else (absValue / 1_000_000_000).formatTrimmed()}b"
            absValue >= 1_000_000 -> "$sign$CNY_SYMBOL${if (absValue >= 10_000_000) (absValue / 1_000_000).roundToInt() else (absValue / 1_000_000).formatTrimmed()}m"
            absValue >= 1_000 -> "$sign$CNY_SYMBOL${if (absValue >= 10_000) (absValue / 1_000).roundToInt() else (absValue / 1_000).formatTrimmed()}k"
            absValue >= 100 -> "$sign$CNY_SYMBOL${absValue.roundToInt()}"
            else -> "$sign$CNY_SYMBOL${absValue.formatTrimmed()}"
        }
    }

    private fun background(view: View, fillColor: Int, radiusDp: Float, strokeColor: Int = Color.TRANSPARENT, strokeWidthDp: Float = 1f) {
        view.background = roundedDrawable(fillColor, radiusDp, strokeColor, strokeWidthDp)
    }

    private fun roundedDrawable(fillColor: Int, radiusDp: Float, strokeColor: Int = Color.TRANSPARENT, strokeWidthDp: Float = 1f): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp)
            setColor(fillColor)
            if (strokeColor != Color.TRANSPARENT) {
                setStroke(dpInt(strokeWidthDp), strokeColor)
            }
        }
    }

    private fun color(resId: Int): Int = ContextCompat.getColor(requireContext(), resId)

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun dpInt(value: Int): Int = dp(value.toFloat()).roundToInt()

    private fun dpInt(value: Float): Int = dp(value).roundToInt()

    private fun Double.formatTrimmed(): String {
        val rounded = if (this >= 1000) String.format(Locale.US, "%.0f", this) else String.format(Locale.US, "%.1f", this)
        return rounded.removeSuffix(".0")
    }

    private data class InsightsData(
        val index: TransactionIndex,
        val categories: Map<String, LedgerCategory>,
        val budgets: Map<YearMonth, Double>
    )

    private data class UiState(
        val granularity: InsightsGranularity = InsightsGranularity.MONTH,
        val year: Int,
        val monthIndex: Int,
        val trendMetric: InsightsMetric = InsightsMetric.EXPENSE,
        val categoryMode: InsightsCategoryMode = InsightsCategoryMode.EXPENSE,
        val calendarMetric: InsightsMetric = InsightsMetric.EXPENSE,
        val selectedBarIndex: Int? = null,
        val selectedCategoryId: String? = null,
        val categoryExpanded: Boolean = false
    )

    private data class TransactionIndex(
        val transactions: List<IndexedTransaction>,
        val byMonth: Map<MonthBucket, List<IndexedTransaction>>,
        val byYear: Map<Int, List<IndexedTransaction>>,
        val years: List<Int>
    )

    private data class IndexedTransaction(
        val source: LedgerTransaction,
        val year: Int,
        val monthIndex: Int,
        val day: Int,
        val dayKey: String
    )

    private data class MonthBucket(val year: Int, val monthIndex: Int)
    private data class Summary(val income: Double, val expense: Double) { val balance: Double get() = income - expense }
    private data class PeriodProgress(val totalUnits: Int, val elapsedUnits: Int, val isCurrentPeriod: Boolean)
    private data class Anomaly(val categoryName: String, val amount: Double, val baseline: Double, val severity: Double)
    private data class CalendarStat(val dayLabel: Int, val monthIndex: Int, val income: Double, val expense: Double)
    private data class ProjectCalendarStat(val date: LocalDate, val income: Double, val expense: Double)
    private data class ProjectMonthCalendarStat(val month: YearMonth, val income: Double, val expense: Double)
    private data class YearCalendarStat(val year: Int, val income: Double, val expense: Double)
    private data class ToneScale(val maxValue: Double, val positiveMax: Double, val negativeMax: Double)
    private data class CellTone(val fillColor: Int, val strokeColor: Int, val primaryTextColor: Int)

    companion object {
        private const val INSIGHTS_PERIOD_PICKER_REQUEST_KEY = "insights_period_picker_result"
        private const val CNY_SYMBOL = "\uFFE5"
        private const val CATEGORY_VISIBLE_LIMIT = 5
        private const val ARG_DEFAULT_GRANULARITY = "arg_default_granularity"
        private const val ARG_DEFAULT_YEAR = "arg_default_year"
        private const val ARG_DEFAULT_MONTH_INDEX = "arg_default_month_index"

        fun newInstance(
            defaultGranularity: String = "MONTH",
            defaultYear: Int? = null,
            defaultMonthIndex: Int? = null
        ): InsightsFragment {
            return InsightsFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_DEFAULT_GRANULARITY, defaultGranularity)
                    defaultYear?.let { putInt(ARG_DEFAULT_YEAR, it) }
                    defaultMonthIndex?.let { putInt(ARG_DEFAULT_MONTH_INDEX, it) }
                }
            }
        }
    }
}

