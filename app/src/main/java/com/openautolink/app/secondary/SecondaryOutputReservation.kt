package com.openautolink.app.secondary

import java.util.concurrent.CompletableFuture

/** Atomic preparation/install cancellation boundary. Callbacks always run outside its monitor. */
internal class SecondaryOutputReservation<T>(
    private val revoke: (T) -> Unit,
    private val detach: (T) -> CompletableFuture<Boolean>,
) {
    private val lock = Any()
    private var cancelled = false
    private val owners = mutableListOf<T>()
    private var retirement: CompletableFuture<Boolean>? = null
    fun install(owner: T): Boolean {
        val accepted = synchronized(lock) {
            if (cancelled) false else { owners.add(owner); true }
        }
        if (!accepted) { revoke(owner); detach(owner) }
        return accepted
    }
    fun snapshot(): List<T> = synchronized(lock) { owners.toList() }
    fun retire(): CompletableFuture<Boolean> {
        val result: CompletableFuture<Boolean>
        val exact: List<T>
        synchronized(lock) {
            retirement?.let { return it }
            cancelled = true
            result = CompletableFuture<Boolean>().also { retirement = it }
            exact = owners.toList()
        }
        val futures = exact.map { owner ->
            try {
                revoke(owner)
                detach(owner).handle { success, failure ->
                    val acknowledged = failure == null && success == true
                    if (acknowledged) synchronized(lock) { owners.remove(owner) }
                    acknowledged
                }
            } catch (_: Exception) { CompletableFuture.completedFuture(false) }
        }
        CompletableFuture.allOf(*futures.toTypedArray()).whenComplete { _, failure ->
            result.complete(failure == null && futures.all { it.getNow(false) })
        }
        return result
    }
}
