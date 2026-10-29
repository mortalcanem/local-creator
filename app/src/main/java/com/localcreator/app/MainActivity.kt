package com.localcreator.app

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
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
    private var capture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var monitor: Job? = null
    private var refreshJob: Job? = null
    private var busy = false
    private var stopping = false
    private var cameraPending = false
    private var pendingBackup: String? = null
    private var pickerOpen = false
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasPermissions()) startCamera() else status.text = "Camera and microphone permissions are required. Tap Record to retry."
    }
    private val introPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pickerOpen = false
        if (uri != null && !busy && recording == null) importIntro(uri) else controls()
    }
    private val backupPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        pickerOpen = false
        val name = pendingBackup
        pendingBackup = null
        if (uri != null && name != null && !busy && recording == null) backup(name, uri) else controls()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storage = Storage(this)
        pendingBackup = savedInstanceState?.getString("pendingBackup")
        pickerOpen = savedInstanceState?.getBoolean("pickerOpen") ?: false
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 12, 20, 12); fitsSystemWindows = true }
        root.addView(TextView(this).apply { text = "Local Creator · development build"; textSize = 21f })
        usage = TextView(this).apply { text = "Checking storage…"; textSize = 12f }
        status = TextView(this).apply { text = "Starting camera…" }
        root.addView(usage); root.addView(status)
        preview = PreviewView(this)
        root.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
        recordButton = Button(this).apply { text = "Record"; setOnClickListener { if (recording != null) stopRecording("Saving…") else beginRecording() } }
        root.addView(recordButton)
        introLabel = TextView(this)
        root.addView(introLabel)
        val actions = LinearLayout(this)
        introButton = Button(this).apply { text = "Import intro"; setOnClickListener {
            if (idle()) { pickerOpen = true; controls(); introPicker.launch(arrayOf("video/*")) }
        } }
        cleanButton = Button(this).apply { text = "Clear temp files"; setOnClickListener { confirmCleanup() } }
        actions.addView(introButton); actions.addView(cleanButton); root.addView(actions)
        library = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(library) }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(TextView(this).apply { text = "Private originals are deleted if app data is cleared or the app is uninstalled. Save copies first."; textSize = 11f })
        setContentView(root)
        controls(); refresh()
        if (hasPermissions()) startCamera() else permissionRequest.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }
    private fun idle() = !busy && recording == null && !pickerOpen
    private fun controls() {
        recordButton.text = if (recording != null) "Stop recording" else "Record"
        recordButton.isEnabled = !busy && !stopping && !pickerOpen
        introButton.isEnabled = idle(); cleanButton.isEnabled = idle()
    }
    private fun hasPermissions() = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    private fun startCamera() {
        if (capture != null || cameraPending || !hasPermissions()) return
        cameraPending = true
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                if (isDestroyed) return@addListener
                val provider = future.get()
                val camera = if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD))).build()
                val video = VideoCapture.withOutput(recorder)
                val cameraPreview = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                provider.unbindAll(); provider.bindToLifecycle(this, camera, cameraPreview, video)
                capture = video
                status.text = "Ready. Intro assembly and AI editing are not implemented yet."
            } catch (e: Exception) { status.text = "Camera unavailable: ${e.message}" }
            finally { cameraPending = false }
        }, ContextCompat.getMainExecutor(this))
    }
    @Suppress("MissingPermission")
    private fun beginRecording() {
        if (!idle()) return
        if (!hasPermissions()) { permissionRequest.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)); return }
        val video = capture ?: run { startCamera(); return }
        busy = true; controls()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { storage.snapshot().failure(256_000_000L)?.let { error(it) } }
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
                val file = File(storage.recordings, "recording_${UUID.randomUUID()}.mp4")
                val output = FileOutputOptions.Builder(file).setFileSizeLimit(2_000_000_000L).build()
                recording = video.output.prepareRecording(this@MainActivity, output).withAudioEnabled().start(ContextCompat.getMainExecutor(this@MainActivity)) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> status.text = "Recording. Stops at 2 GB or earlier if space runs low."
                        is VideoRecordEvent.Finalize -> {
                            monitor?.cancel(); monitor = null; recording = null; stopping = false
                            status.text = if (!event.hasError() || event.error == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED) "Recording saved." else "Recording error ${event.error}; recoverable file kept for review."
                            controls(); refresh()
                        }
                    }
                }
                monitor = lifecycleScope.launch {
                    while (isActive) {
                        delay(2_000L)
                        try {
                            val reason = withContext(Dispatchers.IO) { storage.snapshot().failure(Storage.HEADROOM) }
                            if (reason != null) { stopRecording(reason); break }
                            refresh()
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { stopRecording("Storage monitoring failed; stopping safely."); break }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { status.text = "Recording could not start: ${e.message}" }
            finally { busy = false; controls(); refresh() }
        }
    }
    private fun stopRecording(message: String) {
        if (recording == null || stopping) return
        stopping = true; status.text = message; controls(); recording?.stop()
    }
    private fun refresh() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            try {
                val (snapshot, videos, introSize) = withContext(Dispatchers.IO) { Triple(storage.snapshot(), storage.list(), File(storage.intros, "intro.media").length()) }
                usage.text = "Managed files ${gb(snapshot.used)} / 10 GB · free ${gb(snapshot.free)} · 50 GB reserve"
                introLabel.text = if (introSize > 0) "Intro imported (${gb(introSize)}); not yet attached to recordings." else "No intro imported"
                library.removeAllViews()
                library.addView(TextView(this@MainActivity).apply { text = "Saved recordings (${videos.size})"; textSize = 18f })
                videos.forEach { file ->
                    library.addView(TextView(this@MainActivity).apply { text = "${DateFormat.getDateTimeInstance().format(Date(file.lastModified()))} · ${gb(file.length())}" })
                    val buttons = LinearLayout(this@MainActivity)
                    fun button(label: String, action: () -> Unit) = Button(this@MainActivity).apply { text = label; isEnabled = idle(); setOnClickListener { if (idle()) action() } }
                    buttons.addView(button("Play") { play(file) })
                    buttons.addView(button("Save copy") {
                        pendingBackup = file.name; pickerOpen = true; controls(); refresh()
                        backupPicker.launch("LocalCreator_${file.name}")
                    })
                    buttons.addView(button("Delete") {
                        AlertDialog.Builder(this@MainActivity).setTitle("Permanently delete this original?").setMessage("Save and verify a backup first. This cannot be undone.").setNegativeButton("Keep", null).setPositiveButton("Delete") { _, _ ->
                            if (idle()) operation("Deleting…", "Recording deleted.") { require(file.delete()) { "Could not delete recording" } }
                        }.show()
                    })
                    library.addView(buttons)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { usage.text = "Storage check failed: ${e.message}" }
        }
    }
    private fun play(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) { status.text = "Unable to play: ${e.message}" }
    }
    private fun operation(message: String, success: String, block: suspend () -> Unit) {
        if (!idle()) return
        busy = true; controls(); refresh(); status.text = message
        lifecycleScope.launch {
            try { withContext(Dispatchers.IO) { block() }; status.text = success }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { status.text = "Operation failed: ${e.message}" }
            finally { busy = false; controls(); refresh() }
        }
    }
    private fun confirmCleanup() {
        if (!idle()) return
        AlertDialog.Builder(this).setTitle("Clear temporary files?").setMessage("Originals and imported intro will be kept.").setNegativeButton("Cancel", null).setPositiveButton("Clear") { _, _ ->
            operation("Clearing…", "Temporary files cleared. Originals kept.") { require(storage.working.listFiles().orEmpty().map { it.deleteRecursively() }.all { it }) }
        }.show()
    }
    private fun importIntro(uri: Uri) {
        operation("Importing intro…", "Intro imported locally. Automatic intro assembly is not implemented yet.") {
            val partial = File(storage.intros, "intro.partial")
            try {
                partial.delete()
                val input = contentResolver.openInputStream(uri) ?: error("Cannot read selected intro")
                input.use { source ->
                    partial.outputStream().use { target ->
                        val buffer = ByteArray(1_048_576); var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = source.read(buffer); if (n < 0) break
                            total += n; require(total <= 1_000_000_000L) { "Intro exceeds 1 GB import limit" }
                            storage.snapshot().failure(n.toLong() + 2_000_000L)?.let { error(it) }
                            target.write(buffer, 0, n)
                        }
                        require(total > 0) { "Intro is empty" }
                    }
                }
                java.nio.file.Files.move(partial.toPath(), File(storage.intros, "intro.media").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } finally { partial.delete() }
        }
    }
    private fun backup(name: String, uri: Uri) {
        operation("Copying original; keep this screen open…", "Copy completed. Original kept. Open the destination copy to verify it before deleting anything.") {
            val source = storage.resolve(name)
            val expected = source.length(); require(source.isFile && expected > 0) { "Original missing or empty" }
            require(expected <= (storage.snapshot().free - Storage.FLOOR - Storage.HEADROOM).coerceAtLeast(0)) { "Not enough free space above reserve for a local copy" }
            var copied = 0L; var checked = 0L
            val output = contentResolver.openOutputStream(uri, "w") ?: error("Cannot open destination")
            source.inputStream().use { input ->
                output.use { target ->
                    val buffer = ByteArray(1_048_576)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer); if (n < 0) break
                        if (copied == 0L || copied - checked >= 16_000_000L) {
                            require(storage.snapshot().free > Storage.FLOOR + Storage.HEADROOM) { "Free-space reserve reached; destination may be incomplete" }; checked = copied
                        }
                        target.write(buffer, 0, n); copied += n
                    }
                    target.flush()
                }
            }
            require(copied == expected && source.length() == expected) { "Source changed; verify destination before using it" }
        }
    }
    private fun gb(bytes: Long) = String.format(Locale.US, "%.2f GB", bytes / 1e9)
    override fun onSaveInstanceState(outState: Bundle) {
        pendingBackup?.let { outState.putString("pendingBackup", it) }; outState.putBoolean("pickerOpen", pickerOpen); super.onSaveInstanceState(outState)
    }
    override fun onStop() { stopRecording("Saving because the app left the screen…"); super.onStop() }
}
