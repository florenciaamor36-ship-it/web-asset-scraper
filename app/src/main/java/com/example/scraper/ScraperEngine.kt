package com.example.scraper

import com.example.data.ScrapedAsset
import com.example.data.ScrapedSession

/** Uses the same rendered-page capture engine as the web version. */
object ScraperEngine {
    suspend fun scrapeUrl(rawUrl: String): Pair<ScrapedSession, List<ScrapedAsset>> {
        require(rawUrl.isNotBlank()) { "Ingresá una URL válida." }
        return CaptureApi.capture(rawUrl)
    }
}
