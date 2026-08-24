package com.androidresourcestress

import android.os.Bundle
import android.widget.Toast

class SessionDetailActivity : LocalizedActivity() {
    private var session: StressSessionSnapshot? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = PageUi.content(this, getString(R.string.session_detail_title))
        val startTime = intent.getLongExtra(EXTRA_START_TIME, -1L)
        session = SessionHistoryStore(this).find(startTime)
        content.addView(PageUi.body(this, session?.let { SessionResultFormatter.detailed(this, it) }
            ?: getString(R.string.session_not_found)))
        content.addView(PageUi.button(this, getString(R.string.export_share_json)) { export() }, PageUi.matchWrap())
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
            Toast.makeText(this, it.message ?: getString(R.string.export_failed), Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val EXTRA_START_TIME = "session_start_wall_time_ms"
    }
}
