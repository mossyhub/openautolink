package com.openautolink.app.diagnostics.gm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import org.json.JSONObject

/** Own current-user metadata only. Never creates a projection session or initializes live. */
internal object GmObserverDiagnostics {
    fun snapshot(context:Context):JSONObject {
        // Requests only a bounded off-thread refresh to the normal logger; wire shape stays exact.
        GmAdmissionDiagnostics.refreshMain(context,fromSnapshot=true)
        val s=GmObserverStore(context).read()
        return JSONObject().put("enabled",s.enabled).put("configurationAvailable",s.available).put("componentQualified",GmObserverGate.qualified(context))
            .put("received",s.received).put("ignored",s.ignored).put("queueDroppedAtLastWrite",s.dropped)
            .put("lastReceiptElapsedMs",s.lastElapsed).put("lastReceiptWallMs",s.lastWall)
            .put("ownAndroidUid",Process.myUid()).put("ownAndroidUserId","UNAVAILABLE_PUBLIC_API")
            .put("selectedState","UNKNOWN").put("ownership","NOT_ESTABLISHED")
            .put("senderScope","FRAMEWORK_SIGNATURE_GATE_PRIVILEGED_CLASS")
    }
    /** Called only by the visible normal-app UI. Never launches/starts the observer or OEM. */
    @Synchronized fun setEnabled(context:Context,enabled:Boolean,allowEnable:()->Boolean={true}):Boolean {
        val store=GmObserverStore(context)
        if(enabled && !allowEnable())return false
        if(!store.setEnabled(enabled))return false
        return try {
            if(enabled && !allowEnable()){store.setEnabled(false);return false}
            if(!enabled)context.stopService(Intent(context,GmPageObserverService::class.java))
            context.packageManager.setComponentEnabledSetting(ComponentName(context,GmPageObserverService::class.java),
                if(enabled)PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP)
            // Exact readback, not success inferred from setComponentEnabledSetting returning.
            val effective=GmObserverGate.qualified(context)
            if(enabled && !allowEnable()) {
                store.setEnabled(false)
                context.packageManager.setComponentEnabledSetting(ComponentName(context,GmPageObserverService::class.java),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,PackageManager.DONT_KILL_APP)
                return false
            }
            if(enabled && !effective){store.setEnabled(false);false}else effective==enabled
        }catch(_:Exception){if(enabled)store.setEnabled(false);false}
    }
}
