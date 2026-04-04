package com.example.account.ui.insights

import com.example.account.data.TransactionType
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

enum class InsightId {
    A1_EXPENSE_INCREASE,
    A4_SURPLUS_DROP,
    B1_SURPLUS_PROJECTION_LOW,
    B2_BUDGET_OVERSPEND_RISK,
    C1_CATEGORY_SPIKE,
    D1_LARGE_TRANSACTION,
    F1_WEEKEND_HIGHER,
    H1_TOP_SAVABLE_CATEGORY
}

enum class InsightType {
    SUMMARY,
    RISK,
    ANOMALY,
    HABIT,
    SUGGESTION
}

enum class InsightAction {
    TREND,
    CATEGORY,
    CALENDAR,
    NONE
}

enum class InsightPanelStatus {
    STABLE,
    ATTENTION,
    RISK,
    IMPROVING
}

data class RuleTransaction(
    val type: TransactionType,
    val categoryId: String,
    val amount: Double,
    val timestampMillis: Long
)

data class InsightContext(
    val granularity: InsightsGranularity,
    val year: Int,
    val monthIndex: Int,
    val now: LocalDate,
    val transactionsCurrent: List<RuleTransaction>,
    val allTransactions: List<RuleTransaction>,
    val monthlyBudget: Double?,
    val yearlyBudget: Double?
)

data class InsightItem(
    val insightId: InsightId,
    val insightType: InsightType,
    val priorityScore: Int,
    val confidence: Double,
    val action: InsightAction,
    val dedupeKey: String? = null,
    val categoryId: String? = null,
    val numeric: Map<String, Double> = emptyMap(),
    val text: Map<String, String> = emptyMap(),
    val timestampMillis: Long? = null
)

data class InsightPanelModel(
    val status: InsightPanelStatus,
    val allScopeHint: Boolean,
    val primaryInsights: List<InsightItem>,
    val suggestion: InsightItem?
)

private data class PeriodTotal(
    val income: Double,
    val expense: Double
) {
    val surplus: Double get() = income - expense
    val surplusRate: Double
        get() = when {
            income > 0.0 -> surplus / income
            expense > 0.0 -> -1.0
            else -> 0.0
        }
}

object InsightsRuleEngine {

    private val zoneId: ZoneId = ZoneId.systemDefault()

    fun evaluate(context: InsightContext): InsightPanelModel {
        if (context.granularity == InsightsGranularity.ALL) {
            return InsightPanelModel(
                status = InsightPanelStatus.ATTENTION,
                allScopeHint = true,
                primaryInsights = emptyList(),
                suggestion = null
            )
        }
        if (context.transactionsCurrent.isEmpty()) {
            return InsightPanelModel(
                status = InsightPanelStatus.STABLE,
                allScopeHint = false,
                primaryInsights = emptyList(),
                suggestion = null
            )
        }

        val currentTotal = totals(context.transactionsCurrent)
        val previousTotal = previousPeriodTotal(context)
        val baselineTotals = baselineTotals(context)

        val candidateInsights = mutableListOf<InsightItem>()
        addA1(candidateInsights, context, currentTotal, previousTotal)
        addA4(candidateInsights, context, currentTotal, previousTotal, baselineTotals)
        addB1(candidateInsights, context, currentTotal, baselineTotals)
        addB2(candidateInsights, context, currentTotal)
        addC1(candidateInsights, context)
        addD1(candidateInsights, context)
        addF1(candidateInsights, context)

        val suggestion = evaluateH1(context)
        val dedupedMain = dedupe(candidateInsights)
            .sortedByDescending { it.priorityScore }
            .take(3)

        val status = resolveStatus(
            mainInsights = dedupedMain,
            currentTotal = currentTotal,
            previousTotal = previousTotal
        )

        return InsightPanelModel(
            status = status,
            allScopeHint = false,
            primaryInsights = dedupedMain,
            suggestion = suggestion
        )
    }

    private fun addA1(
        sink: MutableList<InsightItem>,
        context: InsightContext,
        current: PeriodTotal,
        previous: PeriodTotal?
    ) {
        val previousExpense = previous?.expense ?: return
        if (previousExpense <= 0.0) return
        val delta = current.expense - previousExpense
        val ratio = delta / previousExpense
        if (current.expense > previousExpense * 1.1 && delta >= 100.0) {
            sink += buildInsight(
                id = InsightId.A1_EXPENSE_INCREASE,
                type = InsightType.SUMMARY,
                impactAmount = delta,
                anomalyRatio = ratio,
                actionabilityScore = 70,
                confidence = 0.9,
                action = InsightAction.TREND,
                numeric = mapOf(
                    "current_expense" to current.expense,
                    "previous_expense" to previousExpense,
                    "delta_amount" to delta,
                    "delta_ratio" to ratio
                )
            )
        }
    }

    private fun addA4(
        sink: MutableList<InsightItem>,
        context: InsightContext,
        current: PeriodTotal,
        previous: PeriodTotal?,
        baseline: List<PeriodTotal>
    ) {
        val previousSurplus = previous?.surplus
        val conditionDelta = previousSurplus != null && current.surplus < previousSurplus - 200.0
        val avgSurplusRate = baseline.map { it.surplusRate }.takeIf { it.isNotEmpty() }?.average()
        val conditionRate = avgSurplusRate != null && current.surplusRate < avgSurplusRate - 0.1
        if (!conditionDelta && !conditionRate) return

        val deltaAmountAbs = previousSurplus?.let { abs(current.surplus - it) } ?: abs(current.surplus)
        val ratioBase = if (avgSurplusRate != null && abs(avgSurplusRate) > 1e-6) {
            abs((current.surplusRate - avgSurplusRate) / abs(avgSurplusRate))
        } else {
            abs(current.surplusRate)
        }
        sink += buildInsight(
            id = InsightId.A4_SURPLUS_DROP,
            type = InsightType.RISK,
            impactAmount = deltaAmountAbs,
            anomalyRatio = ratioBase,
            actionabilityScore = 100,
            confidence = if (avgSurplusRate != null) 0.9 else 0.7,
            action = InsightAction.TREND,
            numeric = mapOf(
                "current_surplus" to current.surplus,
                "current_surplus_rate" to current.surplusRate,
                "delta_amount_abs" to deltaAmountAbs,
                "previous_surplus" to (previousSurplus ?: 0.0),
                "avg_surplus_rate" to (avgSurplusRate ?: 0.0)
            )
        )
    }

    private fun addB1(
        sink: MutableList<InsightItem>,
        context: InsightContext,
        current: PeriodTotal,
        baseline: List<PeriodTotal>
    ) {
        val avgSurplus = baseline.map { it.surplus }.takeIf { it.isNotEmpty() }?.average() ?: return
        val projection = projectedTotals(context, current) ?: return
        if (projection.isEndWindow) return
        if (projection.projectedSurplus >= avgSurplus * 0.8) return

        val deltaRatio = if (abs(avgSurplus) > 1e-6) {
            abs((projection.projectedSurplus - avgSurplus) / abs(avgSurplus))
        } else {
            0.0
        }
        sink += buildInsight(
            id = InsightId.B1_SURPLUS_PROJECTION_LOW,
            type = InsightType.RISK,
            impactAmount = abs(projection.projectedSurplus - avgSurplus),
            anomalyRatio = deltaRatio,
            actionabilityScore = 100,
            confidence = if (baseline.size >= 3) 0.9 else 0.7,
            action = InsightAction.TREND,
            numeric = mapOf(
                "projected_expense" to projection.projectedExpense,
                "projected_income" to projection.projectedIncome,
                "projected_surplus" to projection.projectedSurplus,
                "avg_surplus" to avgSurplus,
                "delta_ratio" to deltaRatio
            )
        )
    }

    private fun addB2(
        sink: MutableList<InsightItem>,
        context: InsightContext,
        current: PeriodTotal
    ) {
        val budget = when (context.granularity) {
            InsightsGranularity.MONTH -> context.monthlyBudget
            InsightsGranularity.YEAR -> context.yearlyBudget
            InsightsGranularity.ALL -> null
        } ?: return
        if (budget <= 0.0) return

        val projection = projectedTotals(context, current) ?: return
        val projectedExpense = projection.projectedExpense
        if (projectedExpense <= budget * 1.05) return
        val gap = projectedExpense - budget
        val ratio = gap / budget
        sink += buildInsight(
            id = InsightId.B2_BUDGET_OVERSPEND_RISK,
            type = InsightType.RISK,
            impactAmount = gap,
            anomalyRatio = ratio,
            actionabilityScore = 100,
            confidence = 0.9,
            action = InsightAction.TREND,
            numeric = mapOf(
                "budget" to budget,
                "projected_expense" to projectedExpense,
                "projected_budget_gap" to gap,
                "budget_used_ratio" to (current.expense / budget)
            )
        )
    }

    private fun addC1(
        sink: MutableList<InsightItem>,
        context: InsightContext
    ) {
        val currentByCategory = context.transactionsCurrent
            .asSequence()
            .filter { it.type == TransactionType.EXPENSE }
            .groupBy { it.categoryId }
            .mapValues { (_, list) -> list.sumOf { it.amount } }

        if (currentByCategory.isEmpty()) return
        val topCategoryIds = currentByCategory.entries
            .sortedByDescending { it.value }
            .take(5)
            .map { it.key }
            .toSet()

        topCategoryIds.forEach { categoryId ->
            val currentAmount = currentByCategory[categoryId] ?: return@forEach
            val baselineValues = categoryBaseline(context, categoryId)
            if (baselineValues.isEmpty()) return@forEach
            val baseline = baselineValues.average()
            if (baseline <= 0.0) return@forEach
            val delta = currentAmount - baseline
            val ratio = delta / baseline
            if (currentAmount > baseline * 1.3 && delta >= 100.0) {
                sink += buildInsight(
                    id = InsightId.C1_CATEGORY_SPIKE,
                    type = InsightType.ANOMALY,
                    impactAmount = delta,
                    anomalyRatio = ratio,
                    actionabilityScore = 70,
                    confidence = if (baselineValues.size >= 3) 0.9 else 0.7,
                    action = InsightAction.CATEGORY,
                    categoryId = categoryId,
                    dedupeKey = "category_up_$categoryId",
                    numeric = mapOf(
                        "category_current" to currentAmount,
                        "category_baseline" to baseline,
                        "delta_amount" to delta,
                        "delta_ratio" to ratio
                    )
                )
            }
        }
    }

    private fun addD1(
        sink: MutableList<InsightItem>,
        context: InsightContext
    ) {
        val nowDate = context.now
        val recentExpenses = context.transactionsCurrent
            .asSequence()
            .filter { it.type == TransactionType.EXPENSE }
            .filter {
                val txDate = localDate(it.timestampMillis)
                !txDate.isAfter(nowDate) && !txDate.isBefore(nowDate.minusDays(6))
            }
            .toList()
        if (recentExpenses.isEmpty()) return

        val baselineSample = context.allTransactions
            .asSequence()
            .filter { it.type == TransactionType.EXPENSE }
            .filter {
                val date = localDate(it.timestampMillis)
                date.isAfter(nowDate.minusDays(90)) && date.isBefore(nowDate)
            }
            .map { it.amount }
            .filter { it > 0.0 }
            .sorted()
            .toList()
        val p95 = percentile(baselineSample, 0.95)
        val threshold = max(p95, 300.0)
        val hit = recentExpenses
            .filter { it.amount >= threshold }
            .maxByOrNull { it.amount }
            ?: return
        sink += buildInsight(
            id = InsightId.D1_LARGE_TRANSACTION,
            type = InsightType.ANOMALY,
            impactAmount = hit.amount,
            anomalyRatio = if (threshold > 0) hit.amount / threshold else 1.0,
            actionabilityScore = 70,
            confidence = if (baselineSample.size >= 20) 0.9 else 0.7,
            action = InsightAction.CALENDAR,
            numeric = mapOf(
                "amount" to hit.amount,
                "p95" to p95,
                "threshold" to threshold
            ),
            timestampMillis = hit.timestampMillis
        )
    }

    private fun addF1(
        sink: MutableList<InsightItem>,
        context: InsightContext
    ) {
        val dateTotals = context.transactionsCurrent
            .asSequence()
            .filter { it.type == TransactionType.EXPENSE }
            .groupBy { localDate(it.timestampMillis) }
            .mapValues { (_, list) -> list.sumOf { it.amount } }

        val periodDates = activeDatesForScope(context)
        if (periodDates.isEmpty()) return

        var weekendTotal = 0.0
        var weekdayTotal = 0.0
        var weekendCount = 0
        var weekdayCount = 0
        periodDates.forEach { date ->
            val value = dateTotals[date] ?: 0.0
            val weekend = date.dayOfWeek.value >= 6
            if (weekend) {
                weekendTotal += value
                weekendCount++
            } else {
                weekdayTotal += value
                weekdayCount++
            }
        }
        if (weekendCount == 0 || weekdayCount == 0) return

        val weekendAvg = weekendTotal / weekendCount
        val weekdayAvg = weekdayTotal / weekdayCount
        if (weekdayAvg <= 0.0) return
        if (weekendAvg < weekdayAvg * 1.4) return
        if (weekendAvg - weekdayAvg < 30.0) return

        val multiple = weekendAvg / weekdayAvg
        sink += buildInsight(
            id = InsightId.F1_WEEKEND_HIGHER,
            type = InsightType.HABIT,
            impactAmount = weekendAvg - weekdayAvg,
            anomalyRatio = multiple - 1.0,
            actionabilityScore = 70,
            confidence = if (periodDates.size >= 14) 0.9 else 0.7,
            action = InsightAction.CALENDAR,
            numeric = mapOf(
                "weekend_avg" to weekendAvg,
                "weekday_avg" to weekdayAvg,
                "multiple" to multiple
            )
        )
    }

    private fun evaluateH1(context: InsightContext): InsightItem? {
        val currentByCategory = context.transactionsCurrent
            .asSequence()
            .filter { it.type == TransactionType.EXPENSE }
            .groupBy { it.categoryId }
            .mapValues { (_, list) -> list.sumOf { it.amount } }
        if (currentByCategory.isEmpty()) return null

        val candidates = mutableListOf<InsightItem>()
        currentByCategory.forEach { (categoryId, currentAmount) ->
            val baselineValues = categoryBaseline(context, categoryId)
            if (baselineValues.isEmpty()) return@forEach
            val baseline = baselineValues.average()
            if (baseline <= 0.0) return@forEach
            val delta = currentAmount - baseline
            val ratio = delta / baseline
            if (currentAmount > baseline * 1.2 && delta >= 100.0) {
                candidates += buildInsight(
                    id = InsightId.H1_TOP_SAVABLE_CATEGORY,
                    type = InsightType.SUGGESTION,
                    impactAmount = delta,
                    anomalyRatio = ratio,
                    actionabilityScore = 100,
                    confidence = if (baselineValues.size >= 3) 0.9 else 0.7,
                    action = InsightAction.CATEGORY,
                    categoryId = categoryId,
                    dedupeKey = "category_up_$categoryId",
                    numeric = mapOf(
                        "savable_amount" to delta,
                        "category_current" to currentAmount,
                        "category_baseline" to baseline
                    )
                )
            }
        }
        return candidates.maxByOrNull { it.priorityScore }
    }

    private fun dedupe(items: List<InsightItem>): List<InsightItem> {
        if (items.isEmpty()) return emptyList()
        val byKey = linkedMapOf<String, InsightItem>()
        val noKey = mutableListOf<InsightItem>()
        items.forEach { item ->
            val key = item.dedupeKey
            if (key.isNullOrBlank()) {
                noKey += item
            } else {
                val current = byKey[key]
                if (current == null || item.priorityScore > current.priorityScore) {
                    byKey[key] = item
                }
            }
        }
        return byKey.values + noKey
    }

    private fun resolveStatus(
        mainInsights: List<InsightItem>,
        currentTotal: PeriodTotal,
        previousTotal: PeriodTotal?
    ): InsightPanelStatus {
        val hasRisk = mainInsights.any { it.insightType == InsightType.RISK }
        if (hasRisk) return InsightPanelStatus.RISK
        if (mainInsights.isNotEmpty()) return InsightPanelStatus.ATTENTION
        val previousExpense = previousTotal?.expense ?: return InsightPanelStatus.STABLE
        if (previousExpense > 0.0 && currentTotal.expense <= previousExpense * 0.9 && currentTotal.surplus >= (previousTotal?.surplus ?: 0.0)) {
            return InsightPanelStatus.IMPROVING
        }
        return InsightPanelStatus.STABLE
    }

    private fun totals(transactions: List<RuleTransaction>): PeriodTotal {
        val income = transactions.asSequence()
            .filter { it.type == TransactionType.INCOME }
            .sumOf { it.amount }
        val expense = transactions.asSequence()
            .filter { it.type == TransactionType.EXPENSE }
            .sumOf { it.amount }
        return PeriodTotal(income = income, expense = expense)
    }

    private fun previousPeriodTotal(context: InsightContext): PeriodTotal? {
        val previousTransactions = periodTransactions(context, offset = -1)
        return previousTransactions.takeIf { it.isNotEmpty() }?.let(::totals)
    }

    private fun baselineTotals(context: InsightContext): List<PeriodTotal> {
        val values = mutableListOf<PeriodTotal>()
        for (offset in 1..3) {
            val transactions = periodTransactions(context, offset = -offset)
            if (transactions.isNotEmpty()) {
                values += totals(transactions)
            }
        }
        return values
    }

    private fun categoryBaseline(context: InsightContext, categoryId: String): List<Double> {
        val values = mutableListOf<Double>()
        for (offset in 1..3) {
            val transactions = periodTransactions(context, offset = -offset)
                .filter { it.type == TransactionType.EXPENSE && it.categoryId == categoryId }
            if (transactions.isNotEmpty()) {
                values += transactions.sumOf { it.amount }
            }
        }
        return values
    }

    private fun periodTransactions(context: InsightContext, offset: Int): List<RuleTransaction> {
        if (context.granularity == InsightsGranularity.MONTH) {
            val target = YearMonth.of(context.year, context.monthIndex + 1).plusMonths(offset.toLong())
            return context.allTransactions.filter { tx ->
                YearMonth.from(Instant.ofEpochMilli(tx.timestampMillis).atZone(zoneId)) == target
            }
        }
        val targetYear = context.year + offset
        return context.allTransactions.filter { tx ->
            Instant.ofEpochMilli(tx.timestampMillis).atZone(zoneId).year == targetYear
        }
    }

    private fun activeDatesForScope(context: InsightContext): List<LocalDate> {
        return if (context.granularity == InsightsGranularity.MONTH) {
            val month = YearMonth.of(context.year, context.monthIndex + 1)
            val isCurrent = month.year == context.now.year && month.monthValue == context.now.monthValue
            val dayCount = if (isCurrent) context.now.dayOfMonth else month.lengthOfMonth()
            (1..dayCount).map { month.atDay(it) }
        } else {
            val isCurrentYear = context.year == context.now.year
            val maxMonth = if (isCurrentYear) context.now.monthValue else 12
            val dates = mutableListOf<LocalDate>()
            for (month in 1..maxMonth) {
                val ym = YearMonth.of(context.year, month)
                val maxDay = if (isCurrentYear && month == context.now.monthValue) context.now.dayOfMonth else ym.lengthOfMonth()
                for (day in 1..maxDay) {
                    dates += ym.atDay(day)
                }
            }
            dates
        }
    }

    private data class ProjectionResult(
        val projectedIncome: Double,
        val projectedExpense: Double,
        val projectedSurplus: Double,
        val isEndWindow: Boolean
    )

    private fun projectedTotals(context: InsightContext, current: PeriodTotal): ProjectionResult? {
        return if (context.granularity == InsightsGranularity.MONTH) {
            val target = YearMonth.of(context.year, context.monthIndex + 1)
            val isCurrentMonth = target.year == context.now.year && target.monthValue == context.now.monthValue
            if (!isCurrentMonth) return null
            val elapsedDays = context.now.dayOfMonth.coerceAtLeast(1)
            val totalDays = target.lengthOfMonth()
            val projectedExpense = current.expense / elapsedDays * totalDays
            val projectedIncome = current.income / elapsedDays * totalDays
            ProjectionResult(
                projectedIncome = projectedIncome,
                projectedExpense = projectedExpense,
                projectedSurplus = projectedIncome - projectedExpense,
                isEndWindow = totalDays - elapsedDays < 3
            )
        } else {
            val isCurrentYear = context.year == context.now.year
            if (!isCurrentYear) return null
            val elapsedMonths = context.now.monthValue.coerceAtLeast(1)
            val projectedExpense = current.expense / elapsedMonths * 12
            val projectedIncome = current.income / elapsedMonths * 12
            ProjectionResult(
                projectedIncome = projectedIncome,
                projectedExpense = projectedExpense,
                projectedSurplus = projectedIncome - projectedExpense,
                isEndWindow = elapsedMonths >= 12
            )
        }
    }

    private fun buildInsight(
        id: InsightId,
        type: InsightType,
        impactAmount: Double,
        anomalyRatio: Double,
        actionabilityScore: Int,
        confidence: Double,
        action: InsightAction,
        dedupeKey: String? = null,
        categoryId: String? = null,
        numeric: Map<String, Double> = emptyMap(),
        text: Map<String, String> = emptyMap(),
        timestampMillis: Long? = null
    ): InsightItem {
        val impactScore = when {
            impactAmount >= 1000 -> 100
            impactAmount >= 500 -> 80
            impactAmount >= 200 -> 60
            impactAmount >= 100 -> 40
            else -> 20
        }
        val anomalyPercent = abs(anomalyRatio) * 100
        val anomalyScore = when {
            anomalyPercent >= 100 -> 100
            anomalyPercent >= 50 -> 80
            anomalyPercent >= 30 -> 60
            anomalyPercent >= 15 -> 40
            else -> 20
        }
        val confidenceScore = when {
            confidence >= 0.85 -> 100
            confidence >= 0.6 -> 70
            else -> 40
        }
        val priority = (
            impactScore * 0.4 +
                anomalyScore * 0.3 +
                actionabilityScore * 0.2 +
                confidenceScore * 0.1
            ).roundToInt()

        return InsightItem(
            insightId = id,
            insightType = type,
            priorityScore = priority,
            confidence = confidence,
            action = action,
            dedupeKey = dedupeKey,
            categoryId = categoryId,
            numeric = numeric,
            text = text,
            timestampMillis = timestampMillis
        )
    }

    private fun percentile(sortedValues: List<Double>, percentile: Double): Double {
        if (sortedValues.isEmpty()) return 0.0
        val clamped = percentile.coerceIn(0.0, 1.0)
        val index = ((sortedValues.size - 1) * clamped).roundToInt()
        return sortedValues[index]
    }

    private fun localDate(timestampMillis: Long): LocalDate {
        return Instant.ofEpochMilli(timestampMillis).atZone(zoneId).toLocalDate()
    }
}
