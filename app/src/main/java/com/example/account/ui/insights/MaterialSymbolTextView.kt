package com.example.account.ui.insights

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.res.ResourcesCompat
import com.example.account.R

class MaterialSymbolTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    init {
        includeFontPadding = false
        fontFeatureSettings = "liga"
        typeface = ResourcesCompat.getFont(context, R.font.material_symbols_outlined_static)
    }
}
