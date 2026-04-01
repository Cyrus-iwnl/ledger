package com.example.account.ui.edit

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.widget.DatePicker
import android.widget.NumberPicker
import android.widget.TextView
import android.widget.Toast
import android.app.Dialog
import android.graphics.Typeface
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.WindowCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.account.MainActivity
import com.example.account.R
import com.example.account.data.CurrencyCode
import com.example.account.data.LedgerViewModel
import com.example.account.data.TransactionDraft
import com.example.account.data.TransactionType
import com.example.account.databinding.FragmentTransactionBinding
import com.example.account.ui.DialogFactory
import com.google.android.material.button.MaterialButton
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class EditTransactionFragment : Fragment() {

    private var _binding: FragmentTransactionBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: LedgerViewModel
    private lateinit var categoryAdapter: CategoryAdapter
    private var transactionId: Long? = null
    private var selectedType: TransactionType = TransactionType.EXPENSE
    private var selectedDateMillis: Long = System.currentTimeMillis()
    private var selectedCurrency: CurrencyCode = CurrencyCode.CNY
    private var amountText: String = "0"
    private var isTypeAnimating: Boolean = false
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

        categoryAdapter = CategoryAdapter { category ->
            categoryAdapter.setSelectedCategory(category.id)
        }
        binding.categoryList.layoutManager = GridLayoutManager(requireContext(), 5)
        binding.categoryList.adapter = categoryAdapter
        binding.categoryList.itemAnimator = null

        applySystemBarColors(
            statusBarColor = ContextCompat.getColor(requireContext(), R.color.editor_bg),
            navigationBarColor = ContextCompat.getColor(requireContext(), R.color.editor_keypad_surface)
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

        binding.expenseButton.setOnClickListener { setType(TransactionType.EXPENSE) }
        binding.incomeButton.setOnClickListener { setType(TransactionType.INCOME) }

        binding.dateButton.setOnClickListener {
            openDateTimePicker()
        }
        binding.currencyButton.setOnClickListener {
            openCurrencyPicker()
        }

        setupSwipeToSwitchType()
        setupCategoryListSwipeToSwitchType()
        setKeyHandlers()
        setupKeypadFeedback()
        binding.typeToggle.post { syncTypeToggleThumb(animated = false) }

        val draft = transactionId?.let { viewModel.getDraftForTransaction(it) } ?: TransactionDraft(
            transactionId = null,
            type = selectedType,
            categoryId = viewModel.categoriesFor(selectedType).firstOrNull()?.id.orEmpty(),
            amountText = "0",
            note = "",
            dateMillis = selectedDateMillis
        )
        applyDraft(draft)

        binding.keySave.setOnClickListener {
            saveTransaction(closeAfterSave = true)
        }

        binding.deleteButton.visibility = if (transactionId == null) View.GONE else View.VISIBLE
    }

    private fun applyDraft(draft: TransactionDraft) {
        transactionId = draft.transactionId
        selectedType = draft.type
        selectedDateMillis = draft.dateMillis
        selectedCurrency = draft.currency
        amountText = normalizeAmount(draft.amountText)
        binding.noteInput.setText(draft.note)
        updateTypeToggleUi()
        updateDateButtonText()
        updateCurrencyButtonText()
        updateAmountPreview()
        updatePrimaryActionUi()
        loadCategories(selectedCategoryId = draft.categoryId)
        binding.typeToggle.post { syncTypeToggleThumb(animated = false) }
    }

    private fun loadCategories(selectedCategoryId: String? = null) {
        val categories = viewModel.categoriesFor(selectedType)
        categoryAdapter.submitList(categories)
        val targetId = selectedCategoryId
            ?: categoryAdapter.getSelectedCategory()?.id
            ?: categories.firstOrNull()?.id
        if (targetId != null) {
            categoryAdapter.setSelectedCategory(targetId)
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
        binding.keyClear.setOnClickListener { saveTransaction(closeAfterSave = false) }
        binding.keyPlus.setOnClickListener { setType(TransactionType.INCOME) }
        binding.keyMinus.setOnClickListener { setType(TransactionType.EXPENSE) }
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
        attachSwipeSwitchListener(binding.editorMetaPanel, touchSlop, allowTapToggle = false)
    }

    private fun setupCategoryListSwipeToSwitchType() {
        val touchSlop = ViewConfiguration.get(requireContext()).scaledTouchSlop
        binding.categoryList.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            private var downX = 0f
            private var downY = 0f

            override fun onInterceptTouchEvent(rv: RecyclerView, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
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
                                return true
                            }
                            if (deltaX > 0 && selectedType != TransactionType.EXPENSE) {
                                binding.typeToggle.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                setType(TransactionType.EXPENSE)
                                return true
                            }
                        }
                    }
                }
                return false
            }
        })
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
            binding.keyClear,
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
        if (!amountText.contains(".")) {
            amountText = if (amountText.isBlank()) "0." else "$amountText."
            updateAmountPreview()
        }
    }

    private fun backspace() {
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
        updateAmountPreview()
    }

    private fun updatePrimaryActionUi() {
        val saveColor = ContextCompat.getColor(
            requireContext(),
            if (selectedType == TransactionType.EXPENSE) R.color.editor_tertiary else R.color.income_color
        )
        binding.keySave.backgroundTintList = ColorStateList.valueOf(saveColor)
    }

    private fun normalizeAmount(text: String): String {
        val parsed = text.toDoubleOrNull() ?: 0.0
        return if (parsed % 1.0 == 0.0) parsed.toLong().toString() else parsed.toString()
    }

    private fun updateAmountPreview() {
        binding.amountPreview.text = formatAmountForEditor()
        binding.amountPreview.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (selectedType == TransactionType.EXPENSE) R.color.editor_tertiary else R.color.income_color
            )
        )
    }

    private fun formatAmountForEditor(): String {
        return when {
            amountText.isBlank() -> "0"
            else -> amountText
        }
    }

    private fun updateDateButtonText() {
        binding.dateButton.text = formatDateTime(selectedDateMillis)
    }

    private fun updateCurrencyButtonText() {
        binding.currencyButton.text = selectedCurrency.code
    }

    private fun openCurrencyPicker() {
        val popup = PopupMenu(requireContext(), binding.currencyButton)
        CurrencyCode.values().forEachIndexed { index, currency ->
            popup.menu.add(0, index, index, "${currency.code} (${currency.symbol})")
        }
        popup.setOnMenuItemClickListener { menuItem ->
            val target = CurrencyCode.values().getOrNull(menuItem.itemId) ?: return@setOnMenuItemClickListener true
            selectedCurrency = target
            updateCurrencyButtonText()
            true
        }
        popup.show()
    }

    private fun formatDateTime(millis: Long): String {
        val zoneId = ZoneId.systemDefault()
        val dateTime = Instant.ofEpochMilli(millis).atZone(zoneId)
        val date = dateTime.toLocalDate()
        val today = LocalDate.now(zoneId)
        val dateText = when (date) {
            today -> getString(R.string.date_today)
            today.minusDays(1) -> getString(R.string.date_yesterday)
            else -> date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.getDefault()))
        }
        val timeText = dateTime.format(DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()))
        return "$dateText $timeText"
    }

    private fun openDateTimePicker() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_transaction_datetime, null, false)
        val datePicker = dialogView.findViewById<DatePicker>(R.id.datetime_date_picker)
        val timeToggleButton = dialogView.findViewById<View>(R.id.datetime_time_toggle)
        val timeContainer = dialogView.findViewById<View>(R.id.datetime_time_container)
        val hourPicker = dialogView.findViewById<NumberPicker>(R.id.datetime_hour_picker)
        val minutePicker = dialogView.findViewById<NumberPicker>(R.id.datetime_minute_picker)
        val previewText = dialogView.findViewById<TextView>(R.id.datetime_preview_text)
        val cancelButton = dialogView.findViewById<View>(R.id.cancel_button)
        val saveButton = dialogView.findViewById<View>(R.id.save_button)
        val zoneId = ZoneId.systemDefault()
        val dateTime = Instant.ofEpochMilli(selectedDateMillis).atZone(zoneId)
        var showTimeEditor = false

        datePicker.init(dateTime.year, dateTime.monthValue - 1, dateTime.dayOfMonth, null)
        setupTimePicker(hourPicker, 0, 23, dateTime.hour)
        setupTimePicker(minutePicker, 0, 59, dateTime.minute)

        fun updateTimeUi() {
            timeContainer.visibility = if (showTimeEditor) View.VISIBLE else View.GONE
            if (timeToggleButton is MaterialButton) {
                timeToggleButton.text = if (showTimeEditor) {
                    getString(R.string.dialog_datetime_hide_time)
                } else {
                    getString(R.string.dialog_datetime_add_time)
                }
            }
        }

        fun pickedMillis(): Long {
            val localDate = LocalDate.of(
                datePicker.year,
                datePicker.month + 1,
                datePicker.dayOfMonth
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

        datePicker.setOnDateChangedListener { _, _, _, _ ->
            refreshPreview()
        }
        hourPicker.setOnValueChangedListener { _, _, _ -> refreshPreview() }
        minutePicker.setOnValueChangedListener { _, _, _ -> refreshPreview() }
        timeToggleButton.setOnClickListener {
            showTimeEditor = !showTimeEditor
            updateTimeUi()
            refreshPreview()
        }
        updateTimeUi()
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

    private fun setupTimePicker(picker: NumberPicker, min: Int, max: Int, value: Int) {
        picker.minValue = min
        picker.maxValue = max
        picker.wrapSelectorWheel = true
        picker.value = value.coerceIn(min, max)
        picker.setFormatter { number -> String.format(Locale.getDefault(), "%02d", number) }
    }

    private fun saveTransaction(closeAfterSave: Boolean) {
        val amount = amountText.toDoubleOrNull() ?: 0.0
        if (amount <= 0) {
            Toast.makeText(requireContext(), R.string.invalid_amount, Toast.LENGTH_SHORT).show()
            return
        }
        val categoryId = categoryAdapter.getSelectedCategory()?.id
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
        viewModel.saveTransaction(
            TransactionDraft(
                transactionId = transactionId,
                type = selectedType,
                categoryId = savedCategoryId,
                amountText = amount.toString(),
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
        selectedDateMillis = savedDateMillis
        selectedCurrency = CurrencyCode.CNY
        binding.noteInput.setText("")
        updateDateButtonText()
        updateCurrencyButtonText()
        updateAmountPreview()
        updatePrimaryActionUi()
        loadCategories(selectedCategoryId = savedCategoryId)
        binding.deleteButton.visibility = View.GONE
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

    companion object {
        private const val ARG_TRANSACTION_ID = "transaction_id"
        private const val ARG_DATE_MILLIS = "date_millis"
        private const val ARG_DEFAULT_TYPE = "default_type"
        private const val MAX_INTEGER_DIGITS = 7
        private const val MAX_DECIMAL_DIGITS = 2
        private const val BACKSPACE_REPEAT_INTERVAL_MS = 60L

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
