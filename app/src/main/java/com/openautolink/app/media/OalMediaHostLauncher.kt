package com.openautolink.app.media

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** Requests the public host destination; never sets, observes or claims the selected source. */
object OalMediaHostLauncher {
    enum class Result { REQUESTED, UNAVAILABLE, DENIED }
    fun intent(context:Context):Intent = Intent("android.car.intent.action.MEDIA_TEMPLATE")
        .putExtra("android.car.intent.extra.MEDIA_COMPONENT",ComponentName(context,OalMediaBrowserService::class.java).flattenToString())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    fun launch(context:Context):Result = try {
        context.startActivity(intent(context)); Result.REQUESTED
    } catch (_: ActivityNotFoundException) { Result.UNAVAILABLE }
      catch (_: SecurityException) { Result.DENIED }
}
