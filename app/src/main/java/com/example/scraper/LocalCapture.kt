package com.example.scraper

import android.content.Context
import com.example.data.ScrapedAsset
import com.example.data.ScrapedSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLConnection
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Local, best-effort scan of public resources directly referenced by a page. */
object LocalCapture {
    private const val MAX_HTML_BYTES = 2 * 1024 * 1024
    private const val MAX_TEXT_BYTES = 1024 * 1024
    private const val MAX_ASSETS = 120
    private const val MAX_FILE_BYTES = 32L * 1024 * 1024
    private const val MAX_ZIP_BYTES = 150L * 1024 * 1024
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()
    private val headClient = client.newBuilder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS)
        .build()

    private data class Candidate(val url: String, val hintedType: String = "")
    private data class Meta(val mime: String, val size: Long)

    suspend fun capture(rawUrl: String): Pair<ScrapedSession, List<ScrapedAsset>> = withContext(Dispatchers.IO) {
        val start = rawUrl.trim().toHttpUrlOrNull() ?: throw IOException("Ingresá una URL HTTP o HTTPS válida.")
        if (start.scheme != "http" && start.scheme != "https") throw IOException("Solo se admiten páginas HTTP o HTTPS.")
        val pageRequest = Request.Builder().url(start).header("User-Agent", UA).get().build()
        var finalUrl = start.toString()
        val html = client.newCall(pageRequest).execute().use { response ->
            if (!response.isSuccessful) throw IOException("No se pudo abrir la página (${response.code}).")
            finalUrl = response.request.url.toString()
            val body = response.body ?: throw IOException("La página llegó vacía.")
            String(body.byteStream().use { readBounded(it, MAX_HTML_BYTES, "La página supera el límite de 2 MB.") }, Charsets.UTF_8)
        }
        val doc = Jsoup.parse(html, finalUrl)
        val candidates = collectCandidates(doc, finalUrl)
        if (candidates.isEmpty()) {
            throw IOException("No encontré recursos públicos enlazados directamente. Los que aparecen solo al ejecutar el juego pueden no detectarse sin un navegador remoto.")
        }
        val semaphore = Semaphore(6)
        val sessionId = UUID.randomUUID().toString()
        val assets = coroutineScope {
            candidates.take(MAX_ASSETS).map { candidate ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val meta = getMeta(candidate)
                        ScrapedAsset(
                            id = UUID.randomUUID().toString(),
                            sessionId = sessionId,
                            url = candidate.url,
                            fileName = fileName(candidate.url),
                            category = category(candidate.url, meta.mime.ifBlank { candidate.hintedType }),
                            mimeType = meta.mime.ifBlank { candidate.hintedType.ifBlank { mime(candidate.url) } },
                            sizeBytes = meta.size
                        )
                    }
                }
            }.awaitAll()
        }
        val title = doc.title().trim().ifBlank { start.host }
        val session = ScrapedSession(
            id = sessionId,
            url = finalUrl,
            title = title,
            totalAssets = assets.size,
            totalSizeMB = assets.sumOf { it.sizeBytes.coerceAtLeast(0) } / (1024.0 * 1024.0)
        )
        session to assets
    }

    suspend fun readText(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", UA).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("No se pudo descargar el recurso (${response.code}).")
            val body = response.body ?: throw IOException("El recurso llegó vacío.")
            String(body.byteStream().use { readBounded(it, MAX_TEXT_BYTES, "El archivo es demasiado grande para previsualizarlo.") }, Charsets.UTF_8)
        }
    }

    suspend fun export(
        context: Context,
        session: ScrapedSession,
        assets: List<ScrapedAsset>,
        onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        if (assets.isEmpty()) throw IOException("No hay recursos seleccionados.")
        val safeTitle = session.title.replace(Regex("[^A-Za-z0-9_-]"), "_").take(50).ifBlank { "Export" }
        val output = File(context.cacheDir, "WebAssets_${safeTitle}_${System.currentTimeMillis()}.zip")
        var downloaded = 0
        var totalBytes = 0L
        val skipped = mutableListOf<String>()
        val names = mutableSetOf<String>()
        try {
            ZipOutputStream(FileOutputStream(output)).use { zip ->
                assets.forEachIndexed { index, asset ->
                    onProgress(index, assets.size, asset.fileName)
                    try {
                        val request = Request.Builder().url(asset.url).header("User-Agent", UA).get().build()
                        client.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                            val body = response.body ?: throw IOException("respuesta vacía")
                            zip.putNextEntry(ZipEntry(uniqueName(fileName(asset.url), names)))
                            body.byteStream().use { input ->
                                val buffer = ByteArray(16 * 1024)
                                var fileBytes = 0L
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    fileBytes += read
                                    totalBytes += read
                                    if (fileBytes > MAX_FILE_BYTES) throw IOException("supera el límite de 32 MB por archivo")
                                    if (totalBytes > MAX_ZIP_BYTES) throw IOException("se alcanzó el límite de 150 MB por ZIP")
                                    zip.write(buffer, 0, read)
                                }
                            }
                            zip.closeEntry()
                            downloaded++
                        }
                    } catch (e: Exception) {
                        runCatching { zip.closeEntry() }
                        skipped += "${asset.fileName}: ${e.localizedMessage ?: "no se pudo descargar"}"
                    }
                }
                if (skipped.isNotEmpty()) {
                    zip.putNextEntry(ZipEntry("_no_descargados.txt"))
                    zip.write(skipped.joinToString("\n").toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
            if (downloaded == 0) throw IOException("No se pudo descargar ningún recurso. El sitio puede bloquear las descargas externas.")
            onProgress(assets.size, assets.size, output.name)
            output
        } catch (e: Exception) {
            output.delete()
            throw e
        }
    }

    private suspend fun collectCandidates(doc: Document, pageUrl: String): List<Candidate> {
        val base = pageUrl.toHttpUrlOrNull() ?: return emptyList()
        val found = linkedMapOf<String, Candidate>()
        fun add(raw: String?, hint: String = "") {
            val value = raw?.trim()?.trim('"', '\'')?.takeIf { it.isNotEmpty() } ?: return
            val resolved = base.resolve(value) ?: return
            if (resolved.scheme != "http" && resolved.scheme != "https") return
            found.putIfAbsent(resolved.toString(), Candidate(resolved.toString(), hint))
        }
        val selector = "img[src], img[data-src], audio[src], video[src], video[poster], source[src], track[src], script[src], iframe[src], embed[src], object[data], link[href]"
        for (element in doc.select(selector)) {
            val tag = element.normalName()
            val rel = element.attr("rel").lowercase(Locale.ROOT).split(Regex("\\s+"))
            if (tag == "link" && rel.none { it in setOf("stylesheet", "icon", "preload", "manifest", "prefetch", "apple-touch-icon") }) continue
            val attr = if (tag == "object") "data" else if (tag == "video" && element.hasAttr("poster")) "poster" else "src"
            add(element.attr(attr), element.attr("type"))
            add(element.attr("data-src"), element.attr("type"))
            if (tag == "link") add(element.attr("href"), element.attr("type"))
            if (element.hasAttr("srcset")) element.attr("srcset").split(',').forEach { add(it.trim().substringBefore(' ')) }
        }
        for (anchor in doc.select("a[href]")) {
            val href = anchor.attr("href")
            if (isAsset(href)) add(href)
        }
        val inlineTexts = mutableListOf<String>()
        doc.select("[style]").forEach { inlineTexts += it.attr("style") }
        doc.select("style").forEach { inlineTexts += it.data() }
        doc.select("script:not([src])").forEach { inlineTexts += it.data() }
        inlineTexts.forEach { text ->
            cssUrls(text).forEach { add(it) }
            audioRefs(text).forEach { add(it) }
        }
        val textFiles = found.values.filter { ext(it.url) in setOf("css", "js", "json") }.take(12)
        for (asset in textFiles) {
            val text = runCatching { readText(asset.url) }.getOrNull().orEmpty()
            if (ext(asset.url) == "css") cssUrls(text).forEach { add(asset.url.toHttpUrlOrNull()?.resolve(it)?.toString()) }
            if (ext(asset.url) in setOf("js", "json")) audioRefs(text).forEach { add(asset.url.toHttpUrlOrNull()?.resolve(it)?.toString()) }
            if (found.size >= MAX_ASSETS) break
        }
        return found.values.take(MAX_ASSETS)
    }

    private suspend fun getMeta(candidate: Candidate): Meta = withContext(Dispatchers.IO) {
        val guessed = candidate.hintedType.ifBlank { mime(candidate.url) }
        try {
            val request = Request.Builder().url(candidate.url).header("User-Agent", UA).head().build()
            headClient.newCall(request).execute().use { response ->
                Meta(response.header("Content-Type")?.substringBefore(';')?.trim().orEmpty().ifBlank { guessed }, response.header("Content-Length")?.toLongOrNull() ?: 0L)
            }
        } catch (_: Exception) {
            Meta(guessed, 0L)
        }
    }

    private fun readBounded(input: java.io.InputStream, limit: Int, error: String): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw IOException(error)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun cssUrls(text: String): List<String> =
        Regex("""url\(\s*['\"]?([^'\")]+)['\"]?\s*\)""", RegexOption.IGNORE_CASE)
            .findAll(text).map { it.groupValues[1].trim() }.filter { it.isNotBlank() }.toList()

    private fun audioRefs(text: String): List<String> =
        Regex("""['\"]([^'\"\s<>]{1,300}\.(?:mp3|ogg|opus|wav|m4a|aac|flac)(?:[?#][^'\"\s<>]*)?)['\"]""", RegexOption.IGNORE_CASE)
            .findAll(text).map { it.groupValues[1] }.distinct().take(300).toList()

    private fun isAsset(url: String): Boolean = ext(url) in setOf(
        "mp3", "ogg", "opus", "wav", "m4a", "aac", "flac", "mp4", "webm", "mov", "m3u8", "mpd",
        "png", "jpg", "jpeg", "gif", "webp", "avif", "svg", "ico", "css", "js", "json", "woff", "woff2", "ttf", "otf", "pdf", "zip"
    )

    private fun ext(url: String): String = url.substringBefore('#').substringBefore('?').substringAfterLast('.', "").lowercase(Locale.ROOT)

    private fun mime(url: String): String = URLConnection.guessContentTypeFromName(url.substringBefore('?')) ?: when (ext(url)) {
        "mp3" -> "audio/mpeg"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "flac" -> "audio/flac"
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "m3u8" -> "application/vnd.apple.mpegurl"
        "mpd" -> "application/dash+xml"
        "svg" -> "image/svg+xml"
        "woff2" -> "font/woff2"
        "woff" -> "font/woff"
        "js" -> "text/javascript"
        "css" -> "text/css"
        "json" -> "application/json"
        else -> "application/octet-stream"
    }

    private fun category(url: String, contentType: String): String {
        val type = contentType.lowercase(Locale.ROOT)
        return when {
            type.startsWith("image/") || ext(url) in setOf("png", "jpg", "jpeg", "gif", "webp", "avif", "svg", "ico") -> "IMAGE"
            type.startsWith("audio/") || ext(url) in setOf("mp3", "ogg", "opus", "wav", "m4a", "aac", "flac") -> "AUDIO"
            type.startsWith("video/") || ext(url) in setOf("mp4", "webm", "mov", "m3u8", "mpd") -> "VIDEO"
            type.contains("javascript") || ext(url) == "js" -> "SCRIPT"
            type.contains("css") || ext(url) == "css" -> "STYLE"
            type.contains("font") || ext(url) in setOf("woff", "woff2", "ttf", "otf") -> "FONT"
            type.contains("json") || ext(url) == "json" -> "DATA"
            else -> "OTHER"
        }
    }

    private fun fileName(url: String): String = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        .replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "resource.bin" }

    private fun uniqueName(name: String, used: MutableSet<String>): String {
        if (used.add(name)) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val suffix = if (dot > 0) name.substring(dot) else ""
        var index = 2
        while (!used.add("${stem}_$index$suffix")) index++
        return "${stem}_$index$suffix"
    }
}
