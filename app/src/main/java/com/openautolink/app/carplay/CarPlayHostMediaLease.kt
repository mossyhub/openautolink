package com.openautolink.app.carplay

/** Distinct from audio tracks/focus and from browser discovery. Never persisted. */
enum class CarPlayHostMediaMode { NORMAL, SUPPRESS_PLAYING, INACTIVE }

/** Identity is the attempt, NOT the reusable protocol ownership token or metadata host. */
class CarPlayHostMediaLease(val mode: CarPlayHostMediaMode) {
    // Process-lifecycle sink, independent of authenticated receiver/metadata-host lifetime.
    @Volatile private var diagnosticDirectory: java.io.File? = null
    internal fun prepareDiagnostics(context: android.content.Context) {
        diagnosticDirectory = runCatching { context.getExternalFilesDir(null)?.let { java.io.File(it,"openautolink/logs") } }.getOrNull()
    }
    private val reporter = CarPlayAudioDiagnosticReporter(diagnosticSessions.incrementAndGet(),System::nanoTime) { line ->
        com.openautolink.app.diagnostics.DiagnosticLog.i("carplay-host-media",line)
        android.util.Log.i("OalHostMedia",line)
        HostMediaDiagnosticSink.persist(diagnosticDirectory,line)
    }
    internal var receipt: (String) -> Unit = { HostMediaDiagnosticSink.offer(reporter,it) }
    companion object { private val diagnosticSessions = java.util.concurrent.atomic.AtomicLong() }
    private var details = 0
    private var lifecycle = 0
    private var limited = false
    @Synchronized internal fun record(event: String, active: String, result: String) {
        val detail = event == "withhold"
        if ((detail && details >= 32) || (!detail && lifecycle >= 16)) {
            if (!limited) {
                limited = true
                runCatching { receipt("media stage=host-media event=limit active=unknown mode=${label()} result=withheld lost=1") }
            }
            return
        }
        if (detail) details++ else lifecycle++
        runCatching { receipt("media stage=host-media event=$event active=$active mode=${label()} result=$result lost=0") }
    }
    private fun label() = when(mode) {
        CarPlayHostMediaMode.NORMAL -> "normal"
        CarPlayHostMediaMode.SUPPRESS_PLAYING -> "suppress"
        CarPlayHostMediaMode.INACTIVE -> "inactive"
    }
}

/** No synchronous logger on a manager/ownership callback; bounded process queue outlives hosts. */
internal object HostMediaDiagnosticSink {
    private val lost = java.util.concurrent.atomic.AtomicLong()
    private val worker = java.util.concurrent.ThreadPoolExecutor(1,1,0L,java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(64),
        java.util.concurrent.ThreadFactory { Thread(it,"oal-host-media-diagnostics").apply { isDaemon=true } },
        java.util.concurrent.ThreadPoolExecutor.AbortPolicy())
    /** Only this worker appends/rotates; two finite numeric-only files, no identifiers/art. */
    fun persist(directory: java.io.File?, line: String) {
        try {
            checkNotNull(directory)
            check(directory.isDirectory || directory.mkdirs())
            val file=java.io.File(directory,"oal_host_media.log")
            val bytes=(line+"\n").toByteArray(Charsets.UTF_8)
            if (file.length()+bytes.size > 32*1024) {
                val previous=java.io.File(directory,"oal_host_media_previous.log")
                check(!previous.exists() || previous.delete())
                check(file.renameTo(previous))
            }
            java.io.FileOutputStream(file,true).use { it.write(bytes) }
        } catch (_: Exception) {
            lost.updateAndGet { if(it==Long.MAX_VALUE) it else it+1 }
        }
    }
    /** Manual uploader barrier: never wait on this from a manager or metadata callback. */
    fun flush(): Boolean = try {
        worker.submit {}.get(2,java.util.concurrent.TimeUnit.SECONDS)
        true
    } catch (_: Exception) { false }

    /** Same bounded durable worker and manual-upload barrier; never disk IO on capture. */
    fun offerAaMic(directory: java.io.File?, marker: String) {
        val safe = com.openautolink.app.audio.AaMicDiagnostics.sanitize(marker) ?: return
        try { worker.execute { persist(directory, safe) } }
        catch (_: java.util.concurrent.RejectedExecutionException) {
            lost.updateAndGet { if(it==Long.MAX_VALUE) it else it+1 }
        }
    }

    fun offer(reporter: CarPlayAudioDiagnosticReporter, marker: String) {
        try {
            worker.execute {
                val drops=lost.getAndSet(0)
                reporter.record(if (drops > 0) marker.substringBeforeLast("lost=")+"lost=$drops" else marker)
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            lost.updateAndGet { if(it==Long.MAX_VALUE) it else it+1 }
        }
    }
}

interface CarPlayHostMediaLeaseSource {
    fun begin(lease: CarPlayHostMediaLease)
    fun finish(lease: CarPlayHostMediaLease)
    companion object {
        fun forContext(context: android.content.Context): CarPlayHostMediaLeaseSource {
            val manager = com.openautolink.app.media.OalMediaSessionManager.getInstance(context)
            return object : CarPlayHostMediaLeaseSource {
                override fun begin(lease: CarPlayHostMediaLease) = manager.beginCarPlayHostMedia(lease)
                override fun finish(lease: CarPlayHostMediaLease) = manager.finishCarPlayHostMedia(lease)
            }
        }
        val NONE = object : CarPlayHostMediaLeaseSource {
            override fun begin(lease: CarPlayHostMediaLease) {}
            override fun finish(lease: CarPlayHostMediaLease) {}
        }
    }
}

data class CarPlayAttemptContext(
    val hostMedia: CarPlayHostMediaLease,
    val captureAdmission: () -> Boolean = { false },
    val onCaptureRetirement: ((() -> Unit) -> Unit) = {},
    val onInputAdvertised: (Boolean) -> Unit = {},
)
