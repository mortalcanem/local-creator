package com.localcreator.app

import android.app.AlertDialog
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
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
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class IntroExportActivity : ComponentActivity() {
    private lateinit var storage: Storage
    private lateinit var exports: File
    private lateinit var status: TextView
    private lateinit var select: Spinner
    private lateinit var format: Spinner
    private lateinit var faceEffects: CheckBox
    private lateinit var exportButton: Button
    private lateinit var cancelButton: Button
    private lateinit var list: LinearLayout
    private var recordings: List<File> = emptyList()
    private var active = false
    private var picking = false
    private var generation = 0
    private var transformer: Transformer? = null
    private var task: Job? = null
    private var monitor: Job? = null
    private var partial: File? = null
    private var pendingCopy: String? = null
    private val saveCopy = registerForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        picking = false
        val name = pendingCopy; pendingCopy = null
        if (uri != null && name != null && !active) copyExport(name, uri) else controls()
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        storage = Storage(this); exports = File(storage.root, "exports").apply { mkdirs() }
        pendingCopy = state?.getString("pendingCopy"); picking = state?.getBoolean("picking") ?: false
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24); fitsSystemWindows = true }
        root.addView(TextView(this).apply { text = "Intro + face-guided export · long-video groundwork"; textSize = 22f })
        root.addView(TextView(this).apply { text = "Output 1080p; sources kept. KEEP THIS SCREEN OPEN: leaving cancels rendering. Analysis checkpoints can be reused, but rendered video cannot yet resume." })
        status = TextView(this); root.addView(status)
        select = Spinner(this); root.addView(select)
        format = Spinner(this).apply { adapter = ArrayAdapter(this@IntroExportActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Vertical 1080 × 1920", "Landscape 1920 × 1080")) }; root.addView(format)
        faceEffects = CheckBox(this).apply { text = "Subtle face-guided zooms (offline; recordings up to 2 hours)"; isChecked = state?.getBoolean("faceEffects") ?: false }; root.addView(faceEffects)
        exportButton = Button(this).apply { text = "Export intro + selected recording"; setOnClickListener { beginExport() } }; root.addView(exportButton)
        cancelButton = Button(this).apply { text = "Cancel current job"; setOnClickListener { cancelJob("Canceled. Sources and saved analysis checkpoints kept.") } }; root.addView(cancelButton)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(Button(this).apply { text = "Back to home"; setOnClickListener { if (!active) finish() else status.text = "Cancel the current job first." } })
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (active) cancelJob("Canceled. Sources and saved analysis checkpoints kept.") else finish() }
        })
        refresh(); controls()
    }
    private fun controls() {
        select.isEnabled = !active && !picking; format.isEnabled = !active && !picking; faceEffects.isEnabled = !active && !picking
        exportButton.isEnabled = !active && !picking && recordings.isNotEmpty() && !LongRecordingService.state.value.active
        cancelButton.isEnabled = active
        if (active) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun refresh() {
        lifecycleScope.launch {
            try {
                val data = withContext(Dispatchers.IO) { Triple(storage.list(), exports.listFiles().orEmpty().filter { it.isFile && it.extension == "mp4" }.sortedByDescending { it.lastModified() }, storage.snapshot()) }
                if (active || picking) return@launch
                recordings = data.first
                select.adapter = ArrayAdapter(this@IntroExportActivity, android.R.layout.simple_spinner_dropdown_item, recordings.map { "${DateFormat.getDateTimeInstance().format(Date(it.lastModified()))} · ${gb(it.length())}" })
                if (recordings.isEmpty()) status.text = "No recordings found. Make and stop a recording before exporting."
                list.removeAllViews()
                list.addView(TextView(this@IntroExportActivity).apply { text = "Finished exports (${data.second.size}) · managed ${gb(data.third.used)} / ${gb(data.third.budget)}" })
                data.second.forEach { file ->
                    list.addView(TextView(this@IntroExportActivity).apply { text = "${DateFormat.getDateTimeInstance().format(Date(file.lastModified()))} · ${gb(file.length())}" })
                    val buttons = LinearLayout(this@IntroExportActivity)
                    fun button(text: String, action: () -> Unit) = Button(this@IntroExportActivity).apply { this.text = text; setOnClickListener { if (!active && !picking) action() } }
                    buttons.addView(button("Play") {
                        try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(FileProvider.getUriForFile(this@IntroExportActivity, "$packageName.files", file), "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) } catch(e: Exception) { status.text = "Playback failed: ${e.message}" }
                    })
                    buttons.addView(button("Save copy") { pendingCopy = file.name; picking = true; controls(); saveCopy.launch(file.name) })
                    buttons.addView(button("Delete") {
                        AlertDialog.Builder(this@IntroExportActivity).setTitle("Delete finished export?").setMessage("Original recording and intro are kept.").setNegativeButton("Keep", null).setPositiveButton("Delete") { _, _ ->
                            if (!active && !picking) lifecycleScope.launch { val ok = withContext(Dispatchers.IO) { file.delete() }; status.text = if (ok) "Export deleted. Sources kept." else "Delete failed."; refresh() }
                        }.show()
                    }); list.addView(buttons)
                }; controls()
            } catch(e: CancellationException) { throw e } catch(e: Exception) { status.text = "Could not read local files: ${e.message}" }
        }
    }
    private fun duration(file: File): Long {
        val reader = MediaMetadataRetriever()
        try { reader.setDataSource(file.path); require(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes") { "Input has no readable video track" }; return reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 } ?: error("Cannot determine input duration") } finally { reader.release() }
    }
    private fun edited(file: File, width: Int, height: Int, motion: FaceMotion? = null): EditedMediaItem {
        val mixer = ChannelMixingAudioProcessor().apply { putChannelMixingMatrix(ChannelMixingMatrix.create(1, 1)); putChannelMixingMatrix(ChannelMixingMatrix.create(2, 1)) }
        val resampler = SonicAudioProcessor().apply { setOutputSampleRateHz(48_000) }
        val effects = mutableListOf<Effect>(); if (motion != null) effects.add(motion)
        effects.add(Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT))
        return EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(file))).setEffects(Effects(listOf(mixer, resampler), effects)).build()
    }
    private fun beginExport() {
        if (active || picking) return
        if (LongRecordingService.state.value.active) { status.text = "Stop the long recording before exporting."; return }
        val recording = recordings.getOrNull(select.selectedItemPosition) ?: return
        val intro = File(storage.intros, "intro.media")
        val vertical = format.selectedItemPosition == 0; val useFaceEffects = faceEffects.isChecked
        val token = ++generation
        active = true; controls(); status.text = "Checking inputs and available space…"
        task = lifecycleScope.launch {
            try {
                val sourceState = withContext(Dispatchers.IO) {
                    require(intro.isFile && intro.length() > 0) { "Import an intro on the recording screen first" }
                    val ms = Math.addExact(duration(intro), duration(recording))
                    val estimate = Math.addExact(Math.multiplyExact(ms / 1000 + 1, 2_000_000L), Storage.HEADROOM)
                    storage.snapshot().failure(estimate)?.let { error(it) }
                    listOf(intro.length(), intro.lastModified(), recording.length(), recording.lastModified())
                }
                fun checkSources() { require(listOf(intro.length(), intro.lastModified(), recording.length(), recording.lastModified()) == sourceState) { "Source files changed during export" } }
                val motion = if (useFaceEffects) withContext(Dispatchers.IO) { FaceMotion.analyze(recording, storage) { percent -> withContext(Dispatchers.Main) { if (token == generation) status.text = "Analyzing face locally: $percent% — keep this screen open" } } } else null
                currentCoroutineContext().ensureActive()
                if (token != generation) return@launch
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) { cancelJob("Export did not start because app left screen."); return@launch }
                val outputLimit = withContext(Dispatchers.IO) {
                    checkSources(); val s = storage.snapshot(); s.failure(Storage.HEADROOM)?.let { error(it) }
                    minOf(s.budget - s.used, s.free - Storage.FLOOR) - Storage.HEADROOM
                }
                require(outputLimit > 0) { "No output space remains above protected limits" }
                val faceReport = when { motion == null -> "Effects off."; motion.detectedSamples == 0 -> "No single face detected; original framing used."; else -> "Single face found in ${motion.detectedSamples}/${motion.totalSamples} samples." }
                val id = UUID.randomUUID().toString(); val output = File(storage.working, "export_$id.partial.mp4"); partial = output
                val finalFile = File(exports, "LocalCreator_$id.mp4")
                val width = if (vertical) 1080 else 1920; val height = if (vertical) 1920 else 1080
                val sequence = EditedMediaItemSequence(edited(intro, width, height), edited(recording, width, height, motion))
                val composition = Composition.Builder(sequence).experimentalSetForceAudioTrack(true).build()
                val engine = Transformer.Builder(this@IntroExportActivity).setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC).addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) {
                        if (token != generation) return
                        monitor?.cancel(); monitor = null; transformer = null
                        task = lifecycleScope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    checkSources(); require(output.length() > 0) { "Encoder produced an empty output" }
                                    require(output.length() <= outputLimit) { "Output exceeded its available-space allowance" }; storage.snapshot().failure(0)?.let { error(it) }
                                    java.nio.file.Files.move(output.toPath(), finalFile.toPath())
                                }
                                if (token == generation) { partial = null; status.text = "Export complete. $faceReport Play to verify framing and audio. Sources kept." }
                            } catch(e: CancellationException) { throw e } catch(e: Exception) { output.delete(); if (token == generation) { partial = null; status.text = "Could not finalize export: ${e.message}" } }
                            finally { if (token == generation) { active = false; controls(); refresh() } }
                        }
                    }
                    override fun onError(composition: Composition, result: ExportResult, exception: ExportException) { if (token == generation) cancelJob("Export failed: ${exception.errorCodeName}: ${exception.message}. Sources kept.") }
                }).build()
                transformer = engine; engine.start(composition, output.path)
                monitor = lifecycleScope.launch {
                    try {
                        val progress = ProgressHolder()
                        while (isActive && active && token == generation) {
                            delay(1_000L)
                            val problem = withContext(Dispatchers.IO) { checkSources(); storage.snapshot().failure(Storage.HEADROOM) ?: if (output.length() >= outputLimit) "Export reached its available-space allowance" else null }
                            if (token != generation) return@launch
                            if (problem != null) { cancelJob(problem); return@launch }
                            val state = engine.getProgress(progress)
                            status.text = (if (state == Transformer.PROGRESS_STATE_AVAILABLE) "Exporting ${progress.progress}%" else "Exporting") + " — $faceReport Output ${gb(output.length())}; keep this screen open"
                        }
                    } catch(e: CancellationException) { throw e } catch(e: Exception) { if (token == generation) cancelJob("Export monitoring failed: ${e.message}") }
                }
            } catch(e: CancellationException) { throw e } catch(e: Exception) { if (token == generation) cancelJob("Export could not start: ${e.message}") }
        }
    }
    private fun cancelJob(message: String) {
        generation++; task?.cancel(); task = null; monitor?.cancel(); monitor = null
        val engine = transformer; transformer = null; try { engine?.cancel() } catch (_: Exception) { }
        val file = partial; partial = null; if (file != null) lifecycleScope.launch(Dispatchers.IO) { file.delete() }
        active = false; status.text = message; controls(); refresh()
    }
    private fun copyExport(name: String, uri: Uri) {
        if (active || picking) return
        val token = ++generation; active = true; controls(); status.text = "Saving copy… Keep this screen open."
        task = lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    require(name == File(name).name && name.endsWith(".mp4"))
                    val source = File(exports, name); val expected = source.length(); require(source.isFile && expected > 0)
                    require(expected <= (storage.snapshot().free - Storage.FLOOR - Storage.HEADROOM).coerceAtLeast(0)) { "Not enough space above free-space reserve" }
                    val output = contentResolver.openOutputStream(uri, "w") ?: error("Cannot open destination")
                    var count = 0L; var checked = 0L
                    source.inputStream().use { input -> output.use { target ->
                        val buffer = ByteArray(1_048_576)
                        while(true) {
                            currentCoroutineContext().ensureActive(); val n = input.read(buffer); if(n < 0) break
                            if(count == 0L || count - checked >= 16_000_000L) { require(storage.snapshot().free > Storage.FLOOR + Storage.HEADROOM) { "Free-space reserve reached" }; checked = count }
                            target.write(buffer, 0, n); count += n
                        }; target.flush()
                    } }; require(count == expected && source.length() == expected) { "Source changed during copy" }
                }; if (token == generation) status.text = "Copy saved. Open destination copy to verify before deleting anything."
            } catch(e: CancellationException) { throw e } catch(e: Exception) { if (token == generation) status.text = "Copy failed: ${e.message}. Export kept; destination may be partial." }
            finally { if (token == generation) { active = false; controls(); refresh() } }
        }
    }
    private fun gb(bytes: Long) = String.format(Locale.US, "%.2f GB", bytes / 1e9)
    override fun onSaveInstanceState(state: Bundle) { pendingCopy?.let { state.putString("pendingCopy", it) }; state.putBoolean("picking", picking); state.putBoolean("faceEffects", faceEffects.isChecked); super.onSaveInstanceState(state) }
    override fun onStop() { if (active) cancelJob("Job stopped because app left screen. Sources/checkpoints kept; rendering must restart. A copy destination may be incomplete."); super.onStop() }
}
