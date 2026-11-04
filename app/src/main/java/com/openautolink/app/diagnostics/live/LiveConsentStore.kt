package com.openautolink.app.diagnostics.live

import android.content.Context
import android.util.AtomicFile
import java.io.File
import org.json.JSONObject

internal interface LiveConsent {
    fun load(): Boolean
    fun save(enabled: Boolean)
    fun loadEndpoint():LiveEndpoint=LiveEndpoint.LEGACY
    fun saveEndpoint(endpoint:LiveEndpoint) { error("Endpoint storage unavailable") }
}

/** One atomic non-secret endpoint + consent record; missing/corrupt state never authorizes launch.
 * Legacy single-byte consent migrates only to its original endpoint, preserving paired owners. */
internal class LiveConsentStore(context: Context) : LiveConsent {
    private val file by lazy { AtomicFile(File(context.noBackupFilesDir,"oal-live-launch-consent.bin")) }
    private fun read():Pair<LiveEndpoint,Boolean> {
        if(!file.baseFile.exists())return LiveEndpoint.LEGACY to false
        val bytes=file.openRead().use { input->
            val out=java.io.ByteArrayOutputStream();val chunk=ByteArray(256)
            while(true){val n=input.read(chunk);if(n<0)break;out.write(chunk,0,n);check(out.size()<=8192)}
            out.toByteArray()
        }
        check(bytes.size<=8192)
        if(bytes.size==1)return LiveEndpoint.LEGACY to (bytes[0].toInt()==1)
        return try {
            val json=JSONObject(String(bytes,Charsets.UTF_8))
            LiveEndpoint.parse(json.getString("endpoint")) to json.getBoolean("enabled")
        }catch(_:Exception){LiveEndpoint.LEGACY to false}
    }
    @Synchronized override fun load()=read().second
    @Synchronized override fun loadEndpoint()=read().first
    private fun write(endpoint:LiveEndpoint,enabled:Boolean) {
        val stream=file.startWrite()
        try{stream.write(JSONObject().put("endpoint",endpoint.base).put("enabled",enabled).toString().toByteArray());file.finishWrite(stream)}
        catch(e:Exception){file.failWrite(stream);throw e}
    }
    @Synchronized override fun save(enabled:Boolean)=write(read().first,enabled)
    @Synchronized override fun saveEndpoint(endpoint:LiveEndpoint)=write(endpoint,false)
}
