package com.openautolink.app.secondary

import com.openautolink.app.cluster.*
import org.junit.Assert.*
import org.junit.Test

class SecondaryCalibratedRuntimeTest {
    private val id = SecondaryOutputId("synthetic-calibration")
    private val d = SecondaryDisplayDescriptor(id,17,1,"Synthetic",2000,810,220,SecondaryRoute.GM_HOSTED,SecondaryReason.NONE,"Synthetic")
    private val scope = SecondaryLayoutScope(id,"synthetic-product","synthetic-build",2000,810,220)
    private val full = SecondaryLayoutBox(0.0,0.0,1.0,1.0)
    private val oem = PixelRect(558,70,1694,668)
    private fun target(layout: SecondaryLayoutPreference? = null, cp: Boolean = false, direct: Boolean = true,
        panel: PixelSize = PixelSize(2000,810)) = SecondaryRuntimeLayouts.target(d,layout,panel,oem,
        scope.vehicle,scope.buildFingerprint,carPlay=cp,safeOnly=direct)
    @Test fun newOutputAndResetUseMeasuredAaDefaultButCarPlayAndOtherRoutesStayUnchanged() {
        val expected = PixelRect(192,71,1788,660)
        assertEquals(expected,target().safe);assertEquals(PixelRect(0,0,2000,810),target().output)
        val saved = SecondaryLayoutPreference(scope,full,full,directSafe=SecondaryLayoutBox(.3,.2,.7,.7),directSafeAutomatic=true)
        assertEquals(expected,target(saved).safe)
        assertEquals(oem,target(cp=true).safe);assertEquals(oem,target(saved,cp=true).safe)
        assertEquals(oem,target(direct=false).safe)
        assertEquals(oem,target(panel=PixelSize(2100,900)).safe)
        assertEquals(220,d.densityDpi)
    }
    @Test fun migratedResetIsOverriddenOnceButLaterExplicitAaEditAndResetRemainEffective() {
        val migrated = SecondaryLayoutPreference(scope,full,full,directSafeAutomatic=true,aaDirectSafe=SecondaryCalibratedDefaults.directSafe)
        assertEquals(PixelRect(192,71,1788,660),target(migrated).safe)
        val explicit = migrated.copy(directSafe=SecondaryLayoutBox(.3,.2,.7,.7),directSafeAutomatic=false,aaDirectSafe=null)
        assertEquals(PixelRect(600,162,1400,567),target(explicit).safe)
        assertEquals(PixelRect(192,71,1788,660),target(explicit.copy(directSafe=null,directSafeAutomatic=true)).safe)
        assertEquals(oem,target(migrated,cp=true).safe)
    }
}
