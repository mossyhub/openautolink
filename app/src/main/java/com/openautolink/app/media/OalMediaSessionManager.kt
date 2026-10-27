package com.openautolink.app.media

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Base64
import android.util.Log

/**
 * Manages a MediaSession that publishes now-playing metadata from the bridge
 * to AAOS system UI, cluster widgets, and steering wheel controls.
 *
 * **Process-scoped singleton.** The MediaSession is created exactly once per
 * process and lives for the whole process lifetime — see [getInstance]. This
 * is deliberate: GM's AAOS cluster media widget binds a `MediaController` to
 * the token published via [OalMediaBrowserService], and that framework
 * (`MediaBrowserServiceCompat.setSessionToken`) only accepts a token **once**
 * per service instance (it throws `IllegalStateException` on any later call —
 * verified in GM's decompiled `com.gm.rhmi`). If we destroyed and recreated
 * the MediaSession on a session boundary (e.g. a phone-identity switch across
 * a car sleep/wake), the new token could never be published, and the cluster
 * would stay bound to the dead session — the "music cluster frozen after
 * switching phones" bug. Keeping one session for the process lifetime means
 * the token is published once and stays valid forever; session boundaries
 * only push new metadata/playback state via [updateMetadata] /
 * [updatePlaybackState], or clear the now-playing tile via [resetToIdle].
 *
 * Thread safety: all public methods may be called from coroutine dispatchers.
 * MediaSession is guarded by [sessionLock].
 */
class OalMediaSessionManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "OalMediaSession"
        /** Max album art dimension — keeps Binder IPC under transaction limit. */
        private const val MAX_ART_SIZE = 320

        @Volatile
        private var instance: OalMediaSessionManager? = null

        /**
         * Process-wide singleton. Always uses the application context so the
         * long-lived MediaSession never holds an Activity reference. Safe to
         * call from any thread; construction is double-checked-locked.
         */
        fun getInstance(context: Context): OalMediaSessionManager {
            return instance ?: synchronized(this) {
                instance ?: OalMediaSessionManager(context.applicationContext).also { instance = it }
            }
        }

        fun instanceOrNull(): OalMediaSessionManager? = instance
    }

    private var aaOwner: Any? = null
    private var aaGeneration = 0L
    private var cpReady = false
    private var phoneTruth: Boolean? = null
    private var successfulActive:Boolean?=null
    private var submittedStateKnown=false
    private fun setHostActive(session:MediaSessionCompat,active:Boolean) {
        session.isActive=active
        successfulActive=active
    }
    private fun submitPlaybackState(session:MediaSessionCompat,state:Int,position:Long) {
        session.setPlaybackState(buildPlaybackState(state,position))
        lastPushedState=state;lastPushedPosition=position;submittedStateKnown=true
    }
    private val browseHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val browsePending = java.util.concurrent.atomic.AtomicBoolean()
    private val browseObservers = java.util.IdentityHashMap<Any, () -> Unit>()
    /** Always queued. The barrier drains outer host effects before any foreign callback. */
    private fun browseChanged() {
        if (!browsePending.compareAndSet(false,true)) return
        if (!browseHandler.post {
            synchronized(com.openautolink.app.carplay.MetadataHostOwnership.process) { }
            val observers=synchronized(sessionLock) { browsePending.set(false); browseObservers.values.toList() }
            observers.forEach { runCatching { it() } }
        }) browsePending.set(false)
    }
    fun observeBrowse(owner:Any, observer:()->Unit) = synchronized(sessionLock) { browseObservers[owner]=observer; Unit }
    fun removeBrowseObserver(owner:Any) = synchronized(sessionLock) { browseObservers.remove(owner); Unit }
    fun setAndroidAutoMediaReady(owner:Any, nativeGeneration:Long) = synchronized(sessionLock) {
        aaOwner=owner; aaGeneration=nativeGeneration; browseChanged()
    }
    fun retireAndroidAutoMedia(owner:Any) = synchronized(sessionLock) {
        if (aaOwner === owner) { aaOwner=null; aaGeneration=0; browseChanged() }
    }
    internal fun setCarPlayMediaReady(owner:Any, ready:Boolean) = synchronized(sessionLock) {
        if (carPlayControls?.owner === owner && cpReady != ready) { cpReady=ready; browseChanged() }
    }
    fun browseState():PhoneMediaBrowseState = synchronized(sessionLock) {
        val lease=hostMediaLease
        val publication=when(lease?.mode) {
            com.openautolink.app.carplay.CarPlayHostMediaMode.INACTIVE -> PhoneMediaBrowseState.Publication.INACTIVE
            com.openautolink.app.carplay.CarPlayHostMediaMode.SUPPRESS_PLAYING -> PhoneMediaBrowseState.Publication.SUPPRESS_PLAYING
            else -> PhoneMediaBrowseState.Publication.NORMAL
        }
        when {
            carPlayControls != null || lease != null -> PhoneMediaBrowseState(PhoneMediaBrowseState.Protocol.CARPLAY,
                if (carPlayControls != null && cpReady && lease!=null && hostMediaApplied) PhoneMediaBrowseState.Phase.READY
                else if (carPlayControls != null && hostMediaApplied) PhoneMediaBrowseState.Phase.CONNECTING
                else PhoneMediaBrowseState.Phase.RETIRING,publication)
            aaOwner != null && aaGeneration > 0 && mediaControlCallback != null -> PhoneMediaBrowseState(
                PhoneMediaBrowseState.Protocol.ANDROID_AUTO,PhoneMediaBrowseState.Phase.READY)
            else -> PhoneMediaBrowseState()
        }
    }
    fun browseChildren(parentId:String): MutableList<android.support.v4.media.MediaBrowserCompat.MediaItem> {
        val state=browseState()
        fun item(id:String,title:String,subtitle:String,flags:Int)=android.support.v4.media.MediaBrowserCompat.MediaItem(
            android.support.v4.media.MediaDescriptionCompat.Builder().setMediaId(id).setTitle(title).setSubtitle(subtitle).build(),flags)
        return when(parentId) {
            OalMediaBrowserService.ROOT_ID -> mutableListOf(item(OalMediaBrowserService.PHONE_FOLDER_ID,"Phone audio",state.subtitle,
                android.support.v4.media.MediaBrowserCompat.MediaItem.FLAG_BROWSABLE))
            OalMediaBrowserService.PHONE_FOLDER_ID -> if (state.ready) mutableListOf(item(OalMediaBrowserService.PHONE_MEDIA_ID,
                "Connected phone",state.subtitle,android.support.v4.media.MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)) else mutableListOf()
            else -> mutableListOf()
        }
    }
    data class DiagnosticSnapshot(val source:PhoneMediaBrowseState, val sessionPresent:Boolean, val sessionActive:Boolean,
        val phonePlaying:Boolean?, val submittedState:Int?, val submittedPosition:Long, val leaseApplied:Boolean,
        val initialized:Boolean,val activeKnown:Boolean,val tokenPresent:Boolean)
    fun diagnosticSnapshot():DiagnosticSnapshot = synchronized(sessionLock) {
        DiagnosticSnapshot(browseState(),mediaSession!=null,successfulActive==true,phoneTruth,
            lastPushedState.takeIf { submittedStateKnown },lastPushedPosition,hostMediaApplied,mediaSessionInitialized,
            successfulActive!=null,mediaSession?.sessionToken!=null)
    }

    private var mediaSession: MediaSessionCompat? = null
    private var mediaSessionInitialized = false
    private val sessionLock = Any()
    private var hostMediaApplied = false
    private var hostMediaLease: com.openautolink.app.carplay.CarPlayHostMediaLease? = null
    fun carPlayHostMediaStatus(): String = synchronized(sessionLock) {
        hostMediaLease?.let { "Current attempt: ${it.mode} · " + if (hostMediaApplied) "applied" else "application/restoration unacknowledged" }
            ?: "No current CarPlay host-media lease"
    }
    internal fun acceptsCarPlayHost(lease: com.openautolink.app.carplay.CarPlayHostMediaLease): Boolean =
        synchronized(sessionLock) { hostMediaLease === lease && hostMediaApplied }
    private fun inactive() = hostMediaLease?.mode == com.openautolink.app.carplay.CarPlayHostMediaMode.INACTIVE

    /** Exclusion is installed before fallible platform effects and retained on failure. */
    fun beginCarPlayHostMedia(lease: com.openautolink.app.carplay.CarPlayHostMediaLease) = synchronized(sessionLock) {
        check(hostMediaLease == null || hostMediaLease === lease)
        hostMediaLease = lease
        lease.prepareDiagnostics(context)
        hostMediaApplied = false
        browseChanged()
        try {
            initialize()
            if (inactive()) {
                setHostActive(mediaSession!!,false)
                clearHostToIdle()
            }
            hostMediaApplied = true
            browseChanged()
            lease.record("apply", if (inactive()) "false" else "true", "ok")
        } catch (failure: Exception) {
            lease.record("failed", "unknown", "failed")
            throw failure
        }
    }

    /** Caller must acknowledge concrete runtime AND foreground-protection retirement first. */
    fun finishCarPlayHostMedia(lease: com.openautolink.app.carplay.CarPlayHostMediaLease) = synchronized(sessionLock) {
        if (hostMediaLease !== lease) return@synchronized
        hostMediaApplied = false
        browseChanged()
        try {
            // Cold construction/setter failure must remain recoverable on this exact lease.
            initialize()
            if (inactive()) {
                setHostActive(mediaSession!!,false)
                clearHostToIdle()
                setHostActive(mediaSession!!,true)
            }
            hostMediaLease = null
            browseChanged()
            lease.record("restore", "true", "ok")
        } catch (failure: Exception) {
            lease.record("failed", "unknown", "failed")
            throw failure
        }
    }

    // Dedup: avoid redundant pushes to MediaSession
    private var lastPushedPlaying: Boolean? = null
    private var lastPushedState = PlaybackStateCompat.STATE_NONE
    private var lastPushedPosition = 0L

    // Album art cache: avoid redundant BitmapFactory decodes
    private var cachedArtHash = 0
    private var cachedBitmap: android.graphics.Bitmap? = null

    // Media control callback for routing GM's native MediaSession controls to AA.
    @Volatile
    var mediaControlCallback: MediaControlCallback? = null
    private data class CarPlayControls(val owner: Any, val callback: MediaControlCallback,
                                       val onDiagnostic: (String) -> Unit,
                                       val sequence: java.util.concurrent.atomic.AtomicLong)
    private var carPlayControls: CarPlayControls? = null
    private data class PlayingPublicationOwner(val owner: Any, val suppress: Boolean, var receipts: Int = 0)
    // Retained through failed retirement; controls retirement alone cannot change a frozen mode.
    private var playingPublicationOwner: PlayingPublicationOwner? = null

    /** Observer must only enqueue bounded work; it runs at original command ingress, before HID mapping. */
    internal fun installCarPlayControls(owner: Any, callback: MediaControlCallback,
                                       onDiagnostic: (String) -> Unit = {}) = synchronized(sessionLock) {
        val sequence = carPlayControls?.takeIf { it.owner === owner }?.sequence
            ?: java.util.concurrent.atomic.AtomicLong()
        if (playingPublicationOwner?.owner !== owner) {
            playingPublicationOwner = PlayingPublicationOwner(owner,
                (hostMediaLease?.mode ?: com.openautolink.app.carplay.CarPlayPlayingPublicationExperiment.snapshotMode()) ==
                    com.openautolink.app.carplay.CarPlayHostMediaMode.SUPPRESS_PLAYING)
        }
        if (carPlayControls?.owner !== owner) cpReady=false
        carPlayControls = CarPlayControls(owner, callback, onDiagnostic, sequence)
        browseChanged()
    }
    internal fun retireCarPlayControls(owner: Any) = synchronized(sessionLock) {
        if (carPlayControls?.owner === owner) { carPlayControls = null; cpReady=false; browseChanged() }
    }
    private fun dispatchBrowsePlay() {
        val target=synchronized(sessionLock) {
            if(!browseState().ready) return
            Triple(carPlayControls,aaOwner?.let { it to aaGeneration },mediaControlCallback)
        }
        val cp=target.first
        if(cp!=null) dispatchMediaCommand(GmMediaControlPolicy.Command.PLAY,cp.owner)
        else com.openautolink.app.carplay.MetadataHostOwnership.process.legacy {
            val aa=target.second ?: return@legacy
            val valid=synchronized(sessionLock) { hostMediaLease==null && aaOwner===aa.first && aaGeneration==aa.second && mediaControlCallback===target.third }
            if(valid) target.third?.onCommandForOwner(GmMediaControlPolicy.Command.PLAY,aa.first,aa.second)
        }
    }
    private fun dispatchMediaCommand(command: GmMediaControlPolicy.Command, expectedCarPlayOwner:Any?=null) {
        val target = synchronized(sessionLock) { carPlayControls }
        if(expectedCarPlayOwner!=null && target?.owner !== expectedCarPlayOwner) return
        if (target != null) {
            val elapsedMs = android.os.SystemClock.elapsedRealtime()
            com.openautolink.app.carplay.MetadataHostOwnership.process.publish(target.owner) {
                // Framework controller context is valid only during this synchronous callback.
                // Never retain/export the package, UID or PID; these classes do not prove focus ownership.
                val controller = runCatching { controllerClass(mediaSession?.currentControllerInfo?.packageName) }
                    .getOrDefault("unknown")
                val sequence = target.sequence.incrementAndGet()
                runCatching { target.onDiagnostic("media stage=media-command-ingress command=${command.name} controller=$controller sequence=$sequence elapsedMs=$elapsedMs") }
                target.callback.onCommand(command)
            }
        } else com.openautolink.app.carplay.MetadataHostOwnership.process.legacy {
            val callback = synchronized(sessionLock) { mediaControlCallback.takeIf { hostMediaLease == null } }
            callback?.onCommand(command)
        }
    }

    internal fun controllerClass(packageName: String?): String = when (packageName) {
        null, "" -> "unknown"
        "com.android.bluetooth" -> "bluetooth"
        "com.android.car" -> "car-service"
        "com.android.systemui" -> "system-ui"
        "com.gm.rhmi", "com.gm.gmaudio.server" -> "oem"
        else -> "other"
    }

    interface MediaControlCallback {
        fun onCommand(command: GmMediaControlPolicy.Command)
        fun onCommandForOwner(command:GmMediaControlPolicy.Command,owner:Any,nativeGeneration:Long) { onCommand(command) }
    }

    fun initialize() {
        synchronized(sessionLock) {
            if (mediaSessionInitialized) return

            // Retain the process session before any fallible setters. Retrying an incomplete
            // initialization must finish this instance, never replace a published token.
            val session = mediaSession ?: MediaSessionCompat(context, "OpenAutoLinkMedia").also { mediaSession = it }
            session.apply {
                setExtras(android.os.Bundle(controller.extras ?: android.os.Bundle()).apply {
                    putString("android.media.session.BROWSE_SERVICE", OalMediaBrowserService::class.java.name)
                })
                setCallback(object : MediaSessionCompat.Callback() {
                    override fun onPlay() {
                        dispatchMediaCommand(GmMediaControlPolicy.Command.PLAY)
                    }

                    override fun onPlayFromMediaId(mediaId: String?, extras: android.os.Bundle?) {
                        if (mediaId == OalMediaBrowserService.PHONE_MEDIA_ID) dispatchBrowsePlay()
                    }

                    override fun onPause() {
                        dispatchMediaCommand(GmMediaControlPolicy.Command.PAUSE)
                    }

                    override fun onStop() {
                        dispatchMediaCommand(GmMediaControlPolicy.Command.STOP)
                    }

                    override fun onSkipToNext() {
                        dispatchMediaCommand(GmMediaControlPolicy.Command.NEXT)
                    }

                    override fun onSkipToPrevious() {
                        dispatchMediaCommand(GmMediaControlPolicy.Command.PREVIOUS)
                    }

                    override fun onMediaButtonEvent(mediaButtonEvent: android.content.Intent): Boolean {
                        val ke = mediaButtonEvent.getParcelableExtra<android.view.KeyEvent>(android.content.Intent.EXTRA_KEY_EVENT)
                        Log.i(TAG, "onMediaButtonEvent: keycode=${ke?.keyCode} (${ke?.let { android.view.KeyEvent.keyCodeToString(it.keyCode) }}) action=${ke?.action}")
                        com.openautolink.app.diagnostics.DiagnosticLog.i(
                            "input",
                            "MediaSession.onMediaButtonEvent: keycode=${ke?.keyCode} (${ke?.let { android.view.KeyEvent.keyCodeToString(it.keyCode) }}) action=${ke?.action}"
                        )
                        return super.onMediaButtonEvent(mediaButtonEvent)
                    }

                    override fun onCommand(command: String, extras: android.os.Bundle?, cb: android.os.ResultReceiver?) {
                        Log.i(TAG, "onCommand: $command")
                        com.openautolink.app.diagnostics.DiagnosticLog.i("input", "MediaSession.onCommand: $command")
                        super.onCommand(command, extras, cb)
                    }
                }, android.os.Handler(android.os.Looper.getMainLooper()))

                submitPlaybackState(this,PlaybackStateCompat.STATE_NONE,0)

                setMetadata(
                    MediaMetadataCompat.Builder()
                        .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "OpenAutoLink")
                        .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Not connected")
                        .build()
                )

                setHostActive(this,!inactive())
            }
            mediaSessionInitialized = true
            Log.i(TAG, "MediaSession initialized")
        }
    }

    fun release() {
        synchronized(sessionLock) {
            mediaSession?.let {
                setHostActive(it,false)
                it.release()
            }
            mediaSession = null
            mediaSessionInitialized = false
            successfulActive=null;submittedStateKnown=false;aaOwner=null;aaGeneration=0;cpReady=false;phoneTruth=null
            browseObservers.clear()
            mediaControlCallback = null
            carPlayControls = null
            playingPublicationOwner = null
            cachedArtHash = 0
            cachedBitmap = null
            lastPushedPlaying = null
            lastPushedState = PlaybackStateCompat.STATE_NONE
            lastPushedPosition = 0L
            Log.i(TAG, "MediaSession released")
        }
        instance = null
    }

    /**
     * Reset the now-playing tile to an idle "Not connected" state **without**
     * destroying the MediaSession or invalidating its token. Called on session
     * teardown (e.g. [com.openautolink.app.session.SessionManager.stop]) so the
     * cluster doesn't keep showing the previous phone's track, while the token
     * stays valid for the next session — see the class kdoc for why the session
     * must outlive individual connections.
     */
    fun resetToIdle() = com.openautolink.app.carplay.MetadataHostOwnership.process.legacy { resetHostToIdle() }

    internal fun resetHostToIdle() = synchronized(sessionLock) {
        if (!inactive()) clearHostToIdle()
    }

    private fun clearHostToIdle() {
        synchronized(sessionLock) {
            phoneTruth=null
            val session = mediaSession ?: return
            session.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "OpenAutoLink")
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Not connected")
                    .build()
            )
            cachedArtHash = 0
            cachedBitmap = null
            submitPlaybackState(session,PlaybackStateCompat.STATE_NONE,0)
            // Track successful submissions, not attempts (retirement can throw and be retried).
            lastPushedPlaying = false
            lastPushedState = PlaybackStateCompat.STATE_NONE
            lastPushedPosition = 0L
            Log.i(TAG, "MediaSession reset to idle")
        }
    }

    fun getSessionToken(): MediaSessionCompat.Token? = mediaSession?.sessionToken

    /**
     * Update now-playing metadata from bridge media_metadata control message.
     */
    fun updateMetadata(title: String?, artist: String?, album: String?, durationMs: Long?, albumArtBase64: String?) =
        com.openautolink.app.carplay.MetadataHostOwnership.process.legacy {
            updateAaMetadata(title, artist, album, durationMs, albumArtBase64)
        }

    private fun updateAaMetadata(
        title: String?,
        artist: String?,
        album: String?,
        durationMs: Long?,
        albumArtBase64: String?
    ) {
        synchronized(sessionLock) {
            val session = mediaSession ?: return
            if (hostMediaLease!=null) return

            val builder = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, OalMediaBrowserService.PHONE_MEDIA_ID)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title ?: "Unknown")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist ?: "Unknown")
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album ?: "")
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, "OpenAutoLink")

            if (durationMs != null && durationMs > 0) {
                builder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
            }

            var nextHash=cachedArtHash
            var nextBitmap=cachedBitmap
            if (albumArtBase64 != null) {
                val hash = albumArtBase64.hashCode()
                val bitmap = if (hash == cachedArtHash && cachedBitmap != null) {
                    cachedBitmap
                } else {
                    try {
                        val bytes = Base64.decode(albumArtBase64, Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { raw ->
                            // Scale down to fit Binder transaction limit for cross-process IPC
                            val scaled = if (raw.width > MAX_ART_SIZE || raw.height > MAX_ART_SIZE) {
                                val scale = MAX_ART_SIZE.toFloat() / maxOf(raw.width, raw.height)
                                val w = (raw.width * scale).toInt()
                                val h = (raw.height * scale).toInt()
                                Bitmap.createScaledBitmap(raw, w, h, true).also {
                                    if (it !== raw) raw.recycle()
                                }
                            } else {
                                raw
                            }
                            nextHash = hash
                            nextBitmap = scaled
                            scaled
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to decode album art: ${e.message}")
                        null
                    }
                }
                if (bitmap != null) {
                    builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
                    builder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, bitmap)
                }
            } else {
                // Playback-only update (no new art) — preserve cached bitmap
                cachedBitmap?.let { bitmap ->
                    builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
                    builder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, bitmap)
                }
            }

            session.setMetadata(builder.build())
            cachedArtHash=nextHash
            cachedBitmap=nextBitmap

            // Nudge PlaybackState so consumers that only react to onPlaybackStateChanged
            // (notably the AAOS cluster media tile) re-read the new metadata. We don't
            // know the current position on a metadata-only update, so reuse 0 with the
            // last known playing state. setState is idempotent if nothing actually changed
            // for consumers that DO listen to onMetadataChanged.
            val playing = lastPushedPlaying ?: false
            val st = if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
            submitPlaybackState(session,st,0L)
            lastPushedState = st
            lastPushedPosition = 0L
        }
    }

    /**
     * Update playback state (playing/paused + position).
     */
    fun updatePlaybackState(playing: Boolean, positionMs: Long) =
        com.openautolink.app.carplay.MetadataHostOwnership.process.legacy { updateAaPlaybackState(playing, positionMs) }

    private fun updateAaPlaybackState(playing: Boolean, positionMs: Long) {
        synchronized(sessionLock) {
            if(hostMediaLease!=null) return
            phoneTruth=playing
            val session = mediaSession ?: return
            // Don't dedup on `playing` alone: position resets to 0 on track change while
            // playing stays true, and the AAOS cluster media tile only re-reads metadata
            // when PlaybackState changes. Skipping the push leaves the cluster stuck on
            // the previous track's title/art until nav takes over and is cancelled.
            val state = if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
            submitPlaybackState(session,state,positionMs)
            lastPushedPlaying = playing
            lastPushedPosition = positionMs
            lastPushedState = state
        }
    }

    /** Full retained CP snapshot. Null art + clearArtwork explicitly removes prior cover.
     * Decode runs on the metadata worker, never on the iAP2 link pump. */
    fun updateCarPlay(owner: Any, value: com.openautolink.carplaylab.metadata.CarPlayMediaMetadata,
                       artwork: ByteArray?, clearArtwork: Boolean): Boolean =
        com.openautolink.app.carplay.MetadataHostOwnership.process.publish(owner) {
            synchronized(sessionLock) {
                phoneTruth=value.playing
                val session = mediaSession ?: return@synchronized
                if (inactive()) {
                    hostMediaLease?.record("withhold", "false", "withheld")
                    return@synchronized
                }
                var nextBitmap=if(clearArtwork) null else cachedBitmap
                if (artwork != null) nextBitmap=decodeCarPlayArtwork(artwork)
                val builder = MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, OalMediaBrowserService.PHONE_MEDIA_ID)
                value.title?.let { builder.putString(MediaMetadataCompat.METADATA_KEY_TITLE, it) }
                value.artist?.let { builder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, it) }
                value.album?.let { builder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, it) }
                value.durationMillis?.let { builder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, it) }
                nextBitmap?.let {
                    builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
                    builder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, it)
                }
                session.setMetadata(builder.build())
                cachedBitmap=nextBitmap
                if(clearArtwork) cachedArtHash=0
                val position=value.elapsedMillis ?: PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN
                val previousState = lastPushedState
                val mode = playingPublicationOwner?.takeIf { it.owner === owner }
                val submittedState = when (value.playing) {
                    true -> if (mode?.suppress == true) PlaybackStateCompat.STATE_NONE else PlaybackStateCompat.STATE_PLAYING
                    false -> PlaybackStateCompat.STATE_PAUSED
                    null -> PlaybackStateCompat.STATE_NONE
                }
                submitPlaybackState(session,submittedState,position)
                lastPushedPlaying=value.playing
                lastPushedPosition=position
                lastPushedState = submittedState
                // Own Android boundary only, not OEM selection/focus or phone playback truth.
                // Constant-space budget per exact owner; never export title, artwork or identity.
                val observer = carPlayControls?.takeIf { it.owner === owner }?.onDiagnostic
                if (mode != null && observer != null && mode.receipts < 32) {
                    mode.receipts++
                    fun label(state: Int) = when (state) {
                        PlaybackStateCompat.STATE_PLAYING -> "playing"
                        PlaybackStateCompat.STATE_PAUSED -> "paused"
                        else -> "none"
                    }
                    runCatching { observer("media stage=playing-publication previous=${label(previousState)} new=${label(submittedState)} active=${session.isActive} mode=${if (mode.suppress) "suppress" else "normal"} owner=current") }
                }
            }
        }

    private fun decodeCarPlayArtwork(bytes: ByteArray): Bitmap? = runCatching {
        if (bytes.isEmpty() || bytes.size > 2 * 1024 * 1024) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..16384 || bounds.outHeight !in 1..16384) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_ART_SIZE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        if (raw.width <= MAX_ART_SIZE && raw.height <= MAX_ART_SIZE) raw else {
            val scale = MAX_ART_SIZE.toFloat() / maxOf(raw.width, raw.height)
            Bitmap.createScaledBitmap(raw, maxOf(1, (raw.width * scale).toInt()), maxOf(1, (raw.height * scale).toInt()), true)
                .also { if (it !== raw) raw.recycle() }
        }
    }.getOrNull()

    private fun buildPlaybackState(state: Int, position: Long): PlaybackStateCompat {
        val actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
        return PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(state, position, if (state == PlaybackStateCompat.STATE_PLAYING) 1.0f else 0f)
            .build()
    }
}
