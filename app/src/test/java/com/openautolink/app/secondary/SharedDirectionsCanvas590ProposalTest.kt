package com.openautolink.app.secondary

import com.openautolink.app.cluster.*
import com.openautolink.carplaylab.media.CarPlayClusterVideoConfiguration
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.media.CarPlayVideoSelection
import org.junit.Assert.*
import org.junit.Test

/** UNEXECUTED: selected-canvas correctness is separate from stale fallback prevalidation. */
class SharedDirectionsCanvas590ProposalTest {
 private val panel=PixelSize(2000,810)
 private val d=SecondaryDisplayDescriptor(SecondaryOutputId("canvas-fixture"),17,1,"Synthetic",2000,810,220,SecondaryRoute.PUBLIC_ACTIVITY,SecondaryReason.NONE,"Synthetic")
 private val scope=SecondaryLayoutScope(d.id,"synthetic-product","synthetic-build",2000,810,220)
 private val full=SecondaryLayoutBox(0.0,0.0,1.0,1.0)
 @Test fun staleFallbackPrevalidationMustNotRejectSelectedCanvasFullHeight() {
  val layout=SecondaryLayoutPreference(scope,full,full,directSafe=full)
  val target=SecondaryRuntimeLayouts.target(d,layout,panel,PixelRect(558,70,1694,668),scope.vehicle,scope.buildFingerprint,carPlay=true,safeOnly=true)
  assertEquals("2000x810 selected canvas contains this valid full-height custom safe",panel.bounds,target.safe)
  assertEquals(panel.bounds,SecondaryVideoMapping.carPlay(target,panel).destinationPixels)
 }
 @Test fun actualSelected2000CanvasPreservesCurrentLeftZeroInRealBplist() {
  // Synthetic integer reproduction, NOT a reconstruction of private saved fractions.
  val target=SecondaryRenderTarget(d.id,d.attachmentEpoch,d.displayId,panel,panel.bounds,PixelRect(0,71,1875,653))
  val mapping=SecondaryVideoMapping.carPlay(target,panel)
  assertEquals(panel.bounds,mapping.destinationPixels)
  assertNotEquals(mapping.destinationPixels,SecondaryVideoMapping.carPlayDestination(panel.bounds))
  val s=mapping.phoneSafeInsets
  assertEquals(PixelInsets(left=0,top=71,right=125,bottom=157),s)
  val cfg=CarPlayClusterVideoConfiguration.create(AirPlayInsets(0,0,0,0),AirPlayInsets(top=s.top,bottom=s.bottom,left=s.left,right=s.right),CarPlayVideoSelection(2000,810,30,"qualified.avc"))
  val wire=BplistCodec.decode(BplistCodec.encode(AirPlayInfoPlist.build(AirPlayConfig("fixture","00:00:00:00:00:00","00:00:00:00:00:00","fixture",AirPlayDisplayConfig(1280,720),cluster=cfg.display,disableAudioOutput=true),availableAudioFormats=emptySet()))) as Map<*,*>
  val alt=(wire["displays"] as List<*>)[1] as Map<*,*>
  val area=((alt["viewAreas"] as List<*>).single() as Map<*,*>)
  val safe=area["safeArea"] as Map<*,*>
  assertEquals(0L,(safe["originXPixels"] as Number).toLong())
  assertEquals(71L,(safe["originYPixels"] as Number).toLong())
  assertEquals(1875L,(safe["widthPixels"] as Number).toLong())
  assertEquals(582L,(safe["heightPixels"] as Number).toLong())
  assertEquals(false,safe["drawUIOutsideSafeArea"])
 }
}
