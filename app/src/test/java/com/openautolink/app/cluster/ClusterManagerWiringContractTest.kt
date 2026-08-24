package com.openautolink.app.cluster

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterManagerWiringContractTest {

    private fun projectFile(relative: String): String {
        val start = File(requireNotNull(System.getProperty("user.dir")))
        val root = generateSequence(start) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Could not locate project root from $start")
        return File(root, relative).readText()
    }

    @Test
    fun delayedForegroundRestoreIsOwnedByLaunchingGeneration() {
        val source = projectFile(
            "app/src/main/java/com/openautolink/app/cluster/ClusterManager.kt"
        )
        val start = source.indexOf("// Bring main activity back")
        val end = source.indexOf("}, BRING_BACK_DELAY_MS)", start)
        assertTrue("bring-back callback missing", start >= 0 && end > start)

        val callback = source.substring(start, end)
        assertTrue(
            "bring-back callback must share the lifecycle lock",
            callback.contains("synchronized(ClusterBindingLifecycle.lock)"),
        )
        assertTrue(
            "bring-back callback must reject a retired launch generation",
            callback.contains("ClusterBindingState.isManagerCurrent(launchLease)"),
        )
    }
}
