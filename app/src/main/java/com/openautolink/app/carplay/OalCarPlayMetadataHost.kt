package com.openautolink.app.carplay

import android.content.Context
import com.openautolink.app.cluster.ClusterManager
import com.openautolink.app.cluster.ClusterNavigationState
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.media.OalMediaSessionManager
import com.openautolink.carplaylab.metadata.CarPlayMediaMetadata
import com.openautolink.carplaylab.metadata.CarPlayNavigationMetadata
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

internal interface CarPlayMetadataHost : AutoCloseable {
    fun start(): Boolean
    fun publish(media: CarPlayMediaMetadata?, navigation: CarPlayNavigationMetadata?, artwork: ByteArray?)
}

/** Exact authenticated Wi-Fi link owns the existing host adapters, never a new MediaSession. */
internal class OalCarPlayMetadataHost(private val context: Context) : CarPlayMetadataHost {
    private val owner = Any()
    private var cluster: ClusterManager? = null
    private var mediaManager: OalMediaSessionManager? = null
    private var previousMedia: CarPlayMediaMetadata? = null
    private var previousArt: ByteArray? = null
    private var previousNavigation: CarPlayNavigationMetadata? = null
    override fun start(): Boolean {
        val preferences = AppPreferences.getInstance(context)
        val enabled = runBlocking { preferences.clusterNavigation.first() }
        val units = runBlocking { preferences.distanceUnits.first() }
        if (!MetadataHostOwnership.process.claim(owner)) return false
        MetadataHostOwnership.process.publish(owner) {
            ClusterNavigationState.clearHost()
            ClusterNavigationState.distanceUnits = units
            mediaManager = OalMediaSessionManager.getInstance(context).also { it.initialize(); it.resetHostToIdle() }
            if (enabled) cluster = ClusterManager(context).also { it.setClusterEnabled(true) }
        }
        return true
    }
    override fun publish(media: CarPlayMediaMetadata?, navigation: CarPlayNavigationMetadata?, artwork: ByteArray?) {
        MetadataHostOwnership.process.publish(owner) {
            if (navigation != previousNavigation) {
                val maneuver = navigation?.let(CarPlayClusterMetadataAdapter::maneuver)
                if (maneuver != null) ClusterNavigationState.updateHost(maneuver) else ClusterNavigationState.clearHost()
                previousNavigation = navigation
            }
            if (media != null && (media != previousMedia || !artwork.contentEquals(previousArt))) {
                mediaManager?.updateCarPlay(owner, media, artwork, clearArtwork = artwork == null)
                previousMedia = media
                previousArt = artwork?.copyOf()
            }
        }
    }
    override fun close() {
        MetadataHostOwnership.process.retire(owner) {
            ClusterNavigationState.clearHost()
            mediaManager?.resetHostToIdle()
            cluster?.release()
            cluster = null
            previousMedia = null; previousArt = null; previousNavigation = null
        }
    }
}
