package com.openautolink.app.cluster

import android.util.Log
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator

/**
 * CarAppService for cluster navigation display.
 *
 * Returns [ClusterMainSession] which relays Trip data via NavigationManager.updateTrip().
 * This is the GM AAOS path — GM's internal OnStarTurnByTurnManager consumes the Trip data
 * and renders turn-by-turn on the instrument cluster using its own icon set.
 *
 * For standard AAOS platforms that render Screen.onGetTemplate() directly on the cluster,
 * a different session type would be needed (future work).
 *
 * This service does NOT initialize video, audio, or TCP connections.
 * It only consumes navigation state from [ClusterNavigationState].
 */
class OalClusterService : CarAppService() {

    companion object {
        private const val TAG = "OalClusterSvc"
    }

    override fun createHostValidator(): HostValidator =
        if (ClusterBindingState.frozenMapBinding() != null) HostValidator.Builder(this).build()
        else HostValidator.ALLOW_ALL_HOSTS_VALIDATOR // Preserve the existing icon-host compatibility boundary.

    private fun createFrozenSession(displayType: Int): Session {
        val binding = ClusterBindingState.frozenMapBinding()
        if (binding == null) return ClusterMainSession()
        // A service/binder can outlive an icon->map transition and retain its old validator.
        // Revalidate the actual host at this map admission boundary, never reuse allow-all.
        val trusted = hostInfo?.let { HostValidator.Builder(this).build().isValidHost(it) } == true
        return ClusterMapSession(binding.snapshot, binding.coordinator, if (trusted) displayType else -1)
    }

    @Suppress("DEPRECATION")
    override fun onCreateSession(): Session {
        Log.i(TAG, "Creating session (no SessionInfo — fallback)")
        return createFrozenSession(-1) // Map requires explicit logical MAIN, never infer a role.
    }

    override fun onCreateSession(sessionInfo: SessionInfo): Session {
        Log.i(TAG, "Creating session: displayType=${sessionInfo.displayType}")
        return createFrozenSession(sessionInfo.displayType)
    }
}
