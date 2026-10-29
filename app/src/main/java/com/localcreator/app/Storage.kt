package com.localcreator.app

import android.content.Context
import android.os.StatFs
import java.io.File

class Storage(context: Context) {
    val root = File(context.filesDir, "creator").apply { mkdirs() }
    val recordings = File(root, "recordings").apply { mkdirs() }
    val intros = File(root, "intro").apply { mkdirs() }
    val working = File(root, "working").apply { mkdirs() }
    companion object {
        const val BUDGET = 10_000_000_000L
        const val FLOOR = 50_000_000_000L
        const val HEADROOM = 64_000_000L
    }
    data class Snapshot(val used: Long, val free: Long) {
        fun failure(extra: Long): String? {
            require(extra >= 0)
            if (used >= BUDGET || extra > BUDGET - used) return "The 10 GB managed-file budget would be exceeded."
            if (free <= FLOOR || extra > free - FLOOR) return "The 50 GB free-space reserve would be crossed."
            return null
        }
    }
    fun snapshot() = Snapshot(root.walkTopDown().filter { it.isFile }.sumOf { it.length() }, StatFs(root.path).availableBytes)
    fun list() = recordings.listFiles().orEmpty().filter { it.isFile && it.extension == "mp4" }.sortedByDescending { it.lastModified() }
    fun resolve(name: String): File {
        require(name == File(name).name && name.endsWith(".mp4"))
        return File(recordings, name).canonicalFile.also { require(it.parentFile == recordings.canonicalFile) }
    }
}
