package com.example.account.ui.home

import android.content.Context
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.account.R
import com.example.account.data.CategoryLocalizer
import com.example.account.data.CurrencyCode
import com.example.account.data.DaySummary
import com.example.account.data.LedgerCategory
import com.example.account.data.LedgerFormatters
import com.example.account.data.LedgerTransaction
import com.example.account.data.TransactionType
import com.google.android.material.card.MaterialCardView

class HomeDayAdapter(
    private val categories: List<LedgerCategory>,
    private val onDayAdd: (Long) -> Unit,
    private val onTransactionClick: (Long) -> Unit
) : ListAdapter<DaySummary, HomeDayAdapter.DayViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DayViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_day_summary, parent, false)
        return DayViewHolder(view)
    }

    override fun onBindViewHolder(holder: DayViewHolder, position: Int) {
        holder.bind(
            getItem(position),
            categories,
            onDayAdd,
            onTransactionClick
        )
    }

    class DayViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val dayHeader: View = itemView.findViewById(R.id.day_header)
        private val dayLabel: TextView = itemView.findViewById(R.id.day_label)
        private val dayAmountsContainer: View = itemView.findViewById(R.id.day_amounts_container)
        private val dayIncomeText: TextView = itemView.findViewById(R.id.day_income_text)
        private val dayExpenseText: TextView = itemView.findViewById(R.id.day_expense_text)
        private val container: LinearLayout = itemView.findViewById(R.id.transaction_container)
        private val emptyText: TextView = itemView.findViewById(R.id.empty_text)

        fun bind(
            summary: DaySummary,
            categories: List<LedgerCategory>,
            onDayAdd: (Long) -> Unit,
            onTransactionClick: (Long) -> Unit
        ) {
            dayLabel.text = summary.label
            val hasIncome = summary.income > 0.0
            val hasExpense = summary.expense > 0.0
            dayAmountsContainer.visibility = if (hasIncome || hasExpense) View.VISIBLE else View.GONE
            dayIncomeText.visibility = if (hasIncome) View.VISIBLE else View.GONE
            dayExpenseText.visibility = if (hasExpense) View.VISIBLE else View.GONE
            val expenseLayoutParams = dayExpenseText.layoutParams as ViewGroup.MarginLayoutParams
            expenseLayoutParams.marginStart = if (hasIncome && hasExpense) {
                (10 * itemView.resources.displayMetrics.density).toInt()
            } else {
                0
            }
            dayExpenseText.layoutParams = expenseLayoutParams
            dayIncomeText.text = itemView.context.getString(
                R.string.day_income_format,
                LedgerFormatters.money(summary.income)
            )
            dayExpenseText.text = itemView.context.getString(
                R.string.day_expense_format,
                LedgerFormatters.money(summary.expense)
            )

            dayHeader.setOnClickListener {
                onDayAdd(summary.dateMillis)
            }

            container.removeAllViews()
            if (summary.transactions.isEmpty()) {
                emptyText.visibility = View.VISIBLE
                return
            }
            emptyText.visibility = View.GONE

            summary.transactions.forEachIndexed { index, transaction ->
                val row = LayoutInflater.from(itemView.context)
                    .inflate(R.layout.item_transaction_row, container, false)
                bindTransactionRow(
                    row = row,
                    transaction = transaction,
                    categories = categories,
                    isLast = index == summary.transactions.lastIndex,
                    onTransactionClick = onTransactionClick
                )
                container.addView(row)
            }
        }

        private fun bindTransactionRow(
            row: View,
            transaction: LedgerTransaction,
            categories: List<LedgerCategory>,
            isLast: Boolean,
            onTransactionClick: (Long) -> Unit
        ) {
            val rowContent = row.findViewById<View>(R.id.row_content)
            val category = categories.firstOrNull { it.id == transaction.categoryId }
            val icon = row.findViewById<ImageView>(R.id.category_icon)
            val iconSymbol = row.findViewById<TextView>(R.id.category_icon_symbol)
            val iconCard = row.findViewById<MaterialCardView>(R.id.icon_card)
            val name = row.findViewById<TextView>(R.id.category_name)
            val note = row.findViewById<TextView>(R.id.note_text)
            val amount = row.findViewById<TextView>(R.id.amount_text)
            val amountCny = row.findViewById<TextView>(R.id.amount_cny_text)
            val refunded = row.findViewById<TextView>(R.id.refunded_text)
            val divider = row.findViewById<View>(R.id.row_divider)
            val localizedCategoryName = CategoryLocalizer.displayName(itemView.context, category)

            val typeColor = category?.accentColor ?: if (transaction.type == TransactionType.EXPENSE) {
                itemView.context.getColor(R.color.expense_color)
            } else {
                itemView.context.getColor(R.color.income_color)
            }
            val symbolTypeface = resolveSymbolTypeface(itemView.context)
            if (symbolTypeface != null && !category?.iconGlyph.isNullOrBlank()) {
                iconSymbol.visibility = View.VISIBLE
                icon.visibility = View.GONE
                iconSymbol.typeface = symbolTypeface
                iconSymbol.fontFeatureSettings = "'liga'"
                iconSymbol.text = CategoryLocalizer.normalizeIconGlyph(category?.iconGlyph.orEmpty())
                iconSymbol.contentDescription = localizedCategoryName
                iconSymbol.setTextColor(typeColor)
            } else {
                iconSymbol.visibility = View.GONE
                icon.visibility = View.VISIBLE
                icon.setImageResource(category?.iconRes ?: android.R.drawable.ic_menu_help)
                icon.setColorFilter(typeColor)
            }
            iconCard.setCardBackgroundColor(
                ColorUtils.setAlphaComponent(typeColor, if (transaction.type == TransactionType.EXPENSE) 28 else 24)
            )

            name.text = localizedCategoryName
            val hasNote = transaction.note.isNotBlank()
            if (hasNote) {
                note.visibility = View.VISIBLE
                note.text = transaction.note.trim()
                name.translationY = -2f * row.resources.displayMetrics.density
            } else {
                note.visibility = View.GONE
                note.text = ""
                name.translationY = 0f
            }
            amount.text = LedgerFormatters.signedMoney(
                value = transaction.amount,
                type = transaction.type,
                currency = transaction.currency
            )
            amount.setTextColor(
                if (transaction.type == TransactionType.EXPENSE) {
                    itemView.context.getColor(R.color.expense_color)
                } else {
                    itemView.context.getColor(R.color.income_color)
                }
            )
            if (transaction.currency != CurrencyCode.CNY) {
                amountCny.visibility = View.VISIBLE
                val cnySignedAmount = LedgerFormatters.signedMoney(
                    value = transaction.amountCny,
                    type = transaction.type,
                    currency = CurrencyCode.CNY
                )
                amountCny.text = "($cnySignedAmount)"
            } else {
                amountCny.visibility = View.GONE
                amountCny.text = ""
            }
            if (transaction.type == TransactionType.EXPENSE && transaction.refundedAmount > 0.0) {
                refunded.visibility = View.VISIBLE
                refunded.text = itemView.context.getString(
                    R.string.transaction_refunded_amount_format,
                    LedgerFormatters.money(transaction.refundedAmount, transaction.currency)
                )
            } else {
                refunded.visibility = View.GONE
                refunded.text = ""
            }
            divider.visibility = if (isLast) View.GONE else View.VISIBLE

            rowContent.setOnClickListener { onTransactionClick(transaction.id) }
            rowContent.setOnLongClickListener {
                onTransactionClick(transaction.id)
                true
            }
        }

        companion object {
            private var symbolTypefaceResolved = false
            private var symbolTypeface: Typeface? = null

            private fun resolveSymbolTypeface(context: Context): Typeface? {
                if (!symbolTypefaceResolved) {
                    symbolTypeface = try {
                        ResourcesCompat.getFont(context, R.font.material_symbols_outlined_static)
                    } catch (_: Throwable) {
                        null
                    }
                    symbolTypefaceResolved = true
                }
                return symbolTypeface
            }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<DaySummary>() {
        override fun areItemsTheSame(oldItem: DaySummary, newItem: DaySummary): Boolean {
            return oldItem.dayKey == newItem.dayKey
        }

        override fun areContentsTheSame(oldItem: DaySummary, newItem: DaySummary): Boolean {
            return oldItem == newItem
        }
    }
}
