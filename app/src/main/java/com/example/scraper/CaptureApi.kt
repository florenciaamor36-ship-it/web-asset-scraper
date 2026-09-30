package com.example.scraper

import android.content.Context
import com.example.BuildConfig
import com.example.data.ScrapedAsset
import com.example.data.ScrapedSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Android client for the shared browser-backed capture service. */
object CaptureApi {
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    private fun baseUrl(): String? {
        val configured = BuildConfig.CAPTURE_API_BASE_URL.trim().trimEnd('/')
        return configured.ifBlank { null }
    }

    suspend fun capture(rawUrl: String): Pair<ScrapedSession, List<ScrapedAsset>> = withContext(Dispatchers.IO) {
        val serviceBase = baseUrl()
        if (serviceBase == null) return@withContext LocalCapture.capture(rawUrl)
        val requestBody = JSONObject().put("url", rawUrl.trim()).toString().toRequestBody(jsonType)
        val request = Request.Builder()
            .url("${serviceBase}/api/captures")
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException(readError(text, response.code))
            val payload = JSONObject(text)
            val captureId = payload.getString("captureId")
            val jsonAssets = payload.optJSONArray("assets") ?: JSONArray()
            val assets = mutableListOf<ScrapedAsset>()
            for (index in 0 until jsonAssets.length()) {
                val item = jsonAssets.optJSONObject(index) ?: continue
                if (!item.optBoolean("available", false)) continue
                assets += ScrapedAsset(
                    id = item.getString("id"),
                    sessionId = captureId,
                    url = item.getString("url"),
                    fileName = item.optString("fileName", "resource_${index + 1}"),
                    category = item.optString("category", "OTHER"),
                    mimeType = item.optString("mimeType", "application/octet-stream"),
                    sizeBytes = item.optLong("sizeBytes", 0L)
                )
            }
            if (assets.isEmpty()) throw IOException("La página abrió, pero no hubo recursos descargables para guardar.")
            val session = ScrapedSession(
                id = captureId,
                url = payload.optString("url", rawUrl),
                title = payload.optString("title", "Web Page"),
                totalAssets = assets.size,
                totalSizeMB = assets.sumOf { it.sizeBytes } / (1024.0 * 1024.0)
            )
            session to assets
        }
    }

    suspend fun readTextAsset(asset: ScrapedAsset): String = withContext(Dispatchers.IO) {
        val serviceBase = baseUrl()
        if (serviceBase == null) return@withContext LocalCapture.readText(asset.url)
        val request = Request.Builder()
            .url("${serviceBase}/api/captures/${asset.sessionId}/assets/${asset.id}/text")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException(readError(text, response.code))
            text
        }
    }

    suspend fun export(context: Context, session: ScrapedSession, assets: List<ScrapedAsset>): File = withContext(Dispatchers.IO) {
        val serviceBase = baseUrl()
        if (serviceBase == null) return@withContext LocalCapture.export(context, session, assets)
        if (assets.isEmpty()) throw IOException("No hay recursos seleccionados.")
        val payload = JSONObject()
            .put("captureId", session.id)
            .put("assetIds", JSONArray(assets.map { it.id }))
            .toString()
            .toRequestBody(jsonType)
        val request = Request.Builder()
            .url("${serviceBase}/api/exports")
            .post(payload)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val text = response.body?.string().orEmpty()
                throw IOException(readError(text, response.code))
            }
            val contentType = response.header("Content-Type").orEmpty()
            if (!contentType.contains("application/zip", ignoreCase = true)) {
                throw IOException("El servicio no devolvió un ZIP válido.")
            }
            val safeTitle = session.title.replace(Regex("[^A-Za-z0-9_-]"), "_").take(50).ifBlank { "Export" }
            val output = File(context.cacheDir, "WebAssets_${safeTitle}_${System.currentTimeMillis()}.zip")
            val body = response.body ?: throw IOException("El ZIP llegó vacío.")
            FileOutputStream(output).use { file -> body.byteStream().use { input -> input.copyTo(file) } }
            if (output.length() == 0L) {
                output.delete()
                throw IOException("El ZIP llegó vacío.")
            }
            output
        }
    }

    private fun readError(body: String, status: Int): String {
        return try {
            JSONObject(body).optString("detail").ifBlank { "Error del servicio de captura ($status)." }
        } catch (_: Exception) {
            "Error del servicio de captura ($status)."
        }
    }
}
