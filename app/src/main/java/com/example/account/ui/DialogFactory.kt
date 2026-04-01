package com.example.account.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import com.example.account.R

object DialogFactory {

    fun createCardDialog(context: Context, contentView: View): Dialog {
        return Dialog(context, R.style.Theme_Account_CardDialog).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(contentView)
            setCancelable(true)
            setCanceledOnTouchOutside(true)
            window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT
                )
                setGravity(Gravity.CENTER)
                setWindowAnimations(R.style.Animation_Account_CardDialog)
            }
        }
    }
}
