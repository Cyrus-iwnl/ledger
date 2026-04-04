package com.example.account.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.example.account.R
import com.example.account.data.LedgerViewModel
import com.example.account.databinding.FragmentSettingsBinding
import com.example.account.ui.DialogFactory
import com.google.android.material.button.MaterialButton

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: LedgerViewModel

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
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
        binding.languageItem.setOnClickListener {
            showLanguagePicker()
        }
        binding.startupPageItem.setOnClickListener {
            showStartupPagePicker()
        }
        binding.ledgerItem.setOnClickListener {
            openLedgerSettings()
        }
        binding.exchangeRateItem.setOnClickListener {
            openExchangeRateSettings()
        }
        viewModel.currentLedger.observe(viewLifecycleOwner) { book ->
            binding.ledgerValueText.text = book.name
        }

        renderLanguageValue()
        renderStartupPageValue()
    }

    private fun showLanguagePicker() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_language_picker, null, false)
        val languageGroup = dialogView.findViewById<RadioGroup>(R.id.language_group)
        val cancelButton = dialogView.findViewById<MaterialButton>(R.id.cancel_button)
        val saveButton = dialogView.findViewById<MaterialButton>(R.id.save_button)
        val selectedLanguage = LanguageManager.currentLanguage(requireContext())

        languageGroup.check(
            when (selectedLanguage) {
                AppLanguage.SIMPLIFIED_CHINESE -> R.id.language_option_zh_cn
                AppLanguage.TRADITIONAL_CHINESE -> R.id.language_option_zh_tw
                AppLanguage.ENGLISH -> R.id.language_option_en
            }
        )

        val dialog = DialogFactory.createCardDialog(requireContext(), dialogView)
        cancelButton.setOnClickListener { dialog.dismiss() }
        saveButton.setOnClickListener {
            val target = when (languageGroup.checkedRadioButtonId) {
                R.id.language_option_zh_tw -> AppLanguage.TRADITIONAL_CHINESE
                R.id.language_option_en -> AppLanguage.ENGLISH
                else -> AppLanguage.SIMPLIFIED_CHINESE
            }
            if (target != selectedLanguage) {
                LanguageManager.updateLanguage(requireContext(), target)
            }
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun openLedgerSettings() {
        parentFragmentManager.beginTransaction()
            .replace(
                R.id.fragment_container,
                LedgerSettingsFragment.newInstance(),
                LedgerSettingsFragment::class.java.name
            )
            .addToBackStack(LedgerSettingsFragment::class.java.name)
            .commit()
    }

    private fun showStartupPagePicker() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_startup_page_picker, null, false)
        val startupPageGroup = dialogView.findViewById<RadioGroup>(R.id.startup_page_group)
        val cancelButton = dialogView.findViewById<MaterialButton>(R.id.cancel_button)
        val saveButton = dialogView.findViewById<MaterialButton>(R.id.save_button)
        val selectedStartupPage = StartupPageManager.currentStartupPage(requireContext())

        startupPageGroup.check(
            when (selectedStartupPage) {
                AppStartupPage.HOME -> R.id.startup_page_option_home
                AppStartupPage.TRANSACTION -> R.id.startup_page_option_transaction
            }
        )

        val dialog = DialogFactory.createCardDialog(requireContext(), dialogView)
        cancelButton.setOnClickListener { dialog.dismiss() }
        saveButton.setOnClickListener {
            val target = when (startupPageGroup.checkedRadioButtonId) {
                R.id.startup_page_option_transaction -> AppStartupPage.TRANSACTION
                else -> AppStartupPage.HOME
            }
            if (target != selectedStartupPage) {
                StartupPageManager.updateStartupPage(requireContext(), target)
                renderStartupPageValue()
            }
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun renderLanguageValue() {
        binding.languageValueText.text = labelFor(
            LanguageManager.currentLanguage(requireContext())
        )
    }

    private fun renderStartupPageValue() {
        binding.startupPageValueText.text = startupPageLabelFor(
            StartupPageManager.currentStartupPage(requireContext())
        )
    }

    private fun openExchangeRateSettings() {
        parentFragmentManager.beginTransaction()
            .replace(
                R.id.fragment_container,
                ExchangeRateSettingsFragment.newInstance(),
                ExchangeRateSettingsFragment::class.java.name
            )
            .addToBackStack(ExchangeRateSettingsFragment::class.java.name)
            .commit()
    }

    private fun labelFor(language: AppLanguage): String {
        return when (language) {
            AppLanguage.SIMPLIFIED_CHINESE -> getString(R.string.language_simplified_chinese)
            AppLanguage.TRADITIONAL_CHINESE -> getString(R.string.language_traditional_chinese)
            AppLanguage.ENGLISH -> getString(R.string.language_english)
        }
    }

    private fun startupPageLabelFor(startupPage: AppStartupPage): String {
        return when (startupPage) {
            AppStartupPage.HOME -> getString(R.string.settings_startup_page_option_home)
            AppStartupPage.TRANSACTION -> getString(R.string.settings_startup_page_option_transaction)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(): SettingsFragment = SettingsFragment()
    }
}
