package com.openautolink.app.carplay

import com.openautolink.carplaylab.metadata.CarPlayMetadataIntake
import com.openautolink.carplaylab.metadata.RuntimeMetadataLink
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.transport.Iap2ArtworkTransfer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Constant-space retained intake. Host work is coalesced on one exact-link worker. */
internal class OalCarPlayMetadataLink(
    elapsedMs: () -> Long,
    private val receipt: (String) -> Unit,
    private val host: CarPlayMetadataHost? = null,
) : RuntimeMetadataLink {
    @Volatile var capturePlaybackObservation: () -> ((Boolean?) -> Unit)? = { null }
    private val intake = CarPlayMetadataIntake(elapsedMs).apply { onDiagnostic = receipt }
    override fun mediaAvailability(ready:Boolean) { if (!retired.get()) host?.mediaAvailability(ready) }
    private val retired = AtomicBoolean()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "oal-carplay-metadata").apply { isDaemon = true } }
    private var scheduled = false
    private var dirty = false
    private var authenticated = false
    private var started = false
    @Volatile private var published = false
    private var accepted = 0L
    // Worker-owned last returned publication, separate from the first music receipt.
    private var publishedNavigation: com.openautolink.carplaylab.metadata.CarPlayNavigationMetadata? = null
    private var publishedRoute: com.openautolink.carplaylab.metadata.CarPlayRouteMetadata? = null
    @Synchronized override fun authenticate() {
        if (!retired.get()) { authenticated = true; intake.authenticate(); schedule() }
    }
    @Synchronized override fun handle(frame: Iap2Frame): Boolean {
        if (retired.get()) return frame.messageId in setOf(0x5001,0x5201,0x5202)
        val observation=if(authenticated) capturePlaybackObservation() else null
        val handled = intake.handle(frame)
        intake.explicitPlayback?.let { observation?.invoke(it.playing) }
        if (handled && authenticated) {
            accepted++
            if (accepted == 1L || accepted % 100 == 0L) receipt("carplay-metadata received=$accepted media=${intake.media != null} guidance=${intake.navigation != null} hostPublication=$published")
            schedule()
        }
        return handled
    }
    @Synchronized override fun offerArtwork(transfer: Iap2ArtworkTransfer) {
        if (retired.get()) return
        intake.offerArtwork(transfer.id,transfer.bytes)
        schedule()
    }
    @Synchronized override fun tick() { if (!retired.get()) { intake.tick(); schedule() } }
    /** Caller owns intake monitor. At most one queued/running worker; newer snapshots replace old. */
    private fun schedule() {
        if (!authenticated || retired.get() || host == null) return
        dirty = true
        if (scheduled) return
        scheduled = true
        worker.execute {
            try {
                if (!retired.get() && !started) { started = host.start(); check(started) { "Metadata host admission denied" } }
                while (!retired.get()) {
                    val snapshot = synchronized(this) {
                        if (!dirty) return@execute
                        dirty = false
                        Triple(intake.media, intake.routeMetadata, intake.artwork)
                    }
                    if (!retired.get()) {
                        host.publishRoute(snapshot.first, snapshot.second, snapshot.third)
                        val instruction = snapshot.second?.instruction
                        if (snapshot.second != publishedRoute) {
                            val transition = when {
                                snapshot.second == null -> "end"
                                publishedRoute == null -> "start"
                                else -> "change"
                            }
                            val availability = when {
                                snapshot.second == null -> "none"
                                instruction == null -> "loading"
                                else -> "ready"
                            }
                            publishedRoute = snapshot.second
                            runCatching { receipt("carplay-route publication=returned active=${snapshot.second != null} instruction=$availability transition=$transition") }
                        }
                        if (instruction != publishedNavigation) {
                            val transition = when {
                                instruction == null -> "clear"
                                publishedNavigation == null -> "start"
                                else -> "change"
                            }
                            publishedNavigation = instruction
                            runCatching { receipt("carplay-nav publication=returned guidance=${instruction != null} transition=$transition") }
                        }
                        if (!published && (snapshot.first != null || snapshot.second != null)) {
                            published = true
                            receipt("carplay-metadata hostPublication=true media=${snapshot.first != null} guidance=${instruction != null}")
                        }
                    }
                }
            } catch (_: Exception) {
                revoke()
                receipt("carplay-metadata publication=failed")
            } finally {
                synchronized(this) {
                    scheduled = false
                    if (dirty && !retired.get()) schedule()
                }
            }
        }
    }
    override fun revoke() { retired.set(true); host?.mediaAvailability(false); synchronized(this) { intake.revoke(); dirty = false } }
    override fun close() {
        revoke()
        worker.shutdown()
        // Receiver shutdown may interrupt its control worker; metadata retirement must still be awaited.
        var interrupted = false
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        try {
            while (!worker.isTerminated) {
                val remaining = deadline - System.nanoTime()
                check(remaining > 0) { "Metadata worker retirement timed out" }
                try { check(worker.awaitTermination(remaining, TimeUnit.NANOSECONDS)) { "Metadata worker retirement timed out" } }
                catch (_: InterruptedException) { interrupted = true }
            }
            host?.close()
        } finally { if (interrupted) Thread.currentThread().interrupt() }
    }
}
