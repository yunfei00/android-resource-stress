package com.androidresourcestress

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Switch

class SettingsActivity : LocalizedActivity() {
    private lateinit var preferences: AppPreferences
    private lateinit var historyStore: SessionHistoryStore
    private var binding = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = AppPreferences(this)
        historyStore = SessionHistoryStore(this)
        val content = PageUi.content(this, getString(R.string.settings_title))
        val current = preferences.loadConfiguration()

        content.addView(PageUi.secondary(this, getString(R.string.language)))
        val languages = AppLanguage.entries.toTypedArray()
        val languageLabels = listOf(
            getString(R.string.language_follow_system),
            getString(R.string.language_simplified_chinese),
            getString(R.string.language_english),
        )
        content.addView(spinner(languageLabels, languages.indexOf(preferences.language)) { position ->
            val selected = languages[position]
            if (selected != preferences.language) AppLocaleController.apply(this, selected)
        }, PageUi.matchWrap())

        content.addView(PageUi.secondary(this, getString(R.string.default_preset)))
        val presetValues = arrayOf(StressPreset.BALANCED, StressPreset.HIGH, StressPreset.EXTREME)
        content.addView(spinner(presetValues.map { presetLabel(it) }, presetValues.indexOf(current.preset).coerceAtLeast(2)) { position ->
            preferences.setDefaultPreset(presetValues[position])
        }, PageUi.matchWrap())

        content.addView(PageUi.secondary(this, getString(R.string.default_duration)))
        val durations = StressDuration.entries.toTypedArray()
        content.addView(spinner(durations.map { durationLabel(it) }, durations.indexOf(current.duration)) { position ->
            preferences.setDefaultDuration(durations[position])
        }, PageUi.matchWrap())

        content.addView(Switch(this).apply {
            text = getString(R.string.keep_screen_on)
            isChecked = preferences.keepScreenOnWhileRunning
            setTextColor(getColor(R.color.text_primary))
            setOnCheckedChangeListener { _, checked -> preferences.keepScreenOnWhileRunning = checked }
        }, PageUi.matchWrap())

        content.addView(PageUi.secondary(this, getString(R.string.history_limit)))
        val limits = arrayOf(20, 30, 50)
        content.addView(spinner(limits.map(Int::toString), limits.indexOf(preferences.historyLimit).coerceAtLeast(0)) { position ->
            preferences.historyLimit = limits[position]
            historyStore.trim(limits[position])
        }, PageUi.matchWrap())

        content.addView(Switch(this).apply {
            text = getString(R.string.confirm_storage)
            isChecked = preferences.confirmStorageStress
            setTextColor(getColor(R.color.text_primary))
            setOnCheckedChangeListener { _, checked -> preferences.confirmStorageStress = checked }
        }, PageUi.matchWrap())

        content.addView(PageUi.body(this, getString(R.string.thermal_protection_policy)))
        content.addView(PageUi.button(this, getString(R.string.clear_session_history)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.clear_history_title)
                .setMessage(R.string.clear_history_message)
                .setPositiveButton(R.string.clear) { _, _ -> historyStore.clear() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }, PageUi.matchWrap())
        content.addView(PageUi.secondary(this, getString(
            R.string.app_info_format,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
            BuildConfig.GIT_COMMIT,
            BuildConfig.GIT_TAG,
        )))
        binding = false
    }

    private fun spinner(labels: List<String>, selection: Int, selected: (Int) -> Unit): Spinner =
        Spinner(this).apply {
            adapter = ArrayAdapter(
                this@SettingsActivity,
                android.R.layout.simple_spinner_item,
                labels,
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(selection.coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (!binding) selected(position)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
}
