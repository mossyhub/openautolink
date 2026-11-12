package com.openautolink.app.diagnostics.live

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.util.UUID
import kotlin.random.Random

internal class LiveController(private val scope:CoroutineScope,
    private val wireFactory:()->LiveWire,
    private val snapshot:()->JSONObject,private val statusSink:(String)->Unit={},
    private val networkFactory:()->LiveNetwork={UnknownLiveNetwork}) {
    private val lock=Any()
    private val mutableStatus=MutableStateFlow("Off")
    val status:StateFlow<String> = mutableStatus.asStateFlow()
    private val process=UUID.randomUUID().toString()
    private val sequence=java.util.concurrent.atomic.AtomicLong()
    private class Run(val wire:LiveWire,val buffer:LiveBuffer,val capture:LiveLogSink.Capture,val network:LiveNetwork,val waiting:String) {
        var job:Job?=null
        val wake=Channel<Unit>(Channel.CONFLATED)
        val networkReturnNeeded=java.util.concurrent.atomic.AtomicBoolean(false)
    }
    private var active:Run?=null
    private fun current(run:Run)=synchronized(lock){active===run}
    private fun setStatus(text:String){mutableStatus.value=text;statusSink(text)}
    private fun publish(run:Run,text:String)=synchronized(lock){if(active===run)setStatus(text)}
    fun start(credential:String, waitForNetwork:Boolean=false, endpointWireFactory:()->LiveWire=wireFactory) {
        stop()
        val q=LiveBuffer(sequence=sequence)
        val wire=endpointWireFactory()
        val run=Run(wire,q,LiveLogSink.attach(q),runCatching{networkFactory()}.getOrDefault(UnknownLiveNetwork),if(waitForNetwork)"Waiting for network" else "Waiting for internet")
        synchronized(lock){active=run;setStatus("Connecting")}
        run.job=scope.launch(Dispatchers.IO) {
            val relay=LiveRelay(q,run.wire,process,credential,snapshot){current(run)}
            var failures=0
            val watch=launch(start=CoroutineStart.UNDISPATCHED) {
                var previous=run.network.state.value
                run.network.state.collect { now->
                    if(now==LiveInternet.OFFLINE) {
                        run.networkReturnNeeded.set(true)
                        publish(run,"${run.waiting} · bounded backlog · dropped=${q.dropped}")
                    }
                    if(now==LiveInternet.VALIDATED && previous!=LiveInternet.VALIDATED && current(run)) {
                        if(run.networkReturnNeeded.compareAndSet(true,false)) {
                            publish(run,"Connecting")
                            run.wake.trySend(Unit)
                        }
                    }
                    previous=now
                }
            }
            try {
                while(isActive && current(run)) {
                    if(run.network.state.value==LiveInternet.OFFLINE) {
                        publish(run,"${run.waiting} · bounded backlog · dropped=${q.dropped}")
                        // OS observation only. A timeout rechecks state without issuing requests offline.
                        withTimeoutOrNull(30000){run.network.state.first{it!=LiveInternet.OFFLINE}}
                        continue
                    }
                    // This attempt already consumes the current validated network. A delayed
                    // initial watcher callback is not a second return and must not shorten a
                    // server-failure backoff. Only a subsequent observed loss re-arms wake.
                    run.networkReturnNeeded.set(false)
                    while(run.wake.tryReceive().isSuccess){ /* discard an already-consumed transition */ }
                    try {
                        if(failures>0)publish(run,"Connecting — retrying server")
                        relay.sendOnce();failures=0
                        publish(run,if(run.network.state.value==LiveInternet.OFFLINE)"${run.waiting} · bounded backlog · dropped=${q.dropped}"
                            else "Live read-only · dropped=${q.dropped} · OEM visibility unknown")
                        delay(1000)
                    }catch(e:CancellationException){throw e}
                    catch(_:Exception) {
                        failures=(failures+1).coerceAtMost(6)
                        publish(run,if(run.network.state.value==LiveInternet.OFFLINE)"${run.waiting} · bounded backlog · dropped=${q.dropped}"
                            else "Connecting — server retry${if(run.network.state.value==LiveInternet.UNKNOWN)" (network state unavailable)" else ""} · bounded backlog · dropped=${q.dropped}")
                        val backoff=(1000L shl failures).coerceAtMost(60000)+Random.nextLong(0,1000)
                        // Only a genuine unavailable -> validated edge can shorten server backoff.
                        // Capability storms on an already validated network do not create attempts.
                        withTimeoutOrNull(backoff) {
                            while(current(run)) {
                                run.wake.receive();delay(150)
                                if(run.network.state.value==LiveInternet.VALIDATED)break
                            }
                        }
                    }
                }
            }finally {
                watch.cancel();run.network.close();run.wake.close()
                LiveLogSink.detach(run.capture);run.wire.close();run.buffer.clear()
            }
        }
        if(!current(run)){run.job?.cancel();run.network.close();run.wire.close();LiveLogSink.detach(run.capture)}
    }
    fun stop() {
        val old=synchronized(lock){active.also{active=null;setStatus("Off")}}
        if(old!=null){
            LiveLogSink.detach(old.capture);old.network.close();old.job?.cancel();old.wire.close();old.buffer.clear()
        }
    }
}
