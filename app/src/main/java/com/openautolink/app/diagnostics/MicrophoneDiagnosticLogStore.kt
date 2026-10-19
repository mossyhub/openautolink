package com.openautolink.app.diagnostics

import android.content.Context
import android.util.AtomicFile
import com.shilapi.xcertplay.media.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Last completed numeric result only. No audio, identifiers, exception text, or automatic upload. */
class MicrophoneDiagnosticLogStore(context: Context) {
    private val app = context.applicationContext
    fun save(result: MicrophoneDiagnosticResult): Boolean = synchronized(writeLock) {
        try {
            val bytes = encode(result).toString().plus("\n").toByteArray(Charsets.UTF_8)
            require(bytes.size <= 32768)
            val base = app.getExternalFilesDir(null) ?: return false
            val directory = File(base, "openautolink/logs")
            check(directory.isDirectory || directory.mkdirs())
            val previous = runs(directory)
            val last = previous.maxOfOrNull { sequence(it) } ?: 0L
            require(last < Long.MAX_VALUE)
            val next = maxOf(System.currentTimeMillis().coerceAtLeast(1), last + 1)
            val run = File(directory, "microphone_diagnostic_run_${next.toString().padStart(19, '0')}.log")
            write(run, bytes)
            previous.sortedByDescending(::sequence).drop(11).forEach { check(it.delete()) }
            write(File(directory, "microphone_diagnostic.log"), bytes)
            true
        } catch (_: Exception) { false }
    }
    private fun write(destination: File, bytes: ByteArray) {
        val file = AtomicFile(destination)
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (failure: Exception) { file.failWrite(stream); throw failure }
    }
    private fun runs(directory: File): List<File> = directory.listFiles().orEmpty()
        .filter { it.isFile && runName.matches(it.name) }
    private fun sequence(file: File): Long = file.name.removePrefix("microphone_diagnostic_run_")
        .removeSuffix(".log").toLongOrNull() ?: 0L

    private fun encode(result: MicrophoneDiagnosticResult): JSONObject {
        require(result.arms.size <= 3 && result.inventory.inputs.size <= 16)
        return JSONObject().put("schema", 1).put("kind", "microphone_diagnostic")
            .put("comparison", result.comparison?.name ?: "INSPECT")
            .put("reason", result.reason.name).put("meaning", "numeric_capture_not_speech_or_phone_acceptance")
            .put("inventory", JSONObject().put("match", result.inventory.match.name)
                .put("inputs", JSONArray().apply { result.inventory.inputs.forEach { c ->
                    put(JSONObject().put("sampleRates", numbers(c.sampleRates))
                        .put("channelMasks", numbers(c.channelMasks)).put("channelIndexMasks", numbers(c.channelIndexMasks))
                        .put("ratesUnconstrained", c.ratesUnconstrained).put("masksUnconstrained", c.masksUnconstrained)
                        .put("indexMasksUnconstrained", c.indexMasksUnconstrained).put("truncated", c.truncated))
                } }))
            .put("arms", JSONArray().apply { result.arms.forEach { a ->
                require(a.ordinal in 0..2)
                put(JSONObject().put("ordinal", a.ordinal).put("requested", shape(a.requested))
                    .put("actual", shape(a.actual)).put("client", shape(a.client)).put("device", shape(a.device))
                    .put("preferredRequested", a.preferredRequested).put("preferredAccepted", a.preferredAccepted ?: JSONObject.NULL)
                    .put("routeMatchesNamedInput", a.routeMatchesNamedInput ?: JSONObject.NULL)
                    .put("routeSameAsFirst", a.routeSameAsFirst ?: JSONObject.NULL).put("routeStable", a.routeStable ?: JSONObject.NULL)
                    .put("statistics", statistics(a.statistics)))
            } })
    }
    private fun numbers(values: List<Int>): JSONArray {
        require(values.size <= 16 && values.all { it in 0..192000 })
        return JSONArray(values)
    }
    private fun shape(value: MicrophoneDiagnosticShape?): Any {
        if (value == null) return JSONObject.NULL
        require(value.source in -1..10 && value.sampleRate in -1..192000 && value.channelCount in -1..8 &&
            value.channelMask in -1..255 && value.channelIndexMask in -1..255)
        return JSONObject().put("source",value.source).put("sampleRate",value.sampleRate)
            .put("channelCount",value.channelCount).put("channelMask",value.channelMask).put("channelIndexMask",value.channelIndexMask)
    }
    private fun level(value: MicrophoneDiagnosticLevel?): Any {
        if (value == null) return JSONObject.NULL
        require(value.samples in 0..144000 && value.nonzero in 0..value.samples && value.peak in 0..65536 &&
            value.rms.isFinite() && value.rms in 0.0..65536.0)
        return JSONObject().put("samples",value.samples).put("nonzero",value.nonzero).put("peak",value.peak).put("rms",value.rms)
    }
    private fun statistics(value: MicrophoneDiagnosticStatistics): JSONObject {
        require(value.frames in 0..144000 && value.nonzeroFrames in 0..value.frames && value.channels.size in 1..2)
        return JSONObject().put("frames",value.frames).put("nonzeroFrames",value.nonzeroFrames)
            .put("channels",JSONArray().apply { value.channels.forEach { put(level(it)) } })
            .put("sum",level(value.sum)).put("difference",level(value.difference)).put("mean",level(value.mean))
    }
    private companion object {
        val writeLock = Any()
        val runName = Regex("microphone_diagnostic_run_[0-9]{19}\\.log")
    }
}
