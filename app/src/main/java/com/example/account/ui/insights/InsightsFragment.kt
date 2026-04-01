package com.example.account.ui.insights

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.example.account.R
import com.example.account.data.LedgerCategory
import com.example.account.data.LedgerTransaction
import com.example.account.data.LedgerViewModel
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

class InsightsFragment : Fragment() {

    private var webView: WebView? = null
    private lateinit var viewModel: LedgerViewModel

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_insights, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(
            requireActivity(),
            LedgerViewModel.Factory(requireActivity().application)
        )[LedgerViewModel::class.java]

        webView = view.findViewById<WebView>(R.id.insights_webview).apply {
            setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.app_background))
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                builtInZoomControls = false
                displayZoomControls = false
                loadWithOverviewMode = true
                useWideViewPort = true
            }
        }

        viewModel.refresh()
        renderInsights()
    }

    private fun renderInsights() {
        val template = requireContext().assets.open(ASSET_FILE_NAME).bufferedReader(Charsets.UTF_8).use {
            it.readText()
        }
        val html = template
            .replace("{{DATA_JSON}}", buildPayload().toString())

        webView?.loadDataWithBaseURL(
            BASE_URL,
            html,
            "text/html",
            "utf-8",
            null
        )
    }

    private fun buildPayload(): JSONObject {
        val transactions = viewModel.getAllTransactions()
        val categories = viewModel.getAllCategories()
        return JSONObject().apply {
            put("transactions", JSONArray().apply {
                transactions.forEach { put(it.toJson()) }
            })
            put("categories", JSONArray().apply {
                categories.forEach { put(it.toJson()) }
            })
            put("monthlyBudgets", buildMonthlyBudgets(transactions))
        }
    }

    private fun buildMonthlyBudgets(transactions: List<LedgerTransaction>): JSONObject {
        val zoneId = ZoneId.systemDefault()
        val nowMonth = YearMonth.now(zoneId)
        val transactionMonths = transactions.map {
            YearMonth.from(Instant.ofEpochMilli(it.timestampMillis).atZone(zoneId))
        }
        val minYear = minOf(transactionMonths.minOrNull()?.year ?: nowMonth.year, nowMonth.year)
        val maxYear = maxOf(transactionMonths.maxOrNull()?.year ?: nowMonth.year, nowMonth.year)

        return JSONObject().apply {
            for (year in minYear..maxYear) {
                for (monthValue in 1..12) {
                    val month = YearMonth.of(year, monthValue)
                    val budget = viewModel.getMonthlyBudget(month)
                    if (budget != null && budget > 0.0) {
                        put(month.toString(), budget)
                    }
                }
            }
        }
    }

    private fun LedgerTransaction.toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("type", type.name)
            put("categoryId", categoryId)
            put("amount", amount)
            put("currency", currency.name)
            put("timestampMillis", timestampMillis)
        }
    }

    private fun LedgerCategory.toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("type", type.name)
            put("iconGlyph", iconGlyph.ifBlank { "payments" })
        }
    }

    override fun onDestroyView() {
        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            removeAllViews()
            destroy()
        }
        webView = null
        super.onDestroyView()
    }

    companion object {
        private const val ASSET_FILE_NAME = "insights.html"
        private const val BASE_URL = "https://appassets.androidplatform.net/"

        fun newInstance(): InsightsFragment = InsightsFragment()
    }
}
