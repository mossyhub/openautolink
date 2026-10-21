package com.openautolink.app.diagnostics

import android.app.Application
import android.content.Context
import com.shilapi.xcertplay.media.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [32])
@ConscryptMode(ConscryptMode.Mode.OFF)
class MicrophoneDiagnosticLogStoreTest {
    private fun save(context: Context, result: MicrophoneDiagnosticResult): Boolean {
        val type = try { Class.forName("com.openautolink.app.diagnostics.MicrophoneDiagnosticLogStore") }
        catch (_: ClassNotFoundException) { fail("completed numeric diagnostic must reach manual upload"); throw AssertionError() }
        val store = type.getConstructor(Context::class.java).newInstance(context)
        return type.getMethod("save", MicrophoneDiagnosticResult::class.java).invoke(store, result) as Boolean
    }
    private fun fixture(): MicrophoneDiagnosticResult {
        val shape = MicrophoneDiagnosticShape(6, 24000, 1, 16, 0)
        return MicrophoneDiagnosticResult(MicrophoneDiagnosticComparison.CHANNELS,
            MicrophoneDiagnosticInventory(MicrophoneDiagnosticMatch.UNIQUE,
                listOf(MicrophoneDiagnosticCapabilities(listOf(24000,48000),listOf(16,12),emptyList(),false,false,true,false))),
            listOf(MicrophoneDiagnosticArm(0,shape,shape,false,null,true,true,
                MicrophoneDiagnosticStatistics(2,1,listOf(MicrophoneDiagnosticLevel(2,1,100,70.0)),null,null,null),
                shape,shape.copy(channelCount=2,channelMask=12),true)),MicrophoneDiagnosticReason.CANCELLED)
    }
    @Test fun sequentialComparisonsSurviveTogetherInManualUploadAndRetentionIsBounded() {
        val app = RuntimeEnvironment.getApplication()
        val result = fixture()
        val dir = File(app.getExternalFilesDir(null), "openautolink/logs")
        assertTrue(save(app, result))
        val first = dir.listFiles()!!.filter { it.name.startsWith("microphone_diagnostic_run_") && it.extension == "log" }
        assertEquals("Each completed comparison needs an independent summary", 1, first.size)
        val firstBytes = first.single().readBytes()
        assertTrue(save(app, result.copy(comparison = MicrophoneDiagnosticComparison.RATE)))
        assertTrue(save(app, result.copy(comparison = MicrophoneDiagnosticComparison.PREFERRED)))
        val bytes = ByteArrayOutputStream()
        val connection = object: HttpURLConnection(URL("https://example.invalid/upload")) {
            override fun disconnect() {}
            override fun usingProxy() = false
            override fun connect() {}
            override fun getOutputStream() = bytes
            override fun getResponseCode() = 200
        }
        assertTrue(LogUploader(app, { connection }, { true }).upload("https://example.invalid/upload", "fixture", "fixture") is UploadResult.Success)
        val members = mutableMapOf<String,ByteArray>()
        ZipInputStream(bytes.toByteArray().inputStream()).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; members[entry.name] = zip.readBytes() }
        }
        val runs = members.filterKeys { it.startsWith("microphone_diagnostic_run_") }
        assertEquals(3, runs.size)
        assertEquals(setOf("CHANNELS", "RATE", "PREFERRED"), runs.values.map { JSONObject(it.toString(Charsets.UTF_8)).getString("comparison") }.toSet())
        assertArrayEquals(firstBytes, members[first.single().name])
        repeat(12) { assertTrue(save(app, result.copy(comparison = null))) }
        val retained = dir.listFiles()!!.filter { it.name.startsWith("microphone_diagnostic_run_") && it.extension == "log" }
        assertEquals(12, retained.size)
        assertFalse(first.single().exists())
        assertTrue(retained.all { it.length() <= 32768 && JSONObject(it.readText()).getString("comparison") == "INSPECT" })
    }

    @Test fun invalidOrOversizedNumericResultDoesNotReplaceLastGoodSummary() {
        val app = RuntimeEnvironment.getApplication()
        val good = fixture()
        assertTrue(save(app, good))
        val file = File(app.getExternalFilesDir(null), "openautolink/logs/microphone_diagnostic.log")
        val before = file.readBytes()
        val arm = good.arms.single()
        val invalid = arm.copy(statistics = arm.statistics.copy(channels =
            listOf(MicrophoneDiagnosticLevel(2, 1, 100, Double.NaN))))
        assertFalse(save(app, good.copy(arms = listOf(invalid))))
        assertFalse(save(app, good.copy(arms = List(4) { arm })))
        assertFalse(save(app, good.copy(inventory = good.inventory.copy(inputs = List(17) { good.inventory.inputs.single() }))))
        assertArrayEquals(before, file.readBytes())
    }
    @Test fun completedNumericResultTravelsThroughActualManualUploaderWithoutAudio() {
        val app = RuntimeEnvironment.getApplication()
        val result = fixture()
        assertTrue(save(app,result))
        val file = File(app.getExternalFilesDir(null),"openautolink/logs/microphone_diagnostic.log")
        val expected = file.readBytes()
        val json = JSONObject(expected.toString(Charsets.UTF_8))
        assertEquals("CANCELLED",json.getString("reason"))
        assertEquals(2,json.getJSONArray("arms").getJSONObject(0).getJSONObject("device").getInt("channelCount"))
        assertEquals(1,json.getJSONArray("arms").getJSONObject(0).getJSONObject("client").getInt("channelCount"))
        assertTrue(expected.size <= 32768)
        assertFalse(expected.toString(Charsets.UTF_8).contains("Call_In_Mic"))
        val bytes = ByteArrayOutputStream()
        val connection = object: HttpURLConnection(URL("https://example.invalid/upload")) {
            override fun disconnect() {}
            override fun usingProxy() = false
            override fun connect() {}
            override fun getOutputStream() = bytes
            override fun getResponseCode() = 200
        }
        val uploaded = LogUploader(app,{connection},{true}).upload("https://example.invalid/upload","test-fixture","fixture")
        assertTrue(uploaded is UploadResult.Success)
        val members = mutableMapOf<String,ByteArray>()
        ZipInputStream(bytes.toByteArray().inputStream()).use { zip ->
            while(true) { val entry=zip.nextEntry ?: break; members[entry.name]=zip.readBytes() }
        }
        val immutableRuns = members.filterKeys { it.startsWith("microphone_diagnostic_run_") }
        assertEquals(1, immutableRuns.size)
        assertArrayEquals(expected,immutableRuns.values.single())
        assertFalse("Latest alias must not count as another completed run",members.containsKey("microphone_diagnostic.log"))
        assertTrue(file.exists())
    }
}
