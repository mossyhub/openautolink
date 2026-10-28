package com.openautolink.app.carplay

import android.app.Activity
import java.lang.ref.WeakReference

/** Exact weak resumed registration. This is a conservative guard, never an OS exemption. */
object CarPlayForegroundVisibility {
    private var activity = WeakReference<Activity>(null)
    private var revision = 0L
    @Synchronized fun resumed(owner: Activity): Long {
        activity = WeakReference(owner)
        return ++revision
    }
    @Synchronized fun paused(owner: Activity) {
        if (activity.get() === owner) { activity.clear(); revision++ }
    }
    @Synchronized fun generation(): Long? = activity.get()?.takeUnless { it.isDestroyed || it.isFinishing }?.let { revision }
    fun isCurrent(generation: Long): Boolean = generation() == generation
    fun isResumed(): Boolean = generation() != null
}
