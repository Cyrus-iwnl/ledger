package com.example.account.ui.edit

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.account.R
import com.example.account.data.CategoryLocalizer
import com.example.account.data.LedgerViewModel
import com.example.account.data.TransactionType
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

class CategoryAddFragment : Fragment() {

    private lateinit var viewModel: LedgerViewModel
    private var selectedType: TransactionType = TransactionType.EXPENSE
    private var selectedIconGlyph: String = "category"
    private var selectedColor: Int = Color.parseColor("#FF9F89")
    private var editingCategoryId: String? = null
    private var isEditMode: Boolean = false
    private var symbolTypeface: Typeface? = null
    private var iconAdapter: IconAdapter? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_category_add, container, false)
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
        editingCategoryId = arguments?.getString(ARG_CATEGORY_ID)?.takeIf { it.isNotBlank() }
        val editingCategory = editingCategoryId?.let { viewModel.findCategory(it) }
        if (editingCategory != null) {
            isEditMode = true
            selectedType = editingCategory.type
            selectedIconGlyph = CategoryLocalizer.normalizeIconGlyph(
                editingCategory.iconGlyph.ifBlank { "category" }
            )
            selectedColor = editingCategory.accentColor
        } else {
            isEditMode = false
            editingCategoryId = null
        }

        symbolTypeface = try {
            ResourcesCompat.getFont(requireContext(), R.font.material_symbols_outlined_static)
        } catch (_: Throwable) {
            null
        }

        applySystemBarColors()
        WindowCompat.getInsetsController(requireActivity().window, view).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        view.findViewById<View>(R.id.back_button).setOnClickListener {
            activity?.onBackPressedDispatcher?.onBackPressed()
        }
        view.findViewById<TextView>(R.id.page_title).setText(
            if (isEditMode) R.string.edit_category else R.string.add_category
        )

        setupColorPicker(view)
        setupIconPicker(view)
        updatePreview(view)

        // Add name length limit: 10 Chinese or 30 English
        // We use a simple filter that counts non-ASCII as 3 characters (or similar weight)
        // or just limits total characters. User specifically asked for "10 Chinese / 30 English".
        val nameInput = view.findViewById<EditText>(R.id.name_input)
        nameInput.filters = arrayOf(android.text.InputFilter { source, start, end, dest, dstart, dend ->
            val destText = dest.toString()
            val newText = destText.substring(0, dstart) + source.subSequence(start, end) + destText.substring(dend)
            
            var weight = 0.0
            for (ch in newText) {
                weight += if (ch.code > 127) 3.0 else 1.0
            }
            if (weight > 30.0) "" else null
        })
        if (editingCategory != null) {
            if (isOtherCategory(editingCategory.id)) {
                nameInput.setText(CategoryLocalizer.displayName(requireContext(), editingCategory))
                nameInput.isEnabled = false
                nameInput.isFocusable = false
                nameInput.isFocusableInTouchMode = false
                nameInput.isClickable = false
                nameInput.alpha = 0.6f
            } else {
                nameInput.setText(editingCategory.name)
                nameInput.setSelection(nameInput.text?.length ?: 0)
            }
        }

        view.findViewById<View>(R.id.save_button).setOnClickListener {
            saveCategory(view)
        }
    }

    private fun setupColorPicker(view: View) {
        val colorCanvasImage = view.findViewById<ImageView>(R.id.color_canvas_image)
        val colorCanvasIndicator = view.findViewById<View>(R.id.color_canvas_indicator)
        val hexValue = view.findViewById<EditText>(R.id.color_hex_value)
        val indicatorDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8f * resources.displayMetrics.density
            setColor(Color.TRANSPARENT)
            setStroke((3 * resources.displayMetrics.density).roundToInt(), Color.WHITE)
        }
        colorCanvasIndicator.background = indicatorDrawable

        colorCanvasImage.post {
            val width = colorCanvasImage.width
            val height = colorCanvasImage.height
            if (width <= 0 || height <= 0) return@post
            
            viewLifecycleOwner.lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.Default) {
                    createColorBoardBitmap(width, height)
                }
                colorCanvasImage.setImageBitmap(bitmap)
                
                val hsv = FloatArray(3)
                Color.colorToHSV(selectedColor, hsv)
                val hue = hsv[0]
                val x = ((hue / 360f) * (width - 1)).coerceIn(0f, (width - 1).toFloat())
                selectedColor = colorFromBoardPosition(x, width)
                val newHex = String.format(Locale.US, "#%06X", (0xFFFFFF and selectedColor))
                if (hexValue.text.toString() != newHex) {
                    lastHexText = newHex
                    hexValue.setText(newHex)
                }
                updateBoardIndicator(colorCanvasIndicator, x)
                updatePreview(view)
                iconAdapter?.notifyDataSetChanged()
            }
        }

        colorCanvasImage.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val width = colorCanvasImage.width
                    if (width <= 0) return@setOnTouchListener true
                    val x = event.x.coerceIn(0f, (width - 1).toFloat())
                    val newColor = colorFromBoardPosition(x, width)
                    if (newColor != selectedColor) {
                        selectedColor = newColor
                        val newHex = String.format(Locale.US, "#%06X", (0xFFFFFF and selectedColor))
                        if (newHex != lastHexText) {
                            lastHexText = newHex
                            hexValue.setText(newHex)
                            hexValue.setSelection(newHex.length)
                        }
                        updateBoardIndicator(colorCanvasIndicator, x)
                        updatePreview(view)
                        
                        // 只刷新当前被选中的图标，而不是整个列表
                        val index = ICON_LIST_ITEMS.indexOfFirst {
                            it is IconListItem.Glyph && CategoryLocalizer.normalizeIconGlyph(it.glyph) == selectedIconGlyph
                        }
                        if (index != -1) {
                            iconAdapter?.notifyItemChanged(index)
                        }
                    }
                    true
                }
                else -> false
            }
        }

        hexValue.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val input = s?.toString() ?: ""
                if (input == lastHexText) return
                
                if (input.matches(Regex("^#[0-9A-Fa-f]{6}$"))) {
                    try {
                        val newColor = Color.parseColor(input)
                        if (newColor != selectedColor) {
                            selectedColor = newColor
                            lastHexText = input
                            
                            // 更新指示器位置 (只计算Hue)
                            val width = colorCanvasImage.width
                            if (width > 0) {
                                val hsv = FloatArray(3)
                                Color.colorToHSV(selectedColor, hsv)
                                val x = ((hsv[0] / 360f) * (width - 1)).coerceIn(0f, (width - 1).toFloat())
                                updateBoardIndicator(colorCanvasIndicator, x)
                            }
                            
                            updatePreview(view)
                            val index = ICON_LIST_ITEMS.indexOfFirst {
                                it is IconListItem.Glyph && CategoryLocalizer.normalizeIconGlyph(it.glyph) == selectedIconGlyph
                            }
                            if (index != -1) {
                                iconAdapter?.notifyItemChanged(index)
                            }
                        }
                    } catch (e: Exception) {
                        // ignore invalid colors
                    }
                }
            }
        })
    }

    private fun createColorBoardBitmap(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val hsv = floatArrayOf(0f, 0.5f, 0.9f) // Lower saturation and higher value for macaron colors
        for (x in 0 until width) {
            val hue = 360f * (x.toFloat() / (width - 1).coerceAtLeast(1))
            hsv[0] = hue
            val color = Color.HSVToColor(hsv)
            for (y in 0 until height) {
                bitmap.setPixel(x, y, color)
            }
        }
        return bitmap
    }

    private fun colorFromBoardPosition(x: Float, width: Int): Int {
        val hue = 360f * (x / (width - 1).coerceAtLeast(1))
        return Color.HSVToColor(floatArrayOf(hue, 0.5f, 0.9f))
    }

    private fun updateBoardIndicator(indicator: View, x: Float) {
        indicator.x = (x - indicator.width / 2f).coerceIn(0f, (indicator.parent as View).width.toFloat() - indicator.width)
    }

    private fun setupIconPicker(view: View) {
        val iconGrid = view.findViewById<RecyclerView>(R.id.icon_grid)
        val layoutManager = GridLayoutManager(requireContext(), 6)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (ICON_LIST_ITEMS[position] is IconListItem.Header) 6 else 1
            }
        }
        iconGrid.layoutManager = layoutManager
        iconAdapter = IconAdapter()
        iconGrid.adapter = iconAdapter
        iconGrid.setHasFixedSize(true)
        // Ensure the RecyclerView is fully expanded by disabling nested scrolling
        iconGrid.isNestedScrollingEnabled = false
    }

    private var lastHexText = ""

    private fun updatePreview(view: View) {
        val previewContainer = view.findViewById<FrameLayout>(R.id.icon_preview_container)
        val previewText = view.findViewById<TextView>(R.id.icon_preview)
        
        var background = previewContainer.background as? GradientDrawable
        if (background == null) {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
            }
            previewContainer.background = background
        }
        background.setColor(ColorUtils.setAlphaComponent(selectedColor, 38))

        if (previewText.typeface != symbolTypeface) {
            previewText.typeface = symbolTypeface
        }
        val previewGlyph = CategoryLocalizer.normalizeIconGlyph(selectedIconGlyph)
        if (previewText.text != previewGlyph) {
            previewText.text = previewGlyph
        }
        previewText.setTextColor(selectedColor)
    }

    private fun saveCategory(view: View) {
        val nameInput = view.findViewById<EditText>(R.id.name_input)
        val name = nameInput.text?.toString()?.trim().orEmpty()
        val editId = editingCategoryId

        if (editId == null && name.isBlank()) {
            Toast.makeText(requireContext(), R.string.category_name_empty, Toast.LENGTH_SHORT).show()
            return
        }

        if (isEditMode && editId != null) {
            val current = viewModel.findCategory(editId)
            val effectiveName = if (isOtherCategory(editId)) {
                current?.name.orEmpty()
            } else {
                name
            }
            if (effectiveName.isBlank()) {
                Toast.makeText(requireContext(), R.string.category_name_empty, Toast.LENGTH_SHORT).show()
                return
            }
            val updated = viewModel.updateCategory(editId, effectiveName, selectedIconGlyph, selectedColor)
            if (updated) {
                Toast.makeText(requireContext(), R.string.category_updated, Toast.LENGTH_SHORT).show()
                activity?.onBackPressedDispatcher?.onBackPressed()
            } else {
                Toast.makeText(requireContext(), R.string.category_update_failed, Toast.LENGTH_SHORT).show()
            }
            return
        }

        val currentCategoriesCount = viewModel.categoriesFor(selectedType).size
        if (currentCategoriesCount >= 20) {
            Toast.makeText(requireContext(), R.string.category_limit_reached, Toast.LENGTH_SHORT).show()
            return
        }

        viewModel.addCustomCategory(name, selectedIconGlyph, selectedColor, selectedType)
        Toast.makeText(requireContext(), R.string.category_added, Toast.LENGTH_SHORT).show()
        activity?.onBackPressedDispatcher?.onBackPressed()
    }

    private fun isOtherCategory(categoryId: String?): Boolean {
        return categoryId == "expense_other" || categoryId == "income_other"
    }

    @Suppress("DEPRECATION")
    private fun applySystemBarColors() {
        requireActivity().window.apply {
            statusBarColor = ContextCompat.getColor(requireContext(), R.color.app_background)
            navigationBarColor = ContextCompat.getColor(requireContext(), R.color.app_background)
        }
    }

    sealed class IconListItem {
        data class Header(val titleResId: Int) : IconListItem()
        data class Glyph(val glyph: String) : IconListItem()
    }

    inner class IconAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val TYPE_HEADER = 0
        private val TYPE_GLYPH = 1

        override fun getItemViewType(position: Int): Int {
            return when (ICON_LIST_ITEMS[position]) {
                is IconListItem.Header -> TYPE_HEADER
                is IconListItem.Glyph -> TYPE_GLYPH
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == TYPE_HEADER) {
                val density = parent.resources.displayMetrics.density
                val textView = TextView(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = (16 * density).toInt()
                        bottomMargin = (8 * density).toInt()
                    }
                    textSize = 14f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    setPadding((4 * density).toInt(), 0, 0, 0)
                }
                return HeaderViewHolder(textView)
            } else {
                val density = parent.resources.displayMetrics.density
                val size = (48 * density).toInt()
                val container = FrameLayout(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        size
                    )
                }
                val textView = androidx.appcompat.widget.AppCompatTextView(parent.context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = android.view.Gravity.CENTER
                    }
                    typeface = symbolTypeface
                    textSize = 22f
                    includeFontPadding = false
                    letterSpacing = 0f
                    gravity = android.view.Gravity.CENTER
                    id = android.R.id.icon
                }
                container.addView(textView)
                return IconViewHolder(container, textView)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = ICON_LIST_ITEMS[position]
            if (holder is HeaderViewHolder && item is IconListItem.Header) {
                holder.textView.setText(item.titleResId)
            } else if (holder is IconViewHolder && item is IconListItem.Glyph) {
                val glyph = item.glyph
                val normalizedGlyph = CategoryLocalizer.normalizeIconGlyph(glyph)
                val isSelected = normalizedGlyph == selectedIconGlyph
                holder.textView.text = normalizedGlyph

                if (isSelected) {
                    holder.textView.setTextColor(selectedColor)
                    holder.itemView.background = GradientDrawable().apply {
                        cornerRadius = 12f * resources.displayMetrics.density
                        setColor(ColorUtils.setAlphaComponent(selectedColor, 25))
                    }
                } else {
                    holder.textView.setTextColor(
                        ContextCompat.getColor(requireContext(), R.color.editor_on_surface_variant)
                    )
                    holder.itemView.background = null
                }

                holder.itemView.setOnClickListener {
                    val old = selectedIconGlyph
                    if (old == normalizedGlyph) return@setOnClickListener
                    
                    selectedIconGlyph = normalizedGlyph
                    
                    // Notify all items that might be affected (old and new glyph)
                    // We iterate through the whole list to find all occurrences of both glyphs
                    ICON_LIST_ITEMS.forEachIndexed { index, item ->
                        if (item is IconListItem.Glyph) {
                            val normalized = CategoryLocalizer.normalizeIconGlyph(item.glyph)
                            if (normalized == old || normalized == normalizedGlyph) {
                                notifyItemChanged(index)
                            }
                        }
                    }
                    view?.let { updatePreview(it) }
                }
            }
        }

        override fun getItemCount(): Int = ICON_LIST_ITEMS.size

        inner class HeaderViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)

        inner class IconViewHolder(
            itemView: View,
            val textView: TextView
        ) : RecyclerView.ViewHolder(itemView)
    }

    companion object {
        private const val ARG_TYPE = "category_type"
        private const val ARG_CATEGORY_ID = "category_id"

        fun newInstance(type: TransactionType, categoryId: String? = null): CategoryAddFragment {
            return CategoryAddFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TYPE, type.name)
                    if (!categoryId.isNullOrBlank()) {
                        putString(ARG_CATEGORY_ID, categoryId)
                    }
                }
            }
        }

        val ICON_LIST_ITEMS = listOf(
            IconListItem.Header(R.string.icon_group_food),
            IconListItem.Glyph("restaurant"), IconListItem.Glyph("egg"), IconListItem.Glyph("icecream"), IconListItem.Glyph("lunch_dining"),
            IconListItem.Glyph("local_bar"), IconListItem.Glyph("bakery_dining"), IconListItem.Glyph("ramen_dining"), IconListItem.Glyph("local_pizza"),
            IconListItem.Glyph("cake"), IconListItem.Glyph("set_meal"), IconListItem.Glyph("rice_bowl"), IconListItem.Glyph("dinner_dining"),
            IconListItem.Glyph("coffee"), IconListItem.Glyph("wine_bar"), IconListItem.Glyph("fastfood"), IconListItem.Glyph("tapas"),
            IconListItem.Glyph("cookie"), IconListItem.Glyph("takeout_dining"),

            IconListItem.Header(R.string.icon_group_shopping),
            IconListItem.Glyph("shopping_basket"), IconListItem.Glyph("shopping_cart"), IconListItem.Glyph("shopping_bag"), IconListItem.Glyph("storefront"),
            IconListItem.Glyph("checkroom"), IconListItem.Glyph("dry_cleaning"), IconListItem.Glyph("laundry"), IconListItem.Glyph("iron"),
            IconListItem.Glyph("watch"), IconListItem.Glyph("diamond"), IconListItem.Glyph("redeem"), IconListItem.Glyph("card_giftcard"),
            IconListItem.Glyph("sell"), IconListItem.Glyph("receipt"), IconListItem.Glyph("inventory"), IconListItem.Glyph("loyalty"),
            IconListItem.Glyph("point_of_sale"), IconListItem.Glyph("local_offer"),

            IconListItem.Header(R.string.icon_group_transport),
            IconListItem.Glyph("directions_bus"), IconListItem.Glyph("directions_car"), IconListItem.Glyph("local_taxi"), IconListItem.Glyph("subway"),
            IconListItem.Glyph("train"), IconListItem.Glyph("flight_takeoff"), IconListItem.Glyph("flight"), IconListItem.Glyph("two_wheeler"),
            IconListItem.Glyph("pedal_bike"), IconListItem.Glyph("electric_scooter"), IconListItem.Glyph("sailing"), IconListItem.Glyph("directions_boat"),
            IconListItem.Glyph("local_gas_station"), IconListItem.Glyph("ev_station"), IconListItem.Glyph("garage"), IconListItem.Glyph("toll"),
            IconListItem.Glyph("car_repair"), IconListItem.Glyph("tire_repair"),

            IconListItem.Header(R.string.icon_group_entertainment),
            IconListItem.Glyph("confirmation_number"), IconListItem.Glyph("movie"), IconListItem.Glyph("sports_esports"), IconListItem.Glyph("casino"),
            IconListItem.Glyph("music_note"), IconListItem.Glyph("headphones"), IconListItem.Glyph("theater_comedy"), IconListItem.Glyph("videogame_asset"),
            IconListItem.Glyph("celebration"), IconListItem.Glyph("festival"), IconListItem.Glyph("nightlife"), IconListItem.Glyph("attractions"),
            IconListItem.Glyph("photo_camera"), IconListItem.Glyph("palette"), IconListItem.Glyph("brush"), IconListItem.Glyph("draw"),
            IconListItem.Glyph("piano"), IconListItem.Glyph("music_video"),

            IconListItem.Header(R.string.icon_group_health),
            IconListItem.Glyph("fitness_center"), IconListItem.Glyph("sports_soccer"), IconListItem.Glyph("pool"), IconListItem.Glyph("self_improvement"),
            IconListItem.Glyph("spa"), IconListItem.Glyph("medical_services"), IconListItem.Glyph("medication"), IconListItem.Glyph("vaccines"),
            IconListItem.Glyph("stethoscope"), IconListItem.Glyph("medical_information"), IconListItem.Glyph("health_and_safety"), IconListItem.Glyph("monitor_heart"),
            IconListItem.Glyph("psychology"), IconListItem.Glyph("badminton"), IconListItem.Glyph("sports_tennis"), IconListItem.Glyph("sports_basketball"),
            IconListItem.Glyph("sports_golf"), IconListItem.Glyph("hiking"),
            IconListItem.Glyph("sports_volleyball"), IconListItem.Glyph("sports_baseball"), IconListItem.Glyph("sports_motorsports"), IconListItem.Glyph("sports_martial_arts"),
            IconListItem.Glyph("surfing"), IconListItem.Glyph("kayaking"), IconListItem.Glyph("ice_skating"), IconListItem.Glyph("downhill_skiing"),
            IconListItem.Glyph("snowboarding"), IconListItem.Glyph("roller_skating"), IconListItem.Glyph("rowing"), IconListItem.Glyph("sports_kabaddi"),
            IconListItem.Glyph("sports_football"), IconListItem.Glyph("sports_handball"), IconListItem.Glyph("sports_cricket"), IconListItem.Glyph("sports_hockey"),
            IconListItem.Glyph("sports_rugby"), IconListItem.Glyph("sports_gymnastics"),

            IconListItem.Header(R.string.icon_group_home),
            IconListItem.Glyph("home"), IconListItem.Glyph("house"), IconListItem.Glyph("apartment"), IconListItem.Glyph("villa"),
            IconListItem.Glyph("chair"), IconListItem.Glyph("bed"), IconListItem.Glyph("bathtub"), IconListItem.Glyph("kitchen"),
            IconListItem.Glyph("lightbulb"), IconListItem.Glyph("bolt"), IconListItem.Glyph("water_drop"), IconListItem.Glyph("local_fire_department"),
            IconListItem.Glyph("cleaning_services"), IconListItem.Glyph("handyman"), IconListItem.Glyph("plumbing"), IconListItem.Glyph("roofing"),
            IconListItem.Glyph("window"), IconListItem.Glyph("yard"),

            IconListItem.Header(R.string.icon_group_education),
            IconListItem.Glyph("school"), IconListItem.Glyph("menu_book"), IconListItem.Glyph("auto_stories"), IconListItem.Glyph("library_books"),
            IconListItem.Glyph("science"), IconListItem.Glyph("biotech"), IconListItem.Glyph("calculate"), IconListItem.Glyph("architecture"),
            IconListItem.Glyph("devices"), IconListItem.Glyph("computer"), IconListItem.Glyph("phone_iphone"), IconListItem.Glyph("tablet_mac"),
            IconListItem.Glyph("keyboard"), IconListItem.Glyph("mouse"), IconListItem.Glyph("print"), IconListItem.Glyph("scanner"),
            IconListItem.Glyph("assignment"), IconListItem.Glyph("edit"),

            IconListItem.Header(R.string.icon_group_finance),
            IconListItem.Glyph("payments"), IconListItem.Glyph("savings"), IconListItem.Glyph("account_balance"), IconListItem.Glyph("trending_up"),
            IconListItem.Glyph("paid"), IconListItem.Glyph("receipt_long"), IconListItem.Glyph("request_quote"), IconListItem.Glyph("price_check"),
            IconListItem.Glyph("currency_exchange"), IconListItem.Glyph("credit_card"), IconListItem.Glyph("local_atm"), IconListItem.Glyph("monetization_on"),
            IconListItem.Glyph("account_balance_wallet"), IconListItem.Glyph("wallet"), IconListItem.Glyph("attach_money"), IconListItem.Glyph("money"),
            IconListItem.Glyph("query_stats"), IconListItem.Glyph("insights"),

            IconListItem.Header(R.string.icon_group_communication),
            IconListItem.Glyph("call"), IconListItem.Glyph("chat"), IconListItem.Glyph("email"), IconListItem.Glyph("forum"),
            IconListItem.Glyph("sms"), IconListItem.Glyph("video_call"), IconListItem.Glyph("wifi"), IconListItem.Glyph("cell_tower"),
            IconListItem.Glyph("groups"), IconListItem.Glyph("people"), IconListItem.Glyph("family_restroom"), IconListItem.Glyph("person"),
            IconListItem.Glyph("face_3"), IconListItem.Glyph("face_6"), IconListItem.Glyph("diversity_3"), IconListItem.Glyph("handshake"),
            IconListItem.Glyph("favorite"), IconListItem.Glyph("volunteer_activism"), IconListItem.Glyph("emoji_events"), IconListItem.Glyph("military_tech"),
            IconListItem.Glyph("public"), IconListItem.Glyph("history"), IconListItem.Glyph("notifications"), IconListItem.Glyph("share"),

            IconListItem.Header(R.string.icon_group_pets),
            IconListItem.Glyph("pets"), IconListItem.Glyph("cruelty_free"), IconListItem.Glyph("park"), IconListItem.Glyph("forest"), IconListItem.Glyph("eco"), IconListItem.Glyph("grass"),
            IconListItem.Glyph("water"), IconListItem.Glyph("bug_report"), IconListItem.Glyph("flower"), IconListItem.Glyph("agriculture"), IconListItem.Glyph("filter_hdr"), IconListItem.Glyph("nest_eco_leaf"),

            IconListItem.Header(R.string.icon_group_travel),
            IconListItem.Glyph("connecting_airports"), IconListItem.Glyph("hotel"), IconListItem.Glyph("luggage"), IconListItem.Glyph("beach_access"),
            IconListItem.Glyph("map"), IconListItem.Glyph("explore"), IconListItem.Glyph("tour"), IconListItem.Glyph("camping"),
            IconListItem.Glyph("globe_uk"), IconListItem.Glyph("language"), IconListItem.Glyph("flag"), IconListItem.Glyph("photo_camera_back"),
            IconListItem.Glyph("tram"), IconListItem.Glyph("directions_railway"), IconListItem.Glyph("car_rental"), IconListItem.Glyph("mountain_flag"),
            IconListItem.Glyph("flight_land"), IconListItem.Glyph("holiday_village"),

            IconListItem.Header(R.string.icon_group_beauty),
            IconListItem.Glyph("face_retouching_natural"), IconListItem.Glyph("health_and_beauty"), IconListItem.Glyph("content_cut"), IconListItem.Glyph("flare"), IconListItem.Glyph("face_5"), IconListItem.Glyph("face_4"),
            IconListItem.Glyph("face_2"),  IconListItem.Glyph("styler"),
            IconListItem.Glyph("face"), IconListItem.Glyph("wash"),
            IconListItem.Glyph("clean_hands"), IconListItem.Glyph("shower"),

            IconListItem.Header(R.string.icon_group_tech),
            IconListItem.Glyph("router"), IconListItem.Glyph("speaker"), IconListItem.Glyph("gamepad"), IconListItem.Glyph("earbuds"),
            IconListItem.Glyph("camera"), IconListItem.Glyph("desktop_mac"), IconListItem.Glyph("keyboard_alt"), IconListItem.Glyph("memory"),
            IconListItem.Glyph("tv"), IconListItem.Glyph("laptop"), IconListItem.Glyph("phonelink"), IconListItem.Glyph("developer_board"),
            IconListItem.Glyph("mic"), IconListItem.Glyph("keyboard_tab"), IconListItem.Glyph("save"), IconListItem.Glyph("sd_card"),
            IconListItem.Glyph("fax"), IconListItem.Glyph("tablet"),

            IconListItem.Header(R.string.icon_group_weather),
            IconListItem.Glyph("sunny"), IconListItem.Glyph("cloudy"), IconListItem.Glyph("rainy"), IconListItem.Glyph("ac_unit"),
            IconListItem.Glyph("thermostat"), IconListItem.Glyph("air"), IconListItem.Glyph("cloudy_snowing"), IconListItem.Glyph("thunderstorm"),
            IconListItem.Glyph("partly_cloudy_day"), IconListItem.Glyph("partly_cloudy_night"), IconListItem.Glyph("wind_power"), IconListItem.Glyph("storm"),
            IconListItem.Glyph("tornado"), IconListItem.Glyph("tsunami"), IconListItem.Glyph("flood"), IconListItem.Glyph("severe_cold"),
            IconListItem.Glyph("volcano"), IconListItem.Glyph("umbrella"),

            IconListItem.Header(R.string.icon_group_misc),
            IconListItem.Glyph("category"), IconListItem.Glyph("more_horiz"), IconListItem.Glyph("interests"), IconListItem.Glyph("star"),
            IconListItem.Glyph("bookmark"), IconListItem.Glyph("label"), IconListItem.Glyph("tag"), IconListItem.Glyph("push_pin"),
            IconListItem.Glyph("tips_and_updates"), IconListItem.Glyph("auto_awesome"), IconListItem.Glyph("whatshot"),
            IconListItem.Glyph("build"), IconListItem.Glyph("settings"), IconListItem.Glyph("tune"), IconListItem.Glyph("extension"),
            IconListItem.Glyph("emoji_objects"), IconListItem.Glyph("rocket_launch"), IconListItem.Glyph("smart_toy"), IconListItem.Glyph("token"),
            IconListItem.Glyph("workspace_premium"), IconListItem.Glyph("verified"), IconListItem.Glyph("shield"), IconListItem.Glyph("lock"),
            IconListItem.Glyph("cloud"), IconListItem.Glyph("backup"), IconListItem.Glyph("sync"), IconListItem.Glyph("schedule"),
            IconListItem.Glyph("alarm"), IconListItem.Glyph("timer"), IconListItem.Glyph("event"),
            IconListItem.Glyph("package_2"), IconListItem.Glyph("deployed_code"), IconListItem.Glyph("view_in_ar"),
            IconListItem.Glyph("local_shipping"), IconListItem.Glyph("warehouse"), IconListItem.Glyph("factory"),
            IconListItem.Glyph("undo"), IconListItem.Glyph("redo"), IconListItem.Glyph("refresh"), IconListItem.Glyph("done"),
            IconListItem.Glyph("add_circle"), IconListItem.Glyph("remove_circle"), IconListItem.Glyph("help"), IconListItem.Glyph("info"),
            IconListItem.Glyph("warning"), IconListItem.Glyph("error"), IconListItem.Glyph("notifications_active"), IconListItem.Glyph("verified_user")
        )
    }
}
