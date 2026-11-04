package com.openautolink.app.diagnostics.gm

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.RandomAccessFile
import org.json.JSONObject

/** Fixed-size own primitive aggregate; never stores Intents, Bundles, vendor objects or secrets.
 * Shared by the isolated receiver and normal app. All disk operations run off callback/Main,
 * except one small admission read at Service creation. No polling/keepalive in the receiver. */
internal data class GmObserverState(
    val enabled:Boolean=true, val generation:Long=0, val received:Long=0, val ignored:Long=0,
    val lastElapsed:Long=0, val lastWall:Long=0, val dropped:Long=0, val available:Boolean=true,
)
internal enum class GmReceiveResult { COMMITTED, CONSENT_OFF, GENERATION_CHANGED, CONFIG_UNAVAILABLE, PERSISTENCE_UNAVAILABLE }

internal class GmObserverStore(context:Context) {
    companion object { private val processLock=java.util.concurrent.locks.ReentrantLock() }
    private val dir=File(context.noBackupFilesDir,"gm-page-observer")
    private fun <T> locked(fallback:T,wait:Boolean=false,body:()->T):T {
        if(wait)processLock.lock() else if(!processLock.tryLock())return fallback
        return try {
            dir.mkdirs()
            RandomAccessFile(File(dir,"lock"),"rw").use { file ->
                // Only the already bounded diagnostic disk worker waits behind a reader.
                // Main admission/UI readers and control attempts remain fail-closed/retryable.
                val lock=try{if(wait)file.channel.lock()else file.channel.tryLock()}
                    catch(_:java.nio.channels.OverlappingFileLockException){null}
                if(lock==null)fallback else lock.use{body()}
            }
        }catch(_:Exception){fallback}finally{processLock.unlock()}
    }
    private val closed get()=GmObserverState(enabled=false,generation=Long.MIN_VALUE,available=false)
    private fun readLocked():GmObserverState {
        val file=AtomicFile(File(dir,"state"))
        if(!file.baseFile.exists() && !File(dir,"state.bak").exists())return GmObserverState()
        return try {
            file.openRead().use {
                val buffer=ByteArray(4097);var size=0
                while(size<buffer.size){val n=it.read(buffer,size,buffer.size-size);if(n<0)break;size+=n}
                require(size<=4096)
                val j=JSONObject(String(buffer,0,size,Charsets.UTF_8));require(j.getInt("version")==1)
                GmObserverState(j.getBoolean("enabled"),j.getLong("generation"),j.getLong("received"),
                    j.getLong("ignored"),j.getLong("lastElapsed"),j.getLong("lastWall"),j.getLong("dropped"))
            }
        }catch(_:Exception){closed}
    }
    fun read():GmObserverState=locked(closed){readLocked()}
    private fun write(s:GmObserverState) {
        val j=JSONObject().put("version",1).put("enabled",s.enabled).put("generation",s.generation)
            .put("received",s.received).put("ignored",s.ignored).put("lastElapsed",s.lastElapsed)
            .put("lastWall",s.lastWall).put("dropped",s.dropped)
        val file=AtomicFile(File(dir,"state"));val out=file.startWrite()
        try{out.write(j.toString().toByteArray());file.finishWrite(out)}catch(e:Exception){file.failWrite(out);throw e}
    }
    fun setEnabled(enabled:Boolean):Boolean=locked(false){
        val s=readLocked();write(s.copy(enabled=enabled,generation=if(s.generation==Long.MAX_VALUE)1 else s.generation+1));true
    }
    /** Generation comparison and persistence share the same interprocess lock as Disable.
     * After Disable commits, no older callback can write; Off -> On cannot revive queued work. */
    fun receive(epoch:Long,accepted:Boolean,elapsed:Long,wall:Long,dropped:Long=0):Boolean=
        receiveResult(epoch,accepted,elapsed,wall,dropped)==GmReceiveResult.COMMITTED
    fun receiveResult(epoch:Long,accepted:Boolean,elapsed:Long,wall:Long,dropped:Long=0):GmReceiveResult=
        locked(GmReceiveResult.PERSISTENCE_UNAVAILABLE,wait=true){
        val s=readLocked()
        if(!s.available)return@locked GmReceiveResult.CONFIG_UNAVAILABLE
        if(!s.enabled)return@locked GmReceiveResult.CONSENT_OFF
        if(s.generation!=epoch)return@locked GmReceiveResult.GENERATION_CHANGED
        fun inc(n:Long)=if(n==Long.MAX_VALUE)n else n+1
        write(if(accepted)s.copy(received=inc(s.received),lastElapsed=elapsed,lastWall=wall,dropped=maxOf(s.dropped,dropped))
            else s.copy(ignored=inc(s.ignored),dropped=maxOf(s.dropped,dropped)))
        GmReceiveResult.COMMITTED
    }
}
