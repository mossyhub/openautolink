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

    private var mediaSession: MediaSessionCompat? = null
    private val sessionLock = Any()

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
                com.openautolink.app.carplay.CarPlayPlayingPublicationExperiment.snapshot())
        }
        carPlayControls = CarPlayControls(owner, callback, onDiagnostic, sequence)
    }
    internal fun retireCarPlayControls(owner: Any) = synchronized(sessionLock) {
        if (carPlayControls?.owner === owner) carPlayControls = null
    }
    private fun dispatchMediaCommand(command: GmMediaControlPolicy.Command) {
        val target = synchronized(sessionLock) { carPlayControls }
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
            mediaControlCallback?.onCommand(command)
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
    }

    fun initialize() {
        synchronized(sessionLock) {
            if (mediaSession != null) return

            mediaSession = MediaSessionCompat(context, "OpenAutoLinkMedia").apply {
                setCallback(object : MediaSessionCompat.Callback() {
                    override fun onPlay() {
                        dispatchMediaCommand(GmMediaControlPolicy.Command.PLAY)
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
                })

                setPlaybackState(buildPlaybackState(PlaybackStateCompat.STATE_NONE, 0))

                setMetadata(
                    MediaMetadataCompat.Builder()
                        .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "OpenAutoLink")
                        .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Not connected")
                        .build()
                )

                isActive = true
            }
            Log.i(TAG, "MediaSession initialized")
        }
    }

    fun release() {
        synchronized(sessionLock) {
            mediaSession?.let {
                it.isActive = false
                it.release()
            }
            mediaSession = null
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

    internal fun resetHostToIdle() {
        synchronized(sessionLock) {
            val session = mediaSession ?: return
            cachedArtHash = 0
            cachedBitmap = null
            session.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "OpenAutoLink")
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Not connected")
                    .build()
            )
            session.setPlaybackState(buildPlaybackState(PlaybackStateCompat.STATE_NONE, 0))
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

            val builder = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title ?: "Unknown")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist ?: "Unknown")
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album ?: "")
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, "OpenAutoLink")

            if (durationMs != null && durationMs > 0) {
                builder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
            }

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
                            cachedArtHash = hash
                            cachedBitmap = scaled
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

            // Nudge PlaybackState so consumers that only react to onPlaybackStateChanged
            // (notably the AAOS cluster media tile) re-read the new metadata. We don't
            // know the current position on a metadata-only update, so reuse 0 with the
            // last known playing state. setState is idempotent if nothing actually changed
            // for consumers that DO listen to onMetadataChanged.
            val playing = lastPushedPlaying ?: false
            val st = if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
            lastPushedState = st
            lastPushedPosition = 0L
            session.setPlaybackState(buildPlaybackState(st, 0L))
        }
    }

    /**
     * Update playback state (playing/paused + position).
     */
    fun updatePlaybackState(playing: Boolean, positionMs: Long) =
        com.openautolink.app.carplay.MetadataHostOwnership.process.legacy { updateAaPlaybackState(playing, positionMs) }

    private fun updateAaPlaybackState(playing: Boolean, positionMs: Long) {
        synchronized(sessionLock) {
            val session = mediaSession ?: return
            // Don't dedup on `playing` alone: position resets to 0 on track change while
            // playing stays true, and the AAOS cluster media tile only re-reads metadata
            // when PlaybackState changes. Skipping the push leaves the cluster stuck on
            // the previous track's title/art until nav takes over and is cancelled.
            lastPushedPlaying = playing
            lastPushedPosition = positionMs

            val state = if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
            lastPushedState = state
            session.setPlaybackState(buildPlaybackState(state, positionMs))
        }
    }

    /** Full retained CP snapshot. Null art + clearArtwork explicitly removes prior cover.
     * Decode runs on the metadata worker, never on the iAP2 link pump. */
    fun updateCarPlay(owner: Any, value: com.openautolink.carplaylab.metadata.CarPlayMediaMetadata,
                       artwork: ByteArray?, clearArtwork: Boolean): Boolean =
        com.openautolink.app.carplay.MetadataHostOwnership.process.publish(owner) {
            synchronized(sessionLock) {
                val session = mediaSession ?: return@synchronized
                if (clearArtwork) { cachedBitmap = null; cachedArtHash = 0 }
                if (artwork != null) cachedBitmap = decodeCarPlayArtwork(artwork)
                val builder = MediaMetadataCompat.Builder()
                value.title?.let { builder.putString(MediaMetadataCompat.METADATA_KEY_TITLE, it) }
                value.artist?.let { builder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, it) }
                value.album?.let { builder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, it) }
                value.durationMillis?.let { builder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, it) }
                cachedBitmap?.let {
                    builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
                    builder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, it)
                }
                session.setMetadata(builder.build())
                lastPushedPlaying = value.playing
                lastPushedPosition = value.elapsedMillis ?: PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN
                val previousState = lastPushedState
                val mode = playingPublicationOwner?.takeIf { it.owner === owner }
                val submittedState = when (value.playing) {
                    true -> if (mode?.suppress == true) PlaybackStateCompat.STATE_NONE else PlaybackStateCompat.STATE_PLAYING
                    false -> PlaybackStateCompat.STATE_PAUSED
                    null -> PlaybackStateCompat.STATE_NONE
                }
                session.setPlaybackState(buildPlaybackState(submittedState, lastPushedPosition))
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
            PlaybackStateCompat.ACTION_PLAY_PAUSE
        return PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(state, position, if (state == PlaybackStateCompat.STATE_PLAYING) 1.0f else 0f)
            .build()
    }
}
