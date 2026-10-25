package com.openautolink.app.ui.testing

import android.content.Context
import com.openautolink.carplaylab.telecom.ManagedTelecomExperiment
import org.robolectric.util.ReflectionHelpers

/** Robolectric creates a fresh Application per test, unlike an Android process. Retire the exact
 * old controller before simulating process replacement; never reset account/lifecycle fences.
 * Otherwise a retained singleton launches into the preceding Application's shadow activity queue.
 */
fun resetNativeTelecomTestProcess(context: Context) {
    ManagedTelecomExperiment.get(context).disable()
    ReflectionHelpers.setStaticField(ManagedTelecomExperiment::class.java, "instance", null)
}
