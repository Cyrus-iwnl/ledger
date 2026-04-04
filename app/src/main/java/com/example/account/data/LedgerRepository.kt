package com.example.account.data

import android.content.Context
import android.graphics.Color
import android.util.Log
import com.example.account.PerfTrace
import com.example.account.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class LedgerRepository(context: Context) {

    private val appContext = context.applicationContext
    private val defaultLedgerFile = File(appContext.filesDir, FILE_NAME)
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()
    private val nextId = AtomicLong(1L)
    private var ledgers: MutableList<LedgerBook> = mutableListOf()
    private var currentLedgerId: String = DEFAULT_LEDGER_ID
    private val transactionsCache: MutableMap<String, List<LedgerTransaction>> = mutableMapOf()
    private fun getSectionDateFormatter(): DateTimeFormatter = DateTimeFormatter.ofPattern("MM.dd EEEE", Locale.getDefault())
    private fun getSectionDateFormatterWithYear(): DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd EEEE", Locale.getDefault())

    val categories: List<LedgerCategory> = listOf(
        LedgerCategory("expense_meals", "Meals", TransactionType.EXPENSE, R.drawable.ic_dashboard_restaurant_24, "restaurant", Color.parseColor("#FF9F89")),
        LedgerCategory("expense_snacks", "Snacks", TransactionType.EXPENSE, android.R.drawable.ic_menu_gallery, "icecream", Color.parseColor("#F4C571")),
        LedgerCategory("expense_drinks", "Drinks", TransactionType.EXPENSE, android.R.drawable.ic_menu_gallery, "local_cafe", Color.parseColor("#DDA77B")),
        LedgerCategory("expense_clothing", "Clothing", TransactionType.EXPENSE, android.R.drawable.ic_menu_edit, "checkroom", Color.parseColor("#EA9AB2")),
        LedgerCategory("expense_transport", "Transport", TransactionType.EXPENSE, R.drawable.ic_dashboard_directions_car_24, "directions_bus", Color.parseColor("#7DC3B7")),
        LedgerCategory("expense_travel", "Travel", TransactionType.EXPENSE, android.R.drawable.ic_menu_myplaces, "flight_takeoff", Color.parseColor("#80A6E2")),
        LedgerCategory("expense_fun", "Fun", TransactionType.EXPENSE, android.R.drawable.ic_media_play, "confirmation_number", Color.parseColor("#B894CB")),
        LedgerCategory("expense_utility", "Utility", TransactionType.EXPENSE, android.R.drawable.ic_menu_manage, "bolt", Color.parseColor("#89A3D4")),
        LedgerCategory("expense_learn", "Learn", TransactionType.EXPENSE, android.R.drawable.ic_menu_info_details, "school", Color.parseColor("#8195E0")),
        LedgerCategory("expense_daily", "Daily", TransactionType.EXPENSE, android.R.drawable.ic_menu_agenda, "shopping_basket", Color.parseColor("#60C2D6")),
        LedgerCategory("expense_beauty", "Beauty", TransactionType.EXPENSE, android.R.drawable.ic_menu_camera, "face_3", Color.parseColor("#F18B99")),
        LedgerCategory("expense_medical", "Medical", TransactionType.EXPENSE, android.R.drawable.ic_menu_manage, "medical_services", Color.parseColor("#E78A86")),
        LedgerCategory("expense_sports", "Sports", TransactionType.EXPENSE, android.R.drawable.ic_media_play, "fitness_center", Color.parseColor("#A8CE81")),
        LedgerCategory("expense_gifts", "Gifts", TransactionType.EXPENSE, android.R.drawable.ic_menu_send, "featured_seasonal_and_gifts", Color.parseColor("#D485AD")),
        LedgerCategory("expense_digital", "Digital", TransactionType.EXPENSE, android.R.drawable.ic_menu_slideshow, "devices", Color.parseColor("#77B8E1")),
        LedgerCategory("expense_pets", "Pets", TransactionType.EXPENSE, android.R.drawable.ic_menu_gallery, "pets", Color.parseColor("#A78BFA")),
        LedgerCategory("expense_home", "Home", TransactionType.EXPENSE, android.R.drawable.ic_menu_gallery, "home", Color.parseColor("#FB923C")),
        LedgerCategory("expense_comm", "Comm", TransactionType.EXPENSE, android.R.drawable.ic_menu_gallery, "call", Color.parseColor("#38BDF8")),
        LedgerCategory("expense_social", "Social", TransactionType.EXPENSE, android.R.drawable.ic_menu_gallery, "groups", Color.parseColor("#2DD4BF")),
        LedgerCategory("expense_other", "Other", TransactionType.EXPENSE, android.R.drawable.ic_menu_help, "more_horiz", Color.parseColor("#F472B6")),
        LedgerCategory("income_salary", "Salary", TransactionType.INCOME, android.R.drawable.ic_menu_upload, "payments", Color.parseColor("#76C983")),
        LedgerCategory("income_bonus", "Bonus", TransactionType.INCOME, android.R.drawable.ic_input_add, "savings", Color.parseColor("#ECC344")),
        LedgerCategory("income_investment", "Investment", TransactionType.INCOME, android.R.drawable.ic_menu_send, "trending_up", Color.parseColor("#6DA5E8")),
        LedgerCategory("income_refund", "Refund", TransactionType.INCOME, android.R.drawable.ic_menu_revert, "undo", Color.parseColor("#B0D057")),
        LedgerCategory("income_other", "Other", TransactionType.INCOME, android.R.drawable.ic_menu_share, "paid", Color.parseColor("#57CDCB"))
    )
    init {
        PerfTrace.measure("LedgerRepository.init") {
            synchronized(lock) {
                loadLedgerState()
                syncBuiltInDataAndNextId()
            }
        }
    }

    fun getLedgers(): List<LedgerBook> = synchronized(lock) {
        ledgers.toList()
    }

    fun getCurrentLedger(): LedgerBook = synchronized(lock) {
        ledgers.firstOrNull { it.id == currentLedgerId } ?: ledgers.first()
    }

    fun switchLedger(ledgerId: String): Boolean = synchronized(lock) {
        if (ledgerId == currentLedgerId || ledgers.none { it.id == ledgerId }) {
            return false
        }
        currentLedgerId = ledgerId
        persistLedgerState()
        syncBuiltInDataAndNextId()
        true
    }

    fun addLedger(name: String): LedgerBook = synchronized(lock) {
        val normalized = name.trim()
        require(normalized.isNotEmpty()) { "Ledger name cannot be blank." }
        val book = LedgerBook(
            id = "ledger_${UUID.randomUUID().toString().replace("-", "")}",
            name = normalized,
            type = LedgerBookType.NORMAL
        )
        ledgers.add(book)
        persistLedgerState()
        book
    }

    fun deleteLedger(ledgerId: String): Boolean = synchronized(lock) {
        if (ledgers.size <= 1) {
            return false
        }
        val target = ledgers.firstOrNull { it.id == ledgerId } ?: return false
        ledgers.remove(target)
        if (currentLedgerId == target.id) {
            currentLedgerId = ledgers.first().id
        }
        deleteLedgerData(target.id)
        persistLedgerState()
        syncBuiltInDataAndNextId()
        return true
    }

    fun getDashboard(windowDays: Int, month: YearMonth?, year: Int? = null): LedgerDashboard = synchronized(lock) {
        PerfTrace.measure("LedgerRepository.getDashboard(windowDays=$windowDays, month=$month, year=$year)") {
            if (month == null && year == null) {
                return@measure buildAllTimeDashboard(windowDays)
            }
            if (month == null && year != null) {
                return@measure buildYearDashboard(windowDays, year)
            }
            val resolvedMonth = month ?: return@measure buildAllTimeDashboard(windowDays)
            val zoneId = ZoneId.systemDefault()
            val allTransactions = loadTransactionsInternal()
            val monthTransactions = allTransactions.filter {
                YearMonth.from(java.time.Instant.ofEpochMilli(it.timestampMillis).atZone(zoneId)) == resolvedMonth
            }
            val monthStart = resolvedMonth.atDay(1)
            val monthEnd = resolvedMonth.atEndOfMonth()
            val today = LocalDate.now(zoneId)
            val safeWindowDays = windowDays.coerceAtLeast(1)
            val chartEnd = today

            val chartStart = chartEnd.minusDays((safeWindowDays - 1).toLong())
            val chartDayCount = java.time.temporal.ChronoUnit.DAYS.between(chartStart, chartEnd).toInt() + 1
            val chartDaySequence = (0 until chartDayCount).map { chartStart.plusDays(it.toLong()) }

            val summaryDayCount = java.time.temporal.ChronoUnit.DAYS.between(monthStart, monthEnd).toInt() + 1
            val summaryDaySequence = (0 until summaryDayCount).map { monthStart.plusDays(it.toLong()) }

            val chartPoints = chartDaySequence.map { date ->
                val bucket = allTransactions.filter { sameDay(it.timestampMillis, date, zoneId) }
                val income = bucket.filter { it.type == TransactionType.INCOME }.sumOf { it.amountInCny() }
                val expense = bucket.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amountInCny() }
                ChartPoint(LedgerFormatters.shortLabel(date), income, expense)
            }

            val totalIncome = monthTransactions.filter { it.type == TransactionType.INCOME }.sumOf { it.amountInCny() }
            val totalExpense = monthTransactions.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amountInCny() }

            val daySummaries = summaryDaySequence.reversed().mapNotNull { date ->
                val bucket = monthTransactions
                    .filter { sameDay(it.timestampMillis, date, zoneId) }
                    .sortedByDescending { it.timestampMillis }
                if (bucket.isEmpty()) {
                    null
                } else {
                    val income = bucket.filter { it.type == TransactionType.INCOME }.sumOf { it.amountInCny() }
                    val expense = bucket.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amountInCny() }
                    DaySummary(
                        dateMillis = date.atStartOfDay(zoneId).toInstant().toEpochMilli(),
                        dayKey = date.toString(),
                        label = date.format(getSectionDateFormatter()),
                        income = income,
                        expense = expense,
                        transactions = bucket
                    )
                }
            }

            LedgerDashboard(
                totalIncome = totalIncome,
                totalExpense = totalExpense,
                chartPoints = chartPoints,
                daySummaries = daySummaries
            )
        }
    }

    private fun buildYearDashboard(windowDays: Int, year: Int): LedgerDashboard {
        val zoneId = ZoneId.systemDefault()
        val allTransactions = loadTransactionsInternal()
        val yearTransactions = allTransactions.filter {
            java.time.Instant.ofEpochMilli(it.timestampMillis).atZone(zoneId).year == year
        }
        val yearStart = LocalDate.of(year, 1, 1)
        val yearEnd = LocalDate.of(year, 12, 31)
        val today = LocalDate.now(zoneId)
        val safeWindowDays = windowDays.coerceAtLeast(1)
        val chartEnd = today

        val chartStart = chartEnd.minusDays((safeWindowDays - 1).toLong())
        val chartDayCount = java.time.temporal.ChronoUnit.DAYS.between(chartStart, chartEnd).toInt() + 1
        val chartDaySequence = (0 until chartDayCount).map { chartStart.plusDays(it.toLong()) }

        val summaryDayCount = java.time.temporal.ChronoUnit.DAYS.between(yearStart, yearEnd).toInt() + 1
        val summaryDaySequence = (0 until summaryDayCount).map { yearStart.plusDays(it.toLong()) }

        val chartPoints = chartDaySequence.map { date ->
            val bucket = allTransactions.filter { sameDay(it.timestampMillis, date, zoneId) }
            val income = bucket.filter { it.type == TransactionType.INCOME }.sumOf { it.amountInCny() }
            val expense = bucket.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amountInCny() }
            ChartPoint(LedgerFormatters.shortLabel(date), income, expense)
        }

        val totalIncome = yearTransactions.filter { it.type == TransactionType.INCOME }.sumOf { it.amountInCny() }
        val totalExpense = yearTransactions.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amountInCny() }

        val daySummaries = summaryDaySequence.reversed().mapNotNull { date ->
            val bucket = yearTransactions
                .filter { sameDay(it.timestampMillis, date, zoneId) }
                .sortedByDescending { it.timestampMillis }
            if (bucket.isEmpty()) {
                null
            } else {
                val income = bucket.filter { it.type == TransactionType.INCOME }.sumOf { it.amountInCny() }
                val expense = bucket.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amountInCny() }
                DaySummary(
                    dateMillis = date.atStartOfDay(zoneId).toInstant().toEpochMilli(),
                    dayKey = date.toString(),
                    label = date.format(getSectionDateFormatterWithYear()),
                    income = income,
                    expense = expense,
                    transactions = bucket
                )
            }
        }

        return LedgerDashboard(
            totalIncome = totalIncome,
            totalExpense = totalExpense,
            chartPoints = chartPoints,
            daySummaries = daySummaries
        )
    }

    private fun buildAllTimeDashboard(windowDays: Int): LedgerDashboard {
        val zoneId = ZoneId.systemDefault()
        val allTransactions = loadTransactionsInternal()
        if (allTransactions.isEmpty()) {
            return LedgerDashboard(
                totalIncome = 0.0,
                totalExpense = 0.0,
                chartPoints = emptyList(),
                daySummaries = emptyList()
            )
        }
        val groupedByDay = allTransactions.groupBy {
            java.time.Instant.ofEpochMilli(it.timestampMillis).atZone(zoneId).toLocalDate()
        }
        val firstDate = groupedByDay.keys.minOrNull() ?: LocalDate.now(zoneId)
        val lastDate = groupedByDay.keys.maxOrNull() ?: firstDate
        val totalDays = java.time.temporal.ChronoUnit.DAYS.between(firstDate, lastDate).toInt() + 1
        val daySequence = (0 until totalDays).map { firstDate.plusDays(it.toLong()) }

        val safeWindowDays = windowDays.coerceAtLeast(1)
        val chartEnd = LocalDate.now(zoneId)
        val chartStart = chartEnd.minusDays((safeWindowDays - 1).toLong())
        val chartDayCount = java.time.temporal.ChronoUnit.DAYS.between(chartStart, chartEnd).toInt() + 1
        val chartDaySequence = (0 until chartDayCount).map { chartStart.plusDays(it.toLong()) }

        val chartPoints = chartDaySequence.map { date ->
            val bucket = groupedByDay[date].orEmpty()
            val income = bucket.filter { it.type == TransactionType.INCOME }.sumOf { it.amountInCny() }
            val expense = bucket.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amountInCny() }
            ChartPoint(LedgerFormatters.shortLabel(date), income, expense)
        }

        val daySummaries = daySequence.reversed().mapNotNull { date ->
            val bucket = groupedByDay[date].orEmpty().sortedByDescending { it.timestampMillis }
            if (bucket.isEmpty()) {
                null
            } else {
                val income = bucket.filter { it.type == TransactionType.INCOME }.sumOf { it.amountInCny() }
                val expense = bucket.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amountInCny() }
                DaySummary(
                    dateMillis = date.atStartOfDay(zoneId).toInstant().toEpochMilli(),
                    dayKey = date.toString(),
                    label = date.format(getSectionDateFormatterWithYear()),
                    income = income,
                    expense = expense,
                    transactions = bucket
                )
            }
        }

        val totalIncome = allTransactions.filter { it.type == TransactionType.INCOME }.sumOf { it.amountInCny() }
        val totalExpense = allTransactions.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amountInCny() }
        return LedgerDashboard(
            totalIncome = totalIncome,
            totalExpense = totalExpense,
            chartPoints = chartPoints,
            daySummaries = daySummaries
        )
    }

    fun getInsights(): LedgerInsights = synchronized(lock) {
        PerfTrace.measure("LedgerRepository.getInsights") {
            val transactions = loadTransactionsInternal()
            val zoneId = ZoneId.systemDefault()
            val currentMonth = YearMonth.now(zoneId)
            val previousMonth = currentMonth.minusMonths(1)
            val monthFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())

            val currentMonthTransactions = transactions.filter {
                YearMonth.from(java.time.Instant.ofEpochMilli(it.timestampMillis).atZone(zoneId)) == currentMonth
            }
            val previousMonthTransactions = transactions.filter {
                YearMonth.from(java.time.Instant.ofEpochMilli(it.timestampMillis).atZone(zoneId)) == previousMonth
            }

            val totalIncome = currentMonthTransactions
                .filter { it.type == TransactionType.INCOME }
                .sumOf { it.amountInCny() }
            val totalExpense = currentMonthTransactions
                .filter { it.type == TransactionType.EXPENSE }
                .sumOf { it.amountInCny() }
            val previousMonthExpense = previousMonthTransactions
                .filter { it.type == TransactionType.EXPENSE }
                .sumOf { it.amountInCny() }
            val expenseDelta = when {
                previousMonthExpense <= 0.0 && totalExpense <= 0.0 -> 0.0
                previousMonthExpense <= 0.0 -> 100.0
                else -> ((totalExpense - previousMonthExpense) / previousMonthExpense) * 100.0
            }

            val weeklyPoints = buildList {
                val firstDay = currentMonth.atDay(1)
                val lastDay = currentMonth.atEndOfMonth()
                var cursor = firstDay
                var weekIndex = 1
                while (!cursor.isAfter(lastDay)) {
                    val weekEnd = minOf(cursor.plusDays(6), lastDay)
                    val bucket = currentMonthTransactions.filter { tx ->
                        val txDate = java.time.Instant.ofEpochMilli(tx.timestampMillis).atZone(zoneId).toLocalDate()
                        !txDate.isBefore(cursor) && !txDate.isAfter(weekEnd)
                    }
                    add(
                        InsightsWeekPoint(
                            label = "WK $weekIndex",
                            income = bucket.filter { it.type == TransactionType.INCOME }.sumOf { it.amountInCny() },
                            expense = bucket.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amountInCny() }
                        )
                    )
                    cursor = weekEnd.plusDays(1)
                    weekIndex++
                }
            }

            val monthExpenseTotal = totalExpense.coerceAtLeast(0.0)
            val expenseCategories = currentMonthTransactions
                .asSequence()
                .filter { it.type == TransactionType.EXPENSE }
                .groupBy { it.categoryId }
                .mapNotNull { (categoryId, items) ->
                    val category = categories.firstOrNull { it.id == categoryId } ?: return@mapNotNull null
                    val amount = items.sumOf { it.amountInCny() }
                    InsightsCategoryStat(
                        category = category,
                        amount = amount,
                        ratio = if (monthExpenseTotal > 0.0) amount / monthExpenseTotal else 0.0
                    )
                }
                .sortedByDescending { it.amount }
                .toList()

            LedgerInsights(
                rangeLabel = "${currentMonth.atDay(1).format(monthFormatter)} - ${currentMonth.atEndOfMonth().format(monthFormatter)}, ${currentMonth.year}",
                totalIncome = totalIncome,
                totalExpense = totalExpense,
                remainingBalance = totalIncome - totalExpense,
                monthOverMonthExpenseDelta = expenseDelta,
                weeklyPoints = weeklyPoints,
                expenseCategories = expenseCategories
            )
        }
    }

    private fun buildProjectInsights(
        transactions: List<LedgerTransaction>,
        zoneId: ZoneId
    ): LedgerInsights {
        if (transactions.isEmpty()) {
            return LedgerInsights(
                rangeLabel = "",
                totalIncome = 0.0,
                totalExpense = 0.0,
                remainingBalance = 0.0,
                monthOverMonthExpenseDelta = 0.0,
                weeklyPoints = emptyList(),
                expenseCategories = emptyList()
            )
        }

        val dates = transactions.map {
            java.time.Instant.ofEpochMilli(it.timestampMillis).atZone(zoneId).toLocalDate()
        }
        val firstDate = dates.minOrNull() ?: LocalDate.now(zoneId)
        val lastDate = dates.maxOrNull() ?: firstDate
        val rangeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())

        val totalIncome = transactions
            .filter { it.type == TransactionType.INCOME }
            .sumOf { it.amountInCny() }
        val totalExpense = transactions
            .filter { it.type == TransactionType.EXPENSE }
            .sumOf { it.amountInCny() }

        val dayCount = java.time.temporal.ChronoUnit.DAYS.between(firstDate, lastDate).toInt() + 1
        val dailyPoints = (0 until dayCount).map { offset ->
            val date = firstDate.plusDays(offset.toLong())
            val bucket = transactions.filter { tx ->
                java.time.Instant.ofEpochMilli(tx.timestampMillis).atZone(zoneId).toLocalDate() == date
            }
            InsightsWeekPoint(
                label = LedgerFormatters.shortLabel(date),
                income = bucket.filter { it.type == TransactionType.INCOME }.sumOf { it.amountInCny() },
                expense = bucket.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amountInCny() }
            )
        }

        val expenseCategories = transactions
            .asSequence()
            .filter { it.type == TransactionType.EXPENSE }
            .groupBy { it.categoryId }
            .mapNotNull { (categoryId, items) ->
                val category = categories.firstOrNull { it.id == categoryId } ?: return@mapNotNull null
                val amount = items.sumOf { it.amountInCny() }
                InsightsCategoryStat(
                    category = category,
                    amount = amount,
                    ratio = if (totalExpense > 0.0) amount / totalExpense else 0.0
                )
            }
            .sortedByDescending { it.amount }
            .toList()

        return LedgerInsights(
            rangeLabel = "${firstDate.format(rangeFormatter)} - ${lastDate.format(rangeFormatter)}",
            totalIncome = totalIncome,
            totalExpense = totalExpense,
            remainingBalance = totalIncome - totalExpense,
            monthOverMonthExpenseDelta = 0.0,
            weeklyPoints = dailyPoints,
            expenseCategories = expenseCategories
        )
    }

    fun findTransaction(id: Long): LedgerTransaction? = synchronized(lock) {
        loadTransactionsInternal().firstOrNull { it.id == id }
    }

    fun getAllTransactions(): List<LedgerTransaction> = synchronized(lock) {
        loadTransactionsInternal()
    }

    fun getAllCategories(): List<LedgerCategory> = categories

    fun getDraftForTransaction(id: Long): TransactionDraft? = synchronized(lock) {
        val tx = findTransaction(id) ?: return null
        TransactionDraft(
            transactionId = tx.id,
            type = tx.type,
            categoryId = tx.categoryId,
            amountText = tx.amount.toPlainString(),
            currency = tx.currency,
            note = tx.note,
            dateMillis = tx.timestampMillis
        )
    }

    fun saveTransaction(draft: TransactionDraft): Long = synchronized(lock) {
        val transactions = loadTransactionsInternal().toMutableList()
        val parsedAmount = draft.amountText.toDoubleOrNull() ?: 0.0
        val existing = draft.transactionId?.let { id ->
            transactions.firstOrNull { it.id == id }
        }
        val resolvedAmountCny = resolveAmountCnyForSavedTransaction(
            currency = draft.currency,
            amount = parsedAmount,
            existing = existing
        )
        val preservedRefundedAmount = if (draft.type == TransactionType.EXPENSE) {
            existing?.refundedAmount ?: 0.0
        } else {
            0.0
        }
        val tx = LedgerTransaction(
            id = draft.transactionId ?: nextId.getAndIncrement(),
            type = draft.type,
            categoryId = normalizeCategoryId(draft.categoryId),
            amount = parsedAmount,
            amountCny = resolvedAmountCny,
            refundedAmount = preservedRefundedAmount,
            note = draft.note.trim(),
            timestampMillis = draft.dateMillis,
            currency = draft.currency
        )
        val index = transactions.indexOfFirst { it.id == tx.id }
        if (index >= 0) {
            transactions[index] = tx
        } else {
            transactions.add(0, tx)
        }
        persistLastUsedCurrency(draft.currency)
        persist(transactions)
        tx.id
    }

    fun refundTransaction(id: Long, refundAmount: Double) = synchronized(lock) {
        require(refundAmount.isFinite() && refundAmount > 0.0) { "Refund amount must be a positive number." }
        require(BigDecimal.valueOf(refundAmount).scale() <= 2) { "Refund amount can have up to 2 decimal places." }

        val transactions = loadTransactionsInternal().toMutableList()
        val index = transactions.indexOfFirst { it.id == id }
        if (index < 0) {
            return@synchronized
        }
        val current = transactions[index]
        if (current.type != TransactionType.EXPENSE) {
            throw IllegalArgumentException("Only expense transactions can be refunded.")
        }

        val originalAmount = current.originalExpenseAmount()
        val maxRefundable = originalAmount - current.refundedAmount
        if (refundAmount > maxRefundable + 1e-9) {
            throw IllegalArgumentException("Refund amount cannot exceed original expense amount.")
        }

        val cnyPerUnit = if (current.amount > 0.0) {
            current.amountCny / current.amount
        } else {
            current.currency.defaultRateToCny
        }
        val updated = current.copy(
            amount = (current.amount - refundAmount).coerceAtLeast(0.0),
            amountCny = (current.amountCny - (refundAmount * cnyPerUnit)).coerceAtLeast(0.0),
            refundedAmount = current.refundedAmount + refundAmount
        )
        transactions[index] = updated
        persist(transactions)
    }

    fun deleteTransaction(id: Long) = synchronized(lock) {
        val transactions = loadTransactionsInternal().toMutableList()
        transactions.removeAll { it.id == id }
        persist(transactions)
    }

    fun categoriesFor(type: TransactionType): List<LedgerCategory> {
        return categories.filter { it.type == type }
    }

    fun isCurrentLedgerProject(): Boolean = synchronized(lock) {
        getCurrentLedger().type == LedgerBookType.PROJECT
    }

    fun getExchangeRateToCny(currency: CurrencyCode): Double = synchronized(lock) {
        resolveExchangeRateToCny(currency)
    }

    fun setExchangeRateToCny(currency: CurrencyCode, rate: Double) = synchronized(lock) {
        if (currency == CurrencyCode.CNY) {
            return@synchronized
        }
        require(rate.isFinite() && rate > 0.0) { "Exchange rate must be a positive number." }
        require(BigDecimal.valueOf(rate).scale() <= 6) { "Exchange rate can have up to 6 decimal places." }
        prefs.edit()
            .putString(exchangeRateKey(currency), rate.toString())
            .apply()
    }

    fun getLastUsedCurrency(): CurrencyCode = synchronized(lock) {
        prefs.getString(KEY_LAST_USED_CURRENCY, CurrencyCode.CNY.name)
            .toCurrencyCodeOrDefault()
    }

    fun getMonthlyBudget(month: YearMonth): Double? = synchronized(lock) {
        val ledgerId = currentLedgerId
        val currentBudget = explicitMonthlyBudget(month, ledgerId)
        if (prefs.contains(budgetKey(month, ledgerId))) {
            return@synchronized currentBudget
        }
        currentBudget ?: explicitMonthlyBudget(month.minusMonths(1), ledgerId)
    }

    fun setMonthlyBudget(month: YearMonth, value: Double) = synchronized(lock) {
        require(value.isFinite() && value > 0.0) { "Monthly budget must be a positive number." }
        require(BigDecimal.valueOf(value).scale() <= 2) { "Monthly budget can have up to 2 decimal places." }
        prefs.edit()
            .putString(budgetKey(month, currentLedgerId), value.toString())
            .remove(KEY_MONTHLY_BUDGET_LEGACY)
            .apply()
    }

    fun clearMonthlyBudget(month: YearMonth) = synchronized(lock) {
        prefs.edit()
            .putString(budgetKey(month, currentLedgerId), MONTHLY_BUDGET_CLEARED_MARKER)
            .remove(KEY_MONTHLY_BUDGET_LEGACY)
            .apply()
    }

    fun getYearlyBudget(year: Int): Double? = synchronized(lock) {
        prefs.getString(yearlyBudgetKey(year, currentLedgerId), null)
            ?.toDoubleOrNull()
            ?.takeIf { it > 0.0 }
    }

    fun setYearlyBudget(year: Int, value: Double) = synchronized(lock) {
        require(value.isFinite() && value > 0.0) { "Yearly budget must be a positive number." }
        require(BigDecimal.valueOf(value).scale() <= 2) { "Yearly budget can have up to 2 decimal places." }
        prefs.edit()
            .putString(yearlyBudgetKey(year, currentLedgerId), value.toString())
            .apply()
    }

    fun clearYearlyBudget(year: Int) = synchronized(lock) {
        prefs.edit()
            .remove(yearlyBudgetKey(year, currentLedgerId))
            .apply()
    }

    fun getTotalBudget(): Double? = synchronized(lock) {
        prefs.getString(projectBudgetKey(currentLedgerId), null)
            ?.toDoubleOrNull()
            ?.takeIf { it > 0.0 }
    }

    fun setTotalBudget(value: Double) = synchronized(lock) {
        require(value.isFinite() && value > 0.0) { "Project budget must be a positive number." }
        require(BigDecimal.valueOf(value).scale() <= 2) { "Project budget can have up to 2 decimal places." }
        prefs.edit()
            .putString(projectBudgetKey(currentLedgerId), value.toString())
            .apply()
    }

    fun clearTotalBudget() = synchronized(lock) {
        prefs.edit()
            .remove(projectBudgetKey(currentLedgerId))
            .apply()
    }

    fun getMonthlyExpense(month: YearMonth): Double = synchronized(lock) {
        PerfTrace.measure("LedgerRepository.getMonthlyExpense(month=$month)") {
            loadTransactionsInternal()
                .filter { tx ->
                    YearMonth.from(java.time.Instant.ofEpochMilli(tx.timestampMillis).atZone(ZoneId.systemDefault())) == month &&
                        tx.type == TransactionType.EXPENSE
                }
                .sumOf { it.amountInCny() }
        }
    }

    fun getTotalExpense(): Double = synchronized(lock) {
        loadTransactionsInternal()
            .filter { it.type == TransactionType.EXPENSE }
            .sumOf { it.amountInCny() }
    }

    private fun syncBuiltInDataAndNextId() {
        PerfTrace.measure("LedgerRepository.syncBuiltInDataAndNextId(current=$currentLedgerId)") {
            ensureSeedDataForSampleLedger()
            syncNextTransactionId()
        }
    }

    private fun syncNextTransactionId() {
        nextId.set((loadTransactionsInternal().maxOfOrNull { it.id } ?: 0L) + 1L)
    }

    private fun ensureSeedDataForSampleLedger() {
        PerfTrace.measure("LedgerRepository.ensureSeedDataForSampleLedger") {
            if (ledgers.none { it.id == SAMPLE_LEDGER_ID }) {
                return@measure
            }
            val existing = loadTransactionsInternal(SAMPLE_LEDGER_ID)
            val seed = buildSeedTransactions()
            if (existing.isEmpty()) {
                persist(seed, SAMPLE_LEDGER_ID)
                return@measure
            }

            val zoneId = ZoneId.systemDefault()
            var merged = existing
            var nextGeneratedId = (merged.maxOfOrNull { it.id } ?: 0L) + 1L

            fun hasMonth(targetMonth: YearMonth): Boolean {
                return merged.any {
                    YearMonth.from(java.time.Instant.ofEpochMilli(it.timestampMillis).atZone(zoneId)) == targetMonth
                }
            }

            fun appendMonthIfMissing(targetMonth: YearMonth) {
                if (hasMonth(targetMonth)) {
                    return
                }
                val monthSeed = seed.filter {
                    YearMonth.from(java.time.Instant.ofEpochMilli(it.timestampMillis).atZone(zoneId)) == targetMonth
                }
                if (monthSeed.isEmpty()) {
                    return
                }
                val appended = monthSeed.map { tx ->
                    tx.copy(id = nextGeneratedId++)
                }
                merged = merged + appended
            }

            val seedMonths = seed.map {
                YearMonth.from(java.time.Instant.ofEpochMilli(it.timestampMillis).atZone(zoneId))
            }.distinct()
            seedMonths.forEach(::appendMonthIfMissing)

            if (merged.size != existing.size) {
                persist(merged, SAMPLE_LEDGER_ID)
            }
        }
    }

    private fun loadTransactionsInternal(ledgerId: String = currentLedgerId): List<LedgerTransaction> {
        return PerfTrace.measure("LedgerRepository.loadTransactionsInternal(ledger=$ledgerId)") {
            transactionsCache[ledgerId]?.let { cached ->
                return@measure cached
            }
            val ledgerFile = ledgerFileFor(ledgerId)
            if (!ledgerFile.exists()) {
                return@measure emptyList<LedgerTransaction>().also {
                    transactionsCache[ledgerId] = it
                }
            }
            try {
                val raw = ledgerFile.readText()
                if (raw.isBlank()) {
                    emptyList<LedgerTransaction>().also {
                        transactionsCache[ledgerId] = it
                    }
                } else {
                    val array = JSONArray(raw)
                    buildList {
                        for (i in 0 until array.length()) {
                            add(array.getJSONObject(i).toTransaction())
                        }
                    }.sortedByDescending { it.timestampMillis }
                        .also { parsed ->
                            transactionsCache[ledgerId] = parsed
                        }
                }
            } catch (throwable: Throwable) {
                Log.w(TAG, "Failed to load transactions", throwable)
                emptyList<LedgerTransaction>().also {
                    transactionsCache[ledgerId] = it
                }
            }
        }
    }

    private fun persist(transactions: List<LedgerTransaction>, ledgerId: String = currentLedgerId) {
        PerfTrace.measure("LedgerRepository.persist(ledger=$ledgerId, count=${transactions.size})") {
            val ledgerFile = ledgerFileFor(ledgerId)
            val sortedTransactions = transactions.sortedByDescending { it.timestampMillis }
            val array = JSONArray()
            sortedTransactions.forEach { transaction ->
                array.put(transaction.toJson())
            }
            ledgerFile.writeText(array.toString())
            transactionsCache[ledgerId] = sortedTransactions
        }
    }

    private fun buildSeedTransactions(): List<LedgerTransaction> {
        val zone = ZoneId.systemDefault()
        fun atDate(year: Int, month: Int, dayOfMonth: Int, hour: Int, minute: Int = 0): Long {
            val date = LocalDate.of(year, month, dayOfMonth)
            return date.atTime(hour, minute)
                .atZone(zone)
                .toInstant()
                .toEpochMilli()
        }

        return listOf(
            LedgerTransaction(1, TransactionType.EXPENSE, "expense_daily", 260.0, "Rent portion", atDate(2026, 4, 1, 9, 0)),
            LedgerTransaction(2, TransactionType.INCOME, "income_salary", 700.0, "Salary", atDate(2026, 4, 1, 10, 0)),
            LedgerTransaction(3, TransactionType.EXPENSE, "expense_meals", 45.0, "Breakfast", atDate(2026, 4, 1, 8, 20)),
            LedgerTransaction(4, TransactionType.EXPENSE, "expense_transport", 22.0, "Subway", atDate(2026, 4, 2, 9, 15)),
            LedgerTransaction(5, TransactionType.INCOME, "income_other", 420.0, "Freelance", atDate(2026, 4, 2, 15, 10)),
            LedgerTransaction(6, TransactionType.EXPENSE, "expense_daily", 180.0, "Groceries", atDate(2026, 4, 3, 19, 0)),
            LedgerTransaction(7, TransactionType.EXPENSE, "expense_digital", 60.0, "Cloud storage", atDate(2026, 4, 4, 11, 0)),
            LedgerTransaction(8, TransactionType.INCOME, "income_bonus", 380.0, "Project bonus", atDate(2026, 4, 5, 14, 0)),
            LedgerTransaction(9, TransactionType.EXPENSE, "expense_fun", 92.0, "Concert ticket", atDate(2026, 4, 5, 20, 10)),
            LedgerTransaction(10, TransactionType.EXPENSE, "expense_travel", 140.0, "Train ticket", atDate(2026, 4, 6, 9, 40)),
            LedgerTransaction(11, TransactionType.INCOME, "income_investment", 360.0, "Dividend", atDate(2026, 4, 7, 10, 10)),
            LedgerTransaction(12, TransactionType.EXPENSE, "expense_meals", 58.0, "Dinner", atDate(2026, 4, 7, 18, 50)),
            LedgerTransaction(13, TransactionType.EXPENSE, "expense_learn", 88.0, "Online course", atDate(2026, 4, 8, 21, 15)),
            LedgerTransaction(14, TransactionType.INCOME, "income_refund", 240.0, "Refund", atDate(2026, 4, 9, 10, 30)),
            LedgerTransaction(15, TransactionType.EXPENSE, "expense_utility", 120.0, "Electric bill", atDate(2026, 4, 10, 8, 40)),
            LedgerTransaction(16, TransactionType.EXPENSE, "expense_transport", 24.0, "Taxi", atDate(2026, 4, 11, 22, 10)),
            LedgerTransaction(17, TransactionType.INCOME, "income_other", 400.0, "Part-time", atDate(2026, 4, 12, 11, 30)),
            LedgerTransaction(18, TransactionType.EXPENSE, "expense_beauty", 72.0, "Haircut", atDate(2026, 4, 12, 16, 20)),
            LedgerTransaction(19, TransactionType.EXPENSE, "expense_gifts", 95.0, "Gift", atDate(2026, 4, 14, 20, 0)),
            LedgerTransaction(20, TransactionType.INCOME, "income_bonus", 350.0, "Team reward", atDate(2026, 4, 15, 11, 0)),
            LedgerTransaction(21, TransactionType.EXPENSE, "expense_medical", 68.0, "Medicine", atDate(2026, 4, 15, 13, 30)),
            LedgerTransaction(22, TransactionType.EXPENSE, "expense_sports", 85.0, "Sports", atDate(2026, 4, 18, 10, 15)),
            LedgerTransaction(23, TransactionType.INCOME, "income_investment", 330.0, "Returns", atDate(2026, 4, 19, 10, 30)),
            LedgerTransaction(24, TransactionType.EXPENSE, "expense_snacks", 22.0, "Snacks", atDate(2026, 4, 19, 15, 30)),
            LedgerTransaction(25, TransactionType.EXPENSE, "expense_meals", 62.0, "Team dinner", atDate(2026, 4, 21, 19, 20)),
            LedgerTransaction(26, TransactionType.EXPENSE, "expense_travel", 155.0, "Hotel", atDate(2026, 4, 22, 21, 10)),
            LedgerTransaction(27, TransactionType.INCOME, "income_other", 410.0, "Freelance", atDate(2026, 4, 23, 20, 0)),
            LedgerTransaction(28, TransactionType.EXPENSE, "expense_digital", 65.0, "Subscription", atDate(2026, 4, 24, 9, 30)),
            LedgerTransaction(29, TransactionType.EXPENSE, "expense_fun", 90.0, "Movie night", atDate(2026, 4, 25, 21, 0)),
            LedgerTransaction(30, TransactionType.INCOME, "income_salary", 700.0, "Salary", atDate(2026, 4, 28, 10, 0)),
            LedgerTransaction(31, TransactionType.EXPENSE, "expense_daily", 210.0, "Monthly shopping", atDate(2026, 4, 29, 17, 0)),
            LedgerTransaction(32, TransactionType.EXPENSE, "expense_drinks", 24.0, "Coffee", atDate(2026, 4, 30, 15, 0)),
            LedgerTransaction(33, TransactionType.INCOME, "income_other", 520.0, "Freelance", atDate(2026, 3, 17, 11, 0)),
            LedgerTransaction(34, TransactionType.EXPENSE, "expense_daily", 180.0, "Groceries", atDate(2026, 3, 18, 18, 30)),
            LedgerTransaction(35, TransactionType.INCOME, "income_investment", 480.0, "Returns", atDate(2026, 3, 19, 10, 20)),
            LedgerTransaction(36, TransactionType.EXPENSE, "expense_transport", 165.0, "Transport", atDate(2026, 3, 20, 9, 10)),
            LedgerTransaction(37, TransactionType.INCOME, "income_bonus", 510.0, "Bonus", atDate(2026, 3, 21, 11, 15)),
            LedgerTransaction(38, TransactionType.EXPENSE, "expense_meals", 190.0, "Meals", atDate(2026, 3, 22, 19, 0)),
            LedgerTransaction(39, TransactionType.INCOME, "income_other", 500.0, "Part-time", atDate(2026, 3, 23, 20, 0)),
            LedgerTransaction(40, TransactionType.EXPENSE, "expense_fun", 175.0, "Entertainment", atDate(2026, 3, 24, 20, 20)),
            LedgerTransaction(41, TransactionType.INCOME, "income_investment", 530.0, "Investment", atDate(2026, 3, 25, 10, 30)),
            LedgerTransaction(42, TransactionType.EXPENSE, "expense_utility", 185.0, "Utilities", atDate(2026, 3, 26, 14, 10)),
            LedgerTransaction(43, TransactionType.INCOME, "income_other", 495.0, "Freelance", atDate(2026, 3, 27, 20, 0)),
            LedgerTransaction(44, TransactionType.EXPENSE, "expense_medical", 170.0, "Medical", atDate(2026, 3, 28, 13, 0)),
            LedgerTransaction(45, TransactionType.INCOME, "income_bonus", 515.0, "Bonus", atDate(2026, 3, 29, 11, 45)),
            LedgerTransaction(46, TransactionType.EXPENSE, "expense_daily", 182.0, "Household", atDate(2026, 3, 30, 16, 30)),
            LedgerTransaction(47, TransactionType.INCOME, "income_salary", 505.0, "Salary advance", atDate(2026, 3, 31, 10, 0)),
            LedgerTransaction(48, TransactionType.EXPENSE, "expense_daily", 1800.0, "Rent", atDate(2025, 4, 1, 9, 0)),
            LedgerTransaction(49, TransactionType.INCOME, "income_salary", 22000.0, "Salary", atDate(2025, 4, 1, 10, 0)),
            LedgerTransaction(50, TransactionType.INCOME, "income_bonus", 9000.0, "Bonus", atDate(2025, 4, 8, 10, 30)),
            LedgerTransaction(51, TransactionType.EXPENSE, "expense_meals", 40.0, "Lunch", atDate(2025, 4, 10, 12, 20)),
            LedgerTransaction(52, TransactionType.EXPENSE, "expense_transport", 18.0, "Metro", atDate(2025, 4, 11, 8, 50)),
            LedgerTransaction(53, TransactionType.EXPENSE, "expense_utility", 110.0, "Utilities", atDate(2025, 4, 12, 19, 10)),
            LedgerTransaction(54, TransactionType.INCOME, "income_investment", 2800.0, "Investment", atDate(2025, 4, 16, 15, 0)),
            LedgerTransaction(55, TransactionType.EXPENSE, "expense_daily", 120.0, "Groceries", atDate(2025, 4, 18, 18, 40)),
            LedgerTransaction(56, TransactionType.EXPENSE, "expense_medical", 65.0, "Medical", atDate(2025, 4, 20, 14, 30)),
            LedgerTransaction(57, TransactionType.INCOME, "income_other", 3800.0, "Freelance", atDate(2025, 4, 23, 20, 0)),
            LedgerTransaction(58, TransactionType.EXPENSE, "expense_digital", 55.0, "Subscription", atDate(2025, 4, 27, 9, 20)),
            LedgerTransaction(59, TransactionType.EXPENSE, "expense_fun", 90.0, "Entertainment", atDate(2025, 4, 29, 21, 0)),

            // 2026-01: baseline month, balanced spending
            LedgerTransaction(60, TransactionType.INCOME, "income_salary", 7800.0, "Salary", atDate(2026, 1, 2, 10, 0)),
            LedgerTransaction(61, TransactionType.INCOME, "income_bonus", 1200.0, "Performance bonus", atDate(2026, 1, 18, 11, 0)),
            LedgerTransaction(62, TransactionType.EXPENSE, "expense_home", 1800.0, "Rent", atDate(2026, 1, 3, 9, 0)),
            LedgerTransaction(63, TransactionType.EXPENSE, "expense_meals", 620.0, "Meals total", atDate(2026, 1, 12, 19, 30)),
            LedgerTransaction(64, TransactionType.EXPENSE, "expense_transport", 260.0, "Transit and taxi", atDate(2026, 1, 15, 8, 50)),
            LedgerTransaction(65, TransactionType.EXPENSE, "expense_utility", 240.0, "Utilities", atDate(2026, 1, 20, 20, 0)),
            LedgerTransaction(66, TransactionType.EXPENSE, "expense_digital", 120.0, "Cloud and apps", atDate(2026, 1, 22, 9, 20)),
            LedgerTransaction(67, TransactionType.EXPENSE, "expense_fun", 500.0, "Weekend entertainment", atDate(2026, 1, 26, 21, 10)),

            // 2026-02: lower expense month
            LedgerTransaction(68, TransactionType.INCOME, "income_salary", 7800.0, "Salary", atDate(2026, 2, 2, 10, 0)),
            LedgerTransaction(69, TransactionType.INCOME, "income_other", 600.0, "Freelance", atDate(2026, 2, 16, 14, 20)),
            LedgerTransaction(70, TransactionType.EXPENSE, "expense_home", 1800.0, "Rent", atDate(2026, 2, 3, 9, 0)),
            LedgerTransaction(71, TransactionType.EXPENSE, "expense_meals", 420.0, "Meals total", atDate(2026, 2, 10, 19, 20)),
            LedgerTransaction(72, TransactionType.EXPENSE, "expense_transport", 180.0, "Metro and rides", atDate(2026, 2, 11, 9, 10)),
            LedgerTransaction(73, TransactionType.EXPENSE, "expense_daily", 260.0, "Groceries", atDate(2026, 2, 18, 18, 30)),
            LedgerTransaction(74, TransactionType.EXPENSE, "expense_digital", 88.0, "Subscriptions", atDate(2026, 2, 22, 9, 40)),
            LedgerTransaction(75, TransactionType.EXPENSE, "expense_fun", 190.0, "Movie and games", atDate(2026, 2, 23, 20, 40)),

            // 2026-05: high-spend anomaly month with travel spike and large transaction
            LedgerTransaction(76, TransactionType.INCOME, "income_salary", 7800.0, "Salary", atDate(2026, 5, 2, 10, 0)),
            LedgerTransaction(77, TransactionType.INCOME, "income_other", 1200.0, "Freelance", atDate(2026, 5, 21, 20, 0)),
            LedgerTransaction(78, TransactionType.EXPENSE, "expense_home", 1800.0, "Rent", atDate(2026, 5, 3, 9, 0)),
            LedgerTransaction(79, TransactionType.EXPENSE, "expense_travel", 3200.0, "Flight and hotel bundle", atDate(2026, 5, 9, 19, 45)),
            LedgerTransaction(80, TransactionType.EXPENSE, "expense_meals", 920.0, "Dining out", atDate(2026, 5, 14, 20, 20)),
            LedgerTransaction(81, TransactionType.EXPENSE, "expense_fun", 620.0, "Events and entertainment", atDate(2026, 5, 17, 21, 10)),
            LedgerTransaction(82, TransactionType.EXPENSE, "expense_transport", 410.0, "Taxi and rail", atDate(2026, 5, 19, 8, 20)),
            LedgerTransaction(83, TransactionType.EXPENSE, "expense_digital", 150.0, "Annual subscription", atDate(2026, 5, 25, 11, 10)),

            // 2026-06: improved month after high spend
            LedgerTransaction(84, TransactionType.INCOME, "income_salary", 7800.0, "Salary", atDate(2026, 6, 2, 10, 0)),
            LedgerTransaction(85, TransactionType.INCOME, "income_investment", 700.0, "Fund dividend", atDate(2026, 6, 20, 10, 30)),
            LedgerTransaction(86, TransactionType.EXPENSE, "expense_home", 1800.0, "Rent", atDate(2026, 6, 3, 9, 0)),
            LedgerTransaction(87, TransactionType.EXPENSE, "expense_meals", 520.0, "Meals total", atDate(2026, 6, 10, 19, 0)),
            LedgerTransaction(88, TransactionType.EXPENSE, "expense_transport", 220.0, "Transport", atDate(2026, 6, 12, 8, 50)),
            LedgerTransaction(89, TransactionType.EXPENSE, "expense_daily", 280.0, "Household", atDate(2026, 6, 16, 18, 20)),
            LedgerTransaction(90, TransactionType.EXPENSE, "expense_fun", 260.0, "Weekend recreation", atDate(2026, 6, 21, 21, 0)),
            LedgerTransaction(91, TransactionType.EXPENSE, "expense_digital", 90.0, "Subscriptions", atDate(2026, 6, 26, 9, 30)),

            // 2025-Q4: richer prior-year context for yearly insights
            LedgerTransaction(92, TransactionType.INCOME, "income_salary", 7600.0, "Salary", atDate(2025, 10, 2, 10, 0)),
            LedgerTransaction(93, TransactionType.EXPENSE, "expense_home", 1750.0, "Rent", atDate(2025, 10, 3, 9, 0)),
            LedgerTransaction(94, TransactionType.EXPENSE, "expense_meals", 560.0, "Meals", atDate(2025, 10, 12, 19, 30)),
            LedgerTransaction(95, TransactionType.EXPENSE, "expense_transport", 210.0, "Transport", atDate(2025, 10, 18, 8, 40)),
            LedgerTransaction(96, TransactionType.EXPENSE, "expense_daily", 300.0, "Groceries", atDate(2025, 10, 21, 18, 20)),
            LedgerTransaction(97, TransactionType.INCOME, "income_other", 900.0, "Freelance", atDate(2025, 10, 25, 20, 0)),

            LedgerTransaction(98, TransactionType.INCOME, "income_salary", 7600.0, "Salary", atDate(2025, 11, 2, 10, 0)),
            LedgerTransaction(99, TransactionType.EXPENSE, "expense_home", 1750.0, "Rent", atDate(2025, 11, 3, 9, 0)),
            LedgerTransaction(100, TransactionType.EXPENSE, "expense_meals", 590.0, "Meals", atDate(2025, 11, 9, 19, 0)),
            LedgerTransaction(101, TransactionType.EXPENSE, "expense_transport", 230.0, "Transport", atDate(2025, 11, 15, 8, 30)),
            LedgerTransaction(102, TransactionType.EXPENSE, "expense_fun", 340.0, "Entertainment", atDate(2025, 11, 22, 21, 10)),
            LedgerTransaction(103, TransactionType.INCOME, "income_bonus", 1400.0, "Quarter bonus", atDate(2025, 11, 28, 11, 20)),

            LedgerTransaction(104, TransactionType.INCOME, "income_salary", 7600.0, "Salary", atDate(2025, 12, 2, 10, 0)),
            LedgerTransaction(105, TransactionType.EXPENSE, "expense_home", 1750.0, "Rent", atDate(2025, 12, 3, 9, 0)),
            LedgerTransaction(106, TransactionType.EXPENSE, "expense_meals", 680.0, "Holiday meals", atDate(2025, 12, 10, 19, 40)),
            LedgerTransaction(107, TransactionType.EXPENSE, "expense_gifts", 520.0, "Holiday gifts", atDate(2025, 12, 18, 20, 10)),
            LedgerTransaction(108, TransactionType.EXPENSE, "expense_transport", 260.0, "Travel home", atDate(2025, 12, 20, 9, 20)),
            LedgerTransaction(109, TransactionType.INCOME, "income_other", 1100.0, "Year-end freelance", atDate(2025, 12, 27, 16, 0)),

            // 2025-05: conservative month
            LedgerTransaction(110, TransactionType.INCOME, "income_salary", 7600.0, "Salary", atDate(2025, 5, 2, 10, 0)),
            LedgerTransaction(111, TransactionType.INCOME, "income_other", 500.0, "Freelance", atDate(2025, 5, 20, 16, 0)),
            LedgerTransaction(112, TransactionType.EXPENSE, "expense_home", 1750.0, "Rent", atDate(2025, 5, 3, 9, 0)),
            LedgerTransaction(113, TransactionType.EXPENSE, "expense_meals", 420.0, "Meals", atDate(2025, 5, 11, 19, 30)),
            LedgerTransaction(114, TransactionType.EXPENSE, "expense_transport", 180.0, "Transport", atDate(2025, 5, 15, 8, 40)),
            LedgerTransaction(115, TransactionType.EXPENSE, "expense_daily", 220.0, "Groceries", atDate(2025, 5, 18, 18, 20)),
            LedgerTransaction(116, TransactionType.EXPENSE, "expense_digital", 80.0, "Subscriptions", atDate(2025, 5, 24, 9, 30)),

            // 2025-06: medical spike month
            LedgerTransaction(117, TransactionType.INCOME, "income_salary", 7600.0, "Salary", atDate(2025, 6, 2, 10, 0)),
            LedgerTransaction(118, TransactionType.INCOME, "income_bonus", 800.0, "Bonus", atDate(2025, 6, 26, 11, 0)),
            LedgerTransaction(119, TransactionType.EXPENSE, "expense_home", 1750.0, "Rent", atDate(2025, 6, 3, 9, 0)),
            LedgerTransaction(120, TransactionType.EXPENSE, "expense_medical", 980.0, "Dental care", atDate(2025, 6, 8, 14, 10)),
            LedgerTransaction(121, TransactionType.EXPENSE, "expense_meals", 430.0, "Meals", atDate(2025, 6, 12, 19, 0)),
            LedgerTransaction(122, TransactionType.EXPENSE, "expense_transport", 190.0, "Transport", atDate(2025, 6, 17, 8, 20)),
            LedgerTransaction(123, TransactionType.EXPENSE, "expense_utility", 240.0, "Utilities", atDate(2025, 6, 21, 20, 0)),

            // 2025-07: weekend-heavy fun and travel month
            LedgerTransaction(124, TransactionType.INCOME, "income_salary", 7600.0, "Salary", atDate(2025, 7, 2, 10, 0)),
            LedgerTransaction(125, TransactionType.INCOME, "income_other", 700.0, "Freelance", atDate(2025, 7, 25, 16, 20)),
            LedgerTransaction(126, TransactionType.EXPENSE, "expense_home", 1750.0, "Rent", atDate(2025, 7, 3, 9, 0)),
            LedgerTransaction(127, TransactionType.EXPENSE, "expense_fun", 760.0, "Weekend events", atDate(2025, 7, 12, 20, 30)),
            LedgerTransaction(128, TransactionType.EXPENSE, "expense_travel", 1180.0, "Short trip", atDate(2025, 7, 19, 10, 10)),
            LedgerTransaction(129, TransactionType.EXPENSE, "expense_meals", 500.0, "Meals", atDate(2025, 7, 20, 19, 20)),
            LedgerTransaction(130, TransactionType.EXPENSE, "expense_transport", 220.0, "Transport", atDate(2025, 7, 22, 8, 50)),

            // 2025-08: savings rebound month
            LedgerTransaction(131, TransactionType.INCOME, "income_salary", 7600.0, "Salary", atDate(2025, 8, 2, 10, 0)),
            LedgerTransaction(132, TransactionType.INCOME, "income_investment", 1500.0, "Fund dividend", atDate(2025, 8, 27, 10, 40)),
            LedgerTransaction(133, TransactionType.EXPENSE, "expense_home", 1750.0, "Rent", atDate(2025, 8, 3, 9, 0)),
            LedgerTransaction(134, TransactionType.EXPENSE, "expense_daily", 180.0, "Groceries", atDate(2025, 8, 10, 18, 0)),
            LedgerTransaction(135, TransactionType.EXPENSE, "expense_meals", 360.0, "Meals", atDate(2025, 8, 14, 19, 0)),
            LedgerTransaction(136, TransactionType.EXPENSE, "expense_transport", 170.0, "Transport", atDate(2025, 8, 16, 8, 20)),
            LedgerTransaction(137, TransactionType.EXPENSE, "expense_digital", 85.0, "Subscriptions", atDate(2025, 8, 20, 9, 30)),

            // 2025-09: digital and learning spike month
            LedgerTransaction(138, TransactionType.INCOME, "income_salary", 7600.0, "Salary", atDate(2025, 9, 2, 10, 0)),
            LedgerTransaction(139, TransactionType.INCOME, "income_bonus", 1200.0, "Quarter bonus", atDate(2025, 9, 26, 11, 10)),
            LedgerTransaction(140, TransactionType.EXPENSE, "expense_home", 1750.0, "Rent", atDate(2025, 9, 3, 9, 0)),
            LedgerTransaction(141, TransactionType.EXPENSE, "expense_digital", 980.0, "Device upgrade", atDate(2025, 9, 9, 21, 0)),
            LedgerTransaction(142, TransactionType.EXPENSE, "expense_learn", 620.0, "Course bundle", atDate(2025, 9, 14, 20, 10)),
            LedgerTransaction(143, TransactionType.EXPENSE, "expense_meals", 470.0, "Meals", atDate(2025, 9, 19, 19, 20)),
            LedgerTransaction(144, TransactionType.EXPENSE, "expense_transport", 210.0, "Transport", atDate(2025, 9, 22, 8, 40)),
            LedgerTransaction(145, TransactionType.EXPENSE, "expense_gifts", 430.0, "Festival gifts", atDate(2025, 9, 28, 20, 30))
        )
    }

    private fun LedgerTransaction.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type.name)
        put("categoryId", categoryId)
        put("amount", amount)
        put("amountCny", amountCny)
        put("refundedAmount", refundedAmount)
        put("currency", currency.name)
        put("note", note)
        put("timestampMillis", timestampMillis)
    }

    private fun JSONObject.toTransaction(): LedgerTransaction {
        val currency = optString("currency", CurrencyCode.CNY.name)
            .toCurrencyCodeOrDefault()
        return LedgerTransaction(
            id = getLong("id"),
            type = TransactionType.valueOf(getString("type")),
            categoryId = normalizeCategoryId(getString("categoryId")),
            amount = getDouble("amount"),
            amountCny = normalizeAmountCny(
                raw = optDouble("amountCny", Double.NaN),
                amount = getDouble("amount"),
                currency = currency
            ),
            refundedAmount = optDouble("refundedAmount", 0.0),
            note = optString("note", ""),
            timestampMillis = getLong("timestampMillis"),
            currency = currency
        )
    }

    private fun resolveAmountCnyForSavedTransaction(
        currency: CurrencyCode,
        amount: Double,
        existing: LedgerTransaction?
    ): Double {
        if (currency == CurrencyCode.CNY) {
            return amount
        }
        if (
            existing != null &&
            existing.currency == currency &&
            kotlin.math.abs(existing.amount - amount) < 1e-9
        ) {
            return existing.amountCny
        }
        val rate = resolveExchangeRateToCny(currency)
        return amount * rate
    }

    private fun normalizeAmountCny(raw: Double, amount: Double, currency: CurrencyCode): Double {
        if (raw.isFinite() && raw >= 0.0) {
            return raw
        }
        return amount * currency.defaultRateToCny
    }

    private fun resolveExchangeRateToCny(currency: CurrencyCode): Double {
        if (currency == CurrencyCode.CNY) {
            return 1.0
        }
        val stored = prefs.getString(exchangeRateKey(currency), null)
            ?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it > 0.0 }
        return stored ?: currency.defaultRateToCny
    }

    private fun exchangeRateKey(currency: CurrencyCode): String {
        return "$KEY_EXCHANGE_RATE_PREFIX${currency.code}"
    }

    private fun persistLastUsedCurrency(currency: CurrencyCode) {
        prefs.edit()
            .putString(KEY_LAST_USED_CURRENCY, currency.name)
            .apply()
    }

    private fun String?.toCurrencyCodeOrDefault(): CurrencyCode {
        val raw = this?.trim().orEmpty()
        return CurrencyCode.values().firstOrNull {
            it.name.equals(raw, ignoreCase = true) || it.code.equals(raw, ignoreCase = true)
        } ?: CurrencyCode.CNY
    }

    private fun String?.toLedgerBookTypeOrDefault(): LedgerBookType {
        val raw = this?.trim().orEmpty()
        return LedgerBookType.values().firstOrNull {
            it.name.equals(raw, ignoreCase = true)
        } ?: LedgerBookType.NORMAL
    }

    private fun normalizeCategoryId(categoryId: String): String {
        return when (categoryId) {
            "expense_kids" -> "expense_other"
            else -> categoryId
        }
    }

    private fun Double.toPlainString(): String {
        val text = toString()
        return if (text.endsWith(".0")) text.substring(0, text.length - 2) else text
    }

    private fun loadLedgerState() {
        val parsedLedgers = runCatching {
            val raw = prefs.getString(KEY_LEDGERS, null).orEmpty()
            if (raw.isBlank()) {
                emptyList()
            } else {
                val array = JSONArray(raw)
                buildList {
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        val id = item.optString("id").trim()
                        val name = item.optString("name").trim()
                        val type = item.optString("type")
                            .toLedgerBookTypeOrDefault()
                        if (id.isNotEmpty() && name.isNotEmpty()) {
                            add(LedgerBook(id = id, name = name, type = type))
                        }
                    }
                }
            }
        }.getOrDefault(emptyList())

        val dedupedLedgers = linkedMapOf<String, LedgerBook>()
        parsedLedgers.forEach { ledger ->
            if (!dedupedLedgers.containsKey(ledger.id)) {
                dedupedLedgers[ledger.id] = ledger
            }
        }
        if (dedupedLedgers.isEmpty()) {
            dedupedLedgers[DEFAULT_LEDGER_ID] = LedgerBook(
                id = DEFAULT_LEDGER_ID,
                name = DEFAULT_LEDGER_NAME,
                type = LedgerBookType.NORMAL
            )
            dedupedLedgers[SAMPLE_LEDGER_ID] = LedgerBook(
                id = SAMPLE_LEDGER_ID,
                name = SAMPLE_LEDGER_NAME,
                type = LedgerBookType.NORMAL
            )
        }

        val savedCurrentId = prefs.getString(KEY_CURRENT_LEDGER_ID, DEFAULT_LEDGER_ID).orEmpty()
        val resolvedCurrentId = if (dedupedLedgers.containsKey(savedCurrentId)) {
            savedCurrentId
        } else {
            if (dedupedLedgers.containsKey(DEFAULT_LEDGER_ID)) {
                DEFAULT_LEDGER_ID
            } else {
                dedupedLedgers.keys.first()
            }
        }

        ledgers = dedupedLedgers.values.toMutableList()
        currentLedgerId = resolvedCurrentId
        persistLedgerState()
    }

    private fun persistLedgerState() {
        val array = JSONArray().apply {
            ledgers.forEach { ledger ->
                put(
                    JSONObject().apply {
                        put("id", ledger.id)
                        put("name", ledger.name)
                        put("type", ledger.type.name)
                    }
                )
            }
        }
        prefs.edit()
            .putString(KEY_LEDGERS, array.toString())
            .putString(KEY_CURRENT_LEDGER_ID, currentLedgerId)
            .apply()
    }

    private fun ledgerFileFor(ledgerId: String): File {
        return if (ledgerId == DEFAULT_LEDGER_ID) {
            defaultLedgerFile
        } else {
            File(appContext.filesDir, "$LEDGER_FILE_PREFIX$ledgerId$LEDGER_FILE_SUFFIX")
        }
    }

    private fun deleteLedgerData(ledgerId: String) {
        ledgerFileFor(ledgerId).takeIf { it.exists() }?.delete()
        transactionsCache.remove(ledgerId)
        val targetPrefix = "$KEY_MONTHLY_BUDGET_PREFIX${ledgerId}_"
        val editor = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith(targetPrefix) }
            .forEach { key -> editor.remove(key) }
        val yearlyTargetPrefix = "$KEY_YEARLY_BUDGET_PREFIX${ledgerId}_"
        prefs.all.keys
            .filter { it.startsWith(yearlyTargetPrefix) }
            .forEach { key -> editor.remove(key) }
        editor.remove(projectBudgetKey(ledgerId))
        if (ledgerId == DEFAULT_LEDGER_ID) {
            editor.remove(KEY_MONTHLY_BUDGET_LEGACY)
            prefs.all.keys
                .filter(::isLegacyMonthBudgetKey)
                .forEach { legacyKey -> editor.remove(legacyKey) }
        }
        editor.apply()
    }

    private fun budgetKey(month: YearMonth, ledgerId: String): String =
        "$KEY_MONTHLY_BUDGET_PREFIX${ledgerId}_$month"

    private fun yearlyBudgetKey(year: Int, ledgerId: String): String =
        "$KEY_YEARLY_BUDGET_PREFIX${ledgerId}_$year"

    private fun projectBudgetKey(ledgerId: String): String =
        "$KEY_PROJECT_BUDGET_PREFIX$ledgerId"

    private fun explicitMonthlyBudget(month: YearMonth, ledgerId: String): Double? {
        val key = budgetKey(month, ledgerId)
        val stored = prefs.getString(key, null)
        if (stored == MONTHLY_BUDGET_CLEARED_MARKER) {
            return null
        }
        if (prefs.contains(key)) {
            return stored?.toDoubleOrNull()?.takeIf { it > 0.0 }
        }
        return legacyMonthlyBudget(month, ledgerId)
    }

    private fun legacyMonthlyBudget(month: YearMonth, ledgerId: String): Double? {
        if (ledgerId != DEFAULT_LEDGER_ID) {
            return null
        }
        prefs.getString(legacyBudgetKey(month), null)?.toDoubleOrNull()?.takeIf { it > 0.0 }?.let { return it }
        if (month != YearMonth.now()) {
            return null
        }
        return prefs.getString(KEY_MONTHLY_BUDGET_LEGACY, null)?.toDoubleOrNull()?.takeIf { it > 0.0 }
    }

    private fun legacyBudgetKey(month: YearMonth): String = "$KEY_MONTHLY_BUDGET_PREFIX$month"

    private fun isLegacyMonthBudgetKey(key: String): Boolean {
        return key.startsWith(KEY_MONTHLY_BUDGET_PREFIX) &&
            key.removePrefix(KEY_MONTHLY_BUDGET_PREFIX).matches(Regex("\\d{4}-\\d{2}"))
    }

    private fun sameDay(millis: Long, date: LocalDate, zoneId: ZoneId): Boolean {
        val txDate = java.time.Instant.ofEpochMilli(millis).atZone(zoneId).toLocalDate()
        return txDate == date
    }

    companion object {
        private const val FILE_NAME = "ledger_transactions.json"
        private const val LEDGER_FILE_PREFIX = "ledger_transactions_"
        private const val LEDGER_FILE_SUFFIX = ".json"
        private const val DEFAULT_LEDGER_ID = "default"
        private const val DEFAULT_LEDGER_NAME = "Default"
        private const val SAMPLE_LEDGER_ID = "sample"
        private const val SAMPLE_LEDGER_NAME = "Sample"
        private const val TAG = "LedgerRepository"
        private const val PREFS_NAME = "ledger_settings"
        private const val KEY_LEDGERS = "ledgers"
        private const val KEY_CURRENT_LEDGER_ID = "current_ledger_id"
        private const val KEY_MONTHLY_BUDGET_PREFIX = "monthly_budget_"
        private const val KEY_MONTHLY_BUDGET_LEGACY = "monthly_budget"
        private const val KEY_YEARLY_BUDGET_PREFIX = "yearly_budget_"
        private const val KEY_PROJECT_BUDGET_PREFIX = "project_budget_"
        private const val KEY_EXCHANGE_RATE_PREFIX = "exchange_rate_"
        private const val KEY_LAST_USED_CURRENCY = "last_used_currency"
        private const val MONTHLY_BUDGET_CLEARED_MARKER = "__cleared__"
    }
}

