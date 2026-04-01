package com.example.account.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.account.R
import com.example.account.databinding.FragmentSettingsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

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
        binding.backButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        binding.languageItem.setOnClickListener {
            showLanguagePicker()
        }
        renderLanguageValue()
    }

    private fun showLanguagePicker() {
        val options = listOf(
            AppLanguage.SIMPLIFIED_CHINESE,
            AppLanguage.TRADITIONAL_CHINESE,
            AppLanguage.ENGLISH
        )
        val labels = options.map { labelFor(it) }.toTypedArray()
        val selectedLanguage = LanguageManager.currentLanguage(requireContext())
        val checkedIndex = options.indexOf(selectedLanguage).takeIf { it >= 0 } ?: 0

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_language_title)
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                val target = options[which]
                if (target != selectedLanguage) {
                    LanguageManager.updateLanguage(requireContext(), target)
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun renderLanguageValue() {
        binding.languageValueText.text = labelFor(
            LanguageManager.currentLanguage(requireContext())
        )
    }

    private fun labelFor(language: AppLanguage): String {
        return when (language) {
            AppLanguage.SIMPLIFIED_CHINESE -> getString(R.string.language_simplified_chinese)
            AppLanguage.TRADITIONAL_CHINESE -> getString(R.string.language_traditional_chinese)
            AppLanguage.ENGLISH -> getString(R.string.language_english)
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
