package com.example.scraper

import android.content.Context
import com.example.data.ScrapedSession

/** Requests a ZIP containing the exact captured response bytes from the shared service. */
object ZipExporter {
    suspend fun exportAssetsToZip(
        context: Context,
        sessionTitle: String,
        assets: List<com.example.data.ScrapedAsset>,
        onProgress: (current: Int, total: Int, fileName: String) -> Unit
    ): java.io.File? {
        if (assets.isEmpty()) return null
        onProgress(0, 1, "Solicitando ZIP con los archivos reales")
        val session = ScrapedSession(
            id = assets.first().sessionId,
            url = "",
            title = sessionTitle,
            totalAssets = assets.size
        )
        val zip = CaptureApi.export(context, session, assets)
        onProgress(1, 1, zip.name)
        return zip
    }
}
