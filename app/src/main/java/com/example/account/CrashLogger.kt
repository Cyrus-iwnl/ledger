package com.example.account

import android.app.Application
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object CrashLogger {

    private const val TAG = "CrashLogger"
    private const val LOG_DIR = "crash_logs"
    private const val LOG_FILE = "latest_crash.txt"

    fun install(application: Application) {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                persistCrash(application, thread, throwable)
            } catch (loggingError: Throwable) {
                Log.e(TAG, "Failed to persist crash log", loggingError)
            } finally {
                previousHandler?.uncaughtException(thread, throwable)
                    ?: throw throwable
            }
        }
    }

    private fun persistCrash(application: Application, thread: Thread, throwable: Throwable) {
        val content = buildCrashReport(thread, throwable)
        writeTo(File(application.filesDir, LOG_DIR), content)
        application.getExternalFilesDir(null)?.let { externalDir ->
            writeTo(File(externalDir, LOG_DIR), content)
        }
        Log.e(TAG, content)
    }

    private fun writeTo(directory: File, content: String) {
        if (!directory.exists()) {
            directory.mkdirs()
        }
        File(directory, LOG_FILE).writeText(content)
    }

    private fun buildCrashReport(thread: Thread, throwable: Throwable): String {
        val stackTrace = StringWriter().also { writer ->
            throwable.printStackTrace(PrintWriter(writer))
        }.toString()

        return buildString {
            appendLine("timestamp=${ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}")
            appendLine("thread=${thread.name}")
            appendLine("app_id=com.example.account")
            appendLine("brand=${Build.BRAND}")
            appendLine("manufacturer=${Build.MANUFACTURER}")
            appendLine("device=${Build.DEVICE}")
            appendLine("model=${Build.MODEL}")
            appendLine("sdk_int=${Build.VERSION.SDK_INT}")
            appendLine("release=${Build.VERSION.RELEASE}")
            appendLine()
            appendLine(stackTrace.trimEnd())
        }
    }
}
