package com.example.account

import android.app.Application

class AccountApplication : Application() {

    override fun onCreate() {
        PerfTrace.measure("AccountApplication.onCreate") {
            super.onCreate()
            CrashLogger.install(this)
        }
    }
}
