package com.example.account.ui.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.account.R
import com.example.account.data.LedgerBook

class LedgerBookAdapter(
    private val onLedgerClick: (LedgerBook) -> Unit,
    private val onRenameClick: (LedgerBook) -> Unit,
    private val onDeleteClick: (LedgerBook) -> Unit
) : RecyclerView.Adapter<LedgerBookAdapter.ViewHolder>() {

    private var ledgers: List<LedgerBook> = emptyList()
    private var currentLedgerId: String? = null

    fun submitData(items: List<LedgerBook>, selectedLedgerId: String?) {
        ledgers = items
        currentLedgerId = selectedLedgerId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val itemView = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_ledger_book, parent, false)
        return ViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val ledger = ledgers[position]
        val isCurrent = ledger.id == currentLedgerId
        val canDelete = ledgers.size > 1
        holder.bind(ledger, isCurrent, canDelete, onLedgerClick, onRenameClick, onDeleteClick)
    }

    override fun getItemCount(): Int = ledgers.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val row: View = itemView.findViewById(R.id.ledger_row)
        private val nameText: TextView = itemView.findViewById(R.id.ledger_name_text)
        private val currentTagText: TextView = itemView.findViewById(R.id.current_tag_text)
        private val renameButton: ImageButton = itemView.findViewById(R.id.rename_button)
        private val deleteButton: ImageButton = itemView.findViewById(R.id.delete_button)

        fun bind(
            ledger: LedgerBook,
            isCurrent: Boolean,
            canDelete: Boolean,
            onLedgerClick: (LedgerBook) -> Unit,
            onRenameClick: (LedgerBook) -> Unit,
            onDeleteClick: (LedgerBook) -> Unit
        ) {
            nameText.text = ledger.name
            currentTagText.visibility = if (isCurrent) View.VISIBLE else View.GONE
            deleteButton.isEnabled = canDelete
            deleteButton.alpha = if (canDelete) 1f else 0.35f
            row.setOnClickListener { onLedgerClick(ledger) }
            renameButton.setOnClickListener {
                onRenameClick(ledger)
            }
            deleteButton.setOnClickListener {
                if (canDelete) {
                    onDeleteClick(ledger)
                }
            }
        }
    }
}
