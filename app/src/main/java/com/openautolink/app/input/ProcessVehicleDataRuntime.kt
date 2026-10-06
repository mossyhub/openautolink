package com.openautolink.app.input

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import com.openautolink.app.data.EvLearnedRateEstimator
import com.openautolink.app.diagnostics.DiagnosticLog
import com.openautolink.app.diagnostics.EvContributionService
import com.openautolink.app.transport.ControlMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Dispatches every process-owned raw VHAL batch before any projection gating.
 * The learner and compact recorder therefore remain active with no phone session,
 * while the optional session consumer only mirrors the same batch to AASDK.
 */
internal class ProcessVehicleDataCoordinator(
    private val learn: (ControlMessage.VehicleData, Long) -> Unit,
    private val contribute: (ControlMessage.VehicleData) -> Unit,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) {
    class SessionAttachment internal constructor(internal val id: Long)

    private val _latestVehicleData = MutableStateFlow(ControlMessage.VehicleData())
    val latestVehicleData: StateFlow<ControlMessage.VehicleData> = _latestVehicleData.asStateFlow()

    private val consumerLock = Any()
    private var retainedVehicleData: ControlMessage.VehicleData? = null
    private var projectionConsumer: ((ControlMessage.VehicleData) -> Unit)? = null
    private var projectionAttachmentId = 0L

    fun onRawBatch(data: ControlMessage.VehicleData) {
        synchronized(consumerLock) {
            retainedVehicleData = data
            _latestVehicleData.value = data
            learn(data, elapsedRealtime())
            contribute(data)
            projectionConsumer?.invoke(data)
        }
    }

    fun attachSessionConsumer(consumer: (ControlMessage.VehicleData) -> Unit): SessionAttachment {
        synchronized(consumerLock) {
            projectionConsumer = consumer
            val attachment = SessionAttachment(++projectionAttachmentId)
            retainedVehicleData?.let(consumer)
            return attachment
        }
    }

    fun detachSessionConsumer() {
        synchronized(consumerLock) { projectionConsumer = null }
    }

    fun detachSessionConsumer(attachment: SessionAttachment) {
        synchronized(consumerLock) {
            if (attachment.id == projectionAttachmentId) projectionConsumer = null
        }
    }

    internal fun sessionConsumer(): ((ControlMessage.VehicleData) -> Unit)? =
        synchronized(consumerLock) { projectionConsumer }
}

/** Owns exactly one forwarder and never stops it at session boundaries. */
internal class ProcessVehicleDataOwner(
    private val automotive: Boolean,
    private val createForwarder: ((ControlMessage.VehicleData) -> Unit) -> VehicleDataForwarder,
    private val coordinator: ProcessVehicleDataCoordinator,
    private val resetLearnerContinuity: () -> Unit = {},
) {
    val forwarder: VehicleDataForwarder? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        if (automotive) createForwarder(coordinator::onRawBatch) else null
    }

    fun requestSubscriptionRefresh(reason: String) = forwarder?.requestSubscriptionRefresh(reason)

    fun startProcess() {
        forwarder?.start()
    }

    fun attachSessionConsumer(consumer: (ControlMessage.VehicleData) -> Unit): ProcessVehicleDataCoordinator.SessionAttachment =
        coordinator.attachSessionConsumer(consumer)

    fun detachSessionConsumer() = coordinator.detachSessionConsumer()

    fun detachSessionConsumer(attachment: ProcessVehicleDataCoordinator.SessionAttachment) =
        coordinator.detachSessionConsumer(attachment)

    fun onSessionLifecycleBoundary() {
        resetLearnerContinuity()
        detachSessionConsumer()
    }

    fun onSessionLifecycleBoundary(attachment: ProcessVehicleDataCoordinator.SessionAttachment) {
        resetLearnerContinuity()
        detachSessionConsumer(attachment)
    }

    val latestVehicleData: StateFlow<ControlMessage.VehicleData>
        get() = coordinator.latestVehicleData

    val isActive: Boolean get() = forwarder?.isActive == true
    val propertyStatus: Map<String, String> get() = forwarder?.propertyStatus.orEmpty()
}

/** Application-owned VHAL runtime. SessionManager borrows state/dispatch only. */
object ProcessVehicleDataRuntime {
    @Volatile private var owner: ProcessVehicleDataOwner? = null

    fun initialize(context: Context, estimator: EvLearnedRateEstimator) {
        if (owner != null) return
        synchronized(this) {
            if (owner != null) return
            val app = context.applicationContext
            val coordinator = ProcessVehicleDataCoordinator(
                learn = estimator::onVehicleTick,
                contribute = { data ->
                    IgnitionMonitor.acceptProcessVehicleData(data)
                    EvContributionService.onVehicle(data)
                },
            )
            val automotive = app.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
            owner = ProcessVehicleDataOwner(
                automotive = automotive,
                createForwarder = { sink ->
                    VehicleDataForwarderImpl(
                        app,
                        sendMessage = sink,
                        onIgnitionOn = IgnitionMonitor::acceptIgnitionOn,
                    )
                },
                coordinator = coordinator,
                resetLearnerContinuity = estimator::resetContinuity,
            ).also { it.startProcess() }
            DiagnosticLog.i("vhal", "process vehicle runtime initialized automotive=$automotive")
        }
    }

    fun requestSubscriptionRefresh(reason: String) {
        owner?.requestSubscriptionRefresh(reason)
    }

    fun forwarderOrNull(): VehicleDataForwarder? = owner?.forwarder

    fun latestVehicleData(): StateFlow<ControlMessage.VehicleData>? = owner?.latestVehicleData

    fun isActive(): Boolean = owner?.isActive == true

    fun propertyStatus(): Map<String, String> = owner?.propertyStatus.orEmpty()

    internal fun attachSessionConsumer(consumer: (ControlMessage.VehicleData) -> Unit): ProcessVehicleDataCoordinator.SessionAttachment? =
        owner?.attachSessionConsumer(consumer)

    internal fun onSessionLifecycleBoundary(attachment: ProcessVehicleDataCoordinator.SessionAttachment) {
        owner?.onSessionLifecycleBoundary(attachment)
    }
}
