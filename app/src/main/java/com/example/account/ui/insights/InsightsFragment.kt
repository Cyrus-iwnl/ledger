package com.example.account.ui.insights

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
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
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class InsightsFragment : Fragment() {

    private var webView: WebView? = null
    private var webContainer: ViewGroup? = null
    private var loadingView: View? = null
    private lateinit var viewModel: LedgerViewModel
    private var renderJob: Job? = null
    private var fragmentStartNanos: Long = 0L
    private var currentTraceId: Int = 0
    private var currentPageLoadStartNanos: Long = 0L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_insights, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fragmentStartNanos = SystemClock.elapsedRealtimeNanos()
        logStartup(currentTraceId, "onViewCreated")

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
            logStartup(currentTraceId, "view.post callback")
            ensureWebView()
            renderInsights()
        }
    }

    private fun ensureWebView() {
        if (webView != null) {
            logStartup(currentTraceId, "ensureWebView reused existing instance")
            return
        }
        val container = webContainer ?: return
        val startNanos = SystemClock.elapsedRealtimeNanos()
        webView = WebView(requireContext()).apply {
            setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.app_background))
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            isLongClickable = false
            isHapticFeedbackEnabled = false
            setOnLongClickListener { true }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    logPagePhase("onPageStarted")
                }

                override fun onPageCommitVisible(view: WebView?, url: String?) {
                    logPagePhase("onPageCommitVisible")
                    loadingView?.isVisible = false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    logPagePhase("onPageFinished")
                }
            }
            webChromeClient = object : WebChromeClient() {}
            addJavascriptInterface(object : Any() {
                @android.webkit.JavascriptInterface
                fun close() {
                    activity?.runOnUiThread {
                        if (isAdded) {
                            parentFragmentManager.popBackStack()
                        }
                    }
                }

                @android.webkit.JavascriptInterface
                fun logTiming(message: String) {
                    logStartup(currentTraceId, "web.$message")
                }
            }, "Android")
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                blockNetworkLoads = true
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
        logStep(currentTraceId, "ensureWebView", startNanos)
    }

    private fun renderInsights() {
        loadingView?.isVisible = true
        val appAssets = requireContext().applicationContext.assets
        renderJob?.cancel()
        val traceId = ++currentTraceId
        val renderStartNanos = SystemClock.elapsedRealtimeNanos()
        logStartup(traceId, "renderInsights start")
        renderJob = viewLifecycleOwner.lifecycleScope.launch {
            val html = withContext(Dispatchers.Default) {
                val template = measure(traceId, "loadTemplate") { loadTemplate(appAssets) }
                val payload = measure(traceId, "buildPayload") { buildPayload(traceId) }
                measure(traceId, "mergeHtmlTemplate") {
                    template.replace("{{DATA_JSON}}", payload.toString())
                }
            }
            logStep(traceId, "prepareHtml", renderStartNanos, "size=${html.length}")
            currentPageLoadStartNanos = SystemClock.elapsedRealtimeNanos()
            webView?.loadDataWithBaseURL(
                BASE_URL,
                html,
                "text/html",
                "utf-8",
                null
            )
            logStartup(traceId, "loadDataWithBaseURL dispatched")
        }
    }

    private fun buildPayload(traceId: Int): JSONObject {
        val localeTag = measure(traceId, "payload.currentLocaleTag") { currentLocaleTag() }
        val transactions = measure(traceId, "payload.getAllTransactions") {
            viewModel.getAllTransactions()
        }
        val categories = measure(traceId, "payload.getAllCategories") {
            viewModel.getAllCategories()
        }
        val context = requireContext()
        val transactionsJson = measure(traceId, "payload.transactionsToJson") {
            JSONArray().apply {
                transactions.forEach { put(it.toJson()) }
            }
        }
        val categoriesJson = measure(traceId, "payload.categoriesToJson") {
            JSONArray().apply {
                categories.forEach { put(it.toJson(context)) }
            }
        }
        val monthlyBudgets = measure(traceId, "payload.buildMonthlyBudgets") {
            buildMonthlyBudgets(traceId, transactions)
        }
        return JSONObject().apply {
            put("locale", localeTag)
            put("transactions", transactionsJson)
            put("categories", categoriesJson)
            put("monthlyBudgets", monthlyBudgets)
        }
            .also {
                logStartup(
                    traceId,
                    "payload ready: transactions=${transactions.size}, categories=${categories.size}, budgets=${monthlyBudgets.length()}"
                )
            }
    }

    private fun buildMonthlyBudgets(traceId: Int, transactions: List<LedgerTransaction>): JSONObject {
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
            var budgetMonths = 0
            for (year in minYear..maxYear) {
                for (monthValue in 1..12) {
                    val month = YearMonth.of(year, monthValue)
                    val budget = viewModel.getMonthlyBudget(month)
                    if (budget != null && budget > 0.0) {
                        put(month.toString(), budget)
                        budgetMonths++
                    }
                }
            }
            logStartup(
                traceId,
                "payload.buildMonthlyBudgets scannedYears=${maxYear - minYear + 1}, populatedMonths=$budgetMonths"
            )
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
            put("accentColor", String.format("#%06X", 0xFFFFFF and accentColor))
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
        private const val TAG = "InsightsPerf"
        private const val ASSET_FILE_NAME = "insights.html"
        private const val BASE_URL = "file:///android_asset/"
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

    private inline fun <T> measure(traceId: Int, step: String, block: () -> T): T {
        val startNanos = SystemClock.elapsedRealtimeNanos()
        return block().also {
            logStep(traceId, step, startNanos)
        }
    }

    private fun logPagePhase(phase: String) {
        val loadStart = currentPageLoadStartNanos
        val total = if (loadStart == 0L) "" else " | since loadData=${elapsedMsString(loadStart)}"
        logStartup(currentTraceId, "webView.$phase$total")
    }

    private fun logStep(traceId: Int, step: String, startNanos: Long, extra: String? = null) {
        val detail = buildString {
            append(step)
            append(" took ")
            append(elapsedMsString(startNanos))
            append(" ms")
            if (!extra.isNullOrBlank()) {
                append(" | ")
                append(extra)
            }
        }
        logStartup(traceId, detail)
    }

    private fun logStartup(traceId: Int, message: String) {
        val traceLabel = if (traceId > 0) "#$traceId" else "#-"
        Log.d(
            TAG,
            "[$traceLabel +${elapsedMsString(fragmentStartNanos)} ms] $message"
        )
    }

    private fun elapsedMsString(startNanos: Long): String {
        val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000.0
        return String.format(Locale.US, "%.1f", elapsedMs)
    }
}
