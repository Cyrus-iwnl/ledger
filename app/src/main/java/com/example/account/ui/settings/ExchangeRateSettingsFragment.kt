package com.example.account.ui.settings

import android.os.Bundle
import android.text.format.DateFormat
import android.graphics.drawable.PictureDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RawRes
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.caverock.androidsvg.SVG
import com.example.account.R
import com.example.account.data.CurrencyCode
import com.example.account.data.ExchangeRateApiClient
import com.example.account.data.LedgerViewModel
import com.example.account.databinding.FragmentExchangeRateSettingsBinding
import com.example.account.ui.DialogFactory
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

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
        renderLastUpdatedTime()
    }

    override fun onResume() {
        super.onResume()
        if (_binding == null) {
            return
        }
        renderRateList()
        renderLastUpdatedTime()
    }

    private fun renderRateList() {
        binding.rateListContainer.removeAllViews()
        val currencies = CurrencyCode.values().toList()
        currencies.forEachIndexed { index, currency ->
            val itemView = layoutInflater.inflate(R.layout.item_exchange_rate, binding.rateListContainer, false)
            val row = itemView.findViewById<View>(R.id.exchange_rate_row)
            val flagImage = itemView.findViewById<ImageView>(R.id.currency_flag_image)
            val labelText = itemView.findViewById<TextView>(R.id.currency_label_text)
            val rateValueText = itemView.findViewById<TextView>(R.id.currency_rate_value_text)
            val chevron = itemView.findViewById<View>(R.id.currency_chevron)
            val divider = itemView.findViewById<View>(R.id.row_divider)
            val rateValue = viewModel.getExchangeRateToCny(currency)

            bindLocalFlagSvg(flagImage, currency)
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
                runCatching { ExchangeRateApiClient.requestLatestRatesToCny() }
            }
            if (_binding == null || !isAdded) {
                return@launch
            }
            result.fold(
                onSuccess = { fetchedRates ->
                    fetchedRates.forEach { (currency, rate) ->
                        viewModel.setExchangeRateToCny(currency, rate)
                    }
                    viewModel.markExchangeRateRefreshedToday()
                    renderRateList()
                    renderLastUpdatedTime()
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

    private fun renderLastUpdatedTime() {
        val lastRefreshAt = viewModel.getExchangeRateLastRefreshAtMillis()
        binding.lastRefreshText.text = if (lastRefreshAt != null) {
            val dateText = DateFormat.getDateFormat(requireContext()).format(Date(lastRefreshAt))
            val timeText = DateFormat.getTimeFormat(requireContext()).format(Date(lastRefreshAt))
            getString(R.string.settings_exchange_rate_last_refresh_at_format, "$dateText $timeText")
        } else {
            getString(R.string.settings_exchange_rate_last_refresh_at_empty)
        }
    }

    @RawRes
    private fun flagSvgRawByCurrency(currency: CurrencyCode): Int {
        return when (currency) {
            CurrencyCode.CNY -> R.raw.flag_cny
            CurrencyCode.HKD -> R.raw.flag_hkd
            CurrencyCode.TWD -> R.raw.flag_cny
            CurrencyCode.USD -> R.raw.flag_usd
            CurrencyCode.GBP -> R.raw.flag_gbp
            CurrencyCode.EUR -> R.raw.flag_eur
            CurrencyCode.JPY -> R.raw.flag_jpy
            CurrencyCode.KRW -> R.raw.flag_krw
            CurrencyCode.AUD -> R.raw.flag_aud
            CurrencyCode.CAD -> R.raw.flag_cad
            CurrencyCode.SGD -> R.raw.flag_sgd
            CurrencyCode.CHF -> R.raw.flag_chf
            CurrencyCode.THB -> R.raw.flag_thb
            CurrencyCode.MOP -> R.raw.flag_mop
            CurrencyCode.INR -> R.raw.flag_inr
            CurrencyCode.AED -> R.raw.flag_aed
            CurrencyCode.SAR -> R.raw.flag_sar
            CurrencyCode.RUB -> R.raw.flag_rub
            CurrencyCode.BRL -> R.raw.flag_brl
            CurrencyCode.MXN -> R.raw.flag_mxn
        }
    }

    private fun bindLocalFlagSvg(flagImage: ImageView, currency: CurrencyCode) {
        val flagRawRes = flagSvgRawByCurrency(currency)
        val loaded = runCatching {
            resources.openRawResource(flagRawRes).use { input ->
                val svg = SVG.getFromInputStream(input)
                val picture = svg.renderToPicture()
                flagImage.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                flagImage.setImageDrawable(PictureDrawable(picture))
            }
        }.isSuccess
        if (!loaded) {
            val fallbackLoaded = runCatching {
                resources.openRawResource(R.raw.flag_cny).use { input ->
                    val svg = SVG.getFromInputStream(input)
                    val picture = svg.renderToPicture()
                    flagImage.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    flagImage.setImageDrawable(PictureDrawable(picture))
                }
            }.isSuccess
            if (!fallbackLoaded) {
                flagImage.setLayerType(View.LAYER_TYPE_NONE, null)
                flagImage.setImageDrawable(null)
            }
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
            CurrencyCode.INR -> R.string.settings_currency_name_inr
            CurrencyCode.AED -> R.string.settings_currency_name_aed
            CurrencyCode.SAR -> R.string.settings_currency_name_sar
            CurrencyCode.RUB -> R.string.settings_currency_name_rub
            CurrencyCode.BRL -> R.string.settings_currency_name_brl
            CurrencyCode.MXN -> R.string.settings_currency_name_mxn
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
        fun newInstance(): ExchangeRateSettingsFragment = ExchangeRateSettingsFragment()
    }
}
