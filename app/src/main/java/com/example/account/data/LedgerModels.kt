package com.example.account.data

import androidx.annotation.DrawableRes
import java.text.DecimalFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class TransactionType {
    EXPENSE,
    INCOME
}

enum class CurrencyCode(val code: String, val symbol: String) {
    CNY("CNY", "\u00A5"),
    USD("USD", "$"),
    EUR("EUR", "\u20AC"),
    GBP("GBP", "\u00A3"),
    JPY("JPY", "\u00A5"),
    HKD("HKD", "HK$")
}

data class LedgerCategory(
    val id: String,
    val name: String,
    val type: TransactionType,
    @DrawableRes val iconRes: Int,
    val iconGlyph: String = "",
    val accentColor: Int
)

data class LedgerTransaction(
    val id: Long,
    val type: TransactionType,
    val categoryId: String,
    val amount: Double,
    val note: String,
    val timestampMillis: Long,
    val currency: CurrencyCode = CurrencyCode.CNY
)

data class DaySummary(
    val dateMillis: Long,
    val dayKey: String,
    val label: String,
    val income: Double,
    val expense: Double,
    val transactions: List<LedgerTransaction>
)

data class ChartPoint(
    val label: String,
    val income: Double,
    val expense: Double
)

data class LedgerDashboard(
    val totalIncome: Double,
    val totalExpense: Double,
    val chartPoints: List<ChartPoint>,
    val daySummaries: List<DaySummary>
)

data class InsightsCategoryStat(
    val category: LedgerCategory,
    val amount: Double,
    val ratio: Double
)

data class InsightsWeekPoint(
    val label: String,
    val income: Double,
    val expense: Double
)

data class LedgerInsights(
    val rangeLabel: String,
    val totalIncome: Double,
    val totalExpense: Double,
    val remainingBalance: Double,
    val monthOverMonthExpenseDelta: Double,
    val weeklyPoints: List<InsightsWeekPoint>,
    val expenseCategories: List<InsightsCategoryStat>
)

data class TransactionDraft(
    val transactionId: Long? = null,
    val type: TransactionType = TransactionType.EXPENSE,
    val categoryId: String = "",
    val amountText: String = "0",
    val currency: CurrencyCode = CurrencyCode.CNY,
    val note: String = "",
    val dateMillis: Long = System.currentTimeMillis()
)

object LedgerFormatters {
    private val moneyFormat = DecimalFormat("#,##0.00")
    private val shortDateFormatter = DateTimeFormatter.ofPattern("MM.dd", Locale.getDefault())
    private val dayLabelFormatter = DateTimeFormatter.ofPattern("MM.dd EEEE", Locale.getDefault())

    fun money(value: Double, currency: CurrencyCode = CurrencyCode.CNY): String {
        return "${currency.symbol}${moneyFormat.format(value)}"
    }

    fun signedMoney(
        value: Double,
        type: TransactionType,
        currency: CurrencyCode = CurrencyCode.CNY
    ): String {
        val prefix = when (type) {
            TransactionType.EXPENSE -> "-"
            TransactionType.INCOME -> "+"
        }
        return "$prefix${money(value, currency)}"
    }

    fun dayKey(millis: Long): String {
        val date = LocalDate.ofInstant(
            java.time.Instant.ofEpochMilli(millis),
            ZoneId.systemDefault()
        )
        return date.toString()
    }

    fun dayLabel(millis: Long): String {
        val date = LocalDate.ofInstant(
            java.time.Instant.ofEpochMilli(millis),
            ZoneId.systemDefault()
        )
        return date.format(dayLabelFormatter)
    }

    fun shortLabel(date: LocalDate): String = date.format(shortDateFormatter)
}

