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
         * Push session token to a running service instance.
         * Called from SessionManager after MediaSession is initialized.
         */
        fun updateSessionToken(token: MediaSessionCompat.Token) {
            mediaSessionToken = token
            instance?.let { service ->
                try {
                    service.setSessionToken(token)
                    Log.d(TAG, "Session token pushed to running service")
                } catch (e: IllegalStateException) {
                    // Already set (reinit) — safe to ignore
                    Log.d(TAG, "Session token already set, ignoring")
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
