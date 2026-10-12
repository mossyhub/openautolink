package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayStartupTest {
    @Test fun defaultStartupMirrorsDiPlayWithoutRequestUi() {
        val sent = arrayListOf<Map<String, Any?>>()
        val startup = CarPlayStartup { sent.add(it); true }
        startup.recordReplied()
        startup.eventReady()
        startup.recordReplied()
        startup.eventReady()
        assertTrue("DiPlay RECORD activation does not request UI", sent.isEmpty())
    }

    @Test fun requestUiFollowsRecordReplyAndReadyEventExactlyOnce() {
        val sent = arrayListOf<Map<String, Any?>>()
        val startup = CarPlayStartup(requestUiOnStartup = true) { sent.add(it); true }
        startup.eventReady()
        assertTrue(sent.isEmpty())
        startup.recordReplied()
        assertEquals(listOf(mapOf("type" to "requestUI")), sent)
        startup.recordReplied(); startup.eventReady()
        assertEquals(1, sent.size)
    }
    @Test fun eventConnectingAfterRecordStillStartsUi() {
        val sent = arrayListOf<Map<String, Any?>>()
        val startup = CarPlayStartup(requestUiOnStartup = true) { sent.add(it); true }
        startup.recordReplied()
        assertTrue(sent.isEmpty())
        startup.eventReady()
        assertEquals(listOf(mapOf("type" to "requestUI")), sent)
    }
    @Test fun sessionExposesExplicitOptInWhileDefaultStaysPassive() {
        val attempts = arrayListOf<String>()
        val listener = object : AirPlaySessionListener {
            override fun onDebugLog(message: String) { attempts.add(message) }
        }
        val config = AirPlayConfig("startup-test", "test", "test", "950.7.1", AirPlayDisplayConfig(800, 480))
        val identity = AirPlayIdentity.generate()
        val store = PairingStore()
        val media = object : AirPlayMediaHandler {}
        val defaultSession = AirPlaySession(java.net.Socket(), config, identity, store, null, listener, media)
        val startupField = AirPlaySession::class.java.getDeclaredField("startup").apply { isAccessible = true }
        val defaultStartup = startupField.get(defaultSession) as CarPlayStartup
        defaultStartup.recordReplied(); defaultStartup.eventReady()
        assertTrue(attempts.isEmpty())

        val optInConstructor = AirPlaySession::class.java.constructors.firstOrNull {
            it.parameterTypes.size == 8 && it.parameterTypes.last() == Boolean::class.javaPrimitiveType
        }
        assertNotNull("Session must expose explicit startup opt-in, default false", optInConstructor)
        val optInSession = optInConstructor!!.newInstance(
            java.net.Socket(), config, identity, store, null, listener, media, true,
        ) as AirPlaySession
        val optInStartup = startupField.get(optInSession) as CarPlayStartup
        optInStartup.recordReplied(); optInStartup.eventReady()
        assertEquals(listOf("BENCH_DIAG startupRequestUiSent=false"), attempts)
        // No event transport was installed: this verifies policy wiring, not network receipt.
        defaultStartup.close(); optInStartup.close()
    }

    @Test fun defaultStartupAlsoStaysPassiveWhenEventIsReadyFirst() {
        var sent = 0
        val startup = CarPlayStartup { sent++; true }
        startup.eventReady(); startup.recordReplied()
        assertEquals(0, sent)
    }

    @Test fun retiringOldSessionDoesNotSuppressReplacementStartup() {
        val sent = arrayListOf<String>()
        val old = CarPlayStartup(requestUiOnStartup = true) { sent.add("old"); true }
        val replacement = CarPlayStartup(requestUiOnStartup = true) { sent.add("replacement"); true }
        old.eventReady(); old.close(); old.recordReplied()
        replacement.eventReady(); replacement.recordReplied()
        old.eventReady(); old.recordReplied()
        assertEquals(listOf("replacement"), sent)
    }

    @Test fun failedExplicitRequestIsNotRetriedByDuplicateReadiness() {
        var attempts = 0
        val startup = CarPlayStartup(requestUiOnStartup = true) { attempts++; false }
        startup.recordReplied(); startup.eventReady()
        startup.recordReplied(); startup.eventReady()
        assertEquals(1, attempts)
    }

    @Test fun retirementFencesWorkerWaitingOnExactSessionWriteLock() {
        val lock = Any()
        val entered = java.util.concurrent.CountDownLatch(1)
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val startup = CarPlayStartup(lock, requestUiOnStartup = true) { attempts.incrementAndGet(); true }
        startup.recordReplied()
        val worker = Thread({ entered.countDown(); startup.eventReady() }, "startup-event-test")
        synchronized(lock) {
            worker.start()
            assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
            startup.close()
        }
        worker.join(3000)
        assertFalse(worker.isAlive)
        assertEquals(0, attempts.get())
    }

    @Test fun explicitStartupWriteRemainsOnReadinessWorker() {
        val caller = Thread.currentThread()
        val writer = java.util.concurrent.atomic.AtomicReference<Thread>()
        val startup = CarPlayStartup(requestUiOnStartup = true) { writer.set(Thread.currentThread()); true }
        startup.recordReplied()
        val worker = Thread({ startup.eventReady() }, "startup-event-test")
        worker.start(); worker.join(3000)
        assertFalse(worker.isAlive)
        assertSame(worker, writer.get())
        assertNotSame(caller, writer.get())
    }

    @Test fun retiredSessionCannotSendStartup() {
        var sent = 0
        val startup = CarPlayStartup(requestUiOnStartup = true) { sent++; true }
        startup.recordReplied(); startup.close(); startup.eventReady()
        assertEquals(0, sent)
    }
}
