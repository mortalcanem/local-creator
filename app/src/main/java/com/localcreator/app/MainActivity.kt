package com.localcreator.app

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {
    private lateinit var storage: Storage
    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var usage: TextView
    private lateinit var introLabel: TextView
    private lateinit var library: LinearLayout
    private lateinit var recordButton: Button
    private lateinit var introButton: Button
    private lateinit var cleanButton: Button
    private lateinit var qualityButton: Button
    private var capture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var monitor: Job? = null
    private var refreshJob: Job? = null
    private var busy = false
    private var stopping = false
    private var cameraPending = false
    private var pendingBackup: String? = null
    private var pickerOpen = false
    private var supported: List<Quality> = emptyList()
    private var selected: Quality? = null
    private val preferences by lazy { getSharedPreferences("camera", MODE_PRIVATE) }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasPermissions()) startCamera() else status.text = "Camera and microphone permissions are required. Tap Record to retry."
    }
    private val introPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pickerOpen = false
        if (uri != null && idle()) importIntro(uri) else { controls(); refresh() }
    }
    private val backupPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        pickerOpen = false
        val name = pendingBackup; pendingBackup = null
        if (uri != null && name != null && idle()) backup(name, uri) else { controls(); refresh() }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        storage = Storage(this)
        pendingBackup = state?.getString("pendingBackup")
        pickerOpen = state?.getBoolean("pickerOpen") ?: false
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 12, 20, 12); fitsSystemWindows = true }
        root.addView(TextView(this).apply { text = "Local Creator 0.6 · camera test"; textSize = 21f })
        usage = TextView(this).apply { textSize = 12f }
        status = TextView(this).apply { text = "Starting camera…" }
        root.addView(usage); root.addView(status)
        qualityButton = Button(this).apply { text = "Checking supported quality…"; setOnClickListener { chooseQuality() } }
        root.addView(qualityButton)
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FIT_CENTER; setBackgroundColor(android.graphics.Color.BLACK) }
        root.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
        recordButton = Button(this).apply { text = "Record"; setOnClickListener { if (recording != null) stopRecording("Saving…") else beginRecording() } }
        root.addView(recordButton)
        introLabel = TextView(this); root.addView(introLabel)
        val actions = LinearLayout(this)
        introButton = Button(this).apply { text = "Import intro"; setOnClickListener { if (idle()) { pickerOpen = true; controls(); refresh(); introPicker.launch(arrayOf("video/*")) } } }
        cleanButton = Button(this).apply { text = "Clear temp files"; setOnClickListener { confirmCleanup() } }
        actions.addView(introButton); actions.addView(cleanButton); root.addView(actions)
        library = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(library) }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(TextView(this).apply { text = "Originals are private. Uninstalling or clearing app data deletes them."; textSize = 11f })
        setContentView(root); controls(); refresh()
        if (hasPermissions()) startCamera() else requestPermissions()
    }
    private fun idle() = !busy && recording == null && !pickerOpen && !cameraPending
    private fun controls() {
        recordButton.text = if (recording != null) "Stop recording" else "Record"
        recordButton.isEnabled = !busy && !stopping && !pickerOpen && !cameraPending
        introButton.isEnabled = idle(); cleanButton.isEnabled = idle()
        qualityButton.isEnabled = idle() && supported.isNotEmpty()
    }
    private fun hasPermissions() = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    private fun requestPermissions() = permissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    private fun label(q: Quality): String = when(q) { Quality.UHD -> "4K · more storage"; Quality.FHD -> "1080p · balanced"; Quality.HD -> "720p"; Quality.SD -> "480p"; else -> "Device quality" }
    private fun chooseQuality() {
        if (!idle() || supported.isEmpty()) return
        val choices = supported.toList()
        AlertDialog.Builder(this).setTitle("Front camera recording quality").setSingleChoiceItems(choices.map { label(it) }.toTypedArray(), choices.indexOf(selected)) { dialog, index ->
            if (idle()) { selected = choices[index]; preferences.edit().putString("quality", label(choices[index])).apply(); capture = null; startCamera() }
            dialog.dismiss()
        }.setNegativeButton("Cancel", null).show()
    }
    @Suppress("DEPRECATION")
    private fun startCamera() {
        if (capture != null || cameraPending || !hasPermissions() || recording != null || busy) return
        cameraPending = true; controls(); refresh()
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                if (isDestroyed) return@addListener
                val provider = future.get()
                val front = provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                val info = provider.getCameraInfo(selector)
                supported = QualitySelector.getSupportedQualities(info)
                require(supported.isNotEmpty()) { "No recording qualities exposed by this camera" }
                val saved = preferences.getString("quality", null)
                val preferred = selected?.takeIf { it in supported } ?: supported.find { label(it) == saved } ?: Quality.FHD.takeIf { it in supported } ?: supported.first()
                val fallback = Quality.FHD.takeIf { it in supported } ?: supported.first()
                provider.unbindAll()
                fun bind(q: Quality): VideoCapture<Recorder> {
                    val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(q)).build()
                    val video = VideoCapture.withOutput(recorder)
                    val cameraPreview = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                    provider.bindToLifecycle(this, selector, cameraPreview, video)
                    return video
                }
                var chosen = preferred
                var fallbackUsed = false
                capture = try { bind(preferred) } catch (e: Exception) {
                    if (preferred == fallback) throw e
                    provider.unbindAll(); chosen = fallback; fallbackUsed = true; bind(fallback)
                }
                selected = chosen
                qualityButton.text = "${if (front) "Front" else "Rear"} camera: ${label(chosen)}"
                status.text = if (fallbackUsed) "Selected mode could not bind; using ${label(chosen)}." else "Ready. Full-frame preview; black bars are normal."
            } catch (e: Exception) { capture = null; status.text = "Camera setup failed: ${e.message}" }
            finally { cameraPending = false; controls(); refresh() }
        }, ContextCompat.getMainExecutor(this))
    }
    @Suppress("MissingPermission")
    private fun beginRecording() {
        if (!idle()) return
        if (!hasPermissions()) { requestPermissions(); return }
        val video = capture ?: run { startCamera(); return }
        busy = true; controls()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { storage.snapshot().failure(256_000_000L)?.let { error(it) } }
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
                video.targetRotation = preview.display?.rotation ?: android.view.Surface.ROTATION_0
                val file = File(storage.recordings, "recording_${UUID.randomUUID()}.mp4")
                val output = FileOutputOptions.Builder(file).setFileSizeLimit(2_000_000_000L).build()
                recording = video.output.prepareRecording(this@MainActivity, output).withAudioEnabled().start(ContextCompat.getMainExecutor(this@MainActivity)) { event ->
                    when(event) {
                        is VideoRecordEvent.Start -> status.text = "Recording ${selected?.let { label(it) }}. 2 GB file limit; storage checks active."
                        is VideoRecordEvent.Finalize -> {
                            monitor?.cancel(); monitor = null; recording = null; stopping = false
                            status.text = if (!event.hasError() || event.error == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED) "Saved. Tap Details for actual resolution and bitrate." else "Recording error ${event.error}; recoverable file kept."
                            controls(); refresh()
                        }
                    }
                }
                monitor = lifecycleScope.launch {
                    while (isActive) {
                        delay(2_000L)
                        try {
                            val reason = withContext(Dispatchers.IO) { storage.snapshot().failure(Storage.HEADROOM) }
                            if (reason != null) { stopRecording(reason); break }; refresh()
                        } catch (e: CancellationException) { throw e } catch (e: Exception) { stopRecording("Storage monitor failed; stopping safely."); break }
                    }
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { status.text = "Recording could not start: ${e.message}" }
            finally { busy = false; controls(); refresh() }
        }
    }
    private fun stopRecording(message: String) { if (recording == null || stopping) return; stopping = true; status.text = message; controls(); recording?.stop() }
    private fun refresh() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            try {
                val (s, videos, introSize) = withContext(Dispatchers.IO) { Triple(storage.snapshot(), storage.list(), File(storage.intros, "intro.media").length()) }
                usage.text = "Managed ${gb(s.used)} / 10 GB · free ${gb(s.free)} · 50 GB reserve"
                introLabel.text = if (introSize > 0) "Intro ${gb(introSize)} imported; assembly not implemented yet." else "No intro imported"
                library.removeAllViews()
                library.addView(TextView(this@MainActivity).apply { text = "Saved recordings (${videos.size})"; textSize = 18f })
                videos.forEach { file ->
                    library.addView(TextView(this@MainActivity).apply { text = "${DateFormat.getDateTimeInstance().format(Date(file.lastModified()))} · ${gb(file.length())}" })
                    val buttons = LinearLayout(this@MainActivity)
                    fun button(text: String, action: () -> Unit) = Button(this@MainActivity).apply { this.text = text; isEnabled = idle(); setOnClickListener { if (idle()) action() } }
                    buttons.addView(button("Play") { play(file) })
                    buttons.addView(button("Details") { details(file) })
                    library.addView(buttons)
                    val management = LinearLayout(this@MainActivity)
                    management.addView(button("Save copy") { pendingBackup = file.name; pickerOpen = true; controls(); refresh(); backupPicker.launch("LocalCreator_${file.name}") })
                    management.addView(button("Delete") {
                        AlertDialog.Builder(this@MainActivity).setTitle("Permanently delete original?").setMessage("This cannot be undone.").setNegativeButton("Keep", null).setPositiveButton("Delete") { _, _ -> operation("Deleting…", "Recording deleted.") { require(file.delete()) { "Delete failed" } } }.show()
                    })
                    library.addView(management)
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { usage.text = "Storage check failed: ${e.message}" }
        }
    }
    private fun play(file: File) {
        try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(FileProvider.getUriForFile(this, "$packageName.files", file), "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) } catch(e: Exception) { status.text = "Unable to play: ${e.message}" }
    }
    private fun details(file: File) {
        operation("Reading actual video metadata…", "Video details opened.") {
            val reader = MediaMetadataRetriever()
            val text = try {
                reader.setDataSource(file.path)
                val width = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: "unknown"
                val height = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: "unknown"
                val rotation = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) ?: "unknown"
                val bitrate = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
                val duration = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                "Stored frame: ${width} × ${height} pixels\nRotation: ${rotation} degrees\nContainer bitrate: ${bitrate?.let { String.format(Locale.US, "%.1f Mbps", it / 1e6) } ?: "unknown"}\nDuration: ${duration?.let { String.format(Locale.US, "%.1f seconds", it / 1000.0) } ?: "unknown"}\nFile size: ${gb(file.length())}\nBitrate is container metadata, not a sharpness score."
            } finally { reader.release() }
            withContext(Dispatchers.Main) {
                if (!isFinishing && !isDestroyed && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) AlertDialog.Builder(this@MainActivity).setTitle("Actual saved video").setMessage(text).setPositiveButton("OK", null).show()
            }
        }
    }
    private fun operation(message: String, success: String, block: suspend () -> Unit) {
        if (!idle()) return
        busy = true; controls(); refresh(); status.text = message
        lifecycleScope.launch {
            try { withContext(Dispatchers.IO) { block() }; status.text = success }
            catch(e: CancellationException) { throw e } catch(e: Exception) { status.text = "Operation failed: ${e.message}. An interrupted backup may leave a partial destination; original kept." }
            finally { busy = false; controls(); refresh() }
        }
    }
    private fun confirmCleanup() {
        if (!idle()) return
        AlertDialog.Builder(this).setTitle("Clear temporary files?").setMessage("Originals and intro are kept.").setNegativeButton("Cancel", null).setPositiveButton("Clear") { _, _ -> operation("Clearing…", "Temporary files cleared.") { require(storage.working.listFiles().orEmpty().map { it.deleteRecursively() }.all { it }) } }.show()
    }
    private fun importIntro(uri: Uri) {
        operation("Importing intro…", "Intro imported locally; not yet merged into recordings.") {
            val partial = File(storage.intros, "intro.partial")
            try {
                partial.delete()
                val input = contentResolver.openInputStream(uri) ?: error("Cannot read intro")
                input.use { source -> partial.outputStream().use { target ->
                    val buffer = ByteArray(1_048_576); var total = 0L
                    while(true) {
                        currentCoroutineContext().ensureActive(); val n = source.read(buffer); if(n < 0) break
                        total += n; require(total <= 1_000_000_000L) { "Intro exceeds 1 GB" }
                        storage.snapshot().failure(n.toLong() + 2_000_000L)?.let { error(it) }; target.write(buffer, 0, n)
                    }
                    require(total > 0) { "Intro empty" }
                } }
                java.nio.file.Files.move(partial.toPath(), File(storage.intros, "intro.media").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } finally { partial.delete() }
        }
    }
    private fun backup(name: String, uri: Uri) {
        operation("Copying original; keep this screen open…", "Copy completed. Original kept; open the copy to verify it.") {
            val source = storage.resolve(name); val expected = source.length()
            require(source.isFile && expected > 0) { "Original missing or empty" }
            require(expected <= (storage.snapshot().free - Storage.FLOOR - Storage.HEADROOM).coerceAtLeast(0)) { "Not enough free space above reserve" }
            var copied = 0L; var checked = 0L
            val output = contentResolver.openOutputStream(uri, "w") ?: error("Cannot open destination")
            source.inputStream().use { input -> output.use { target ->
                val buffer = ByteArray(1_048_576)
                while(true) {
                    currentCoroutineContext().ensureActive(); val n = input.read(buffer); if(n < 0) break
                    if(copied == 0L || copied - checked >= 16_000_000L) { require(storage.snapshot().free > Storage.FLOOR + Storage.HEADROOM) { "Free-space reserve reached" }; checked = copied }
                    target.write(buffer, 0, n); copied += n
                }; target.flush()
            } }
            require(copied == expected && source.length() == expected) { "Source changed during copy" }
        }
    }
    private fun gb(bytes: Long) = String.format(Locale.US, "%.2f GB", bytes / 1e9)
    override fun onSaveInstanceState(state: Bundle) { pendingBackup?.let { state.putString("pendingBackup", it) }; state.putBoolean("pickerOpen", pickerOpen); super.onSaveInstanceState(state) }
    override fun onStop() { stopRecording("Saving because app left the screen…"); super.onStop() }
}
