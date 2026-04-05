package com.example.account.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.account.R
import com.example.account.data.LedgerImportSummary
import com.example.account.data.LedgerViewModel
import com.example.account.databinding.FragmentDataTransferBinding
import com.example.account.ui.DialogFactory
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class DataTransferFragment : Fragment() {

    private var _binding: FragmentDataTransferBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: LedgerViewModel

    private val exportDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data ?: return@registerForActivityResult
        exportBackupToUri(uri)
    }

    private val importDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data ?: return@registerForActivityResult
        importBackupFromUri(uri)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDataTransferBinding.inflate(inflater, container, false)
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

        binding.exportItem.setOnClickListener {
            launchExportFilePicker()
        }

        binding.importItem.setOnClickListener {
            requestImportConfirmation()
        }
    }

    private fun launchExportFilePicker() {
        val timestamp = LocalDateTime.now().format(
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US)
        )
        val fileName = "ledger_backup_$timestamp.zip"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, fileName)
        }
        exportDocumentLauncher.launch(intent)
    }

    private fun requestImportConfirmation() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_delete_transaction, null, false)
        val titleText = dialogView.findViewById<TextView>(R.id.delete_title_text)
        val subtitleText = dialogView.findViewById<TextView>(R.id.delete_subtitle_text)
        val descriptionText = dialogView.findViewById<TextView>(R.id.delete_description_text)
        val cancelButton = dialogView.findViewById<MaterialButton>(R.id.cancel_button)
        val confirmButton = dialogView.findViewById<MaterialButton>(R.id.delete_button)

        titleText.text = getString(R.string.settings_import_confirm_title)
        subtitleText.text = getString(R.string.settings_import_confirm_subtitle)
        subtitleText.visibility = View.VISIBLE
        descriptionText.text = getString(R.string.settings_import_confirm_description)
        confirmButton.text = getString(R.string.settings_import_confirm_action)

        val dialog = DialogFactory.createCardDialog(requireContext(), dialogView)
        cancelButton.setOnClickListener { dialog.dismiss() }
        confirmButton.setOnClickListener {
            dialog.dismiss()
            launchImportFilePicker()
        }
        dialog.show()
    }

    private fun launchImportFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/octet-stream"))
        }
        importDocumentLauncher.launch(intent)
    }

    private fun exportBackupToUri(uri: Uri) {
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val message = withContext(Dispatchers.IO) {
                runCatching {
                    val summary = appContext.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        viewModel.exportLedgersToZip(outputStream)
                    } ?: throw IllegalStateException("Unable to create backup file.")
                    appContext.getString(R.string.settings_export_success, summary.ledgerCount)
                }.getOrElse { throwable ->
                    appContext.getString(
                        R.string.settings_data_action_failed_with_reason,
                        throwable.message ?: appContext.getString(R.string.settings_data_action_failed)
                    )
                }
            }
            context?.let { context ->
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun importBackupFromUri(uri: Uri) {
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    appContext.contentResolver.openInputStream(uri)?.use { inputStream ->
                        viewModel.importLedgersFromZip(inputStream)
                    } ?: throw IllegalStateException("Unable to read backup file.")
                }
            }
            if (result.isSuccess) {
                val summary = result.getOrNull() ?: return@launch
                viewModel.refreshAfterImport()
                showImportSuccessToast(summary)
            } else {
                val throwable = result.exceptionOrNull()
                val message = getString(
                    R.string.settings_data_action_failed_with_reason,
                    throwable?.message ?: getString(R.string.settings_data_action_failed)
                )
                context?.let { context ->
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showImportSuccessToast(summary: LedgerImportSummary) {
        context?.let { context ->
            Toast.makeText(
                context,
                getString(
                    R.string.settings_import_success,
                    summary.ledgerCount,
                    summary.transactionCount
                ),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(): DataTransferFragment = DataTransferFragment()
    }
}
