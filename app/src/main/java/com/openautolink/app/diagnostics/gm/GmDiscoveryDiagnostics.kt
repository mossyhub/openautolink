package com.openautolink.app.diagnostics.gm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.os.UserManager
import org.json.JSONObject

/** Public current-own-user queries only: never an OEM cached-core-list membership check. */
internal object GmDiscoveryDiagnostics {
    const val QUERY_FLAGS = 0xc0080 // GET_META_DATA | both direct-boot classes; no DEFAULT/disabled match.
    fun read(context:Context):JSONObject {
        val pm=context.packageManager
        val result=JSONObject().put("scope","OWN_CURRENT_USER_ONLY").put("oemCachedMembership","UNKNOWN")
            .put("oemDiscoveryUser","UNKNOWN").put("frameworkStartRejection","UNOBSERVABLE")
            .put("ownUid",Process.myUid())
        val serial=runCatching { context.getSystemService(UserManager::class.java)
            ?.getSerialNumberForUser(Process.myUserHandle()) }.getOrNull()
        result.put("ownUserSerial",serial?.takeIf{it>=0}?.toString()?:"UNAVAILABLE")
        try {
            val component=ComponentName(context,GmPageObserverService::class.java)
            val matches=pm.queryIntentServices(Intent("gm.cluster.action.APP_SELECTED")
                .addCategory("com.gm.intent.category.COREAPP").setPackage(context.packageName),QUERY_FLAGS)
            val own=matches.firstOrNull{it.serviceInfo?.let{s->s.packageName==component.packageName && s.name==component.className}==true}?.serviceInfo
            result.put("ownQueryAvailable",true).put("ownQueryMatches",own!=null)
                .put("metadataMatches",own?.metaData?.let{it.getString("AppType")=="oal_page_selection_observer" && it.getString("SupportedDisplay")=="IPC"}?:false)
                .put("processMatches",own?.processName==context.packageName+":gmPageObserver")
                .put("signatureGateQualified",GmObserverGate.qualified(context))
        }catch(_:Exception){result.put("ownQueryAvailable",false)}
        // Visibility is intentionally not widened: an unavailable narrow public lookup is UNKNOWN,
        // not proof of package absence or a cross-user sender's identity.
        try {
            val sender=pm.getApplicationInfo("com.gm.cluster",0)
            result.put("senderVisibility","VISIBLE_CURRENT_USER").put("senderUid",sender.uid)
                .put("senderSystemAppId",sender.uid>=0 && sender.uid%100000==1000)
        }catch(_:PackageManager.NameNotFoundException){result.put("senderVisibility","NOT_VISIBLE_OR_NOT_INSTALLED")}
        catch(_:Exception){result.put("senderVisibility","UNAVAILABLE")}
        return result
    }
}
