package com.example.account.ui.home

import android.app.Dialog
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
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.account.MainActivity
import com.example.account.R
import com.example.account.data.LedgerDashboard
import com.example.account.data.LedgerViewModel
import com.example.account.data.TransactionType
import com.example.account.databinding.FragmentHomeBinding
import com.example.account.ui.DialogFactory
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.text.DecimalFormat
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: LedgerViewModel
    private lateinit var adapter: HomeDayAdapter
    private val amountFormat = DecimalFormat("#,##0.00")
    private val budgetInputPattern = Regex("^\\d+(\\.\\d{1,2})?$")
    private val monthFormat = DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault())
    private val transactionTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault())
    private var latestDashboard: LedgerDashboard? = null
    private var monthlyBudgetCap: Double? = null
    private var selectedMonth: YearMonth = YearMonth.now()
    private var selectedWindowDays: Int = 15
    private var trendDisplayMode: TrendDisplayMode = TrendDisplayMode.BOTH
    private var selectedTrendDayLabel: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(
            requireActivity(),
            LedgerViewModel.Factory(requireActivity().application)
        )[LedgerViewModel::class.java]

        binding.monthSelector.text = selectedMonth.format(monthFormat)
        monthlyBudgetCap = viewModel.getMonthlyBudget(selectedMonth)
        viewModel.loadMonthlyBudget(selectedMonth)
        viewModel.setDashboardMonth(selectedMonth)

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
            (activity as? MainActivity)?.openInsights()
        }

        viewModel.monthlyBudget.observe(viewLifecycleOwner) { budget ->
            monthlyBudgetCap = budget
            latestDashboard?.let { renderDashboard(it) }
        }

        viewModel.dashboard.observe(viewLifecycleOwner) { dashboard ->
            latestDashboard = dashboard
            renderDashboard(dashboard)
        }

        parentFragmentManager.setFragmentResultListener(
            MonthPickerDialogFragment.REQUEST_KEY,
            viewLifecycleOwner
        ) { _, bundle ->
            val year = bundle.getInt(MonthPickerDialogFragment.RESULT_YEAR)
            val monthIndex = bundle.getInt(MonthPickerDialogFragment.RESULT_MONTH_INDEX)
            val month = YearMonth.of(year, monthIndex + 1)
            if (month == selectedMonth) {
                return@setFragmentResultListener
            }
            selectedMonth = month
            binding.monthSelector.text = selectedMonth.format(monthFormat)
            monthlyBudgetCap = viewModel.getMonthlyBudget(selectedMonth)
            viewModel.loadMonthlyBudget(selectedMonth)
            viewModel.setDashboardMonth(selectedMonth)
        }

        viewModel.refresh()
    }

    private fun showTrendFilterDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_trend_filter, null, false)
        val modeGroup = dialogView.findViewById<com.google.android.material.chip.ChipGroup>(R.id.filter_mode_group)
        val rangeGroup = dialogView.findViewById<com.google.android.material.chip.ChipGroup>(R.id.filter_range_group)
        val cancelButton = dialogView.findViewById<View>(R.id.filter_cancel_button)
        val applyButton = dialogView.findViewById<View>(R.id.filter_apply_button)

        modeGroup.check(
            when (trendDisplayMode) {
                TrendDisplayMode.BOTH -> R.id.filter_mode_both
                TrendDisplayMode.INCOME -> R.id.filter_mode_income
                TrendDisplayMode.EXPENSE -> R.id.filter_mode_expense
            }
        )
        rangeGroup.check(if (selectedWindowDays == 7) R.id.filter_range_7 else R.id.filter_range_15)

        val dialog = createStyledDialog(dialogView)
        cancelButton.setOnClickListener { dialog.dismiss() }
        applyButton.setOnClickListener {
            trendDisplayMode = when (modeGroup.checkedChipId) {
                R.id.filter_mode_income -> TrendDisplayMode.INCOME
                R.id.filter_mode_expense -> TrendDisplayMode.EXPENSE
                else -> TrendDisplayMode.BOTH
            }

            val updatedWindowDays = when (rangeGroup.checkedChipId) {
                R.id.filter_range_7 -> 7
                else -> 15
            }
            val windowChanged = selectedWindowDays != updatedWindowDays
            selectedWindowDays = updatedWindowDays

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
        val totalExpense = dashboard.totalExpense
        val totalIncome = dashboard.totalIncome
        val remaining = totalIncome - totalExpense
        val monthlyExpense = viewModel.getMonthlyExpense(selectedMonth)

        binding.expenseTotalText.text = buildHeaderAmount(totalExpense)
        binding.incomeTotalText.text = buildPlainAmount(totalIncome)
        binding.incomeTotalText.setTextColor(requireContext().getColor(R.color.text_primary))
        binding.remainingTotalText.text = buildPlainAmount(remaining, showPositiveSign = true)
        binding.remainingTotalText.setTextColor(requireContext().getColor(R.color.text_primary))

        val budgetCap = monthlyBudgetCap
        if (budgetCap == null || budgetCap <= 0.0) {
            binding.budgetPercentText.text = "--"
            binding.budgetProgressText.text = getString(R.string.home_budget_not_set)
            binding.budgetProgressBar.progress = 0
            binding.budgetProgressBar.setIndicatorColor(
                requireContext().getColor(R.color.text_hint)
            )
        } else {
            val safeBudget = budgetCap
            val overBudget = monthlyExpense > safeBudget
            val budgetPercent = ((monthlyExpense / safeBudget) * 100.0)
                .roundToInt()
                .coerceAtLeast(0)
            val budgetProgress = budgetPercent.coerceAtMost(100)
            binding.budgetPercentText.text = "${budgetPercent}%"
            binding.budgetProgressText.text = getString(
                R.string.home_budget_of_format,
                amountFormat.format(safeBudget)
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
        val inputLayout = dialogView.findViewById<TextInputLayout>(R.id.budget_input_layout)
        val input = dialogView.findViewById<TextInputEditText>(R.id.budget_input)
        val cancelButton = dialogView.findViewById<View>(R.id.cancel_button)
        val saveButton = dialogView.findViewById<View>(R.id.save_button)

        inputLayout.hint = getString(
            R.string.home_budget_for_month_format,
            selectedMonth.format(monthFormat)
        )
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
            viewModel.setMonthlyBudget(selectedMonth, value)
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

    private fun openMonthPicker() {
        MonthPickerDialogFragment
            .newInstance(selectedMonth.year, selectedMonth.monthValue - 1)
            .show(parentFragmentManager, "home_month_picker")
    }

    private fun showTransactionActions(transactionId: Long) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_transaction_actions, null, false)
        val transactionTimeText = dialogView.findViewById<android.widget.TextView>(R.id.transaction_time_text)
        val editAction = dialogView.findViewById<View>(R.id.edit_action)
        val deleteAction = dialogView.findViewById<View>(R.id.delete_action)
        val dialog = createStyledDialog(dialogView)
        val recordedAt = viewModel.getTransaction(transactionId)
            ?.let { formatTransactionTime(it.timestampMillis) }
            ?: "--"
        transactionTimeText.text = getString(R.string.home_recorded_at_format, recordedAt)

        editAction.setOnClickListener {
            dialog.dismiss()
            (activity as? MainActivity)?.openTransactionEditor(transactionId = transactionId)
        }
        deleteAction.setOnClickListener {
            dialog.dismiss()
            showDeleteTransactionConfirmation(transactionId)
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

    override fun onResume() {
        super.onResume()
        applyHomeSystemBars()
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
        fun newInstance(): HomeFragment = HomeFragment()
    }

    private enum class TrendDisplayMode {
        BOTH,
        INCOME,
        EXPENSE
    }
}
