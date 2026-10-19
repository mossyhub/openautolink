package com.openautolink.app.ui.diagnostics

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.openautolink.app.diagnostics.MicrophoneDiagnosticLogStore
import com.shilapi.xcertplay.media.MicrophoneDiagnostic
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One NavHost-owned engine. UI notifications read current state rather than stale callback snapshots. */
class MicrophoneDiagnosticBinding(
    context: Context,
    eligibility: () -> Boolean,
    automotive: Boolean,
    freshPark: () -> Boolean,
) : Closeable {
    private val main = Handler(Looper.getMainLooper())
    private val logStore = MicrophoneDiagnosticLogStore(context)
    @Volatile private var closed = false
    private lateinit var target: MicrophoneDiagnosticController
    private val completionLock = Any()
    private val retainedResults = java.util.ArrayDeque<MicrophoneDiagnosticResult>()
    private val engine: MicrophoneDiagnostic = MicrophoneDiagnostic(context,
        eligibility = { !closed && ::target.isInitialized && target.isEligibleNow() },
        onState = { state ->
            // Preserve the exact immutable completion before a later attempt replaces engine.state.
            if (state.phase == MicrophoneDiagnosticPhase.COMPLETE) state.result?.let(::retain)
            main.post { if (!closed) target.refresh() }
        })
    val controller: MicrophoneDiagnosticController get() = target
    init {
        val backend = object : MicrophoneDiagnosticBackend {
            override val state get() = engine.state
            override fun inspect() = !closed && engine.inspect()
            override fun start(comparison: MicrophoneDiagnosticComparison) = !closed && engine.start(comparison)
            override fun startCustom(config: MicrophoneDiagnosticCustom) = !closed && engine.startCustom(config)
            override fun cancel() = engine.cancel()
        }
        target = MicrophoneDiagnosticController(backend, automotive, eligibility, freshPark)
    }
    private fun retain(result: MicrophoneDiagnosticResult) = synchronized(completionLock) {
        if (retainedResults.any { it === result }) return@synchronized
        retainedResults.addLast(result)
        while (retainedResults.size > 12) retainedResults.removeFirst()
        main.post { if (!closed && engine.state.result === result) target.summaryStored = null }
        try {
            writer.execute {
                val saved = logStore.save(result)
                main.post {
                    if (!closed && engine.state.result === result) target.summaryStored = saved
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Saturation must not silently discard another completed result or claim it was saved.
            main.post { if (!closed && engine.state.result === result) target.summaryStored = false }
        }
    }
    override fun close() {
        if (closed) return
        target.updateForeground(false)
        closed = true
        engine.cancel()
    }
    private companion object {
        val writer = ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,ArrayBlockingQueue(12),
            { task -> Thread(task,"oal-mic-summary").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy())
    }
}
