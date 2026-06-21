package com.openautolink.app.media

import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import androidx.media.MediaBrowserServiceCompat

/**
 * MediaBrowserService that registers OpenAutoLink as a media source in AAOS.
 *
 * Exposes the [MediaSessionCompat.Token] so AAOS system UI, cluster, and steering wheel
 * controls can discover and interact with the current playback session. The browse tree
 * is empty — we're a projection app, not a content library.
 */
class OalMediaBrowserService : MediaBrowserServiceCompat() {

    companion object {
        private const val TAG = "OalMediaBrowser"
        private const val EMPTY_ROOT_ID = "oal_empty_root"

        @Volatile
        var mediaSessionToken: MediaSessionCompat.Token? = null

        /**
         * Publish the process-wide MediaSession token to a running service
         * instance. Called once from [com.openautolink.app.OalApplication] at
         * process start, after the singleton MediaSession is initialized.
         *
         * The token is process-stable (the MediaSession is never recreated —
         * see [OalMediaSessionManager]), so this is effectively a one-shot.
         * `MediaBrowserServiceCompat.setSessionToken` throws
         * `IllegalStateException` if a token was already set on this service
         * instance; that is benign only when it's the *same* token (idempotent
         * republish). A throw while attempting to set a *different* token would
         * mean a second MediaSession leaked into the process — a real bug — so
         * we log it at WARN rather than swallowing it silently.
         */
        fun updateSessionToken(token: MediaSessionCompat.Token) {
            val previous = mediaSessionToken
            mediaSessionToken = token
            instance?.let { service ->
                try {
                    service.setSessionToken(token)
                    Log.d(TAG, "Session token published to running service")
                } catch (e: IllegalStateException) {
                    if (previous == token) {
                        // Same token re-published (e.g. service restarted and
                        // re-read the static token in onCreate). Harmless.
                        Log.d(TAG, "Session token already set (same token), ignoring")
                    } else {
                        // A different token can never reach the cluster on this
                        // service instance — the MediaSession should be a
                        // process singleton. Surface it instead of hiding it.
                        Log.w(TAG, "setSessionToken rejected a NEW token; cluster will keep the old binding. " +
                            "This indicates the MediaSession was recreated — it must be process-scoped.")
                    }
                }
            }
        }

        @Volatile
        private var instance: OalMediaBrowserService? = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        mediaSessionToken?.let { token ->
            setSessionToken(token)
        }
        Log.d(TAG, "Service created")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        // Update token if it became available after onCreate
        if (sessionToken == null) {
            mediaSessionToken?.let { token ->
                try {
                    setSessionToken(token)
                } catch (_: IllegalStateException) { }
            }
        }
        return BrowserRoot(EMPTY_ROOT_ID, null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        result.sendResult(mutableListOf())
    }
}
