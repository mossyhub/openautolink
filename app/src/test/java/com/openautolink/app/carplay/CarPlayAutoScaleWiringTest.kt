package com.openautolink.app.carplay

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Source contract only: Android Display/Surface callbacks require parent-owned device validation. */
class CarPlayAutoScaleWiringTest {
    @Test fun runtimeSnapshotsCarPlayViewportWithoutBorrowingAaDpiOrMutatingAnActiveReceiver() {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
        val source = File(root, "app/src/main/java/com/openautolink/app/ui/projection/ProjectionViewModel.kt").readText()
        assertTrue(source.contains("fun onCarPlayViewportChanged("))
        assertTrue(source.contains("display.getRealMetrics(metrics)"))
        assertTrue(source.contains("saved.qualifiedDisplay(viewport)"))
        val policy = File(root, "app/src/main/java/com/openautolink/app/data/CarPlaySettings.kt").readText()
        assertTrue(policy.contains("autoScaleInput = if (autoScale) viewport else null"))
        assertTrue(source.contains("calibration needed"))
        val callback = source.substringAfter("fun onCarPlayViewportChanged(").substringBefore("fun onCarPlaySurfaceAvailable(")
        assertFalse(callback.contains("densityForWidth"))
        assertFalse(callback.contains("reconnect("))
        assertFalse(callback.contains("setRenderRect("))
    }
}
