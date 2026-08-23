package com.androidresourcestress

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

class SessionDetailActivity : Activity() {
    private var session: StressSessionSnapshot? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = PageUi.content(this, "SESSION DETAIL")
        val startTime = intent.getLongExtra(EXTRA_START_TIME, -1L)
        session = SessionHistoryStore(this).find(startTime)
        content.addView(PageUi.body(this, session?.let(SessionResultFormatter::detailed) ?: "Session not found."))
        content.addView(PageUi.button(this, "EXPORT & SHARE JSON") { export() }, PageUi.matchWrap())
    }

    private fun export() {
        val selected = session ?: return
        runCatching {
            val monitor = DeviceMonitor(this)
            val exporter = ResultExporter(this)
            val output = exporter.exportSession(
                selected,
                monitor.deviceInfo(),
                monitor.storageCapacity(),
                GpuInfoReader.read(),
            )
            exporter.share(output, "application/json", getString(R.string.share_result))
        }.onFailure {
            Toast.makeText(this, it.message ?: "Export failed", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val EXTRA_START_TIME = "session_start_wall_time_ms"
    }
}
