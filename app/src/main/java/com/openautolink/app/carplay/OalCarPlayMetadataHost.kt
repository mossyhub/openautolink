package com.openautolink.app.carplay

import android.content.Context
import com.openautolink.app.cluster.ClusterManager
import com.openautolink.app.cluster.ClusterNavigationState
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.media.OalMediaSessionManager
import com.openautolink.carplaylab.metadata.CarPlayMediaMetadata
import com.openautolink.carplaylab.metadata.CarPlayNavigationMetadata
import com.openautolink.carplaylab.metadata.CarPlayRouteMetadata
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

internal interface CarPlayMetadataHost : AutoCloseable {
    fun mediaAvailability(ready:Boolean) {}
    fun mediaOutput(blocked:Boolean,current:()->Boolean) {}
    fun start(): Boolean
    fun publish(media: CarPlayMediaMetadata?, navigation: CarPlayNavigationMetadata?, artwork: ByteArray?)
    /** Compatibility bridge for hosts that consume only instruction metadata. */
    fun publishRoute(media: CarPlayMediaMetadata?, route: CarPlayRouteMetadata?, artwork: ByteArray?) =
        publish(media, route?.instruction, artwork)
}

/** Exact authenticated Wi-Fi link owns the existing host adapters, never a new MediaSession. */
internal class OalCarPlayMetadataHost(private val context: Context,
    private val receipt: (String) -> Unit = {},
    private val attemptContext: CarPlayAttemptContext? = null,
    private val mediaCommand: (Int) -> Boolean = { false }) : CarPlayMetadataHost {
    // Retain the existing JVM constructor and trailing-lambda command call sites.
    constructor(context: Context, mediaCommand: (Int) -> Boolean) : this(context, {}, null, mediaCommand)
    constructor(context: Context, receipt: (String) -> Unit, mediaCommand: (Int) -> Boolean) :
        this(context, receipt, null, mediaCommand)
    private val owner = Any()
    @Volatile private var controlsReady=false
    override fun mediaOutput(blocked:Boolean,current:()->Boolean) {
        mediaManager?.setCarPlayMediaOutput(owner,blocked,current)
    }
    override fun mediaAvailability(ready:Boolean) {
        controlsReady=ready
        mediaManager?.setCarPlayMediaReady(owner,ready)
    }
    private var cluster: ClusterManager? = null
    @Volatile private var mediaManager: OalMediaSessionManager? = null
    private var previousMedia: CarPlayMediaMetadata? = null
    private var previousArt: ByteArray? = null
    private var previousNavigation: CarPlayNavigationMetadata? = null
    private var previousRoute: CarPlayRouteMetadata? = null
    override fun start(): Boolean {
        val preferences = AppPreferences.getInstance(context)
        val enabled = runBlocking { preferences.clusterNavigation.first() }
        val units = runBlocking { preferences.distanceUnits.first() }
        val manager = OalMediaSessionManager.getInstance(context)
        if (attemptContext != null && !manager.acceptsCarPlayHost(attemptContext.hostMedia)) return false
        if (!MetadataHostOwnership.process.claim(owner)) return false
        MetadataHostOwnership.process.publish(owner) {
            ClusterNavigationState.clearHost()
            ClusterNavigationState.distanceUnits = units
            mediaManager = OalMediaSessionManager.getInstance(context).also { it.initialize(); it.resetHostToIdle() }
            mediaManager?.installCarPlayControls(owner, object : OalMediaSessionManager.MediaControlCallback {
                override fun onCommand(command: com.openautolink.app.media.GmMediaControlPolicy.Command) {
                    // Consumer HID descriptor: Play B0, Pause B1, Next B5, Previous B6.
                    // No Stop usage is advertised; GM STOP quiesces playback with explicit Pause,
                    // matching the existing AA policy. Never send the state-dependent toggle.
                    val index = when (command) {
                        com.openautolink.app.media.GmMediaControlPolicy.Command.PLAY -> 1
                        com.openautolink.app.media.GmMediaControlPolicy.Command.PAUSE,
                        com.openautolink.app.media.GmMediaControlPolicy.Command.STOP -> 2
                        com.openautolink.app.media.GmMediaControlPolicy.Command.NEXT -> 4
                        com.openautolink.app.media.GmMediaControlPolicy.Command.PREVIOUS -> 5
                    }
                    mediaCommand(index)
                }
            }, onDiagnostic = receipt)
            mediaManager?.setCarPlayMediaReady(owner,controlsReady)
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
            publishMedia(media, artwork)
        }
    }
    override fun publishRoute(media: CarPlayMediaMetadata?, route: CarPlayRouteMetadata?, artwork: ByteArray?) {
        MetadataHostOwnership.process.publish(owner) {
            if (route != previousRoute) {
                if (route == null) ClusterNavigationState.clearHost()
                else {
                    // A stop/start may be coalesced by the link worker. Its source epoch still
                    // replaces the old route, even if the new instruction is identical or absent.
                    if (previousRoute?.epoch != route.epoch) ClusterNavigationState.clearHost()
                    ClusterNavigationState.updateHost(route.instruction?.let(CarPlayClusterMetadataAdapter::maneuver))
                }
                previousRoute = route
            }
            publishMedia(media, artwork)
        }
    }
    private fun publishMedia(media: CarPlayMediaMetadata?, artwork: ByteArray?) {
        if (media != null && (media != previousMedia || !artwork.contentEquals(previousArt))) {
            mediaManager?.updateCarPlay(owner, media, artwork, clearArtwork = artwork == null)
            previousMedia = media
            previousArt = if (attemptContext?.hostMedia?.mode == CarPlayHostMediaMode.INACTIVE) null else artwork?.copyOf()
        }
    }
    override fun close() {
        MetadataHostOwnership.process.retire(owner) {
            mediaManager?.retireCarPlayControls(owner)
            ClusterNavigationState.clearHost()
            mediaManager?.resetHostToIdle()
            cluster?.release()
            cluster = null
            previousMedia = null; previousArt = null; previousNavigation = null; previousRoute = null
        }
    }
}
