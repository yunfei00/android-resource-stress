package com.androidresourcestress

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DiagnosticLog(context: Context) {
    private val logFile = File(context.filesDir, LOG_FILE_NAME)
    private val exportDirectory = File(context.cacheDir, EXPORT_DIRECTORY)
    private val lock = Any()

    fun record(message: String) {
        val line = "${timestamp()} $message\n"
        synchronized(lock) {
            runCatching {
                if (logFile.length() > MAX_LOG_BYTES) {
                    val retained = logFile.readText(Charsets.UTF_8).takeLast(RETAINED_LOG_CHARS)
                    logFile.writeText(retained, Charsets.UTF_8)
                }
                logFile.appendText(line, Charsets.UTF_8)
            }
        }
    }

    fun export(): File = synchronized(lock) {
        exportDirectory.mkdirs()
        val output = File(exportDirectory, "diagnostic-${fileTimestamp()}.log")
        if (logFile.exists()) {
            logFile.copyTo(output, overwrite = true)
        } else {
            output.writeText("${timestamp()} No diagnostic events recorded.\n", Charsets.UTF_8)
        }
        output
    }

    fun readText(): String = synchronized(lock) {
        if (logFile.exists()) logFile.readText(Charsets.UTF_8) else ""
    }

    private fun timestamp(): String = SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        Locale.US,
    ).format(Date())

    private fun fileTimestamp(): String = SimpleDateFormat(
        "yyyyMMdd-HHmmss",
        Locale.US,
    ).format(Date())

    companion object {
        private const val LOG_FILE_NAME = "diagnostic-events.log"
        private const val EXPORT_DIRECTORY = "exports"
        private const val MAX_LOG_BYTES = 512L * 1024L
        private const val RETAINED_LOG_CHARS = 256 * 1024
    }
}
