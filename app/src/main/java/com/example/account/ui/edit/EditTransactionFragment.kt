package com.example.account.ui.edit

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import android.app.Dialog
import android.graphics.Typeface
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.WindowCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.example.account.MainActivity
import com.example.account.R
import com.example.account.data.CurrencyCode
import com.example.account.data.CategoryLocalizer
import com.example.account.data.LedgerCategory
import com.example.account.data.LedgerViewModel
import com.example.account.data.TransactionDraft
import com.example.account.data.TransactionType
import com.example.account.databinding.FragmentTransactionBinding
import com.example.account.ui.DialogFactory
import com.google.android.material.button.MaterialButton
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.math.BigDecimal
import java.util.Locale

class EditTransactionFragment : Fragment() {

    private var _binding: FragmentTransactionBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: LedgerViewModel
    private var transactionId: Long? = null
    private var selectedType: TransactionType = TransactionType.EXPENSE
    private var selectedCategoryId: String? = null
    private var selectedDateMillis: Long = System.currentTimeMillis()
    private var selectedCurrency: CurrencyCode = CurrencyCode.CNY
    private var amountText: String = "0"
    private var pendingAmountText: String? = null
    private var pendingOperator: AmountOperator? = null
    private var shouldStartNewOperand: Boolean = false
    private var isTypeAnimating: Boolean = false
    private var currentCategories: List<LedgerCategory> = emptyList()
    private val categoryCells = mutableMapOf<String, CategoryCell>()
    private var swipeStartX: Float = 0f
    private var swipeStartY: Float = 0f
    private val repeatBackspace = object : Runnable {
        override fun run() {
            if (_binding == null || !binding.keyBackspace.isPressed) {
                return
            }
            backspace()
            binding.keyBackspace.postDelayed(this, BACKSPACE_REPEAT_INTERVAL_MS)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTransactionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(
            requireActivity(),
            LedgerViewModel.Factory(requireActivity().application)
        )[LedgerViewModel::class.java]

        transactionId = arguments?.getLong(ARG_TRANSACTION_ID)?.takeIf { it > 0 }
        selectedType = TransactionType.valueOf(
            arguments?.getString(ARG_DEFAULT_TYPE) ?: TransactionType.EXPENSE.name
        )
        selectedDateMillis = arguments?.getLong(ARG_DATE_MILLIS)?.takeIf { it > 0 } ?: System.currentTimeMillis()

        applySystemBarColors(
            statusBarColor = ContextCompat.getColor(requireContext(), R.color.app_background),
            navigationBarColor = ContextCompat.getColor(requireContext(), R.color.app_background)
        )
        WindowCompat.getInsetsController(requireActivity().window, binding.root).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        binding.backButton.setOnClickListener {
            activity?.onBackPressedDispatcher?.onBackPressed()
        }

        binding.deleteButton.setOnClickListener {
            transactionId?.let { id ->
                showDeleteTransactionConfirmation(id)
            }
        }

        binding.editCategoryButton.setOnClickListener {
            (activity as? MainActivity)?.openCategoryManage(selectedType)
        }

        binding.expenseButton.setOnClickListener { setType(TransactionType.EXPENSE) }
        binding.incomeButton.setOnClickListener { setType(TransactionType.INCOME) }

        binding.timeCard.setOnClickListener {
            openDateTimePicker()
        }
        binding.currencySymbol.setOnClickListener {
            openCurrencyPicker()
        }

        setupSwipeToSwitchType()
        binding.categoryList.setOnTouchListener(createCategoryTouchListener(handleClick = false))
        setKeyHandlers()
        setupKeypadFeedback()
        binding.typeToggle.post { syncTypeToggleThumb(animated = false) }

        val draft = transactionId?.let { viewModel.getDraftForTransaction(it) } ?: TransactionDraft(
            transactionId = null,
            type = selectedType,
            categoryId = viewModel.categoriesFor(selectedType).firstOrNull()?.id.orEmpty(),
            amountText = "0",
            currency = viewModel.getLastUsedCurrency(),
            note = "",
            dateMillis = selectedDateMillis
        )
        applyDraft(draft)

        binding.keySave.setOnClickListener {
            saveTransaction(closeAfterSave = true)
        }

        updateHeaderActionButtons()
    }

    private fun applyDraft(draft: TransactionDraft) {
        transactionId = draft.transactionId
        selectedType = draft.type
        selectedDateMillis = draft.dateMillis
        selectedCurrency = draft.currency
        amountText = normalizeAmount(draft.amountText)
        clearPendingCalculation()
        binding.noteInput.setText(draft.note)
        updateTypeToggleUi()
        updateDateButtonText()
        updateCurrencyButtonText()
        updateAmountPreview()
        updatePrimaryActionUi()
        loadCategories(selectedCategoryId = draft.categoryId)
        binding.typeToggle.post { syncTypeToggleThumb(animated = false) }
        updateHeaderActionButtons()
    }

    private fun updateHeaderActionButtons() {
        val isEditingTransaction = transactionId != null
        binding.deleteButton.visibility = if (isEditingTransaction) View.VISIBLE else View.GONE
        binding.editCategoryButton.visibility = if (isEditingTransaction) View.GONE else View.VISIBLE
    }

    private fun loadCategories(selectedCategoryId: String? = null) {
        currentCategories = viewModel.categoriesFor(selectedType)
        val validIds = currentCategories.mapTo(mutableSetOf()) { it.id }
        this.selectedCategoryId = when {
            selectedCategoryId != null && selectedCategoryId in validIds -> selectedCategoryId
            this.selectedCategoryId != null && this.selectedCategoryId in validIds -> this.selectedCategoryId
            else -> currentCategories.firstOrNull()?.id
        }
        renderCategoryGrid()
    }

    private fun renderCategoryGrid() {
        categoryCells.clear()
        binding.categoryList.removeAllViews()
        binding.categoryList.weightSum = CATEGORY_ROW_COUNT.toFloat()
        repeat(CATEGORY_ROW_COUNT) { rowIndex ->
            val rowLayout = LinearLayout(requireContext()).apply {
                isBaselineAligned = false
                gravity = android.view.Gravity.CENTER
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
                weightSum = CATEGORY_COLUMN_COUNT.toFloat()
                setOnTouchListener(createCategoryTouchListener(handleClick = false))
            }

            repeat(CATEGORY_COLUMN_COUNT) { columnIndex ->
                val index = rowIndex * CATEGORY_COLUMN_COUNT + columnIndex
                val category = currentCategories.getOrNull(index)
                if (category == null) {
                    rowLayout.addView(
                        Space(requireContext()).apply {
                            layoutParams = createCategoryCellLayoutParams()
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        }
                    )
                    return@repeat
                }

                val itemView = layoutInflater.inflate(R.layout.item_category, rowLayout, false)
                itemView.layoutParams = createCategoryCellLayoutParams()
                val cell = CategoryCell(
                    root = itemView,
                    iconContainer = itemView.findViewById(R.id.category_icon_container),
                    iconImage = itemView.findViewById(R.id.category_icon_image),
                    iconSymbol = itemView.findViewById(R.id.category_icon_symbol),
                    name = itemView.findViewById(R.id.category_name)
                )
                bindCategoryCell(cell, category, category.id == selectedCategoryId)
                itemView.setOnClickListener { selectCategory(category.id) }
                itemView.setOnTouchListener(createCategoryTouchListener(handleClick = true))
                rowLayout.addView(itemView)
                categoryCells[category.id] = cell
            }

            binding.categoryList.addView(rowLayout)
        }
    }

    private fun createCategoryCellLayoutParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.MATCH_PARENT,
            1f
        )
    }

    private fun bindCategoryCell(cell: CategoryCell, category: LedgerCategory, selected: Boolean) {
        val context = cell.root.context
        val localizedName = CategoryLocalizer.displayName(context, category)
        val iconColor = if (selected) {
            category.accentColor
        } else {
            ContextCompat.getColor(context, R.color.editor_on_surface_variant)
        }

        cell.name.text = localizedName
        cell.name.setTextColor(
            if (selected) category.accentColor else ContextCompat.getColor(context, R.color.editor_on_surface_variant)
        )
        cell.iconContainer.background = if (selected) {
            android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(ColorUtils.setAlphaComponent(category.accentColor, 38))
            }
        } else {
            ContextCompat.getDrawable(context, R.drawable.bg_editor_category_icon_unselected)
        }

        val symbolTypeface = resolveSymbolTypeface()
        if (symbolTypeface != null && category.iconGlyph.isNotBlank()) {
            cell.iconSymbol.visibility = View.VISIBLE
            cell.iconImage.visibility = View.GONE
            cell.iconSymbol.typeface = symbolTypeface
            cell.iconSymbol.fontFeatureSettings = "'liga'"
            cell.iconSymbol.text = CategoryLocalizer.normalizeIconGlyph(category.iconGlyph)
            cell.iconSymbol.contentDescription = localizedName
            cell.iconSymbol.setTextColor(iconColor)
        } else {
            cell.iconSymbol.visibility = View.GONE
            cell.iconImage.visibility = View.VISIBLE
            cell.iconImage.setImageResource(category.iconRes)
            cell.iconImage.contentDescription = localizedName
            cell.iconImage.setColorFilter(iconColor)
        }
    }

    private fun selectCategory(categoryId: String) {
        if (selectedCategoryId == categoryId) {
            return
        }
        val previousId = selectedCategoryId
        selectedCategoryId = categoryId
        previousId?.let { previous ->
            val previousCategory = currentCategories.firstOrNull { it.id == previous } ?: return@let
            val previousCell = categoryCells[previous] ?: return@let
            bindCategoryCell(previousCell, previousCategory, selected = false)
        }
        val nextCategory = currentCategories.firstOrNull { it.id == categoryId } ?: return
        val nextCell = categoryCells[categoryId] ?: return
        bindCategoryCell(nextCell, nextCategory, selected = true)
    }

    private fun createCategoryTouchListener(handleClick: Boolean): View.OnTouchListener {
        val touchSlop = ViewConfiguration.get(requireContext()).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        return View.OnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val deltaX = event.rawX - downX
                    val deltaY = event.rawY - downY
                    if (!isTypeAnimating &&
                        kotlin.math.abs(deltaX) >= touchSlop * 2 &&
                        kotlin.math.abs(deltaX) > kotlin.math.abs(deltaY)
                    ) {
                        if (deltaX < 0 && selectedType != TransactionType.INCOME) {
                            binding.typeToggle.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            setType(TransactionType.INCOME)
                            true
                        } else if (deltaX > 0 && selectedType != TransactionType.EXPENSE) {
                            binding.typeToggle.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            setType(TransactionType.EXPENSE)
                            true
                        } else {
                            false
                        }
                    } else if (handleClick &&
                        kotlin.math.abs(deltaX) < touchSlop &&
                        kotlin.math.abs(deltaY) < touchSlop
                    ) {
                        view.performClick()
                        true
                    } else {
                        false
                    }
                }

                MotionEvent.ACTION_CANCEL -> false

                else -> true
            }
        }
    }

    private fun setKeyHandlers() {
        val digitButtons = listOf(
            binding.key0 to "0",
            binding.key1 to "1",
            binding.key2 to "2",
            binding.key3 to "3",
            binding.key4 to "4",
            binding.key5 to "5",
            binding.key6 to "6",
            binding.key7 to "7",
            binding.key8 to "8",
            binding.key9 to "9"
        )
        digitButtons.forEach { (button, value) ->
            button.setOnClickListener { appendDigit(value) }
        }
        binding.keyDot.setOnClickListener { appendDot() }
        binding.keyBackspace.setOnClickListener { backspace() }
        binding.keyPlus.setOnClickListener { applyAmountOperator(AmountOperator.ADD) }
        binding.keyMinus.setOnClickListener { applyAmountOperator(AmountOperator.SUBTRACT) }
    }

    private fun setupSwipeToSwitchType() {
        val touchSlop = ViewConfiguration.get(requireContext()).scaledTouchSlop
        attachSwipeSwitchListener(
            binding.typeToggle,
            touchSlop,
            allowTapToggle = true,
            consumeTouches = true
        )
        attachSwipeSwitchListener(binding.headerBar, touchSlop, allowTapToggle = false)
        attachSwipeSwitchListener(binding.inputPanel, touchSlop, allowTapToggle = false)
    }

    private fun attachSwipeSwitchListener(
        view: View,
        touchSlop: Int,
        allowTapToggle: Boolean,
        consumeTouches: Boolean = false
    ) {
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    swipeStartX = event.rawX
                    swipeStartY = event.rawY
                    consumeTouches
                }

                MotionEvent.ACTION_UP -> {
                    val deltaX = event.rawX - swipeStartX
                    val deltaY = event.rawY - swipeStartY
                    if (!isTypeAnimating &&
                        kotlin.math.abs(deltaX) >= touchSlop * 2 &&
                        kotlin.math.abs(deltaX) > kotlin.math.abs(deltaY)
                        ) {
                            if (deltaX < 0 && selectedType != TransactionType.INCOME) {
                                binding.typeToggle.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                setType(TransactionType.INCOME)
                                return@setOnTouchListener true
                            }
                            if (deltaX > 0 && selectedType != TransactionType.EXPENSE) {
                                binding.typeToggle.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                setType(TransactionType.EXPENSE)
                                return@setOnTouchListener true
                            }
                        }
                    if (allowTapToggle) {
                        return@setOnTouchListener handleToggleTap(event.x)
                    }
                    consumeTouches
                }

                MotionEvent.ACTION_CANCEL -> consumeTouches

                else -> consumeTouches
            }
        }
    }

    private fun setupKeypadFeedback() {
        keypadButtons().forEach { button ->
            button.rippleColor = ColorStateList.valueOf(
                ColorUtils.setAlphaComponent(
                    ContextCompat.getColor(
                        requireContext(),
                        if (button.id == R.id.key_save) R.color.editor_on_tertiary else R.color.editor_on_surface
                    ),
                    if (button.id == R.id.key_save) 34 else 18
                )
            )
            if (button.id != R.id.key_backspace) {
                button.setOnTouchListener { v, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    }
                    false
                }
            }
        }
        setupBackspaceRepeat()
    }

    private fun keypadButtons(): List<MaterialButton> {
        return listOf(
            binding.key0,
            binding.key1,
            binding.key2,
            binding.key3,
            binding.key4,
            binding.key5,
            binding.key6,
            binding.key7,
            binding.key8,
            binding.key9,
            binding.keyDot,
            binding.keyBackspace,
            binding.keyMinus,
            binding.keyPlus,
            binding.keySave
        )
    }

    private fun handleToggleTap(tapX: Float): Boolean {
        val targetType = if (tapX <= binding.typeToggle.width / 2f) {
            TransactionType.EXPENSE
        } else {
            TransactionType.INCOME
        }
        if (targetType != selectedType) {
            binding.typeToggle.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            setType(targetType)
        } else {
            binding.typeToggle.performClick()
        }
        return true
    }

    private fun setupBackspaceRepeat() {
        binding.keyBackspace.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    backspace()
                    view.removeCallbacks(repeatBackspace)
                    view.postDelayed(repeatBackspace, ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    view.removeCallbacks(repeatBackspace)
                    true
                }

                else -> false
            }
        }
    }

    private fun setType(type: TransactionType) {
        if (selectedType == type) {
            syncTypeToggleThumb(animated = false)
            return
        }
        selectedType = type
        updateTypeToggleUi()
        loadCategories()
        updateAmountPreview()
        updatePrimaryActionUi()
        syncTypeToggleThumb(animated = true)
    }

    private fun updateTypeToggleUi() {
        val expenseSelected = selectedType == TransactionType.EXPENSE
        binding.expenseButton.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (expenseSelected) R.color.editor_header_chip_selected_text else R.color.editor_header_chip_unselected_text
            )
        )
        binding.incomeButton.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (expenseSelected) R.color.editor_header_chip_unselected_text else R.color.editor_header_chip_selected_text
            )
        )
        binding.expenseButton.setTypeface(
            binding.expenseButton.typeface,
            if (expenseSelected) Typeface.BOLD else Typeface.NORMAL
        )
        binding.incomeButton.setTypeface(
            binding.incomeButton.typeface,
            if (!expenseSelected) Typeface.BOLD else Typeface.NORMAL
        )
    }

    private fun syncTypeToggleThumb(animated: Boolean) {
        val thumb = binding.typeToggleThumb
        val toggleWidth = binding.typeToggle.width
        val paddingHorizontal = binding.typeToggle.paddingStart + binding.typeToggle.paddingEnd
        if (toggleWidth == 0) {
            return
        }
        val availableWidth = toggleWidth - paddingHorizontal
        val thumbWidth = availableWidth / 2
        val params = thumb.layoutParams
        if (params.width != thumbWidth) {
            params.width = thumbWidth
            thumb.layoutParams = params
        }
        val targetTranslation = if (selectedType == TransactionType.EXPENSE) {
            0f
        } else {
            thumbWidth.toFloat()
        }
        if (!animated) {
            thumb.translationX = targetTranslation
            isTypeAnimating = false
            return
        }
        isTypeAnimating = true
        thumb.animate()
            .translationX(targetTranslation)
            .setDuration(180L)
            .withEndAction { isTypeAnimating = false }
            .start()
    }

    private fun appendDigit(digit: String) {
        if (shouldStartNewOperand) {
            amountText = if (digit == "0") "0" else digit
            shouldStartNewOperand = false
            updateAmountPreview()
            return
        }
        val nextAmount = when {
            amountText == "0" -> digit
            amountText.contains(".") && amountText.substringAfter(".").length >= MAX_DECIMAL_DIGITS -> amountText
            !amountText.contains(".") && amountText.length >= MAX_INTEGER_DIGITS -> amountText
            else -> amountText + digit
        }
        if (nextAmount != amountText) {
            amountText = nextAmount
        }
        updateAmountPreview()
    }

    private fun appendDot() {
        if (shouldStartNewOperand) {
            amountText = "0."
            shouldStartNewOperand = false
            updateAmountPreview()
            return
        }
        if (!amountText.contains(".")) {
            amountText = if (amountText.isBlank()) "0." else "$amountText."
            updateAmountPreview()
        }
    }

    private fun backspace() {
        if (shouldStartNewOperand && pendingAmountText != null && pendingOperator != null) {
            amountText = pendingAmountText ?: "0"
            clearPendingCalculation()
            updateAmountPreview()
            return
        }
        if (!shouldStartNewOperand && pendingAmountText != null && pendingOperator != null && amountText == "0") {
            amountText = pendingAmountText ?: "0"
            clearPendingCalculation()
            updateAmountPreview()
            return
        }
        amountText = when {
            amountText.length <= 1 -> "0"
            else -> amountText.dropLast(1).ifBlank { "0" }.trimEnd('.')
        }
        if (amountText.endsWith(".")) {
            amountText = amountText.dropLast(1)
        }
        if (amountText.isBlank()) {
            amountText = "0"
        }
        updateAmountPreview()
    }

    private fun clearAmount() {
        amountText = "0"
        clearPendingCalculation()
        updateAmountPreview()
    }

    private fun updatePrimaryActionUi() {
        val saveColor = ContextCompat.getColor(
            requireContext(),
            R.color.editor_save_green
        )
        binding.keySave.backgroundTintList = ColorStateList.valueOf(saveColor)
    }

    private fun normalizeAmount(text: String): String {
        val parsed = parseEditorAmount(text) ?: BigDecimal.ZERO
        val normalized = parsed.stripTrailingZeros()
        return if (normalized.scale() < 0) {
            normalized.setScale(0).toPlainString()
        } else {
            normalized.toPlainString()
        }
    }

    private fun updateAmountPreview() {
        binding.amountPreview.text = formatAmountForEditor()
        val amountColor = ContextCompat.getColor(
            requireContext(),
            if (selectedType == TransactionType.EXPENSE) R.color.editor_tertiary else R.color.income_color
        )
        binding.amountPreview.setTextColor(amountColor)
    }

    private fun formatAmountForEditor(): String {
        val current = if (amountText.isBlank()) "0" else amountText
        val left = pendingAmountText
        val operator = pendingOperator
        return when {
            left == null || operator == null -> current
            shouldStartNewOperand -> "$left${operator.symbol}"
            else -> "$left${operator.symbol}$current"
        }
    }

    private fun updateDateButtonText() {
        val display = formatDateTimeDisplay(selectedDateMillis)
        binding.dateButton.text = display.text
        if (display.isRelativeDay) {
            binding.dateButton.gravity = android.view.Gravity.CENTER
            binding.dateButton.maxLines = 1
            binding.dateButton.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
        } else {
            binding.dateButton.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            binding.dateButton.maxLines = 2
            binding.dateButton.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
        }
    }

    private fun updateCurrencyButtonText() {
        binding.currencySymbol.text = selectedCurrency.symbol
    }

    private fun openCurrencyPicker() {
        val popup = PopupMenu(
            requireContext(),
            binding.currencySymbol,
            Gravity.END,
            0,
            R.style.Widget_Account_CurrencyPopupMenu
        )
        CurrencyCode.values().forEachIndexed { index, currency ->
            popup.menu.add(
                0,
                index,
                index,
                getString(currencyNameRes(currency), currency.symbol)
            )
        }
        popup.setOnMenuItemClickListener { menuItem ->
            val target = CurrencyCode.values().getOrNull(menuItem.itemId) ?: return@setOnMenuItemClickListener true
            selectedCurrency = target
            updateCurrencyButtonText()
            true
        }
        applyPopupHorizontalOffset(popup, 10)
        applyPopupMaxHeight(popup, CURRENCY_POPUP_MAX_HEIGHT_DP)
        popup.show()
        applyPopupListMaxHeightAfterShow(popup, CURRENCY_POPUP_MAX_HEIGHT_DP)
    }

    private fun currencyNameRes(currency: CurrencyCode): Int {
        return when (currency) {
            CurrencyCode.CNY -> R.string.settings_currency_name_cny
            CurrencyCode.HKD -> R.string.settings_currency_name_hkd
            CurrencyCode.TWD -> R.string.settings_currency_name_twd
            CurrencyCode.USD -> R.string.settings_currency_name_usd
            CurrencyCode.GBP -> R.string.settings_currency_name_gbp
            CurrencyCode.EUR -> R.string.settings_currency_name_eur
            CurrencyCode.JPY -> R.string.settings_currency_name_jpy
            CurrencyCode.KRW -> R.string.settings_currency_name_krw
            CurrencyCode.AUD -> R.string.settings_currency_name_aud
            CurrencyCode.CAD -> R.string.settings_currency_name_cad
            CurrencyCode.SGD -> R.string.settings_currency_name_sgd
            CurrencyCode.CHF -> R.string.settings_currency_name_chf
            CurrencyCode.THB -> R.string.settings_currency_name_thb
            CurrencyCode.MOP -> R.string.settings_currency_name_mop
            CurrencyCode.INR -> R.string.settings_currency_name_inr
            CurrencyCode.AED -> R.string.settings_currency_name_aed
            CurrencyCode.SAR -> R.string.settings_currency_name_sar
            CurrencyCode.RUB -> R.string.settings_currency_name_rub
            CurrencyCode.BRL -> R.string.settings_currency_name_brl
            CurrencyCode.MXN -> R.string.settings_currency_name_mxn
        }
    }

    private fun applyPopupHorizontalOffset(popup: PopupMenu, offsetDp: Int) {
        runCatching {
            val helperField = PopupMenu::class.java.getDeclaredField("mPopup")
            helperField.isAccessible = true
            val helper = helperField.get(popup)
            val offsetPx = (offsetDp * resources.displayMetrics.density).toInt()
            helper.javaClass
                .getDeclaredMethod("setHorizontalOffset", Int::class.javaPrimitiveType)
                .invoke(helper, offsetPx)
        }
    }

    private fun applyPopupMaxHeight(popup: PopupMenu, maxHeightDp: Int) {
        runCatching {
            val helperField = PopupMenu::class.java.getDeclaredField("mPopup")
            helperField.isAccessible = true
            val helper = helperField.get(popup)
            val maxHeightPx = (maxHeightDp * resources.displayMetrics.density).toInt()
            val getPopupMethod = helper.javaClass.getDeclaredMethod("getPopup").apply {
                isAccessible = true
            }
            val menuPopup = getPopupMethod.invoke(helper)
            val directMethod = menuPopup.javaClass.methods.firstOrNull { method ->
                method.name == "setHeight" && method.parameterTypes.size == 1
            }
            if (directMethod != null) {
                directMethod.invoke(menuPopup, maxHeightPx)
                return@runCatching
            }
            val popupWindowField = menuPopup.javaClass.getDeclaredField("mPopup").apply {
                isAccessible = true
            }
            val popupWindow = popupWindowField.get(menuPopup)
            popupWindow.javaClass
                .getMethod("setHeight", Int::class.javaPrimitiveType)
                .invoke(popupWindow, maxHeightPx)
        }
    }

    private fun applyPopupListMaxHeightAfterShow(popup: PopupMenu, maxHeightDp: Int) {
        runCatching {
            val helperField = PopupMenu::class.java.getDeclaredField("mPopup")
            helperField.isAccessible = true
            val helper = helperField.get(popup)
            val getPopupMethod = helper.javaClass.getDeclaredMethod("getPopup").apply {
                isAccessible = true
            }
            val menuPopup = getPopupMethod.invoke(helper)
            val getListViewMethod = menuPopup.javaClass.methods.firstOrNull { method ->
                method.name == "getListView" && method.parameterTypes.isEmpty()
            } ?: return@runCatching
            val listView = getListViewMethod.invoke(menuPopup) as? android.widget.ListView ?: return@runCatching
            val maxHeightPx = (maxHeightDp * resources.displayMetrics.density).toInt()
            val currentParams = listView.layoutParams ?: return@runCatching
            if (currentParams.height <= 0 || currentParams.height > maxHeightPx) {
                currentParams.height = maxHeightPx
                listView.layoutParams = currentParams
                listView.requestLayout()
            }
        }
    }

    private fun formatDateTime(millis: Long): String {
        return formatDateTimeDisplay(millis).text
    }

    private fun formatDateTimeDisplay(millis: Long): DateTimeDisplay {
        val zoneId = ZoneId.systemDefault()
        val dateTime = Instant.ofEpochMilli(millis).atZone(zoneId)
        val date = dateTime.toLocalDate()
        val today = LocalDate.now(zoneId)
        val isRelativeDay = date == today || date == today.minusDays(1)
        val dateText = when {
            date == today -> getString(R.string.date_today)
            date == today.minusDays(1) -> getString(R.string.date_yesterday)
            else -> date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.getDefault()))
        }
        val timeText = dateTime.format(DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()))
        val text = if (isRelativeDay) {
            "$dateText $timeText"
        } else {
            "$dateText\n$timeText"
        }
        return DateTimeDisplay(text = text, isRelativeDay = isRelativeDay)
    }

    private data class DateTimeDisplay(
        val text: String,
        val isRelativeDay: Boolean
    )

    private fun openDateTimePicker() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_transaction_datetime, null, false)
        val yearPicker = dialogView.findViewById<NumberPicker>(R.id.datetime_year_picker)
        val monthPicker = dialogView.findViewById<NumberPicker>(R.id.datetime_month_picker)
        val dayPicker = dialogView.findViewById<NumberPicker>(R.id.datetime_day_picker)
        val nowButton = dialogView.findViewById<View>(R.id.datetime_now_button)
        val hourPicker = dialogView.findViewById<NumberPicker>(R.id.datetime_hour_picker)
        val minutePicker = dialogView.findViewById<NumberPicker>(R.id.datetime_minute_picker)
        val previewText = dialogView.findViewById<TextView>(R.id.datetime_preview_text)
        val cancelButton = dialogView.findViewById<View>(R.id.cancel_button)
        val saveButton = dialogView.findViewById<View>(R.id.save_button)
        val zoneId = ZoneId.systemDefault()
        val dateTime = Instant.ofEpochMilli(selectedDateMillis).atZone(zoneId)

        listOf(nowButton, cancelButton, saveButton).forEach { button ->
            (button as? MaterialButton)?.apply {
                stateListAnimator = null
                elevation = 0f
                translationZ = 0f
            }
        }

        val currentYear = LocalDate.now(zoneId).year
        val minYear = minOf(dateTime.year, currentYear) - 20
        val maxYear = maxOf(dateTime.year, currentYear) + 20

        setupNumberPicker(yearPicker, minYear, maxYear, dateTime.year, padWithZero = false)
        setupNumberPicker(monthPicker, 1, 12, dateTime.monthValue, padWithZero = true)
        setupNumberPicker(
            dayPicker,
            1,
            YearMonth.of(dateTime.year, dateTime.monthValue).lengthOfMonth(),
            dateTime.dayOfMonth,
            padWithZero = true
        )
        setupNumberPicker(hourPicker, 0, 23, dateTime.hour, padWithZero = true)
        setupNumberPicker(minutePicker, 0, 59, dateTime.minute, padWithZero = true)

        fun syncDayRange(targetDay: Int = dayPicker.value) {
            val maxDay = YearMonth.of(yearPicker.value, monthPicker.value).lengthOfMonth()
            dayPicker.wrapSelectorWheel = false
            dayPicker.minValue = 1
            dayPicker.maxValue = maxDay
            dayPicker.value = targetDay.coerceIn(1, maxDay)
            dayPicker.wrapSelectorWheel = true
        }

        fun pickedMillis(): Long {
            val localDate = LocalDate.of(
                yearPicker.value,
                monthPicker.value,
                dayPicker.value
            )
            val hour = hourPicker.value
            val minute = minutePicker.value
            return localDate.atTime(hour, minute, 0)
                .atZone(zoneId)
                .toInstant()
                .toEpochMilli()
        }

        fun refreshPreview() {
            previewText.text = formatDateTime(pickedMillis())
        }

        yearPicker.setOnValueChangedListener { _, _, _ ->
            syncDayRange()
            refreshPreview()
        }
        monthPicker.setOnValueChangedListener { _, _, _ ->
            syncDayRange()
            refreshPreview()
        }
        dayPicker.setOnValueChangedListener { _, _, _ -> refreshPreview() }
        hourPicker.setOnValueChangedListener { _, _, _ -> refreshPreview() }
        minutePicker.setOnValueChangedListener { _, _, _ -> refreshPreview() }
        nowButton.setOnClickListener {
            val now = Instant.now().atZone(zoneId)
            yearPicker.value = now.year
            monthPicker.value = now.monthValue
            syncDayRange(now.dayOfMonth)
            dayPicker.value = now.dayOfMonth.coerceIn(1, dayPicker.maxValue)
            hourPicker.value = now.hour
            minutePicker.value = now.minute
            refreshPreview()
        }
        refreshPreview()

        val dialog = createStyledDialog(dialogView)
        cancelButton.setOnClickListener { dialog.dismiss() }
        saveButton.setOnClickListener {
            selectedDateMillis = pickedMillis()
            updateDateButtonText()
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun setupNumberPicker(
        picker: NumberPicker,
        min: Int,
        max: Int,
        value: Int,
        padWithZero: Boolean
    ) {
        picker.minValue = min
        picker.maxValue = max
        picker.wrapSelectorWheel = true
        picker.value = value.coerceIn(min, max)
        picker.setFormatter { number ->
            if (padWithZero) {
                String.format(Locale.getDefault(), "%02d", number)
            } else {
                number.toString()
            }
        }
        applyCompactPickerDividers(picker)
    }

    private fun applyCompactPickerDividers(picker: NumberPicker) {
        val density = resources.displayMetrics.density
        val compactDividerDistancePx = (30f * density).toInt()
        val compactDividerHeightPx = (1.5f * density).toInt().coerceAtLeast(1)

        runCatching {
            NumberPicker::class.java
                .getDeclaredField("mSelectionDividersDistance")
                .apply { isAccessible = true }
                .setInt(picker, compactDividerDistancePx)
        }
        runCatching {
            NumberPicker::class.java
                .getDeclaredField("mSelectionDividerHeight")
                .apply { isAccessible = true }
                .setInt(picker, compactDividerHeightPx)
        }
        picker.invalidate()
    }

    private fun saveTransaction(closeAfterSave: Boolean) {
        val resolvedAmountText = resolveAmountTextForSave()
        val amount = parseEditorAmount(resolvedAmountText) ?: BigDecimal.ZERO
        if (amount <= BigDecimal.ZERO) {
            Toast.makeText(requireContext(), R.string.invalid_amount, Toast.LENGTH_SHORT).show()
            return
        }
        if (!isWithinRecordAmountLimit(amount)) {
            Toast.makeText(requireContext(), "Amount must be within 8 digits", Toast.LENGTH_SHORT).show()
            return
        }
        val categoryId = selectedCategoryId
            ?: viewModel.categoriesFor(selectedType).firstOrNull()?.id
            ?: return
        val note = binding.noteInput.text?.toString().orEmpty()
        val savedDateMillis = Instant.ofEpochMilli(selectedDateMillis)
            .atZone(ZoneId.systemDefault())
            .withSecond(0)
            .withNano(0)
            .toInstant()
            .toEpochMilli()

        val savedCategoryId = categoryId
        amountText = resolvedAmountText
        clearPendingCalculation()
        viewModel.saveTransaction(
            TransactionDraft(
                transactionId = transactionId,
                type = selectedType,
                categoryId = savedCategoryId,
                amountText = normalizeAmount(resolvedAmountText),
                currency = selectedCurrency,
                note = note,
                dateMillis = savedDateMillis
            )
        )
        if (closeAfterSave) {
            (activity as? MainActivity)?.closeEditorAndReturnHome()
            return
        }

        transactionId = null
        amountText = "0"
        clearPendingCalculation()
        selectedDateMillis = savedDateMillis
        selectedCurrency = viewModel.getLastUsedCurrency()
        binding.noteInput.setText("")
        updateDateButtonText()
        updateCurrencyButtonText()
        updateAmountPreview()
        updatePrimaryActionUi()
        loadCategories(selectedCategoryId = savedCategoryId)
        updateHeaderActionButtons()
        Toast.makeText(
            requireContext(),
            R.string.transaction_saved_ready_next,
            Toast.LENGTH_SHORT
        ).show()
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
            (activity as? MainActivity)?.closeEditorAndReturnHome()
        }

        dialog.show()
    }

    private fun createStyledDialog(dialogView: View): Dialog {
        return DialogFactory.createCardDialog(requireContext(), dialogView)
    }

    private fun resolveSymbolTypeface(): Typeface? {
        if (!symbolTypefaceResolved) {
            symbolTypeface = try {
                ResourcesCompat.getFont(requireContext(), R.font.material_symbols_outlined_static)
            } catch (_: Throwable) {
                null
            }
            symbolTypefaceResolved = true
        }
        return symbolTypeface
    }

    override fun onDestroyView() {
        _binding?.keyBackspace?.removeCallbacks(repeatBackspace)
        context?.let { context ->
            applySystemBarColors(
                statusBarColor = ContextCompat.getColor(context, R.color.app_background),
                navigationBarColor = ContextCompat.getColor(context, R.color.app_background)
            )
        }
        super.onDestroyView()
        _binding = null
    }

    @Suppress("DEPRECATION")
    private fun applySystemBarColors(statusBarColor: Int, navigationBarColor: Int) {
        requireActivity().window.apply {
            this.statusBarColor = statusBarColor
            this.navigationBarColor = navigationBarColor
        }
    }

    private data class CategoryCell(
        val root: View,
        val iconContainer: FrameLayout,
        val iconImage: ImageView,
        val iconSymbol: TextView,
        val name: TextView
    )

    private enum class AmountOperator(val symbol: String) {
        ADD("+"),
        SUBTRACT("-")
    }

    private fun applyAmountOperator(operator: AmountOperator) {
        val currentAmount = parseEditorAmount(amountText) ?: return
        val currentAmountText = normalizeAmount(currentAmount.toPlainString())
        val leftOperandText = pendingAmountText
        val existingOperator = pendingOperator

        if (leftOperandText == null || existingOperator == null) {
            pendingAmountText = currentAmountText
            pendingOperator = operator
            shouldStartNewOperand = true
            updateAmountPreview()
            return
        }

        if (shouldStartNewOperand) {
            pendingOperator = operator
            updateAmountPreview()
            return
        }

        val leftAmount = parseEditorAmount(leftOperandText) ?: currentAmount
        val result = when (existingOperator) {
            AmountOperator.ADD -> leftAmount + currentAmount
            AmountOperator.SUBTRACT -> leftAmount - currentAmount
        }
        amountText = normalizeAmount(result.toPlainString())
        pendingAmountText = amountText
        pendingOperator = operator
        shouldStartNewOperand = true
        updateAmountPreview()
    }

    private fun resolveAmountTextForSave(): String {
        val leftOperandText = pendingAmountText
        val operator = pendingOperator
        if (leftOperandText == null || operator == null) {
            return normalizeAmount(amountText)
        }
        if (shouldStartNewOperand) {
            return normalizeAmount(leftOperandText)
        }
        val left = parseEditorAmount(leftOperandText) ?: BigDecimal.ZERO
        val right = parseEditorAmount(amountText) ?: BigDecimal.ZERO
        val result = when (operator) {
            AmountOperator.ADD -> left + right
            AmountOperator.SUBTRACT -> left - right
        }
        return normalizeAmount(result.toPlainString())
    }

    private fun parseEditorAmount(text: String): BigDecimal? {
        val sanitized = text.trim().removeSuffix(".")
        if (sanitized.isBlank()) {
            return BigDecimal.ZERO
        }
        return sanitized.toBigDecimalOrNull()
    }

    private fun clearPendingCalculation() {
        pendingAmountText = null
        pendingOperator = null
        shouldStartNewOperand = false
    }

    private fun isWithinRecordAmountLimit(amount: BigDecimal): Boolean {
        val integerText = amount.abs().toPlainString().substringBefore('.').trimStart('0')
        val integerDigits = if (integerText.isEmpty()) 1 else integerText.length
        return integerDigits <= MAX_RECORDED_INTEGER_DIGITS
    }

    companion object {
        private const val ARG_TRANSACTION_ID = "transaction_id"
        private const val ARG_DATE_MILLIS = "date_millis"
        private const val ARG_DEFAULT_TYPE = "default_type"
        private const val CATEGORY_COLUMN_COUNT = 5
        private const val CATEGORY_ROW_COUNT = 4
        private const val CATEGORY_SLOT_COUNT = CATEGORY_COLUMN_COUNT * CATEGORY_ROW_COUNT
        private const val MAX_INTEGER_DIGITS = 8
        private const val MAX_RECORDED_INTEGER_DIGITS = 8
        private const val MAX_DECIMAL_DIGITS = 2
        private const val BACKSPACE_REPEAT_INTERVAL_MS = 60L
        private const val CURRENCY_POPUP_MAX_HEIGHT_DP = 300
        private var symbolTypefaceResolved = false
        private var symbolTypeface: Typeface? = null

        fun newInstance(
            transactionId: Long?,
            dateMillis: Long?,
            defaultType: TransactionType
        ): EditTransactionFragment {
            return EditTransactionFragment().apply {
                arguments = Bundle().apply {
                    if (transactionId != null) putLong(ARG_TRANSACTION_ID, transactionId)
                    if (dateMillis != null) putLong(ARG_DATE_MILLIS, dateMillis)
                    putString(ARG_DEFAULT_TYPE, defaultType.name)
                }
            }
        }
    }
}
