package com.localcreator.app

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import android.util.AtomicFile
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

class LongRecordingService : LifecycleService() {
    data class State(val active: Boolean = false, val message: String = "Ready", val file: String = "", val seconds: Long = 0, val bytes: Long = 0, val phase: String = "IDLE")
    companion object {
        const val STOP = "com.localcreator.app.STOP_LONG_RECORDING"
        private val mutableState = MutableStateFlow(State())
        val state = mutableState.asStateFlow()
    }
    inner class LocalBinder : Binder() { val service: LongRecordingService get() = this@LongRecordingService }
    private val binder = LocalBinder()
    private lateinit var storage: Storage
    private var recording: Recording? = null
    private var provider: ProcessCameraProvider? = null
    private var cameraPreview: Preview? = null
    private var surface: Preview.SurfaceProvider? = null
    private var monitor: Job? = null
    private var preparation: Job? = null
    private var stopping = false
    private var started = false
    private var journalFile: File? = null
    private var file: File? = null
    private var stopReason = "Stopped by user"
    private var lastJournalSeconds = -10L
    private var wake: PowerManager.WakeLock? = null
    private val journalWriter = Executors.newSingleThreadExecutor()
    override fun onCreate() {
        super.onCreate(); storage = Storage(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("long_recording", "Long video recording", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onBind(intent: Intent): IBinder { super.onBind(intent); return binder }
    fun attachPreview(value: Preview.SurfaceProvider?) { surface = value; cameraPreview?.setSurfaceProvider(value) }
    private fun notification(text: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 21, Intent(this, LongRecordingActivity::class.java), flags)
        val stop = PendingIntent.getService(this, 22, Intent(this, LongRecordingService::class.java).setAction(STOP), flags)
        return NotificationCompat.Builder(this, "long_recording").setSmallIcon(android.R.drawable.presence_video_online).setContentTitle("Local Creator recording").setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).addAction(android.R.drawable.ic_media_pause, "Stop and save", stop).build()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == STOP) { requestStop("Stopped by user"); return START_NOT_STICKY }
        if (started) return START_NOT_STICKY
        started = true
        try {
            val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            ServiceCompat.startForeground(this, 20, notification("Preparing camera…"), type)
            require(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) { "Camera and microphone permissions missing" }
            val minutes = intent?.getIntExtra("minutes", 60)?.takeIf { it in listOf(30, 60, 120) } ?: 60
            val want4k = intent?.getBooleanExtra("uhd", false) ?: false
            val id = UUID.randomUUID().toString()
            file = File(storage.recordings, "recording_session_$id.mp4")
            journalFile = File(File(storage.root, "sessions").apply { mkdirs() }, "$id.json")
            update(State(true, "Checking storage…", file!!.name, phase = "PREPARING")); journal()
            preparation = lifecycleScope.launch {
                try {
                    val estimateBitrate = if (want4k) 65_000_000L else 20_000_000L
                    val snapshot = withContext(Dispatchers.IO) { storage.snapshot() }
                    snapshot.failure(estimateBitrate * (minutes * 60L) / 8L + Storage.HEADROOM)?.let { error(it) }
                    val power = getSystemService(PowerManager::class.java)
                    wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocalCreator:Recording").apply { setReferenceCounted(false); acquire((minutes * 60L + 120L) * 1000L) }
                    prepareCamera(minutes, want4k)
                } catch(e: CancellationException) { throw e } catch(e: Exception) { fail("Could not prepare recording: ${e.message}") }
            }
        } catch(e: Exception) { fail("Could not start recording service: ${e.message}") }
        return START_NOT_STICKY
    }
    @androidx.annotation.OptIn(markerClass = [androidx.camera.core.ExperimentalCameraInfo::class])
    @Suppress("DEPRECATION")
    private fun prepareCamera(minutes: Int, want4k: Boolean) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (stopping || !mutableState.value.active) return@addListener
            try {
                val p = future.get(); provider = p
                val selector = if (p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                val supported = QualitySelector.getSupportedQualities(p.getCameraInfo(selector))
                val q = if (want4k && Quality.UHD in supported) Quality.UHD else if (Quality.FHD in supported) Quality.FHD else supported.first()
                val bitrate = if (q == Quality.UHD) 50_000_000 else 16_000_000
                val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(q)).setTargetVideoEncodingBitRate(bitrate).build()
                val capture = VideoCapture.withOutput(recorder)
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(surface) }; cameraPreview = preview
                p.bindToLifecycle(this, selector, preview, capture)
                preparation = lifecycleScope.launch {
                    try {
                        for (remaining in 3 downTo 1) { update(mutableState.value.copy(message = "Recording starts in $remaining seconds…")); delay(1_000L) }
                        if (!stopping) startCapture(capture, minutes, q == Quality.UHD)
                    } catch(e: CancellationException) { throw e } catch(e: Exception) { fail("Camera start failed: ${e.message}") }
                }
            } catch(e: Exception) { fail("Camera setup failed: ${e.message}") }
        }, ContextCompat.getMainExecutor(this))
    }
    @Suppress("MissingPermission")
    private suspend fun startCapture(capture: VideoCapture<Recorder>, minutes: Int, uhd: Boolean) {
        require(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED })
        val s = withContext(Dispatchers.IO) { storage.snapshot() }
        s.failure(Storage.HEADROOM)?.let { error(it) }
        val ceiling = minOf(s.budget - s.used, s.free - Storage.FLOOR) - Storage.HEADROOM
        require(ceiling > Storage.HEADROOM) { "Not enough storage headroom" }
        val output = FileOutputOptions.Builder(file!!).setFileSizeLimit(ceiling).setDurationLimitMillis(minutes * 60_000L).build()
        recording = capture.output.prepareRecording(this, output).withAudioEnabled().start(ContextCompat.getMainExecutor(this)) { event ->
            when(event) {
                is VideoRecordEvent.Start -> { update(mutableState.value.copy(message = "Recording ${if (uhd) "4K" else "device-selected HD"}; planned maximum $minutes min", phase = "RECORDING")); journal() }
                is VideoRecordEvent.Status -> {
                    val seconds = event.recordingStats.recordedDurationNanos / 1_000_000_000L
                    val current = mutableState.value.copy(seconds = seconds, bytes = event.recordingStats.numBytesRecorded)
                    mutableState.value = current
                    if (seconds - lastJournalSeconds >= 10) { lastJournalSeconds = seconds; journal(); updateNotification() }
                }
                is VideoRecordEvent.Finalize -> {
                    monitor?.cancel(); recording = null
                    val normal = !event.hasError() || event.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED || event.error == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED
                    val message = if (normal) "Saved. $stopReason. Verify playback in the originals library." else "Recording error ${event.error}. Recoverable file kept; verify playback."
                    update(mutableState.value.copy(active = false, message = message, bytes = file?.length() ?: 0, phase = if (normal) "FINALIZED" else "ERROR")); journal(); finishService()
                }
            }
        }
        monitor = lifecycleScope.launch {
            try {
                while (isActive) { delay(2_000L); val reason = withContext(Dispatchers.IO) { storage.snapshot().failure(Storage.HEADROOM) }; if (reason != null) { requestStop(reason); break } }
            } catch(e: CancellationException) { throw e } catch(e: Exception) { requestStop("Storage monitoring failed") }
        }
    }
    fun requestStop(reason: String) {
        if (stopping) return
        stopping = true; stopReason = reason; preparation?.cancel(); monitor?.cancel()
        if (recording != null) { update(mutableState.value.copy(message = "Saving recording…", phase = "STOPPING")); journal(); recording?.stop() }
        else { update(mutableState.value.copy(active = false, message = "Stopped before capture.", phase = "CANCELED")); journal(); finishService() }
    }
    private fun update(value: State) { mutableState.value = value; updateNotification() }
    private fun updateNotification() {
        if (mutableState.value.active) getSystemService(NotificationManager::class.java).notify(20, notification("${mutableState.value.seconds}s · ${mutableState.value.bytes / 1_000_000} MB · ${mutableState.value.message}"))
    }
    private fun journal() {
        val destination = journalFile ?: return; val s = mutableState.value
        try { journalWriter.execute {
            val atomic = AtomicFile(destination); var stream: java.io.FileOutputStream? = null
            try {
                val json = JSONObject().put("file", s.file).put("phase", s.phase).put("seconds", s.seconds).put("bytes", s.bytes).put("message", s.message).put("updatedMillis", System.currentTimeMillis())
                stream = atomic.startWrite(); stream!!.write(json.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream)
            } catch (_: Exception) { if (stream != null) atomic.failWrite(stream) }
        } } catch (_: Exception) { }
    }
    private fun fail(message: String) {
        update(mutableState.value.copy(active = false, message = message, phase = "ERROR")); journal(); finishService()
    }
    private fun finishService() { monitor?.cancel(); cameraPreview?.setSurfaceProvider(null); provider?.unbindAll(); wake?.let { if (it.isHeld) it.release() }; wake = null; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onDestroy() {
        preparation?.cancel(); monitor?.cancel(); recording?.stop()
        if (mutableState.value.active) { mutableState.value = mutableState.value.copy(active = false, message = "Service interrupted; verify saved file.", phase = "INTERRUPTED"); journal() }
        cameraPreview?.setSurfaceProvider(null); provider?.unbindAll(); wake?.let { if (it.isHeld) it.release() }; journalWriter.shutdown(); super.onDestroy()
    }
}
