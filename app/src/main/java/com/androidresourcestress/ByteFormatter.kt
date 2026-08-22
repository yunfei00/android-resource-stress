package com.androidresourcestress

import java.util.Locale

object ByteFormatter {
    private const val KIB = 1024.0
    private const val MIB = KIB * 1024.0
    private const val GIB = MIB * 1024.0

    fun formatBytes(bytes: Long): String {
        val safeBytes = bytes.coerceAtLeast(0L)
        return when {
            safeBytes < KIB -> "$safeBytes B"
            safeBytes < MIB -> formatCompact(safeBytes / KIB, "KB")
            safeBytes < GIB -> formatCompact(safeBytes / MIB, "MB")
            else -> String.format(Locale.US, "%.2f GB", safeBytes / GIB)
        }
    }

    fun formatRate(bytesPerSecond: Double): String {
        val safeRate = bytesPerSecond.coerceAtLeast(0.0)
        return when {
            safeRate < KIB -> String.format(Locale.US, "%.0f B/s", safeRate)
            safeRate < MIB -> String.format(Locale.US, "%.1f KB/s", safeRate / KIB)
            safeRate < GIB -> String.format(Locale.US, "%.1f MB/s", safeRate / MIB)
            else -> String.format(Locale.US, "%.2f GB/s", safeRate / GIB)
        }
    }

    private fun formatCompact(value: Double, unit: String): String {
        val pattern = if (value >= 100.0 || value % 1.0 == 0.0) "%.0f %s" else "%.1f %s"
        return String.format(Locale.US, pattern, value, unit)
    }
}
