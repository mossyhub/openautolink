package com.openautolink.app.secondary

import com.openautolink.app.cluster.*
import com.openautolink.app.data.*
import com.openautolink.app.transport.aasdk.AasdkSecondaryVideoOffer
import com.openautolink.carplaylab.media.CarPlayClusterVideoConfiguration
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.media.CarPlayVideoSelection
import org.junit.Assert.*
import org.junit.Test

class ClusterDirectVideoGeometryTest {
    private val d=SecondaryDisplayDescriptor(SecondaryOutputId("no-profile"),17,4,"Selected public display",1920,1080,160,SecondaryRoute.PUBLIC_ACTIVITY,SecondaryReason.NONE,"Unknown")
    private val panel=PixelSize(1600,600) // Actual owned drawable is a subset, never fake panel dimensions.
    private val scope=SecondaryLayoutScope(d.id,"unknown-vehicle","actual-build",d.width,d.height,d.densityDpi)
    private val legacy=SecondaryLayoutPreference(scope,SecondaryLayoutBox(.1,.1,.9,.9),SecondaryLayoutBox(.3,.3,.7,.7))
    private fun target(layout:SecondaryLayoutPreference?,cp:Boolean)=SecondaryRuntimeLayouts.target(d,layout,panel,PixelRect(480,120,1120,480),"unknown-vehicle","actual-build",carPlay=cp,safeOnly=true)
    @Test fun safeEditRetainsWholeSurfaceAndLegacyOuterAndSerializesIndependentPhoneInsets() {
        val changed=legacy.copy(directSafe=SecondaryLayoutBox(.35,.25,.65,.75))
        for(cp in listOf(false,true)) {
            val a=target(legacy,cp);val b=target(changed,cp)
            assertEquals(panel.bounds,a.output);assertEquals(a.output,b.output)
            val ma=if(cp) SecondaryVideoMapping.carPlay(a) else SecondaryVideoMapping.androidAuto(a)
            val mb=if(cp) SecondaryVideoMapping.carPlay(b) else SecondaryVideoMapping.androidAuto(b)
            assertEquals(ma.codedSize,mb.codedSize);assertEquals(ma.codecCrop,mb.codecCrop);assertEquals(ma.destination,mb.destination)
            assertNotEquals(ma.phoneSafeInsets,mb.phoneSafeInsets)
            if(cp) {
                var wireIndex=0
                fun wire(m:VideoMapping):Map<*,*> {
                    val safe=m.phoneSafeInsets
                    val cfg=CarPlayClusterVideoConfiguration.create(AirPlayInsets(0,0,0,0),AirPlayInsets(safe.top,safe.bottom,safe.left,safe.right),CarPlayVideoSelection(1280,480,30,"qualified.avc"))
                    val dict=AirPlayInfoPlist.build(AirPlayConfig("fixture","00:00:00:00:00:00","00:00:00:00:00:00","fixture",AirPlayDisplayConfig(1280,720),cluster=cfg.display,disableAudioOutput=true),availableAudioFormats=emptySet())
                    val bytes=BplistCodec.encode(dict)
                    System.getProperty("direct.receipt.dir")?.let { dir -> java.io.File(dir).mkdirs();java.io.File(dir,"cp-safe-${wireIndex++}.bplist").writeBytes(bytes) }
                    return BplistCodec.decode(bytes) as Map<*,*>
                }
                val before=wire(ma)["displays"] as List<*>;val after=wire(mb)["displays"] as List<*>
                assertEquals(before.first(),after.first());assertNotEquals(before[1],after[1])
                val safe=(((after[1] as Map<*,*>)["viewAreas"] as List<*>).single() as Map<*,*>)["safeArea"] as Map<*,*>
                assertEquals(mb.phoneSafeInsets.left.toLong(),(safe["originXPixels"] as Number).toLong())
            } else {
                fun offer(m:VideoMapping):AasdkSecondaryVideoOffer {
                    val c=m.codecCrop;val s=m.phoneSafeInsets
                    return AasdkSecondaryVideoOffer.create(19,2,2,cropLeft=c.left,cropTop=c.top,cropRight=c.right,cropBottom=c.bottom,
                        safeLeft=s.left,safeTop=s.top,safeRight=s.right,safeBottom=s.bottom,
                        stableLeft=s.left,stableTop=s.top,stableRight=s.right,stableBottom=s.bottom)
                }
                assertEquals(offer(ma).cropTop,offer(mb).cropTop);assertNotEquals(offer(ma).safeTop,offer(mb).safeTop)
                System.getProperty("direct.receipt.dir")?.let { dir ->
                    java.io.File(dir).mkdirs()
                    listOf("before" to offer(ma),"after" to offer(mb)).forEach { (name,o) ->
                        val fields=listOf("channelId","protocolDisplayId","displayType","density","cropLeft","cropTop","cropRight","cropBottom","safeLeft","safeTop","safeRight","safeBottom","stableLeft","stableTop","stableRight","stableBottom")
                        val json=kotlinx.serialization.json.buildJsonObject { fields.forEach { field -> put(field,kotlinx.serialization.json.JsonPrimitive(o.javaClass.getField(field).getInt(o))) } }
                        java.io.File(dir,"aa-offer-safe-$name.json").writeText(json.toString())
                    }
                }
            }
        }
        val intent=SecondaryPreferencesSnapshot(2,mapOf(d.id to SecondaryOutputPreference(true,AaSecondaryRole.AUXILIARY,true,true,changed)),ClusterOutputPreferences(ClusterOutputSelection(directVideoWindow=true),ClusterDisplayMode.NATIVE_ICONS))
        val restored=SecondaryDisplayPreferences.read(SecondaryDisplayPreferences.encode(intent),intent.legacyCluster)
        assertEquals(intent,restored);assertEquals(legacy.output,restored.outputs.getValue(d.id).layout!!.output)
        assertEquals(legacy.safe,restored.outputs.getValue(d.id).layout!!.safe)
        val reset=target(changed.copy(directSafe=null,directSafeAutomatic=true),false)
        assertEquals(panel.bounds,reset.output);assertEquals(PixelRect(480,120,1120,480),reset.safe)
        val unknown=target(changed.copy(scope=scope.copy(vehicle="other")),false)
        assertEquals(panel.bounds,unknown.output);assertEquals(reset.safe,unknown.safe)
    }
    @Test fun onlyQualifiedGmProductGetsAuxiliaryDefaultAndExplicitRolesStayExact() {
        val gm = ClusterDisplayProfiles.AEGEAN_ORANGE_239.vehicle.product
        assertEquals(AaSecondaryRole.AUXILIARY, defaultAaSecondaryRole(gm))
        assertEquals(AaSecondaryRole.CLUSTER, defaultAaSecondaryRole("other-product"))
        assertEquals(AaSecondaryRole.CLUSTER, defaultAaSecondaryRole("2"))
        val unset = SecondaryOutputPreference(true, null, true, true, legacy)
        assertEquals(unset.copy(aaRole = AaSecondaryRole.AUXILIARY), unset.withDefaultAaRole(gm))
        assertEquals(unset.copy(aaRole = AaSecondaryRole.CLUSTER), unset.withDefaultAaRole("other-product"))
        for (role in AaSecondaryRole.entries) {
            val explicit = unset.copy(aaRole = role)
            assertSame(explicit, explicit.withDefaultAaRole(gm))
            assertSame(explicit, explicit.withDefaultAaRole("other-product"))
        }
    }

    @Test fun pilotAndCoexistenceAreOffByDefaultButExplicitChoiceAllowsOnlySourcedGuidance() {
        assertFalse(ClusterOutputSelection().directVideoWindow)
        assertFalse(ClusterOutputSelection().nativeIconsWithMap)
        val video=ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO,nativeIconsWithMap=true,directVideoWindow=true)
        for(protocol in ClusterProjectionProtocol.entries) {
            assertTrue(ClusterOutputPolicy.shouldPublishNativeIcons(video,protocol,true))
            assertFalse(ClusterOutputPolicy.shouldPublishNativeIcons(video,protocol,false))
            assertFalse(ClusterOutputPolicy.shouldPublishNativeIcons(video.copy(nativeIconsWithMap=false),protocol,true))
            assertTrue(ClusterOutputPolicy.shouldPublishNativeIcons(video.copy(mode=ClusterDisplayMode.NATIVE_ICONS),protocol,true))
            assertFalse(ClusterOutputPolicy.shouldOfferMapVideo(video.copy(mode=ClusterDisplayMode.OFF),protocol))
        }
    }
}
