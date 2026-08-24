package com.androidresourcestress

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView

class HistoryActivity : LocalizedActivity() {
    private lateinit var content: LinearLayout
    private lateinit var store: SessionHistoryStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SessionHistoryStore(this)
        content = PageUi.content(this, getString(R.string.history_title))
    }

    override fun onResume() {
        super.onResume()
        renderHistory()
    }

    private fun renderHistory() {
        while (content.childCount > FIXED_CHILDREN) content.removeViewAt(FIXED_CHILDREN)
        val sessions = store.list()
        content.addView(PageUi.secondary(this, resources.getQuantityString(
            R.plurals.saved_sessions,
            sessions.size,
            sessions.size,
        )))
        content.addView(PageUi.button(this, getString(R.string.clear_history)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.clear_history_title)
                .setMessage(R.string.clear_history_message)
                .setPositiveButton(R.string.clear) { _, _ ->
                    store.clear()
                    renderHistory()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }, PageUi.matchWrap())
        if (sessions.isEmpty()) {
            content.addView(PageUi.body(this, getString(R.string.no_results)))
            return
        }
        sessions.forEach { session ->
            content.addView(TextView(this).apply {
                text = SessionResultFormatter.compact(this@HistoryActivity, session)
                textSize = 13f
                setTextColor(getColor(R.color.text_primary))
                setBackgroundResource(R.drawable.card_background)
                val padding = PageUi.dp(this@HistoryActivity, 14)
                setPadding(padding, padding, padding, padding)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    startActivity(
                        Intent(this@HistoryActivity, SessionDetailActivity::class.java)
                            .putExtra(SessionDetailActivity.EXTRA_START_TIME, session.startWallTimeMs),
                    )
                }
            }, PageUi.matchWrap().apply {
                topMargin = PageUi.dp(this@HistoryActivity, 10)
            })
        }
    }

    companion object {
        private const val FIXED_CHILDREN = 2
    }
}
