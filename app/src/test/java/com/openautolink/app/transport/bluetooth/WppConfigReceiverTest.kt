package com.openautolink.app.transport.bluetooth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class WppConfigReceiverTest {
    @Test fun listenerFailureRetiresSocket() = runBlocking {
        val f = Fixture()
        try {
            f.receiver.start()
            await { f.listener.attempts.get() == 1 }
            f.listener.close()
            await { f.receiver.status.value.startsWith("Listener failed") }
            await { WppConfigEvent.STOPPED in f.events }
            assertEquals(listOf(WppConfigEvent.LISTENER_UP, WppConfigEvent.LISTENER_FAILED, WppConfigEvent.STOPPED), f.events)
            assertEquals(1, f.listener.attempts.get())
        } finally { f.close() }
    }

    @Test fun stopClosesAcceptedClientBeforeDelayedPayloadCanWrite() = runBlocking {
        val f = Fixture()
        val input = GatedInput(frame())
        val client = Client(input)
        try {
            f.receiver.start()
            f.listener.clients.put(client)
            assertTrue(input.entered.await(2, TimeUnit.SECONDS))
            f.receiver.stop()
            assertTrue("stop must close an accepted blocking reader", client.closed)
            input.release.countDown()
            delay(100)
            assertEquals(0, f.writes.get())
            assertNull(f.receiver.appliedConfig.value)
            assertEquals(0, client.output.size())
        } finally { input.release.countDown(); f.close() }
    }

    @Test fun stoppedWorkerCannotWriteOrStopReplacementListener() = runBlocking {
        val parent = SupervisorJob()
        val oldListener = Listener()
        val replacement = Listener()
        val listeners = LinkedBlockingQueue<WppConfigListener>().apply {
            put(oldListener); put(replacement)
        }
        val writes = AtomicInteger()
        val receiver = WppConfigReceiver(
            CoroutineScope(parent + Dispatchers.IO), { listeners.take() },
            { WppConfigValues("old", "AA:BB:CC:DD:EE:FF") },
            { _, commit -> commit { writes.incrementAndGet() } },
        )
        val input = GatedInput(frame(), releaseOnClose = false)
        val client = Client(input)
        try {
            receiver.start()
            oldListener.clients.put(client)
            assertTrue(input.entered.await(2, TimeUnit.SECONDS))
            receiver.stop()
            receiver.start()
            await { replacement.attempts.get() == 1 }
            input.release.countDown()
            delay(100)
            assertEquals("old worker must not persist into the new generation", 0, writes.get())
            assertFalse(replacement.closed)
            assertTrue(receiver.status.value.startsWith("Listening"))
            assertNull(receiver.appliedConfig.value)
        } finally { input.release.countDown(); receiver.stop(); parent.cancel() }
    }

    @Test fun parentCancellationClosesListenerAndBlockedClient() = runBlocking {
        val f = Fixture()
        val input = GatedInput(frame())
        val client = Client(input)
        try {
            f.receiver.start()
            f.listener.clients.put(client)
            assertTrue(input.entered.await(2, TimeUnit.SECONDS))
            f.parent.cancel()
            await { client.closed && f.listener.closed }
            assertEquals("Stopped", f.receiver.status.value)
            assertEquals(0, f.writes.get())
            f.receiver.start()
            assertEquals("Stopped", f.receiver.status.value)
        } finally { input.release.countDown(); f.close() }
    }

    @Test fun stalledReadHasDeadlineThatClosesSocketWithoutApplyingConfig() = runBlocking {
        val f = Fixture()
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val input = object : InputStream() {
            override fun read(): Int {
                entered.countDown()
                released.await()
                throw IOException("closed")
            }
            override fun close() { released.countDown() }
        }
        val client = Client(input)
        try {
            f.receiver.start()
            f.listener.clients.put(client)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            withTimeout(6000) { while (!client.closed) delay(5) }
            assertEquals(0, f.writes.get())
            assertEquals(0, client.output.size())
            assertTrue(f.receiver.status.value.startsWith("Listening"))
        } finally { input.close(); f.close() }
    }

    @Test fun queuedPersistenceCannotApplyAfterStopAndRestart() = runBlocking {
        val parent = SupervisorJob()
        val oldListener = Listener()
        val replacement = Listener()
        val listeners = LinkedBlockingQueue<WppConfigListener>().apply {
            put(oldListener); put(replacement)
        }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val writes = AtomicInteger()
        val receiver = WppConfigReceiver(
            CoroutineScope(parent + Dispatchers.IO), { listeners.take() },
            { WppConfigValues("old", "AA:BB:CC:DD:EE:FF") },
            { _, commit ->
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                // Like DataStore: mutation runs later, after the suspending call began.
                commit { writes.incrementAndGet() }.also { finished.countDown() }
            },
        )
        val client = Client(ByteArrayInputStream(frame()))
        try {
            receiver.start(); oldListener.clients.put(client)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            receiver.stop(); receiver.start()
            await { replacement.attempts.get() == 1 }
            release.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertEquals("queued old edit must be fenced at mutation time", 0, writes.get())
            assertFalse(replacement.closed)
            assertNull(receiver.appliedConfig.value)
            assertEquals(0, client.output.size())
        } finally { release.countDown(); receiver.stop(); parent.cancel() }
    }

    @Test fun concurrentClientsCannotCommitTwoConfigurations() = runBlocking {
        val parent = SupervisorJob()
        val listener = Listener()
        val committed = CountDownLatch(1)
        val release = CountDownLatch(1)
        val decoded = AtomicInteger()
        val writes = AtomicInteger()
        val receiver = WppConfigReceiver(
            CoroutineScope(parent + Dispatchers.IO), { listener },
            { decoded.incrementAndGet(); WppConfigValues("network", "AA:BB:CC:DD:EE:FF") },
            { _, commit ->
                val applied = commit { writes.incrementAndGet() }
                committed.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                applied
            },
        )
        val first = Client(ByteArrayInputStream(frame()))
        val second = Client(ByteArrayInputStream(frame()))
        try {
            receiver.start(); listener.clients.put(first)
            assertTrue(committed.await(2, TimeUnit.SECONDS))
            listener.clients.put(second)
            await { decoded.get() == 2 }
            delay(100)
            assertEquals("one successful transfer per listener generation", 1, writes.get())
            release.countDown()
            await { first.closed && second.closed }
            assertEquals(1L, receiver.appliedConfig.value?.version)
        } finally { release.countDown(); receiver.stop(); parent.cancel() }
    }

    @Test fun nonemptyWhitespaceSsidIsPreservedAndAcknowledged() = runBlocking {
        val parent = SupervisorJob()
        val listener = Listener()
        val expected = WppConfigValues("   ", "AA:BB:CC:DD:EE:FF")
        val stored = java.util.concurrent.atomic.AtomicReference<WppConfigValues>()
        val receiver = WppConfigReceiver(
            CoroutineScope(parent + Dispatchers.IO), { listener }, { expected },
            { config, commit -> commit { stored.set(config) } },
        )
        val client = Client(ByteArrayInputStream(frame()))
        try {
            receiver.start(); listener.clients.put(client)
            await { client.closed }
            assertEquals(expected, stored.get())
            assertEquals(expected.ssid, receiver.appliedConfig.value?.ssid)
            assertEquals("OK", java.io.DataInputStream(ByteArrayInputStream(client.output.toByteArray())).readUTF())
        } finally { receiver.stop(); parent.cancel() }
    }

    @Test fun outcomeEventsReportAppliedAndStoppedWithoutNetworkIdentity() = runBlocking {
        val f = Fixture()
        val client = Client(ByteArrayInputStream(frame()))
        try {
            f.receiver.start(); f.listener.clients.put(client)
            await { WppConfigEvent.STOPPED in f.events }
            assertEquals(listOf(WppConfigEvent.LISTENER_UP, WppConfigEvent.APPLIED, WppConfigEvent.STOPPED), f.events)
            assertEquals(1, f.writes.get())
            assertEquals(" CarWifi ", f.receiver.appliedConfig.value?.ssid)
        } finally { f.close() }
    }

    @Test fun emptySsidIsRejectedAndListenerStaysAvailable() = runBlocking {
        val f = Fixture(WppConfigValues("", "AA:BB:CC:DD:EE:FF"))
        val client = Client(ByteArrayInputStream(frame()))
        try {
            f.receiver.start(); f.listener.clients.put(client)
            await { client.closed }
            assertEquals(listOf(WppConfigEvent.LISTENER_UP, WppConfigEvent.REJECTED), f.events)
            assertEquals("ERR", java.io.DataInputStream(ByteArrayInputStream(client.output.toByteArray())).readUTF())
            assertEquals(0, f.writes.get())
            assertTrue(f.receiver.status.value.startsWith("Listening"))
            f.receiver.stop()
            await { WppConfigEvent.STOPPED in f.events }
        } finally { f.close() }
    }

    private class Fixture(config: WppConfigValues = WppConfigValues(" CarWifi ", "AA:BB:CC:DD:EE:FF")) {
        val events = java.util.concurrent.CopyOnWriteArrayList<WppConfigEvent>()
        val parent = SupervisorJob()
        val listener = Listener()
        val writes = AtomicInteger()
        val receiver = WppConfigReceiver(
            CoroutineScope(parent + Dispatchers.IO),
            listen = { listener },
            decode = { config },
            persist = { _, commit -> commit { writes.incrementAndGet() } },
            onEvent = { events.add(it) },
        )
        fun close() { receiver.stop(); parent.cancel() }
    }

    private class Listener : WppConfigListener {
        val clients = LinkedBlockingQueue<Client>()
        val attempts = AtomicInteger()
        @Volatile var closed = false
        override fun accept(): WppConfigClient {
            attempts.incrementAndGet()
            while (!closed) {
                clients.poll(10, TimeUnit.MILLISECONDS)?.let { return it }
            }
            throw IOException("listener closed")
        }
        override fun close() { closed = true }
    }

    private class Client(override val input: InputStream) : WppConfigClient {
        override val output = ByteArrayOutputStream()
        @Volatile var closed = false
        override fun close() { closed = true; input.close() }
    }

    // Still returns bytes after close, modelling a read already delivered by the OS.
    private class GatedInput(bytes: ByteArray, private val releaseOnClose: Boolean = true) : InputStream() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val delegate = ByteArrayInputStream(bytes)
        override fun read(): Int {
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS)) { "test reader not released" }
            return delegate.read()
        }
        override fun close() { if (releaseOnClose) release.countDown() }
    }

    companion object {
        private fun frame(): ByteArray = ByteArrayOutputStream().also {
            DataOutputStream(it).apply { writeInt(2); writeBytes("{}") }
        }.toByteArray()
        private suspend fun await(condition: () -> Boolean) {
            withTimeout(2000) { while (!condition()) delay(5) }
        }
    }
}
