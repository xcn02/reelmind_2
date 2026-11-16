package fr.reelmind.app

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Every extraction route is optional. All results are ephemeral, never written to public storage. */
data class ExtractSettings(
    val local: Boolean, val render: Boolean, val priority: String, val backend: String,
    val transcription: Boolean, val images: String, val temporary: Boolean,
    val metadataMethod: String = "auto",
) {
    companion object {
        fun load(context: Context): ExtractSettings {
            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            return ExtractSettings(
                prefs.getBoolean("ex_local", true), prefs.getBoolean("ex_render", true),
                prefs.getString("ex_priority", "auto") ?: "auto",
                prefs.getString("ex_backend", "") ?: "",
                prefs.getBoolean("ex_audio", true), prefs.getString("ex_images", "gemini") ?: "gemini",
                prefs.getBoolean("ex_temporary", true),
                prefs.getString("ex_metadata_method", "auto") ?: "auto"
            )
        }
    }
}

data class ExtractedContent(
    val title: String = "", val caption: String = "", val author: String = "",
    val thumbnail: String = "", val kind: String = "", val audioFile: File? = null,
    val images: List<File> = emptyList(), val ownedFiles: List<File> = emptyList(),
    val diagnostics: String = "", val imageUrls: List<String> = emptyList()
) {
    fun cleanup() { ownedFiles.forEach { runCatching { it.delete(); it.parentFile?.takeIf { dir -> dir.name.startsWith("reelmind_") && dir.listFiles()?.isEmpty() == true }?.delete() } } }
}

object ExtractionCoordinator {
    fun extract(context: Context, rawUrl: String, settings: ExtractSettings): ExtractedContent {
        val url = validateInstagramUrl(rawUrl)
        val methods = when (settings.metadataMethod) {
            "json" -> listOf("json")
            "local" -> listOf("local")
            else -> if (settings.priority == "remote") listOf("json", "remote", "local")
                    else listOf("json", "local", "remote")
        }
        var caption = ""; var author = ""; var thumbnail = ""; var title = ""; var kind = ""
        var audio: File? = null
        val images = mutableListOf<File>(); val ownedFiles = mutableListOf<File>()
        val imageUrls = mutableListOf<String>()
        val attempts = mutableListOf<String>()
        for (method in methods) {
            if (method == "local" && !settings.local || method == "remote" && !settings.render) continue
            if (method == "remote" && settings.backend.isBlank()) continue
            try {
                // Do not launch yt-dlp merely to duplicate already complete metadata.
                if (caption.isNotBlank() && author.isNotBlank() && thumbnail.isNotBlank() &&
                    (audio != null || !settings.transcription || kind != "video") &&
                    (images.isNotEmpty() || imageUrls.isNotEmpty() || settings.images == "off" || (settings.images == "auto" && kind == "video"))) break
                val result = when (method) {
                    "json" -> InstagramJsonExtractor.extract(url)
                    "local" -> LocalExtractor.extract(context, url, settings)
                    else -> RenderExtractor.extract(context, url, settings)
                }
                if (caption.isBlank()) caption = result.caption
                if (author.isBlank()) author = result.author
                if (thumbnail.isBlank()) thumbnail = result.thumbnail
                if (title.isBlank()) title = result.title
                if (kind.isBlank()) kind = result.kind
                if (audio == null) audio = result.audioFile
                images.addAll(result.images)
                imageUrls.addAll(result.imageUrls)
                ownedFiles.addAll(result.ownedFiles)
                attempts.add("$method: légende=${result.caption.isNotBlank()}, auteur=${result.author.isNotBlank()}, image=${result.thumbnail.isNotBlank()}")
            } catch (e: Exception) {
                attempts.add("$method: ${e.message.orEmpty().take(110)}")
            }
        }
        // JSON-only limits metadata extraction, but audio can still use local yt-dlp.
        if (settings.metadataMethod == "json" && (settings.transcription || settings.images != "off") && settings.local) {
            try {
                val media = LocalExtractor.extract(context, url, settings)
                audio = media.audioFile
                images.addAll(media.images)
                imageUrls.addAll(media.imageUrls)
                ownedFiles.addAll(media.ownedFiles)
                attempts.add("audio yt-dlp: ${audio != null}")
            } catch (e: Exception) { attempts.add("audio yt-dlp: ${e.message.orEmpty().take(100)}") }
        }
        // A carousel or image post has no speech; analyze the actual public images instead.
        if (settings.images != "off" && kind != "video" && images.isEmpty() && imageUrls.isNotEmpty()) {
            for ((index, imageUrl) in imageUrls.distinct().take(20).withIndex()) {
                try {
                    val imageUri = java.net.URI(imageUrl)
                    require(imageUri.scheme == "https" && imageUri.host?.lowercase()?.let { h ->
                        h == "instagram.com" || h.endsWith(".instagram.com") ||
                            h == "fbcdn.net" || h.endsWith(".fbcdn.net") ||
                            h == "cdninstagram.com" || h.endsWith(".cdninstagram.com")
                    } == true) { "Hôte image Instagram non autorisé" }
                    val conn = imageUri.toURL().openConnection() as HttpURLConnection
                    try {
                        conn.connectTimeout = 7000; conn.readTimeout = 10000
                        conn.instanceFollowRedirects = false
                        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                        require(conn.responseCode == 200) { "Image HTTP ${conn.responseCode}" }
                        val target = File.createTempFile("reelmind_photo_${index}_", ".jpg", context.cacheDir)
                        try {
                            conn.inputStream.use { input -> target.outputStream().use { output ->
                                val buffer = ByteArray(8192); var count = 0
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    count += n; require(count < 4_000_000) { "Image trop volumineuse" }
                                    output.write(buffer, 0, n)
                                }
                            } }
                            images.add(target); ownedFiles.add(target)
                        } catch (e: Exception) { target.delete(); throw e }
                    } finally { conn.disconnect() }
                } catch (e: Exception) { attempts.add("image $index: ${e.message.orEmpty().take(75)}") }
            }
        }
        if (caption.isBlank()) {
            val fallback = runCatching { InstagramPublicMetadata.read(url) }.getOrDefault("")
            if (fallback.isNotBlank()) { caption = fallback; attempts.add("open-graph: légende") }
        }
        val diagnostics = attempts.joinToString(" | ").take(600)
        if (caption.isBlank() && author.isBlank() && thumbnail.isBlank() && audio == null && images.isEmpty())
            throw IllegalStateException(diagnostics.ifBlank { "Aucune méthode d'extraction disponible" })
        return ExtractedContent(title, caption, author, thumbnail, kind, audio, images, ownedFiles, diagnostics, imageUrls)
    }

    fun validateInstagramUrl(raw: String): String {
        val uri = java.net.URI(raw)
        require(uri.scheme == "https" && uri.host?.lowercase() in setOf("instagram.com", "www.instagram.com")) {
            "URL Instagram HTTPS obligatoire"
        }
        require(uri.path.matches(Regex("/(reel|reels|p|tv)/[A-Za-z0-9_-]+/?"))) { "URL de publication non reconnue" }
        return "https://www.instagram.com${uri.path.trimEnd('/')}"
    }
}

object LocalExtractor {
    private val lock = Any()
    fun initialize(context: Context) {
        synchronized(lock) {
            YoutubeDL.getInstance().init(context.applicationContext)
        }
    }

    fun update(context: Context, nightly: Boolean): String {
        initialize(context)
        val channel = if (nightly) YoutubeDL.UpdateChannel.NIGHTLY else YoutubeDL.UpdateChannel.STABLE
        return YoutubeDL.getInstance().updateYoutubeDL(context, channel).toString()
    }

    fun extract(context: Context, url: String, settings: ExtractSettings): ExtractedContent {
        initialize(context)
        val infoRequest = YoutubeDLRequest(url)
        infoRequest.addOption("--dump-single-json")
        infoRequest.addOption("--skip-download")
        infoRequest.addOption("--no-playlist")
        val infoText = YoutubeDL.getInstance().execute(infoRequest).out.trim()
        val json = JSONObject(infoText.lineSequence().last { it.trim().startsWith("{") })
        val caption = json.optString("description")
        val title = json.optString("title")
        val author = json.optString("uploader").ifBlank { json.optString("channel") }
        val thumbnail = json.optString("thumbnail")
        val kind = if (json.optJSONArray("entries") != null) "carousel" else "video"
        var audio: File? = null
        val files = mutableListOf<File>()
        val images = mutableListOf<File>()
        val folder = File(context.cacheDir, "reelmind_${System.nanoTime()}").apply { mkdirs() }
        fun download(format: String, prefix: String): File? {
            val request = YoutubeDLRequest(url)
            request.addOption("--no-playlist")
            request.addOption("-f", format)
            request.addOption("--max-filesize", "50M")
            request.addOption("-o", File(folder, "$prefix.%(ext)s").absolutePath)
            YoutubeDL.getInstance().execute(request)
            val result = folder.listFiles()?.firstOrNull { it.name.startsWith("$prefix.") && it.isFile && it.length() > 0 &&
                it.extension.lowercase() in setOf("m4a", "mp3", "webm", "ogg", "opus", "wav", "flac", "mp4", "mkv") }
            if (result != null && result !in files) files.add(result)
            return result
        }
        try {
            if (settings.transcription && kind == "video") {
                // A fixed m4a/mp3-only selector frequently fails on Instagram media.
                // Groq can also accept webm/ogg, handled in GroqWhisper below.
                audio = runCatching { download("bestaudio[ext=m4a]/bestaudio[ext=mp3]/bestaudio", "audio") }.getOrNull()
            }
            if ((settings.images == "gemini" || audio == null && settings.transcription) && kind == "video") {
                val video = runCatching { download("best[ext=mp4]/best", "visual") }.getOrNull()
                if (video != null) {
                    // Some Reels only expose a combined MP4 stream, not a standalone audio track.
                    if (audio == null && settings.transcription) audio = video
                    if (settings.images == "gemini") {
                        val frames = runCatching { MediaFrames.sample(video, folder) }.getOrDefault(emptyList())
                        images.addAll(frames)
                        files.addAll(frames)
                    }
                }
            }
        } catch (error: Exception) {
            files.forEach { runCatching { it.delete() } }
            throw error
        }
        return ExtractedContent(title, caption, author, thumbnail, kind, audio, images, ownedFiles = files)
    }
}

/** Optional extraction-only endpoint. Never send provider API keys or login cookies to it. */
object RenderExtractor {
    fun extract(context: Context, url: String, settings: ExtractSettings): ExtractedContent {
        val endpoint = settings.backend.trimEnd('/')
        val uri = java.net.URI(endpoint)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) { "Renseigne une URL HTTPS Render" }
        val connection = URL("$endpoint/extract").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"; connection.connectTimeout = 80000; connection.readTimeout = 120000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            val token = context.getSharedPreferences("settings", 0).getString("ex_backend_token", "").orEmpty()
            if (token.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $token")
            connection.outputStream.use { it.write(JSONObject().put("url", url)
                .put("audio", settings.transcription).put("images", settings.images != "off").toString().toByteArray()) }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            require(code in 200..299) { "Render HTTP $code : ${body.take(160)}" }
            val json = JSONObject(body)
            val owned = mutableListOf<File>()
            fun file(encoded: String, extension: String): File? {
                if (encoded.isBlank()) return null
                require(encoded.length <= 25_000_000) { "Média trop volumineux" }
                val bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
                val output = File.createTempFile("reelmind_", extension, context.cacheDir)
                output.writeBytes(bytes)
                owned.add(output)
                return output
            }
            val audio = file(json.optString("audio_base64"), ".mp3")
            val images = json.optJSONArray("images_base64") ?: JSONArray()
            val imageFiles = (0 until minOf(images.length(), 10)).mapNotNull { file(images.optString(it), ".jpg") }
            return ExtractedContent(json.optString("title"), json.optString("caption"),
                json.optString("author"), json.optString("thumbnail"), json.optString("kind"), audio, imageFiles, owned)
        } finally { connection.disconnect() }
    }
}
