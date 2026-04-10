package com.example.account.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.account.data.ExchangeRateApiClient
import com.example.account.data.LedgerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class ExchangeRateSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val repository = LedgerRepository(applicationContext)
        if (!repository.needsExchangeRateDailyRefresh()) {
            return@withContext Result.success()
        }
        runCatching {
            val rates = ExchangeRateApiClient.requestLatestRatesToCny()
            rates.forEach { (currency, rate) ->
                repository.setExchangeRateToCny(currency, rate)
            }
            repository.markExchangeRateRefreshed()
        }.fold(
            onSuccess = { Result.success() },
            onFailure = {
                if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                    Result.retry()
                } else {
                    Result.failure()
                }
            }
        )
    }

    companion object {
        private const val UNIQUE_PERIODIC_WORK_NAME = "exchange_rate_daily_sync_work"
        private const val UNIQUE_STARTUP_WORK_NAME = "exchange_rate_startup_sync_work"
        private const val MAX_RETRY_ATTEMPTS = 3

        fun enqueueDaily(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val periodicRequest = PeriodicWorkRequestBuilder<ExchangeRateSyncWorker>(1, TimeUnit.DAYS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicRequest
            )
            val startupRequest = OneTimeWorkRequestBuilder<ExchangeRateSyncWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_STARTUP_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                startupRequest
            )
        }
    }
}
