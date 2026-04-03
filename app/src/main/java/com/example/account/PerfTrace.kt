package com.example.account

import android.os.SystemClock
import android.util.Log
import java.util.Locale

object PerfTrace {

    private const val TAG = "PerfTrace"
    private const val ENABLED = true

    fun <T> measure(label: String, block: () -> T): T {
        if (!ENABLED) {
            return block()
        }
        val startNanos = SystemClock.elapsedRealtimeNanos()
        return try {
            block()
        } finally {
            val durationMs = (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000.0
            Log.i(TAG, "$label took ${"%.2f".format(Locale.US, durationMs)} ms")
        }
    }

    fun mark(message: String) {
        if (!ENABLED) {
            return
        }
        Log.i(TAG, message)
    }
}
