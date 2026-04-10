package com.example.account

import android.app.Application
import com.example.account.sync.ExchangeRateSyncWorker

class AccountApplication : Application() {

    override fun onCreate() {
        PerfTrace.measure("AccountApplication.onCreate") {
            super.onCreate()
            CrashLogger.install(this)
            ExchangeRateSyncWorker.enqueueDaily(this)
        }
    }
}
