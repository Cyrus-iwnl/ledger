package com.example.account.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.account.R
import com.example.account.data.LedgerBook
import com.example.account.data.LedgerViewModel
import com.example.account.databinding.FragmentLedgerSettingsBinding
import com.example.account.ui.DialogFactory
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.TextInputEditText

class LedgerSettingsFragment : Fragment() {

    private var _binding: FragmentLedgerSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: LedgerViewModel
    private lateinit var adapter: LedgerBookAdapter

    private var ledgers: List<LedgerBook> = emptyList()
    private var currentLedger: LedgerBook? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLedgerSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(
            requireActivity(),
            LedgerViewModel.Factory(requireActivity().application)
        )[LedgerViewModel::class.java]

        adapter = LedgerBookAdapter(
            onLedgerClick = { ledger ->
                if (ledger.id != currentLedger?.id) {
                    viewModel.switchLedger(ledger.id)
                }
            },
            onDeleteClick = { ledger ->
                requestDeleteLedger(ledger)
            }
        )

        binding.ledgerList.layoutManager = LinearLayoutManager(requireContext())
        binding.ledgerList.adapter = adapter

        binding.backButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        binding.addButton.setOnClickListener {
            showAddLedgerDialog()
        }

        viewModel.ledgers.observe(viewLifecycleOwner) { items ->
            ledgers = items
            renderLedgers()
        }
        viewModel.currentLedger.observe(viewLifecycleOwner) { ledger ->
            currentLedger = ledger
            renderLedgers()
        }
    }

    private fun showAddLedgerDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_ledger, null, false)
        val inputLayout = dialogView.findViewById<TextInputLayout>(R.id.ledger_input_layout)
        val input = dialogView.findViewById<TextInputEditText>(R.id.ledger_input)
        val cancelButton = dialogView.findViewById<MaterialButton>(R.id.cancel_button)
        val saveButton = dialogView.findViewById<MaterialButton>(R.id.save_button)

        val dialog = DialogFactory.createCardDialog(requireContext(), dialogView)
        cancelButton.setOnClickListener { dialog.dismiss() }
        saveButton.setOnClickListener {
            val name = input.text?.toString()?.trim().orEmpty()
            if (name.isBlank()) {
                inputLayout.error = getString(R.string.settings_ledger_name_empty)
                return@setOnClickListener
            }
            inputLayout.error = null
            viewModel.addLedger(name)
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun requestDeleteLedger(ledger: LedgerBook) {
        if (ledgers.size <= 1) {
            Toast.makeText(requireContext(), R.string.settings_ledger_delete_last_blocked, Toast.LENGTH_SHORT).show()
            return
        }
        showDangerConfirmationDialog(
            title = getString(R.string.settings_ledger_delete_step1_title),
            subtitle = getString(R.string.settings_ledger_delete_step1_subtitle),
            description = getString(R.string.settings_ledger_delete_step1_description, ledger.name),
            confirmText = getString(R.string.settings_ledger_delete_step1_continue)
        ) {
            showDangerConfirmationDialog(
                title = getString(R.string.settings_ledger_delete_step2_title),
                subtitle = getString(R.string.settings_ledger_delete_step2_subtitle),
                description = getString(R.string.settings_ledger_delete_step2_description, ledger.name),
                confirmText = getString(R.string.settings_ledger_delete_step2_confirm)
            ) {
                val deleted = viewModel.deleteLedger(ledger.id)
                if (!deleted) {
                    Toast.makeText(
                        requireContext(),
                        R.string.settings_ledger_delete_last_blocked,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun showDangerConfirmationDialog(
        title: String,
        subtitle: String,
        description: String,
        confirmText: String,
        onConfirm: () -> Unit
    ) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_delete_transaction, null, false)
        val titleText = dialogView.findViewById<TextView>(R.id.delete_title_text)
        val subtitleText = dialogView.findViewById<TextView>(R.id.delete_subtitle_text)
        val descriptionText = dialogView.findViewById<TextView>(R.id.delete_description_text)
        val cancelButton = dialogView.findViewById<MaterialButton>(R.id.cancel_button)
        val deleteButton = dialogView.findViewById<MaterialButton>(R.id.delete_button)

        titleText.text = title
        subtitleText.text = subtitle
        descriptionText.text = description
        deleteButton.text = confirmText

        val dialog = DialogFactory.createCardDialog(requireContext(), dialogView)
        cancelButton.setOnClickListener { dialog.dismiss() }
        deleteButton.setOnClickListener {
            dialog.dismiss()
            onConfirm()
        }
        dialog.show()
    }

    private fun renderLedgers() {
        adapter.submitData(ledgers, currentLedger?.id)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(): LedgerSettingsFragment = LedgerSettingsFragment()
    }
}
