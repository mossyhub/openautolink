package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class AudioOfferDiagnosticsTest {
    @Suppress("UNCHECKED_CAST")
    private fun lines(info: Map<String, Any?>): List<String> {
        val type = try { Class.forName("com.shilapi.xcertplay.airplay.AudioOfferDiagnostics") }
            catch (_: ClassNotFoundException) { return emptyList() }
        return type.getMethod("lines", Map::class.java)
            .invoke(type.getField("INSTANCE").get(null), info) as List<String>
    }

    @Test fun infoHandlerExtractsFromTheSameObjectThatItEncodes() {
        val source = java.io.File("src/main/java/com/shilapi/xcertplay/airplay/AirPlaySession.kt").readText()
        val handler = source.substringAfter("path.endsWith(\"/info\") -> {").substringBefore("request.method == \"POST\"")
        assertTrue(handler.contains("AudioOfferDiagnostics.lines(info).forEach(listener::onDebugLog)"))
        assertTrue(handler.contains("body = BplistCodec.encode(info)"))
        assertFalse(handler.contains("onDebugLog(info.toString()"))
    }

    @Test fun hostileRowsCannotExportSecretsOrCoerceUntrustedNumbers() {
        val valid = mapOf("type" to 100, "audioType" to "default", "audioOutputFormats" to 1L)
        val hostile = listOf(
            valid + ("audioType" to "secretSSID"), valid + ("audioType" to "default\npassphrase=secret"),
            valid + ("audioOutputFormats" to "private-key"), valid + ("audioOutputFormats" to -1L),
            valid + ("audioOutputFormats" to 1.75), valid + ("audioOutputFormats" to Double.NaN),
            valid + ("type" to 100.5), valid + ("type" to 9999),
            valid + ("audioInputFormats" to "192.168.1.1"), valid + ("audioInputFormats" to null),
            valid + ("audioOutputFormats" to object : Number() {
                override fun toLong(): Long = error("untrusted conversion invoked")
                override fun toByte(): Byte = error("untrusted")
                override fun toShort(): Short = error("untrusted")
                override fun toInt(): Int = error("untrusted")
                override fun toFloat(): Float = error("untrusted")
                override fun toDouble(): Double = error("untrusted")
            })
        )
        assertTrue(lines(mapOf("audioFormats" to hostile)).isEmpty())
        assertEquals(listOf("BENCH_DIAG audioOfferRow index=0 type=100 audioType=default output=1 input=absent"),
            lines(mapOf("audioFormats" to listOf(valid + mapOf("privateKey" to "secret", "deviceID" to "private")),
                "SSID" to "secretSSID")))
    }

    @Test fun extractionHasAHardRowBudgetAndPreservesSourceIndexes() {
        val row = mapOf("type" to 102, "audioType" to "media", "audioOutputFormats" to Long.MAX_VALUE,
            "audioInputFormats" to 0L)
        val actual = lines(mapOf("audioFormats" to listOf("malformed") + List(1000) { row }))
        assertEquals(15, actual.size)
        assertTrue(actual.first().contains("index=1 "))
        assertTrue(actual.last().contains("index=15 "))
        assertTrue(actual.all { it.length <= 384 && it.endsWith("input=0") })
        assertTrue(lines(emptyMap()).isEmpty())
    }

    @Test fun extractsTheActualOfferedRowsRatherThanRebuildingTheCatalog() {
        val info = mapOf("audioFormats" to listOf(
            mapOf("type" to 102, "audioType" to "media", "audioOutputFormats" to 8388608L),
            mapOf("type" to 100L, "audioType" to "speechRecognition", "audioOutputFormats" to 536870912L,
                "audioInputFormats" to 536870912L)))
        assertEquals(listOf(
            "BENCH_DIAG audioOfferRow index=0 type=102 audioType=media output=8388608 input=absent",
            "BENCH_DIAG audioOfferRow index=1 type=100 audioType=speechRecognition output=536870912 input=536870912"
        ), lines(info))
    }
}
