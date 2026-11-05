package com.openautolink.app.ui.settings

import android.app.Application
import android.hardware.display.DisplayManager
import android.os.Looper
import android.view.Display
import com.openautolink.app.displays.SecondaryDisplayInventory
import com.openautolink.app.secondary.*
import org.junit.Assert.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers

/** Owns a fresh producer and installs only that inventory singleton for production consumers.
 * Restore the prior inventory without closing someone else's producer or resetting safety fences. */
internal class RawDisplayTestFixture(private val app: Application) : AutoCloseable {
    private val previous=ReflectionHelpers.getStaticField<SecondaryDisplayPolicyInventory?>(SecondaryDisplayPolicyInventory::class.java,"instance")
    val raw=SecondaryDisplayInventory(app)
    val policy=SecondaryDisplayPolicyInventory(app,raw)
    init { ReflectionHelpers.setStaticField(SecondaryDisplayPolicyInventory::class.java,"instance",policy) }
    fun target(id:Int,width:Int,height:Int,route:SecondaryRoute):SecondaryDisplayDescriptor {
        raw.refresh()
        repeat(10) { shadowOf(Looper.getMainLooper()).idle() }
        val row=raw.displays.value.single { it.displayId==id }
        assertEquals("Raw physical mode width before policy selection: $row",width,row.width)
        assertEquals(height,row.height)
        assertNotNull("Unique build/name/panel identity: $row",row.key)
        assertFalse(row.identityAmbiguous);assertTrue("Live public raw row: $row",row.available)
        assertTrue("Actual platform admission query: $row",row.launchAllowed);assertNull(row.reason)
        val actual=app.getSystemService(DisplayManager::class.java).getDisplay(id)
        assertTrue(actual.isValid);assertEquals(Display.STATE_ON,actual.state)
        val metrics=android.util.DisplayMetrics().also { actual.getRealMetrics(it) }
        assertEquals("Raw physical width versus live public width: $row",row.width,metrics.widthPixels)
        assertEquals("Raw physical height versus live public height: $row",row.height,metrics.heightPixels)
        val descriptor=policy.snapshots.value.displays.single { it.displayId==id }
        assertEquals(row.key,descriptor.id.value);assertEquals(SecondaryReason.NONE,descriptor.reason)
        assertEquals(route,descriptor.route);assertTrue(descriptor.attachmentEpoch>0)
        assertEquals("Exact descriptor must still be current before binding",descriptor,
            SecondaryDisplayPolicyInventory.get(app).snapshots.value.displays.single { it.id==descriptor.id })
        return descriptor
    }
    override fun close() {
        policy.close();raw.close();shadowOf(Looper.getMainLooper()).idle()
        ReflectionHelpers.setStaticField(SecondaryDisplayPolicyInventory::class.java,"instance",previous)
    }
}
