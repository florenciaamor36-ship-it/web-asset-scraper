package com.example.scraper

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeRequestRecorderTest {
    @Test
    fun recordsOnlyUniqueHttpUrlsAndHonorsLimit() {
        val recorder = RuntimeRequestRecorder(maxUrls = 2)
        recorder.record("https://cdn.example.test/sound.mp3#fragment")
        recorder.record("https://cdn.example.test/sound.mp3#another-fragment")
        recorder.record("javascript:alert(1)")
        recorder.record("file:///private/data")
        recorder.record("https://cdn.example.test/image.png")
        recorder.record("https://cdn.example.test/extra.ogg")

        assertEquals(2, recorder.size())
        assertEquals(
            listOf("https://cdn.example.test/image.png", "https://cdn.example.test/sound.mp3"),
            recorder.snapshot()
        )
    }
}
