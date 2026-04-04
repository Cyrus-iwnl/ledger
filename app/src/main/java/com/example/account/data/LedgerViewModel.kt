package com.example.account.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.account.PerfTrace
import java.time.YearMonth

class LedgerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = LedgerRepository(application)
    private val _dashboard = MutableLiveData<LedgerDashboard>()
    val dashboard: LiveData<LedgerDashboard> = _dashboard
    private val _insights = MutableLiveData<LedgerInsights>()
    val insights: LiveData<LedgerInsights> = _insights
    private val _monthlyBudget = MutableLiveData<Double?>()
    val monthlyBudget: LiveData<Double?> = _monthlyBudget
    private val _ledgers = MutableLiveData<List<LedgerBook>>()
    val ledgers: LiveData<List<LedgerBook>> = _ledgers
    private val _currentLedger = MutableLiveData<LedgerBook>()
    val currentLedger: LiveData<LedgerBook> = _currentLedger
    private var currentBudgetMonth: YearMonth = YearMonth.now()
    private var currentDashboardMonth: YearMonth? = YearMonth.now()
    private var currentDashboardYear: Int? = null

    private var windowDays: Int = DEFAULT_WINDOW_DAYS

    init {
        PerfTrace.measure("LedgerViewModel.init") {
            refreshLedgerState()
            _monthlyBudget.value = loadCurrentBudget()
            refresh()
        }
    }

    fun setWindowDays(days: Int) {
        val normalized = days.coerceAtLeast(1)
        if (windowDays == normalized) {
            return
        }
        windowDays = normalized
        refresh()
    }

    fun refresh() {
        PerfTrace.measure("LedgerViewModel.refresh(windowDays=$windowDays, month=$currentDashboardMonth)") {
            _dashboard.value = repository.getDashboard(windowDays, currentDashboardMonth, currentDashboardYear)
            _insights.value = repository.getInsights()
        }
    }

    fun setDashboardMonth(month: YearMonth?) {
        if (currentDashboardMonth == month && currentDashboardYear == null) {
            return
        }
        currentDashboardMonth = month
        currentDashboardYear = null
        refresh()
    }

    fun setDashboardYear(year: Int) {
        if (currentDashboardYear == year && currentDashboardMonth == null) {
            return
        }
        currentDashboardYear = year
        currentDashboardMonth = null
        refresh()
    }

    fun switchLedger(ledgerId: String): Boolean {
        val changed = repository.switchLedger(ledgerId)
        if (!changed) {
            return false
        }
        refreshLedgerState()
        _monthlyBudget.value = loadCurrentBudget()
        refresh()
        return true
    }

    fun addLedger(name: String): LedgerBook {
        val ledger = repository.addLedger(name)
        refreshLedgerState()
        return ledger
    }

    fun renameLedger(ledgerId: String, name: String): Boolean {
        val renamed = repository.renameLedger(ledgerId, name)
        if (!renamed) {
            return false
        }
        refreshLedgerState()
        return true
    }

    fun deleteLedger(ledgerId: String): Boolean {
        val deleted = repository.deleteLedger(ledgerId)
        if (!deleted) {
            return false
        }
        refreshLedgerState()
        _monthlyBudget.value = loadCurrentBudget()
        refresh()
        return true
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

    fun refundTransaction(id: Long, refundAmount: Double) {
        repository.refundTransaction(id, refundAmount)
        refresh()
    }

    fun loadMonthlyBudget(month: YearMonth) {
        currentBudgetMonth = month
        _monthlyBudget.value = loadCurrentBudget()
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

    fun clearMonthlyBudget(month: YearMonth) {
        repository.clearMonthlyBudget(month)
        currentBudgetMonth = month
        _monthlyBudget.value = null
    }

    fun getMonthlyExpense(month: YearMonth): Double = repository.getMonthlyExpense(month)

    fun getYearlyBudget(year: Int): Double? = repository.getYearlyBudget(year)

    fun setYearlyBudget(year: Int, value: Double) {
        repository.setYearlyBudget(year, value)
    }

    fun clearYearlyBudget(year: Int) {
        repository.clearYearlyBudget(year)
    }

    fun getTotalBudget(): Double? = repository.getTotalBudget()

    fun setTotalBudget(value: Double) {
        repository.setTotalBudget(value)
    }

    fun clearTotalBudget() {
        repository.clearTotalBudget()
    }

    fun getTotalExpense(): Double = repository.getTotalExpense()

    fun getExchangeRateToCny(currency: CurrencyCode): Double {
        return repository.getExchangeRateToCny(currency)
    }

    fun setExchangeRateToCny(currency: CurrencyCode, rate: Double) {
        repository.setExchangeRateToCny(currency, rate)
    }

    fun getLastUsedCurrency(): CurrencyCode {
        return repository.getLastUsedCurrency()
    }

    private fun refreshLedgerState() {
        _ledgers.value = repository.getLedgers()
        _currentLedger.value = repository.getCurrentLedger()
    }

    private fun loadCurrentBudget(): Double? {
        return repository.getMonthlyBudget(currentBudgetMonth)
    }

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
        private const val DEFAULT_WINDOW_DAYS = 15
    }
}
