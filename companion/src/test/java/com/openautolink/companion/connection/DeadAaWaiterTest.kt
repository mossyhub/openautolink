package com.openautolink.companion.connection

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import com.openautolink.companion.service.TcpAdvertiser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class DeadAaWaiterTest {
    private fun eventually(message: String, predicate: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!predicate() && System.nanoTime() < end) Thread.sleep(5)
        check(predicate()) { message }
    }
    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private class LaunchContext(base: Context) : ContextWrapper(base) {
        val broadcasts = CopyOnWriteArrayList<Intent>()
        override fun sendBroadcast(intent: Intent) { broadcasts.add(intent) }
        override fun startActivity(intent: Intent) = Unit
    }
    private class Fixture : AutoCloseable {
        val sockets = mutableListOf<Socket>()
        val proxies = mutableListOf<AaProxy>()
        var advertiser: TcpAdvertiser? = null
        fun pair(): Pair<Socket, Socket> = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val local = Socket("127.0.0.1", server.localPort)
            (local to server.accept()).also { (a, b) ->
                listOf(a, b).forEach { it.soTimeout = 2000; sockets.add(it) }
            }
        }
        fun aa(proxy: AaProxy) = Socket("127.0.0.1", proxy.localPort).also { it.soTimeout = 2000; sockets.add(it) }
        fun proxy(car: Socket? = null, listener: AaProxy.Listener? = null) = AaProxy(car, listener).also { proxies.add(it); it.start() }
        override fun close() {
            advertiser?.stop()
            proxies.forEach { it.stop() }
            sockets.forEach { runCatching { it.close() } }
            runBlocking { withTimeout(5000) {
                (proxies.map { it as Any } + listOfNotNull(advertiser)).forEach {
                    val scope = it.javaClass.getDeclaredField("scope").apply { isAccessible = true }.get(it) as CoroutineScope
                    scope.coroutineContext[Job]!!.join()
                }
            } }
        }
    }
    private fun exchange(aa: Socket, car: Socket, initial: ByteArray = byteArrayOf()) {
        val payload = ByteArray(17001) { (it * 31).toByte() }
        aa.getOutputStream().write(payload)
        check(car.getInputStream().readNBytes(initial.size + payload.size).contentEquals(initial + payload)) { "AA bytes preserved exactly once and in order" }
        car.getOutputStream().write(payload.reversedArray())
        check(aa.getInputStream().readNBytes(payload.size).contentEquals(payload.reversedArray())) { "reverse bytes delivered" }
    }
    @Test fun closedWaiterRetiresBeforeCarAndDoesNotStealSuccessor() = Fixture().use { f ->
        val connected = AtomicInteger()
        val p = f.proxy(listener = object : AaProxy.Listener {
            override fun onConnected() { connected.incrementAndGet() }
            override fun onDisconnected(unexpected: Boolean) = Unit
        })
        val dead = f.aa(p)
        eventually("AA is waiting") { p.hasWaitingBridge() }
        dead.close()
        eventually("closed AA waiter must retire without a car") { !p.hasPendingBridge() }
        val (car, peer) = f.pair()
        check(p.updateCarSocket(car))
        check(!car.isClosed && connected.get() == 0) { "no connected callback or car consumption for dead waiter" }
        exchange(f.aa(p), peer)
        check(connected.get() == 1)
    }
    @Test fun earlyBytesAndSilentWaiterSurvivePolling() = Fixture().use { f ->
        val p = f.proxy()
        val aa = f.aa(p)
        eventually("silent waiter") { p.hasWaitingBridge() }
        // Multiple actual poll periods, not a read-timeout-driven disconnect.
        Thread.sleep(250)
        check(p.hasWaitingBridge())
        val initial = ByteArray(12003) { (it xor 0xA5).toByte() }
        aa.getOutputStream().write(initial)
        Thread.sleep(150)
        val (car, peer) = f.pair()
        check(p.updateCarSocket(car))
        exchange(aa, peer, initial)
    }
    @Test fun bytesThenEofCannotConsumeCar() = Fixture().use { f ->
        val p = f.proxy()
        val aa = f.aa(p)
        eventually("waiter") { p.hasWaitingBridge() }
        aa.getOutputStream().write(ByteArray(12003) { it.toByte() })
        aa.shutdownOutput()
        eventually("EOF behind early bytes retires waiter") { !p.hasPendingBridge() }
        val (car, peer) = f.pair()
        check(p.updateCarSocket(car))
        exchange(f.aa(p), peer)
    }
    @Test fun excessivePreCarReadAheadIsBoundedWithoutConsumingCar() = Fixture().use { f ->
        val p = f.proxy()
        val aa = f.aa(p)
        eventually("waiter") { p.hasWaitingBridge() }
        aa.getOutputStream().write(ByteArray(65537) { it.toByte() })
        eventually("oversized pre-car input retired at finite read-ahead budget") { !p.hasPendingBridge() }
        val (car, peer) = f.pair()
        check(p.updateCarSocket(car))
        exchange(f.aa(p), peer)
    }
    @Test fun replacementAaOwnsCarDespiteOldWaiterEof() = Fixture().use { f ->
        val p = f.proxy()
        val old = f.aa(p)
        eventually("old waiter") { p.hasWaitingBridge() }
        old.getOutputStream().write(byteArrayOf(91, 92, 93))
        val generation = (field(p, "waitGeneration").get(p) as AtomicInteger).get()
        val fresh = f.aa(p)
        eventually("replacement accepted") { (field(p, "waitGeneration").get(p) as AtomicInteger).get() > generation }
        old.shutdownOutput()
        val (car, peer) = f.pair()
        check(p.updateCarSocket(car))
        exchange(fresh, peer)
    }
    @Test fun pollingRestoresExistingReadTimeout() = Fixture().use { f ->
        val p = f.proxy()
        val aa = f.aa(p)
        eventually("waiter") { p.hasWaitingBridge() }
        val lock = field(p, "ownershipLock").get(p)
        val accepted = synchronized(lock) {
            @Suppress("UNCHECKED_CAST")
            (field(p, "aaSockets").get(p) as Set<Socket>).single().also { it.soTimeout = 1437 }
        }
        repeat(5) {
            check(p.hasWaitingBridge())
            synchronized(lock) { check(accepted.soTimeout == 1437) { "poll changed caller timeout" } }
            Thread.sleep(60)
        }
        val (car, peer) = f.pair()
        check(p.updateCarSocket(car))
        exchange(aa, peer)
        check(accepted.soTimeout == 1437)
    }
    @Test fun stopRetiresSilentWaiterWithoutClaimingCar() = Fixture().use { f ->
        val connected = AtomicInteger()
        val p = f.proxy(listener = object : AaProxy.Listener {
            override fun onConnected() { connected.incrementAndGet() }
            override fun onDisconnected(unexpected: Boolean) = Unit
        })
        val aa = f.aa(p)
        eventually("waiter") { p.hasWaitingBridge() }
        p.stop()
        eventually("stopped waiter retired") { !p.hasPendingBridge() }
        check(aa.getInputStream().read() == -1 && connected.get() == 0)
        val (car, _) = f.pair()
        check(!p.updateCarSocket(car) && !car.isClosed)
    }
    @Test fun delayedWaiterCallbackCannotInviteForNewerAaTicket() = Fixture().use { f ->
        val context = LaunchContext(RuntimeEnvironment.getApplication())
        val a = TcpAdvertiser(context, object : TcpAdvertiser.StateListener {
            override fun onConnecting() = Unit
            override fun onProxyConnected() = Unit
            override fun onProxyDisconnected() = Unit
            override fun onLaunchTimeout() = Unit
        })
        f.advertiser = a
        field(a, "isRunning").set(a, true)
        a.preWarmAaPipeline()
        eventually("proxy") { (field(a, "activeProxy").get(a) as? AaProxy)?.isAccepting == true }
        val p = field(a, "activeProxy").get(a) as AaProxy
        f.proxies.add(p)
        val old = f.aa(p)
        eventually("old waiter") { p.hasWaitingBridge() }
        // Original production has no waiter token; it must still fail on unobserved
        // EOF below, rather than on a reflection/setup error in the baseline run.
        val oldWaiter = runCatching { field(p, "waitingAa").get(p) }.getOrElse { Any() }
        val generation = (field(p, "waitGeneration").get(p) as AtomicInteger).get()
        val (car, peer) = f.pair()
        val before = context.broadcasts.size
        synchronized(field(a, "proxyOwnershipLock").get(a)) {
            a.javaClass.getDeclaredMethod("handleCarConnection", Socket::class.java).apply { isAccessible = true }.invoke(a, car)
            check(context.broadcasts.size == before)
            old.shutdownOutput()
            eventually("old EOF observed") { !p.hasWaitingBridge() }
            val replacement = f.aa(p)
            eventually("newer AA ticket accepted") { (field(p, "waitGeneration").get(p) as AtomicInteger).get() > generation }
            replacement.shutdownOutput()
            Thread.sleep(100)
            val listener = field(p, "listener").get(p)
            val callback = listener.javaClass.declaredMethods.single { it.name == "onWaitingPeerClosed" }
            callback.isAccessible = true
            if (callback.parameterCount == 1) callback.invoke(listener, car)
            else callback.invoke(listener, car, oldWaiter)
            check(context.broadcasts.size == before) { "stale waiter callback must not invite for a newer AA ticket" }
        }
        eventually("current ticket reinvites exactly once") { context.broadcasts.size == before + 1 }
        check(!car.isClosed)
        exchange(f.aa(p), peer)
    }
    @Test fun fullAdvertiserReinvitesAfterDeadPrewarmInsteadOfSuppressingLaunch() = advertiserReinvite(false)
    @Test fun fullWppAdvertiserReinvitesAfterDeadWaiter() = advertiserReinvite(true)
    @Test fun eofAfterSuppressedOfferPreservesCarAndReinvites() = advertiserReinvite(false, true)
    private fun advertiserReinvite(wpp: Boolean, eofAfterOffer: Boolean = false) = Fixture().use { f ->
        val context = LaunchContext(RuntimeEnvironment.getApplication())
        val connected = AtomicInteger()
        val disconnected = AtomicInteger()
        val a = TcpAdvertiser(context, object : TcpAdvertiser.StateListener {
            override fun onConnecting() = Unit
            override fun onProxyConnected() { connected.incrementAndGet() }
            override fun onProxyDisconnected() { disconnected.incrementAndGet() }
            override fun onLaunchTimeout() = Unit
        })
        f.advertiser = a
        field(a, "isRunning").set(a, true)
        if (wpp) a.javaClass.getDeclaredMethod("ensureProxyForWpp").apply { isAccessible = true }.invoke(a)
        else a.preWarmAaPipeline()
        eventually("warm proxy") { (field(a, "activeProxy").get(a) as? AaProxy)?.isAccepting == true }
        val p = field(a, "activeProxy").get(a) as AaProxy
        f.proxies.add(p)
        val dead = f.aa(p)
        eventually("advertiser prewarm waiter") { p.hasWaitingBridge() }
        val before = context.broadcasts.size
        val (car, peer) = f.pair()
        fun handle() = a.javaClass.getDeclaredMethod("handleCarConnection", Socket::class.java).apply { isAccessible = true }.invoke(a, car)
        if (eofAfterOffer) {
            // Hold the real lease lock so the waiter cannot claim the offered car.
            // Production advertiser sees a genuinely live waiter and suppresses launch;
            // FIN arrives before the existing coroutine is allowed to take the lease.
            synchronized(field(p, "ownershipLock").get(p)) {
                handle()
                check(context.broadcasts.size == before) { "live waiter must suppress duplicate invitation" }
                dead.shutdownOutput()
            }
        } else {
            dead.close()
            Thread.sleep(100) // Real loopback FIN, before actual suppression decision.
            handle()
        }
        eventually("fresh car requires new AA invitation, not stale waiter suppression") { context.broadcasts.size == before + 1 }
        check(connected.get() == 0 && disconnected.get() == 0 && !car.isClosed) { "dead waiter publishes no stale state" }
        exchange(f.aa(p), peer)
        eventually("one real connected publication") { connected.get() == 1 }
        check(disconnected.get() == 0)
    }
}
