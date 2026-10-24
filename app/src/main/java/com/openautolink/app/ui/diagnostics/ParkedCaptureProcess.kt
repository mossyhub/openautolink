package com.openautolink.app.ui.diagnostics

import android.content.Context
import android.os.Looper
import com.openautolink.app.diagnostics.MicrophoneDiagnosticLogStore
import com.shilapi.xcertplay.media.NativeCaptureOwnerRegistry
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Retained by static process root, never by a screen scope. Polling survives Activity destruction. */
internal object ParkedCaptureProcess {
    private var instance:ParkedCaptureComparison?=null
    private var processScheduler:java.util.concurrent.ScheduledExecutorService?=null
    @Synchronized fun get(context:Context):ParkedCaptureComparison {
        instance?.let{return it}
        val app=context.applicationContext
        val cleanup=Executors.newSingleThreadExecutor { Thread(it,"oal-capture-owner").apply{isDaemon=true} }
        val scheduler=Executors.newSingleThreadScheduledExecutor { Thread(it,"oal-capture-poll").apply{isDaemon=true} }.also { processScheduler=it }
        val registry=NativeCaptureOwnerRegistry(cleanup){ Looper.myLooper()==Looper.getMainLooper() }
        val policy=ParkedCapturePolicy(app)
        val store=MicrophoneDiagnosticLogStore(app)
        val comparison=ParkedCaptureComparison(registry,{backend,eligible->
            val gate={eligible() && policy.allowed()}
            if(backend==0)JavaVr48Run(app,gate) else NativeAAudioRun(gate)
        },store::saveParkedCapture)
        instance=comparison
        scheduler.scheduleWithFixedDelay({
            // A scheduler failure must not stop future revocation/retirement polls.
            runCatching { comparison.poll() }
        },0,50,TimeUnit.MILLISECONDS)
        return comparison
    }
}
