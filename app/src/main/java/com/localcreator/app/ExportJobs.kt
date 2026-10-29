package com.localcreator.app

import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Blocking local persistence: call from Dispatchers.IO. No rendering or automatic restart. */
class ExportJobs(private val storage: Storage) {
    enum class Phase { QUEUED, ANALYZING, RENDERING, FINALIZING, COMPLETED, CANCELED, FAILED, INTERRUPTED }
    data class Job(
        val id: String, val recording: String, val recordingBytes: Long, val recordingModified: Long,
        val introBytes: Long, val introModified: Long, val vertical: Boolean, val faceEffects: Boolean,
        val createdMs: Long, val updatedMs: Long, val phase: Phase = Phase.QUEUED,
        val percent: Int = 0, val attempt: Int = 0, val session: String = "",
        val token: String = "", val message: String = ""
    ) {
        val outputName: String get() = "LocalCreator_$id.mp4"
    }
    companion object {
        private val lock = Any()
        private val sessionId = UUID.randomUUID().toString()
        private const val VERSION = 1
        private const val MAX_BYTES = 32_768L
        private const val MAX_JOBS = 100
        private val idPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val running = setOf(Phase.ANALYZING, Phase.RENDERING, Phase.FINALIZING)
        private val terminal = setOf(Phase.COMPLETED, Phase.CANCELED, Phase.FAILED, Phase.INTERRUPTED)
    }
    private fun directory(): File = File(storage.root, "export_jobs").also { check(it.isDirectory || it.mkdirs()) { "Cannot open export-job directory" } }
    private fun atomic(id: String): AtomicFile { require(idPattern.matches(id)) { "Invalid job ID" }; return AtomicFile(File(directory(), "$id.json")) }
    private fun ids(): List<String> = directory().listFiles().orEmpty().mapNotNull {
        val name = it.name.removeSuffix(".bak")
        if (name.endsWith(".json")) name.removeSuffix(".json").takeIf(idPattern::matches) else null
    }.distinct()
    fun list(): List<Job> = synchronized(lock) { ids().map { read(it) }.sortedByDescending { it.createdMs } }
    fun get(id: String): Job = synchronized(lock) { read(id) }
    fun create(recording: String, vertical: Boolean, faceEffects: Boolean): Job = synchronized(lock) {
        val jobs = ids().map { read(it) }
        require(jobs.size < MAX_JOBS) { "Job history is full; remove terminal job metadata first" }
        require(jobs.none { it.phase == Phase.QUEUED || it.phase in running }) { "Finish, cancel or recover the existing job first" }
        val source = storage.resolve(recording); val intro = File(storage.intros, "intro.media")
        require(source.isFile && source.length() > 0 && intro.isFile && intro.length() > 0) { "Recording or intro is missing or empty" }
        val now = System.currentTimeMillis()
        val job = Job(UUID.randomUUID().toString(), recording, source.length(), source.lastModified(), intro.length(), intro.lastModified(), vertical, faceEffects, now, now)
        write(job); job
    }
    /** Must be called after startup, before claiming a job. Does not restart anything. */
    fun recoverPreviousProcess(): Int = synchronized(lock) {
        var count = 0
        ids().map { read(it) }.forEach { job ->
            if (job.phase in running && job.session != sessionId) {
                write(job.copy(phase = Phase.INTERRUPTED, updatedMs = System.currentTimeMillis(), message = "Previous process ended; render must restart. Analysis checkpoints may be reused.")); count++
            }
        }; count
    }
    fun claim(id: String): Job = synchronized(lock) {
        val job = read(id)
        require(job.phase in setOf(Phase.QUEUED, Phase.INTERRUPTED, Phase.FAILED, Phase.CANCELED)) { "Job is already running or completed" }
        require(ids().filter { it != id }.map { read(it) }.none { it.phase in running || it.phase == Phase.QUEUED }) { "Another job is pending or running" }
        checkSources(job)
        val claimed = job.copy(phase = Phase.ANALYZING, percent = 0, attempt = Math.addExact(job.attempt, 1), session = sessionId, token = UUID.randomUUID().toString(), updatedMs = System.currentTimeMillis(), message = "")
        write(claimed); claimed
    }
    /** Token guards reject callbacks from canceled attempts. Completion is metadata only. */
    fun update(id: String, token: String, phase: Phase, percent: Int, message: String = ""): Job = synchronized(lock) {
        val old = read(id)
        require(old.phase in running && old.session == sessionId && old.token.isNotEmpty() && old.token == token) { "Stale or inactive export attempt" }
        require(percent in 0..100 && message.length <= 512)
        val allowed = when (old.phase) {
            Phase.ANALYZING -> setOf(Phase.ANALYZING, Phase.RENDERING, Phase.CANCELED, Phase.FAILED, Phase.INTERRUPTED)
            Phase.RENDERING -> setOf(Phase.RENDERING, Phase.FINALIZING, Phase.CANCELED, Phase.FAILED, Phase.INTERRUPTED)
            Phase.FINALIZING -> setOf(Phase.FINALIZING, Phase.COMPLETED, Phase.CANCELED, Phase.FAILED, Phase.INTERRUPTED)
            else -> emptySet()
        }
        require(phase in allowed) { "Invalid job transition" }
        if (phase == Phase.COMPLETED) {
            checkSources(old)
            val output = File(File(storage.root, "exports"), old.outputName)
            require(output.isFile && output.length() > 0) { "Finished output missing or empty" }
        }
        val next = old.copy(phase = phase, percent = if (phase == Phase.COMPLETED) 100 else percent, message = message, updatedMs = System.currentTimeMillis())
        write(next); next
    }
    fun cancelQueued(id: String): Job = synchronized(lock) {
        val old = read(id); require(old.phase == Phase.QUEUED) { "Active jobs require worker cancellation" }
        val next = old.copy(phase = Phase.CANCELED, updatedMs = System.currentTimeMillis(), message = "Canceled before processing"); write(next); next
    }
    /** Deletes journal metadata only, never input, checkpoints, partials or finished video. */
    fun forget(id: String) = synchronized(lock) { val job = read(id); require(job.phase in terminal); atomic(id).delete() }
    fun checkSources(job: Job) {
        val source = storage.resolve(job.recording); val intro = File(storage.intros, "intro.media")
        require(source.isFile && source.length() == job.recordingBytes && source.lastModified() == job.recordingModified && intro.isFile && intro.length() == job.introBytes && intro.lastModified() == job.introModified) { "Job sources changed or disappeared; create a new job" }
    }
    private fun read(id: String): Job {
        val text = atomic(id).openRead().use { input ->
            val bytes = input.readBytesLimited(); String(bytes, Charsets.UTF_8)
        }
        val j = JSONObject(text); require(j.getInt("version") == VERSION && j.getString("id") == id)
        val job = Job(id, j.getString("recording"), j.getLong("recordingBytes"), j.getLong("recordingModified"), j.getLong("introBytes"), j.getLong("introModified"), j.getBoolean("vertical"), j.getBoolean("faceEffects"), j.getLong("createdMs"), j.getLong("updatedMs"), Phase.valueOf(j.getString("phase")), j.getInt("percent"), j.getInt("attempt"), j.getString("session"), j.getString("token"), j.getString("message"))
        storage.resolve(job.recording)
        require(job.recordingBytes > 0 && job.introBytes > 0 && job.percent in 0..100 && job.attempt >= 0 && job.message.length <= 512)
        require(job.session.isEmpty() || idPattern.matches(job.session)); require(job.token.isEmpty() || idPattern.matches(job.token))
        if (job.phase in running) require(job.session.isNotEmpty() && job.token.isNotEmpty() && job.attempt > 0)
        return job
    }
    private fun java.io.InputStream.readBytesLimited(): ByteArray {
        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
        while (true) { val count = read(buffer); if (count < 0) break; require(output.size().toLong() + count <= MAX_BYTES) { "Job journal too large" }; output.write(buffer, 0, count) }
        return output.toByteArray()
    }
    private fun write(job: Job) {
        val j = JSONObject().put("version", VERSION).put("id", job.id).put("recording", job.recording).put("recordingBytes", job.recordingBytes).put("recordingModified", job.recordingModified).put("introBytes", job.introBytes).put("introModified", job.introModified).put("vertical", job.vertical).put("faceEffects", job.faceEffects).put("createdMs", job.createdMs).put("updatedMs", job.updatedMs).put("phase", job.phase.name).put("percent", job.percent).put("attempt", job.attempt).put("session", job.session).put("token", job.token).put("message", job.message)
        val bytes = j.toString().toByteArray(Charsets.UTF_8); require(bytes.size <= MAX_BYTES)
        storage.snapshot().failure(Storage.HEADROOM + bytes.size)?.let { error(it) }
        val file = atomic(job.id); val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch (e: Exception) { file.failWrite(stream); throw e }
    }
}
