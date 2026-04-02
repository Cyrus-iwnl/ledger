package com.example.account.data

import android.content.Context
import android.graphics.Color
import android.util.Log
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
import java.util.concurrent.atomic.AtomicLong

class LedgerRepository(context: Context) {

    private val appContext = context.applicationContext
    private val ledgerFile = File(appContext.filesDir, FILE_NAME)
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()
    private val nextId = AtomicLong(1L)
    private val sectionDateFormatter = DateTimeFormatter.ofPattern("MM.dd EEEE", Locale.US)

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
        LedgerCategory("expense_kids", "Kids", TransactionType.EXPENSE, android.R.drawable.ic_menu_gallery, "child_care", Color.parseColor("#F472B6")),
        LedgerCategory("income_salary", "Salary", TransactionType.INCOME, android.R.drawable.ic_menu_upload, "payments", Color.parseColor("#76C983")),
        LedgerCategory("income_bonus", "Bonus", TransactionType.INCOME, android.R.drawable.ic_input_add, "savings", Color.parseColor("#ECC344")),
        LedgerCategory("income_investment", "Investment", TransactionType.INCOME, android.R.drawable.ic_menu_send, "trending_up", Color.parseColor("#6DA5E8")),
        LedgerCategory("income_refund", "Refund", TransactionType.INCOME, android.R.drawable.ic_menu_revert, "undo", Color.parseColor("#B0D057")),
        LedgerCategory("income_other", "Other", TransactionType.INCOME, android.R.drawable.ic_menu_share, "paid", Color.parseColor("#57CDCB"))
    )
    init {
        ensureSeedData()
    }

    fun getDashboard(windowDays: Int, month: YearMonth): LedgerDashboard = synchronized(lock) {
        val zoneId = ZoneId.systemDefault()
        val allTransactions = loadTransactionsInternal()
        val monthTransactions = allTransactions.filter {
            YearMonth.from(java.time.Instant.ofEpochMilli(it.timestampMillis).atZone(zoneId)) == month
        }
        val monthStart = month.atDay(1)
        val monthEnd = month.atEndOfMonth()
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
            val income = bucket.filter { it.type == TransactionType.INCOME }.sumOf { it.amount }
            val expense = bucket.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amount }
            ChartPoint(LedgerFormatters.shortLabel(date), income, expense)
        }

        val totalIncome = monthTransactions.filter { it.type == TransactionType.INCOME }.sumOf { it.amount }
        val totalExpense = monthTransactions.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amount }

        val daySummaries = summaryDaySequence.reversed().mapNotNull { date ->
            val bucket = monthTransactions
                .filter { sameDay(it.timestampMillis, date, zoneId) }
                .sortedByDescending { it.timestampMillis }
            if (bucket.isEmpty()) {
                null
            } else {
                val income = bucket.filter { it.type == TransactionType.INCOME }.sumOf { it.amount }
                val expense = bucket.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amount }
                DaySummary(
                    dateMillis = date.atStartOfDay(zoneId).toInstant().toEpochMilli(),
                    dayKey = date.toString(),
                    label = date.format(sectionDateFormatter),
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

    fun getInsights(): LedgerInsights = synchronized(lock) {
        val transactions = loadTransactionsInternal()
        val zoneId = ZoneId.systemDefault()
        val currentMonth = YearMonth.now(zoneId)
        val previousMonth = currentMonth.minusMonths(1)
        val monthFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.US)

        val currentMonthTransactions = transactions.filter {
            YearMonth.from(java.time.Instant.ofEpochMilli(it.timestampMillis).atZone(zoneId)) == currentMonth
        }
        val previousMonthTransactions = transactions.filter {
            YearMonth.from(java.time.Instant.ofEpochMilli(it.timestampMillis).atZone(zoneId)) == previousMonth
        }

        val totalIncome = currentMonthTransactions
            .filter { it.type == TransactionType.INCOME }
            .sumOf { it.amount }
        val totalExpense = currentMonthTransactions
            .filter { it.type == TransactionType.EXPENSE }
            .sumOf { it.amount }
        val previousMonthExpense = previousMonthTransactions
            .filter { it.type == TransactionType.EXPENSE }
            .sumOf { it.amount }
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
                        income = bucket.filter { it.type == TransactionType.INCOME }.sumOf { it.amount },
                        expense = bucket.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amount }
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
                val amount = items.sumOf { it.amount }
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
        val tx = LedgerTransaction(
            id = draft.transactionId ?: nextId.getAndIncrement(),
            type = draft.type,
            categoryId = draft.categoryId,
            amount = parsedAmount,
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
        persist(transactions)
        tx.id
    }

    fun deleteTransaction(id: Long) = synchronized(lock) {
        val transactions = loadTransactionsInternal().toMutableList()
        transactions.removeAll { it.id == id }
        persist(transactions)
    }

    fun categoriesFor(type: TransactionType): List<LedgerCategory> {
        return categories.filter { it.type == type }
    }

    fun getMonthlyBudget(month: YearMonth): Double? {
        return explicitMonthlyBudget(month)
            ?: explicitMonthlyBudget(month.minusMonths(1))
    }

    fun setMonthlyBudget(month: YearMonth, value: Double) {
        require(value.isFinite() && value > 0.0) { "Monthly budget must be a positive number." }
        require(BigDecimal.valueOf(value).scale() <= 2) { "Monthly budget can have up to 2 decimal places." }
        prefs.edit()
            .putString(budgetKey(month), value.toString())
            .remove(KEY_MONTHLY_BUDGET_LEGACY)
            .apply()
    }

    fun getMonthlyExpense(month: YearMonth): Double = synchronized(lock) {
        loadTransactionsInternal()
            .filter { tx ->
                YearMonth.from(java.time.Instant.ofEpochMilli(tx.timestampMillis).atZone(ZoneId.systemDefault())) == month &&
                    tx.type == TransactionType.EXPENSE
            }
            .sumOf { it.amount }
    }

    private fun ensureSeedData() {
        synchronized(lock) {
            val existing = loadTransactionsInternal()
            val seed = buildSeedTransactions()
            if (existing.isEmpty()) {
                nextId.set((seed.maxOfOrNull { it.id } ?: 0L) + 1L)
                persist(seed)
                return
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

            appendMonthIfMissing(YearMonth.of(2026, 3))
            appendMonthIfMissing(YearMonth.of(2025, 4))

            if (merged.size != existing.size) {
                persist(merged)
            }
            nextId.set(nextGeneratedId)
        }
    }

    private fun loadTransactionsInternal(): List<LedgerTransaction> {
        if (!ledgerFile.exists()) {
            return emptyList()
        }
        return try {
            val raw = ledgerFile.readText()
            if (raw.isBlank()) {
                emptyList()
            } else {
                val array = JSONArray(raw)
                buildList {
                    for (i in 0 until array.length()) {
                        add(array.getJSONObject(i).toTransaction())
                    }
                }.sortedByDescending { it.timestampMillis }
            }
        } catch (throwable: Throwable) {
            Log.w(TAG, "Failed to load transactions", throwable)
            emptyList()
        }
    }

    private fun persist(transactions: List<LedgerTransaction>) {
        val array = JSONArray()
        transactions.sortedByDescending { it.timestampMillis }.forEach { transaction ->
            array.put(transaction.toJson())
        }
        ledgerFile.writeText(array.toString())
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
            LedgerTransaction(59, TransactionType.EXPENSE, "expense_fun", 90.0, "Entertainment", atDate(2025, 4, 29, 21, 0))
        )
    }

    private fun LedgerTransaction.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type.name)
        put("categoryId", categoryId)
        put("amount", amount)
        put("currency", currency.name)
        put("note", note)
        put("timestampMillis", timestampMillis)
    }

    private fun JSONObject.toTransaction(): LedgerTransaction {
        return LedgerTransaction(
            id = getLong("id"),
            type = TransactionType.valueOf(getString("type")),
            categoryId = getString("categoryId"),
            amount = getDouble("amount"),
            note = optString("note", ""),
            timestampMillis = getLong("timestampMillis"),
            currency = optString("currency", CurrencyCode.CNY.name)
                .toCurrencyCodeOrDefault()
        )
    }

    private fun String?.toCurrencyCodeOrDefault(): CurrencyCode {
        val raw = this?.trim().orEmpty()
        return CurrencyCode.values().firstOrNull {
            it.name.equals(raw, ignoreCase = true) || it.code.equals(raw, ignoreCase = true)
        } ?: CurrencyCode.CNY
    }

    private fun Double.toPlainString(): String {
        val text = toString()
        return if (text.endsWith(".0")) text.substring(0, text.length - 2) else text
    }

    private fun budgetKey(month: YearMonth): String = "$KEY_MONTHLY_BUDGET_PREFIX$month"

    private fun explicitMonthlyBudget(month: YearMonth): Double? {
        return prefs.getString(budgetKey(month), null)?.toDoubleOrNull()?.takeIf { it > 0.0 }
            ?: legacyMonthlyBudget(month)
    }

    private fun legacyMonthlyBudget(month: YearMonth): Double? {
        if (month != YearMonth.now()) {
            return null
        }
        return prefs.getString(KEY_MONTHLY_BUDGET_LEGACY, null)?.toDoubleOrNull()?.takeIf { it > 0.0 }
    }

    private fun sameDay(millis: Long, date: LocalDate, zoneId: ZoneId): Boolean {
        val txDate = java.time.Instant.ofEpochMilli(millis).atZone(zoneId).toLocalDate()
        return txDate == date
    }

    companion object {
        private const val FILE_NAME = "ledger_transactions.json"
        private const val TAG = "LedgerRepository"
        private const val PREFS_NAME = "ledger_settings"
        private const val KEY_MONTHLY_BUDGET_PREFIX = "monthly_budget_"
        private const val KEY_MONTHLY_BUDGET_LEGACY = "monthly_budget"
    }
}

