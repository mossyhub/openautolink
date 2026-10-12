package com.openautolink.app.ui.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source wiring contracts; rendered Compose/device proof is a separate gate. */
class SupportOpenAutoLinkTest {
    @Test
    fun `support card is below global controls on the initial Connection page only`() {
        val settings = projectFile("app/src/main/java/com/openautolink/app/ui/settings/SettingsScreen.kt").readText()
        assertTrue(settings.contains("mutableStateOf(SettingsTab.CONNECTION)"))
        val screen = settings.substringBefore("private fun ConnectionStatusBar")
        val controls = screen.indexOf("ConnectionStatusBar(")
        val support = screen.indexOf("SupportOpenAutoLink(")
        val tabs = screen.indexOf("when (selectedTab)")
        assertTrue("Support card must follow main controls and precede tab content", support > controls && support < tabs)
        assertTrue(screen.substring(controls, support).contains("if (selectedTab == SettingsTab.CONNECTION)"))
        assertEquals(1, Regex("SupportOpenAutoLink\\(").findAll(settings).count())
    }

    @Test
    fun `scan action opens only a dismissible local dialog without touching projection`() {
        val support = projectFile("app/src/main/java/com/openautolink/app/ui/settings/SupportOpenAutoLink.kt").readText()
        assertTrue("Dialog must start closed", support.contains("remember { mutableStateOf(false) }"))
        assertTrue(support.contains("onClick = { showQr = true }"))
        assertTrue(support.contains("if (showQr)"))
        assertTrue(support.contains("onDismissRequest = { showQr = false }"))
        assertTrue(support.contains("onClose = { showQr = false }"))
        listOf("Scan with your phone", "Scan while parked", "Close", "buymeacoffee.com/mossyhub").forEach {
            assertTrue("Missing readable copy: $it", support.contains(it))
        }
        listOf("Intent", "startActivity", "LocalUriHandler", "launchUrl", "LaunchedEffect", "WebView",
            "SettingsViewModel", "SessionManager", "onSaveAndConnect", "FloatingActionButton", "http://").forEach {
            assertFalse("Support UI must not launch external apps or change session/settings: $it", support.contains(it))
        }
    }

    @Test
    fun `static QR is packaged without density scaling and keeps a four module white quiet zone`() {
        val support = projectFile("app/src/main/java/com/openautolink/app/ui/settings/SupportOpenAutoLink.kt").readText()
        assertTrue("QR must use a bundled image", support.contains("R.drawable.support_open_auto_link_qr"))
        assertTrue(support.contains("Color.White"))
        assertTrue(support.contains("ContentScale.Fit"))
        val asset = projectFile("app/src/main/res/drawable-nodpi/support_open_auto_link_qr.png")
        // This exact asset was independently decoded with OpenCV at 592/320/280/240/192/160px.
        // Pinning the bytes keeps that verified URL from silently changing without a new decode gate.
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(asset.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals("a517f6ca568a1e3f74df75d01ed876bde959ce90caeab5f9546549bdab4d61c1", digest)
        val png = java.io.DataInputStream(asset.inputStream())
        val compressed = java.io.ByteArrayOutputStream()
        png.use {
            assertEquals(-8552249625308161526L, it.readLong()) // PNG signature
            var ended = false
            while (!ended) {
                val size = it.readInt()
                val type = ByteArray(4).also(it::readFully).toString(Charsets.US_ASCII)
                val bytes = ByteArray(size).also(it::readFully)
                it.readInt() // CRC is also validated by the independent real QR decoder.
                when (type) {
                    "IHDR" -> {
                        val header = java.nio.ByteBuffer.wrap(bytes)
                        assertEquals(592, header.int)
                        assertEquals(592, header.int)
                        assertEquals(8, header.get().toInt()) // grayscale, eight bits per pixel
                        assertEquals(0, header.get().toInt())
                    }
                    "IDAT" -> compressed.write(bytes)
                    "IEND" -> ended = true
                }
            }
        }
        val raster = java.util.zip.InflaterInputStream(compressed.toByteArray().inputStream()).use { it.readBytes() }
        assertEquals(592 * 593, raster.size)
        var blackPixels = 0
        for (y in 0 until 592) {
            assertEquals("Asset uses lossless unfiltered rows", 0, raster[y * 593].toInt())
            for (x in 0 until 592) {
                val pixel = raster[y * 593 + x + 1].toInt() and 255
                assertTrue("QR must be black/white", pixel == 255 || pixel == 0)
                if (x < 64 || y < 64 || x >= 528 || y >= 528) {
                    assertEquals("Four modules of quiet zone must remain white", 255, pixel)
                }
                if (pixel == 0) blackPixels++
            }
        }
        assertTrue("QR may not be a blank placeholder", blackPixels > 0)
    }

    @Test
    fun `dialog adapts to available width and height while keeping Close outside scrolling content`() {
        val support = projectFile("app/src/main/java/com/openautolink/app/ui/settings/SupportOpenAutoLink.kt").readText()
        assertTrue(support.contains("usePlatformDefaultWidth = false"))
        assertTrue(support.contains("maxWidth >= 560.dp"))
        assertTrue(support.contains("maxHeight"))
        assertTrue(support.contains("verticalScroll(rememberScrollState())"))
        assertTrue(support.contains("widthIn(max = 760.dp)"))
        assertTrue(support.contains("supportClose"))
    }

    private fun projectFile(path: String): File {
        var dir = File(System.getProperty("user.dir") ?: error("user.dir unavailable"))
        repeat(8) {
            val candidate = File(dir, path)
            if (candidate.isFile) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        error("Cannot locate project file: $path")
    }
}
