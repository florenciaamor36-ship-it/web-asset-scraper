package com.example.scraper

import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/** Records HTTP(S) resource URLs seen by an in-app WebView without changing its requests. */
class RuntimeRequestRecorder(private val maxUrls: Int = 2_000) {
    private val urls = ConcurrentHashMap.newKeySet<String>()

    fun record(rawUrl: String) {
        if (urls.size >= maxUrls) return
        val normalized = runCatching {
            val uri = URI(rawUrl)
            val scheme = uri.scheme?.lowercase()
            if ((scheme != "http" && scheme != "https") || uri.host.isNullOrBlank() || uri.userInfo != null) {
                null
            } else {
                uri.normalize().toASCIIString().substringBefore('#')
            }
        }.getOrNull() ?: return
        if (urls.size < maxUrls) urls.add(normalized)
    }

    fun snapshot(): List<String> = urls.toList().sorted()

    fun size(): Int = urls.size
}
