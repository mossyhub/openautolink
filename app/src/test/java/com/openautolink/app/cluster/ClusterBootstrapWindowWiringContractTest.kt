package com.openautolink.app.cluster

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterBootstrapWindowWiringContractTest {

    private fun projectFile(relative: String): String {
        val start = File(requireNotNull(System.getProperty("user.dir")))
        val root = generateSequence(start) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
        return File(requireNotNull(root), relative).readText()
    }

    @Test
    fun bootstrapWindowIsProtectedBeforeCarAppActivityCreatesItsSurface() {
        val application = projectFile(
            "app/src/main/java/com/openautolink/app/OalApplication.kt",
        )
        val protector = projectFile(
            "app/src/main/java/com/openautolink/app/cluster/ClusterBootstrapWindowProtector.kt",
        )
        val taskState = projectFile(
            "app/src/main/java/com/openautolink/app/cluster/ClusterBootstrapTaskState.kt",
        )

        val install = application.indexOf("ClusterBootstrapWindowProtector.install(this)")
        val sessionInfrastructure = Regex("""ProcessVehicleDataRuntime\.initialize\(\s*this,\s*learnedEstimator,""")
            .find(application)?.range?.first ?: -1
        assertTrue("protector must install before session infrastructure", install in 0 until sessionInfrastructure)
        assertTrue(protector.contains("private const val CAR_APP_ACTIVITY = \"androidx.car.app.activity.CarAppActivity\""))
        assertTrue(protector.contains("if (activity.javaClass.name != CAR_APP_ACTIVITY) return"))
        assertTrue(protector.contains("onActivityPreCreated"))
        assertTrue(protector.contains("WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE"))
        assertTrue(protector.contains("WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE"))
        assertTrue(protector.contains("attributes.alpha = 0f"))
        assertTrue(protector.contains("Cluster bootstrap window protected"))
        assertTrue(protector.contains("window.decorView.post"))
        assertTrue(protector.contains("ClusterBootstrapTaskState<Activity>()"))
        assertTrue(protector.contains("moveTaskToBack(true)"))
        assertTrue(protector.contains("Cluster bootstrap task background outcome="))
        assertTrue(taskState.contains("if (this.generation != generation)"))
        assertTrue(taskState.contains("backgroundRequestedGeneration == target.generation"))
    }

    @Test
    fun readyPrimaryStaysInvisibleWithoutForegroundBounceOrUnsafeTaskRetirement() {
        val manager = projectFile(
            "app/src/main/java/com/openautolink/app/cluster/ClusterManager.kt",
        )
        val session = projectFile(
            "app/src/main/java/com/openautolink/app/cluster/ClusterMainSession.kt",
        )

        val collectorStart = session.indexOf("sessionScope.launch")
        val ready = session.indexOf("ClusterBindingState.markReady(lease)")
        val background = session.indexOf("ClusterBootstrapWindowProtector.moveTaskToBack(lease.generation)")
        assertTrue("collector must be installed before readiness", collectorStart in 0 until ready)
        assertTrue("task must move behind the foreground only after readiness", ready in 0 until background)
        assertTrue(manager.contains("ClusterBindingState.hasReadySession(launchLease)"))
        assertFalse(manager.contains("scheduleBootstrapRetirement"))
        assertFalse(manager.contains("finishClusterTask(launchLease, \"session-ready\")"))
        assertFalse(manager.contains("Brought main activity back to foreground"))
        assertFalse(manager.contains("Intent.FLAG_ACTIVITY_REORDER_TO_FRONT"))
    }
}
