package com.example.account.ui.insights

enum class InsightsGranularity {
    MONTH,
    YEAR,
    ALL
}

enum class InsightsMetric {
    EXPENSE,
    INCOME,
    BALANCE
}

enum class InsightsCategoryMode {
    EXPENSE,
    INCOME
}

data class InsightsTrendBucket(
    val label: String,
    val detailLabel: String = label,
    val income: Double,
    val expense: Double
)

data class InsightsCategorySlice(
    val categoryId: String,
    val name: String,
    val iconGlyph: String,
    val amount: Double,
    val ratio: Double,
    val accentColor: Int
)
