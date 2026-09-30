package com.example.scraper

import androidx.test.core.app.ApplicationProvider
import com.example.data.ScrapedSession
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocalCaptureTest {
    @Test
    fun staticallyLinkedImageAndAudioAreDetectedAndExportedToZip() = runBlocking {
        FixtureServer().use { fixture ->
            val (session, assets) = LocalCapture.capture(fixture.url("/page"))

            assertEquals("Static fixture", session.title)
            assertEquals(setOf("cover.png", "sample.mp3"), assets.map { it.fileName }.toSet())
            assertEquals(setOf("IMAGE", "AUDIO"), assets.map { it.category }.toSet())

            val zipFile = LocalCapture.export(
                ApplicationProvider.getApplicationContext(),
                session,
                assets
            )
            try {
                ZipFile(zipFile).use { zip ->
                    assertEquals(setOf("cover.png", "sample.mp3"), zip.entries().asSequence().map { it.name }.toSet())
                    assertArrayEquals(FixtureServer.PNG, zip.getInputStream(zip.getEntry("cover.png")).use { it.readBytes() })
                    assertArrayEquals(FixtureServer.MP3, zip.getInputStream(zip.getEntry("sample.mp3")).use { it.readBytes() })
                }
            } finally {
                zipFile.delete()
            }
        }
    }

    private class FixtureServer : Closeable {
        private val resources = mapOf(
            "/page" to Resource("text/html; charset=utf-8", """
                <!doctype html><html><head><title>Static fixture</title></head>
                <body><img src="/assets/cover.png"><audio src="/assets/sample.mp3"></audio></body></html>
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)),
            "/assets/cover.png" to Resource("image/png", PNG),
            "/assets/sample.mp3" to Resource("audio/mpeg", MP3)
        )
        private val server = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        private val workers = Executors.newCachedThreadPool()
        @Volatile private var stopped = false
        private val acceptor = Thread {
            while (!stopped) {
                try {
                    val socket = server.accept()
                    workers.submit { serve(socket) }
                } catch (_: SocketException) {
                    if (!stopped) throw IllegalStateException("Fixture server stopped unexpectedly")
                }
            }
        }.apply { isDaemon = true; start() }

        fun url(path: String) = "http://127.0.0.1:${server.localPort}$path"

        private fun serve(socket: java.net.Socket) {
            socket.use { connection ->
                connection.soTimeout = 5_000
                val reader = BufferedReader(InputStreamReader(connection.getInputStream(), StandardCharsets.ISO_8859_1))
                val request = reader.readLine()?.split(' ') ?: return
                while (reader.readLine()?.isNotEmpty() == true) { /* consume request headers */ }
                val resource = resources[request.getOrNull(1)]
                val status = if (resource == null) "404 Not Found" else "200 OK"
                val body = resource?.bytes ?: ByteArray(0)
                val headers = buildString {
                    append("HTTP/1.1 $status\r\n")
                    append("Content-Type: ${resource?.mime ?: "text/plain"}\r\n")
                    append("Content-Length: ${body.size}\r\n")
                    append("Connection: close\r\n\r\n")
                }.toByteArray(StandardCharsets.ISO_8859_1)
                val output = connection.getOutputStream()
                output.write(headers)
                if (request.firstOrNull() != "HEAD") output.write(body)
                output.flush()
            }
        }

        override fun close() {
            stopped = true
            server.close()
            workers.shutdownNow()
            acceptor.join(1_000)
        }

        private data class Resource(val mime: String, val bytes: ByteArray)

        companion object {
            val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3)
            val MP3 = "fixture-audio-bytes".toByteArray(StandardCharsets.UTF_8)
        }
    }
}
