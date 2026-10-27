package com.openautolink.app.carplay

import android.content.pm.ServiceInfo

/** Full replacement masks, never permission constants or an accumulated historical union. */
internal object CarPlayForegroundPolicy {
    const val BASE = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
    const val MIC = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
}
