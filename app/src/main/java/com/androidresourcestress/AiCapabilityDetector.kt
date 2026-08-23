package com.androidresourcestress

import android.os.Build

data class AiCapabilityInfo(
    val dedicatedAccelerator: String,
    val androidNeuralNetworksApi: String,
    val vendor: String,
    val status: String,
)

object AiCapabilityDetector {
    fun detect(): AiCapabilityInfo = AiCapabilityInfo(
        dedicatedAccelerator = "Unknown",
        androidNeuralNetworksApi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            "Platform API available"
        } else {
            "Unavailable"
        },
        vendor = Build.MANUFACTURER.orEmpty().ifBlank { "Unknown" },
        status = "Not stressed in this version",
    )
}
