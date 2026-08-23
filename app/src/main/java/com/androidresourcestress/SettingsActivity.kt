package com.androidresourcestress

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Switch

class SettingsActivity : Activity() {
    private lateinit var preferences: AppPreferences
    private lateinit var historyStore: SessionHistoryStore
    private var binding = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = AppPreferences(this)
        historyStore = SessionHistoryStore(this)
        val content = PageUi.content(this, "SETTINGS")
        val current = preferences.loadConfiguration()

        content.addView(PageUi.secondary(this, "Default preset"))
        val presetValues = arrayOf(StressPreset.BALANCED, StressPreset.HIGH, StressPreset.EXTREME)
        content.addView(spinner(presetValues.map { it.name }, presetValues.indexOf(current.preset).coerceAtLeast(2)) { position ->
            preferences.setDefaultPreset(presetValues[position])
        }, PageUi.matchWrap())

        content.addView(PageUi.secondary(this, "Default duration"))
        val durations = StressDuration.entries.toTypedArray()
        content.addView(spinner(durations.map { it.displayLabel }, durations.indexOf(current.duration)) { position ->
            preferences.setDefaultDuration(durations[position])
        }, PageUi.matchWrap())

        content.addView(Switch(this).apply {
            text = "Keep screen on while running"
            isChecked = preferences.keepScreenOnWhileRunning
            setTextColor(getColor(R.color.text_primary))
            setOnCheckedChangeListener { _, checked -> preferences.keepScreenOnWhileRunning = checked }
        }, PageUi.matchWrap())

        content.addView(PageUi.secondary(this, "History limit (20–50)"))
        val limits = arrayOf(20, 30, 40, 50)
        content.addView(spinner(limits.map(Int::toString), limits.indexOf(preferences.historyLimit).coerceAtLeast(0)) { position ->
            preferences.historyLimit = limits[position]
            historyStore.trim(limits[position])
        }, PageUi.matchWrap())

        content.addView(Switch(this).apply {
            text = "Confirm before enabling storage stress"
            isChecked = preferences.confirmStorageStress
            setTextColor(getColor(R.color.text_primary))
            setOnCheckedChangeListener { _, checked -> preferences.confirmStorageStress = checked }
        }, PageUi.matchWrap())

        content.addView(PageUi.body(this, "Thermal protection: ALWAYS ACTIVE\nAutomatic stop at SEVERE or higher."))
        content.addView(PageUi.button(this, "CLEAR SESSION HISTORY") {
            AlertDialog.Builder(this)
                .setTitle("Clear history?")
                .setMessage("All saved stress session results will be deleted.")
                .setPositiveButton("CLEAR") { _, _ -> historyStore.clear() }
                .setNegativeButton("CANCEL", null)
                .show()
        }, PageUi.matchWrap())
        content.addView(PageUi.secondary(this, buildString {
            append("Version ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
            append("Commit: ").append(BuildConfig.GIT_COMMIT).append('\n')
            append("Tag: ").append(BuildConfig.GIT_TAG).append('\n')
            append("Repository: git@github.com:yunfei00/android-resource-stress.git")
        }))
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
