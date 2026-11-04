package com.openautolink.app.ui.settings

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openautolink.app.carplay.CarPlayUiState
import com.openautolink.app.cluster.ClusterCalibrationPreview
import com.openautolink.app.cluster.ClusterProjectionStatus
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.session.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** Reuses the process policy/raw producer. Settings never owns an inventory listener or host. */
@Composable
internal fun rememberProductionSecondaryDisplaysSource(
    context: Context,
    statuses: StateFlow<Map<com.openautolink.app.secondary.SecondaryOutputId,
        com.openautolink.app.secondary.SecondaryOutputStatus>>?,
): SecondaryDisplaysSource {
    val producer = remember(context.applicationContext) {
        com.openautolink.app.secondary.SecondaryDisplayPolicyInventory.get(context.applicationContext)
    }
    return remember(producer, statuses) { SecondaryDisplaysSource(producer.snapshots, producer.raw.displays, statuses) }
}

/** Current transport/host receipts only. Initial display qualification supplies editable geometry,
 * never host attachment, a phone stream, physical visibility, or an observed surface size. */
internal fun productionClusterObservation(
    session: SessionState,
    androidAuto: ClusterProjectionStatus,
    carPlay: CarPlayUiState,
): ClusterSettingsObservation {
    val carPlayOwner = carPlay.selected || carPlay.busy
    return ClusterSettingsObservation(
        source = when {
            carPlayOwner && carPlay.cluster.phoneStreamActive -> "CarPlay · phone stream active"
            carPlayOwner -> "CarPlay · selected; phone stream not active"
            session != SessionState.IDLE -> "Android Auto · ${session.name.lowercase()}"
            else -> null
        },
        output = if (carPlayOwner) carPlay.cluster.output else androidAuto,
        // Neither status flow currently supplies a measured host width/height receipt.
        actualSurface = null,
    )
}

/** Owns creation/retirement across asynchronous preference loading and Compose disposal. Installing
 * inside the IO block closes the cancellation window between creation and delivery to Main. */
private class ProductionClusterSettingsOwner {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val adapter = MutableStateFlow<ClusterSettingsRuntimeAdapter?>(null)
    val failure = MutableStateFlow<String?>(null)
    private var closed = false

    @Synchronized fun install(created: ClusterSettingsRuntimeAdapter) {
        if (closed) created.close() else adapter.value = created
    }

    @Synchronized fun fail(message: String) { if (!closed) failure.value = message }

    @Synchronized fun close() {
        if (closed) return
        closed = true
        adapter.value?.close()
        scope.cancel()
    }
}

/** Production entrypoint shared by the standalone destination and projection overlay. Required
 * preview injection must come from the host's shared admission arbiter; never construct a second
 * ClusterManager here. The owner is above navigation, so route/recomposition cannot reset a draft.
 * null means initialization is pending/failed and cluster editing is unavailable. The real navigation
 * tree stays mounted, and ordinary Save uses its own persistence transaction (no fake layout commit).
 * Once installed, the adapter is retained for this owner's lifetime, including failed layout commits.
 */
@Composable
internal fun rememberProductionClusterSettingsBinding(
    context: Context,
    preferences: AppPreferences,
    session: StateFlow<SessionState>,
    androidAuto: StateFlow<ClusterProjectionStatus>,
    carPlay: StateFlow<CarPlayUiState>,
    preview: ClusterCalibrationPreview,
    onInitializationError: (String?) -> Unit = {},
): ClusterSettingsBindings? {
    val owner = remember(context, preferences, session, androidAuto, carPlay, preview) { ProductionClusterSettingsOwner() }
    val observations = remember(owner) {
        val runtime = com.openautolink.app.secondary.SecondaryOutputRuntime.get(context)
        combine(session, androidAuto, carPlay, runtime.liveHostedDisplay, runtime.statuses) { aaSession, aaOutput, cp, live, _ ->
            // Live geometry is an exact host readback, separate from the frozen phone mapping.
            val current = runtime.currentLiveHostedDisplay()?.takeIf { live != null && it.host == live.host && it.profile == live.profile }
            // Expanded callbacks do not mutate the already offered descriptor. Keep this notice
            // until an explicit next-owner apply consumes the saved layout; host loss clears it.
            val pending = current != null && runtime.hostedLayoutSelectionReason() ==
                com.openautolink.app.cluster.ClusterLayoutFallbackReason.CANVAS_CHANGED
            productionClusterObservation(aaSession, aaOutput, cp).copy(liveDisplay = current,
                actualSurface = current?.host?.surfaceSize,
                host = if (pending) "Saved cluster layout is pending. When the cluster is ready, use Save changes or Reconnect to apply." else null)
        }.stateIn(owner.scope, SharingStarted.Eagerly,
            productionClusterObservation(session.value, androidAuto.value, carPlay.value))
    }
    DisposableEffect(owner) { onDispose { owner.close() } }
    LaunchedEffect(owner) {
        try {
            withContext(Dispatchers.IO) {
                owner.install(ClusterSettingsRuntimeAdapter.create(context, observations, preferences, preview, owner.scope))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            owner.fail(failure.message ?: "Cluster settings could not be loaded")
        }
    }
    val error by owner.failure.collectAsStateWithLifecycle()
    LaunchedEffect(error) { onInitializationError(error) }
    val adapter by owner.adapter.collectAsStateWithLifecycle()
    val active = adapter ?: return null
    val bindings by active.bindings.collectAsStateWithLifecycle()
    val inventory = remember(context.applicationContext) {
        com.openautolink.app.secondary.SecondaryDisplayPolicyInventory.get(context.applicationContext)
    }
    val snapshot by inventory.snapshots.collectAsStateWithLifecycle()
    val target = snapshot.displays.singleOrNull { it.route == com.openautolink.app.secondary.SecondaryRoute.GM_HOSTED &&
        it.reason == com.openautolink.app.secondary.SecondaryReason.NONE &&
        bindings.layout?.key?.panel?.let { panel -> panel.width == it.width && panel.height == it.height } == true }
    val directGrid = remember(context, target, owner) { target?.let { display ->
        LiveGridWindows.binding(context, display) { session.value == SessionState.IDLE && !carPlay.value.busy && !carPlay.value.selected }
    } }
    DisposableEffect(directGrid) { onDispose { directGrid?.stop?.invoke() } }
    return bindings.copy(liveGrid = directGrid, useLiveGridEditor = true)
}
