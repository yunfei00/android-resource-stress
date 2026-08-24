package com.androidresourcestress

import java.util.Locale
import kotlin.math.abs

object TemperatureFormatter {
    fun format(celsius: Double?): String = celsius?.takeIf { it.isFinite() }?.let {
        String.format(Locale.US, "%.1f °C", it)
    } ?: "N/A"
}

object FrequencyFormatter {
    fun format(hertz: Long?): String = hertz?.takeIf { it > 0L }?.let {
        when {
            it >= 1_000_000_000L -> String.format(Locale.US, "%.2f GHz", it / 1_000_000_000.0)
            it >= 1_000_000L -> String.format(Locale.US, "%.0f MHz", it / 1_000_000.0)
            it >= 1_000L -> String.format(Locale.US, "%.0f kHz", it / 1_000.0)
            else -> "$it Hz"
        }
    } ?: "Unsupported"
}

object PowerFormatter {
    fun voltage(volts: Double?): String = volts?.takeIf { it.isFinite() && it > 0.0 }?.let {
        String.format(Locale.US, "%.3f V", it)
    } ?: "Unsupported"

    fun current(amps: Double?): String = amps?.takeIf { it.isFinite() }?.let {
        String.format(Locale.US, "%+.3f A", it)
    } ?: "Unsupported"

    fun power(watts: Double?): String = watts?.takeIf { it.isFinite() && it >= 0.0 }?.let {
        String.format(Locale.US, "%.2f W", abs(it))
    } ?: "Unsupported"
}
