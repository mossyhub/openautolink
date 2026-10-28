package com.openautolink.app.carplay

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

class CarPlayManifestPermissionTest {
    private val android = "http://schemas.android.com/apk/res/android"
    private fun elements(file: String, tag: String): List<Element> {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val nodes = factory.newDocumentBuilder().parse(File(file)).getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }
    private fun Element.attr(name: String) = getAttributeNS(android, name)
    @Test fun carRetainsOnlyUsedForegroundPermissionsAndDrivingDeclarations() {
        val file = "src/main/AndroidManifest.xml"
        val permissions = elements(file, "uses-permission").map { it.attr("name") }
        assertTrue(permissions.contains("android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"))
        assertTrue(permissions.contains("android.permission.FOREGROUND_SERVICE_MICROPHONE"))
        assertTrue(permissions.contains("android.permission.FOREGROUND_SERVICE"))
        assertTrue(permissions.contains("android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE"))
        val service = elements(file, "service").single { it.attr("name") == ".carplay.CarPlaySessionService" }
        assertEquals(setOf("connectedDevice","mediaPlayback","microphone"),service.attr("foregroundServiceType").split('|').toSet())
        assertEquals("false", service.attr("exported"))
        for (name in listOf(".MainActivity", "androidx.car.app.activity.CarAppActivity")) {
            val activity = elements(file, "activity").single { it.attr("name") == name }
            val metadata = activity.getElementsByTagName("meta-data")
            assertTrue((0 until metadata.length).map { metadata.item(it) as Element }.any {
                it.attr("name") == "distractionOptimized" && it.attr("value") == "true"
            })
        }
        assertTrue(elements(file, "meta-data").any {
            it.attr("name") == "com.android.automotive.application.DISTRACTION_OPTIMIZED" && it.attr("value") == "true"
        })
    }
    @Test fun companionKeepsItsUsedForegroundPermissions() {
        val permissions = elements("../companion/src/main/AndroidManifest.xml", "uses-permission").map { it.attr("name") }
        assertTrue(permissions.contains("android.permission.FOREGROUND_SERVICE"))
        assertTrue(permissions.contains("android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE"))
    }
}
