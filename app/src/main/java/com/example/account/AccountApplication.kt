package com.example.account

import android.app.Application

class AccountApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
    }
}
