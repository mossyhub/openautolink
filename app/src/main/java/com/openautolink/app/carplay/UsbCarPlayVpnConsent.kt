package com.openautolink.app.carplay

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** UI launches only system-owned VPN consent, never approves it programmatically. */
object UsbCarPlayVpnConsent {
    data class Request(val generation: Long, val intent: Intent)
    private class Pending(val request: Request) {
        val completed = CountDownLatch(1)
        @Volatile var granted = false
    }
    private val lock = Any()
    private var nextGeneration = 0L
    private var pending: Pending? = null
    private val _request = MutableStateFlow<Request?>(null)
    val request = _request.asStateFlow()

    fun await(intent: Intent, isCancelled: () -> Boolean): Boolean {
        val attempt = synchronized(lock) {
            check(pending == null) { "USB VPN consent already pending" }
            Pending(Request(++nextGeneration, intent)).also { pending = it; _request.value = it.request }
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
            while (!isCancelled() && System.nanoTime() < deadline) {
                if (attempt.completed.await(100, TimeUnit.MILLISECONDS)) return attempt.granted && !isCancelled()
            }
            return false
        } finally {
            synchronized(lock) {
                if (pending === attempt) { pending = null; _request.value = null }
            }
        }
    }

    fun complete(generation: Long, granted: Boolean) = synchronized(lock) {
        pending?.takeIf { it.request.generation == generation }?.let {
            it.granted = granted
            it.completed.countDown()
        }
    }
}
