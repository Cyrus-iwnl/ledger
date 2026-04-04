package com.example.account.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.account.R
import com.example.account.data.CurrencyCode
import com.example.account.data.LedgerViewModel
import com.example.account.databinding.FragmentExchangeRateSettingsBinding
import com.example.account.ui.DialogFactory
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.HttpURLConnection
import java.net.URL

class ExchangeRateSettingsFragment : Fragment() {

    private var _binding: FragmentExchangeRateSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: LedgerViewModel
    private val exchangeRatePattern = Regex("^\\d+(\\.\\d{1,6})?$")
    private var isFetchingLatestRates = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentExchangeRateSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(
            requireActivity(),
            LedgerViewModel.Factory(requireActivity().application)
        )[LedgerViewModel::class.java]

        binding.backButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        binding.autoFetchButton.setOnClickListener {
            fetchLatestRatesFromApi()
        }
        renderRateList()
    }

    private fun renderRateList() {
        binding.rateListContainer.removeAllViews()
        val currencies = CurrencyCode.values().toList()
        currencies.forEachIndexed { index, currency ->
            val itemView = layoutInflater.inflate(R.layout.item_exchange_rate, binding.rateListContainer, false)
            val row = itemView.findViewById<View>(R.id.exchange_rate_row)
            val flagText = itemView.findViewById<TextView>(R.id.currency_flag_text)
            val labelText = itemView.findViewById<TextView>(R.id.currency_label_text)
            val rateValueText = itemView.findViewById<TextView>(R.id.currency_rate_value_text)
            val chevron = itemView.findViewById<View>(R.id.currency_chevron)
            val divider = itemView.findViewById<View>(R.id.row_divider)
            val rateValue = viewModel.getExchangeRateToCny(currency)

            flagText.text = flagByCurrency(currency)
            labelText.text = getString(currencyNameRes(currency), currency.symbol)
            rateValueText.text = getString(R.string.settings_exchange_rate_value_format, formatRate(rateValue))
            divider.visibility = if (index == currencies.lastIndex) View.GONE else View.VISIBLE

            if (currency == CurrencyCode.CNY) {
                row.isClickable = false
                row.isFocusable = false
                row.foreground = null
                chevron.visibility = View.GONE
            } else {
                chevron.visibility = View.VISIBLE
                row.setOnClickListener {
                    showExchangeRateEditor(currency)
                }
            }
            binding.rateListContainer.addView(itemView)
        }
    }

    private fun showExchangeRateEditor(currency: CurrencyCode) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_exchange_rate, null, false)
        val titleText = dialogView.findViewById<TextView>(R.id.exchange_rate_dialog_title)
        val subtitleText = dialogView.findViewById<TextView>(R.id.exchange_rate_dialog_subtitle)
        val inputLayout = dialogView.findViewById<TextInputLayout>(R.id.exchange_rate_input_layout)
        val input = dialogView.findViewById<TextInputEditText>(R.id.exchange_rate_input)
        val cancelButton = dialogView.findViewById<MaterialButton>(R.id.cancel_button)
        val saveButton = dialogView.findViewById<MaterialButton>(R.id.save_button)
        val currentRate = viewModel.getExchangeRateToCny(currency)

        titleText.text = getString(R.string.settings_exchange_rate_dialog_title_format, currency.code)
        subtitleText.text = getString(R.string.settings_exchange_rate_dialog_subtitle_format, currency.code)
        inputLayout.hint = getString(R.string.settings_exchange_rate_input_hint_format, currency.code)
        input.setText(formatRate(currentRate))
        input.setSelection(input.text?.length ?: 0)

        val dialog = DialogFactory.createCardDialog(requireContext(), dialogView)
        cancelButton.setOnClickListener { dialog.dismiss() }
        saveButton.setOnClickListener {
            val raw = input.text?.toString()?.trim()?.replace(",", "").orEmpty()
            if (!exchangeRatePattern.matches(raw)) {
                inputLayout.error = getString(R.string.settings_exchange_rate_invalid_decimals)
                return@setOnClickListener
            }
            val value = raw.toDoubleOrNull()
            if (value == null || !value.isFinite() || value <= 0.0) {
                inputLayout.error = getString(R.string.settings_exchange_rate_invalid_positive)
                return@setOnClickListener
            }
            inputLayout.error = null
            viewModel.setExchangeRateToCny(currency, value)
            renderRateList()
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun fetchLatestRatesFromApi() {
        if (isFetchingLatestRates) {
            return
        }
        updateAutoFetchState(fetching = true)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { requestLatestRatesToCny() }
            }
            if (_binding == null || !isAdded) {
                return@launch
            }
            result.fold(
                onSuccess = { fetchedRates ->
                    fetchedRates.forEach { (currency, rate) ->
                        viewModel.setExchangeRateToCny(currency, rate)
                    }
                    renderRateList()
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.settings_exchange_rate_auto_fetch_success, fetchedRates.size),
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onFailure = {
                    Toast.makeText(
                        requireContext(),
                        R.string.settings_exchange_rate_auto_fetch_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
            updateAutoFetchState(fetching = false)
        }
    }

    private fun updateAutoFetchState(fetching: Boolean) {
        isFetchingLatestRates = fetching
        binding.autoFetchButton.isEnabled = !fetching
        binding.autoFetchButton.text = getString(
            if (fetching) {
                R.string.settings_exchange_rate_auto_fetch_loading
            } else {
                R.string.settings_exchange_rate_auto_fetch
            }
        )
    }

    private fun requestLatestRatesToCny(): Map<CurrencyCode, Double> {
        val quotes = AUTO_FETCH_CURRENCIES.joinToString(",") { it.code }
        val connection = (URL("$FRANKFURTER_API/v2/rates?base=CNY&quotes=$quotes").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/json")
        }

        try {
            val statusCode = connection.responseCode
            val body = (if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            })?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (statusCode !in 200..299) {
                val message = parseApiErrorMessage(body) ?: "HTTP $statusCode"
                throw IllegalStateException(message)
            }

            val response = JSONArray(body)
            val rates = mutableMapOf<CurrencyCode, Double>()
            for (index in 0 until response.length()) {
                val item = response.getJSONObject(index)
                val quoteCode = item.optString("quote")
                val quoteCurrency = AUTO_FETCH_CURRENCIES.firstOrNull { it.code == quoteCode } ?: continue
                val ratePerCny = item.optString("rate").toBigDecimalOrNull()
                if (ratePerCny == null || ratePerCny <= BigDecimal.ZERO) {
                    continue
                }
                val cnyPerUnit = BigDecimal.ONE
                    .divide(ratePerCny, 6, RoundingMode.HALF_UP)
                    .toDouble()
                rates[quoteCurrency] = cnyPerUnit
            }

            if (rates.isEmpty()) {
                throw IllegalStateException("No valid rates returned")
            }
            return rates
        } finally {
            connection.disconnect()
        }
    }

    private fun parseApiErrorMessage(body: String): String? {
        if (body.isBlank()) {
            return null
        }
        return runCatching {
            JSONObject(body).optString("message")
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun flagByCurrency(currency: CurrencyCode): String {
        return when (currency) {
            CurrencyCode.CNY -> "\uD83C\uDDE8\uD83C\uDDF3"
            CurrencyCode.HKD -> "\uD83C\uDDED\uD83C\uDDF0"
            CurrencyCode.TWD -> "\uD83C\uDDE8\uD83C\uDDF3"
            CurrencyCode.USD -> "\uD83C\uDDFA\uD83C\uDDF8"
            CurrencyCode.GBP -> "\uD83C\uDDEC\uD83C\uDDE7"
            CurrencyCode.EUR -> "\uD83C\uDDEA\uD83C\uDDFA"
            CurrencyCode.JPY -> "\uD83C\uDDEF\uD83C\uDDF5"
            CurrencyCode.KRW -> "\uD83C\uDDF0\uD83C\uDDF7"
            CurrencyCode.AUD -> "\uD83C\uDDE6\uD83C\uDDFA"
            CurrencyCode.CAD -> "\uD83C\uDDE8\uD83C\uDDE6"
            CurrencyCode.SGD -> "\uD83C\uDDF8\uD83C\uDDEC"
            CurrencyCode.CHF -> "\uD83C\uDDE8\uD83C\uDDED"
            CurrencyCode.THB -> "\uD83C\uDDF9\uD83C\uDDED"
            CurrencyCode.MOP -> "\uD83C\uDDF2\uD83C\uDDF4"
        }
    }

    private fun currencyNameRes(currency: CurrencyCode): Int {
        return when (currency) {
            CurrencyCode.CNY -> R.string.settings_currency_name_cny
            CurrencyCode.HKD -> R.string.settings_currency_name_hkd
            CurrencyCode.TWD -> R.string.settings_currency_name_twd
            CurrencyCode.USD -> R.string.settings_currency_name_usd
            CurrencyCode.GBP -> R.string.settings_currency_name_gbp
            CurrencyCode.EUR -> R.string.settings_currency_name_eur
            CurrencyCode.JPY -> R.string.settings_currency_name_jpy
            CurrencyCode.KRW -> R.string.settings_currency_name_krw
            CurrencyCode.AUD -> R.string.settings_currency_name_aud
            CurrencyCode.CAD -> R.string.settings_currency_name_cad
            CurrencyCode.SGD -> R.string.settings_currency_name_sgd
            CurrencyCode.CHF -> R.string.settings_currency_name_chf
            CurrencyCode.THB -> R.string.settings_currency_name_thb
            CurrencyCode.MOP -> R.string.settings_currency_name_mop
        }
    }

    private fun formatRate(rate: Double): String {
        return java.math.BigDecimal.valueOf(rate).stripTrailingZeros().toPlainString()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        isFetchingLatestRates = false
        _binding = null
    }

    companion object {
        private const val FRANKFURTER_API = "https://api.frankfurter.dev"
        private val AUTO_FETCH_CURRENCIES = CurrencyCode.values().filter { it != CurrencyCode.CNY }
        fun newInstance(): ExchangeRateSettingsFragment = ExchangeRateSettingsFragment()
    }
}
