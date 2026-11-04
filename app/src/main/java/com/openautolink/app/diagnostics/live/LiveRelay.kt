package com.openautolink.app.diagnostics.live

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal interface LiveWire : AutoCloseable {
    fun post(path: String, body: JSONObject, bearer: String? = null): JSONObject
    override fun close()
}
internal class LiveHttpFailure(val status: Int) : IOException("OAL observer request rejected")

/** User-selected HTTPS base/typed routes; system certificate AND hostname verification; no redirects. */
internal class LiveHttps(private val endpoint:LiveEndpoint=LiveEndpoint.LEGACY) : LiveWire {
    companion object { const val BASE = "https://mossy-access.themosscrew.com/oal" }
    private val lock = Any()
    private var active: HttpsURLConnection? = null
    private var closed = false
    override fun post(path: String, body: JSONObject, bearer: String?): JSONObject {
        require(path in setOf("/v2/pair", "/v2/connect", "/v2/revoke", "/v1/ingest", "/v1/disconnect"))
        val data = body.toString().toByteArray(Charsets.UTF_8)
        require(data.size <= 64 * 1024)
        val connection = synchronized(lock) {
            check(!closed)
            (URL(endpoint.base + path).openConnection() as HttpsURLConnection).also {
                it.instanceFollowRedirects = false; it.connectTimeout = 5000; it.readTimeout = 5000
                it.requestMethod = "POST"; it.doOutput = true; it.useCaches = false
                it.setRequestProperty("Content-Type", "application/json")
                bearer?.let { token -> it.setRequestProperty("Authorization", "Bearer $token") }
                it.setFixedLengthStreamingMode(data.size); active = it
            }
        }
        try {
            connection.outputStream.use { it.write(data) }
            val code = connection.responseCode
            if (code != 200) throw LiveHttpFailure(code)
            val bytes = connection.inputStream.use { input ->
                val result = java.io.ByteArrayOutputStream(); val chunk = ByteArray(1024)
                while (true) { val n = input.read(chunk); if (n < 0) break; result.write(chunk, 0, n)
                    if (result.size() > 16384) throw IOException("observer response too large") }
                result.toByteArray()
            }
            if (bytes.size > 16384) throw IOException("observer response too large")
            return JSONObject(String(bytes, Charsets.UTF_8))
        } finally {
            synchronized(lock) { if (active === connection) active = null }
            connection.disconnect()
        }
    }
    override fun close() {
        val connection = synchronized(lock) { closed = true; active.also { active = null } }
        connection?.disconnect()
    }
}

/** One owner per relay. Network callbacks never mutate projection, preferences, or another owner. */
internal class LiveRelay(private val buffer: LiveBuffer, private val wire: LiveWire,
    private val process: String, private val credential: String,
    private val snapshot: () -> JSONObject, private val current: () -> Boolean) {
    private var token: String? = null
    fun sendOnce() {
        if (!current()) return
        if (token == null) {
            val connected = wire.post("/v2/connect", JSONObject().put("deviceCredential", credential))
            if (!current()) return
            token = connected.getString("token")
        }
        if (!current()) return
        val events = ArrayList<LiveEvent>()
        val snapshotValue=snapshot()
        val eventBudget=64*1024-snapshotValue.toString().toByteArray(Charsets.UTF_8).size-2048
        val rows = JSONArray(); var rowBytes=0
        for (e in buffer.batch(64)) {
            val tag=LiveRedaction.clean(e.tag); val message=LiveRedaction.clean(e.message)
            if(tag.length>160 || message.toByteArray(Charsets.UTF_8).size>16384){buffer.discard(e.seq);continue}
            val row=JSONObject().put("seq", e.seq).put("wallMs", e.wallMs)
                .put("elapsedMs", e.elapsedMs).put("session", e.session).put("level", e.level)
                .put("tag",tag).put("message",message).put("source",e.source)
            val size=row.toString().toByteArray(Charsets.UTF_8).size
            if(rowBytes+size>minOf(48*1024,eventBudget))break
            rows.put(row);events.add(e);rowBytes+=size
        }
        val batch = JSONObject().put("process", process).put("dropped", buffer.dropped).put("events", rows).put("snapshot", snapshotValue)
        try {
            if (!current()) return
            val response = wire.post("/v1/ingest", batch, token)
            // Even a snapshot-only upload needs a server acknowledgment before UI may say Live.
            val ack = response.getLong("ack")
            if (current() && events.isNotEmpty()) {
                // A server cannot acknowledge an unseen future record and erase unsent diagnostics.
                if (ack == events.last().seq) buffer.acknowledge(ack)
                else throw IOException("observer acknowledgment mismatch")
            }
        } catch (e: LiveHttpFailure) {
            if (e.status == 401) token = null
            throw e
        }
    }
}
