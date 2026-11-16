package fr.reelmind.app

import android.content.Context

/** Clean up old interrupted downloads without touching active WorkManager jobs. */
object CacheMaintenance {
    fun cleanOrphans(context: Context) {
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        context.cacheDir.listFiles()?.forEach { file ->
            if ((file.name.startsWith("reelmind_") || file.name.startsWith("video_dl_")) &&
                file.lastModified() < cutoff) {
                runCatching { file.deleteRecursively() }
            }
        }
    }
}
