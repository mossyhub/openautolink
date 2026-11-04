package com.openautolink.app.diagnostics.gm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.util.AtomicFile
import androidx.core.content.ContextCompat
import com.openautolink.app.diagnostics.DiagnosticLog
import com.openautolink.app.secondary.BoundedPageDiagnosticSink
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/** Finite local control-flow counts, separate from the strict 13-field live snapshot contract.
 * Best effort ASYNC persistence: an absent breadcrumb is UNKNOWN, never "callback not called".
 * Observer never initializes logging/live/network. Main's existing logger preserves its ordinary
 * upload/opt-in live rules. No action text, Intents, Bundles, payloads or navigation strings stored. */
internal object GmAdmissionDiagnostics {
    enum class Code { APPLICATION, CREATE, GATE_DENIED, CONFIG_READ_OK, CONFIG_UNAVAILABLE, CONSENT_OFF,
        ADMITTED, START_RECOGNIZED, START_OTHER, DECODE_UNAVAILABLE_OR_INVALID, TYPE_UNKNOWN,
        DECODED, ENQUEUE_ACCEPTED, ENQUEUE_DROPPED, WORKER_GATE_DENIED,
        GENERATION_CHANGED, COMMIT_OK, COMMIT_UNAVAILABLE, RELAY_FAILED }
    private val fileLock=ReentrantLock()
    private val scheduler=java.util.concurrent.Executors.newSingleThreadExecutor{r->
        Thread(r,"OalGmAdmission").apply{isDaemon=true}
    }
    private data class Event(val context:Context,val code:Code,val elapsed:Long)
    private val writer:GmAdmissionQueue<Event> = GmAdmissionQueue(schedule={scheduler.execute(it)},consume={persist(it.context,it.code,it.elapsed)})
    private val mainInitialized=AtomicBoolean()
    private val refreshPending=AtomicBoolean()
    private val nextRefresh=AtomicLong()
    private val snapshotRequested=AtomicBoolean()
    private var lastSnapshotEmission:Long?=null
    @Volatile private var mainSink:BoundedPageDiagnosticSink?=null
    private var lastMain=""
    private var lastRelay=0L // observer disk worker only
    private fun dir(context:Context)=File(context.noBackupFilesDir,"gm-page-admission")
    private fun action(context:Context)=context.packageName+".action.GM_ADMISSION_CHANGED"
    private fun permission(context:Context)=context.packageName+".permission.OBSERVE_CLUSTER_PAGE"
    private fun <T> locked(context:Context,fallback:T,wait:Boolean,body:(File)->T):T {
        if(wait)fileLock.lock() else if(!fileLock.tryLock())return fallback
        return try {
            val directory=dir(context);directory.mkdirs()
            RandomAccessFile(File(directory,"lock"),"rw").use{handle->
                val lock=try{if(wait)handle.channel.lock()else handle.channel.tryLock()}
                    catch(_:java.nio.channels.OverlappingFileLockException){null}
                if(lock==null)fallback else lock.use{body(directory)}
            }
        }catch(_:Exception){fallback}finally{fileLock.unlock()}
    }
    private fun readFile(directory:File):JSONObject {
        val file=File(directory,"counts")
        if(!file.exists() && !File(directory,"counts.bak").exists())return JSONObject().put("available",true).put("hasPersisted",false)
        val bytes=AtomicFile(file).openRead().use{it.readBytesBounded()}
        val j=JSONObject(String(bytes,Charsets.UTF_8));require(j.getInt("version")==1)
        // Whitelist on read too: malformed local files cannot turn into arbitrary diagnostic text.
        val clean=JSONObject().put("available",true).put("hasPersisted",true)
        Code.values().forEach{clean.put(it.name,j.optLong(it.name).coerceAtLeast(0))}
        for(key in arrayOf("lastElapsed","diagnosticDroppedAtLastWrite"))clean.put(key,j.optLong(key).coerceAtLeast(0))
        return clean
    }
    private fun java.io.InputStream.readBytesBounded():ByteArray {
        val buffer=ByteArray(4097);var size=0
        while(size<buffer.size){val n=read(buffer,size,buffer.size-size);if(n<0)break;size+=n}
        require(size<=4096);return buffer.copyOf(size)
    }
    fun read(context:Context):JSONObject=locked(context,JSONObject().put("available",false),false){readFile(it)}
    fun record(context:Context,code:Code) {
        writer.offer(Event(context.applicationContext,code,SystemClock.elapsedRealtime()))
    }
    private fun persist(context:Context,code:Code,elapsed:Long) {
        val dropped=writer.dropped
        val success=locked(context,false,true){directory->
            val j=readFile(directory)
            if(!j.optBoolean("available"))return@locked false
            j.put("version",1).put(code.name,if(j.optLong(code.name)==Long.MAX_VALUE)Long.MAX_VALUE else j.optLong(code.name)+1)
                .put("lastElapsed",elapsed).put("diagnosticDroppedAtLastWrite",maxOf(j.optLong("diagnosticDroppedAtLastWrite"),dropped))
            val bytes=j.toString().toByteArray(Charsets.UTF_8);require(bytes.size<=4096)
            val file=AtomicFile(File(directory,"counts"));val out=file.startWrite()
            try{out.write(bytes);file.finishWrite(out)}catch(e:Exception){file.failWrite(out);throw e}
            true
        }
        // A later successful record reports failures. Pre-persistence loss cannot survive death.
        check(success)
        if(elapsed-lastRelay>=1000){
            lastRelay=elapsed
            runCatching { context.sendBroadcast(Intent(action(context)).setPackage(context.packageName),permission(context)) }
        }
    }
    /** Main process only. Receiver is dynamic/signature-protected; no cold-start component. */
    fun initializeMain(context:Context) {
        if(!mainInitialized.compareAndSet(false,true))return
        try {
            val app=context.applicationContext
            mainSink=BoundedPageDiagnosticSink(capacity=1){
                try{emitMain(app)}finally{refreshPending.set(false)}
            }
            ContextCompat.registerReceiver(app,object:BroadcastReceiver(){
                override fun onReceive(c:Context,intent:Intent){if(intent.action==action(app))refreshMain(app)}
            },IntentFilter(action(app)),permission(app),null,ContextCompat.RECEIVER_EXPORTED)
            refreshMain(app)
        }catch(_:Exception){mainInitialized.set(false);mainSink?.close();mainSink=null}
    }
    /** Safe to request from snapshot/UI; all PM/disk/log calls remain on a bounded worker. */
    fun refreshMain(context:Context,fromSnapshot:Boolean=false) {
        if(fromSnapshot)snapshotRequested.set(true)
        if(mainSink==null)return
        val now=SystemClock.elapsedRealtime();val next=nextRefresh.get()
        // Bound even trusted/same-signature relay noise before PM/disk work, without a timer.
        if(now<next || !nextRefresh.compareAndSet(next,now+1000))return
        if(!refreshPending.compareAndSet(false,true))return
        if(mainSink?.tryOffer("refresh")!=true)refreshPending.set(false)
    }
    private fun emitMain(context:Context) {
        val admission=read(context)
        val discovery=GmDiscoveryDiagnostics.read(context)
        // Existing snapshot requests may replay unchanged facts at most once/minute. No timer,
        // polling, live initialization or new network: this prevents startup's local-only log
        // from suppressing facts after a later explicit live consent/reconnect.
        val fromSnapshot=snapshotRequested.getAndSet(false)
        val now=SystemClock.elapsedRealtime()
        val previous=lastSnapshotEmission
        val replay=fromSnapshot && (previous==null || now-previous>=60_000)
        val canonical=admission.toString()+discovery.toString()
        if(canonical==lastMain && !replay)return
        DiagnosticLog.i("gmAdmission","v1 evidence: ASYNC_LOCAL_COUNTS absence: UNKNOWN frameworkStartRejection: UNOBSERVABLE oemCachedMembership: UNKNOWN")
        fun emit(prefix:String,j:JSONObject){
            // Fixed whitelisted scalar fields only; each line fits DiagnosticLog's actual 500 cap.
            var line="v1 $prefix"
            for(key in j.keys().asSequence().toList().sorted()){
                // A space also keeps finite key/value pairs below live's entropy-token rule.
                val field=" $key: ${j.get(key)}"
                if(line.length+field.length>450){DiagnosticLog.i("gmAdmission",line);line="v1 $prefix"}
                line+=field
            }
            DiagnosticLog.i("gmAdmission",line)
        }
        emit("admission",admission);emit("discovery",discovery)
        lastMain=canonical
        if(fromSnapshot)lastSnapshotEmission=now
    }
    internal fun awaitIdleForTest() {
        // Quiescence of already offered records only; not an all-producers/durability barrier.
        val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while(!writer.isIdle() && System.nanoTime()<until)Thread.sleep(5)
        check(writer.isIdle())
    }
}
