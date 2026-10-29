package com.localcreator.app

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class BackgroundExportService : Service() {
    data class Status(val id: String = "", val active: Boolean = false, val text: String = "No background export running")
    companion object {
        private val mutable = MutableStateFlow(Status())
        val state = mutable.asStateFlow()
        val busy: Boolean get() = state.value.active
        private const val CHANNEL = "background_export"
        private const val NOTICE = 402
        private const val CANCEL = "com.localcreator.app.CANCEL_EXPORT"
        /** Call on main, from visible UI only. Reserves the UI guard before service starts. */
        fun start(context: Context, id: String) {
            check(!busy && !LongRecordingService.state.value.active) { "Another recording/export is active" }
            require(id.matches(Regex("[0-9a-f-]{36}")))
            check(Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) { "Allow notifications before starting background export" }
            mutable.value = Status(id, true, "Starting background export…")
            try { ContextCompat.startForegroundService(context, Intent(context, BackgroundExportService::class.java).putExtra("job", id)) }
            catch (e: Exception) { mutable.value = Status(id, false, "Service start failed: ${e.message}"); throw e }
        }
        fun cancel(context: Context) { if (busy) context.startService(Intent(context, BackgroundExportService::class.java).setAction(CANCEL).putExtra("job", state.value.id)) }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var work: kotlinx.coroutines.Job? = null
    private var engine: Transformer? = null
    private var jobId = ""
    private var reason = "Canceled"
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) {
            if (jobId.isNotEmpty() && intent.getStringExtra("job") == jobId) { reason = "Canceled by user"; work?.cancel(); stopSelf() }
            else if (work == null) stopSelf()
            return START_NOT_STICKY
        }
        if (work != null) return START_NOT_STICKY
        jobId = intent?.getStringExtra("job") ?: ""
        if (jobId.isEmpty() || !busy || state.value.id != jobId) { stopSelf(); return START_NOT_STICKY }
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Video export", NotificationManager.IMPORTANCE_LOW))
            val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTICE, notification("Preparing export…"), type) else startForeground(NOTICE, notification("Preparing export…"))
        } catch (e: Exception) { mutable.value = Status(jobId, false, "Service could not start: ${e.message}"); stopSelf(); return START_NOT_STICKY }
        work = scope.launch { process() }
        return START_NOT_STICKY
    }
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 402, Intent(this, BackgroundExportActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 403, Intent(this, BackgroundExportService::class.java).setAction(CANCEL).putExtra("job", jobId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("Local Creator export").setContentText(text).setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true).addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancel).build()
    }
    private fun report(text: String) {
        mutable.value = Status(jobId, true, text)
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            try { getSystemService(NotificationManager::class.java).notify(NOTICE, notification(text)) } catch (_: SecurityException) { }
        }
    }
    private suspend fun process() {
        val storage = Storage(this); val jobs = ExportJobs(storage)
        var record: ExportJobs.Job? = null
        var output: File? = null
        var wake: PowerManager.WakeLock? = null
        var success = false
        var finalText = "Export stopped"
        try {
            check(!LongRecordingService.state.value.active) { "Stop the recording first" }
            withTimeout(21_000_000L) {
                wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:export").apply { acquire(21_600_000L) }
                record = withContext(Dispatchers.IO) { jobs.recoverPreviousProcess(); jobs.claim(jobId) }
                var job = requireNotNull(record)
                val recording = storage.resolve(job.recording); val intro = File(storage.intros, "intro.media")
                val exports = File(storage.root, "exports"); val finalFile = File(exports, job.outputName)
                withContext(Dispatchers.IO) {
                    check(exports.isDirectory || exports.mkdirs())
                    require(!finalFile.exists()) { "Finished output already exists; review it in finished videos. It will not be overwritten." }
                    val ms = Math.addExact(duration(intro), duration(recording))
                    val estimate = Math.addExact(Math.multiplyExact(ms / 1000 + 1, 2_000_000L), Storage.HEADROOM)
                    storage.snapshot().failure(estimate)?.let { error(it) }; jobs.checkSources(job)
                }
                var savedAt = SystemClock.elapsedRealtime()
                val motion = if (job.faceEffects) withContext(Dispatchers.IO) {
                    FaceMotion.analyze(recording, storage) { percent ->
                        withContext(Dispatchers.Main) { report("Analyzing face locally: $percent%") }
                        if (SystemClock.elapsedRealtime() - savedAt >= 10_000L) { jobs.update(job.id, job.token, ExportJobs.Phase.ANALYZING, percent); savedAt = SystemClock.elapsedRealtime() }
                    }
                } else null
                currentCoroutineContext().ensureActive()
                val limit = withContext(Dispatchers.IO) {
                    jobs.checkSources(job); val s = storage.snapshot(); s.failure(Storage.HEADROOM)?.let { error(it) }
                    minOf(s.budget - s.used, s.free - Storage.FLOOR) - Storage.HEADROOM
                }
                require(limit > 0) { "Not enough output space" }
                val partial = File(storage.working, "background_${job.id}_${job.token}.partial.mp4"); output = partial
                job = withContext(Dispatchers.IO) { jobs.update(job.id, job.token, ExportJobs.Phase.RENDERING, 0) }; record = job
                val width = if (job.vertical) 1080 else 1920; val height = if (job.vertical) 1920 else 1080
                val composition = Composition.Builder(EditedMediaItemSequence(edited(intro, width, height), edited(recording, width, height, motion))).experimentalSetForceAudioTrack(true).build()
                report("Rendering in background…")
                render(composition, partial, limit, storage, jobs, job)
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable + Dispatchers.IO) {
                    jobs.update(job.id, job.token, ExportJobs.Phase.FINALIZING, 100)
                    jobs.checkSources(job); require(partial.length() in 1..limit) { "Output missing or exceeds available-space allowance" }
                    storage.snapshot().failure(0)?.let { error(it) }
                    java.nio.file.Files.move(partial.toPath(), finalFile.toPath())
                    jobs.update(job.id, job.token, ExportJobs.Phase.COMPLETED, 100)
                    success = true
                }
                finalText = "Export complete. Open finished videos and verify framing/audio. Sources kept."
            }
        } catch (e: CancellationException) {
            finalText = if (e is TimeoutCancellationException) "Export time limit reached; retry starts rendering again" else "$reason. Sources/checkpoints kept; rendering must restart."
        } catch (e: Exception) { finalText = "Export failed: ${e.message}. Sources kept; review any finished output before retrying." }
        finally {
            try { engine?.cancel() } catch (_: Exception) { }; engine = null
            withContext(NonCancellable + Dispatchers.IO) {
                val job = record
                if (!success && job != null) {
                    try { jobs.update(job.id, job.token, ExportJobs.Phase.INTERRUPTED, 0, finalText.take(512)) }
                    catch (e: Exception) { finalText += " Could not persist job status: ${e.message}" }
                }
                output?.let { if (it.exists()) it.delete() }
            }
            if (wake?.isHeld == true) wake?.release()
            if (state.value.id == jobId) mutable.value = Status(jobId, false, finalText)
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
    }
    private suspend fun render(composition: Composition, output: File, limit: Long, storage: Storage, jobs: ExportJobs, job: ExportJobs.Job) = coroutineScope {
        var monitor: kotlinx.coroutines.Job? = null
        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                val transformer = Transformer.Builder(this@BackgroundExportService).setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC).addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) { if (continuation.isActive) continuation.resume(Unit) }
                    override fun onError(composition: Composition, result: ExportResult, exception: ExportException) { if (continuation.isActive) continuation.resumeWithException(exception) }
                }).build()
                engine = transformer
                transformer.start(composition, output.path)
                monitor = launch {
                    val progress = ProgressHolder(); var savedAt = SystemClock.elapsedRealtime()
                    while (isActive) {
                        delay(2_000L)
                        withContext(Dispatchers.IO) { jobs.checkSources(job); storage.snapshot().failure(Storage.HEADROOM)?.let { error(it) }; require(output.length() < limit) { "Output allowance reached" } }
                        val percent = if (transformer.getProgress(progress) == Transformer.PROGRESS_STATE_AVAILABLE) progress.progress.coerceIn(0, 100) else 0
                        report("Rendering: $percent% — you may leave this screen")
                        if (SystemClock.elapsedRealtime() - savedAt >= 10_000L) { withContext(Dispatchers.IO) { jobs.update(job.id, job.token, ExportJobs.Phase.RENDERING, percent) }; savedAt = SystemClock.elapsedRealtime() }
                    }
                }
            }
        } finally { monitor?.cancel(); try { engine?.cancel() } finally { engine = null } }
    }
    private fun duration(file: File): Long {
        val reader = MediaMetadataRetriever()
        try { reader.setDataSource(file.path); require(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"); return reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 } ?: error("Unreadable duration") } finally { reader.release() }
    }
    private fun edited(file: File, width: Int, height: Int, motion: FaceMotion? = null): EditedMediaItem {
        val mixer = ChannelMixingAudioProcessor().apply { putChannelMixingMatrix(ChannelMixingMatrix.create(1, 1)); putChannelMixingMatrix(ChannelMixingMatrix.create(2, 1)) }
        val resampler = SonicAudioProcessor().apply { setOutputSampleRateHz(48_000) }
        val effects = mutableListOf<Effect>(); if (motion != null) effects.add(motion)
        effects.add(Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT))
        return EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(file))).setEffects(Effects(listOf(mixer, resampler), effects)).build()
    }
    override fun onTimeout(startId: Int, fgsType: Int) { reason = "Android background-processing time allowance reached"; work?.cancel(); stopSelf() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
