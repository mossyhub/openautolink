// SPDX-License-Identifier: GPL-3.0-only
package com.openautolink.carplaylab.media

import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.media.*
import org.junit.Assert.*
import org.junit.Test

class CarPlayClusterOfferTest {
    private val exact = CarPlayVideoSelection(1280,480,30,"cluster.avc")
    @Test fun malformedGeometryAndUnqualifiedSecondaryAreRejectedRatherThanSanitized() {
        fun rejected(view: AirPlayInsets = AirPlayInsets(), safe: AirPlayInsets = AirPlayInsets(),
            selection: CarPlayVideoSelection = exact, width: Int = 1280, height: Int = 480, fps: Int = 30) {
            assertThrows(IllegalArgumentException::class.java) {
                CarPlayClusterVideoConfiguration.create(view,safe,selection,width,height,fps)
            }
        }
        for (insets in listOf(AirPlayInsets(top=-1),AirPlayInsets(left=1280),
            AirPlayInsets(top=240,bottom=240),AirPlayInsets(left=Int.MAX_VALUE,right=Int.MAX_VALUE))) {
            rejected(view=insets); rejected(safe=insets)
        }
        rejected(view=AirPlayInsets(left=100),safe=AirPlayInsets(left=99))
        rejected(width=0); rejected(width=1278); rejected(height=720); rejected(fps=60)
        rejected(selection=exact.copy(width=1282)); rejected(selection=exact.copy(fps=29))
        rejected(selection=exact.copy(hevcDecoderName="cluster.hevc"))
        rejected(selection=CarPlayVideoSelection(1280,720,30,"baseline.avc",usedFallback=true))
        assertThrows(IllegalArgumentException::class.java) { exact.copy(avcDecoderName=" ") }
        assertThrows(IllegalArgumentException::class.java) { exact.copy(hevcDecoderName="") }
    }
    @Test fun clusterContentDefaultsToMapWithTurnCardAndOffersEveryAltScreenUrl() {
        val default = CarPlayClusterVideoConfiguration.create(AirPlayInsets(),AirPlayInsets(5,5,50,50),exact)
        assertEquals(CarPlayClusterContent.MAP_WITH_CARD,CarPlayClusterContent.DEFAULT)
        assertEquals("maps:/car/instrumentcluster",default.display.initialUrl)
        for (content in CarPlayClusterContent.entries) {
            val config = CarPlayClusterVideoConfiguration.create(AirPlayInsets(),AirPlayInsets(5,5,50,50),exact,
                content=content)
            assertEquals(content.url,config.display.initialUrl)
            assertEquals(content,CarPlayClusterContent.fromUrl(config.display.initialUrl))
        }
        assertEquals(CarPlayClusterVideoQualification.CLUSTER_URLS,CarPlayClusterContent.entries.map { it.url }.toSet())
        assertNull(CarPlayClusterContent.fromUrl("maps:/car/instrumentcluster/other"))
        assertThrows(IllegalArgumentException::class.java) {
            CarPlayClusterVideoQualification.qualify(default.display.copy(initialUrl="maps:/car/instrumentcluster/other"),exact)
        }
    }
    @Test fun fullPerDisplayReceiptRejectsStaleMainSecondaryAndGlobalHevcBindings() {
        val cluster = CarPlayClusterVideoConfiguration.create(AirPlayInsets(),AirPlayInsets(5,5,50,50),exact)
        val main = CarPlayVideoSelection(1920,1080,30,"main.avc","main.hevc")
        val bound = main.withCluster(cluster.display,exact)
        val config = AirPlayConfig("test","test","test","950.7.1",AirPlayDisplayConfig(1920,1080,fps=30),cluster=cluster.display)
        assertTrue(bound.matches(config)); assertFalse(bound.hevcOffered); assertTrue(main.hevcOffered)
        assertFalse(bound.matches(config.copy(hevc=true)))
        assertFalse(bound.matches(config.copy(cluster=null)))
        assertFalse(bound.matches(config.copy(main=config.main.copy(widthPixels=1280))))
        assertFalse(bound.matches(config.copy(main=config.main.copy(fps=60))))
        assertFalse(bound.matches(config.copy(cluster=cluster.display.copy(initialUrl="https://example.invalid"))))
        assertThrows(IllegalArgumentException::class.java) { bound.withCluster(cluster.display,exact) }
        assertThrows(IllegalArgumentException::class.java) { main.withCluster(cluster.display.copy(features=8),exact) }
    }
    @Test fun boundedNumericConfigurationIsAvailableAtReceiverBoundary() {
        assertNotNull("receiver needs an optional immutable validated cluster configuration",
            runCatching { Class.forName("com.openautolink.carplaylab.media.CarPlayClusterVideoConfiguration") }.getOrNull())
    }
    @Test fun exactSecondaryReceiptCanBindWithoutRemovingUnqualifiedClusterGuard() {
        val main = CarPlayVideoSelection(1280,720,30,"main.avc")
        val display = AirPlayDisplayConfig(1280,480,fps=30,primaryInputDevice=0,features=0,
            viewArea=AirPlayInsets(),safeArea=AirPlayInsets(20,20,100,100),safeAreaDrawOutside=false,
            initialUrl="maps:/car/instrumentcluster/map")
        val config = AirPlayConfig("test","test","test","950.7.1",AirPlayDisplayConfig(1280,720,fps=30),cluster=display)
        assertFalse("an unbound receipt cannot authorize a second display",main.matches(config))
        val bind = main.javaClass.methods.firstOrNull { it.name == "withCluster" }
        assertNotNull("qualified per-display binding is required",bind)
        val bound = bind!!.invoke(main,display,CarPlayVideoSelection(1280,480,30,"cluster.avc")) as CarPlayVideoSelection
        assertTrue("exact main and secondary receipts must match their serialized offer",bound.matches(config))
        assertFalse(bound.matches(config.copy(cluster=display.copy(safeArea=AirPlayInsets()))))
    }
}
