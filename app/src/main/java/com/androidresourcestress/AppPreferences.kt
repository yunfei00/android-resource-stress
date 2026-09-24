package com.androidresourcestress

import android.content.Context

class AppPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    var keepScreenOnWhileRunning: Boolean
        get() = preferences.getBoolean(KEY_KEEP_SCREEN_ON, true)
        set(value) = preferences.edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()

    var historyLimit: Int
        get() = preferences.getInt(KEY_HISTORY_LIMIT, DEFAULT_HISTORY_LIMIT)
            .coerceIn(MIN_HISTORY_LIMIT, MAX_HISTORY_LIMIT)
        set(value) = preferences.edit()
            .putInt(KEY_HISTORY_LIMIT, value.coerceIn(MIN_HISTORY_LIMIT, MAX_HISTORY_LIMIT))
            .apply()

    var confirmStorageStress: Boolean
        get() = preferences.getBoolean(KEY_CONFIRM_STORAGE, true)
        set(value) = preferences.edit().putBoolean(KEY_CONFIRM_STORAGE, value).apply()

    var language: AppLanguage
        get() = enumPreference(KEY_LANGUAGE, AppLanguage.SYSTEM)
        set(value) = preferences.edit().putString(KEY_LANGUAGE, value.name).apply()

    fun loadConfiguration(): CombinedStressConfiguration {
        val preset = enumPreference(KEY_PRESET, StressPreset.EXTREME)
        val fallback = PresetConfigurations.create(preset)
        return CombinedStressConfiguration(
            preset = preset,
            cpuEnabled = preferences.getBoolean(KEY_CPU_ENABLED, fallback.cpuEnabled),
            gpuEnabled = preferences.getBoolean(KEY_GPU_ENABLED, fallback.gpuEnabled),
            memoryEnabled = preferences.getBoolean(KEY_MEMORY_ENABLED, fallback.memoryEnabled),
            storageEnabled = preferences.getBoolean(KEY_STORAGE_ENABLED, false),
            cpuTargetPercent = preferences.getInt(
                KEY_CPU_TARGET,
                fallback.cpuTargetPercent,
            ),
            gpuTargetPercent = preferences.getInt(
                KEY_GPU_TARGET,
                fallback.gpuTargetPercent,
            ),
            memoryTarget = enumPreference(KEY_MEMORY_TARGET, fallback.memoryTarget),
            storageMode = enumPreference(KEY_STORAGE_MODE, StorageMode.MIXED),
            storageLevel = enumPreference(KEY_STORAGE_LEVEL, StorageLevel.LOW),
            gpuMode = enumPreference(KEY_GPU_MODE, GpuMode.COMPUTE),
            duration = enumPreference(KEY_DURATION, StressDuration.MINUTES_5),
            screenMode = enumPreference(KEY_SCREEN_MODE, ScreenMode.ON),
            gpu3dLevel = enumPreference(KEY_GPU3D_LEVEL, Gpu3dStressLevel.MEDIUM),
            gpu3dRunMode = enumPreference(KEY_GPU3D_RUN_MODE, Gpu3dRunMode.FIXED),
            gpu3dStepDurationSeconds = preferences.getInt(KEY_GPU3D_STEP_SECONDS, 20)
                .coerceIn(Gpu3dAutoDuration.MIN_SECONDS, Gpu3dAutoDuration.MAX_SECONDS),
        )
    }

    fun saveConfiguration(configuration: CombinedStressConfiguration) {
        preferences.edit()
            .putString(KEY_PRESET, configuration.preset.name)
            .putBoolean(KEY_CPU_ENABLED, configuration.cpuEnabled)
            .putBoolean(KEY_GPU_ENABLED, configuration.gpuEnabled)
            .putBoolean(KEY_MEMORY_ENABLED, configuration.memoryEnabled)
            .putBoolean(KEY_STORAGE_ENABLED, configuration.storageEnabled)
            .putInt(KEY_CPU_TARGET, configuration.cpuTargetPercent)
            .putInt(KEY_GPU_TARGET, configuration.gpuTargetPercent)
            .putString(KEY_MEMORY_TARGET, configuration.memoryTarget.name)
            .putString(KEY_STORAGE_MODE, configuration.storageMode.name)
            .putString(KEY_STORAGE_LEVEL, configuration.storageLevel.name)
            .putString(KEY_GPU_MODE, configuration.gpuMode.name)
            .putString(KEY_DURATION, configuration.duration.name)
            .putString(KEY_SCREEN_MODE, configuration.screenMode.name)
            .putString(KEY_GPU3D_LEVEL, configuration.gpu3dLevel.name)
            .putString(KEY_GPU3D_RUN_MODE, configuration.gpu3dRunMode.name)
            .putInt(KEY_GPU3D_STEP_SECONDS, configuration.gpu3dStepDurationSeconds)
            .apply()
    }

    fun setDefaultPreset(preset: StressPreset) {
        val current = loadConfiguration()
        val presetConfiguration = PresetConfigurations.create(preset, current.duration)
        saveConfiguration(
            presetConfiguration.copy(
                storageMode = current.storageMode,
                storageLevel = current.storageLevel,
                gpuMode = current.gpuMode,
                screenMode = current.screenMode,
                gpu3dLevel = current.gpu3dLevel,
                gpu3dRunMode = current.gpu3dRunMode,
                gpu3dStepDurationSeconds = current.gpu3dStepDurationSeconds,
            ),
        )
    }

    fun setDefaultDuration(duration: StressDuration) {
        saveConfiguration(loadConfiguration().copy(duration = duration))
    }

    private inline fun <reified T : Enum<T>> enumPreference(key: String, fallback: T): T =
        runCatching {
            enumValueOf<T>(preferences.getString(key, fallback.name) ?: fallback.name)
        }.getOrDefault(fallback)

    companion object {
        private const val PREFERENCES_NAME = "android_resource_stress_settings"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_HISTORY_LIMIT = "history_limit"
        private const val KEY_CONFIRM_STORAGE = "confirm_storage"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_PRESET = "preset"
        private const val KEY_CPU_ENABLED = "cpu_enabled"
        private const val KEY_GPU_ENABLED = "gpu_enabled"
        private const val KEY_MEMORY_ENABLED = "memory_enabled"
        private const val KEY_STORAGE_ENABLED = "storage_enabled"
        private const val KEY_CPU_TARGET = "cpu_target"
        private const val KEY_GPU_TARGET = "gpu_target"
        private const val KEY_MEMORY_TARGET = "memory_target"
        private const val KEY_STORAGE_MODE = "storage_mode"
        private const val KEY_STORAGE_LEVEL = "storage_level"
        private const val KEY_GPU_MODE = "gpu_mode"
        private const val KEY_DURATION = "duration"
        private const val KEY_SCREEN_MODE = "screen_mode"
        private const val KEY_GPU3D_LEVEL = "gpu3d_level"
        private const val KEY_GPU3D_RUN_MODE = "gpu3d_run_mode"
        private const val KEY_GPU3D_STEP_SECONDS = "gpu3d_step_seconds"
        const val DEFAULT_HISTORY_LIMIT = 30
        const val MIN_HISTORY_LIMIT = 20
        const val MAX_HISTORY_LIMIT = 50
    }
}
