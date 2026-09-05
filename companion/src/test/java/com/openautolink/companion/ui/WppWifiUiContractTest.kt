package com.openautolink.companion.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source wiring checks only; network-selection behavior is executed in WppNetworkSelectionTest. */
class WppWifiUiContractTest {
    private fun screenSource(): String {
        val root = System.getProperty("repo.root")?.let(::File)
        val relative = "src/main/java/com/openautolink/companion/ui/MainScreen.kt"
        return listOfNotNull(root?.resolve("companion/$relative"), File(relative), File("companion/$relative"))
            .first { it.isFile }.readText()
    }

    @Test
    fun sendIsGuardedBeforeLaunchAndAlwaysReleasesBusyState() {
        val section = screenSource().substringAfter("private fun WppWifiSendSection(")
            .substringBefore("private fun CarWifiAddDialog(")
        assertTrue(section.contains("var isSending by remember { mutableStateOf(false) }"))
        assertTrue(section.contains("enabled = selectedBtMacs.isNotEmpty() && !isSending"))
        val click = section.substringAfter(".clickable(enabled = !isSending) {")
            .substringBefore(".padding(vertical = 6.dp)")
        val beforeLaunch = click.substringBefore("scope.launch {")
        assertTrue("Guard before recomposition can deliver another click", beforeLaunch.contains("if (isSending) return@clickable"))
        assertTrue(beforeLaunch.contains("isSending = true"))
        assertTrue("Close picker so progress status remains visible", beforeLaunch.contains("showScanPicker = false"))
        assertTrue(beforeLaunch.contains("onStatusChanged(\"Sending "))
        assertTrue(Regex("scope\\.launch \\{\\s*try \\{\\s*val result = WppConfigBtClient\\.sendToTargetCars").containsMatchIn(click))
        assertTrue(click.contains("ssid = network.ssid,"))
        assertTrue(click.contains("onSuccess = { count ->"))
        assertTrue(click.contains("onFailure = { error ->"))
        assertTrue(Regex("catch \\(e: CancellationException\\) \\{\\s*onStatusChanged\\(\"Send cancelled\\.\"\\)\\s*throw e").containsMatchIn(click))
        assertTrue(click.contains("catch (e: Exception)"))
        assertTrue(Regex("finally \\{\\s*isSending = false\\s*}").containsMatchIn(click))
    }

    @Test
    fun scanAdapterDelegatesWithoutChangingSsidIdentity() {
        val adapter = screenSource().substringAfter("private fun visibleWppNetworks(")
            .substringBefore("@Composable")
        assertTrue("The UI must use the tested selector", adapter.contains("selectWppNetworks("))
        assertTrue("Pass the exact scan SSID", adapter.contains("ssid = scan.SSID.orEmpty()"))
        assertFalse(adapter.contains(".trim()"))
        assertFalse(adapter.contains(".lowercase()"))
    }
}
