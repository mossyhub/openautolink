package com.openautolink.app.announcements

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

data class ReleaseAnnouncement(val id: String, val versionCode: Int, val title: String, val body: String) {
    val acknowledgementKey: String get() = "$versionCode:$id"
}

internal object ReleaseAnnouncements {
    // Opt-IN release authoring: add an exact code + unique ID only for a significant feature
    // needing attention. Unlisted releases have NO announcement; never use >= or release notes.
    private val catalog = mapOf(572 to ReleaseAnnouncement(
        "driver-display-maps-572", 572,
        "Maps on your driver display",
        "OAL can now show Android Auto and CarPlay maps on your driver display.\n\n" +
            "To enable it, open Settings → Secondary Displays and turn on Driver-display video.",
    ), 575 to ReleaseAnnouncement(
        "driver-display-maps-575", 575,
        "Maps on your driver display",
        "OAL can now show Android Auto and CarPlay maps on your driver display.\n\n" +
            "To enable it, open Settings → Secondary Displays and turn on Driver-display video.",
    ))
    fun forVersion(versionCode: Int): ReleaseAnnouncement? = catalog[versionCode]
}

/** Ordinary app DataStore metadata; never reads/writes video, connection, or settings drafts. */
class ReleaseAnnouncementStore(
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val acknowledged = mutableSetOf<String>()
    private val claims = mutableMapOf<String, Any>()

    suspend fun isAcknowledged(announcement: ReleaseAnnouncement): Boolean {
        val key = announcement.acknowledgementKey
        if (synchronized(this) { key in acknowledged }) return true
        val persisted = key in dataStore.data.first()[ACKNOWLEDGED].orEmpty()
        return synchronized(this) {
            if (persisted) acknowledged.add(key)
            key in acknowledged
        }
    }

    @Synchronized fun claim(announcement: ReleaseAnnouncement, owner: Any): Boolean {
        val key = announcement.acknowledgementKey
        if (key in acknowledged || claims.containsKey(key)) return false
        claims[key] = owner
        return true
    }

    @Synchronized fun release(announcement: ReleaseAnnouncement, owner: Any) {
        val key = announcement.acknowledgementKey
        if (claims[key] === owner) claims.remove(key)
    }

    /** Hide immediately; process-owned IO survives Activity disposal. Failure is optional metadata,
     * not a failed settings save. Memory acknowledgement prevents repeat prompts in this process. */
    fun dismiss(announcement: ReleaseAnnouncement, owner: Any): Job {
        val key = announcement.acknowledgementKey
        val accepted = synchronized(this) {
            if (claims[key] !== owner || key in acknowledged) false
            else { acknowledged.add(key); claims.remove(key); true }
        }
        return scope.launch {
            if (!accepted) return@launch
            try {
                dataStore.edit { it[ACKNOWLEDGED] = it[ACKNOWLEDGED].orEmpty() + key }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                Log.w("ReleaseAnnouncement", "Optional acknowledgement write failed")
            }
        }
    }

    private companion object {
        val ACKNOWLEDGED = stringSetPreferencesKey("release_announcements_acknowledged_v1")
    }
}
