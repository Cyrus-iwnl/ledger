package com.example.account.ui.home

import android.app.Dialog
import android.content.Context
import android.text.InputFilter
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.account.MainActivity
import com.example.account.PerfTrace
import com.example.account.R
import com.example.account.data.LedgerDashboard
import com.example.account.data.LedgerTransaction
import com.example.account.data.LedgerViewModel
import com.example.account.data.TransactionType
import com.example.account.data.originalExpenseAmount
import com.example.account.databinding.FragmentHomeBinding
import com.example.account.ui.DialogFactory
import com.example.account.ui.insights.insightsNumberLocale
import com.example.account.ui.insights.insightsText
import com.example.account.ui.insights.normalizeInsightsLocaleTag
import com.example.account.ui.period.PeriodPickerDialogFragment
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.text.DecimalFormat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: LedgerViewModel
    private lateinit var adapter: HomeDayAdapter
    private val amountFormat = DecimalFormat("#,##0.00")
    private val budgetInputPattern = Regex("^\\d+(\\.\\d{1,2})?$")
    private var transactionTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault())
    private var latestDashboard: LedgerDashboard? = null
    private var monthlyBudgetCap: Double? = null
    private var selectedMonth: YearMonth? = YearMonth.now()
    private var monthPickerAnchorMonth: YearMonth = YearMonth.now()
    private var selectedYear: Int = YearMonth.now().year
    private var selectedScope: HomeScope = HomeScope.MONTH
    private var selectedWindowDays: Int = 15
    private var trendDisplayMode: TrendDisplayMode = TrendDisplayMode.EXPENSE
    private var selectedTrendDayLabel: String? = null
    private var localeTag: String = "en"
    private var numberLocale: Locale = Locale.US
    private var monthWord: String = "Month"
    private var needsLocaleDrivenDashboardRefresh: Boolean = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        PerfTrace.measure("HomeFragment.onViewCreated") {
            super.onViewCreated(view, savedInstanceState)

            viewModel = ViewModelProvider(
                requireActivity(),
                LedgerViewModel.Factory(requireActivity().application)
            )[LedgerViewModel::class.java]

            syncLocaleStateFromResources(force = true)

            restoreHomeScopePreference()
            restoreTrendFilterPreference()
            applyLedgerModeUi()
            refreshBudgetForSelection()
            applyDashboardScope()

            adapter = HomeDayAdapter(
                categories = viewModel.categoriesFor(TransactionType.EXPENSE) + viewModel.categoriesFor(TransactionType.INCOME),
                onDayAdd = { dateMillis ->
                    (activity as? MainActivity)?.openTransactionEditor(dateMillis = dateMillis)
                },
                onTransactionClick = { transactionId ->
                    showTransactionActions(transactionId)
                }
            )

            binding.dayList.layoutManager = LinearLayoutManager(requireContext())
            binding.dayList.adapter = adapter

            binding.menuButton.setOnClickListener {
                (activity as? MainActivity)?.openSettings()
            }
            binding.trendFilterButton.setOnClickListener {
                showTrendFilterDialog()
            }
            binding.trendChart.setOnPointSelectedListener { point ->
                selectedTrendDayLabel = point?.label
                latestDashboard?.let { renderDashboard(it) }
            }
            binding.root.setOnTouchListener { _, event ->
                if (
                    event.actionMasked == MotionEvent.ACTION_DOWN &&
                    selectedTrendDayLabel != null &&
                    !isTouchInsideView(binding.trendChart, event.rawX, event.rawY)
                ) {
                    binding.trendChart.clearSelectedPoint()
                }
                false
            }
            viewModel.setWindowDays(selectedWindowDays)

            binding.budgetSection.setOnClickListener {
                showMonthlyBudgetDialog()
            }
            binding.monthSelector.setOnClickListener {
                openMonthPicker()
            }
            binding.statsButton.setOnClickListener {
                val currentMonth = when (selectedScope) {
                    HomeScope.MONTH -> selectedMonth ?: monthPickerAnchorMonth
                    HomeScope.YEAR -> YearMonth.of(selectedYear, monthPickerAnchorMonth.monthValue)
                    HomeScope.ALL -> monthPickerAnchorMonth
                }
                val granularity = when (selectedScope) {
                    HomeScope.MONTH -> "MONTH"
                    HomeScope.YEAR -> "YEAR"
                    HomeScope.ALL -> "ALL"
                }
                (activity as? MainActivity)?.openInsights(
                    defaultGranularity = granularity,
                    defaultYear = currentMonth.year,
                    defaultMonthIndex = currentMonth.monthValue - 1
                )
            }

            viewModel.currentLedger.observe(viewLifecycleOwner) { ledger ->
                applyLedgerModeUi()
                refreshBudgetForSelection()
                latestDashboard?.let { renderDashboard(it) }
            }

            viewModel.monthlyBudget.observe(viewLifecycleOwner) { budget ->
                if (selectedScope != HomeScope.MONTH) {
                    return@observe
                }
                monthlyBudgetCap = budget
                latestDashboard?.let { renderDashboard(it) }
            }

            viewModel.dashboard.observe(viewLifecycleOwner) { dashboard ->
                latestDashboard = dashboard
                renderDashboard(dashboard)
            }

            parentFragmentManager.setFragmentResultListener(
                HOME_PERIOD_PICKER_REQUEST_KEY,
                viewLifecycleOwner
            ) { _, bundle ->
                val isAll = bundle.getBoolean(PeriodPickerDialogFragment.RESULT_IS_ALL, false)
                if (isAll) {
                    selectAllMonths()
                    return@setFragmentResultListener
                }
                val resultMode = bundle.getString(PeriodPickerDialogFragment.RESULT_MODE, PeriodPickerDialogFragment.MODE_MONTH)
                if (resultMode == PeriodPickerDialogFragment.MODE_YEAR) {
                    val year = bundle.getInt(PeriodPickerDialogFragment.RESULT_YEAR, selectedYear)
                    if (selectedScope == HomeScope.YEAR && selectedYear == year) {
                        return@setFragmentResultListener
                    }
                    selectedScope = HomeScope.YEAR
                    selectedYear = year
                    monthPickerAnchorMonth = YearMonth.of(year, monthPickerAnchorMonth.monthValue)
                    persistHomeScopePreference()
                    applyLedgerModeUi()
                    refreshBudgetForSelection()
                    applyDashboardScope()
                    return@setFragmentResultListener
                }
                val currentMonth = selectedMonth ?: monthPickerAnchorMonth
                val year = bundle.getInt(PeriodPickerDialogFragment.RESULT_YEAR, currentMonth.year)
                val monthIndex = bundle.getInt(PeriodPickerDialogFragment.RESULT_MONTH_INDEX, currentMonth.monthValue - 1)
                val month = YearMonth.of(year, monthIndex + 1)
                if (selectedScope == HomeScope.MONTH && month == selectedMonth) {
                    return@setFragmentResultListener
                }
                selectedScope = HomeScope.MONTH
                selectedMonth = month
                monthPickerAnchorMonth = month
                selectedYear = month.year
                persistHomeScopePreference()
                applyLedgerModeUi()
                refreshBudgetForSelection()
                applyDashboardScope()
            }

            if (viewModel.dashboard.value == null) {
                viewModel.refresh()
            }
        }
    }

    private fun showTrendFilterDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_trend_filter, null, false)
        val modeBothButton = dialogView.findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.filter_mode_both)
        val modeIncomeButton = dialogView.findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.filter_mode_income)
        val modeExpenseButton = dialogView.findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.filter_mode_expense)
        val range7Button = dialogView.findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.filter_range_7)
        val range15Button = dialogView.findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.filter_range_15)
        val cancelButton = dialogView.findViewById<View>(R.id.filter_cancel_button)
        val applyButton = dialogView.findViewById<View>(R.id.filter_apply_button)

        var pendingMode = trendDisplayMode
        var pendingDays = selectedWindowDays

        fun styleFilterToggle(button: androidx.appcompat.widget.AppCompatButton, active: Boolean) {
            val radius = 12f * resources.displayMetrics.density
            val stroke = (1f * resources.displayMetrics.density).toInt().coerceAtLeast(1)
            button.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = radius
                setColor(if (active) ContextCompat.getColor(requireContext(), R.color.surface_elevated) else android.graphics.Color.TRANSPARENT)
                setStroke(
                    if (active) stroke else 0,
                    ContextCompat.getColor(requireContext(), R.color.card_stroke)
                )
            }
            button.stateListAnimator = null
            button.elevation = 0f
            button.setTextColor(
                if (active) ContextCompat.getColor(requireContext(), R.color.text_primary)
                else ContextCompat.getColor(requireContext(), R.color.text_secondary)
            )
            button.typeface = android.graphics.Typeface.create(
                ResourcesCompat.getFont(requireContext(), R.font.inter_family),
                if (active) 700 else 600,
                false
            )
        }

        fun refreshFilterToggleUi() {
            styleFilterToggle(modeBothButton, pendingMode == TrendDisplayMode.BOTH)
            styleFilterToggle(modeIncomeButton, pendingMode == TrendDisplayMode.INCOME)
            styleFilterToggle(modeExpenseButton, pendingMode == TrendDisplayMode.EXPENSE)
            styleFilterToggle(range7Button, pendingDays == 7)
            styleFilterToggle(range15Button, pendingDays != 7)
        }

        modeBothButton.setOnClickListener {
            pendingMode = TrendDisplayMode.BOTH
            refreshFilterToggleUi()
        }
        modeIncomeButton.setOnClickListener {
            pendingMode = TrendDisplayMode.INCOME
            refreshFilterToggleUi()
        }
        modeExpenseButton.setOnClickListener {
            pendingMode = TrendDisplayMode.EXPENSE
            refreshFilterToggleUi()
        }
        range7Button.setOnClickListener {
            pendingDays = 7
            refreshFilterToggleUi()
        }
        range15Button.setOnClickListener {
            pendingDays = 15
            refreshFilterToggleUi()
        }

        refreshFilterToggleUi()

        val dialog = createStyledDialog(dialogView)
        cancelButton.setOnClickListener { dialog.dismiss() }
        applyButton.setOnClickListener {
            trendDisplayMode = pendingMode
            val updatedWindowDays = pendingDays
            val windowChanged = selectedWindowDays != updatedWindowDays
            selectedWindowDays = updatedWindowDays
            persistTrendFilterPreference()

            dialog.dismiss()

            if (windowChanged) {
                selectedTrendDayLabel = null
                viewModel.setWindowDays(selectedWindowDays)
            } else {
                latestDashboard?.let { renderDashboard(it) }
            }
        }
        dialog.show()
    }

    private fun renderDashboard(dashboard: LedgerDashboard) {
        PerfTrace.measure("HomeFragment.renderDashboard(points=${dashboard.chartPoints.size}, days=${dashboard.daySummaries.size})") {
            val totalExpense = dashboard.totalExpense
            val totalIncome = dashboard.totalIncome
            val remaining = totalIncome - totalExpense
            val scopedExpense = totalExpense

            binding.expenseTotalText.text = buildHeaderAmount(totalExpense)
            binding.incomeTotalText.text = buildPlainAmount(totalIncome)
            binding.incomeTotalText.setTextColor(requireContext().getColor(R.color.text_primary))
            binding.remainingTotalText.text = buildPlainAmount(remaining, showPositiveSign = true)
            binding.remainingTotalText.setTextColor(requireContext().getColor(R.color.text_primary))

            val budgetCap = monthlyBudgetCap
            if (budgetCap == null || budgetCap <= 0.0) {
                binding.budgetPercentText.text = "--"
                binding.budgetProgressText.text = getString(R.string.home_budget_not_set)
                binding.budgetForecastText.text = getString(R.string.home_budget_not_set)
                binding.budgetProgressBar.progress = 0
                binding.budgetProgressBar.setIndicatorColor(
                    requireContext().getColor(R.color.text_hint)
                )
            } else {
                val safeBudget = budgetCap
                val overBudget = scopedExpense > safeBudget
                val budgetPercent = ((scopedExpense / safeBudget) * 100.0)
                    .roundToInt()
                    .coerceAtLeast(0)
                val budgetProgress = budgetPercent.coerceAtMost(100)
                binding.budgetPercentText.text = "${budgetPercent}%"
                binding.budgetProgressText.text = getString(
                    R.string.home_budget_amount_value_format,
                    amountFormat.format(safeBudget)
                )
                binding.budgetForecastText.text = buildBudgetForecastText(
                    scopedExpense = scopedExpense,
                    budgetCap = safeBudget,
                    dashboard = dashboard
                )
                binding.budgetProgressBar.progress = budgetProgress
                binding.budgetProgressBar.setIndicatorColor(
                    requireContext().getColor(
                        if (overBudget) R.color.expense_color else R.color.income_color
                    )
                )
            }

            val windowIncome = dashboard.chartPoints.sumOf { it.income }
            val windowExpense = dashboard.chartPoints.sumOf { it.expense }
            val selectedPoint = selectedTrendDayLabel?.let { selectedLabel ->
                dashboard.chartPoints.firstOrNull { it.label == selectedLabel }
            }
            if (selectedTrendDayLabel != null && selectedPoint == null) {
                selectedTrendDayLabel = null
            }
            binding.trendTitleText.text = getString(R.string.home_trend_title_format, selectedWindowDays)
            binding.trendSummaryText.text = selectedPoint?.let(::buildSelectedDaySummary)
                ?: buildTrendSummary(windowIncome, windowExpense)

            binding.trendChart.submitData(
                newPoints = dashboard.chartPoints,
                showIncome = trendDisplayMode != TrendDisplayMode.EXPENSE,
                showExpense = trendDisplayMode != TrendDisplayMode.INCOME
            )
            adapter.submitList(dashboard.daySummaries)
        }
    }

    private fun buildTrendSummary(windowIncome: Double, windowExpense: Double): String {
        return when (trendDisplayMode) {
            TrendDisplayMode.INCOME -> getString(
                R.string.home_trend_summary_income_format,
                amountFormat.format(windowIncome)
            )
            TrendDisplayMode.EXPENSE -> getString(
                R.string.home_trend_summary_expense_format,
                amountFormat.format(windowExpense)
            )
            TrendDisplayMode.BOTH -> getString(
                R.string.home_trend_summary_both_format,
                amountFormat.format(windowIncome),
                amountFormat.format(windowExpense)
            )
        }
    }

    private fun buildSelectedDaySummary(point: com.example.account.data.ChartPoint): String {
        return getString(
            R.string.home_selected_day_summary_format,
            point.label,
            amountFormat.format(point.income),
            amountFormat.format(point.expense)
        )
    }

    private fun buildBudgetForecastText(
        scopedExpense: Double,
        budgetCap: Double,
        dashboard: LedgerDashboard
    ): String {
        if (budgetCap <= 0.0) {
            return getString(R.string.home_budget_forecast_unknown)
        }
        if (scopedExpense >= budgetCap) {
            return getString(R.string.home_budget_forecast_over)
        }

        val zoneId = ZoneId.systemDefault()
        val today = LocalDate.now(zoneId)
        val remaining = budgetCap - scopedExpense

        return when (selectedScope) {
            HomeScope.MONTH -> {
                val month = selectedMonth ?: return getString(R.string.home_budget_forecast_unknown)
                val isCurrentMonth = month.year == today.year && month.monthValue == today.monthValue
                if (!isCurrentMonth) {
                    return getString(R.string.home_budget_forecast_safe)
                }
                val elapsedDays = today.dayOfMonth.coerceAtLeast(1)
                val runRate = scopedExpense / elapsedDays.toDouble()
                if (!runRate.isFinite() || runRate <= 0.0) {
                    return getString(R.string.home_budget_forecast_unknown)
                }
                val daysToOverrun = ceil(remaining / runRate).toLong().coerceAtLeast(1L)
                val targetDate = today.plusDays(daysToOverrun)
                if (targetDate.isAfter(month.atEndOfMonth())) {
                    getString(R.string.home_budget_forecast_safe)
                } else {
                    getString(
                        R.string.home_budget_forecast_date_format,
                        formatForecastDate(targetDate)
                    )
                }
            }

            HomeScope.YEAR -> {
                val isCurrentYear = selectedYear == today.year
                if (!isCurrentYear) {
                    return getString(R.string.home_budget_forecast_safe)
                }
                val elapsedDays = today.dayOfYear.coerceAtLeast(1)
                val runRate = scopedExpense / elapsedDays.toDouble()
                if (!runRate.isFinite() || runRate <= 0.0) {
                    return getString(R.string.home_budget_forecast_unknown)
                }
                val daysToOverrun = ceil(remaining / runRate).toLong().coerceAtLeast(1L)
                val targetDate = today.plusDays(daysToOverrun)
                val yearEnd = LocalDate.of(selectedYear, 12, 31)
                if (targetDate.isAfter(yearEnd)) {
                    getString(R.string.home_budget_forecast_safe)
                } else {
                    getString(
                        R.string.home_budget_forecast_date_format,
                        formatForecastDate(targetDate)
                    )
                }
            }

            HomeScope.ALL -> {
                val firstDate = dashboard.daySummaries
                    .minByOrNull { it.dateMillis }
                    ?.let { Instant.ofEpochMilli(it.dateMillis).atZone(zoneId).toLocalDate() }
                    ?: today
                val elapsedDays = ChronoUnit.DAYS.between(firstDate, today).toInt() + 1
                if (elapsedDays <= 0) {
                    return getString(R.string.home_budget_forecast_unknown)
                }
                val runRate = scopedExpense / elapsedDays.toDouble()
                if (!runRate.isFinite() || runRate <= 0.0) {
                    return getString(R.string.home_budget_forecast_unknown)
                }
                val daysToOverrun = ceil(remaining / runRate).toLong().coerceAtLeast(1L)
                val targetDate = today.plusDays(daysToOverrun)
                getString(
                    R.string.home_budget_forecast_date_format,
                    formatForecastDate(targetDate)
                )
            }
        }
    }

    private fun formatForecastDate(date: LocalDate): String {
        val pattern = if (localeTag == "en") "MMM d, yyyy" else "yyyy/M/d"
        return date.format(DateTimeFormatter.ofPattern(pattern, numberLocale))
    }

    private fun buildHeaderAmount(value: Double): SpannableString {
        val text = "\u00A5${amountFormat.format(value)}"
        return SpannableString(text).apply {
            setSpan(
                ForegroundColorSpan(requireContext().getColor(R.color.text_hint)),
                0,
                1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            setSpan(
                RelativeSizeSpan(0.5f),
                0,
                1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            val decimalStart = text.lastIndexOf('.')
            if (decimalStart >= 0) {
                setSpan(
                    RelativeSizeSpan(0.6f),
                    decimalStart,
                    text.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }

    private fun buildPlainAmount(value: Double, showPositiveSign: Boolean = false): SpannableString {
        val sign = when {
            value < 0 -> "-"
            showPositiveSign && value > 0 -> "+"
            else -> ""
        }
        val text = "${sign}\u00A5${amountFormat.format(abs(value))}"
        return SpannableString(text).apply {
            val decimalStart = text.lastIndexOf('.')
            if (decimalStart >= 0) {
                setSpan(
                    RelativeSizeSpan(0.6f),
                    decimalStart,
                    text.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }

    private fun showMonthlyBudgetDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_monthly_budget, null, false)
        val titleText = dialogView.findViewById<android.widget.TextView>(R.id.budget_dialog_title)
        val inputLayout = dialogView.findViewById<TextInputLayout>(R.id.budget_input_layout)
        val input = dialogView.findViewById<TextInputEditText>(R.id.budget_input)
        val cancelButton = dialogView.findViewById<View>(R.id.cancel_button)
        val saveButton = dialogView.findViewById<View>(R.id.save_button)
        val allMode = selectedScope == HomeScope.ALL
        val yearMode = selectedScope == HomeScope.YEAR

        if (allMode) {
            titleText.text = getString(R.string.dialog_total_budget_title)
            inputLayout.hint = getString(R.string.home_budget_for_all_format)
        } else if (yearMode) {
            titleText.text = getString(R.string.dialog_yearly_budget_title)
            inputLayout.hint = getString(R.string.home_budget_for_year_format, selectedYear)
        } else {
            titleText.text = getString(R.string.dialog_monthly_budget_title)
            inputLayout.hint = getString(
                R.string.home_budget_for_month_format,
                formatSelectedMonth()
            )
        }
        val existingBudget = monthlyBudgetCap?.takeIf { it > 0.0 }
        input.setText(
            existingBudget?.let { amountFormat.format(it).replace(",", "") } ?: ""
        )
        input.filters = arrayOf(decimalDigitsFilter(2))
        input.setSelection(input.text?.length ?: 0)

        val dialog = createStyledDialog(dialogView)

        cancelButton.setOnClickListener { dialog.dismiss() }
        saveButton.setOnClickListener {
            val raw = input.text?.toString()?.trim()?.replace(",", "") ?: ""
            if (raw.isEmpty()) {
                inputLayout.error = null
                if (allMode) {
                    viewModel.clearTotalBudget()
                    monthlyBudgetCap = null
                } else if (yearMode) {
                    viewModel.clearYearlyBudget(selectedYear)
                    monthlyBudgetCap = null
                } else {
                    val month = selectedMonth ?: return@setOnClickListener
                    viewModel.clearMonthlyBudget(month)
                    monthlyBudgetCap = viewModel.getMonthlyBudget(month)
                }
                latestDashboard?.let { renderDashboard(it) }
                dialog.dismiss()
                return@setOnClickListener
            }
            if (!budgetInputPattern.matches(raw)) {
                inputLayout.error = getString(R.string.home_budget_input_invalid_decimals)
                return@setOnClickListener
            }
            val value = raw.toDoubleOrNull()
            if (value == null || !value.isFinite() || value <= 0.0) {
                inputLayout.error = getString(R.string.home_budget_input_invalid_positive)
                return@setOnClickListener
            }
            inputLayout.error = null
            if (allMode) {
                viewModel.setTotalBudget(value)
                monthlyBudgetCap = viewModel.getTotalBudget()
            } else if (yearMode) {
                viewModel.setYearlyBudget(selectedYear, value)
                monthlyBudgetCap = viewModel.getYearlyBudget(selectedYear)
            } else {
                val month = selectedMonth ?: return@setOnClickListener
                viewModel.setMonthlyBudget(month, value)
            }
            latestDashboard?.let { renderDashboard(it) }
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun decimalDigitsFilter(decimalDigits: Int): InputFilter {
        val pattern = Regex("^\\d*(\\.\\d{0,$decimalDigits})?$")
        return InputFilter { source, start, end, dest, dstart, dend ->
            val replacement = source.subSequence(start, end).toString()
            val updated = StringBuilder(dest)
                .replace(dstart, dend, replacement)
                .toString()
            if (updated.isEmpty() || pattern.matches(updated)) null else ""
        }
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

    private fun selectAllMonths() {
        if (selectedScope == HomeScope.ALL) {
            return
        }
        selectedScope = HomeScope.ALL
        selectedMonth = null
        persistHomeScopePreference()
        binding.monthSelector.text = formatSelectedMonth()
        applyLedgerModeUi()
        refreshBudgetForSelection()
        applyDashboardScope()
    }

    private fun restoreHomeScopePreference() {
        val now = YearMonth.now()
        val prefs = homePrefs()
        val restoredMonth = runCatching {
            val year = prefs.getInt(KEY_HOME_SCOPE_MONTH_YEAR, now.year)
            val monthValue = prefs.getInt(KEY_HOME_SCOPE_MONTH_VALUE, now.monthValue).coerceIn(1, 12)
            YearMonth.of(year, monthValue)
        }.getOrDefault(now)
        selectedMonth = restoredMonth
        selectedYear = prefs.getInt(KEY_HOME_SCOPE_YEAR, restoredMonth.year).coerceAtLeast(1)
        selectedScope = when (prefs.getString(KEY_HOME_SCOPE_MODE, HomeScope.MONTH.name)) {
            HomeScope.YEAR.name -> HomeScope.YEAR
            HomeScope.ALL.name -> HomeScope.ALL
            else -> HomeScope.MONTH
        }
        monthPickerAnchorMonth = runCatching {
            YearMonth.of(selectedYear, restoredMonth.monthValue)
        }.getOrDefault(restoredMonth)
        if (selectedScope == HomeScope.ALL) {
            selectedMonth = null
        }
    }

    private fun persistHomeScopePreference() {
        val resolvedMonth = selectedMonth ?: monthPickerAnchorMonth
        homePrefs().edit()
            .putString(KEY_HOME_SCOPE_MODE, selectedScope.name)
            .putInt(KEY_HOME_SCOPE_YEAR, selectedYear)
            .putInt(KEY_HOME_SCOPE_MONTH_YEAR, resolvedMonth.year)
            .putInt(KEY_HOME_SCOPE_MONTH_VALUE, resolvedMonth.monthValue)
            .apply()
    }

    private fun restoreTrendFilterPreference() {
        val prefs = homePrefs()
        selectedWindowDays = when (prefs.getInt(KEY_HOME_TREND_WINDOW_DAYS, 15)) {
            7 -> 7
            else -> 15
        }
        trendDisplayMode = when (prefs.getString(KEY_HOME_TREND_DISPLAY_MODE, TrendDisplayMode.EXPENSE.name)) {
            TrendDisplayMode.EXPENSE.name -> TrendDisplayMode.EXPENSE
            TrendDisplayMode.INCOME.name -> TrendDisplayMode.INCOME
            else -> TrendDisplayMode.EXPENSE
        }
    }

    private fun persistTrendFilterPreference() {
        homePrefs().edit()
            .putInt(KEY_HOME_TREND_WINDOW_DAYS, selectedWindowDays)
            .putString(KEY_HOME_TREND_DISPLAY_MODE, trendDisplayMode.name)
            .apply()
    }

    private fun homePrefs() =
        requireContext().applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun openMonthPicker() {
        val pickerText = insightsText(localeTag)
        val currentMonth = when (selectedScope) {
            HomeScope.MONTH -> selectedMonth ?: monthPickerAnchorMonth
            HomeScope.YEAR -> YearMonth.of(selectedYear, monthPickerAnchorMonth.monthValue)
            HomeScope.ALL -> monthPickerAnchorMonth
        }
        val availableYears = (viewModel.getAllTransactions()
            .map { Instant.ofEpochMilli(it.timestampMillis).atZone(ZoneId.systemDefault()).year } + LocalDate.now().year)
            .distinct()
            .sortedDescending()
            .toIntArray()
        val monthToggleText = if (localeTag == "en") "By ${insightsText(localeTag).month}" else "按${insightsText(localeTag).month}"
        val yearToggleText = if (localeTag == "en") "By ${insightsText(localeTag).year}" else "按${insightsText(localeTag).year}"
        val selectedScopeMode = when (selectedScope) {
            HomeScope.MONTH -> PeriodPickerDialogFragment.MODE_MONTH
            HomeScope.YEAR -> PeriodPickerDialogFragment.MODE_YEAR
            HomeScope.ALL -> PeriodPickerDialogFragment.MODE_ALL
        }
        PeriodPickerDialogFragment
            .newMonthPicker(
                requestKey = HOME_PERIOD_PICKER_REQUEST_KEY,
                selectedYear = currentMonth.year,
                selectedMonthIndex = currentMonth.monthValue - 1,
                displayYear = currentMonth.year,
                localeTag = localeTag,
                monthWord = monthWord,
                title = monthToggleText,
                closeText = pickerText.close,
                enableAllToggle = true,
                selectedScopeMode = selectedScopeMode,
                allText = pickerText.all,
                yearText = yearToggleText,
                availableYears = availableYears
            )
            .show(parentFragmentManager, "home_period_picker")
    }

    private fun showTransactionActions(transactionId: Long) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_transaction_actions, null, false)
        val transactionTimeText = dialogView.findViewById<android.widget.TextView>(R.id.transaction_time_text)
        val editAction = dialogView.findViewById<View>(R.id.edit_action)
        val deleteAction = dialogView.findViewById<View>(R.id.delete_action)
        val refundAction = dialogView.findViewById<View>(R.id.refund_action)
        val dialog = createStyledDialog(dialogView)
        val transaction = viewModel.getTransaction(transactionId)
        val recordedAt = transaction
            ?.let { formatTransactionTime(it.timestampMillis) }
            ?: "--"
        transactionTimeText.text = getString(R.string.home_recorded_at_format, recordedAt)
        refundAction.isVisible = transaction?.type == TransactionType.EXPENSE

        editAction.setOnClickListener {
            dialog.dismiss()
            (activity as? MainActivity)?.openTransactionEditor(transactionId = transactionId)
        }
        deleteAction.setOnClickListener {
            dialog.dismiss()
            showDeleteTransactionConfirmation(transactionId)
        }
        refundAction.setOnClickListener {
            val expenseTransaction = transaction ?: return@setOnClickListener
            dialog.dismiss()
            showRefundDialog(expenseTransaction)
        }

        dialog.show()
    }

    private fun showRefundDialog(transaction: LedgerTransaction) {
        if (transaction.type != TransactionType.EXPENSE) {
            return
        }
        val dialogView = layoutInflater.inflate(R.layout.dialog_transaction_refund, null, false)
        val originalText = dialogView.findViewById<android.widget.TextView>(R.id.refund_original_text)
        val inputLayout = dialogView.findViewById<TextInputLayout>(R.id.refund_input_layout)
        val input = dialogView.findViewById<TextInputEditText>(R.id.refund_input)
        val cancelButton = dialogView.findViewById<View>(R.id.cancel_button)
        val submitButton = dialogView.findViewById<View>(R.id.submit_button)
        val dialog = createStyledDialog(dialogView)

        val originalAmount = transaction.originalExpenseAmount()
        val maxRefundAmount = originalAmount.coerceAtLeast(0.0)
        originalText.text = getString(
            R.string.refund_original_and_refunded_format,
            amountFormat.format(originalAmount),
            amountFormat.format(transaction.refundedAmount)
        )

        input.filters = arrayOf(decimalDigitsFilter(2))
        input.setText(amountFormat.format(transaction.refundedAmount).replace(",", ""))
        input.setSelection(input.text?.length ?: 0)

        cancelButton.setOnClickListener { dialog.dismiss() }
        submitButton.setOnClickListener {
            val raw = input.text?.toString()?.trim()?.replace(",", "") ?: ""
            if (!budgetInputPattern.matches(raw)) {
                inputLayout.error = getString(R.string.home_budget_input_invalid_decimals)
                return@setOnClickListener
            }
            val value = raw.toDoubleOrNull()
            if (value == null || !value.isFinite() || value < 0.0) {
                inputLayout.error = getString(R.string.home_budget_input_invalid_positive)
                return@setOnClickListener
            }
            if (value > maxRefundAmount) {
                inputLayout.error = getString(
                    R.string.refund_amount_too_large_format,
                    amountFormat.format(maxRefundAmount)
                )
                return@setOnClickListener
            }
            inputLayout.error = null
            try {
                viewModel.refundTransaction(transaction.id, value)
                dialog.dismiss()
            } catch (_: IllegalArgumentException) {
                inputLayout.error = getString(
                    R.string.refund_amount_too_large_format,
                    amountFormat.format(maxRefundAmount)
                )
            }
        }

        dialog.show()
    }

    private fun showDeleteTransactionConfirmation(transactionId: Long) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_delete_transaction, null, false)
        val cancelButton = dialogView.findViewById<View>(R.id.cancel_button)
        val deleteButton = dialogView.findViewById<View>(R.id.delete_button)
        val dialog = createStyledDialog(dialogView)

        cancelButton.setOnClickListener { dialog.dismiss() }
        deleteButton.setOnClickListener {
            dialog.dismiss()
            viewModel.deleteTransaction(transactionId)
        }

        dialog.show()
    }

    private fun createStyledDialog(dialogView: View): Dialog {
        return DialogFactory.createCardDialog(requireContext(), dialogView)
    }

    private fun formatTransactionTime(timestampMillis: Long): String {
        return Instant.ofEpochMilli(timestampMillis)
            .atZone(ZoneId.systemDefault())
            .format(transactionTimeFormat)
    }

    private fun formatSelectedMonth(): String {
        return when (selectedScope) {
            HomeScope.ALL -> getString(R.string.home_month_selector_all)
            HomeScope.YEAR -> selectedYear.toString()
            HomeScope.MONTH -> {
                val resolvedMonth = selectedMonth ?: YearMonth.now()
                if (localeTag == "en") {
                    val monthLabel = resolvedMonth.month
                        .getDisplayName(TextStyle.SHORT, numberLocale)
                        .lowercase(numberLocale)
                        .replaceFirstChar { first ->
                            if (first.isLowerCase()) first.titlecase(numberLocale) else first.toString()
                        }
                    "$monthLabel ${resolvedMonth.year}"
                } else {
                    resolvedMonth.format(DateTimeFormatter.ofPattern("yyyy/M", numberLocale))
                }
            }
        }
    }

    private fun applyLedgerModeUi() {
        binding.summaryScopeLabel.text = when (selectedScope) {
            HomeScope.ALL -> getString(R.string.home_scope_all_spending)
            HomeScope.YEAR -> getString(R.string.home_scope_yearly_spending)
            HomeScope.MONTH -> getString(R.string.monthly_spending)
        }
        binding.budgetLabelText.text = when (selectedScope) {
            HomeScope.ALL -> getString(R.string.home_budget_prefix_all)
            HomeScope.YEAR -> getString(R.string.home_budget_prefix_year)
            HomeScope.MONTH -> getString(R.string.home_budget_prefix_month)
        }
        binding.monthSelector.text = formatSelectedMonth()
        binding.monthSelector.isClickable = true
        binding.monthSelector.isFocusable = true
        binding.monthSelector.setCompoundDrawablesRelativeWithIntrinsicBounds(
            0,
            0,
            R.drawable.ic_dashboard_expand_more_20,
            0
        )
        binding.trendFilterButton.isVisible = true
    }

    private fun refreshBudgetForSelection() {
        when (selectedScope) {
            HomeScope.ALL -> {
                monthlyBudgetCap = viewModel.getTotalBudget()
            }
            HomeScope.YEAR -> {
                monthlyBudgetCap = viewModel.getYearlyBudget(selectedYear)
            }
            HomeScope.MONTH -> {
                val month = selectedMonth ?: return
                monthlyBudgetCap = viewModel.getMonthlyBudget(month)
                viewModel.loadMonthlyBudget(month)
            }
        }
    }

    private fun applyDashboardScope() {
        when (selectedScope) {
            HomeScope.ALL -> viewModel.setDashboardMonth(null)
            HomeScope.YEAR -> viewModel.setDashboardYear(selectedYear)
            HomeScope.MONTH -> viewModel.setDashboardMonth(selectedMonth)
        }
    }

    override fun onResume() {
        super.onResume()
        val localeChanged = syncLocaleStateFromResources()
        if (localeChanged) {
            applyLedgerModeUi()
            needsLocaleDrivenDashboardRefresh = true
        }
        if (needsLocaleDrivenDashboardRefresh) {
            refreshBudgetForSelection()
            viewModel.refresh()
            needsLocaleDrivenDashboardRefresh = false
        }
        applyHomeSystemBars()
    }

    private fun syncLocaleStateFromResources(force: Boolean = false): Boolean {
        val resolvedTag = normalizeInsightsLocaleTag(resources.configuration.locales[0]?.toLanguageTag())
        if (!force && resolvedTag == localeTag) {
            return false
        }
        localeTag = resolvedTag
        numberLocale = insightsNumberLocale(resolvedTag)
        monthWord = insightsText(resolvedTag).month
        transactionTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault())
        return true
    }

    private fun applyHomeSystemBars() {
        val window = activity?.window ?: return
        window.statusBarColor = ContextCompat.getColor(requireContext(), R.color.app_background)
        window.navigationBarColor = ContextCompat.getColor(requireContext(), R.color.app_background)
        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val PREFS_NAME = "app_settings"
        private const val KEY_HOME_SCOPE_MODE = "home_scope_mode"
        private const val KEY_HOME_SCOPE_YEAR = "home_scope_year"
        private const val KEY_HOME_SCOPE_MONTH_YEAR = "home_scope_month_year"
        private const val KEY_HOME_SCOPE_MONTH_VALUE = "home_scope_month_value"
        private const val KEY_HOME_TREND_WINDOW_DAYS = "home_trend_window_days"
        private const val KEY_HOME_TREND_DISPLAY_MODE = "home_trend_display_mode"
        private const val HOME_PERIOD_PICKER_REQUEST_KEY = "home_period_picker_result"

        fun newInstance(): HomeFragment = HomeFragment()
    }

    private enum class TrendDisplayMode {
        BOTH,
        INCOME,
        EXPENSE
    }

    private enum class HomeScope {
        MONTH,
        YEAR,
        ALL
    }
}
