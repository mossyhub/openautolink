package com.openautolink.app.diagnostics.live

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/** Owns optional startup, storage and status authority independently of primary app startup. */
internal class LiveDiagnosticsOwner(
    private val context:Context,
    private val scope:CoroutineScope,
    private val credentials:LiveCredentials=LiveCredentialStore(context),
    private val consent:LiveConsent=LiveConsentStore(context),
    private val wireFactory:(()->LiveWire)?=null,
    private val networkFactory:(()->LiveNetwork)?=null,
    private val endpointWireFactory:(LiveEndpoint)->LiveWire={endpoint->wireFactory?.invoke()?:LiveHttps(endpoint)}
) {
    private val guard=Any()
    private val operation=Mutex()
    private var generation=0L
    private var policyRevision=0L
    private val wire=AtomicReference<LiveWire?>()
    private val mutableStatus=MutableStateFlow("Off")
    val status=mutableStatus.asStateFlow()
    private val mutableAutoConnect=MutableStateFlow(false)
    val autoConnect=mutableAutoConnect.asStateFlow()
    private val mutableEndpoint=MutableStateFlow<LiveEndpoint?>(null)
    val endpoint=mutableEndpoint.asStateFlow()
    private val mutableReady=MutableStateFlow(false)
    val ready=mutableReady.asStateFlow()
    private val mutableBusy=MutableStateFlow(false)
    val busy=mutableBusy.asStateFlow()
    private fun loadEndpoint() {
        if(!mutableReady.value){mutableEndpoint.value=consent.loadEndpoint();mutableReady.value=true}
    }
    fun applyEndpoint(text:String) {
        val selected=try{LiveEndpoint.parse(text)}catch(_:Exception){return}
        val epoch=synchronized(guard) {
            if(mutableBusy.value || !mutableReady.value || selected==mutableEndpoint.value)return
            mutableBusy.value=true;policyRevision++;stop();generation
        }
        scope.launch {
            operation.withLock {
                try {
                    consent.saveEndpoint(selected) // endpoint + explicit launch Off, one durable transaction
                    synchronized(guard){
                        mutableEndpoint.value=selected;mutableAutoConnect.value=false
                        if(generation==epoch)mutableStatus.value="Off — server changed; Enable to use this server's pairing"
                    }
                }catch(_:Exception){publish(epoch,"Off — server not saved; previous server retained")}
                finally{mutableBusy.value=false}
            }
        }
    }
    private var job:Job?=null
    private var statusJob:Job?=null
    private var preferencesJob:Job?=null
    @Volatile private var savedRevision=-1L
    private val controller=LiveController(scope,{endpointWireFactory(checkNotNull(mutableEndpoint.value))},{LiveSnapshot.read(context,savedRevision)},networkFactory={networkFactory?.invoke()?:AndroidLiveNetwork(context,requireValidatedInternet=mutableEndpoint.value==LiveEndpoint.LEGACY)})
    private fun current(epoch:Long)=synchronized(guard){generation==epoch}
    private fun publish(epoch:Long,text:String)=synchronized(guard){if(generation==epoch)mutableStatus.value=text}
    fun restore() {
        val (epoch,policy)=synchronized(guard){generation to policyRevision}
        scope.launch {
            operation.withLock {
                try {
                    loadEndpoint()
                    val enabled=withTimeout(5000){consent.load()}
                    synchronized(guard){if(policyRevision==policy)mutableAutoConnect.value=enabled}
                    if(enabled && mutableEndpoint.value!=null && current(epoch)) {
                        val restoreJob=currentCoroutineContext()[Job]
                        synchronized(guard){if(generation!=epoch)return@withLock;job=restoreJob}
                        activate(epoch,"",false)
                    }
                }catch(e:CancellationException){if(e is TimeoutCancellationException)publish(epoch,"Off — launch storage unavailable; retry Enable while parked") else throw e}
                catch(_:Exception){publish(epoch,"Off — launch storage unavailable; retry Enable while parked")}
            }
        }
    }
    fun updateAutoConnect(enabled:Boolean) {
        val (epoch,policy)=synchronized(guard){if(mutableBusy.value)return;generation to ++policyRevision}
        scope.launch {
            operation.withLock {
                try {
                    synchronized(guard){
                        if(policyRevision!=policy)return@withLock
                        loadEndpoint()
                        if(enabled && mutableEndpoint.value==null){publish(epoch,"Off — Apply an HTTPS diagnostics server first");return@withLock}
                        consent.save(enabled)
                        mutableAutoConnect.value=enabled
                    }
                }catch(_:Exception){publish(epoch,"${if(controller.status.value=="Off")"Off" else "Connecting"} — launch choice not saved; retry while parked")}
            }
        }
    }
    fun enable(code:String)=synchronized(guard) {
        if(mutableBusy.value)return@synchronized
        stop()
        val epoch=++generation
        publish(epoch,"Connecting")
        job=scope.launch {operation.withLock {activate(epoch,code,true)}}
    }
    private suspend fun activate(epoch:Long,code:String,allowPair:Boolean) {
        var transport:LiveWire?=null
        try {
            loadEndpoint()
            val selected=mutableEndpoint.value?:run{publish(epoch,"Off — Apply an HTTPS diagnostics server first");return}
            var credential=withTimeout(5000){credentials.load(selected)}
            if(!current(epoch))return
            if(credential==null) {
                if(!allowPair || !code.matches(Regex("[a-z]+(?:-[a-z]+){4}"))){publish(epoch,"Off — pairing required; enter an owner invitation while parked");return}
                transport=endpointWireFactory(selected)
                synchronized(guard){if(generation!=epoch){transport.close();return};wire.set(transport)}
                credential=transport.post("/v2/pair",JSONObject().put("code",code)).getString("deviceCredential")
                synchronized(guard){if(generation!=epoch)return;credentials.save(selected,credential)}
            }
            synchronized(guard) {
                if(generation!=epoch)return
                controller.start(credential,waitForNetwork=selected!=LiveEndpoint.LEGACY){endpointWireFactory(selected)}
                statusJob=scope.launch {controller.status.collect{publish(epoch,it)}}
                preferencesJob=scope.launch {
                    com.openautolink.app.data.AppPreferences.getInstance(context).secondaryDisplayPreferences
                        .catch { synchronized(guard){if(generation==epoch)savedRevision=-1} }
                        .collect{saved->synchronized(guard){if(generation==epoch)savedRevision=saved.revision}}
                }
            }
        }catch(e:CancellationException){if(e is TimeoutCancellationException)publish(epoch,"Off — pairing storage unavailable; retry Enable while parked") else throw e}
        catch(e:Exception){
            publish(epoch,if(transport==null)"Off — pairing storage unavailable; retry Enable or Revoke while parked" else "Off — pairing/network/storage unavailable; retry Enable while parked")
        }finally{if(transport!=null){wire.compareAndSet(transport,null);transport.close()}}
    }
    fun stop()=synchronized(guard) {
        generation++;job?.cancel();job=null
        statusJob?.cancel();statusJob=null;preferencesJob?.cancel();preferencesJob=null;savedRevision=-1
        wire.getAndSet(null)?.close();controller.stop();mutableStatus.value="Off"
    }
    fun revoke()=synchronized(guard) {
        if(mutableBusy.value)return@synchronized
        stop()
        val epoch=++generation
        // Durable local opt-out must precede any coroutine suspension or network failure.
        try{synchronized(guard){policyRevision++;consent.save(false);mutableAutoConnect.value=false}}
        catch(_:Exception){publish(epoch,"Off — launch choice could not be cleared; retry Revoke while parked");return@synchronized}
        publish(epoch,"Off — revoking owner")
        job=scope.launch {operation.withLock {
            try{loadEndpoint()}catch(_:Exception){publish(epoch,"Off — pairing storage unavailable; retry Revoke");return@withLock}
            val selected=mutableEndpoint.value?:run{publish(epoch,"Off — Apply an HTTPS diagnostics server first");return@withLock}
            val transport=try{endpointWireFactory(selected)}catch(_:Exception){publish(epoch,"Off — server revoke unconfirmed; retry Revoke");return@withLock}
            synchronized(guard){if(generation!=epoch){transport.close();return@withLock};wire.set(transport)}
            try {
                val credential=credentials.load(selected)
                if(!current(epoch))return@withLock
                if(credential!=null)transport.post("/v2/revoke",JSONObject().put("deviceCredential",credential))
                synchronized(guard){if(generation==epoch){credentials.clear(selected);mutableStatus.value="Off — owner revoked"}}
            }catch(e:CancellationException){throw e}
            catch(e:Exception){
                if(e is LiveHttpFailure && e.status==401 && current(epoch)) {
                    synchronized(guard){if(generation==epoch)runCatching{credentials.clear(selected)}.fold(
                        onSuccess={mutableStatus.value="Off — old pairing no longer authorized"},
                        onFailure={mutableStatus.value="Off — local pairing cleanup failed; retry Revoke"})}
                }else publish(epoch,"Off — server revoke unconfirmed; retry Revoke")
            }finally{wire.compareAndSet(transport,null);transport.close()}
        }}
    }
}
