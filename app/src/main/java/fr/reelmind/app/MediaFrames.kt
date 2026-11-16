package fr.reelmind.app

import android.media.MediaMetadataRetriever
import android.graphics.Bitmap
import java.io.File

/** Local sampling of a public video already downloaded by yt-dlp. No AI call here. */
object MediaFrames {
    fun sample(video: File, folder: File, maxFrames: Int = 3): List<File> {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(video.absolutePath)
            val lengthMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return emptyList()
            if (lengthMs <= 0) return emptyList()
            return (1..maxFrames).mapNotNull { index ->
                val timestampUs = lengthMs * 1000L * index / (maxFrames + 1)
                val bitmap = retriever.getFrameAtTime(timestampUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return@mapNotNull null
                try {
                    val scale = minOf(1f, 960f / maxOf(bitmap.width, bitmap.height))
                    val resized = if (scale < 1f) Bitmap.createScaledBitmap(bitmap,
                        (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
                    val output = File(folder, "frame_$index.jpg")
                    output.outputStream().use { resized.compress(Bitmap.CompressFormat.JPEG, 78, it) }
                    if (resized !== bitmap) resized.recycle()
                    output
                } finally { bitmap.recycle() }
            }
        } finally { retriever.release() }
    }
}
