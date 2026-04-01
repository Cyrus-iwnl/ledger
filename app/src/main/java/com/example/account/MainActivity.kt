package com.example.account

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.example.account.data.TransactionType
import com.example.account.databinding.ActivityMainBinding
import com.example.account.ui.edit.EditTransactionFragment
import com.example.account.ui.home.HomeFragment
import com.example.account.ui.insights.InsightsFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val homeTag = HomeFragment::class.java.name
    private val editorTag = EditTransactionFragment::class.java.name
    private val insightsTag = InsightsFragment::class.java.name

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            showHome()
        }

        binding.fabAdd.setOnClickListener {
            openTransactionEditor()
        }

        supportFragmentManager.addOnBackStackChangedListener {
            updateChrome()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (supportFragmentManager.backStackEntryCount > 0) {
                    supportFragmentManager.popBackStack()
                } else {
                    finish()
                }
            }
        })

        updateChrome()
    }

    fun showHome() {
        supportFragmentManager.popBackStackImmediate(
            null,
            androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE
        )
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, HomeFragment.newInstance(), homeTag)
            .commitNowAllowingStateLoss()
        updateChrome()
    }

    fun openTransactionEditor(
        transactionId: Long? = null,
        dateMillis: Long? = null,
        defaultType: TransactionType = TransactionType.EXPENSE
    ) {
        supportFragmentManager.beginTransaction()
            .replace(
                R.id.fragment_container,
                EditTransactionFragment.newInstance(transactionId, dateMillis, defaultType),
                editorTag
            )
            .addToBackStack(editorTag)
            .commit()
        updateChrome()
    }

    fun closeEditorAndReturnHome() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStackImmediate()
        } else {
            showHome()
        }
        updateChrome()
    }

    fun openInsights() {
        supportFragmentManager.beginTransaction()
            .replace(
                R.id.fragment_container,
                InsightsFragment.newInstance(),
                insightsTag
            )
            .addToBackStack(insightsTag)
            .commit()
        updateChrome()
    }

    private fun updateChrome() {
        val isHomeVisible = supportFragmentManager.findFragmentById(R.id.fragment_container) is HomeFragment
        binding.fabAdd.isVisible = isHomeVisible
    }
}
