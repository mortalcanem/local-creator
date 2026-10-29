package com.localcreator.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*

class BackgroundExportActivity : ComponentActivity() {
    private lateinit var storage: Storage
    private lateinit var jobs: ExportJobs
    private lateinit var status: TextView
    private lateinit var recording: Spinner
    private lateinit var vertical: CheckBox
    private lateinit var face: CheckBox
    private lateinit var start: Button
    private lateinit var cancel: Button
    private lateinit var history: LinearLayout
    private var files: List<java.io.File> = emptyList()
    private var loading = false
    private var notice = "Experimental: use short clips first."
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed -> notice = if (allowed) "Notifications allowed; tap Start or Retry again." else "Notifications are required for this experimental export path." }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); storage = Storage(this); jobs = ExportJobs(storage)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24); fitsSystemWindows = true }
        root.addView(TextView(this).apply { text = "Background export · experimental"; textSize = 23f })
        root.addView(TextView(this).apply { text = "Intro + recording, 1080p. Navigation should not cancel this path. Android may stop it; retry restarts rendering, not a partial MP4. Keep originals. Use short test clips first." })
        status = TextView(this); root.addView(status)
        recording = Spinner(this); root.addView(recording)
        vertical = CheckBox(this).apply { text = "Vertical 1080 × 1920 (otherwise landscape)"; isChecked = state?.getBoolean("vertical") ?: true }; root.addView(vertical)
        face = CheckBox(this).apply { text = "Offline face effects (recordings up to 2 hours)"; isChecked = state?.getBoolean("face") ?: false }; root.addView(face)
        start = Button(this).apply { text = "Start background export"; setOnClickListener { begin(null) } }; root.addView(start)
        cancel = Button(this).apply { text = "Cancel running export"; setOnClickListener { BackgroundExportService.cancel(this@BackgroundExportActivity) } }; root.addView(cancel)
        root.addView(Button(this).apply { text = "Finished videos / tested foreground exporter"; setOnClickListener { if (!loading && !BackgroundExportService.busy && !LongRecordingService.state.value.active) startActivity(Intent(this@BackgroundExportActivity, IntroExportActivity::class.java)) } })
        history = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; root.addView(ScrollView(this).apply { addView(history) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        lifecycleScope.launch { while (isActive) { if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && !loading) refresh(); delay(2_000L) } }
    }
    private fun controls() {
        val available = !loading && !BackgroundExportService.busy && !LongRecordingService.state.value.active
        recording.isEnabled = available; vertical.isEnabled = available; face.isEnabled = available; start.isEnabled = available && files.isNotEmpty()
        cancel.isEnabled = BackgroundExportService.busy
        status.text = "$notice\n${BackgroundExportService.state.value.text}"
    }
    private fun allowed(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) { permission.launch(Manifest.permission.POST_NOTIFICATIONS); return false }; return true
    }
    private fun begin(id: String?) {
        if (loading || BackgroundExportService.busy || LongRecordingService.state.value.active || !allowed()) return
        val name = files.getOrNull(recording.selectedItemPosition)?.name
        if (id == null && name == null) return
        val useVertical = vertical.isChecked; val useFace = face.isChecked
        loading = true; controls()
        lifecycleScope.launch {
            try {
                val job = withContext(Dispatchers.IO) { jobs.recoverPreviousProcess(); if (id == null) jobs.create(requireNotNull(name), useVertical, useFace) else jobs.get(id) }
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) { notice = "Job saved; return and tap Retry to start."; return@launch }
                BackgroundExportService.start(this@BackgroundExportActivity, job.id)
                notice = "Background job requested. Use Cancel to stop; Back does not cancel."
            } catch (e: CancellationException) { throw e } catch (e: Exception) { notice = "Could not start: ${e.message}. A queued job may be retried below." }
            finally { loading = false; controls() }
        }
    }
    private suspend fun refresh() {
        try {
            val selected = files.getOrNull(recording.selectedItemPosition)?.name
            val data = withContext(Dispatchers.IO) { if (!BackgroundExportService.busy) jobs.recoverPreviousProcess(); Pair(storage.list(), jobs.list()) }
            if (loading) return
            files = data.first
            recording.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, files.map { it.name })
            val index = files.indexOfFirst { it.name == selected }; if (index >= 0) recording.setSelection(index)
            history.removeAllViews()
            for (job in data.second) {
                history.addView(TextView(this).apply { text = "${job.id.take(8)} · ${job.phase} · ${job.percent}%\n${job.message}" })
                if (!BackgroundExportService.busy && !LongRecordingService.state.value.active) {
                    if (job.phase in setOf(ExportJobs.Phase.QUEUED, ExportJobs.Phase.INTERRUPTED, ExportJobs.Phase.FAILED, ExportJobs.Phase.CANCELED)) history.addView(Button(this).apply { text = "Retry ${job.id.take(8)} (render restarts)"; setOnClickListener { begin(job.id) } })
                    history.addView(Button(this).apply { text = if (job.phase == ExportJobs.Phase.QUEUED) "Cancel queued job" else "Forget terminal job record (videos kept)"; setOnClickListener {
                        if (!loading && !BackgroundExportService.busy) lifecycleScope.launch {
                            loading = true; controls()
                            try { withContext(Dispatchers.IO) { if (job.phase == ExportJobs.Phase.QUEUED) jobs.cancelQueued(job.id) else jobs.forget(job.id) }; notice = "Job metadata updated; videos kept." }
                            catch (e: CancellationException) { throw e } catch (e: Exception) { notice = "Job update failed: ${e.message}" }
                            finally { loading = false; controls() }
                        }
                    } })
                }
            }; controls()
        } catch (e: CancellationException) { throw e } catch (e: Exception) { notice = "Job list unavailable: ${e.message}"; controls() }
    }
    override fun onSaveInstanceState(state: Bundle) { state.putBoolean("vertical", vertical.isChecked); state.putBoolean("face", face.isChecked); super.onSaveInstanceState(state) }
}
