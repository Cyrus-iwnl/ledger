package com.example.account.ui.period

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.GridLayout
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.WindowCompat
import androidx.fragment.app.DialogFragment
import com.example.account.R
import com.example.account.databinding.DialogPeriodPickerBinding
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

class PeriodPickerDialogFragment : DialogFragment() {

    private var _binding: DialogPeriodPickerBinding? = null
    private val binding get() = _binding!!

    private val mode: String
        get() = requireArguments().getString(ARG_MODE) ?: MODE_MONTH

    private val requestKey: String
        get() = requireArguments().getString(ARG_REQUEST_KEY) ?: DEFAULT_REQUEST_KEY

    private val selectedYear: Int
        get() = requireArguments().getInt(ARG_SELECTED_YEAR)

    private val selectedMonthIndex: Int
        get() = requireArguments().getInt(ARG_SELECTED_MONTH_INDEX)

    private val titleText: String
        get() = requireArguments().getString(ARG_TITLE).orEmpty()

    private val closeText: String
        get() = requireArguments().getString(ARG_CLOSE_TEXT).orEmpty()

    private val allText: String
        get() = requireArguments().getString(ARG_ALL_TEXT).orEmpty()

    private val yearText: String
        get() = requireArguments().getString(ARG_YEAR_TEXT).orEmpty()

    private val enableAllToggle: Boolean
        get() = requireArguments().getBoolean(ARG_ENABLE_ALL_TOGGLE, false)

    private val selectedScopeMode: String
        get() = requireArguments().getString(ARG_SELECTED_SCOPE_MODE) ?: MODE_MONTH

    private val localeTag: String
        get() = requireArguments().getString(ARG_LOCALE_TAG) ?: "en"

    private val monthWord: String
        get() = requireArguments().getString(ARG_MONTH_WORD) ?: "Month"

    private val numberLocale: Locale by lazy(LazyThreadSafetyMode.NONE) {
        when (localeTag) {
            "zh-CN" -> Locale.SIMPLIFIED_CHINESE
            "zh-TW" -> Locale.TRADITIONAL_CHINESE
            else -> Locale.US
        }
    }

    private var displayedYear: Int = 0
    private var activeScopeMode: String = MODE_MONTH

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogPeriodPickerBinding.inflate(LayoutInflater.from(requireContext()))
        displayedYear = requireArguments().getInt(ARG_DISPLAY_YEAR, selectedYear)
        activeScopeMode = if (enableAllToggle) selectedScopeMode else mode

        applyStaticText()
        applyStaticStyles()
        bindListeners()
        renderOptions()

        binding.periodPickerCard.post {
            if (!isAdded || _binding == null) return@post
            binding.periodPickerCard.updateLayoutParams<ViewGroup.LayoutParams> {
                width = minOf(binding.root.width - dpInt(48), dpInt(448))
            }
        }

        return Dialog(requireContext(), android.R.style.Theme_Translucent_NoTitleBar).apply {
            setContentView(binding.root)
            setCancelable(true)
            setCanceledOnTouchOutside(true)
            window?.apply {
                clearFlags(
                    WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
                        WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
                )
                addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                statusBarColor = ContextCompat.getColor(requireContext(), R.color.app_background)
                navigationBarColor = ContextCompat.getColor(requireContext(), R.color.app_background)
                WindowCompat.getInsetsController(this, decorView).apply {
                    isAppearanceLightStatusBars = true
                    isAppearanceLightNavigationBars = true
                }
            }
        }
    }

    override fun onDestroy() {
        _binding = null
        super.onDestroy()
    }

    private fun applyStaticText() {
        binding.periodPickerTitle.text = titleText
        val showScopeToggle = mode == MODE_MONTH && enableAllToggle
        binding.periodPickerTitle.isVisible = !showScopeToggle
        binding.periodPickerScopeGroup.isVisible = showScopeToggle
        binding.periodPickerMonthToggle.text = titleText
        binding.periodPickerYearToggle.text = yearText
        binding.periodPickerAllToggle.text = allText
    }

    private fun applyStaticStyles() {
        val white = color(R.color.insights_surface_container_lowest)
        val surfaceLow = color(R.color.insights_surface_container_low)
        val surfaceVariant = color(R.color.insights_surface_variant)
        background(binding.periodPickerNav, surfaceLow, 12f)
        background(binding.periodPickerPrev, white, 12f)
        background(binding.periodPickerNext, white, 12f)
        styleScopeToggles(surfaceLow, surfaceVariant)
    }

    private fun bindListeners() {
        binding.periodPickerOverlay.setOnClickListener { dismissAllowingStateLoss() }
        binding.periodPickerCard.setOnClickListener { }
        binding.periodPickerMonthToggle.setOnClickListener {
            if (activeScopeMode == MODE_MONTH) return@setOnClickListener
            activeScopeMode = MODE_MONTH
            val surfaceLow = color(R.color.insights_surface_container_low)
            val surfaceVariant = color(R.color.insights_surface_variant)
            styleScopeToggles(surfaceLow, surfaceVariant)
            renderOptions()
        }
        binding.periodPickerYearToggle.setOnClickListener {
            if (activeScopeMode == MODE_YEAR) return@setOnClickListener
            activeScopeMode = MODE_YEAR
            val surfaceLow = color(R.color.insights_surface_container_low)
            val surfaceVariant = color(R.color.insights_surface_variant)
            styleScopeToggles(surfaceLow, surfaceVariant)
            renderOptions()
        }
        binding.periodPickerAllToggle.setOnClickListener {
            if (activeScopeMode == MODE_ALL) return@setOnClickListener
            activeScopeMode = MODE_ALL
            val surfaceLow = color(R.color.insights_surface_container_low)
            val surfaceVariant = color(R.color.insights_surface_variant)
            styleScopeToggles(surfaceLow, surfaceVariant)
            renderOptions()
        }
        binding.periodPickerConfirmAll.setOnClickListener {
            publishAllResult()
            dismissAllowingStateLoss()
        }
        binding.periodPickerPrev.setOnClickListener {
            displayedYear -= 1
            renderOptions()
        }
        binding.periodPickerNext.setOnClickListener {
            displayedYear += 1
            renderOptions()
        }
    }

    private fun renderOptions() {
        val effectiveMode = if (enableAllToggle) activeScopeMode else mode
        when (effectiveMode) {
            MODE_MONTH -> {
                binding.periodPickerNav.isVisible = true
                binding.periodPickerOptions.isVisible = true
                binding.periodPickerConfirmAll.isVisible = false
                renderMonthOptions()
            }
            MODE_YEAR -> {
                binding.periodPickerNav.isVisible = false
                binding.periodPickerOptions.isVisible = true
                binding.periodPickerConfirmAll.isVisible = false
                renderYearOptions()
            }
            MODE_ALL -> {
                binding.periodPickerNav.isVisible = false
                binding.periodPickerOptions.isVisible = false
                binding.periodPickerOptions.removeAllViews()
                binding.periodPickerConfirmAll.isVisible = true
            }
        }
    }

    private fun renderYearOptions() {
        val years = requireArguments().getIntArray(ARG_AVAILABLE_YEARS)?.toList().orEmpty()
            .ifEmpty { listOf(selectedYear) }
        binding.periodPickerOptions.removeAllViews()
        years.forEach { year ->
            binding.periodPickerOptions.addView(
                optionButton(
                    label = year.toString(),
                    active = year == selectedYear
                ) {
                    publishResult(MODE_YEAR, year, selectedMonthIndex)
                }
            )
        }
    }

    private fun renderMonthOptions() {
        binding.periodPickerNavLabel.text = displayedYear.toString()
        binding.periodPickerOptions.removeAllViews()
        repeat(12) { monthIndex ->
            binding.periodPickerOptions.addView(
                optionButton(
                    label = monthLabel(monthIndex, displayedYear),
                    active = displayedYear == selectedYear && monthIndex == selectedMonthIndex
                ) {
                    publishResult(MODE_MONTH, displayedYear, monthIndex)
                }
            )
        }
    }

    private fun publishResult(resultMode: String, year: Int, monthIndex: Int) {
        parentFragmentManager.setFragmentResult(
            requestKey,
            Bundle().apply {
                putString(RESULT_MODE, resultMode)
                putInt(RESULT_YEAR, year)
                putInt(RESULT_MONTH_INDEX, monthIndex)
                putBoolean(RESULT_IS_ALL, false)
            }
        )
        dismissAllowingStateLoss()
    }

    private fun publishAllResult() {
        parentFragmentManager.setFragmentResult(
            requestKey,
            Bundle().apply {
                putString(RESULT_MODE, MODE_ALL)
                putInt(RESULT_YEAR, selectedYear)
                putInt(RESULT_MONTH_INDEX, selectedMonthIndex)
                putBoolean(RESULT_IS_ALL, true)
            }
        )
    }

    private fun styleScopeToggles(surfaceLow: Int, surfaceVariant: Int) {
        if (!binding.periodPickerScopeGroup.isVisible) {
            return
        }
        background(binding.periodPickerScopeGroup, surfaceLow, 999f)
        val monthActive = activeScopeMode == MODE_MONTH
        val yearActive = activeScopeMode == MODE_YEAR
        val allActive = activeScopeMode == MODE_ALL
        background(
            view = binding.periodPickerMonthToggle,
            fillColor = if (monthActive) color(R.color.insights_primary) else Color.TRANSPARENT,
            radiusDp = 999f,
            strokeColor = if (monthActive) Color.TRANSPARENT else Color.TRANSPARENT
        )
        binding.periodPickerMonthToggle.setTextColor(
            if (monthActive) color(R.color.insights_on_primary) else color(R.color.insights_on_surface)
        )
        binding.periodPickerMonthToggle.typeface = Typeface.create(
            ResourcesCompat.getFont(requireContext(), R.font.inter_family),
            if (monthActive) 700 else 600,
            false
        )

        background(
            view = binding.periodPickerYearToggle,
            fillColor = if (yearActive) color(R.color.insights_primary) else Color.TRANSPARENT,
            radiusDp = 999f,
            strokeColor = if (yearActive) Color.TRANSPARENT else Color.TRANSPARENT
        )
        binding.periodPickerYearToggle.setTextColor(
            if (yearActive) color(R.color.insights_on_primary) else color(R.color.insights_on_surface)
        )
        binding.periodPickerYearToggle.typeface = Typeface.create(
            ResourcesCompat.getFont(requireContext(), R.font.inter_family),
            if (yearActive) 700 else 600,
            false
        )

        background(
            view = binding.periodPickerAllToggle,
            fillColor = if (allActive) color(R.color.insights_primary) else Color.TRANSPARENT,
            radiusDp = 999f,
            strokeColor = if (allActive) Color.TRANSPARENT else Color.TRANSPARENT
        )
        binding.periodPickerAllToggle.setTextColor(
            if (allActive) color(R.color.insights_on_primary) else color(R.color.insights_on_surface)
        )
        binding.periodPickerAllToggle.typeface = Typeface.create(
            ResourcesCompat.getFont(requireContext(), R.font.inter_family),
            if (allActive) 700 else 600,
            false
        )
    }

    private fun optionButton(label: String, active: Boolean, onClick: () -> Unit): View {
        return AppCompatButton(requireContext()).apply {
            layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dpInt(6), dpInt(6), dpInt(6), dpInt(6))
            }
            minWidth = 0
            minimumHeight = dpInt(48)
            stateListAnimator = null
            elevation = 0f
            translationZ = 0f
            includeFontPadding = false
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create(ResourcesCompat.getFont(requireContext(), R.font.inter_family), 700, false)
            isAllCaps = false
            background = roundedDrawable(
                fillColor = if (active) color(R.color.insights_primary) else color(R.color.insights_surface_container_low),
                radiusDp = 16f
            )
            setTextColor(if (active) color(R.color.insights_on_primary) else color(R.color.insights_on_surface))
            setPadding(dpInt(12), dpInt(12), dpInt(12), dpInt(12))
            setOnClickListener { onClick() }
        }
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
            "${monthIndex + 1}$monthWord"
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

    companion object {
        const val RESULT_MODE = "mode"
        const val RESULT_YEAR = "year"
        const val RESULT_MONTH_INDEX = "month_index"
        const val RESULT_IS_ALL = "is_all"

        const val MODE_MONTH = "month"
        const val MODE_YEAR = "year"
        const val MODE_ALL = "all"

        private const val DEFAULT_REQUEST_KEY = "period_picker_result"
        private const val ARG_REQUEST_KEY = "request_key"
        private const val ARG_MODE = "mode"
        private const val ARG_SELECTED_YEAR = "selected_year"
        private const val ARG_SELECTED_MONTH_INDEX = "selected_month_index"
        private const val ARG_DISPLAY_YEAR = "display_year"
        private const val ARG_AVAILABLE_YEARS = "available_years"
        private const val ARG_LOCALE_TAG = "locale_tag"
        private const val ARG_MONTH_WORD = "month_word"
        private const val ARG_TITLE = "title"
        private const val ARG_CLOSE_TEXT = "close_text"
        private const val ARG_ENABLE_ALL_TOGGLE = "enable_all_toggle"
        private const val ARG_SELECTED_SCOPE_MODE = "selected_scope_mode"
        private const val ARG_ALL_TEXT = "all_text"
        private const val ARG_YEAR_TEXT = "year_text"

        fun newMonthPicker(
            requestKey: String,
            selectedYear: Int,
            selectedMonthIndex: Int,
            displayYear: Int,
            localeTag: String,
            monthWord: String,
            title: String,
            closeText: String,
            enableAllToggle: Boolean = false,
            selectedScopeMode: String = MODE_MONTH,
            allText: String = "",
            yearText: String = "",
            availableYears: IntArray = intArrayOf()
        ): PeriodPickerDialogFragment {
            return PeriodPickerDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_REQUEST_KEY, requestKey)
                    putString(ARG_MODE, MODE_MONTH)
                    putInt(ARG_SELECTED_YEAR, selectedYear)
                    putInt(ARG_SELECTED_MONTH_INDEX, selectedMonthIndex)
                    putInt(ARG_DISPLAY_YEAR, displayYear)
                    putString(ARG_LOCALE_TAG, localeTag)
                    putString(ARG_MONTH_WORD, monthWord)
                    putString(ARG_TITLE, title)
                    putString(ARG_CLOSE_TEXT, closeText)
                    putBoolean(ARG_ENABLE_ALL_TOGGLE, enableAllToggle)
                    putString(ARG_SELECTED_SCOPE_MODE, selectedScopeMode)
                    putString(ARG_ALL_TEXT, allText)
                    putString(ARG_YEAR_TEXT, yearText)
                    if (availableYears.isNotEmpty()) {
                        putIntArray(ARG_AVAILABLE_YEARS, availableYears)
                    }
                }
            }
        }

        fun newYearPicker(
            requestKey: String,
            selectedYear: Int,
            selectedMonthIndex: Int,
            availableYears: IntArray,
            title: String,
            closeText: String
        ): PeriodPickerDialogFragment {
            return PeriodPickerDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_REQUEST_KEY, requestKey)
                    putString(ARG_MODE, MODE_YEAR)
                    putInt(ARG_SELECTED_YEAR, selectedYear)
                    putInt(ARG_SELECTED_MONTH_INDEX, selectedMonthIndex)
                    putIntArray(ARG_AVAILABLE_YEARS, availableYears)
                    putString(ARG_TITLE, title)
                    putString(ARG_CLOSE_TEXT, closeText)
                }
            }
        }
    }
}
