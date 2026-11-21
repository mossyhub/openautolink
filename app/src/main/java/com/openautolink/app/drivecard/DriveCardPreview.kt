package com.openautolink.app.drivecard

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.openautolink.app.secondary.SecondaryDisplayDescriptor
import com.openautolink.app.secondary.SecondaryOutputId

/** Main-confined, memory-only borrowing of an ALREADY admitted card View. No launcher, intent,
 * Surface, vehicle subscriber, navigation owner, or persistence. The View's current predicate
 * captures its exact registry Entry/Activity/receipt. Never re-resolve by id after borrowing. */
internal object DriveCardPreview {
    private val targets=mutableMapOf<SecondaryOutputId,Target>()
    private val main=Handler(Looper.getMainLooper())
    internal class Target(val descriptor:SecondaryDisplayDescriptor,val view:DriveCardView) {
        var lease:Lease?=null
    }
    class Lease internal constructor(private val target:Target) {
        val deadlineElapsedMs=SystemClock.elapsedRealtime()+30_000L
        private var stopped=false
        private val saved=target.view.layout
        private val timeout=Runnable{stop()}
        init {main.postDelayed(timeout,30_000L)}
        val isActive:Boolean get() {
            if(stopped)return false
            if(Looper.myLooper()!=Looper.getMainLooper())return false
            if(target.lease!==this || targets[target.descriptor.id]!==target ||
                !target.view.isCurrentCard() || SystemClock.elapsedRealtime()>=deadlineElapsedMs) {
                stop();return false
            }
            return true
        }
        fun update(layout:DriveCardLayout):Boolean {
            if(!isActive)return false
            target.view.updatePreviewLayout(layout)
            return true
        }
        fun stop() {
            if(Looper.myLooper()!=Looper.getMainLooper()) {main.post{stop()};return}
            if(stopped)return
            stopped=true;main.removeCallbacks(timeout)
            if(target.lease===this) {
                target.lease=null
                target.view.updatePreviewLayout(saved) // exact predecessor View only
            }
        }
    }
    fun start(descriptor:SecondaryDisplayDescriptor):Lease? {
        if(Looper.myLooper()!=Looper.getMainLooper())return null
        val target=targets[descriptor.id]?.takeIf{it.descriptor==descriptor && it.view.isCurrentCard()} ?: return null
        target.lease?.stop()
        return Lease(target).also{target.lease=it}
    }
    fun register(descriptor:SecondaryDisplayDescriptor,view:DriveCardView) {
        check(Looper.myLooper()==Looper.getMainLooper())
        targets.remove(descriptor.id)?.lease?.stop()
        targets[descriptor.id]=Target(descriptor,view)
    }
    fun unregister(view:DriveCardView) {
        check(Looper.myLooper()==Looper.getMainLooper())
        val exact=targets.values.singleOrNull{it.view===view} ?: return
        exact.lease?.stop()
        if(targets[exact.descriptor.id]===exact)targets.remove(exact.descriptor.id)
    }
}
