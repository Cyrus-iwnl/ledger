package com.example.account.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import java.time.YearMonth

class LedgerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = LedgerRepository(application)
    private val _dashboard = MutableLiveData<LedgerDashboard>()
    val dashboard: LiveData<LedgerDashboard> = _dashboard
    private val _insights = MutableLiveData<LedgerInsights>()
    val insights: LiveData<LedgerInsights> = _insights
    private val _monthlyBudget = MutableLiveData<Double?>()
    val monthlyBudget: LiveData<Double?> = _monthlyBudget
    private var currentBudgetMonth: YearMonth = YearMonth.now()
    private var currentDashboardMonth: YearMonth = YearMonth.now()

    private var windowDays: Int = DEFAULT_WINDOW_DAYS

    init {
        _monthlyBudget.value = repository.getMonthlyBudget(currentBudgetMonth)
        refresh()
    }

    fun setWindowDays(days: Int) {
        windowDays = days
        refresh()
    }

    fun refresh() {
        _dashboard.value = repository.getDashboard(windowDays, currentDashboardMonth)
        _insights.value = repository.getInsights()
    }

    fun setDashboardMonth(month: YearMonth) {
        currentDashboardMonth = month
        refresh()
    }

    fun categoriesFor(type: TransactionType): List<LedgerCategory> = repository.categoriesFor(type)

    fun getAllCategories(): List<LedgerCategory> = repository.getAllCategories()

    fun getAllTransactions(): List<LedgerTransaction> = repository.getAllTransactions()

    fun getDraftForTransaction(id: Long): TransactionDraft? = repository.getDraftForTransaction(id)

    fun getTransaction(id: Long): LedgerTransaction? = repository.findTransaction(id)

    fun saveTransaction(draft: TransactionDraft): Long {
        val savedId = repository.saveTransaction(draft)
        refresh()
        return savedId
    }

    fun deleteTransaction(id: Long) {
        repository.deleteTransaction(id)
        refresh()
    }

    fun loadMonthlyBudget(month: YearMonth) {
        currentBudgetMonth = month
        _monthlyBudget.value = repository.getMonthlyBudget(month)
    }

    fun getMonthlyBudget(month: YearMonth): Double? {
        return if (currentBudgetMonth == month) {
            _monthlyBudget.value ?: repository.getMonthlyBudget(month)
        } else {
            repository.getMonthlyBudget(month)
        }
    }

    fun setMonthlyBudget(month: YearMonth, value: Double) {
        repository.setMonthlyBudget(month, value)
        currentBudgetMonth = month
        _monthlyBudget.value = value
    }

    fun getMonthlyExpense(month: YearMonth): Double = repository.getMonthlyExpense(month)

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(LedgerViewModel::class.java)) {
                return LedgerViewModel(application) as T
            }
            throw IllegalArgumentException("未知的 ViewModel 类型: ${modelClass.name}")
        }
    }

    companion object {
        private const val DEFAULT_WINDOW_DAYS = 7
    }
}
