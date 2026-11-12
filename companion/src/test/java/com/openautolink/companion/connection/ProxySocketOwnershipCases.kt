package com.openautolink.companion.connection

import android.content.Context
import com.openautolink.companion.service.TcpAdvertiser
import kotlinx.coroutines.*
import java.net.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*

/** Real production proxy/advertiser, real loopback bytes; only Android launch/routing is supplied by the caller. */
object ProxySocketOwnershipCases {
    private const val DEADLINE = 5000L
    private fun demand(ok: Boolean, message: String) = check(ok) { message }
    private fun eventually(message: String, pred: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DEADLINE)
        while (!pred() && System.nanoTime() < end) Thread.sleep(5)
        demand(pred(), message)
    }
    private fun await(latch: CountDownLatch, message: String) = demand(latch.await(DEADLINE, TimeUnit.MILLISECONDS), message)
    private fun field(o: Any, name: String): Any? = o.javaClass.getDeclaredField(name).let { it.isAccessible = true; it.get(o) }
    private fun setField(o: Any, name: String, value: Any?) = o.javaClass.getDeclaredField(name).let { it.isAccessible = true; it.set(o, value) }
    private fun handle(a: TcpAdvertiser, s: Socket) {
        a.javaClass.getDeclaredMethod("handleCarConnection", Socket::class.java).let { it.isAccessible = true; it.invoke(a, s) }
    }
    private class TrackedSocket(port: Int) : Socket() {
        val inputs = AtomicInteger()
        val armed = AtomicBoolean()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        init { connect(InetSocketAddress("127.0.0.1", port)) }
        override fun getInputStream(): java.io.InputStream { inputs.incrementAndGet(); return super.getInputStream() }
        override fun isConnected(): Boolean {
            val real = super.isConnected()
            if (armed.compareAndSet(true, false)) { entered.countDown(); await(release, "finalizer released") }
            return real
        }
    }
    private data class Peers(val local: TrackedSocket, val peer: Socket)
    private class Fixture(private val context: Context) : AutoCloseable {
        val sockets = mutableListOf<Socket>()
        val proxies = mutableListOf<AaProxy>()
        var advertiser: TcpAdvertiser? = null
        val disconnected = AtomicInteger()
        var disconnectHook: () -> Unit = {}
        fun pair(): Peers = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val local = TrackedSocket(server.localPort)
            val peer = server.accept()
            listOf(local, peer).forEach { it.soTimeout = 2000; it.tcpNoDelay = true; sockets.add(it) }
            Peers(local, peer)
        }
        fun proxy(car: Socket? = null, listener: AaProxy.Listener? = null) = AaProxy(car, listener).also { proxies.add(it); it.start() }
        fun aa(p: AaProxy) = Socket("127.0.0.1", p.localPort).also { it.soTimeout = 2000; sockets.add(it) }
        fun advertiser(mode: String, car: Socket? = null): TcpAdvertiser {
            val a = TcpAdvertiser(context, object : TcpAdvertiser.StateListener {
                override fun onConnecting() {}
                override fun onProxyConnected() {}
                override fun onProxyDisconnected() { disconnected.incrementAndGet(); disconnectHook() }
                override fun onLaunchTimeout() {}
            })
            advertiser = a
            setField(a, "isRunning", true)
            when (mode) {
                "constructor" -> handle(a, car!!)
                "warm" -> a.preWarmAaPipeline()
                "wpp" -> a.javaClass.getDeclaredMethod("ensureProxyForWpp").let { it.isAccessible = true; it.invoke(a) }
                else -> error(mode)
            }
            eventually("advertiser publishes proxy") { (field(a, "activeProxy") as? AaProxy)?.isAccepting == true }
            proxies.add(active(a))
            if (mode != "constructor" && car != null) handle(a, car)
            return a
        }
        fun active(a: TcpAdvertiser) = field(a, "activeProxy") as AaProxy
        override fun close() {
            disconnectHook = {}
            sockets.filterIsInstance<TrackedSocket>().forEach { it.release.countDown() }
            advertiser?.stop()
            proxies.forEach { it.stop() }
            sockets.forEach { runCatching { it.close() } }
            runBlocking { withTimeout(DEADLINE) {
                proxies.forEach { (field(it, "scope") as CoroutineScope).coroutineContext[Job]!!.join() }
                advertiser?.let { (field(it, "scope") as CoroutineScope).coroutineContext[Job]!!.join() }
            } }
        }
    }
    private fun bridge(aa: Socket, car: Socket, label: String) {
        val forward = "aa-to-car:$label".toByteArray()
        aa.getOutputStream().write(forward); aa.getOutputStream().flush()
        demand(car.getInputStream().readNBytes(forward.size).contentEquals(forward), "exact AA->car bytes $label")
        val reverse = "car-to-aa:$label".toByteArray()
        car.getOutputStream().write(reverse); car.getOutputStream().flush()
        demand(aa.getInputStream().readNBytes(reverse.size).contentEquals(reverse), "exact car->AA bytes $label")
    }
    private fun idle(p: AaProxy) = eventually("bridge retired") { !p.hasPendingBridge() }
    fun prewarm(context: Context) = Fixture(context).use { f ->
        val p = f.proxy(); val aa = f.aa(p)
        eventually("prewarm waiter") { p.hasPendingBridge() && !p.hasActiveBridge() }
        val car = f.pair(); p.updateCarSocket(car.local); bridge(aa, car.peer, "prewarm")
    }
    fun idleReplacement(context: Context) = Fixture(context).use { f ->
        val old = f.pair(); val fresh = f.pair(); val p = f.proxy(old.local)
        old.local.close(); p.updateCarSocket(fresh.local)
        bridge(f.aa(p), fresh.peer, "idle-replacement")
        demand(old.local.inputs.get() == 0, "constructor was superseded")
    }
    fun neverPumped(context: Context) = Fixture(context).use { f ->
        val car = f.pair(); val first = AtomicBoolean(true)
        val p = f.proxy(null, object : AaProxy.Listener {
            override fun onConnected() { if (first.compareAndSet(true, false)) error("injected pre-pump setup failure") }
            override fun onDisconnected(unexpected: Boolean) {}
        })
        p.updateCarSocket(car.local); val aa = f.aa(p)
        demand(aa.getInputStream().read() == -1, "setup failure closes AA"); idle(p)
        demand(!car.local.isClosed, "never-pumped car retained")
        bridge(f.aa(p), car.peer, "never-pumped-reuse")
    }
    fun failedFallback(context: Context) = Fixture(context).use { f ->
        val old = f.pair(); val a = f.advertiser("constructor", old.local); val p = f.active(a)
        val aa = f.aa(p); bridge(aa, old.peer, "initial")
        old.peer.setSoLinger(true, 0); old.peer.close(); aa.shutdownOutput(); idle(p)
        val retry = f.aa(p)
        eventually("retry waits for fresh car, never reuses failed original") { p.hasPendingBridge() && !p.hasActiveBridge() }
        demand(old.local.inputs.get() == 1, "failed original input requested only once")
        val fresh = f.pair(); handle(a, fresh.local); bridge(retry, fresh.peer, "fresh-after-failure")
        retry.shutdownOutput(); fresh.peer.shutdownOutput(); idle(p)
        val retry2 = f.aa(p)
        eventually("later retry waits instead of immutable fallback") { p.hasPendingBridge() && !p.hasActiveBridge() }
        demand(old.local.inputs.get() == 1, "original never resurrected after replacement")
        val newest = f.pair(); handle(a, newest.local); bridge(retry2, newest.peer, "second-recovery")
    }
    fun oneDirection(context: Context, reset: Boolean) = Fixture(context).use { f ->
        val car = f.pair()
        car.local.soTimeout = 0 // The sibling must be unblocked by retirement, not a fixture read timeout.
        val p = f.proxy(car.local); val aa = f.aa(p)
        bridge(aa, car.peer, "one-direction")
        if (reset) { car.peer.setSoLinger(true, 0); car.peer.close() } else aa.shutdownOutput()
        idle(p) // The opposite peer deliberately stays open: joinAll must not strand its reader.
        demand(car.local.isClosed, "pumped socket retired on first direction termination")
        demand(aa.getInputStream().read() == -1, "other AA direction unblocked")
    }
    fun advertiserRetires(context: Context, mode: String) = Fixture(context).use { f ->
        val car = f.pair(); val a = f.advertiser(mode, car.local); val p = f.active(a)
        val aa = f.aa(p); bridge(aa, car.peer, mode)
        aa.shutdownOutput(); car.peer.shutdownOutput(); idle(p)
        eventually("exact advertiser socket retired $mode") { field(a, "activeCarSocket") == null && car.local.isClosed }
        demand(field(a, "bridgeRelaunchJob") == null, "no launch against absent car $mode")
        demand(f.disconnected.get() == 1, "one disconnected notification $mode")
    }
    fun replacementDuringFinalizer(context: Context) = Fixture(context).use { f ->
        val old = f.pair(); val fresh = f.pair(); val first = AtomicBoolean(true)
        val p = f.proxy(null, object : AaProxy.Listener {
            override fun onConnected() {
                if (first.compareAndSet(true, false)) {
                    old.local.armed.set(true)
                    error("injected pre-pump setup failure")
                }
            }
            override fun onDisconnected(unexpected: Boolean) {}
        })
        p.updateCarSocket(old.local); val aa = f.aa(p)
        await(old.local.entered, "old never-pumped finalizer reached retention predicate")
        try {
            p.updateCarSocket(fresh.local)
        } finally { old.local.release.countDown() }
        demand(aa.getInputStream().read() == -1, "failed AA closed"); idle(p)
        demand(field(p, "pendingCarSocket") === fresh.local, "old finalizer cannot overwrite fresh pending socket")
        bridge(f.aa(p), fresh.peer, "replacement-not-overwritten")
        demand(fresh.local.inputs.get() == 1, "fresh pending not overwritten by old finalizer")
    }
    fun replacementDuringCallback(context: Context, mode: String) = Fixture(context).use { f ->
        val old = f.pair(); val a = f.advertiser(mode, old.local); val p = f.active(a)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        f.disconnectHook = { entered.countDown(); await(release, "old callback released") }
        val aa = f.aa(p); bridge(aa, old.peer, "before-old-callback-$mode")
        aa.shutdownOutput(); old.peer.shutdownOutput(); await(entered, "old disconnect callback paused")
        val fresh = f.pair()
        try { handle(a, fresh.local) } finally { release.countDown() }
        eventually("old callback returned") { !p.hasPendingBridge() }
        demand(!fresh.local.isClosed && field(a, "activeCarSocket") === fresh.local, "old callback cannot close successor $mode")
        bridge(f.aa(p), fresh.peer, "successor-$mode")
    }
    fun staleProxyStop(context: Context) = Fixture(context).use { f ->
        val old = f.pair(); val a = f.advertiser("constructor", old.local); val p = f.active(a)
        bridge(f.aa(p), old.peer, "old-proxy")
        val fresh = f.pair(); handle(a, fresh.local)
        eventually("new proxy installed") { f.active(a) !== p && f.active(a).isAccepting }
        val successor = f.active(a); f.proxies.add(successor)
        p.stop(); bridge(f.aa(successor), fresh.peer, "stale-stop-safe")
        demand(field(a, "activeCarSocket") === fresh.local && !fresh.local.isClosed, "stale stop keeps successor")
    }
    fun staleCallback(context: Context) = Fixture(context).use { f ->
        val old = f.pair(); val a = f.advertiser("constructor", old.local); val p = f.active(a)
        bridge(f.aa(p), old.peer, "old-event-source")
        val listener = field(p, "listener")!!
        val oldBridge = runCatching { field(a, "activeProxyBridge") }.getOrNull()
        val fresh = f.pair(); handle(a, fresh.local)
        eventually("replacement proxy published") { f.active(a) !== p && f.active(a).isAccepting }
        val successor = f.active(a); f.proxies.add(successor)
        val aa = f.aa(successor); bridge(aa, fresh.peer, "before-delayed-old-event")
        // Deliver the actual listener's old event after replacement, not a fake ownership model.
        val exact = listener.javaClass.declaredMethods.singleOrNull { it.name == "onDisconnected" && it.parameterCount == 4 }
        if (exact != null) { exact.isAccessible = true; exact.invoke(listener, old.local, oldBridge ?: Any(), true, false) }
        else listener.javaClass.getDeclaredMethod("onDisconnected", Boolean::class.javaPrimitiveType).let {
            it.isAccessible = true; it.invoke(listener, true)
        }
        demand(field(a, "activeCarSocket") === fresh.local && !fresh.local.isClosed, "stale proxy callback leaves exact successor intact")
        bridge(aa, fresh.peer, "after-delayed-old-event")
        demand(field(a, "bridgeRelaunchJob") == null, "stale event schedules no relaunch")
    }
    fun cases(context: Context): Map<String, () -> Unit> = linkedMapOf(
        "prewarm" to { prewarm(context) }, "idleReplacement" to { idleReplacement(context) },
        "neverPumped" to { neverPumped(context) }, "failedFallback" to { failedFallback(context) },
        "oneDirectionEof" to { oneDirection(context, false) }, "oneDirectionReset" to { oneDirection(context, true) },
        "constructorRetires" to { advertiserRetires(context, "constructor") },
        "warmRetires" to { advertiserRetires(context, "warm") }, "wppRetires" to { advertiserRetires(context, "wpp") },
        "replacementDuringFinalizer" to { replacementDuringFinalizer(context) },
        "constructorCallback" to { replacementDuringCallback(context, "constructor") },
        "warmCallback" to { replacementDuringCallback(context, "warm") },
        "wppCallback" to { replacementDuringCallback(context, "wpp") },
        "staleProxyStop" to { staleProxyStop(context) },
        "staleCallback" to { staleCallback(context) },
    )
}
