package com.openautolink.app.transport.aasdk

import com.openautolink.app.cluster.*
import com.openautolink.app.transport.ConnectionState
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test

/** Actual production JNI callback adapter and public session flow, no native library needed. */
class AasdkClusterVideoSessionTest {
    private fun offer(): AasdkClusterVideoOffer {
        val d=requireNotNull(ClusterDisplayProfiles.qualify(
            ClusterVehicleIdentity("aegean_orange",32,"VCUSR5-163",239),
            listOf(ClusterDisplayBinding(ClusterVehicleRole.DRIVER_CLUSTER,7)),
            ClusterHostGeometry(7,PixelSize(2000,810),PixelRect(0,135,2000,810),PixelRect(0,135,2000,810)),
        ))
        return AasdkClusterVideoOffer.fromMapping(ClusterVideoGeometry.androidAuto(d))
    }
    private fun callback(s:AasdkSession)=s.javaClass.getDeclaredMethod("telemetryCallback")
        .apply{isAccessible=true}.invoke(s) as AasdkSessionCallback

    @Test fun realAdapterKeepsClusterFramesAndRetirementAwayFromMainTouchAndCodec() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val s=AasdkSession(scope,mockk(relaxed=true))
            s.sdrConfig=AasdkSdrConfig(clusterOffer=offer())
            val callback=callback(s)
            callback.onVideoGeometrySelected(1920,1080,91)
            callback.onVideoCodecConfigured(7)
            var mainFrames=0
            val clusterFrames=mutableListOf<AasdkClusterVideoFrame>()
            val main=launch(start=CoroutineStart.UNDISPATCHED){s.videoFrames.collect{mainFrames++}}
            val cluster=launch(start=CoroutineStart.UNDISPATCHED){s.clusterVideoFrames.collect{clusterFrames+=it}}
            callback.onClusterVideoState(1,3,0,82,true)
            callback.onClusterVideoFrame(byteArrayOf(0,0,1,0x65),5,1,1);yield()
            assertEquals(1,clusterFrames.size);assertEquals(0,mainFrames)
            assertEquals(82,s.clusterVideoSelection.value?.mediaSessionId)
            assertTrue(s.isCurrentClusterVideo(clusterFrames.single()))
            s.retireClusterVideo()
            callback.onClusterVideoState(2,3,0,83,true)
            assertNull(s.clusterVideoSelection.value)
            assertFalse(s.isCurrentClusterVideo(clusterFrames.single()))
            assertEquals(1920 to 1080,s.activeVideoGeometry)
            assertEquals(7,s.negotiatedCodecType.value)
            assertEquals(ConnectionState.DISCONNECTED,s.connectionState.value)
            main.cancelAndJoin();cluster.cancelAndJoin()
        } finally {scope.cancel()}
    }
    @Test fun realAdapterRejectsSameEpochFromPriorNativeCallbackAndDefaultHasNoOffer() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val s=AasdkSession(scope,mockk(relaxed=true))
            callback(s).onClusterVideoState(1,3,0,81,true)
            assertNull(s.clusterVideoSelection.value)
            s.sdrConfig=AasdkSdrConfig(clusterOffer=offer())
            val old=callback(s);old.onClusterVideoState(1,3,0,82,true)
            val current=callback(s);current.onClusterVideoState(1,3,0,83,true)
            val selected=s.clusterVideoSelection.value
            old.onClusterVideoState(2,3,-1,0,false)
            old.onClusterVideoFrame(byteArrayOf(1),5,1,1)
            assertSame(selected,s.clusterVideoSelection.value)
            s.beginEvTelemetryGeneration()
            current.onClusterVideoState(2,3,0,84,true)
            assertNull(s.clusterVideoSelection.value)
        } finally {scope.cancel()}
    }
}
