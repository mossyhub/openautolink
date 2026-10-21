package com.shilapi.xcertplay.media

import android.content.Context
import android.media.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32], manifest=Config.NONE)
class OwnRecorderRouteObserverTest {
    private fun device(id: Int) = mock(AudioDeviceInfo::class.java).also { `when`(it.id).thenReturn(id) }

    @Test fun logicalEqualityStabilityUnknownOverflowAndNewOwnerEpochAreExplicit() {
        val map=LogicalRouteOrdinals(2)
        val a=device(701);val b=device(702);val c=device(703)
        val record=mock(AudioRecord::class.java)
        val config=mock(AudioRecordingConfiguration::class.java)
        val observer=OwnRecorderRouteObserver(map)
        fun observe(r: AudioDeviceInfo?, active: AudioDeviceInfo?): String {
            `when`(record.routedDevice).thenReturn(r);`when`(config.audioDevice).thenReturn(active)
            return observer.receipt(record,Result.success(config))!!
        }
        assertTrue(observe(a,a).endsWith("routed=1 active=1 equality=same stability=unknown truncated=false"))
        assertTrue(observe(a,b).endsWith("routed=1 active=2 equality=different stability=same truncated=false"))
        assertTrue(observe(b,b).contains("stability=different"))
        assertTrue(observe(null,b).contains("routed=unknown active=2 equality=unknown stability=unknown"))
        assertTrue(observe(a,a).contains("stability=unknown"))
        assertTrue(observe(c,c).endsWith("routed=unknown active=unknown equality=unknown stability=unknown truncated=true"))
        // IDs may be reused by Android: this is intentionally LOGICAL endpoint equality only.
        assertEquals(1,map.ordinal(701))
        observe(a,a)
        assertTrue(OwnRecorderRouteObserver(map).receipt(record,Result.success(config))!!
            .endsWith("routed=1 active=1 equality=same stability=unknown truncated=false"))
        `when`(record.routedDevice).thenThrow(IllegalStateException("private"))
        `when`(config.audioDevice).thenThrow(SecurityException("private"))
        val failed=observer.receipt(record,Result.success(config))!!
        assertTrue(failed.contains("routed=unknown active=unknown equality=unknown stability=unknown"))
        assertFalse(failed.contains("private"))
        doReturn(a).`when`(record).routedDevice
        doReturn(b).`when`(config).audioDevice
        doThrow(SecurityException("private")).`when`(a).id
        assertTrue(observer.receipt(record,Result.success(config))!!
            .endsWith("routed=unknown active=2 equality=unknown stability=unknown truncated=false"))
    }

    @Test fun retirementDuringMappingQueryNeverReadsMicrophoneRowsOrReportsLateResults() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val exited=CountDownLatch(1)
        val record=mock(AudioRecord::class.java);val row=mock(MicrophoneInfo::class.java)
        `when`(record.activeMicrophones).thenAnswer {
            entered.countDown();release.await(3,TimeUnit.SECONDS);exited.countDown();listOf(row)
        }
        val lines=CopyOnWriteArrayList<String>()
        val observer=MicrophonePolicyDiagnostics.forRecorder(mock(Context::class.java),record,MicrophoneSource.MIC,24000,lines::add)
        try {
            observer.sample();assertTrue(entered.await(3,TimeUnit.SECONDS))
            observer.close();release.countDown();assertTrue(exited.await(3,TimeUnit.SECONDS));Thread.sleep(60)
            verifyNoInteractions(row)
            assertTrue(lines.isEmpty())
        } finally {observer.close();release.countDown()}
    }

    @Test fun mappingIsBoundedAndReadsOnlyChannelMappingNeverMicrophoneIdentity() {
        val record=mock(AudioRecord::class.java)
        val microphones=(0..5).map { mock(MicrophoneInfo::class.java) }
        `when`(record.activeMicrophones).thenReturn(microphones)
        `when`(microphones[0].channelMapping).thenReturn((0..9).map {
            android.util.Pair(it, if(it==0) MicrophoneInfo.CHANNEL_MAPPING_DIRECT else MicrophoneInfo.CHANNEL_MAPPING_PROCESSED)
        })
        val lines=mapping(record)
        assertTrue(lines.contains("media stage=microphone-mapping scope=own result=ok examined=1 truncated=true"))
        val channels=lines.filter {it.startsWith("media stage=microphone-channel ")}
        assertEquals(8,channels.size)
        assertTrue(channels[0].endsWith("row=0 channel=0 mapping=direct"))
        assertTrue(channels[1].endsWith("row=0 channel=1 mapping=processed"))
        microphones.forEach {
            verify(it,never()).id;verify(it,never()).address;verify(it,never()).description
            verify(it,never()).position;verify(it,never()).orientation
        }
        for (index in 1..5) verify(microphones[index],never()).channelMapping
    }

    @Test fun rowCapUnknownChannelAndUnknownMappingRemainFinite() {
        val record=mock(AudioRecord::class.java)
        val rows=(0..5).map { mock(MicrophoneInfo::class.java) }
        rows.forEach { `when`(it.channelMapping).thenReturn(listOf(android.util.Pair(99,77))) }
        `when`(record.activeMicrophones).thenReturn(rows)
        val lines=mapping(record)
        assertTrue(lines.last().endsWith("result=ok examined=4 truncated=true"))
        assertEquals(4,lines.count {it.endsWith("channel=unknown mapping=unknown")})
        for(index in 0..3) { verify(rows[index],times(1)).channelMapping;verifyNoMoreInteractions(rows[index]) }
        for(index in 4..5) verifyNoInteractions(rows[index])
    }

    @Test fun heldExactRouteRetirementSuppressesFurtherQueriesAndNeverQueuesAnotherOwner() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val exited=CountDownLatch(1)
        val record=mock(AudioRecord::class.java);val successor=mock(AudioRecord::class.java)
        val lines=CopyOnWriteArrayList<String>()
        val threads=CopyOnWriteArrayList<String>()
        `when`(record.routedDevice).thenAnswer {
            threads.add(Thread.currentThread().name);entered.countDown()
            release.await(3,TimeUnit.SECONDS);exited.countDown();null
        }
        val owner=MicrophonePolicyDiagnostics.forRecorder(mock(Context::class.java),record,MicrophoneSource.MIC,24000,lines::add)
        val next=MicrophonePolicyDiagnostics.forRecorder(mock(Context::class.java),successor,MicrophoneSource.MIC,24000,lines::add)
        try {
            owner.sample();assertTrue(entered.await(3,TimeUnit.SECONDS))
            repeat(100) { next.sample() }
            owner.close();next.close();release.countDown()
            assertTrue(exited.await(3,TimeUnit.SECONDS));Thread.sleep(60)
            assertTrue(lines.isEmpty())
            assertEquals(listOf("carplay-mic-policy"),threads.toList())
            verify(record,never()).activeRecordingConfiguration
            verify(record,never()).activeMicrophones
            verifyNoInteractions(successor)
        } finally {owner.close();next.close();release.countDown()}
    }

    @Test fun retirementDuringConfigurationReadDoesNotStartLaterModeOrMappingQueries() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val exited=CountDownLatch(1)
        val app=mock(Context::class.java);val manager=mock(AudioManager::class.java)
        val record=mock(AudioRecord::class.java);val config=mock(AudioRecordingConfiguration::class.java)
        `when`(app.getSystemService(AudioManager::class.java)).thenReturn(manager)
        `when`(record.activeRecordingConfiguration).thenReturn(config)
        `when`(config.clientFormat).thenAnswer { entered.countDown();release.await(3,TimeUnit.SECONDS);exited.countDown();null }
        val lines=CopyOnWriteArrayList<String>()
        val policy=MicrophonePolicyDiagnostics.forRecorder(app,record,MicrophoneSource.MIC,24000,lines::add)
        try {
            policy.sample();assertTrue(entered.await(3,TimeUnit.SECONDS))
            policy.close();release.countDown();assertTrue(exited.await(3,TimeUnit.SECONDS));Thread.sleep(60)
            assertTrue(lines.isEmpty())
            verify(manager,never()).mode
            verify(record,never()).activeMicrophones
        } finally { policy.close();release.countDown() }
    }

    @Test fun mappingEmptyDeniedUnsupportedUnavailableRemainDistinct() {
        val record=mock(AudioRecord::class.java)
        `when`(record.activeMicrophones).thenReturn(emptyList())
        assertTrue(mapping(record).any {it.contains("result=empty examined=0 truncated=false")})
        doThrow(SecurityException("private")).`when`(record).activeMicrophones
        assertTrue(mapping(record).any {it.contains("result=denied examined=0 truncated=false")})
        doThrow(UnsupportedOperationException("private")).`when`(record).activeMicrophones
        assertTrue(mapping(record).any {it.contains("result=unsupported examined=0 truncated=false")})
        doThrow(java.io.IOException("private")).`when`(record).activeMicrophones
        assertTrue(mapping(record).any {it.contains("result=unavailable examined=0 truncated=false")})
    }

    private fun mapping(record: AudioRecord): List<String> {
        val lines=CopyOnWriteArrayList<String>();val done=CountDownLatch(1)
        val observer=MicrophonePolicyDiagnostics.forRecorder(mock(Context::class.java),record,MicrophoneSource.MIC,24000) {
            lines.add(it);if(it.startsWith("media stage=microphone-mapping ")) done.countDown()
        }
        try {
            observer.sample();assertTrue("mapping observation missing",done.await(3,TimeUnit.SECONDS))
            // Mapping header is the final receipt; worker is about to finish its bounded task.
            Thread.sleep(40)
            assertFalse(lines.any {it.contains("private")})
            return lines.toList()
        } finally { observer.close();Thread.sleep(40) }
    }

    @Test fun existingExactRecorderWorkerEmitsLogicalRouteWithoutReadingPrivateIdentity() {
        val app=mock(Context::class.java)
        val record=mock(AudioRecord::class.java)
        val config=mock(AudioRecordingConfiguration::class.java)
        val device=mock(AudioDeviceInfo::class.java)
        `when`(device.id).thenReturn(23456789)
        `when`(record.routedDevice).thenReturn(device)
        `when`(record.activeRecordingConfiguration).thenReturn(config)
        `when`(config.audioDevice).thenReturn(device)
        `when`(record.activeMicrophones).thenReturn(emptyList())
        val lines=CopyOnWriteArrayList<String>();val done=CountDownLatch(1)
        val observer=MicrophonePolicyDiagnostics.forRecorder(app,record,MicrophoneSource.MIC,24000) {
            lines.add(it);if(it.startsWith("media stage=microphone-route ")) done.countDown()
        }
        try {
            observer.sample()
            assertTrue("own route observation missing from existing worker",done.await(3,TimeUnit.SECONDS))
            assertTrue(lines.any { it.contains("equality=same stability=unknown truncated=false") })
            assertFalse(lines.any { it.contains("23456789") })
            verify(device,never()).address
            verify(device,never()).productName
            verify(record,never()).audioSessionId
        } finally {observer.close()}
    }
}
