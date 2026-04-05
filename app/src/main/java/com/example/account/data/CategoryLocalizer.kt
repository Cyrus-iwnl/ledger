package com.example.account.data

import android.content.Context
import com.example.account.R

object CategoryLocalizer {

    private val iconGlyphAliases = mapOf(
        "discount" to "local_offer",
        "insect_control" to "pest_control",
        "leaf" to "eco",
        "flower" to "local_florist",
        "gamepad" to "sports_esports",
        "tag" to "sell",
        "face_smile" to "mood",
        "health_and_beauty" to "spa",
        "eye_tracking" to "visibility",
        "deployed_code" to "code",
        "package_2" to "inventory_2",
        "conveyor_belt" to "local_shipping"
    )

    fun displayName(context: Context, category: LedgerCategory?): String {
        val fallback = category?.name ?: context.getString(R.string.category_uncategorized)
        return nameForId(context, category?.id, fallback)
    }

    fun nameForId(context: Context, categoryId: String?, fallback: String): String {
        val key = categoryId ?: return fallback
        val resId = when (key) {
            "expense_meals" -> R.string.category_meals
            "expense_snacks" -> R.string.category_snacks
            "expense_drinks" -> R.string.category_drinks
            "expense_clothing" -> R.string.category_clothing
            "expense_transport" -> R.string.category_transport
            "expense_travel" -> R.string.category_travel
            "expense_fun" -> R.string.category_fun
            "expense_utility" -> R.string.category_utility
            "expense_learn" -> R.string.category_learn
            "expense_daily" -> R.string.category_daily
            "expense_beauty" -> R.string.category_beauty
            "expense_medical" -> R.string.category_medical
            "expense_sports" -> R.string.category_sports
            "expense_gifts" -> R.string.category_gifts
            "expense_digital" -> R.string.category_digital
            "expense_pets" -> R.string.category_pets
            "expense_home" -> R.string.category_home
            "expense_comm" -> R.string.category_comm
            "expense_social" -> R.string.category_social
            "expense_kids" -> R.string.category_kids
            "expense_other" -> R.string.category_other
            "income_salary" -> R.string.category_salary
            "income_bonus" -> R.string.category_bonus
            "income_investment" -> R.string.category_investment
            "income_refund" -> R.string.category_refund
            "income_other" -> R.string.category_other
            else -> null
        }
        return if (resId == null) fallback else context.getString(resId)
    }

    fun normalizeIconGlyph(glyph: String): String {
        val trimmed = glyph.trim()
        if (trimmed.isEmpty()) return trimmed
        return iconGlyphAliases[trimmed] ?: trimmed
    }
}

