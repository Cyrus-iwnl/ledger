package com.example.account.ui.insights

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout

class SquareLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val side = measuredWidth
        setMeasuredDimension(side, side)
    }
}
