package com.openautolink.app.diagnostics.live

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Optional process-owned observer: startup is Off unless the user explicitly saved auto-connect.
 * No boot receiver, FGS, wake lock, network binding, projection control or activity launch. */
internal object LiveDiagnostics {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val off=MutableStateFlow("Off")
    private val noConsent=MutableStateFlow(false)
    @Volatile private var owner:LiveDiagnosticsOwner?=null
    val status:StateFlow<String> get()=owner?.status?:off
    val autoConnect:StateFlow<Boolean> get()=owner?.autoConnect?:noConsent
    val endpoint get()=owner?.endpoint?:MutableStateFlow<LiveEndpoint?>(null)
    val ready get()=owner?.ready?:noConsent
    val busy get()=owner?.busy?:noConsent
    fun applyEndpoint(context:Context,text:String){initialize(context);owner!!.applyEndpoint(text)}
    @Synchronized fun initialize(context:Context) {
        if(owner!=null)return
        owner=LiveDiagnosticsOwner(context.applicationContext,scope).also{it.restore()}
    }
    fun enable(context:Context,code:String){initialize(context);owner!!.enable(code)}
    fun updateAutoConnect(context:Context,enabled:Boolean){initialize(context);owner!!.updateAutoConnect(enabled)}
    fun stop(){owner?.stop()}
    fun revoke(context:Context){initialize(context);owner!!.revoke()}
}
