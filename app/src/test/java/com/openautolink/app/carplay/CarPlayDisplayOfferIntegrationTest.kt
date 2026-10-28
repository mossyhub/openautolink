package com.openautolink.app.carplay

import android.content.Context
import android.hardware.usb.UsbDevice
import android.view.Surface
import com.openautolink.app.diagnostics.DiagnosticLog
import com.openautolink.app.diagnostics.LogUploader
import com.openautolink.app.diagnostics.OalLog
import com.openautolink.app.diagnostics.UploadResult
import com.openautolink.carplaylab.media.CarPlayDisplayPreferences
import com.openautolink.carplaylab.media.CarPlayResolution
import com.openautolink.carplaylab.media.ProjectionReceiver
import io.mockk.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Actual runtime receiver-publication -> reporter/sanitizer -> manual uploader ZIP path.
 * Receiver construction/Android services are boundaries; no phone rendering claim. */
class CarPlayDisplayOfferIntegrationTest {
    @get:Rule val temp = TemporaryFolder()
    @After fun cleanup() = unmockkAll()

    private fun processStore(): Any {
        val type = runCatching { Class.forName("com.openautolink.app.carplay.CarPlayDisplayOfferStore") }.getOrNull()
        assertNotNull("qualified runtime offers need a process observation owner", type)
        return type!!.getField("PROCESS").get(null)
    }
    private fun offer(store: Any): Any? {
        val flow = store.javaClass.getMethod("getState").invoke(store) as StateFlow<*>
        return flow.value
    }
    private fun property(value: Any, name: String): Any? = value.javaClass.getMethod("get" + name.replaceFirstChar { it.uppercase() }).invoke(value)

    @Test fun exactRuntimeOwnersFenceLatePublicationAndFailedStartupRetiresObservation() {
        fun receiver(width: Int) = mockk<ProjectionReceiver>(relaxed = true).also {
            every { it.displayConfiguration } returns CarPlayDisplayPreferences(widthPhysicalMm = width).freezeScale()
            every { it.awaitClosed(10_000) } returns true
        }
        fun install(runtime: CarPlayRuntime, receiver: ProjectionReceiver) {
            runtime.javaClass.declaredMethods.single { it.name == "ownReceiver" }.apply { isAccessible = true }
                .invoke(runtime, { receiver })
        }
        mockkObject(DiagnosticLog)
        every { DiagnosticLog.i(any(), any()) } returns Unit
        listOf(false, true).forEach { usb ->
            val store = CarPlayDisplayOfferStore()
            var callbacks = 0
            val context = mockk<Context>(relaxed = true)
            every { context.checkSelfPermission(any()) } returns android.content.pm.PackageManager.PERMISSION_DENIED
            fun runtime() = if (usb) OalUsbCarPlayRuntime(context, mockk<UsbDevice>(relaxed = true),
                CarPlayDisplayPreferences(), mockk<Surface>(), {}, onDisplayQualified = { callbacks++ }, displayOfferStore = store)
            else OalCarPlayRuntime(context, CarPlayRuntimeConfig("", "", "", false, "", "", 0, CarPlayDisplayPreferences()),
                mockk<Surface>(), {}, onDisplayQualified = { callbacks++ }, displayOfferStore = store)
            val a = runtime()
            install(a, receiver(200))
            val b = runtime()
            try {
                install(b, receiver(225))
                val successor = store.state.value
                // An older real runtime can finish receiver construction after B owns observation.
                install(a, receiver(100))
                a.cancel(); a.close()
                assertSame(successor, store.state.value)
                assertEquals(225, store.state.value!!.referenceWidthMm)
                assertEquals(2, callbacks)
            } finally { a.close(); b.close() }
            val failed = runtime()
            try {
                assertTrue("Actual startup must fail before qualification", runCatching { failed.run() }.isFailure)
                install(failed, receiver(400))
                assertEquals("Failed attempt's late offer is no longer authorized", 225, store.state.value!!.referenceWidthMm)
                assertEquals(CarPlayDisplayOfferStore.Phase.ENDED, store.state.value!!.phase)
                assertEquals(2, callbacks)
            } finally { failed.close() }
        }
    }

    @Test fun bothRuntimePublicationPathsRetainActualFrozenOfferAndExportOnlyFiniteV2() {
        val store = processStore()
        val base = temp.newFolder("volume")
        val logs = File(base, "openautolink/logs").apply { mkdirs() }
        val source = File(logs, "oal_display.log")
        val context = mockk<Context>(relaxed = true)
        every { context.getExternalFilesDirs(null) } returns arrayOf(base)
        every { context.cacheDir } returns temp.newFolder("cache")
        mockkObject(DiagnosticLog, OalLog)
        every { DiagnosticLog.i(any(), any()) } answers { source.appendText(secondArg<String>() + "\n") }
        every { OalLog.i(any(), any()) } returns Unit
        every { OalLog.w(any(), any()) } returns Unit
        every { OalLog.e(any(), any()) } returns Unit
        listOf(false, true).forEach { usb ->
            val requested = CarPlayDisplayPreferences(widthPhysicalMm = 225)
            val actual = CarPlayDisplayPreferences(resolution = CarPlayResolution.CUSTOM, customWidth = 1920,
                customHeight = 750, widthPhysicalMm = 200,
                autoScaleInput = if (usb) com.openautolink.carplaylab.media.CarPlayAutoScalePolicy.Input(
                    1920, 750, 1920, 750, Double.NaN, Double.POSITIVE_INFINITY, Int.MAX_VALUE) else null,
                scaleMode = com.openautolink.carplaylab.media.CarPlayScaleReceipt.Mode.AUTOMATIC).freezeScale()
            val receiver = mockk<ProjectionReceiver>(relaxed = true)
            every { receiver.displayConfiguration } returns actual
            every { receiver.awaitClosed(10_000) } returns true
            var callbacks = 0
            val runtime: CarPlayRuntime = if (usb) OalUsbCarPlayRuntime(context, mockk<UsbDevice>(relaxed = true), requested,
                mockk<Surface>(), {}, onDisplayQualified = { assertSame(actual, it); callbacks++; error("observer failure") })
            else OalCarPlayRuntime(context, CarPlayRuntimeConfig("", "", "", false, "", "", 0, requested),
                mockk<Surface>(), {}, onDisplayQualified = { assertSame(actual, it); callbacks++; error("observer failure") })
            try {
                val install = runtime.javaClass.declaredMethods.single { it.name == "ownReceiver" }.apply { isAccessible = true }
                install.invoke(runtime, { receiver })
                val frozen = requireNotNull(offer(store))
                assertEquals(200, property(frozen, "referenceWidthMm"))
                assertEquals(200, property(frozen, "effectiveWidthMm"))
                assertEquals(78, property(frozen, "effectiveHeightMm"))
                assertEquals("OFFERED", property(frozen, "phase").toString())
                assertEquals(if (usb) "NONFINITE_METRICS" else "MISSING_VIEWPORT", property(frozen, "reason").toString())
                assertEquals(1, callbacks)
                verify(exactly = 1) { receiver.displayConfiguration }
            } finally { runtime.close() }
            assertEquals("ENDED", property(requireNotNull(offer(store)), "phase").toString())
        }
        val wire = ByteArrayOutputStream()
        val connection = object : HttpURLConnection(URL("https://logs.example.test/upload")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() = Unit
            override fun getOutputStream() = wire
            override fun getResponseCode() = 200
        }
        assertTrue(LogUploader(context, { connection }, { true }).upload("https://logs.example.test/upload", "fixture-token", "fixture") is UploadResult.Success)
        val members = mutableMapOf<String, String>()
        ZipInputStream(wire.toByteArray().inputStream()).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; members[entry.name] = zip.readBytes().toString(Charsets.UTF_8) }
        }
        val exported = members.getValue(source.name)
        assertEquals(2, exported.lineSequence().count { it.contains("BENCH_DIAG displayScaleV2 ") })
        assertTrue(exported.contains("mode=auto reason=missing_viewport reference=200 physical=200x78"))
        assertTrue(exported.contains("mode=auto reason=nonfinite_metrics reference=200 physical=200x78"))
        listOf("NaN", "Infinity", "fixture-token", "phoneAddress", "displayId").forEach { assertFalse(exported.contains(it)) }
    }
}
