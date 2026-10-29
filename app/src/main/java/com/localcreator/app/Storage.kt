package com.localcreator.app

import android.content.Context
import android.os.StatFs
import java.io.File

class Storage(context: Context) {
    private val preferences = context.getSharedPreferences("storage_settings", Context.MODE_PRIVATE)
    val root = File(context.filesDir, "creator").apply { mkdirs() }
    val recordings = File(root, "recordings").apply { mkdirs() }
    val intros = File(root, "intro").apply { mkdirs() }
    val working = File(root, "working").apply { mkdirs() }
    companion object {
        const val GB = 1_000_000_000L
        const val BUDGET = 350 * GB
        const val FLOOR = 50 * GB
        const val HEADROOM = 64_000_000L
    }
    val budgetBytes: Long get() = preferences.getLong("budget_bytes", BUDGET).takeIf { it > 0 } ?: BUDGET
    data class Snapshot(val used: Long, val free: Long, val budget: Long = BUDGET) {
        fun failure(extra: Long): String? {
            require(extra >= 0)
            if (used >= budget || extra > budget - used) return "The ${budget / GB} GB managed-file budget would be exceeded."
            if (free <= FLOOR || extra > free - FLOOR) return "The 50 GB free-space reserve would be crossed."
            return null
        }
    }
    /** Call on an IO dispatcher. This changes a ceiling, not a disk allocation. */
    fun setBudgetGb(gigabytes: Long) {
        require(gigabytes > 0) { "Enter a positive whole number of GB" }
        val bytes = Math.multiplyExact(gigabytes, GB)
        require(bytes >= snapshot().used) { "Budget cannot be below files already stored. No files were deleted." }
        check(preferences.edit().putLong("budget_bytes", bytes).commit()) { "Could not save storage setting" }
    }
    fun snapshot() = Snapshot(root.walkTopDown().filter { it.isFile }.sumOf { it.length() }, StatFs(root.path).availableBytes, budgetBytes)
    fun list() = recordings.listFiles().orEmpty().filter { it.isFile && it.extension == "mp4" }.sortedByDescending { it.lastModified() }
    fun resolve(name: String): File {
        require(name == File(name).name && name.endsWith(".mp4"))
        return File(recordings, name).canonicalFile.also { require(it.parentFile == recordings.canonicalFile) }
    }
}
