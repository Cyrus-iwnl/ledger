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
import com.example.account.data.amountInCny
import com.example.account.databinding.FragmentInsightsBinding
import com.example.account.databinding.ItemInsightRuleBinding
import com.example.account.databinding.ItemInsightsCategoryBinding
import com.example.account.ui.DialogFactory
import com.example.account.ui.period.PeriodPickerDialogFragment
import com.google.android.material.button.MaterialButton
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
        binding.insightsPageTitle.post {
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
                InsightsData(
                    index = buildTransactionIndex(transactions),
                    categories = categories.associateBy { it.id }
                )
            }
            transactionIndex = result.index
            categoryMap = result.categories
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
        renderSummary(currentSummary, previousSummary, currentTransactions)
        renderInsightsPanel(index, currentTransactions)
        renderTrend(buckets)
        renderCategories(currentTransactions)
        renderCalendar(currentTransactions)
        updateToggleStyles()
    }

    private fun applyStaticText() {
        binding.insightsPageTitle.text = currentPeriodLabel()
        binding.insightsBalanceLabel.text = text.remainingBalance
        binding.insightsBalanceDeltaLabel.text = compareLabel()
        binding.insightsExpenseLabel.text = forceTwoLineLabel(text.totalExpense)
        binding.insightsIncomeLabel.text = forceTwoLineLabel(text.totalIncome)
        binding.insightsBalanceSavingsRateLabel.text = text.savingsRate
        binding.insightsMetricsTitle.text = text.insightModuleTitle
        binding.insightsMetricsHelp.contentDescription = insightHelpButtonLabel()
        binding.insightsSuggestionTitle.text = text.insightSuggestionTitle
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
    }

    private fun bindListeners() {
        binding.insightsBackButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        binding.insightsPageTitle.setOnClickListener { openPeriodPicker() }
        binding.insightsMetricsHelp.setOnClickListener { showInsightHelpDialog() }
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
            val isAll = bundle.getBoolean(PeriodPickerDialogFragment.RESULT_IS_ALL, false)
            if (isAll) {
                if (uiState.granularity == InsightsGranularity.ALL) return@setFragmentResultListener
                uiState = uiState.copy(granularity = InsightsGranularity.ALL)
                applyLedgerModeUi()
                render()
                return@setFragmentResultListener
            }

            val mode = bundle.getString(PeriodPickerDialogFragment.RESULT_MODE, PeriodPickerDialogFragment.MODE_MONTH)
            if (mode == PeriodPickerDialogFragment.MODE_YEAR) {
                val year = bundle.getInt(PeriodPickerDialogFragment.RESULT_YEAR, uiState.year)
                if (uiState.granularity == InsightsGranularity.YEAR && uiState.year == year) {
                    return@setFragmentResultListener
                }
                uiState = uiState.copy(granularity = InsightsGranularity.YEAR, year = year)
                applyLedgerModeUi()
                render()
                return@setFragmentResultListener
            }

            val year = bundle.getInt(PeriodPickerDialogFragment.RESULT_YEAR, uiState.year)
            val monthIndex = bundle.getInt(PeriodPickerDialogFragment.RESULT_MONTH_INDEX, uiState.monthIndex)
            if (uiState.granularity == InsightsGranularity.MONTH && uiState.year == year && uiState.monthIndex == monthIndex) {
                return@setFragmentResultListener
            }
            uiState = uiState.copy(granularity = InsightsGranularity.MONTH, year = year, monthIndex = monthIndex)
            applyLedgerModeUi()
            render()
        }
    }

    private fun openPeriodPicker() {
        val selectedScopeMode = when (uiState.granularity) {
            InsightsGranularity.MONTH -> PeriodPickerDialogFragment.MODE_MONTH
            InsightsGranularity.YEAR -> PeriodPickerDialogFragment.MODE_YEAR
            InsightsGranularity.ALL -> PeriodPickerDialogFragment.MODE_ALL
        }
        val monthToggleText = if (localeTag == "en") "By ${text.month}" else "\u6309${text.month}"
        val yearToggleText = if (localeTag == "en") "By ${text.year}" else "\u6309${text.year}"
        val availableYears = transactionIndex?.years.orEmpty().ifEmpty { listOf(LocalDate.now().year) }.toIntArray()
        PeriodPickerDialogFragment
            .newMonthPicker(
                requestKey = INSIGHTS_PERIOD_PICKER_REQUEST_KEY,
                selectedYear = uiState.year,
                selectedMonthIndex = uiState.monthIndex,
                displayYear = uiState.year,
                localeTag = localeTag,
                monthWord = text.month,
                title = monthToggleText,
                closeText = text.close,
                enableAllToggle = true,
                selectedScopeMode = selectedScopeMode,
                allText = text.all,
                yearText = yearToggleText,
                availableYears = availableYears
            )
            .show(parentFragmentManager, "insights_period_picker")
    }

    private fun renderSummary(
        current: Summary,
        previous: Summary,
        currentTransactions: List<IndexedTransaction>
    ) {
        binding.insightsPageTitle.text = currentPeriodLabel()
        binding.insightsRemainingBalance.text = richMoney(current.balance)
        binding.insightsExpenseAmount.text = money(current.expense)
        binding.insightsIncomeAmount.text = money(current.income)
        val progress = getPeriodProgress()
        val periodAvgTemplate = if (uiState.granularity == InsightsGranularity.YEAR) text.monthlyAvg else text.dailyAvg
        val expenseCount = currentTransactions.count { it.source.type == TransactionType.EXPENSE }
        val incomeCount = currentTransactions.count { it.source.type == TransactionType.INCOME }
        val expenseAvgTicket = if (expenseCount > 0) compactMoney(current.expense / expenseCount) else "--"
        val incomeAvgTicket = if (incomeCount > 0) compactMoney(current.income / incomeCount) else "--"
        val expensePeriodAvg = if (expenseCount > 0) compactMoney(current.expense / max(1, progress.elapsedUnits)) else "--"
        val incomePeriodAvg = if (incomeCount > 0) compactMoney(current.income / max(1, progress.elapsedUnits)) else "--"
        val savingsRate = when {
            current.income > 0.0 -> (current.balance / current.income) * 100.0
            current.expense > 0.0 -> -100.0
            else -> null
        }

        binding.insightsExpenseAvgTicket.text = text.format(text.avgTicket, mapOf("amount" to expenseAvgTicket))
        binding.insightsIncomeAvgTicket.text = text.format(text.avgTicket, mapOf("amount" to incomeAvgTicket))
        binding.insightsExpensePeriodAvg.text = text.format(periodAvgTemplate, mapOf("amount" to expensePeriodAvg))
        binding.insightsIncomePeriodAvg.text = text.format(periodAvgTemplate, mapOf("amount" to incomePeriodAvg))
        binding.insightsBalanceSavingsRateValue.text = savingsRate?.let { formatPercentValue(it) } ?: "--"

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

    private fun renderInsightsPanel(
        index: TransactionIndex,
        currentTransactions: List<IndexedTransaction>
    ) {
        val month = YearMonth.of(uiState.year, uiState.monthIndex + 1)
        val context = InsightContext(
            granularity = uiState.granularity,
            year = uiState.year,
            monthIndex = uiState.monthIndex,
            now = LocalDate.now(),
            transactionsCurrent = currentTransactions.map { it.toRuleTransaction() },
            allTransactions = index.transactions.map { it.toRuleTransaction() },
            monthlyBudget = if (uiState.granularity == InsightsGranularity.MONTH) viewModel.getMonthlyBudget(month) else null,
            yearlyBudget = if (uiState.granularity == InsightsGranularity.YEAR) viewModel.getYearlyBudget(uiState.year) else null
        )
        val panel = InsightsRuleEngine.evaluate(context)
        val primaryCards = panel.primaryInsights.map { toInsightCard(it) }

        binding.insightsPrimaryInsightList.removeAllViews()
        binding.insightsSuggestionContainer.removeAllViews()
        binding.insightsSuggestionTitle.isVisible = false
        binding.insightsSuggestionContainer.isVisible = false

        if (panel.allScopeHint) {
            binding.insightsPrimaryInsightList.addView(
                createInsightCard(
                    card = InsightCard(
                        type = InsightType.SUMMARY,
                        typeLabel = "",
                        title = text.noData,
                        body = text.insightAllScopeHint,
                        keyNumber = "",
                        action = InsightAction.NONE,
                        actionLabel = "",
                        categoryId = null
                    ),
                    showTypeTag = false
                )
            )
            return
        }

        if (currentTransactions.isEmpty()) {
            binding.insightsPrimaryInsightList.addView(
                createInsightCard(
                    card = InsightCard(
                        type = InsightType.SUMMARY,
                        typeLabel = "",
                        title = text.noData,
                        body = emptyInsightsHintBody(),
                        keyNumber = "",
                        action = InsightAction.NONE,
                        actionLabel = "",
                        categoryId = null
                    ),
                    showTypeTag = false
                )
            )
            return
        }

        primaryCards.forEachIndexed { index, card ->
            val cardView = createInsightCard(card)
            cardView.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                if (index < primaryCards.lastIndex) {
                    bottomMargin = dpInt(8)
                }
            }
            binding.insightsPrimaryInsightList.addView(cardView)
        }

        if (primaryCards.isEmpty()) {
            binding.insightsPrimaryInsightList.addView(
                createInsightCard(
                    card = InsightCard(
                        type = InsightType.SUMMARY,
                        typeLabel = "",
                        title = text.noObviousAnomalies,
                        body = noRuleHitHintBody(),
                        keyNumber = "",
                        action = InsightAction.NONE,
                        actionLabel = "",
                        categoryId = null
                    ),
                    showTypeTag = false
                )
            )
        }
    }

    private fun emptyInsightsHintBody(): String {
        return when (localeTag) {
            "zh-CN" -> if (uiState.granularity == InsightsGranularity.YEAR) {
                "\u672C\u5E74\u6682\u65E0\u8D26\u5355\u6570\u636E\uFF0C\u8BB0\u4E00\u7B14\u540E\u5373\u53EF\u751F\u6210\u6D1E\u5BDF\u3002"
            } else {
                "\u672C\u6708\u6682\u65E0\u8D26\u5355\u6570\u636E\uFF0C\u8BB0\u4E00\u7B14\u540E\u5373\u53EF\u751F\u6210\u6D1E\u5BDF\u3002"
            }
            "zh-TW" -> if (uiState.granularity == InsightsGranularity.YEAR) {
                "\u672C\u5E74\u66AB\u7121\u5E33\u55AE\u8CC7\u6599\uFF0C\u8A18\u4E00\u7B46\u5F8C\u5373\u53EF\u7522\u751F\u6D1E\u5BDF\u3002"
            } else {
                "\u672C\u6708\u66AB\u7121\u5E33\u55AE\u8CC7\u6599\uFF0C\u8A18\u4E00\u7B46\u5F8C\u5373\u53EF\u7522\u751F\u6D1E\u5BDF\u3002"
            }
            else -> if (uiState.granularity == InsightsGranularity.YEAR) {
                "No records this year yet. Add transactions to generate insights."
            } else {
                "No records this month yet. Add transactions to generate insights."
            }
        }
    }

    private fun noRuleHitHintBody(): String {
        return when (localeTag) {
            "zh-CN" -> "\u5F53\u524D\u5468\u671F\u672A\u5339\u914D\u5230\u89C4\u5219\u6216\u5EFA\u8BAE\uFF0C\u7EE7\u7EED\u8BB0\u8D26\u540E\u518D\u67E5\u770B\u3002"
            "zh-TW" -> "\u76EE\u524D\u9031\u671F\u672A\u5339\u914D\u5230\u898F\u5247\u6216\u5EFA\u8B70\uFF0C\u6301\u7E8C\u8A18\u5E33\u5F8C\u518D\u67E5\u770B\u3002"
            else -> "No rules or suggestions matched for this period yet."
        }
    }

    private fun insightHelpButtonLabel(): String {
        return when (localeTag) {
            "zh-CN" -> "\u6D1E\u5BDF\u8BF4\u660E"
            "zh-TW" -> "\u6D1E\u5BDF\u8AAA\u660E"
            else -> "Insight help"
        }
    }

    private fun showInsightHelpDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_insight_help, null, false)
        val titleText = dialogView.findViewById<TextView>(R.id.help_title_text)
        val descriptionText = dialogView.findViewById<TextView>(R.id.help_description_text)
        val closeButton = dialogView.findViewById<MaterialButton>(R.id.close_button)

        val title = when (localeTag) {
            "zh-CN" -> "\u6570\u636E\u6D1E\u5BDF\u8BF4\u660E"
            "zh-TW" -> "\u8CC7\u6599\u6D1E\u5BDF\u8AAA\u660E"
            else -> "About Data Insights"
        }
        val message = when (localeTag) {
            "zh-CN" -> "\u6570\u636E\u6D1E\u5BDF\u4F1A\u57FA\u4E8E\u5F53\u524D\u5468\u671F\u7684\u6536\u652F\u8BB0\u5F55\uFF0C\u81EA\u52A8\u63D0\u70BC\u6700\u503C\u5F97\u5173\u6CE8\u7684\u53D8\u5316\uFF0C\u5E2E\u4F60\u66F4\u5FEB\u53D1\u73B0\u95EE\u9898\u3002\n\n\u7C7B\u578B\u8BF4\u660E\uFF1A\n\u2022 \u53D8\u5316\uff08Summary\uff09\uFF1A\u76F8\u6BD4\u4E0A\u671F\u51FA\u73B0\u660E\u663E\u53D8\u5316\n\u2022 \u98CE\u9669\uff08Risk\uff09\uFF1A\u6309\u5F53\u524D\u8D8B\u52BF\u53EF\u80FD\u51FA\u73B0\u8D85\u652F\u6216\u7ED3\u4F59\u4E0B\u6ED1\n\u2022 \u5F02\u5E38\uff08Anomaly\uff09\uFF1A\u8FD1\u671F\u51FA\u73B0\u4E0D\u5BFB\u5E38\u7684\u652F\u51FA\u4E8B\u4EF6\n\u2022 \u4E60\u60EF\uff08Habit\uff09\uFF1A\u7A33\u5B9A\u51FA\u73B0\u7684\u6D88\u8D39\u6A21\u5F0F\u504F\u5DEE"
            "zh-TW" -> "\u8CC7\u6599\u6D1E\u5BDF\u6703\u6839\u64DA\u76EE\u524D\u9031\u671F\u7684\u6536\u652F\u8A18\u9304\uFF0C\u81EA\u52D5\u63D0\u7149\u6700\u503C\u5F97\u95DC\u6CE8\u7684\u8B8A\u5316\uFF0C\u5E6B\u4F60\u66F4\u5FEB\u627E\u5230\u554F\u984C\u3002\n\n\u985E\u578B\u8AAA\u660E\uFF1A\n\u2022 \u8B8A\u5316\uff08Summary\uff09\uFF1A\u76F8\u8F03\u4E0A\u671F\u51FA\u73FE\u660E\u986F\u8B8A\u5316\n\u2022 \u98A8\u96AA\uff08Risk\uff09\uFF1A\u4F9D\u76EE\u524D\u8D68\u52E2\u53EF\u80FD\u767C\u751F\u8D85\u652F\u6216\u7D50\u9918\u4E0B\u6ED1\n\u2022 \u7570\u5E38\uff08Anomaly\uff09\uFF1A\u8FD1\u671F\u51FA\u73FE\u4E0D\u5C0B\u5E38\u7684\u652F\u51FA\u4E8B\u4EF6\n\u2022 \u7FD2\u6163\uff08Habit\uff09\uFF1A\u7A69\u5B9A\u51FA\u73FE\u7684\u6D88\u8CBB\u6A21\u5F0F\u504F\u5DEE"
            else -> "Data Insights summarizes current-period records and highlights the most important spending signals so you can spot issues faster.\n\nTypes:\n\u2022 Summary: clear period-over-period change\n\u2022 Risk: current trend may lead to overspending or lower surplus\n\u2022 Anomaly: unusual expense events detected recently\n\u2022 Habit: recurring spending pattern deviation"
        }

        titleText.text = title
        descriptionText.text = message
        closeButton.text = text.close
        val dialog = DialogFactory.createCardDialog(requireContext(), dialogView)
        closeButton.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun createInsightCard(card: InsightCard, showTypeTag: Boolean = true): View {
        val itemBinding = ItemInsightRuleBinding.inflate(layoutInflater, null, false)
        itemBinding.insightRuleType.isVisible = showTypeTag && card.typeLabel.isNotBlank()
        itemBinding.insightRuleType.text = card.typeLabel.uppercase(numberLocale)
        itemBinding.insightRuleTitle.text = card.title
        itemBinding.insightRuleBody.text = card.body
        val surfaceVariant = color(R.color.insights_surface_variant)
        val fill = color(R.color.insights_surface_container_lowest)
        background(itemBinding.insightRuleRoot, fill, 14f, ColorUtils.setAlphaComponent(surfaceVariant, 64))
        val (tagTextColor, tagFill, tagStroke) = when (card.type) {
            InsightType.RISK -> Triple(
                color(R.color.insights_amber_600),
                color(R.color.insights_amber_50),
                color(R.color.insights_amber_200)
            )
            InsightType.ANOMALY -> Triple(
                color(R.color.insights_error_container),
                color(R.color.insights_red_50),
                color(R.color.insights_red_100)
            )
            InsightType.HABIT -> {
                val blue = color(R.color.insights_blue_500)
                Triple(
                    blue,
                    ColorUtils.setAlphaComponent(blue, 26),
                    ColorUtils.setAlphaComponent(blue, 76)
                )
            }
            InsightType.SUGGESTION -> Triple(
                color(R.color.insights_green_600),
                color(R.color.insights_green_50),
                color(R.color.insights_green_100)
            )
            InsightType.SUMMARY -> Triple(
                color(R.color.insights_on_surface_variant),
                color(R.color.insights_surface_container_low),
                ColorUtils.setAlphaComponent(color(R.color.insights_surface_variant), 180)
            )
        }
        itemBinding.insightRuleType.setTextColor(tagTextColor)
        if (itemBinding.insightRuleType.isVisible) {
            itemBinding.insightRuleType.setPadding(
                dpInt(8f),
                dpInt(3f),
                dpInt(8f),
                dpInt(3f)
            )
            background(
                itemBinding.insightRuleType,
                fillColor = tagFill,
                radiusDp = 999f,
                strokeColor = tagStroke,
                strokeWidthDp = 1f
            )
        }
        return itemBinding.root
    }

    private fun IndexedTransaction.toRuleTransaction(): RuleTransaction {
        return RuleTransaction(
            type = source.type,
            categoryId = source.categoryId,
            amount = amountCny,
            timestampMillis = source.timestampMillis
        )
    }

    private fun toInsightCard(item: InsightItem): InsightCard {
        val actionLabel = when (item.action) {
            InsightAction.TREND -> text.insightActionTrend
            InsightAction.CATEGORY -> text.insightActionCategory
            InsightAction.CALENDAR -> text.insightActionCalendar
            InsightAction.NONE -> ""
        }
        val typeLabel = when (item.insightType) {
            InsightType.SUMMARY -> text.insightTypeSummary
            InsightType.RISK -> text.insightTypeRisk
            InsightType.ANOMALY -> text.insightTypeAnomaly
            InsightType.HABIT -> text.insightTypeHabit
            InsightType.SUGGESTION -> text.insightTypeSuggestion
        }
        val categoryName = item.categoryId?.let { categoryName(it) }.orEmpty()
        val isYear = uiState.granularity == InsightsGranularity.YEAR
        fun tri(zhCn: String, zhTw: String, en: String): String {
            return when (localeTag) {
                "zh-CN" -> zhCn
                "zh-TW" -> zhTw
                else -> en
            }
        }

        return when (item.insightId) {
            InsightId.A1_EXPENSE_INCREASE -> {
                val ratio = item.numeric["delta_ratio"] ?: 0.0
                val delta = item.numeric["delta_amount"] ?: 0.0
                InsightCard(
                    type = item.insightType,
                    typeLabel = typeLabel,
                    title = if (isYear) {
                        tri(
                            "\u672C\u5E74\u603B\u652F\u51FA\u663E\u8457\u4E0A\u5347",
                            "\u672C\u5E74\u7E3D\u652F\u51FA\u660E\u986F\u4E0A\u5347",
                            "Total expense increased significantly this year"
                        )
                    } else {
                        tri(
                            "\u672C\u6708\u603B\u652F\u51FA\u663E\u8457\u4E0A\u5347",
                            "\u672C\u6708\u7E3D\u652F\u51FA\u660E\u986F\u4E0A\u5347",
                            "Total expense increased significantly this month"
                        )
                    },
                    body = tri(
                        "\u8F83\u4E0A\u671F\u589E\u957F ${formatPercent(ratio)}\uFF0C\u591A\u652F\u51FA ${money(delta)}\u3002",
                        "\u8F03\u4E0A\u671F\u6210\u9577 ${formatPercent(ratio)}\uFF0C\u591A\u652F\u51FA ${money(delta)}\u3002",
                        "Up ${formatPercent(ratio)} vs previous period, +${money(delta)} expense."
                    ),
                    keyNumber = "",
                    action = item.action,
                    actionLabel = actionLabel,
                    categoryId = item.categoryId
                )
            }
            InsightId.A4_SURPLUS_DROP -> {
                val currentSurplus = item.numeric["current_surplus"] ?: 0.0
                val delta = item.numeric["delta_amount_abs"] ?: 0.0
                val rate = item.numeric["current_surplus_rate"] ?: 0.0
                InsightCard(
                    type = item.insightType,
                    typeLabel = typeLabel,
                    title = if (isYear) {
                        tri(
                            "\u672C\u5E74\u7ED3\u4F59\u660E\u663E\u6076\u5316",
                            "\u672C\u5E74\u7D50\u9918\u660E\u986F\u60E1\u5316",
                            "Surplus worsened this year"
                        )
                    } else {
                        tri(
                            "\u672C\u6708\u7ED3\u4F59\u660E\u663E\u6076\u5316",
                            "\u672C\u6708\u7D50\u9918\u660E\u986F\u60E1\u5316",
                            "Surplus worsened this month"
                        )
                    },
                    body = tri(
                        "\u5F53\u524D\u7ED3\u4F59 ${money(currentSurplus)}\uFF0C\u8F83\u4E0A\u671F\u53D8\u5316 ${money(delta)}\uFF0C\u7ED3\u4F59\u7387 ${formatPercent(rate)}\u3002",
                        "\u76EE\u524D\u7D50\u9918 ${money(currentSurplus)}\uFF0C\u8F03\u4E0A\u671F\u8B8A\u5316 ${money(delta)}\uFF0C\u7D50\u9918\u7387 ${formatPercent(rate)}\u3002",
                        "Current surplus ${money(currentSurplus)}, changed ${money(delta)} vs previous, surplus rate ${formatPercent(rate)}."
                    ),
                    keyNumber = "",
                    action = item.action,
                    actionLabel = actionLabel,
                    categoryId = item.categoryId
                )
            }
            InsightId.B1_SURPLUS_PROJECTION_LOW -> {
                val projectedSurplus = item.numeric["projected_surplus"] ?: 0.0
                val ratio = item.numeric["delta_ratio"] ?: 0.0
                InsightCard(
                    type = item.insightType,
                    typeLabel = typeLabel,
                    title = if (isYear) {
                        tri(
                            "\u5E74\u672B\u7ED3\u4F59\u53EF\u80FD\u504F\u4F4E",
                            "\u5E74\u672B\u7D50\u9918\u53EF\u80FD\u504F\u4F4E",
                            "Year-end surplus may be low"
                        )
                    } else {
                        tri(
                            "\u6708\u5E95\u7ED3\u4F59\u53EF\u80FD\u504F\u4F4E",
                            "\u6708\u5E95\u7D50\u9918\u53EF\u80FD\u504F\u4F4E",
                            "Month-end surplus may be low"
                        )
                    },
                    body = tri(
                        "\u6309\u5F53\u524D\u8282\u594F\uFF0C\u9884\u6D4B\u7ED3\u4F59\u7EA6 ${money(projectedSurplus)}\uFF0C\u4F4E\u4E8E\u8FD1\u671F\u5747\u503C ${formatPercent(ratio)}\u3002",
                        "\u4F9D\u76EE\u524D\u7BC0\u594F\uFF0C\u9810\u4F30\u7D50\u9918\u7D04 ${money(projectedSurplus)}\uFF0C\u4F4E\u65BC\u8FD1\u671F\u5747\u503C ${formatPercent(ratio)}\u3002",
                        "At current pace, projected surplus is ${money(projectedSurplus)}, ${formatPercent(ratio)} below recent average."
                    ),
                    keyNumber = "",
                    action = item.action,
                    actionLabel = actionLabel,
                    categoryId = item.categoryId
                )
            }
            InsightId.B2_BUDGET_OVERSPEND_RISK -> {
                val projectedExpense = item.numeric["projected_expense"] ?: 0.0
                val gap = item.numeric["projected_budget_gap"] ?: 0.0
                InsightCard(
                    type = item.insightType,
                    typeLabel = typeLabel,
                    title = if (isYear) {
                        tri(
                            "\u672C\u5E74\u5B58\u5728\u8D85\u652F\u98CE\u9669",
                            "\u672C\u5E74\u5B58\u5728\u8D85\u652F\u98A8\u96AA",
                            "Over-budget risk this year"
                        )
                    } else {
                        tri(
                            "\u672C\u6708\u5B58\u5728\u8D85\u652F\u98CE\u9669",
                            "\u672C\u6708\u5B58\u5728\u8D85\u652F\u98A8\u96AA",
                            "Over-budget risk this month"
                        )
                    },
                    body = tri(
                        "\u9884\u8BA1\u652F\u51FA ${money(projectedExpense)}\uFF0C\u53EF\u80FD\u8D85\u51FA\u9884\u7B97 ${money(gap)}\u3002",
                        "\u9810\u8A08\u652F\u51FA ${money(projectedExpense)}\uFF0C\u53EF\u80FD\u8D85\u51FA\u9810\u7B97 ${money(gap)}\u3002",
                        "Projected expense ${money(projectedExpense)} may exceed budget by ${money(gap)}."
                    ),
                    keyNumber = "",
                    action = item.action,
                    actionLabel = actionLabel,
                    categoryId = item.categoryId
                )
            }
            InsightId.C1_CATEGORY_SPIKE -> {
                val ratio = item.numeric["delta_ratio"] ?: 0.0
                val delta = item.numeric["delta_amount"] ?: 0.0
                InsightCard(
                    type = item.insightType,
                    typeLabel = typeLabel,
                    title = tri(
                        "$categoryName \u652F\u51FA\u660E\u663E\u5347\u9AD8",
                        "$categoryName \u652F\u51FA\u660E\u986F\u5347\u9AD8",
                        "$categoryName spending spiked"
                    ),
                    body = tri(
                        "$categoryName \u8F83\u8FD1\u671F\u57FA\u7EBF\u9AD8 ${formatPercent(ratio)}\uFF0C\u591A\u652F\u51FA ${money(delta)}\u3002",
                        "$categoryName \u8F03\u8FD1\u671F\u57FA\u7DDA\u9AD8 ${formatPercent(ratio)}\uFF0C\u591A\u652F\u51FA ${money(delta)}\u3002",
                        "$categoryName is ${formatPercent(ratio)} above baseline, +${money(delta)} expense."
                    ),
                    keyNumber = "",
                    action = item.action,
                    actionLabel = actionLabel,
                    categoryId = item.categoryId
                )
            }
            InsightId.D1_LARGE_TRANSACTION -> {
                val amount = item.numeric["amount"] ?: 0.0
                val dateText = item.timestampMillis?.let { formatInsightDate(it) } ?: "--"
                InsightCard(
                    type = item.insightType,
                    typeLabel = typeLabel,
                    title = tri(
                        "\u6700\u8FD1\u51FA\u73B0\u5927\u989D\u5355\u7B14\u652F\u51FA",
                        "\u8FD1\u671F\u51FA\u73FE\u5927\u984D\u55AE\u7B46\u652F\u51FA",
                        "A large single expense was detected"
                    ),
                    body = tri(
                        "$dateText \u6709\u4E00\u7B14 ${money(amount)} \u7684\u652F\u51FA\uFF0C\u660E\u663E\u9AD8\u4E8E\u8FD1\u671F\u5E38\u89C4\u6C34\u5E73\u3002",
                        "$dateText \u6709\u4E00\u7B46 ${money(amount)} \u7684\u652F\u51FA\uFF0C\u660E\u986F\u9AD8\u65BC\u8FD1\u671F\u5E38\u614B\u6C34\u6E96\u3002",
                        "On $dateText, a ${money(amount)} expense was detected and is well above normal."
                    ),
                    keyNumber = "",
                    action = item.action,
                    actionLabel = actionLabel,
                    categoryId = item.categoryId
                )
            }
            InsightId.F1_WEEKEND_HIGHER -> {
                val weekendAvg = item.numeric["weekend_avg"] ?: 0.0
                val weekdayAvg = item.numeric["weekday_avg"] ?: 0.0
                val multiple = item.numeric["multiple"] ?: 0.0
                InsightCard(
                    type = item.insightType,
                    typeLabel = typeLabel,
                    title = tri(
                        "\u5468\u672B\u6D88\u8D39\u663E\u8457\u9AD8\u4E8E\u5DE5\u4F5C\u65E5",
                        "\u9031\u672B\u6D88\u8CBB\u660E\u986F\u9AD8\u65BC\u5E73\u65E5",
                        "Weekend spending is much higher than weekdays"
                    ),
                    body = tri(
                        "\u5468\u672B\u65E5\u5747 ${money(weekendAvg)}\uFF0C\u5DE5\u4F5C\u65E5\u65E5\u5747 ${money(weekdayAvg)}\uFF0C\u7EA6\u4E3A ${multiple.formatTrimmed()}\u500D\u3002",
                        "\u9031\u672B\u65E5\u5747 ${money(weekendAvg)}\uFF0C\u5E73\u65E5\u65E5\u5747 ${money(weekdayAvg)}\uFF0C\u7D04\u70BA ${multiple.formatTrimmed()}\u500D\u3002",
                        "Weekend daily avg ${money(weekendAvg)} vs weekday ${money(weekdayAvg)} (${multiple.formatTrimmed()}x)."
                    ),
                    keyNumber = "",
                    action = item.action,
                    actionLabel = actionLabel,
                    categoryId = item.categoryId
                )
            }
            InsightId.H1_TOP_SAVABLE_CATEGORY -> {
                val savable = item.numeric["savable_amount"] ?: 0.0
                InsightCard(
                    type = item.insightType,
                    typeLabel = typeLabel,
                    title = tri(
                        "\u4F18\u5148\u63A7\u5236 $categoryName \u66F4\u6709\u6548",
                        "\u512A\u5148\u63A7\u5236 $categoryName \u66F4\u6709\u6548",
                        "Prioritizing $categoryName can save more"
                    ),
                    body = tri(
                        "\u82E5 $categoryName \u6062\u590D\u5230\u8FD1\u671F\u5E73\u5747\u6C34\u5E73\uFF0C\u672C\u671F\u9884\u8BA1\u53EF\u591A\u7ED3\u4F59 ${money(savable)}\u3002",
                        "\u82E5 $categoryName \u56DE\u5230\u8FD1\u671F\u5E73\u5747\u6C34\u6E96\uFF0C\u672C\u671F\u9810\u4F30\u53EF\u591A\u7D50\u9918 ${money(savable)}\u3002",
                        "If $categoryName returns to baseline, you could save about ${money(savable)} this period."
                    ),
                    keyNumber = "",
                    action = item.action,
                    actionLabel = actionLabel,
                    categoryId = item.categoryId
                )
            }
        }
    }

    private fun formatInsightDate(timestampMillis: Long): String {
        val date = Instant.ofEpochMilli(timestampMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        val pattern = if (localeTag == "en") "MMM d, yyyy" else "yyyy/M/d"
        return date.format(DateTimeFormatter.ofPattern(pattern, numberLocale))
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
            itemBinding.categoryIcon.text = CategoryLocalizer.normalizeIconGlyph(slice.iconGlyph.ifBlank { "payments" })
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
            totals[transaction.source.categoryId] = (totals[transaction.source.categoryId] ?: 0.0) + transaction.amountCny
        }
        val total = totals.values.sum()
        return totals.entries.map { (categoryId, amount) ->
            val category = categoryMap[categoryId]
            InsightsCategorySlice(
                categoryId = categoryId,
                name = category?.let { CategoryLocalizer.nameForId(requireContext(), it.id, it.name) } ?: text.other,
                iconGlyph = CategoryLocalizer.normalizeIconGlyph(category?.iconGlyph.orEmpty().ifBlank { "payments" }),
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
                income = bucket.sumOf { if (it.source.type == TransactionType.INCOME) it.amountCny else 0.0 },
                expense = bucket.sumOf { if (it.source.type == TransactionType.EXPENSE) it.amountCny else 0.0 }
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
                income = bucket.sumOf { if (it.source.type == TransactionType.INCOME) it.amountCny else 0.0 },
                expense = bucket.sumOf { if (it.source.type == TransactionType.EXPENSE) it.amountCny else 0.0 }
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
                income = bucket.sumOf { if (it.source.type == TransactionType.INCOME) it.amountCny else 0.0 },
                expense = bucket.sumOf { if (it.source.type == TransactionType.EXPENSE) it.amountCny else 0.0 }
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

    private fun buildTransactionIndex(transactions: List<LedgerTransaction>): TransactionIndex {
        val currentYear = LocalDate.now().year
        val indexed = transactions.map { transaction ->
            val date = Instant.ofEpochMilli(transaction.timestampMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            IndexedTransaction(
                source = transaction,
                amountCny = transaction.amountInCny(),
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
            if (transaction.source.type == TransactionType.INCOME) income += transaction.amountCny else expense += transaction.amountCny
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
                        income = bucket.sumOf { if (it.source.type == TransactionType.INCOME) it.amountCny else 0.0 },
                        expense = bucket.sumOf { if (it.source.type == TransactionType.EXPENSE) it.amountCny else 0.0 }
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
                            income += bucket.sumOf { if (it.source.type == TransactionType.INCOME) it.amountCny else 0.0 }
                            expense += bucket.sumOf { if (it.source.type == TransactionType.EXPENSE) it.amountCny else 0.0 }
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
                        income = bucket.sumOf { if (it.source.type == TransactionType.INCOME) it.amountCny else 0.0 },
                        expense = bucket.sumOf { if (it.source.type == TransactionType.EXPENSE) it.amountCny else 0.0 }
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
                        income = bucket.sumOf { if (it.source.type == TransactionType.INCOME) it.amountCny else 0.0 },
                        expense = bucket.sumOf { if (it.source.type == TransactionType.EXPENSE) it.amountCny else 0.0 }
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
                        income = current.income + if (transaction.source.type == TransactionType.INCOME) transaction.amountCny else 0.0,
                        expense = current.expense + if (transaction.source.type == TransactionType.EXPENSE) transaction.amountCny else 0.0
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
                        income = current.income + if (transaction.source.type == TransactionType.INCOME) transaction.amountCny else 0.0,
                        expense = current.expense + if (transaction.source.type == TransactionType.EXPENSE) transaction.amountCny else 0.0
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
                income = current.income + if (transaction.source.type == TransactionType.INCOME) transaction.amountCny else 0.0,
                expense = current.expense + if (transaction.source.type == TransactionType.EXPENSE) transaction.amountCny else 0.0
            )
        }
        return stats
    }

    private fun buildMonthlyCalendarStats(transactions: List<IndexedTransaction>): List<CalendarStat> {
        val stats = MutableList(12) { monthIndex -> CalendarStat(0, monthIndex, 0.0, 0.0) }
        transactions.forEach { transaction ->
            val current = stats[transaction.monthIndex]
            stats[transaction.monthIndex] = current.copy(
                income = current.income + if (transaction.source.type == TransactionType.INCOME) transaction.amountCny else 0.0,
                expense = current.expense + if (transaction.source.type == TransactionType.EXPENSE) transaction.amountCny else 0.0
            )
        }
        return stats
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

    private fun updateToggleStyles() {
        styleSliderToggle(binding.insightsTrendMetricExpense, uiState.trendMetric == InsightsMetric.EXPENSE)
        styleSliderToggle(binding.insightsTrendMetricIncome, uiState.trendMetric == InsightsMetric.INCOME)
        styleSliderToggle(binding.insightsTrendMetricBalance, uiState.trendMetric == InsightsMetric.BALANCE)
        styleSliderToggle(binding.insightsCategoryTypeExpense, uiState.categoryMode == InsightsCategoryMode.EXPENSE)
        styleSliderToggle(binding.insightsCategoryTypeIncome, uiState.categoryMode == InsightsCategoryMode.INCOME)
        styleSliderToggle(binding.insightsCalendarMetricExpense, uiState.calendarMetric == InsightsMetric.EXPENSE)
        styleSliderToggle(binding.insightsCalendarMetricIncome, uiState.calendarMetric == InsightsMetric.INCOME)
        styleSliderToggle(binding.insightsCalendarMetricBalance, uiState.calendarMetric == InsightsMetric.BALANCE)
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
            return getString(R.string.home_month_selector_all)
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
        val categories: Map<String, LedgerCategory>
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
        val amountCny: Double,
        val year: Int,
        val monthIndex: Int,
        val day: Int,
        val dayKey: String
    )

    private data class MonthBucket(val year: Int, val monthIndex: Int)
    private data class Summary(val income: Double, val expense: Double) { val balance: Double get() = income - expense }
    private data class PeriodProgress(val totalUnits: Int, val elapsedUnits: Int, val isCurrentPeriod: Boolean)
    private data class CalendarStat(val dayLabel: Int, val monthIndex: Int, val income: Double, val expense: Double)
    private data class ProjectCalendarStat(val date: LocalDate, val income: Double, val expense: Double)
    private data class ProjectMonthCalendarStat(val month: YearMonth, val income: Double, val expense: Double)
    private data class YearCalendarStat(val year: Int, val income: Double, val expense: Double)
    private data class ToneScale(val maxValue: Double, val positiveMax: Double, val negativeMax: Double)
    private data class CellTone(val fillColor: Int, val strokeColor: Int, val primaryTextColor: Int)
    private data class InsightCard(
        val type: InsightType,
        val typeLabel: String,
        val title: String,
        val body: String,
        val keyNumber: String,
        val action: InsightAction,
        val actionLabel: String,
        val categoryId: String?
    )

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

