package com.openautolink.app.displays

import android.app.Application
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(sdk = [32, 34], application = Application::class,
    shadows = [SecondaryDisplayInventoryTest.QueryShadow::class,
        SecondaryDisplayInventoryTest.ProductShadow::class])
class SecondaryDisplayInventoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @org.junit.Before fun resetBoundaries() {
        assertEquals(Application::class.java, context.javaClass)
        QueryShadow.allow = true
        QueryShadow.failure = null
        QueryShadow.calls.clear()
        QueryShadow.duringQuery = null
        ProductShadow.failure = null
        ProductShadow.product = null
    }

    @org.robolectric.annotation.Implements(android.app.ActivityManager::class)
    class QueryShadow : org.robolectric.shadows.ShadowActivityManager() {
        @org.robolectric.annotation.Implementation
        fun isActivityStartAllowedOnDisplay(context: Context, id: Int, intent: android.content.Intent): Boolean {
            calls.add(Triple(context, id, android.content.Intent(intent)))
            duringQuery?.invoke()
            failure?.let { throw it }
            return allow
        }
        companion object {
            var allow = true
            var failure: RuntimeException? = null
            var duringQuery: (() -> Unit)? = null
            val calls = mutableListOf<Triple<Context, Int, android.content.Intent>>()
        }
    }

    @org.robolectric.annotation.Implements(android.view.Display::class)
    class ProductShadow : org.robolectric.shadows.ShadowDisplay() {
        @org.robolectric.annotation.Implementation
        fun getDeviceProductInfo(): android.hardware.display.DeviceProductInfo? {
            failure?.let { throw it }
            return product
        }
        companion object {
            var product: android.hardware.display.DeviceProductInfo? = null
            var failure: RuntimeException? = null
        }
    }

    private fun add(name: String = "Secondary"): Int =
        ShadowDisplayManager.addDisplay("w800dp-h480dp-land-mdpi", name)
    private fun drain() = shadowOf(Looper.getMainLooper()).idle()
    private fun display(id: Int) = context.getSystemService(DisplayManager::class.java).getDisplay(id)

    @Test fun identicalMetadataRemainsTwoAmbiguousRowsNotMerged() {
        add(); add()
        SecondaryDisplayInventory(context).use { inventory ->
            val rows = inventory.displays.value
            assertEquals(2, rows.size)
            assertTrue(rows.all { it.key == null && it.identityAmbiguous })
            assertEquals(2, rows.map { it.displayId }.toSet().size)
            assertTrue(rows.all { it.reason == SecondaryDisplayReason.IDENTITY_AMBIGUOUS.name })
            try {
                (rows as MutableList<DiscoveredSecondaryDisplay>).clear()
                fail("Published inventory must be immutable")
            } catch (_: UnsupportedOperationException) { }
        }
    }

    @Test fun remappedRuntimeIdKeepsFingerprintAndProductMetadataChangesIt() {
        ProductShadow.product = android.hardware.display.DeviceProductInfo("Panel", "ABC", "model-1", 2024, 0)
        val first = add("Panel \" \\ name")
        SecondaryDisplayInventory(context).use { inventory ->
            val key = inventory.displays.value.single().key
            assertNotNull(key)
            assertFalse(inventory.displays.value.single().identityAmbiguous)
            ShadowDisplayManager.removeDisplay(first)
            val next = add("Panel \" \\ name")
            drain()
            assertNotEquals(first, next)
            assertEquals(key, inventory.displays.value.single().key)
            ProductShadow.product = android.hardware.display.DeviceProductInfo("Panel", "ABC", "model-2", 2024, 0)
            inventory.refresh()
            assertNotEquals(key, inventory.displays.value.single().key)
        }
    }

    @Test fun retainsPrivateOffAndUnknownStateWithoutAdmissionQueries() {
        val privateId = add("Private")
        val offId = add("Off")
        val unknownId = add("Unknown")
        shadowOf(display(privateId)).setFlags(android.view.Display.FLAG_PRIVATE)
        shadowOf(display(offId)).setState(android.view.Display.STATE_OFF)
        shadowOf(display(unknownId)).setState(android.view.Display.STATE_UNKNOWN)
        SecondaryDisplayInventory(context).use { inventory ->
            val rows = inventory.displays.value
            assertEquals(3, rows.size)
            assertTrue(rows.all { !it.available && !it.launchAllowed })
            assertEquals(SecondaryDisplayReason.PRIVATE_DISPLAY.name, rows.first { it.displayId == privateId }.reason)
            assertTrue(QueryShadow.calls.isEmpty())
            shadowOf(display(offId)).setState(android.view.Display.STATE_ON)
            inventory.refresh()
            assertTrue(inventory.displays.value.first { it.displayId == offId }.available)
        }
    }

    @Test fun onlyQueriesExplicitOwnActivityNeverLaunchesOrClaimsRendering() {
        val id = add()
        SecondaryDisplayInventory(context).use { inventory ->
            val row = inventory.displays.value.single()
            assertTrue(row.available)
            assertTrue(row.launchAllowed)
            assertNull(row.reason)
            val query = QueryShadow.calls.single()
            assertEquals(id, query.second)
            assertEquals(context.packageName, query.third.component!!.packageName)
            assertEquals("com.openautolink.app.MainActivity", query.third.component!!.className)
            assertNull(query.third.action)
            assertNull(query.third.extras)
            assertEquals(0, query.third.flags)
            assertNull(shadowOf(context as Application).nextStartedActivity)
            assertNull(shadowOf(context).nextStartedService)
        }
    }

    @Test fun queryDeniedSecurityAndUnsupportedAreTruthfulWithoutExceptionText() {
        add()
        QueryShadow.allow = false
        SecondaryDisplayInventory(context).use { inventory ->
            assertEquals(SecondaryDisplayReason.LAUNCH_QUERY_DENIED.name, inventory.displays.value.single().reason)
            for ((failure, reason) in listOf(
                SecurityException("secret details") to SecondaryDisplayReason.LAUNCH_QUERY_SECURITY_DENIED,
                UnsupportedOperationException("secret details") to SecondaryDisplayReason.LAUNCH_QUERY_UNAVAILABLE,
                IllegalStateException("secret details") to SecondaryDisplayReason.LAUNCH_QUERY_UNAVAILABLE
            )) {
                QueryShadow.failure = failure
                inventory.refresh()
                val row = inventory.displays.value.single()
                assertTrue(row.available)
                assertFalse(row.launchAllowed)
                assertEquals(reason.name, row.reason)
            }
            assertNull(shadowOf(context as Application).nextStartedActivity)
        }
    }

    @Test fun optionalEdidFailuresUseSamePublicIdentityAsAbsentEdid() {
        val id = add()
        SecondaryDisplayInventory(context).use { inventory ->
            val withoutEdid = inventory.displays.value.single()
            for (failure in listOf(SecurityException("sensitive"), IllegalStateException("sensitive"))) {
                ProductShadow.failure = failure
                inventory.refresh()
                val row = inventory.displays.value.single()
                assertEquals(id, row.displayId)
                assertEquals(withoutEdid.key, row.key)
                assertNotNull(row.key)
                assertFalse(row.identityAmbiguous)
                assertTrue(row.available)
                assertTrue(row.launchAllowed)
                assertNull(row.reason)
            }
        }
    }

    @Test fun closeFencesQueuedListenerEventsAndRefreshIsInert() {
        var callbacks = 0
        val inventory = SecondaryDisplayInventory(context) { callbacks++ }
        add(); drain()
        val before = inventory.displays.value
        val count = callbacks
        Thread { repeat(100) { inventory.refresh() } }.apply { start(); join() }
        inventory.close()
        inventory.close()
        add("after-close")
        drain()
        inventory.refresh()
        assertEquals(before, inventory.displays.value)
        assertEquals(count, callbacks)
    }

    @Test fun closeInsideAdmissionQueryFencesInflightSnapshot() {
        add()
        var callbacks = 0
        val inventory = SecondaryDisplayInventory(context) { callbacks++ }
        val before = inventory.displays.value
        QueryShadow.allow = false
        QueryShadow.duringQuery = { Thread { inventory.close() }.apply { start(); join() } }
        inventory.refresh()
        drain()
        assertEquals(before, inventory.displays.value)
        assertEquals(1, callbacks)
    }

    @Test fun callbackRunsOnMainAndCanRefreshOrCloseReentrantly() {
        var seen = 0
        lateinit var inventory: SecondaryDisplayInventory
        inventory = SecondaryDisplayInventory(context) {
            assertEquals(Looper.getMainLooper(), Looper.myLooper())
            seen++
            inventory.refresh()
            inventory.close()
        }
        add(); drain()
        assertEquals(1, seen)
        add("late"); drain()
        assertEquals(1, seen)
    }

    @Test fun failedListenerRegistrationKeepsVisibleRowsButDoesNotClaimLiveAvailability() {
        val id = add()
        val actualRows = context.getSystemService(DisplayManager::class.java).displays
        for ((failure, reason) in listOf(
            SecurityException("private service details") to "LISTENER_SECURITY_DENIED",
            UnsupportedOperationException("private service details") to "LISTENER_UNAVAILABLE"
        )) {
            val fakeManager = mockk<DisplayManager>()
            every { fakeManager.displays } returns actualRows
            every { fakeManager.registerDisplayListener(any(), any<android.os.Handler>()) } throws failure
            val wrapped = object : android.content.ContextWrapper(context) {
                override fun getApplicationContext(): Context = this
                override fun getSystemService(name: String): Any? =
                    if (name == Context.DISPLAY_SERVICE) fakeManager else super.getSystemService(name)
            }
            SecondaryDisplayInventory(wrapped).use { inventory ->
                val row = inventory.displays.value.single()
                assertEquals(id, row.displayId)
                assertEquals(reason, row.reason)
                assertFalse(row.available)
                assertFalse(row.launchAllowed)
                assertNotNull(row.key)
                inventory.refresh()
                assertEquals(row, inventory.displays.value.single())
                // A failed read and later successful enumeration cannot repair monitoring.
                every { fakeManager.displays } throws IllegalStateException("read failed")
                inventory.refresh()
                assertFalse(inventory.displays.value.single().available)
                every { fakeManager.displays } returns actualRows
                inventory.refresh()
                assertEquals(row, inventory.displays.value.single())
                io.mockk.verify(exactly = 1) {
                    fakeManager.registerDisplayListener(any(), any<android.os.Handler>())
                }
            }
            io.mockk.verify(exactly = 0) { fakeManager.unregisterDisplayListener(any()) }
        }
    }

    @Test fun onlySuccessfulRegistrationOfNewInventoryRestoresLiveAvailability() {
        add()
        val actualRows = context.getSystemService(DisplayManager::class.java).displays
        val fakeManager = mockk<DisplayManager>()
        every { fakeManager.displays } returns actualRows
        var registrationFails = true
        every { fakeManager.registerDisplayListener(any(), any<android.os.Handler>()) } answers {
            if (registrationFails) throw SecurityException("private service details")
            Unit
        }
        every { fakeManager.unregisterDisplayListener(any()) } returns Unit
        val wrapped = object : android.content.ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSystemService(name: String): Any? =
                if (name == Context.DISPLAY_SERVICE) fakeManager else super.getSystemService(name)
        }
        SecondaryDisplayInventory(wrapped).use { failed ->
            registrationFails = false
            failed.refresh()
            assertEquals("LISTENER_SECURITY_DENIED", failed.displays.value.single().reason)
            assertFalse(failed.displays.value.single().available)
            assertFalse(failed.displays.value.single().launchAllowed)
        }
        SecondaryDisplayInventory(wrapped).use { recovered ->
            val row = recovered.displays.value.single()
            assertTrue(row.available)
            assertTrue(row.launchAllowed)
            assertNull(row.reason)
            assertNotNull(row.key)
        }
        io.mockk.verify(exactly = 2) {
            fakeManager.registerDisplayListener(any(), any<android.os.Handler>())
        }
        io.mockk.verify(exactly = 1) { fakeManager.unregisterDisplayListener(any()) }
    }

    @Test fun closeBeforeQueuedInitializationPreventsRegistrationAndPublication() {
        add()
        var callbacks = 0
        lateinit var inventory: SecondaryDisplayInventory
        Thread {
            inventory = SecondaryDisplayInventory(context) { callbacks++ }
            inventory.close()
        }.apply { start(); join() }
        drain()
        assertTrue(inventory.displays.value.isEmpty())
        assertEquals(0, callbacks)
        assertTrue(QueryShadow.calls.isEmpty())
    }

    @Test fun followsRealDisplayAddChangeRemoveWithoutDefaultDisplay() {
        val inventory = SecondaryDisplayInventory(context)
        try {
            assertTrue(inventory.displays.value.isEmpty())
            val id = ShadowDisplayManager.addDisplay("w800dp-h480dp-land-mdpi", "Secondary")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(id), inventory.displays.value.map { it.displayId })
            ShadowDisplayManager.changeDisplay(id, "w1024dp-h600dp-land-mdpi")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1024, inventory.displays.value.single().width)
            ShadowDisplayManager.removeDisplay(id)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(inventory.displays.value.isEmpty())
        } finally { inventory.close() }
    }
}
