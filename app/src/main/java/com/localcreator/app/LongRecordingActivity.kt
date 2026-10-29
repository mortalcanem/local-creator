package com.localcreator.app

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*
import java.io.File
import java.util.Locale

class LongRecordingActivity : ComponentActivity() {
    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var storageText: TextView
    private lateinit var duration: Spinner
    private lateinit var quality: CheckBox
    private lateinit var start: Button
    private lateinit var stop: Button
    private var bound = false
    private var service: LongRecordingService? = null
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        status.text = if (cameraAllowed()) "Permissions ready. Tap Start again." else "Camera and microphone permissions are required."
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) { service = (binder as LongRecordingService.LocalBinder).service; service?.attachPreview(preview.surfaceProvider) }
        override fun onServiceDisconnected(name: ComponentName) { service = null }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 16, 20, 16); fitsSystemWindows = true }
        root.addView(TextView(this).apply { text = "Long-session recording · experimental 0.9"; textSize = 22f })
        root.addView(TextView(this).apply { text = "Starts after a 3-second countdown. A visible service notification provides Stop. Test short sessions and background behavior before trusting long recordings. No automatic restart after process death." })
        status = TextView(this); storageText = TextView(this); root.addView(status); root.addView(storageText)
        duration = Spinner(this).apply { adapter = ArrayAdapter(this@LongRecordingActivity, android.R.layout.simple_spinner_dropdown_item, listOf("30-minute maximum", "60-minute maximum", "120-minute maximum")) }; root.addView(duration)
        quality = CheckBox(this).apply { text = "Prefer 4K when supported (more storage/heat)" }; root.addView(quality)
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }; root.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
        start = Button(this).apply { text = "Start long session"; setOnClickListener { startRecording() } }; root.addView(start)
        stop = Button(this).apply { text = "Stop and save"; setOnClickListener { service?.requestStop("Stopped by user") ?: run { if (LongRecordingService.state.value.active) startService(Intent(this@LongRecordingActivity, LongRecordingService::class.java).setAction(LongRecordingService.STOP)) } } }; root.addView(stop)
        root.addView(Button(this).apply { text = "Read latest session journal"; setOnClickListener { showJournal() } })
        root.addView(Button(this).apply { text = "Back to home (recording continues if active)"; setOnClickListener { finish() } })
        setContentView(root)
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch { LongRecordingService.state.collect { s ->
                status.text = "${s.phase}: ${s.message}\n${s.seconds / 60}m ${s.seconds % 60}s · ${String.format(Locale.US, "%.2f GB", s.bytes / 1e9)}"
                start.isEnabled = !s.active; stop.isEnabled = s.active; duration.isEnabled = !s.active; quality.isEnabled = !s.active
                if (s.active) bindRecorder()
            } }
            launch { while (isActive) {
                try { val s = withContext(Dispatchers.IO) { Storage(this@LongRecordingActivity).snapshot() }; storageText.text = String.format(Locale.US, "Managed %.2f / %.0f GB · phone free %.2f GB · 50 GB reserve", s.used / 1e9, s.budget / 1e9, s.free / 1e9) } catch(e: CancellationException) { throw e } catch(e: Exception) { storageText.text = "Storage unavailable: ${e.message}" }
                delay(5_000L)
            } }
        } }
    }
    private fun cameraAllowed() = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    private fun startRecording() {
        if (LongRecordingService.state.value.active || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val requested = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) requested.add(Manifest.permission.POST_NOTIFICATIONS)
        if (!cameraAllowed() || (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) { permissions.launch(requested.toTypedArray()); return }
        val minutes = listOf(30, 60, 120)[duration.selectedItemPosition]
        try {
            ContextCompat.startForegroundService(this, Intent(this, LongRecordingService::class.java).putExtra("minutes", minutes).putExtra("uhd", quality.isChecked))
            bindRecorder(); status.text = "Starting service…"
        } catch(e: Exception) { status.text = "Service start failed: ${e.message}" }
    }
    private fun bindRecorder() { if (!bound) bound = bindService(Intent(this, LongRecordingService::class.java), connection, BIND_AUTO_CREATE) }
    override fun onStart() { super.onStart(); if (LongRecordingService.state.value.active) bindRecorder() }
    override fun onStop() { service?.attachPreview(null); if (bound) { unbindService(connection); bound = false }; service = null; super.onStop() }
    private fun showJournal() {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                val root = Storage(this@LongRecordingActivity).root
                val latest = File(root, "sessions").listFiles().orEmpty().filter { it.extension == "json" }.maxByOrNull { it.lastModified() }
                latest?.readText() ?: "No session journal yet. A journal records status; it does not repair or recover a broken MP4."
            }
            android.app.AlertDialog.Builder(this@LongRecordingActivity).setTitle("Latest session status").setMessage(text).setPositiveButton("OK", null).show()
        }
    }
}
