// SPDX-License-Identifier: GPL-3.0-only
package com.openautolink.carplaylab.media
import android.content.Context
import android.view.Surface
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32], manifest=Config.NONE)
class ProjectionReceiverWiredTest {
 @Test fun wiredIdentificationDoesNotRequireWirelessSsidOrBluetoothIdentity() {
  val format=android.media.MediaFormat.createVideoFormat("video/avc",1280,720)
  val caps=org.robolectric.shadows.MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
   .setMediaFormat(format).setIsEncoder(false).setColorFormats(intArrayOf(2130708361))
   .setProfileLevels(arrayOf(android.media.MediaCodecInfo.CodecProfileLevel().apply {
     profile=android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
     level=android.media.MediaCodecInfo.CodecProfileLevel.AVCLevel52
   })).build()
  org.robolectric.shadows.ShadowMediaCodecList.addCodec(org.robolectric.shadows.MediaCodecInfoBuilder.newBuilder()
   .setName("test.avc.decoder").setIsEncoder(false).setCapabilities(caps).build())
  val surface=mock(Surface::class.java); `when`(surface.isValid).thenReturn(true)
  val context=RuntimeEnvironment.getApplication() as Context
  java.io.File(context.noBackupFilesDir,"pairing-probe").deleteRecursively()
  val r=ProjectionReceiver(context,"02:00:00:00:00:01",surface,mock(MfiAuthenticator::class.java), wired=true,onStatus={})
  try { r.setRuntimeIdentification(Iap2IdentificationConfig(name="OAL",modelIdentifier="OAL",manufacturer="OAL",
    serialNumber="private",firmwareVersion="1",hardwareVersion="Android",carPlayUsbInterfaceNumber=1,wireless=null)) }
  finally { r.close(); check(r.awaitClosed(10000)) }
 }
}
