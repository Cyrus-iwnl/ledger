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
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import com.example.account.R
import com.example.account.data.CategoryLocalizer
import com.example.account.data.LedgerCategory
import com.example.account.data.LedgerTransaction
import com.example.account.data.LedgerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

class InsightsFragment : Fragment() {

    private var webView: WebView? = null
    private var webContainer: ViewGroup? = null
    private var loadingView: View? = null
    private lateinit var viewModel: LedgerViewModel
    private var renderJob: Job? = null

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

        webContainer = view.findViewById(R.id.insights_webview_container)
        loadingView = view.findViewById(R.id.insights_loading)
        loadingView?.isVisible = true

        view.post {
            if (!isAdded || this@InsightsFragment.view == null) {
                return@post
            }
            ensureWebView()
            renderInsights()
        }
    }

    private fun ensureWebView() {
        if (webView != null) {
            return
        }
        val container = webContainer ?: return
        webView = WebView(requireContext()).apply {
            setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.app_background))
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            isLongClickable = false
            isHapticFeedbackEnabled = false
            setOnLongClickListener { true }
            webViewClient = object : WebViewClient() {
                override fun onPageCommitVisible(view: WebView?, url: String?) {
                    loadingView?.isVisible = false
                }
            }
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
        container.addView(
            webView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun renderInsights() {
        loadingView?.isVisible = true
        val appAssets = requireContext().applicationContext.assets
        renderJob?.cancel()
        renderJob = viewLifecycleOwner.lifecycleScope.launch {
            val html = withContext(Dispatchers.Default) {
                val template = loadTemplate(appAssets)
                template.replace("{{DATA_JSON}}", buildPayload().toString())
            }
            webView?.loadDataWithBaseURL(
                BASE_URL,
                html,
                "text/html",
                "utf-8",
                null
            )
        }
    }

    private fun buildPayload(): JSONObject {
        val transactions = viewModel.getAllTransactions()
        val categories = viewModel.getAllCategories()
        val context = requireContext()
        return JSONObject().apply {
            put("locale", currentLocaleTag())
            put("transactions", JSONArray().apply {
                transactions.forEach { put(it.toJson()) }
            })
            put("categories", JSONArray().apply {
                categories.forEach { put(it.toJson(context)) }
            })
            put("monthlyBudgets", buildMonthlyBudgets(transactions))
        }
    }

    private fun buildMonthlyBudgets(transactions: List<LedgerTransaction>): JSONObject {
        val zoneId = ZoneId.systemDefault()
        val nowMonth = YearMonth.now(zoneId)
        var minYear = nowMonth.year
        var maxYear = nowMonth.year

        transactions.forEach { transaction ->
            val year = Instant.ofEpochMilli(transaction.timestampMillis).atZone(zoneId).year
            minYear = min(minYear, year)
            maxYear = max(maxYear, year)
        }

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

    private fun LedgerCategory.toJson(context: android.content.Context): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", CategoryLocalizer.nameForId(context, id, name))
            put("type", type.name)
            put("iconGlyph", iconGlyph.ifBlank { "payments" })
        }
    }

    private fun currentLocaleTag(): String {
        val locales = resources.configuration.locales
        return if (locales.isEmpty) "en" else locales[0].toLanguageTag()
    }

    override fun onDestroyView() {
        renderJob?.cancel()
        renderJob = null
        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            removeAllViews()
            destroy()
        }
        webContainer?.removeAllViews()
        webView = null
        webContainer = null
        loadingView = null
        super.onDestroyView()
    }

    companion object {
        private const val ASSET_FILE_NAME = "insights.html"
        private const val BASE_URL = "https://appassets.androidplatform.net/"
        @Volatile
        private var cachedTemplate: String? = null

        private fun loadTemplate(assets: android.content.res.AssetManager): String {
            val cached = cachedTemplate
            if (cached != null) {
                return cached
            }
            return synchronized(this) {
                cachedTemplate ?: assets.open(ASSET_FILE_NAME).bufferedReader(Charsets.UTF_8).use {
                    it.readText()
                }.also { template ->
                    cachedTemplate = template
                }
            }
        }

        fun newInstance(): InsightsFragment = InsightsFragment()
    }
}
