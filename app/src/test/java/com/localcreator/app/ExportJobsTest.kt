package com.localcreator.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExportJobsTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var storage: Storage
    private lateinit var jobs: ExportJobs
    private lateinit var source: File
    @Before fun setup() {
        val application = RuntimeEnvironment.getApplication()
        val directory = temporary.newFolder("private-files")
        val prefix = "journal-test-${UUID.randomUUID()}-"
        val context = object : ContextWrapper(application) {
            override fun getFilesDir(): File = directory
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = application.getSharedPreferences(prefix + name, mode)
        }
        storage = Storage(context)
        ShadowStatFs.registerStats(storage.root.absolutePath, 100_000_000, 80_000_000, 80_000_000)
        source = File(storage.recordings, "fixture.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        File(storage.intros, "intro.media").writeBytes(byteArrayOf(4, 5, 6))
        jobs = ExportJobs(storage)
    }
    private fun create() = jobs.create(source.name, vertical = true, faceEffects = true)
    private fun journal(id: String) = File(File(storage.root, "export_jobs"), "$id.json")
    private fun finalizing(): ExportJobs.Job {
        val claimed = jobs.claim(create().id)
        jobs.update(claimed.id, claimed.token, ExportJobs.Phase.RENDERING, 0)
        return jobs.update(claimed.id, claimed.token, ExportJobs.Phase.FINALIZING, 100)
    }
    @Test fun queuedJobRoundTripsAcrossStoreInstances() {
        val job = create()
        assertEquals(job, ExportJobs(storage).get(job.id))
        assertEquals(ExportJobs.Phase.QUEUED, job.phase)
        assertEquals(source.name, job.recording)
        assertTrue(job.vertical && job.faceEffects)
    }
    @Test fun anotherPendingOrRunningJobIsRejected() {
        val job = create()
        assertThrows(IllegalArgumentException::class.java) { create() }
        jobs.claim(job.id)
        assertThrows(IllegalArgumentException::class.java) { create() }
        assertEquals(1, jobs.list().size)
    }
    @Test fun retryGetsNewTokenAndRejectsOldCallbacks() {
        val first = jobs.claim(create().id)
        jobs.update(first.id, first.token, ExportJobs.Phase.CANCELED, 0)
        val second = jobs.claim(first.id)
        assertEquals(first.attempt + 1, second.attempt)
        assertNotEquals(first.token, second.token)
        assertThrows(IllegalArgumentException::class.java) { jobs.update(first.id, first.token, ExportJobs.Phase.RENDERING, 10) }
        assertEquals(ExportJobs.Phase.RENDERING, jobs.update(second.id, second.token, ExportJobs.Phase.RENDERING, 10).phase)
    }
    @Test fun cannotSkipRenderingAndFinalization() {
        val job = jobs.claim(create().id)
        assertThrows(IllegalArgumentException::class.java) { jobs.update(job.id, job.token, ExportJobs.Phase.COMPLETED, 100) }
        assertEquals(ExportJobs.Phase.ANALYZING, jobs.get(job.id).phase)
    }
    @Test fun completionRequiresNonemptyExpectedOutput() {
        val job = finalizing()
        assertThrows(IllegalArgumentException::class.java) { jobs.update(job.id, job.token, ExportJobs.Phase.COMPLETED, 100) }
        val output = File(File(storage.root, "exports").apply { mkdirs() }, job.outputName)
        output.writeBytes(byteArrayOf())
        assertThrows(IllegalArgumentException::class.java) { jobs.update(job.id, job.token, ExportJobs.Phase.COMPLETED, 100) }
    }
    @Test fun forgettingCompletedMetadataPreservesInputsAndOutput() {
        val job = finalizing()
        val output = File(File(storage.root, "exports").apply { mkdirs() }, job.outputName).apply { writeBytes(byteArrayOf(7)) }
        val completed = jobs.update(job.id, job.token, ExportJobs.Phase.COMPLETED, 10)
        assertEquals(100, completed.percent)
        assertThrows(IllegalArgumentException::class.java) { jobs.claim(job.id) }
        jobs.forget(job.id)
        assertTrue(jobs.list().isEmpty())
        assertTrue(source.exists() && File(storage.intros, "intro.media").exists() && output.exists())
    }
    @Test fun sourceChangeBlocksClaim() {
        val job = create()
        source.appendBytes(byteArrayOf(8))
        assertThrows(IllegalArgumentException::class.java) { jobs.claim(job.id) }
        assertEquals(ExportJobs.Phase.QUEUED, jobs.get(job.id).phase)
    }
    @Test fun previousSessionBecomesInterruptedAndCanRetry() {
        val old = jobs.claim(create().id)
        val file = journal(old.id)
        file.writeText(JSONObject(file.readText()).put("session", UUID.randomUUID().toString()).toString())
        assertEquals(1, jobs.recoverPreviousProcess())
        assertEquals(ExportJobs.Phase.INTERRUPTED, jobs.get(old.id).phase)
        assertEquals(old.attempt + 1, jobs.claim(old.id).attempt)
    }
    @Test fun recoveryDoesNotInterruptCurrentSession() {
        val job = jobs.claim(create().id)
        assertEquals(0, jobs.recoverPreviousProcess())
        assertEquals(ExportJobs.Phase.ANALYZING, jobs.get(job.id).phase)
    }
    @Test fun corruptJournalIsNotSilentlyDiscarded() {
        val job = create()
        journal(job.id).writeText("not-json")
        assertThrows(Exception::class.java) { jobs.list() }
        assertThrows(Exception::class.java) { create() }
        assertTrue(source.exists())
    }
    @Test fun oversizedJournalIsRejected() {
        val job = create()
        journal(job.id).writeText("x".repeat(32_769))
        assertThrows(IllegalArgumentException::class.java) { jobs.get(job.id) }
    }
    @Test fun lowSpaceDoesNotReplaceDurableQueuedRecord() {
        val job = create()
        val original = journal(job.id).readText()
        ShadowStatFs.registerStats(storage.root.absolutePath, 100, 0, 0)
        assertThrows(IllegalStateException::class.java) { jobs.claim(job.id) }
        assertEquals(original, journal(job.id).readText())
    }
    @Test fun percentOutsideRangeIsRejectedWithoutChangingState() {
        val job = jobs.claim(create().id)
        assertThrows(IllegalArgumentException::class.java) { jobs.update(job.id, job.token, ExportJobs.Phase.ANALYZING, 101) }
        assertEquals(0, jobs.get(job.id).percent)
    }
}
