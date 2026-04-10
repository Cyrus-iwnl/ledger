package com.example.account.data

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.HttpURLConnection
import java.net.URL

object ExchangeRateApiClient {

    fun requestLatestRatesToCny(): Map<CurrencyCode, Double> {
        val quotes = AUTO_FETCH_CURRENCIES.joinToString(",") { it.code }
        val connection = (URL("$FRANKFURTER_API/v2/rates?base=CNY&quotes=$quotes").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/json")
        }

        try {
            val statusCode = connection.responseCode
            val body = (if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            })?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (statusCode !in 200..299) {
                val message = parseApiErrorMessage(body) ?: "HTTP $statusCode"
                throw IllegalStateException(message)
            }

            val response = JSONArray(body)
            val rates = mutableMapOf<CurrencyCode, Double>()
            for (index in 0 until response.length()) {
                val item = response.getJSONObject(index)
                val quoteCode = item.optString("quote")
                val quoteCurrency = AUTO_FETCH_CURRENCIES.firstOrNull { it.code == quoteCode } ?: continue
                val ratePerCny = item.optString("rate").toBigDecimalOrNull()
                if (ratePerCny == null || ratePerCny <= BigDecimal.ZERO) {
                    continue
                }
                val cnyPerUnit = BigDecimal.ONE
                    .divide(ratePerCny, 6, RoundingMode.HALF_UP)
                    .toDouble()
                rates[quoteCurrency] = cnyPerUnit
            }

            if (rates.isEmpty()) {
                throw IllegalStateException("No valid rates returned")
            }
            return rates
        } finally {
            connection.disconnect()
        }
    }

    private fun parseApiErrorMessage(body: String): String? {
        if (body.isBlank()) {
            return null
        }
        return runCatching {
            JSONObject(body).optString("message")
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private const val FRANKFURTER_API = "https://api.frankfurter.dev"
    private val AUTO_FETCH_CURRENCIES = CurrencyCode.values().filter { it != CurrencyCode.CNY }
}

