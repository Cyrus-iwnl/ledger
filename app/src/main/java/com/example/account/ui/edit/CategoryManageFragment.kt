package com.example.account.ui.edit

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.account.MainActivity
import com.example.account.R
import com.example.account.data.CategoryLocalizer
import com.example.account.data.LedgerCategory
import com.example.account.data.LedgerViewModel
import com.example.account.data.TransactionType
import com.example.account.ui.DialogFactory
import java.util.Collections

class CategoryManageFragment : Fragment() {

    private lateinit var viewModel: LedgerViewModel
    private var selectedType: TransactionType = TransactionType.EXPENSE
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: CategoryManageAdapter
    private var isTypeAnimating = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_category_manage, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(
            requireActivity(),
            LedgerViewModel.Factory(requireActivity().application)
        )[LedgerViewModel::class.java]

        selectedType = TransactionType.valueOf(
            arguments?.getString(ARG_TYPE) ?: TransactionType.EXPENSE.name
        )

        applySystemBarColors()
        WindowCompat.getInsetsController(requireActivity().window, view).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        view.findViewById<View>(R.id.back_button).setOnClickListener {
            activity?.onBackPressedDispatcher?.onBackPressed()
        }

        setupTypeToggle(view)
        setupRecyclerView(view)

        view.findViewById<View>(R.id.add_category_button).setOnClickListener {
            (activity as? MainActivity)?.openCategoryAdd(selectedType)
        }

        loadCategories()
    }

    override fun onResume() {
        super.onResume()
        loadCategories()
    }

    private fun setupTypeToggle(view: View) {
        val typeToggle = view.findViewById<FrameLayout>(R.id.type_toggle)
        val thumb = view.findViewById<View>(R.id.type_toggle_thumb)
        val expenseBtn = view.findViewById<TextView>(R.id.expense_button)
        val incomeBtn = view.findViewById<TextView>(R.id.income_button)

        expenseBtn.setOnClickListener { setType(TransactionType.EXPENSE) }
        incomeBtn.setOnClickListener { setType(TransactionType.INCOME) }

        typeToggle.post {
            syncTypeToggleThumb(view, animated = false)
        }
        updateTypeToggleUi(view)
    }

    private fun setType(type: TransactionType) {
        if (selectedType == type || isTypeAnimating) return
        selectedType = type
        view?.let { v ->
            updateTypeToggleUi(v)
            syncTypeToggleThumb(v, animated = true)
        }
        loadCategories()
    }

    private fun updateTypeToggleUi(view: View) {
        val expenseSelected = selectedType == TransactionType.EXPENSE
        val expenseBtn = view.findViewById<TextView>(R.id.expense_button)
        val incomeBtn = view.findViewById<TextView>(R.id.income_button)

        expenseBtn.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (expenseSelected) R.color.editor_header_chip_selected_text else R.color.editor_header_chip_unselected_text
            )
        )
        incomeBtn.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (expenseSelected) R.color.editor_header_chip_unselected_text else R.color.editor_header_chip_selected_text
            )
        )
        expenseBtn.setTypeface(expenseBtn.typeface, if (expenseSelected) Typeface.BOLD else Typeface.NORMAL)
        incomeBtn.setTypeface(incomeBtn.typeface, if (!expenseSelected) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun syncTypeToggleThumb(view: View, animated: Boolean) {
        val typeToggle = view.findViewById<FrameLayout>(R.id.type_toggle)
        val thumb = view.findViewById<View>(R.id.type_toggle_thumb)
        val toggleWidth = typeToggle.width
        val paddingHorizontal = typeToggle.paddingStart + typeToggle.paddingEnd
        if (toggleWidth == 0) return

        val availableWidth = toggleWidth - paddingHorizontal
        val thumbWidth = availableWidth / 2
        val params = thumb.layoutParams
        if (params.width != thumbWidth) {
            params.width = thumbWidth
            thumb.layoutParams = params
        }
        val targetTranslation = if (selectedType == TransactionType.EXPENSE) 0f else thumbWidth.toFloat()

        if (!animated) {
            thumb.translationX = targetTranslation
            isTypeAnimating = false
            return
        }
        isTypeAnimating = true
        thumb.animate()
            .translationX(targetTranslation)
            .setDuration(180L)
            .withEndAction { isTypeAnimating = false }
            .start()
    }

    private fun setupRecyclerView(view: View) {
        recyclerView = view.findViewById(R.id.category_list)
        adapter = CategoryManageAdapter(
            onDeleteClick = { category -> showDeleteConfirmation(category) },
            onEditClick = { category ->
                (activity as? MainActivity)?.openCategoryAdd(category.type, category.id)
            },
            onStartDrag = { viewHolder -> itemTouchHelper.startDrag(viewHolder) }
        )
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
        itemTouchHelper.attachToRecyclerView(recyclerView)
    }

    private val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN,
        0
    ) {
        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            val from = viewHolder.adapterPosition
            val to = target.adapterPosition
            adapter.moveItem(from, to)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            val orderedIds = adapter.getOrderedIds()
            viewModel.reorderCategories(selectedType, orderedIds)
        }

        override fun isLongPressDragEnabled(): Boolean = false
    })

    private fun loadCategories() {
        val categories = viewModel.categoriesFor(selectedType)
        adapter.submitList(categories)
    }

    private fun showDeleteConfirmation(category: LedgerCategory) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_delete_category, null, false)
        val cancelButton = dialogView.findViewById<View>(R.id.cancel_button)
        val deleteButton = dialogView.findViewById<View>(R.id.delete_button)

        val dialog = DialogFactory.createCardDialog(requireContext(), dialogView)
        cancelButton.setOnClickListener { dialog.dismiss() }
        deleteButton.setOnClickListener {
            dialog.dismiss()
            viewModel.deleteCategory(category.id)
            loadCategories()
            Toast.makeText(requireContext(), R.string.category_deleted, Toast.LENGTH_SHORT).show()
        }
        dialog.show()
    }

    @Suppress("DEPRECATION")
    private fun applySystemBarColors() {
        requireActivity().window.apply {
            statusBarColor = ContextCompat.getColor(requireContext(), R.color.app_background)
            navigationBarColor = ContextCompat.getColor(requireContext(), R.color.app_background)
        }
    }

    inner class CategoryManageAdapter(
        private val onDeleteClick: (LedgerCategory) -> Unit,
        private val onEditClick: (LedgerCategory) -> Unit,
        private val onStartDrag: (RecyclerView.ViewHolder) -> Unit
    ) : RecyclerView.Adapter<CategoryManageAdapter.ViewHolder>() {

        private val items = mutableListOf<LedgerCategory>()

        fun submitList(list: List<LedgerCategory>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        fun moveItem(from: Int, to: Int) {
            Collections.swap(items, from, to)
            notifyItemMoved(from, to)
        }

        fun getOrderedIds(): List<String> = items.map { it.id }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_category_manage, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val iconContainer = itemView.findViewById<FrameLayout>(R.id.category_icon_container)
            private val iconImage = itemView.findViewById<ImageView>(R.id.category_icon_image)
            private val iconSymbol = itemView.findViewById<TextView>(R.id.category_icon_symbol)
            private val nameText = itemView.findViewById<TextView>(R.id.category_name)
            private val editBtn = itemView.findViewById<View>(R.id.edit_button)
            private val deleteBtn = itemView.findViewById<View>(R.id.delete_button)
            private val dragHandle = itemView.findViewById<View>(R.id.drag_handle)

            fun bind(category: LedgerCategory) {
                val context = itemView.context
                val localizedName = CategoryLocalizer.displayName(context, category)
                nameText.text = localizedName

                // Set icon
                val iconColor = category.accentColor
                iconContainer.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ColorUtils.setAlphaComponent(category.accentColor, 38))
                }

                val symbolTypeface = resolveSymbolTypeface()
                if (symbolTypeface != null && category.iconGlyph.isNotBlank()) {
                    iconSymbol.visibility = View.VISIBLE
                    iconImage.visibility = View.GONE
                    iconSymbol.typeface = symbolTypeface
                    iconSymbol.text = CategoryLocalizer.normalizeIconGlyph(category.iconGlyph)
                    iconSymbol.setTextColor(iconColor)
                } else {
                    iconSymbol.visibility = View.GONE
                    iconImage.visibility = View.VISIBLE
                    iconImage.setImageResource(category.iconRes)
                    iconImage.setColorFilter(iconColor)
                }

                // Hide delete for "other" categories
                val isOther = category.id == "expense_other" || category.id == "income_other"
                deleteBtn.visibility = if (isOther) View.INVISIBLE else View.VISIBLE
                deleteBtn.setOnClickListener {
                    if (!isOther) onDeleteClick(category)
                }
                editBtn.setOnClickListener { onEditClick(category) }

                // Drag handle
                dragHandle.setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        onStartDrag(this)
                    }
                    false
                }
            }
        }
    }

    private fun resolveSymbolTypeface(): Typeface? {
        if (!symbolTypefaceResolved) {
            symbolTypeface = try {
                ResourcesCompat.getFont(requireContext(), R.font.material_symbols_outlined_static)
            } catch (_: Throwable) {
                null
            }
            symbolTypefaceResolved = true
        }
        return symbolTypeface
    }

    companion object {
        private const val ARG_TYPE = "category_type"
        private var symbolTypefaceResolved = false
        private var symbolTypeface: Typeface? = null

        fun newInstance(type: TransactionType): CategoryManageFragment {
            return CategoryManageFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TYPE, type.name)
                }
            }
        }
    }
}
