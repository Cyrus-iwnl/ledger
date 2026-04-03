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
import com.example.account.ui.settings.AppStartupPage
import com.example.account.ui.settings.LanguageManager
import com.example.account.ui.settings.SettingsFragment
import com.example.account.ui.settings.StartupPageManager

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val homeTag = HomeFragment::class.java.name
    private val editorTag = EditTransactionFragment::class.java.name
    private val insightsTag = InsightsFragment::class.java.name
    private val settingsTag = SettingsFragment::class.java.name

    override fun onCreate(savedInstanceState: Bundle?) {
        PerfTrace.measure("MainActivity.onCreate") {
            PerfTrace.measure("MainActivity.applySavedLanguage") {
                LanguageManager.applySavedLanguage(this)
            }
            PerfTrace.measure("MainActivity.super.onCreate") {
                super.onCreate(savedInstanceState)
            }
            PerfTrace.measure("MainActivity.inflate+setContentView") {
                binding = ActivityMainBinding.inflate(layoutInflater)
                setContentView(binding.root)
            }

            if (savedInstanceState == null) {
                PerfTrace.measure("MainActivity.initialShowPage") {
                    showInitialPage()
                }
            }

            PerfTrace.measure("MainActivity.bindUiListeners") {
                binding.fabAdd.setOnClickListener {
                    openTransactionEditor()
                }

                supportFragmentManager.addOnBackStackChangedListener {
                    updateChrome()
                }

                onBackPressedDispatcher.addCallback(this@MainActivity, object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        if (supportFragmentManager.backStackEntryCount > 0) {
                            supportFragmentManager.popBackStack()
                        } else {
                            finish()
                        }
                    }
                })
            }

            updateChrome()
        }
    }

    fun showHome() {
        PerfTrace.measure("MainActivity.showHome") {
            supportFragmentManager.popBackStackImmediate(
                null,
                androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE
            )
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, HomeFragment.newInstance(), homeTag)
                .commitNowAllowingStateLoss()
            updateChrome()
        }
    }

    private fun showInitialPage() {
        when (StartupPageManager.currentStartupPage(this)) {
            AppStartupPage.HOME -> showHome()
            AppStartupPage.TRANSACTION -> showTransactionEditorAsRoot()
        }
    }

    private fun showTransactionEditorAsRoot(
        dateMillis: Long? = null,
        defaultType: TransactionType = TransactionType.EXPENSE
    ) {
        supportFragmentManager.popBackStackImmediate(
            null,
            androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE
        )
        supportFragmentManager.beginTransaction()
            .replace(
                R.id.fragment_container,
                EditTransactionFragment.newInstance(transactionId = null, dateMillis = dateMillis, defaultType = defaultType),
                editorTag
            )
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

    fun openInsights(
        defaultGranularity: String = "MONTH",
        defaultYear: Int? = null,
        defaultMonthIndex: Int? = null
    ) {
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.fragment_forward_enter,
                R.anim.fragment_forward_exit,
                R.anim.fragment_pop_enter,
                R.anim.fragment_pop_exit
            )
            .replace(
                R.id.fragment_container,
                InsightsFragment.newInstance(
                    defaultGranularity = defaultGranularity,
                    defaultYear = defaultYear,
                    defaultMonthIndex = defaultMonthIndex
                ),
                insightsTag
            )
            .addToBackStack(insightsTag)
            .commit()
        updateChrome()
    }

    fun openSettings() {
        supportFragmentManager.beginTransaction()
            .replace(
                R.id.fragment_container,
                SettingsFragment.newInstance(),
                settingsTag
            )
            .addToBackStack(settingsTag)
            .commit()
        updateChrome()
    }

    private fun updateChrome() {
        val isHomeVisible = supportFragmentManager.findFragmentById(R.id.fragment_container) is HomeFragment
        binding.fabAdd.isVisible = isHomeVisible
    }
}
