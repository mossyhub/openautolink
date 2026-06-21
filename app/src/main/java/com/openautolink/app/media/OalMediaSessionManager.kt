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

    // Album art cache: avoid redundant BitmapFactory decodes
    private var cachedArtHash = 0
    private var cachedBitmap: android.graphics.Bitmap? = null

    // Media control callback for routing steering wheel buttons to bridge
    var mediaControlCallback: MediaControlCallback? = null

    interface MediaControlCallback {
        fun onPlay()
        fun onPause()
        fun onSkipToNext()
        fun onSkipToPrevious()
    }

    fun initialize() {
        synchronized(sessionLock) {
            if (mediaSession != null) return

            mediaSession = MediaSessionCompat(context, "OpenAutoLinkMedia").apply {
                setCallback(object : MediaSessionCompat.Callback() {
                    override fun onPlay() { mediaControlCallback?.onPlay() }
                    override fun onPause() { mediaControlCallback?.onPause() }
                    override fun onSkipToNext() { mediaControlCallback?.onSkipToNext() }
                    override fun onSkipToPrevious() { mediaControlCallback?.onSkipToPrevious() }

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
            cachedArtHash = 0
            cachedBitmap = null
            lastPushedPlaying = null
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
    fun resetToIdle() {
        synchronized(sessionLock) {
            val session = mediaSession ?: return
            cachedArtHash = 0
            cachedBitmap = null
            lastPushedPlaying = false
            session.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "OpenAutoLink")
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Not connected")
                    .build()
            )
            session.setPlaybackState(buildPlaybackState(PlaybackStateCompat.STATE_NONE, 0))
            Log.i(TAG, "MediaSession reset to idle")
        }
    }

    fun getSessionToken(): MediaSessionCompat.Token? = mediaSession?.sessionToken

    /**
     * Update now-playing metadata from bridge media_metadata control message.
     */
    fun updateMetadata(
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
            session.setPlaybackState(buildPlaybackState(st, 0L))
        }
    }

    /**
     * Update playback state (playing/paused + position).
     */
    fun updatePlaybackState(playing: Boolean, positionMs: Long) {
        synchronized(sessionLock) {
            val session = mediaSession ?: return
            // Don't dedup on `playing` alone: position resets to 0 on track change while
            // playing stays true, and the AAOS cluster media tile only re-reads metadata
            // when PlaybackState changes. Skipping the push leaves the cluster stuck on
            // the previous track's title/art until nav takes over and is cancelled.
            lastPushedPlaying = playing

            val state = if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
            session.setPlaybackState(buildPlaybackState(state, positionMs))
        }
    }

    private fun buildPlaybackState(state: Int, position: Long): PlaybackStateCompat {
        return PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE
            )
            .setState(state, position, if (state == PlaybackStateCompat.STATE_PLAYING) 1.0f else 0f)
            .build()
    }
}
