package com.localcreator.app

import android.graphics.Matrix
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.util.AtomicFile
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.math.*

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class FaceMotion(private val samples: List<Sample>) : MatrixTransformation {
    data class Sample(val us: Long, val box: RectF?)
    val detectedSamples: Int get() = samples.count { it.box != null }
    val totalSamples: Int get() = samples.size
    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val identity = Matrix()
        if (samples.size < 2) return identity
        val time = presentationTimeUs.coerceAtLeast(0)
        var low = 0; var high = samples.lastIndex
        while (low + 1 < high) { val mid = (low + high) / 2; if (samples[mid].us <= time) low = mid else high = mid }
        val a = samples[low]; val b = samples[high]
        val fraction = ((time - a.us).toDouble() / (b.us - a.us).coerceAtLeast(1)).coerceIn(0.0, 1.0).toFloat()
        val first = a.box; val second = b.box
        if (first == null && second == null) return identity
        val box = when {
            first != null && second != null -> RectF(min(first.left, second.left), min(first.top, second.top), max(first.right, second.right), max(first.bottom, second.bottom))
            first != null -> RectF(first)
            else -> RectF(second!!)
        }
        val confidence = if (first == null) fraction else if (second == null) 1f - fraction else 1f
        val seconds = time / 1_000_000.0; val phase = seconds % 10.0
        fun smooth(x: Double): Double { val v = x.coerceIn(0.0, 1.0); return v * v * (3 - 2 * v) }
        val envelope = when { phase < 3.0 -> 0.0; phase < 3.8 -> smooth((phase - 3.0) / 0.8); phase < 6.2 -> 1.0; phase < 7.0 -> 1.0 - smooth((phase - 6.2) / 0.8); else -> 0.0 }
        val scale = 1f + 0.12f * envelope.toFloat() * confidence
        if (scale <= 1.0001f) return identity
        val left = 2f * box.left - 1f; val right = 2f * box.right - 1f
        val bottom = 1f - 2f * box.bottom; val top = 1f - 2f * box.top
        val margin = scale - 1f
        val minX = max(-margin, -1f - scale * left); val maxX = min(margin, 1f - scale * right)
        val minY = max(-margin, -1f - scale * bottom); val maxY = min(margin, 1f - scale * top)
        if (minX > maxX || minY > maxY) return identity
        val cx = (left + right) / 2f; val cy = (bottom + top) / 2f
        val side = if ((seconds / 10).toLong() % 2L == 0L) 1f else -1f
        val tx = ((1f - scale) * cx + side * margin * 0.12f).coerceIn(minX, maxX)
        val ty = ((1f - scale) * cy).coerceIn(minY, maxY)
        return Matrix().apply { setScale(scale, scale); postTranslate(tx, ty) }
    }
    companion object {
        private val scanMutex = Mutex()
        private const val CACHE_VERSION = 1
        private const val MAX_DURATION_MS = 7_210_000L
        suspend fun analyze(file: File, storage: Storage, progress: suspend (Int) -> Unit): FaceMotion = scanMutex.withLock {
            analyzeLocked(file, storage, progress)
        }
        private suspend fun analyzeLocked(file: File, storage: Storage, progress: suspend (Int) -> Unit): FaceMotion {
            currentCoroutineContext().ensureActive()
            require(file.isFile && file.length() > 0) { "Recording missing or empty" }
            val originalSize = file.length(); val originalModified = file.lastModified()
            fun unchanged() { require(file.length() == originalSize && file.lastModified() == originalModified) { "Recording changed during analysis; stop recording before editing" } }
            val reader = MediaMetadataRetriever()
            val detector = FaceDetection.getClient(FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).setMinFaceSize(0.12f).build())
            try {
                reader.setDataSource(file.path)
                val durationMs = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: error("Cannot read recording duration")
                require(durationMs in 1..MAX_DURATION_MS) { "Face analysis supports up to two hours, with ten seconds of finalization tolerance" }
                val durationUs = durationMs * 1000L
                val times = (0L until durationUs step 1_000_000L).toMutableList().apply { if (lastOrNull() != durationUs - 1) add(durationUs - 1) }
                val fingerprint = "$CACHE_VERSION|${file.name}|$originalSize|$originalModified|$durationUs"
                val key = MessageDigest.getInstance("SHA-256").digest(fingerprint.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
                val cache = AtomicFile(File(storage.working, "face-plan-$key.json"))
                val samples = loadCheckpoint(cache, fingerprint, times)
                progress(samples.size * 100 / times.size)
                if (samples.size == times.size) { unchanged(); return FaceMotion(samples.toList()) }
                val startIndex = samples.size
                for (index in startIndex until times.size) {
                    currentCoroutineContext().ensureActive(); unchanged()
                    storage.snapshot().failure(Storage.HEADROOM)?.let { error(it) }
                    val time = times[index]
                    val frame = reader.getScaledFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST, 720, 720)
                    var box: RectF? = null
                    if (frame != null) {
                        try {
                            val faces = Tasks.await(detector.process(InputImage.fromBitmap(frame, 0)))
                            currentCoroutineContext().ensureActive()
                            if (faces.size == 1) {
                                val r = faces.single().boundingBox
                                val px = r.width() * 0.15f; val py = r.height() * 0.25f
                                val candidate = RectF(((r.left - px) / frame.width).coerceIn(0f, 1f), ((r.top - py) / frame.height).coerceIn(0f, 1f), ((r.right + px) / frame.width).coerceIn(0f, 1f), ((r.bottom + py) / frame.height).coerceIn(0f, 1f))
                                if (candidate.width() > 0 && candidate.height() > 0) box = candidate
                            }
                        } finally { frame.recycle() }
                    }
                    samples.add(Sample(time, box))
                    if (samples.size % 30 == 0 || index == times.lastIndex) { currentCoroutineContext().ensureActive(); unchanged(); saveCheckpoint(cache, fingerprint, samples, storage) }
                    progress(samples.size * 100 / times.size)
                }
                unchanged(); return FaceMotion(samples.toList())
            } finally { detector.close(); reader.release() }
        }
        private fun loadCheckpoint(cache: AtomicFile, fingerprint: String, times: List<Long>): MutableList<Sample> {
            return try {
                require(cache.baseFile.length() <= 4_000_000L)
                val json = JSONObject(cache.openRead().bufferedReader().use { it.readText() })
                require(json.getString("fingerprint") == fingerprint && json.getInt("version") == CACHE_VERSION)
                val rows = json.getJSONArray("samples"); require(rows.length() <= times.size)
                val samples = ArrayList<Sample>(rows.length())
                for (index in 0 until rows.length()) {
                    val row = rows.getJSONArray(index); require(row.length() == 1 || row.length() == 5)
                    val us = row.getLong(0); require(us == times[index])
                    val box = if (row.length() == 5) {
                        val values = (1..4).map { row.getDouble(it).toFloat() }; require(values.all { it.isFinite() && it in 0f..1f })
                        RectF(values[0], values[1], values[2], values[3]).also { require(it.width() > 0 && it.height() > 0) }
                    } else null
                    samples.add(Sample(us, box))
                }; samples
            } catch (_: Exception) { arrayListOf() }
        }
        private fun saveCheckpoint(cache: AtomicFile, fingerprint: String, samples: List<Sample>, storage: Storage) {
            val rows = JSONArray()
            samples.forEach { sample ->
                val row = JSONArray().put(sample.us)
                sample.box?.let { row.put(it.left.toDouble()).put(it.top.toDouble()).put(it.right.toDouble()).put(it.bottom.toDouble()) }
                rows.put(row)
            }
            val bytes = JSONObject().put("version", CACHE_VERSION).put("fingerprint", fingerprint).put("samples", rows).toString().toByteArray(Charsets.UTF_8)
            storage.snapshot().failure(bytes.size.toLong() + Storage.HEADROOM)?.let { error(it) }
            val stream = cache.startWrite()
            try { stream.write(bytes); cache.finishWrite(stream) } catch(e: Exception) { cache.failWrite(stream); throw e }
        }
    }
}
