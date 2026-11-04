package com.openautolink.app.diagnostics.gm

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.openautolink.app.secondary.BoundedPageDiagnosticSink

/** Framework enforces this incoming permission BEFORE Service delivery. This readback is a
 * fail-closed configuration check, not callback-time sender identification. AOSP32 permits
 * system/root appIds and own UID as well as signature-permission holders: privileged/trusted
 * class, NOT unique GM authentication. Installed GM UID/framework still need vehicle evidence. */
internal object GmObserverGate {
    fun qualified(context:Context):Boolean=try {
        val pm=context.packageManager
        val permission=context.packageName+".permission.OBSERVE_CLUSTER_PAGE"
        val p=pm.getPermissionInfo(permission,0)
        val component=ComponentName(context,GmPageObserverService::class.java)
        val s=pm.getServiceInfo(component,PackageManager.GET_META_DATA or PackageManager.MATCH_DISABLED_COMPONENTS)
        val override=pm.getComponentEnabledSetting(component)
        p.packageName==context.packageName && p.protectionLevel==PermissionInfo.PROTECTION_SIGNATURE &&
            s.permission==permission && s.exported && s.applicationInfo.enabled &&
            override!=PackageManager.COMPONENT_ENABLED_STATE_DISABLED &&
            override!=PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER &&
            override!=PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED &&
            (override==PackageManager.COMPONENT_ENABLED_STATE_ENABLED || s.enabled)
    }catch(_:Exception){false}
}
internal fun isGmObserverProcess(name:String,packageName:String)=name==packageName+":gmPageObserver"

/** Own ordinary finite service, never an OEM SDK subclass. The optional installed shared
 * library may decode public vendor payloads. Only copied primitives reach an own protected
 * receiver already registered by a live direct-window owner; this never cold-launches OAL. */
class GmPageObserverService:Service() {
    companion object { const val FULL_MAP_SELECTED="gm.cluster.action.FULL_MAP_SELECTED" }
    private var epoch=Long.MIN_VALUE
    private var admitted=false
    private lateinit var sink:BoundedPageDiagnosticSink
    private val main=Handler(Looper.getMainLooper())
    override fun onCreate() {
        super.onCreate()
        GmAdmissionDiagnostics.record(this,GmAdmissionDiagnostics.Code.CREATE)
        val store=GmObserverStore(this)
        // One finite <=4096-byte admission read, not per-event disk IO on Main.
        admitted=GmObserverGate.qualified(this)
        if(!admitted)GmAdmissionDiagnostics.record(this,GmAdmissionDiagnostics.Code.GATE_DENIED)
        val state=store.read();epoch=state.generation
        GmAdmissionDiagnostics.record(this,when {
            !state.available->GmAdmissionDiagnostics.Code.CONFIG_UNAVAILABLE
            !state.enabled->GmAdmissionDiagnostics.Code.CONSENT_OFF
            admitted->GmAdmissionDiagnostics.Code.ADMITTED
            else->GmAdmissionDiagnostics.Code.CONFIG_READ_OK
        })
        admitted=admitted && state.enabled && state.available
        sink=BoundedPageDiagnosticSink(capacity=128) { captured ->
            val parts=captured.split(' ')
            val startId=parts[1].toInt()
            try {
                // No retained Intent or Bundle crosses this queue. Disable and re-enable
                // fence old queued work under the store's exact-generation file lock.
                val accepted=parts[2]=="1"
                val result=if(GmObserverGate.qualified(this))store.receiveResult(parts[0].toLong(),accepted,
                    parts[3].toLong(),parts[4].toLong(),
                    parts.lastOrNull{it.startsWith("diagnosticDropped=")}?.substringAfter('=')?.toLongOrNull()?:0)
                    else null
                GmAdmissionDiagnostics.record(this,when(result){
                    null->GmAdmissionDiagnostics.Code.WORKER_GATE_DENIED
                    GmReceiveResult.COMMITTED->GmAdmissionDiagnostics.Code.COMMIT_OK
                    GmReceiveResult.CONSENT_OFF->GmAdmissionDiagnostics.Code.CONSENT_OFF
                    GmReceiveResult.GENERATION_CHANGED->GmAdmissionDiagnostics.Code.GENERATION_CHANGED
                    GmReceiveResult.CONFIG_UNAVAILABLE->GmAdmissionDiagnostics.Code.CONFIG_UNAVAILABLE
                    GmReceiveResult.PERSISTENCE_UNAVAILABLE->GmAdmissionDiagnostics.Code.COMMIT_UNAVAILABLE
                })
                // Persistence is diagnostic only. Authoritative primitive receipt was relayed
                // before this queue; replaying it here could resurrect an obsolete page.
            } finally { main.post{stopSelfResult(startId)} }
        }
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        // Unknown/null actions coalesce to ignored counts; their arbitrary text is never kept.
        val accepted=intent?.action==FULL_MAP_SELECTED
        GmAdmissionDiagnostics.record(this,if(accepted)GmAdmissionDiagnostics.Code.START_RECOGNIZED else GmAdmissionDiagnostics.Code.START_OTHER)
        val elapsed=SystemClock.elapsedRealtime()
        val page=if(admitted && accepted && flags==0)GmPageDecoder.decode(requireNotNull(intent))else null
        if(admitted && accepted && flags==0){
            GmAdmissionDiagnostics.record(this,if(page==null)GmAdmissionDiagnostics.Code.DECODE_UNAVAILABLE_OR_INVALID else GmAdmissionDiagnostics.Code.DECODED)
            if(page==null)GmAdmissionDiagnostics.record(this,GmAdmissionDiagnostics.Code.TYPE_UNKNOWN)
        }
        if(admitted && accepted && flags==0 && GmObserverGate.qualified(this)) {
            // Nonwaiting bounded generation read: a held store lock fails closed. Even an
            // invalid/UNKNOWN event revokes pending authority, never authorizes a task front.
            val current=GmObserverStore(this).read()
            val qualified=page.takeIf { current.available && current.enabled && current.generation==epoch }
            try { GmPageRelay.send(this,qualified,epoch,elapsed) }
            catch(_:Exception) { GmAdmissionDiagnostics.record(this,GmAdmissionDiagnostics.Code.RELAY_FAILED) }
        }
        val payload=page?.let { "${if(it.selected)1 else 0} ${it.type} ${android.net.Uri.encode(it.view)} ${it.displayType} ${it.width} ${it.height}" }?:"-1 - - -1 0 0"
        if(!admitted)stopSelfResult(startId)
        else {
            val queued=sink.tryOffer("$epoch $startId ${if(accepted)1 else 0} $elapsed ${System.currentTimeMillis()} $payload")
            GmAdmissionDiagnostics.record(this,if(queued)GmAdmissionDiagnostics.Code.ENQUEUE_ACCEPTED else GmAdmissionDiagnostics.Code.ENQUEUE_DROPPED)
            if(!queued)stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }
    override fun onBind(intent:Intent?)=null
    override fun onDestroy(){sink.close();super.onDestroy()}
}
