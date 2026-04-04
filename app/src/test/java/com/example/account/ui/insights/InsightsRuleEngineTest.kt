package com.example.account.ui.insights

import com.example.account.data.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class InsightsRuleEngineTest {

    private val zoneId: ZoneId = ZoneId.systemDefault()

    @Test
    fun `A1 triggers when expense grows over threshold`() {
        val now = LocalDate.of(2026, 4, 20)
        val transactions = listOf(
            tx(LocalDate.of(2026, 3, 10), TransactionType.EXPENSE, 1000.0, "expense_meals"),
            tx(LocalDate.of(2026, 4, 10), TransactionType.EXPENSE, 1300.0, "expense_meals")
        )
        val model = InsightsRuleEngine.evaluate(
            monthContext(now, 2026, 4, transactions, monthlyBudget = 5000.0)
        )

        assertTrue(model.primaryInsights.any { it.insightId == InsightId.A1_EXPENSE_INCREASE })
    }

    @Test
    fun `A1 does not trigger when absolute delta is too small`() {
        val now = LocalDate.of(2026, 4, 20)
        val transactions = listOf(
            tx(LocalDate.of(2026, 3, 10), TransactionType.EXPENSE, 100.0, "expense_meals"),
            tx(LocalDate.of(2026, 4, 10), TransactionType.EXPENSE, 190.0, "expense_meals")
        )
        val model = InsightsRuleEngine.evaluate(
            monthContext(now, 2026, 4, transactions)
        )

        assertFalse(model.primaryInsights.any { it.insightId == InsightId.A1_EXPENSE_INCREASE })
    }

    @Test
    fun `B2 triggers when projection exceeds budget`() {
        val now = LocalDate.of(2026, 4, 10)
        val transactions = listOf(
            tx(LocalDate.of(2026, 4, 1), TransactionType.EXPENSE, 800.0, "expense_meals"),
            tx(LocalDate.of(2026, 4, 8), TransactionType.EXPENSE, 700.0, "expense_transport")
        )
        val model = InsightsRuleEngine.evaluate(
            monthContext(now, 2026, 4, transactions, monthlyBudget = 1800.0)
        )

        assertTrue(model.primaryInsights.any { it.insightId == InsightId.B2_BUDGET_OVERSPEND_RISK })
    }

    @Test
    fun `B2 does not trigger without budget`() {
        val now = LocalDate.of(2026, 4, 10)
        val transactions = listOf(
            tx(LocalDate.of(2026, 4, 1), TransactionType.EXPENSE, 1000.0, "expense_meals")
        )
        val model = InsightsRuleEngine.evaluate(monthContext(now, 2026, 4, transactions))
        assertFalse(model.primaryInsights.any { it.insightId == InsightId.B2_BUDGET_OVERSPEND_RISK })
    }

    @Test
    fun `C1 triggers for category spike over 3 month baseline`() {
        val now = LocalDate.of(2026, 4, 20)
        val transactions = mutableListOf<RuleTransaction>()
        transactions += tx(LocalDate.of(2026, 1, 10), TransactionType.EXPENSE, 200.0, "expense_meals")
        transactions += tx(LocalDate.of(2026, 2, 10), TransactionType.EXPENSE, 220.0, "expense_meals")
        transactions += tx(LocalDate.of(2026, 3, 10), TransactionType.EXPENSE, 210.0, "expense_meals")
        transactions += tx(LocalDate.of(2026, 4, 10), TransactionType.EXPENSE, 500.0, "expense_meals")

        val model = InsightsRuleEngine.evaluate(monthContext(now, 2026, 4, transactions))
        assertTrue(model.primaryInsights.any { it.insightId == InsightId.C1_CATEGORY_SPIKE })
    }

    @Test
    fun `D1 triggers for large recent transaction`() {
        val now = LocalDate.of(2026, 4, 20)
        val transactions = mutableListOf<RuleTransaction>()
        for (day in 1..30) {
            transactions += tx(LocalDate.of(2026, 3, day.coerceAtMost(28)), TransactionType.EXPENSE, 80.0 + day, "expense_daily")
        }
        transactions += tx(LocalDate.of(2026, 4, 18), TransactionType.EXPENSE, 900.0, "expense_fun")

        val model = InsightsRuleEngine.evaluate(monthContext(now, 2026, 4, transactions))
        assertTrue(model.primaryInsights.any { it.insightId == InsightId.D1_LARGE_TRANSACTION })
    }

    @Test
    fun `F1 triggers when weekend average is much higher`() {
        val now = LocalDate.of(2026, 4, 20)
        val transactions = mutableListOf<RuleTransaction>()
        // Weekdays low
        transactions += tx(LocalDate.of(2026, 4, 6), TransactionType.EXPENSE, 40.0, "expense_daily") // Mon
        transactions += tx(LocalDate.of(2026, 4, 7), TransactionType.EXPENSE, 35.0, "expense_daily")
        transactions += tx(LocalDate.of(2026, 4, 8), TransactionType.EXPENSE, 42.0, "expense_daily")
        // Weekend high
        transactions += tx(LocalDate.of(2026, 4, 11), TransactionType.EXPENSE, 220.0, "expense_fun") // Sat
        transactions += tx(LocalDate.of(2026, 4, 12), TransactionType.EXPENSE, 260.0, "expense_fun") // Sun

        val model = InsightsRuleEngine.evaluate(monthContext(now, 2026, 4, transactions))
        assertTrue(model.primaryInsights.any { it.insightId == InsightId.F1_WEEKEND_HIGHER })
    }

    @Test
    fun `H1 appears in suggestion and main list capped at 3`() {
        val now = LocalDate.of(2026, 4, 20)
        val transactions = mutableListOf<RuleTransaction>()
        // Baselines
        transactions += tx(LocalDate.of(2026, 1, 10), TransactionType.EXPENSE, 150.0, "expense_meals")
        transactions += tx(LocalDate.of(2026, 2, 10), TransactionType.EXPENSE, 150.0, "expense_meals")
        transactions += tx(LocalDate.of(2026, 3, 10), TransactionType.EXPENSE, 150.0, "expense_meals")
        // Current
        transactions += tx(LocalDate.of(2026, 4, 10), TransactionType.EXPENSE, 500.0, "expense_meals")
        transactions += tx(LocalDate.of(2026, 4, 12), TransactionType.EXPENSE, 900.0, "expense_fun")

        val model = InsightsRuleEngine.evaluate(monthContext(now, 2026, 4, transactions, monthlyBudget = 1000.0))
        assertTrue(model.primaryInsights.size <= 3)
        assertNotNull(model.suggestion)
        assertEquals(InsightId.H1_TOP_SAVABLE_CATEGORY, model.suggestion?.insightId)
    }

    @Test
    fun `ALL scope returns hint and no insights`() {
        val now = LocalDate.of(2026, 4, 20)
        val model = InsightsRuleEngine.evaluate(
            InsightContext(
                granularity = InsightsGranularity.ALL,
                year = 2026,
                monthIndex = 3,
                now = now,
                transactionsCurrent = emptyList(),
                allTransactions = emptyList(),
                monthlyBudget = null,
                yearlyBudget = null
            )
        )
        assertTrue(model.allScopeHint)
        assertTrue(model.primaryInsights.isEmpty())
    }

    @Test
    fun `empty current period should not produce risk insights`() {
        val now = LocalDate.of(2026, 4, 20)
        val transactions = listOf(
            tx(LocalDate.of(2026, 3, 10), TransactionType.INCOME, 3200.0, "income_salary"),
            tx(LocalDate.of(2026, 3, 11), TransactionType.EXPENSE, 1800.0, "expense_home")
        )
        val model = InsightsRuleEngine.evaluate(monthContext(now, 2026, 4, transactions))

        assertEquals(InsightPanelStatus.STABLE, model.status)
        assertTrue(model.primaryInsights.isEmpty())
        assertTrue(model.suggestion == null)
        assertFalse(model.allScopeHint)
    }

    @Test
    fun `year granularity supports A1 comparison`() {
        val now = LocalDate.of(2026, 10, 1)
        val transactions = listOf(
            tx(LocalDate.of(2025, 5, 10), TransactionType.EXPENSE, 2000.0, "expense_meals"),
            tx(LocalDate.of(2026, 5, 10), TransactionType.EXPENSE, 2600.0, "expense_meals")
        )
        val model = InsightsRuleEngine.evaluate(
            yearContext(now, 2026, transactions, yearlyBudget = 10000.0)
        )
        assertTrue(model.primaryInsights.any { it.insightId == InsightId.A1_EXPENSE_INCREASE })
    }

    private fun monthContext(
        now: LocalDate,
        year: Int,
        month: Int,
        transactions: List<RuleTransaction>,
        monthlyBudget: Double? = null
    ): InsightContext {
        val current = transactions.filter {
            val date = LocalDate.ofInstant(Instant.ofEpochMilli(it.timestampMillis), zoneId)
            date.year == year && date.monthValue == month
        }
        return InsightContext(
            granularity = InsightsGranularity.MONTH,
            year = year,
            monthIndex = month - 1,
            now = now,
            transactionsCurrent = current,
            allTransactions = transactions,
            monthlyBudget = monthlyBudget,
            yearlyBudget = null
        )
    }

    private fun yearContext(
        now: LocalDate,
        year: Int,
        transactions: List<RuleTransaction>,
        yearlyBudget: Double? = null
    ): InsightContext {
        val current = transactions.filter {
            val date = LocalDate.ofInstant(Instant.ofEpochMilli(it.timestampMillis), zoneId)
            date.year == year
        }
        return InsightContext(
            granularity = InsightsGranularity.YEAR,
            year = year,
            monthIndex = 0,
            now = now,
            transactionsCurrent = current,
            allTransactions = transactions,
            monthlyBudget = null,
            yearlyBudget = yearlyBudget
        )
    }

    private fun tx(date: LocalDate, type: TransactionType, amount: Double, categoryId: String): RuleTransaction {
        return RuleTransaction(
            type = type,
            categoryId = categoryId,
            amount = amount,
            timestampMillis = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
        )
    }
}
