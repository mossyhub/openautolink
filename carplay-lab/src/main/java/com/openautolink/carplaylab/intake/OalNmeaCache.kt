package com.openautolink.carplaylab.intake

/** Constant-space host NMEA cache. Never synthesizes fix, satellites, altitude or velocity. */
class OalNmeaCache(private val elapsedMs: () -> Long) {
    private class Sample(val sentence: String, val receivedMs: Long)
    private val samples = mutableMapOf<Int, Sample>()

    @Synchronized fun accept(sentence: String): Boolean {
        if (sentence.length > 132) return false
        val text = sentence.trimEnd('\r', '\n')
        val selector = when (text.substringBefore(',').takeLast(3)) { "GGA" -> 1; "RMC" -> 2; else -> return false }
        samples.remove(selector) // A newer invalid fix revokes the retained valid fix.
        if (text.length !in 10..128 || text.any { it.code !in 32..126 } ||
            !text.startsWith("$") || text.substring(1,3) !in setOf("GP", "GN", "GL")) return false
        val star = text.indexOf('*')
        if (star != text.length - 3) return false
        val expected = text.substring(star + 1).toIntOrNull(16) ?: return false
        if (text.substring(1,star).fold(0) { a, c -> a xor c.code } != expected) return false
        val fields = text.substring(1,star).split(',')
        val latIndex = if (selector == 1) 2 else 3
        if (fields.size <= latIndex + 3) return false
        if (!coordinate(fields[latIndex], fields[latIndex + 1], true) ||
            !coordinate(fields[latIndex + 2], fields[latIndex + 3], false)) return false
        if (selector == 1 && (fields.getOrNull(6)?.toIntOrNull() ?: 0) !in 1..8) return false
        if (selector == 2 && fields.getOrNull(2) != "A") return false
        val time = fields.getOrNull(1) ?: return false
        if (!time.matches(Regex("[0-9]{6}(\\.[0-9]+)?")) ||
            time.take(2).toInt() !in 0..23 || time.substring(2,4).toInt() !in 0..59 ||
            time.substring(4,6).toInt() !in 0..60) return false
        samples[selector] = Sample(text, elapsedMs())
        return true
    }

    private fun coordinate(value: String, hemisphere: String, latitude: Boolean): Boolean {
        val degreesLength = if (latitude) 2 else 3
        if (!value.matches(Regex("[0-9]{${degreesLength + 2}}[.][0-9]+"))) return false
        val degrees = value.take(degreesLength).toInt()
        val minutes = value.drop(degreesLength).toDoubleOrNull() ?: return false
        val limit = if (latitude) 90 else 180
        return degrees <= limit && minutes < 60 && (degrees < limit || minutes == 0.0) &&
            hemisphere in (if (latitude) setOf("N", "S") else setOf("E", "W"))
    }

    @Synchronized fun latest(requested: Set<Int>): String? {
        val now = elapsedMs()
        return listOf(1,2).filter { it in requested }.mapNotNull { id ->
            samples[id]?.takeIf { now >= it.receivedMs && now - it.receivedMs <= 3000 }?.sentence
        }.takeIf { it.isNotEmpty() }?.joinToString("\r\n", postfix = "\r\n")
    }
}
