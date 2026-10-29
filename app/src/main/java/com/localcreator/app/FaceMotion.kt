package com.localcreator.app

import android.graphics.Matrix
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
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
        val seconds = time / 1_000_000.0
        val phase = seconds % 10.0
        fun smooth(x: Double): Double { val v = x.coerceIn(0.0, 1.0); return v * v * (3 - 2 * v) }
        val envelope = when { phase < 3.0 -> 0.0; phase < 3.8 -> smooth((phase - 3.0) / 0.8); phase < 6.2 -> 1.0; phase < 7.0 -> 1.0 - smooth((phase - 6.2) / 0.8); else -> 0.0 }
        val scale = 1f + 0.12f * envelope.toFloat() * confidence
        if (scale <= 1.0001f) return identity
        val left = 2f * box.left - 1f; val right = 2f * box.right - 1f
        val bottom = 1f - 2f * box.bottom; val top = 1f - 2f * box.top
        val margin = scale - 1f
        val minX = max(-margin, -1f - scale * left)
        val maxX = min(margin, 1f - scale * right)
        val minY = max(-margin, -1f - scale * bottom)
        val maxY = min(margin, 1f - scale * top)
        if (minX > maxX || minY > maxY) return identity
        val cx = (left + right) / 2f; val cy = (bottom + top) / 2f
        val side = if ((seconds / 10).toLong() % 2L == 0L) 1f else -1f
        val tx = ((1f - scale) * cx + side * margin * 0.12f).coerceIn(minX, maxX)
        val ty = ((1f - scale) * cy).coerceIn(minY, maxY)
        return Matrix().apply { setScale(scale, scale); postTranslate(tx, ty) }
    }

    companion object {
        suspend fun analyze(file: File, storage: Storage, progress: suspend (Int) -> Unit): FaceMotion {
            val reader = MediaMetadataRetriever()
            val detector = FaceDetection.getClient(FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).setMinFaceSize(0.12f).build())
            try {
                reader.setDataSource(file.path)
                val durationMs = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: error("Cannot read recording duration")
                require(durationMs in 1..600_000L) { "Face-effects test mode supports recordings up to 10 minutes; turn effects off for longer recordings" }
                val durationUs = durationMs * 1000L
                val times = (0L until durationUs step 1_000_000L).toMutableList().apply { if (lastOrNull() != durationUs - 1) add(durationUs - 1) }
                val samples = ArrayList<Sample>(times.size)
                for ((index, time) in times.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    storage.snapshot().failure(Storage.HEADROOM)?.let { error(it) }
                    val frame = reader.getScaledFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST, 720, 720)
                    var box: RectF? = null
                    if (frame != null) {
                        try {
                            val faces = Tasks.await(detector.process(InputImage.fromBitmap(frame, 0)))
                            currentCoroutineContext().ensureActive()
                            if (faces.size == 1) {
                                val r = faces.single().boundingBox
                                val padX = r.width() * 0.15f; val padY = r.height() * 0.25f
                                box = RectF(((r.left - padX) / frame.width).coerceIn(0f, 1f), ((r.top - padY) / frame.height).coerceIn(0f, 1f), ((r.right + padX) / frame.width).coerceIn(0f, 1f), ((r.bottom + padY) / frame.height).coerceIn(0f, 1f))
                                if (box.width() <= 0 || box.height() <= 0) box = null
                            }
                        } finally { frame.recycle() }
                    }
                    samples.add(Sample(time, box)); progress(((index + 1) * 100) / times.size)
                }
                return FaceMotion(samples)
            } finally { detector.close(); reader.release() }
        }
    }
}
