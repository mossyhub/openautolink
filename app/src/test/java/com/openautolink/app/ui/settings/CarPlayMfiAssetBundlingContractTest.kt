package com.openautolink.app.ui.settings

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source wiring guards complement runtime crypto tests and actual Gradle layout probes. */
class CarPlayMfiAssetBundlingContractTest {
    private val root: File get() = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "app/src/main").isDirectory }

    @Test fun optionalExternalAssetsAreWiredWithoutTrackedCredentialCopies() {
        val build = File(root, "app/build.gradle.kts").readText()
        assertTrue("Optional private build property is missing", build.contains("oalCarPlayMfiAssetsDir"))
        assertTrue(build.contains("assets.srcDir"))
        assertTrue(build.contains("identity.pk8"))
        assertTrue(build.contains("certificate.p7b"))
        assertTrue(build.contains("startsWith(rootProject.projectDir.canonicalFile.toPath())"))
    }

    @Test fun runtimeBootstrapPrecedesAuthenticationLoad() {
        val runtime = File(root, "app/src/main/java/com/openautolink/app/carplay/OalCarPlayRuntime.kt").readText()
        val hook = runtime.indexOf("CarPlayMfiAssetBootstrap(")
        assertTrue("First-use runtime bootstrap hook is missing", hook >= 0)
        assertTrue(hook < runtime.indexOf("LocalMfiAuthenticationClient.load("))
        val bootstrap = File(root, "app/src/main/java/com/openautolink/app/ui/settings/CarPlayMfiAssetBootstrap.kt").readText()
        assertFalse(bootstrap.contains("Log."))
        assertFalse(bootstrap.contains("StateFlow"))
    }
}
