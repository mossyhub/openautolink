package com.openautolink.app.carplay

import android.app.Notification
import android.app.Service
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowService

/** Robolectric 4.14 caches getForegroundServiceType across stopForeground; model OS replacement/removal. */
@Implements(Service::class)
open class AcknowledgedForegroundShadow : ShadowService() {
    private var mask = 0
    @Implementation(minSdk=29)
    override fun startForeground(id: Int, notification: Notification, foregroundServiceType: Int) {
        super.startForeground(id,notification,foregroundServiceType)
        mask=foregroundServiceType
    }
    @Implementation(minSdk=24)
    override fun stopForeground(flags: Int) { super.stopForeground(flags); mask=0 }
    @Implementation(minSdk=29)
    override fun getForegroundServiceType(): Int = mask
}
