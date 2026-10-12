package com.openautolink.app.transport.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbConstants as AndroidUsbConstants
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.openautolink.app.diagnostics.OalLog
import com.openautolink.app.transport.aasdk.AasdkTransportPipe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * USB connection state machine.
 */
enum class UsbConnectionState {
    IDLE,
    DEVICE_DETECTED,
    AWAITING_USER_SELECTION,
    PERMISSION_REQUESTED,
    SWITCHING_TO_ACCESSORY,
    ACCESSORY_DETECTED,
    CONNECTING,
    CONNECTED,
}

/**
 * A user-visible USB device candidate shown in the picker.
 */
data class UsbDeviceCandidate(
    val deviceName: String,        // stable id from UsbDevice.deviceName (e.g. /dev/bus/usb/001/002)
    val vendorId: Int,
    val productId: Int,
    val friendlyName: String,      // manufacturer + product, falling back to VID:PID
    val isAccessoryMode: Boolean,  // true if already in Google Accessory mode
    val isLikelyPhone: Boolean = false, // heuristic: exposes ADB/MTP/PTP/vendor interfaces
)

/**
 * Manages the full USB transport lifecycle:
 *   1. Monitors USB attach/detach events
 *   2. Requests USB permission from the user
 *   3. Performs AOA v2 handshake to switch phone to accessory mode
 *   4. Opens bulk endpoints and creates AasdkTransportPipe
 *   5. Delivers the pipe to the session via [onTransportReady]
 *
 * Mirrors the lifecycle pattern of TcpConnector.
 */
class UsbConnectionManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onTransportReady: (AasdkTransportPipe) -> Unit,
    private val onTransportDetached: (AasdkTransportPipe, () -> Unit) -> Unit = { _, retired -> retired() },
    // Runtime owns Apple permission, mode switching and endpoints; never AOA.
    private val onIphoneSelected: (UsbDevice) -> Boolean = { false },
    private val onIphoneDetached: (UsbDevice, () -> Unit) -> Unit = { _, retired -> retired() },
    private val onCandidate: ((Long, UsbDevice) -> Unit)? = null,
    private val onCandidateDetached: (Long) -> Unit = {},
    private val onAdmissionEnded: (Long) -> Unit = {},
    private val consent: com.openautolink.app.carplay.UsbHotPlugConsent = com.openautolink.app.carplay.UsbHotPlugConsent(),
) {
    companion object {
        private const val TAG = "UsbConnectionManager"
        private const val ACTION_USB_PERMISSION = "com.openautolink.app.USB_PERMISSION"
        private const val EXTRA_PERMISSION_GENERATION = "usb_generation"
        private const val IPHONE_REENUMERATION_GRACE_MS = 3_000L
        /**
         * Poll cadence while waiting for the phone to re-enumerate in accessory
         * mode. 100ms (was 500ms) so we notice within ~a tenth of a second of the
         * device appearing — on a head unit this delay is on the critical path of
         * every connect and reconnect, and the poll itself is just a HashMap read.
         */
        private const val ACCESSORY_SCAN_INTERVAL_MS = 100L
        /** 100ms x 100 = 10s budget, unchanged in wall-clock terms from 500ms x 20. */
        private const val ACCESSORY_SCAN_MAX_ATTEMPTS = 100

        /**
         * Device/interface classes that can never be an Android phone acting as
         * an AOA source: 0x01 audio, 0x03 HID, 0x07 printer, 0x08 mass storage,
         * 0x09 hub, 0x0B smart card, 0x0E video (webcams).
         */
        private val EXCLUDED_USB_CLASSES = setOf(0x01, 0x03, 0x07, 0x08, 0x09, 0x0B, 0x0E)

        private val _status = MutableStateFlow("Idle")
        val status: StateFlow<String> = _status.asStateFlow()

        private val _connectionState = MutableStateFlow(UsbConnectionState.IDLE)
        val connectionState: StateFlow<UsbConnectionState> = _connectionState.asStateFlow()

        private val _availableDevices = MutableStateFlow<List<UsbDeviceCandidate>>(emptyList())
        val availableDevices: StateFlow<List<UsbDeviceCandidate>> = _availableDevices.asStateFlow()

        @Volatile
        private var activeInstance: UsbConnectionManager? = null

        /** Called from the UI when the user picks a device from the picker. */
        fun selectDevice(deviceName: String) {
            activeInstance?.onUserSelectedDevice(deviceName)
        }
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    @Volatile
    private var isRunning = false

    @Volatile
    private var currentPipe: UsbTransportPipe? = null

    @Volatile
    private var currentAasdkPipe: AasdkTransportPipe? = null

    private val dispatchOwnership = UsbDispatchOwnership()

    private var pendingCandidateGeneration: Long? = null
    private var admissionGeneration: Long? = null

    @Synchronized fun pendingDevice(generation: Long): UsbDevice? {
        val candidate = consent.current(generation) ?: return null
        return usbManager.deviceList[candidate.deviceName]?.takeIf {
            isRunning && it.vendorId == candidate.vendorId && it.deviceName !in detachedDeviceNames
        }
    }
    @Synchronized fun stay(generation: Long) {
        if (consent.stay(generation)) pendingCandidateGeneration = null
    }
    /** Called only AFTER awaited projection retirement; presence is checked a second time. */
    @Synchronized fun accept(generation: Long): Boolean {
        val device = pendingDevice(generation) ?: return false
        if (dispatchOwnership.current() != null || retirementPending.get()) return false
        consent.accept(generation) ?: return false
        pendingCandidateGeneration = null
        admissionGeneration = generation
        requestPermissionOrConnect(device, consentGranted = true)
        return dispatchOwnership.current() != null
    }
    @Synchronized private fun releaseSelection(token: UsbDispatchOwnership.Token): Boolean {
        if (!dispatchOwnership.release(token)) return false
        val generation = admissionGeneration
        admissionGeneration = null
        if (generation != null) onAdmissionEnded(generation)
        return true
    }
    /** Startup failures must retire the session before releasing this exact dispatch token. */
    @Synchronized fun failedAdmission(generation: Long? = null) {
        if (generation != null && admissionGeneration != generation) return
        val token = dispatchOwnership.current() ?: return
        rejectedDeviceNames.add(token.deviceName)
        closePipe()
        retirementPending.set(false) // Caller has awaited the exact session/runtime retirement.
        releaseSelection(token)
    }
    @Synchronized fun ownsTransport(pipe: AasdkTransportPipe, generation: Long): Boolean =
        currentAasdkPipe === pipe && admissionGeneration == generation &&
            dispatchOwnership.current()?.retiring == false && !retirementPending.get()
    @Synchronized fun retryCandidates() {
        if (!isRunning || dispatchOwnership.current() != null || retirementPending.get()) return
        pendingCandidateGeneration?.let { onCandidateDetached(it) }
        pendingCandidateGeneration = null
        usbManager.deviceList.values.forEach {
            rejectedDeviceNames.remove(it.deviceName)
            consent.retry(it.deviceName)
        }
        scanExistingDevices()
    }
    @Volatile private var selectedIphone: UsbDevice? = null
    private val rejectedDeviceNames = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var iphoneReenumerationJob: Job? = null
    private var iphoneTransitionToken: UsbDispatchOwnership.Token? = null
    private var iphoneTransitionDeadline = 0L

    /** Capture with retirement acknowledgement before launching the runtime; closures fence generations.
     * Begin BEFORE the mode-switch request. Accept ONLY after fresh permission, private serial,
     * exact bus, detach, configuration and uniqueness have been checked by the runtime. */
    fun iphoneReenumerationCallbacks(device: UsbDevice): Pair<() -> Boolean, (UsbDevice) -> Boolean> {
        val token = dispatchOwnership.current()?.takeIf {
            it.route == UsbDispatchOwnership.Route.APPLE && it.deviceName == device.deviceName && !it.retiring
        } ?: return Pair({ false }, { false })
        return Pair({ beginIphoneTransition(token) }, { replacement -> acceptIphoneReplacement(token, replacement) })
    }

    @Synchronized private fun beginIphoneTransition(token: UsbDispatchOwnership.Token): Boolean {
        if (!isRunning || !dispatchOwnership.isCurrent(token) || token.retiring ||
            token.reenumerating || iphoneTransitionToken != null) return false
        iphoneTransitionToken = token
        iphoneTransitionDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
            com.openautolink.app.carplay.UsbCarPlayPermission.REENUMERATION_TIMEOUT_MS)
        iphoneReenumerationJob = scope.launch {
            delay(com.openautolink.app.carplay.UsbCarPlayPermission.REENUMERATION_TIMEOUT_MS)
            synchronized(this@UsbConnectionManager) {
                if (iphoneTransitionToken === token && dispatchOwnership.isCurrent(token)) retireIphone(token)
            }
        }
        return true
    }

    @Synchronized private fun acceptIphoneReplacement(token: UsbDispatchOwnership.Token, replacement: UsbDevice): Boolean {
        val old = selectedIphone ?: return false
        if (!isRunning || iphoneTransitionToken !== token || System.nanoTime() >= iphoneTransitionDeadline ||
            !token.reenumerating || token.retiring || replacement.deviceName == old.deviceName ||
            replacement.deviceName.substringBeforeLast('/') != old.deviceName.substringBeforeLast('/') ||
            replacement.deviceName in detachedDeviceNames || !dispatchOwnership.isCurrent(token)) return false
        if (!dispatchOwnership.adoptReenumeration(token, replacement.deviceName, replacement.vendorId)) return false
        // Keep rejected unverified enumerations fenced on runtime failure; explicit selection/replug retries.
        rejectedDeviceNames.add(old.deviceName)
        selectedIphone = replacement
        iphoneTransitionToken = null
        iphoneReenumerationJob?.cancel()
        iphoneReenumerationJob = null
        return true
    }
    private val transportOwnership = UsbTransportOwnership()
    private val transportStateLock = Any()
    private val detachedDeviceNames =
        java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val retirementPending = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Guards the connect path against being entered twice for the same
     * accessory. Two independent callers race here on every USB session:
     *
     *  1. the [ACTION_USB_PERMISSION] broadcast → [onPermissionGranted], and
     *  2. the [performAoaSwitch] re-enumeration poll loop, which rescans for
     *     the switched accessory and calls [requestPermissionOrConnect] again.
     *
     * Before this guard both paths reached [connectToAccessory] and each one
     * invoked [onTransportReady], so `AasdkSession` ran `nativeCreateSession`
     * + `nativeStartSession` a second time on a live session. The duplicate
     * start forced a `nativeStopSession` from the wrong thread and tripped the
     * `JniSession::stop()` io_service join — an ANR (observed 2026-07-27 on
     * car app 0.1.371: two tombstoned dumps in three minutes, every session
     * showing a duplicate "USB endpoints opened" / "Starting native aasdk
     * session (USB)" pair ~5ms apart).
     *
     * Claiming is atomic via [connectClaimed] so whichever path arrives first
     * wins and the loser becomes a no-op.
     */
    private val connectClaimed = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * deviceName of the device we currently have a permission dialog in flight for,
     * or null. Prevents a second dialog being stacked for the same device when both
     * the existing-device scan and the ATTACHED broadcast fire for one plug-in.
     * Cleared as soon as the request resolves (granted, denied, or device detached).
     */
    private val pendingPermissionDevice =
        java.util.concurrent.atomic.AtomicReference<String?>(null)

    @Volatile
    private var currentConnection: UsbDeviceConnection? = null

    private var scanJob: Job? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (!isRunning) return
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    @Suppress("DEPRECATION")
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (device != null && isRunning) {
                        OalLog.i(TAG, "USB device attached: ${device.deviceName} " +
                                "VID=${String.format("%04X", device.vendorId)} " +
                                "PID=${String.format("%04X", device.productId)}")
                        handleDeviceAttached(device)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    @Suppress("DEPRECATION")
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (device == null) {
                        OalLog.w(TAG, "USB detach had no device identity — leaving active transport alone")
                    } else {
                        OalLog.i(TAG, "USB device detached: ${device.deviceName}")
                        handleDeviceDetached(device)
                    }
                }
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    @Suppress("DEPRECATION")
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    // The dialog has resolved either way — release the guard so a
                    // later, legitimate request (e.g. user retries, or the phone is
                    // re-plugged) is not suppressed.
                    if (device == null || device.vendorId == 0x05ac) return
                    val token = dispatchOwnership.permission(device.deviceName,
                        intent.getLongExtra(EXTRA_PERMISSION_GENERATION, -1L)) ?: return
                    pendingPermissionDevice.compareAndSet(device.deviceName, null)
                    if (granted && usbManager.hasPermission(device)) {
                        OalLog.i(TAG, "USB permission granted for: ${device.deviceName}")
                        onPermissionGranted(device)
                    } else {
                        OalLog.w(TAG, "USB permission denied")
                        _status.value = "USB permission denied"
                        _connectionState.value = UsbConnectionState.IDLE
                        rejectedDeviceNames.add(device.deviceName)
                        releaseSelection(token)
                    }
                }
            }
        }
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        activeInstance = this
        _connectionState.value = UsbConnectionState.IDLE
        _status.value = "Waiting for USB device..."

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

        // Enumerate currently-attached devices and either auto-connect to an
        // already-switched accessory or publish the candidate list for the UI.
        scanExistingDevices()
    }

    @Synchronized fun stop() {
        if (!isRunning) return
        isRunning = false
        pendingCandidateGeneration?.let { consent.stay(it); onCandidateDetached(it) }
        pendingCandidateGeneration = null
        if (activeInstance === this) activeInstance = null
        scanJob?.cancel()
        scanJob = null
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (_: IllegalArgumentException) { }
        iphoneReenumerationJob?.cancel()
        iphoneReenumerationJob = null
        val selected = dispatchOwnership.current()
        if (selected?.route == UsbDispatchOwnership.Route.APPLE) {
            retireIphone(selected)
        } else if (currentAasdkPipe != null) {
            selected?.let { dispatchOwnership.retire() }
            val detachedPipe = currentAasdkPipe!!
            retirementPending.set(true)
            closePipe()
            runCatching { onTransportDetached(detachedPipe) {
                if (selected != null && releaseSelection(selected)) retirementPending.set(false)
            } }
        } else if (selected?.retiring == true) {
            // A previous detach already handed retirement to the native owner.
            // stop/start must not discard that outstanding acknowledgement.
            closePipe()
        } else {
            closePipe()
            selected?.let { releaseSelection(it) }
        }
        pendingPermissionDevice.set(null)
        _availableDevices.value = emptyList()
        _connectionState.value = UsbConnectionState.IDLE
        _status.value = "Stopped"
    }

    /**
     * Capture during onIphoneSelected, before launching runtime work. Call this
     * closure only after full CarPlay retirement (also permission/start failure).
     * Stale closures cannot release a replacement phone or a restarted manager.
     */
    fun iphoneRetirementAcknowledgement(device: UsbDevice): () -> Unit {
        val token = dispatchOwnership.current()?.takeIf {
            it.route == UsbDispatchOwnership.Route.APPLE && it.deviceName == device.deviceName
        } ?: return {}
        return { releaseIphone(token) }
    }

    @Synchronized private fun releaseIphone(token: UsbDispatchOwnership.Token) {
        if (!dispatchOwnership.isCurrent(token)) return
        // No automatic reprompt/restart for the phone whose runtime just failed.
        rejectedDeviceNames.add(token.deviceName)
        if (iphoneTransitionToken === token) {
            // Polling can observe a replacement before its ATTACHED broadcast. A denied/expired
            // transition must not immediately select that enumeration and prompt again.
            usbManager.deviceList.values.filter { it.vendorId == 0x05ac &&
                it.deviceName.substringBeforeLast('/') == token.deviceName.substringBeforeLast('/') }
                .forEach { rejectedDeviceNames.add(it.deviceName) }
        }
        if (!releaseSelection(token)) return
        iphoneReenumerationJob?.cancel()
        iphoneReenumerationJob = null
        selectedIphone = null
        iphoneTransitionToken = null
        if (isRunning) {
            _connectionState.value = UsbConnectionState.IDLE
            _status.value = "Waiting for USB device..."
            scope.launch(Dispatchers.IO) { scanExistingDevices() }
        }
    }

    @Synchronized private fun retireIphone(token: UsbDispatchOwnership.Token) {
        if (!dispatchOwnership.isCurrent(token) || dispatchOwnership.retire() !== token) return
        iphoneReenumerationJob?.cancel()
        iphoneReenumerationJob = null
        val device = selectedIphone ?: return
        // Failure leaves admission blocked: never replace a still-live runtime.
        runCatching { onIphoneDetached(device) { releaseIphone(token) } }
            .onFailure { OalLog.e(TAG, "CarPlay USB retirement failed; replacement remains blocked") }
    }

    private fun scanExistingDevices() {
        if (!isRunning || dispatchOwnership.current() != null || retirementPending.get()) return
        val devices = usbManager.deviceList
        OalLog.i(TAG, "Scanning ${devices.size} existing USB devices")

        // Auto-handle a device already in accessory mode — this is the return
        // path after we triggered an AOA switch on a previous selection, so
        // there's no point re-prompting the user. Permission is still requested
        // (it persists across the re-enumeration on most cars but not all).
        for ((_, device) in devices) {
            if (UsbConstants.isAccessoryDevice(device.vendorId, device.productId)) {
                OalLog.i(TAG, "Found device already in accessory mode: ${device.deviceName}")
                detachedDeviceNames.remove(device.deviceName)
                _connectionState.value = UsbConnectionState.ACCESSORY_DETECTED
                requestPermissionOrConnect(device)
                return
            }
        }

        publishCandidates()
        autoSelectIfUnambiguous()
    }

    /**
     * OEM head units never show a device picker: you plug the phone in and
     * projection starts. Match that behaviour whenever the choice is unambiguous.
     *
     * In USB mode there is only ever one phone attached to the head unit, so:
     *  - exactly one candidate  -> take it, whatever it looks like. This is the
     *    important case: a phone in "charging only" mode exposes no data
     *    interfaces and fails [looksLikePhone], but is still perfectly AOA-capable
     *    (the handshake is endpoint-0 only), so requiring a phone-like signature
     *    here would strand the user's actual phone behind a needless tap.
     *  - several candidates, exactly one phone-like -> take that one; the rest are
     *    head-unit internals that slipped past [isHubOrSystemDevice].
     *  - anything genuinely ambiguous -> fall back to the picker.
     *
     * The picker UI is retained as the fallback and as an escape hatch; it simply
     * should not be needed in the normal case.
     */
    private fun autoSelectIfUnambiguous() {
        if (!isRunning) return
        if (currentPipe != null || connectClaimed.get() || dispatchOwnership.current() != null) return
        if (_connectionState.value == UsbConnectionState.PERMISSION_REQUESTED) return

        val candidates = _availableDevices.value.filterNot { it.deviceName in rejectedDeviceNames }
        val phones = candidates.filter { it.isLikelyPhone }
        val target = when {
            candidates.size == 1 -> candidates.first()
            phones.size == 1 -> phones.first()
            else -> null
        } ?: run {
            if (candidates.size > 1) {
                OalLog.i(TAG, "Not auto-selecting: ${candidates.size} candidates, " +
                        "${phones.size} phone-like — falling back to picker")
            }
            return
        }

        val device = usbManager.deviceList[target.deviceName] ?: return
        OalLog.i(TAG, "Auto-selecting USB device: ${target.friendlyName} " +
                "(${"%04X:%04X".format(target.vendorId, target.productId)}, " +
                "phoneLike=${target.isLikelyPhone}) — no picker needed")
        _status.value = "Connecting to ${target.friendlyName}..."
        requestPermissionOrConnect(device)
    }

    @Synchronized private fun handleDeviceAttached(device: UsbDevice) {
        if (!isRunning) return
        detachedDeviceNames.remove(device.deviceName)
        val selected = dispatchOwnership.current()
        if (selected?.route == UsbDispatchOwnership.Route.APPLE) {
            if (device.vendorId == 0x05ac) {
                // Attach is observation, not ownership proof. Runtime alone validates serial.
                if (device.deviceName != selected.deviceName) {
                    if (iphoneTransitionToken != null) rejectedDeviceNames.add(device.deviceName)
                    else if (onCandidate != null) offerCandidate(device)
                    else rejectedDeviceNames.add(device.deviceName)
                }
                return
            }
            // A new Android phone must not wait behind a vanished Apple phone.
            if (onCandidate == null && selected.reenumerating && !isHubOrSystemDevice(device)) retireIphone(selected)
            if (onCandidate != null && !isHubOrSystemDevice(device)) offerCandidate(device)
            return
        }
        if (selected != null && device.deviceName != selected.deviceName &&
            !(selected.reenumerating && UsbConstants.isAccessoryDevice(device.vendorId, device.productId))) {
            if (onCandidate != null && !isHubOrSystemDevice(device)) offerCandidate(device)
            return
        }
        if (selected?.reenumerating == true && UsbConstants.isAccessoryDevice(device.vendorId, device.productId)) {
            if (!dispatchOwnership.adoptReenumeration(selected, device.deviceName, device.vendorId)) return
        }
        if (UsbConstants.isAccessoryDevice(device.vendorId, device.productId)) {
            // Accessory came back after our AOA switch — connect without prompting again.
            _connectionState.value = UsbConnectionState.ACCESSORY_DETECTED
            _status.value = "Accessory device detected"
            requestPermissionOrConnect(device)
        } else {
            // Newly-attached non-accessory device: refresh the picker list, then
            // auto-connect if it is unambiguously the phone. This is the plug-in
            // path an OEM head unit handles with no user interaction at all.
            publishCandidates()
            autoSelectIfUnambiguous()
        }
    }

    @Synchronized private fun handleDeviceDetached(device: UsbDevice) {
        // Record the physical event before taking the publication lock. A connect
        // that has opened endpoints but not yet published ownership must see this
        // tombstone and refuse to deliver the now-detached pipe.
        detachedDeviceNames.add(device.deviceName)
        val pending = pendingCandidateGeneration
        if (consent.detach(device.deviceName)) {
            pendingCandidateGeneration = null
            if (pending != null) onCandidateDetached(pending)
        }
        rejectedDeviceNames.remove(device.deviceName)
        val selected = dispatchOwnership.current()
        if (selected?.route == UsbDispatchOwnership.Route.APPLE) {
            if (selected.deviceName != device.deviceName || selected.retiring) return
            if (!dispatchOwnership.beginReenumeration(selected)) return
            if (iphoneTransitionToken === selected) {
                // Explicitly armed by runtime before NCM switch: existing 15s deadline wins.
                return
            }
            // Ordinary unplug (not a runtime-authorized transition) retains the legacy 3s grace.
            iphoneReenumerationJob = scope.launch {
                delay(IPHONE_REENUMERATION_GRACE_MS)
                if (dispatchOwnership.isCurrent(selected) && selected.reenumerating) retireIphone(selected)
            }
            return
        }
        if (selected?.deviceName == device.deviceName && !selected.reenumerating) {
            dispatchOwnership.detach(device.deviceName)
        }
        val retiredState = synchronized(transportStateLock) {
            val retired = transportOwnership.detach(device.deviceName)
                ?: return@synchronized null
            retirementPending.set(true)
            val detachedPipe = currentAasdkPipe
            currentAasdkPipe = null
            retired to detachedPipe
        }
        if (retiredState == null) {
            OalLog.i(TAG, "Ignoring detach for ${device.deviceName} — it does not own " +
                    "the active phone transport; any in-flight publication is tombstoned")
            pendingPermissionDevice.compareAndSet(device.deviceName, null)
            if (selected?.deviceName == device.deviceName && selected.retiring) {
                releaseSelection(selected)
                _connectionState.value = UsbConnectionState.IDLE
            }
            publishCandidates()
            return
        }
        val (retired, detachedPipe) = retiredState

        closePipe()
        // closePipe clears ordinary ownership; retirement remains a separate
        // barrier until the native owner positively acknowledges its stop.
        pendingPermissionDevice.compareAndSet(device.deviceName, null)
        _connectionState.value = UsbConnectionState.IDLE
        _status.value = "USB device disconnected"
        if (detachedPipe != null) {
            OalLog.i(TAG, "Active USB transport detached: generation=${retired.generation} — " +
                    "retiring its native attempt before accepting re-enumeration")
            val acknowledgeRetired = {
                if (selected != null && releaseSelection(selected) &&
                    retirementPending.compareAndSet(true, false)) {
                    OalLog.i(TAG, "USB native retirement acknowledged — rescanning accessories")
                    if (isRunning) scope.launch(Dispatchers.IO) { scanExistingDevices() }
                }
                Unit
            }
            runCatching { onTransportDetached(detachedPipe, acknowledgeRetired) }
                .onFailure {
                    OalLog.e(TAG, "USB native retirement callback failed: ${it.message}; " +
                            "replacement remains blocked")
                }
        } else {
            selected?.let { releaseSelection(it) }
            retirementPending.set(false)
        }
        publishCandidates()
    }

    /**
     * Re-enumerates attached USB devices and publishes the user-visible
     * candidate list. Hubs and mass-storage devices are filtered out.
     */
    private fun publishCandidates() {
        // Deliberately NOT filtered by looksLikePhone(): a phone sitting in
        // "charging only" USB mode exposes no data interfaces, so a strict phone
        // filter would hide it and make it unreachable. Hiding the user's actual
        // phone is far worse than showing one extra entry. Instead we hide only
        // devices that are POSITIVELY not phones (hubs, storage, HID, audio...)
        // and sort the likely phones to the top.
        val list = usbManager.deviceList.values
            .filterNot { isHubOrSystemDevice(it) }
            .map { d ->
                UsbDeviceCandidate(
                    deviceName = d.deviceName,
                    vendorId = d.vendorId,
                    productId = d.productId,
                    friendlyName = friendlyNameFor(d),
                    isAccessoryMode = UsbConstants.isAccessoryDevice(d.vendorId, d.productId),
                    isLikelyPhone = looksLikePhone(d),
                )
            }
            .sortedWith(compareByDescending<UsbDeviceCandidate> { it.isAccessoryMode }
                .thenByDescending { it.isLikelyPhone }
                .thenBy { it.friendlyName.lowercase() })
        _availableDevices.value = list
        if (_connectionState.value == UsbConnectionState.IDLE && list.isNotEmpty()) {
            _connectionState.value = UsbConnectionState.AWAITING_USER_SELECTION
            _status.value = "Select a USB device to connect"
        } else if (list.isEmpty() && _connectionState.value == UsbConnectionState.AWAITING_USER_SELECTION) {
            _connectionState.value = UsbConnectionState.IDLE
            _status.value = "Waiting for USB device..."
        }
    }

    private fun friendlyNameFor(device: UsbDevice): String {
        val mfg = try { device.manufacturerName } catch (_: SecurityException) { null }
        val prod = try { device.productName } catch (_: SecurityException) { null }
        val composed = listOfNotNull(mfg?.trim(), prod?.trim())
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        if (composed.isNotEmpty()) return composed
        return "USB Device %04X:%04X".format(device.vendorId, device.productId)
    }

    /**
     * Called when the user taps a device in the picker. Looks the device up
     * by its stable [UsbDevice.deviceName] and starts the permission/AOA flow.
     */
    private fun onUserSelectedDevice(deviceName: String) {
        if (!isRunning) return
        val device = usbManager.deviceList[deviceName]
        if (device == null) {
            OalLog.w(TAG, "User selected device $deviceName but it is no longer attached")
            publishCandidates()
            return
        }
        OalLog.i(TAG, "User selected USB device: ${device.deviceName} " +
                "VID=${String.format("%04X", device.vendorId)} " +
                "PID=${String.format("%04X", device.productId)}")
        rejectedDeviceNames.remove(deviceName) // Explicit picker tap is a deliberate retry.
        consent.retry(deviceName)
        _connectionState.value = UsbConnectionState.DEVICE_DETECTED
        _status.value = "Requesting permission for ${friendlyNameFor(device)}..."
        requestPermissionOrConnect(device)
    }

    private fun requestPermissionOrConnect(device: UsbDevice) = requestPermissionOrConnect(device, false)

    private fun offerCandidate(device: UsbDevice) {
        if (device.deviceName in rejectedDeviceNames) return
        val callback = onCandidate ?: return
        val candidate = consent.offer(device.deviceName, device.vendorId) ?: return
        pendingCandidateGeneration = candidate.generation
        callback(candidate.generation, device)
    }

    @Synchronized private fun requestPermissionOrConnect(device: UsbDevice, consentGranted: Boolean) {
        if (!isRunning || device.deviceName in rejectedDeviceNames) return
        // Passive observation never requests permission or changes the current projection owner.
        // Existing dispatch ownership covers validated AOA / Apple re-enumeration only.
        val owner = dispatchOwnership.current()
        if (!consentGranted && onCandidate != null &&
            (owner == null || owner.deviceName != device.deviceName || owner.retiring)) {
            offerCandidate(device)
            return
        }
        // Apple devices are never AOA candidates, even when CarPlay is disabled.
        if (device.vendorId == 0x05ac) {
            if (currentPipe != null || connectClaimed.get() || retirementPending.get()) return
            val token = dispatchOwnership.select(device.deviceName, device.vendorId) ?: return
            selectedIphone = device
            val accepted = runCatching { onIphoneSelected(device) }.getOrDefault(false)
            if (!accepted) {
                rejectedDeviceNames.add(device.deviceName)
                releaseSelection(token)
                selectedIphone = null
                _status.value = "CarPlay unavailable for this USB device"
            } else if (dispatchOwnership.isCurrent(token)) {
                _connectionState.value = UsbConnectionState.CONNECTING
                _status.value = "Connecting via CarPlay USB..."
            }
            return
        }
        val existing = dispatchOwnership.current()
        val token = if (existing == null) dispatchOwnership.select(device.deviceName, device.vendorId)
            else existing.takeIf { it.route == UsbDispatchOwnership.Route.ANDROID &&
                it.deviceName == device.deviceName && !it.retiring && !it.reenumerating }
        if (token == null) return
        if (retirementPending.get()) {
            OalLog.i(TAG, "Deferring ${device.deviceName} until the detached native attempt retires")
            return
        }
        // Idempotency gate — see [connectClaimed]. A session is already
        // established or being established; a second caller must not start
        // another native session on top of it.
        if (currentPipe != null || connectClaimed.get()) {
            OalLog.i(TAG, "Ignoring duplicate connect for ${device.deviceName} — " +
                    "a USB session is already claimed (state=${_connectionState.value})")
            return
        }
        if (usbManager.hasPermission(device)) {
            onPermissionGranted(device)
            return
        }
        // DOUBLE-PROMPT GUARD (GM AAOS).
        //
        // One physical plug-in can reach this method twice for the SAME device:
        // scanExistingDevices() spots the accessory, and the ACTION_USB_DEVICE_ATTACHED
        // broadcast fires for the same re-enumeration. Separately, the manifest
        // intent-filter (usb_device_filter.xml) makes the OS raise its own dialog —
        // the one carrying the "use by default" checkbox.
        //
        // On GM head units that checkbox never persists (known GM AAOS bug), so every
        // redundant request is a dialog the user must physically dismiss. Collapse
        // repeat requests for the same device to a single in-flight prompt.
        val key = device.deviceName
        if (!dispatchOwnership.beginPermission(token)) return
        if (!pendingPermissionDevice.compareAndSet(null, key)) {
            OalLog.i(TAG, "Permission prompt already in flight for " +
                    "${pendingPermissionDevice.get()} — not raising a second dialog for $key")
            return
        }
        _connectionState.value = UsbConnectionState.PERMISSION_REQUESTED
        _status.value = "Requesting USB permission..."
        val request = runCatching {
            val permissionIntent = PendingIntent.getBroadcast(
                context, token.generation.toInt(),
                Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
                    .setData(android.net.Uri.parse("oal-usb:permission/${token.generation}"))
                    .putExtra(EXTRA_PERMISSION_GENERATION, token.generation),
                PendingIntent.FLAG_MUTABLE,
            )
            usbManager.requestPermission(device, permissionIntent)
        }
        request.onFailure {
            pendingPermissionDevice.compareAndSet(key, null)
            rejectedDeviceNames.add(key)
            releaseSelection(token)
            _connectionState.value = UsbConnectionState.IDLE
            _status.value = "USB permission request failed"
            OalLog.e(TAG, "USB permission request failed")
        }
    }

    private fun onPermissionGranted(device: UsbDevice) {
        if (!isRunning || device.vendorId == 0x05ac) return
        val token = dispatchOwnership.current() ?: return
        if (token.deviceName != device.deviceName || token.retiring) return
        if (UsbConstants.isAccessoryDevice(device.vendorId, device.productId)) {
            // Already in accessory mode — open endpoints
            scanJob = scope.launch(Dispatchers.IO) {
                if (dispatchOwnership.isCurrent(token) && !token.retiring) connectToAccessory(device)
            }
        } else {
            // Need AOA switch first
            _connectionState.value = UsbConnectionState.SWITCHING_TO_ACCESSORY
            _status.value = "Switching to accessory mode..."
            scanJob = scope.launch(Dispatchers.IO) {
                if (dispatchOwnership.isCurrent(token) && !token.retiring) performAoaSwitch(device)
            }
        }
    }

    private suspend fun performAoaSwitch(device: UsbDevice) {
        if (device.vendorId == 0x05ac) return
        val token = dispatchOwnership.current() ?: return
        if (!dispatchOwnership.beginReenumeration(token)) return
        val success = runCatching { UsbAccessoryMode.switchToAccessory(usbManager, device) }.getOrDefault(false)
        if (!dispatchOwnership.isCurrent(token)) return
        if (!success) {
            releaseSelection(token)
            OalLog.e(TAG, "AOA switch failed")
            _status.value = "AOA switch failed"
            _connectionState.value = UsbConnectionState.IDLE
            return
        }

        // Wait for the device to re-enumerate as a Google Accessory.
        //
        // Previously this slept AOA_SWITCH_SETTLE_MS (2s) unconditionally before
        // even looking. Measured on a real head unit the accessory appears ~2.0s
        // after "AOA switch initiated", so a blind 2s sleep plus a 500ms poll
        // interval meant we routinely noticed it up to half a second late, on
        // every single connect. Poll fast from the start instead: the settle
        // delay becomes a *budget*, not a floor.
        _status.value = "Waiting for accessory re-enumeration..."

        // Poll for the accessory device. Bail out early if another path (the
        // ACTION_USB_PERMISSION broadcast, which commonly lands first once the
        // accessory re-enumerates) has already claimed the connect slot —
        // otherwise this loop races it into a duplicate native session start.
        var attempts = 0
        while (isRunning && dispatchOwnership.isCurrent(token) && attempts < ACCESSORY_SCAN_MAX_ATTEMPTS) {
            if (currentPipe != null || connectClaimed.get()) {
                OalLog.i(TAG, "AOA re-enumeration poll stopping — connect already claimed")
                return
            }
            val devices = usbManager.deviceList
            for ((_, d) in devices) {
                if (UsbConstants.isAccessoryDevice(d.vendorId, d.productId)) {
                    OalLog.i(TAG, "Accessory device found after AOA switch: ${d.deviceName} " +
                            "(${attempts * ACCESSORY_SCAN_INTERVAL_MS}ms after switch)")
                    detachedDeviceNames.remove(d.deviceName)
                    if (!dispatchOwnership.adoptReenumeration(token, d.deviceName, d.vendorId)) return
                    _connectionState.value = UsbConnectionState.ACCESSORY_DETECTED
                    requestPermissionOrConnect(d)
                    return
                }
            }
            attempts++
            delay(ACCESSORY_SCAN_INTERVAL_MS)
        }
        if (!releaseSelection(token)) return
        OalLog.e(TAG, "Accessory device not found after AOA switch ($attempts attempts)")
        _status.value = "Accessory not found after switch"
        _connectionState.value = UsbConnectionState.IDLE
    }

    private fun connectToAccessory(device: UsbDevice) {
        val selection = dispatchOwnership.current() ?: return
        if (!isRunning || device.vendorId == 0x05ac || selection.deviceName != device.deviceName || selection.retiring) return
        if (retirementPending.get()) {
            OalLog.i(TAG, "connectToAccessory deferred — previous native attempt is retiring")
            return
        }
        // Atomically claim the connect slot. If another path (permission
        // broadcast vs. AOA re-enumeration poll) beat us here, abort — a
        // duplicate onTransportReady() starts a second native aasdk session
        // on a live transport and deadlocks JniSession::stop().
        if (!connectClaimed.compareAndSet(false, true)) {
            OalLog.i(TAG, "connectToAccessory skipped — slot already claimed by another path")
            return
        }
        _connectionState.value = UsbConnectionState.CONNECTING
        _status.value = "Opening USB endpoints..."

        val connection = runCatching { usbManager.openDevice(device) }.getOrNull()
        if (connection == null) {
            OalLog.e(TAG, "Failed to open accessory device")
            _status.value = "Failed to open device"
            _connectionState.value = UsbConnectionState.IDLE
            connectClaimed.set(false)
            releaseSelection(selection)
            rejectedDeviceNames.add(device.deviceName)
            return
        }

        // Find the bulk interface and endpoints
        val (iface, epIn, epOut) = findBulkEndpoints(device)
        if (iface == null || epIn == null || epOut == null) {
            OalLog.e(TAG, "No bulk endpoints found on accessory device")
            connection.close()
            _status.value = "No bulk endpoints"
            _connectionState.value = UsbConnectionState.IDLE
            connectClaimed.set(false)
            releaseSelection(selection)
            rejectedDeviceNames.add(device.deviceName)
            return
        }

        if (!connection.claimInterface(iface, true)) {
            OalLog.e(TAG, "Failed to claim USB interface")
            connection.close()
            _status.value = "Failed to claim interface"
            _connectionState.value = UsbConnectionState.IDLE
            connectClaimed.set(false)
            releaseSelection(selection)
            rejectedDeviceNames.add(device.deviceName)
            return
        }

        OalLog.i(TAG, "USB endpoints opened — IN: ${epIn.address} OUT: ${epOut.address} " +
                "maxPacket: ${epIn.maxPacketSize}/${epOut.maxPacketSize}")

        val pipe = UsbTransportPipe(connection, epIn, epOut)
        val transportPipe = AasdkTransportPipe(pipe.toInputStream(), pipe.toOutputStream())
        val ownership = synchronized(transportStateLock) {
            if (!isRunning || !dispatchOwnership.isCurrent(selection) || selection.retiring || retirementPending.get() ||
                detachedDeviceNames.contains(device.deviceName)
            ) {
                null
            } else {
                currentConnection = connection
                currentPipe = pipe
                val claimed = transportOwnership.claim(device.deviceName)
                currentAasdkPipe = transportPipe
                claimed
            }
        }
        if (ownership == null) {
            OalLog.i(TAG, "USB endpoint publication cancelled for ${device.deviceName} — " +
                    "device detached or previous native attempt is retiring")
            pipe.close()
            connectClaimed.set(false)
            _connectionState.value = UsbConnectionState.IDLE
            _status.value = "Waiting for USB re-enumeration..."
            return
        }

        _connectionState.value = UsbConnectionState.CONNECTED
        _status.value = "Connected via USB"
        OalLog.i(TAG, "USB transport owner installed: generation=${ownership.generation} " +
                "device=${ownership.deviceName}")

        onTransportReady(transportPipe)
    }

    private data class BulkEndpoints(
        val iface: UsbInterface?,
        val endpointIn: UsbEndpoint?,
        val endpointOut: UsbEndpoint?,
    )

    private fun findBulkEndpoints(device: UsbDevice): BulkEndpoints {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            for (j in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(j)
                if (ep.type == android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == android.hardware.usb.UsbConstants.USB_DIR_IN) {
                        epIn = ep
                    } else {
                        epOut = ep
                    }
                }
            }
            if (epIn != null && epOut != null) {
                return BulkEndpoints(iface, epIn, epOut)
            }
        }
        return BulkEndpoints(null, null, null)
    }

    private fun closePipe() {
        val pipe = synchronized(transportStateLock) {
            val current = currentPipe
            currentPipe = null
            currentAasdkPipe = null
            currentConnection = null
            transportOwnership.clear()
            // Release the connect slot so a genuine re-attach (cable replug,
            // detach broadcast, session restart) can establish a new session.
            connectClaimed.set(false)
            current
        }
        pipe?.close()
    }

    /**
     * True when a device can never be an Android phone we could drive into AOA.
     *
     * Checking only [UsbDevice.deviceClass] is not enough: phones, hubs, card
     * readers and head-unit internals alike report class 0 (per-interface,
     * "see interface descriptors"), so the old `deviceClass == 8 || == 9` test
     * matched almost nothing. Observed on a real head unit: the picker listed
     * **9** devices and the user selected `0424:4911` (a Microchip USB2517 hub)
     * before finding the phone.
     *
     * A device is excluded when the device descriptor is a known non-phone class,
     * or when every interface it exposes is a known non-phone class.
     *
     * NOTE: a phone in "charging only" USB mode is deliberately NOT excluded. The
     * AOA v2 handshake ([UsbAccessoryMode]) is entirely endpoint-0 control
     * transfers — GET_PROTOCOL / SEND_STRING / START — and needs no interface at
     * all; the accessory interface is *created by* the switch. Charge-only is the
     * default USB state on modern Android, and is exactly the state an OEM head
     * unit drives into accessory mode every day. Excluding it would hide the one
     * device the user needs.
     */
    private fun isHubOrSystemDevice(device: UsbDevice): Boolean {
        if (UsbDevicePolicy.isKnownNonPhoneDevice(device.vendorId, device.productId)) return true
        if (device.deviceClass in EXCLUDED_USB_CLASSES) return true
        val ifaceCount = device.interfaceCount
        // No interfaces at all: cannot rule it out — a charge-only phone looks
        // like this and is still AOA-capable over endpoint 0.
        if (ifaceCount == 0) return false
        for (i in 0 until ifaceCount) {
            if (device.getInterface(i).interfaceClass !in EXCLUDED_USB_CLASSES) return false
        }
        return true
    }

    /**
     * True when a device is plausibly an Android phone we can drive into AOA.
     *
     * A phone in normal (non-accessory) mode exposes MTP/PTP, ADB, or a vendor
     * -specific interface. We deliberately keep this permissive — an unknown
     * phone that fails the heuristic would be unreachable, which is worse than
     * showing one extra entry — but it is enough to drop hubs, storage, HID and
     * audio peripherals from the picker.
     */
    private fun looksLikePhone(device: UsbDevice): Boolean {
        if (device.vendorId == 0x05ac) return true
        if (UsbConstants.isAccessoryDevice(device.vendorId, device.productId)) return true
        for (i in 0 until device.interfaceCount) {
            val cls = device.getInterface(i).interfaceClass
            // Vendor-specific (0xFF) covers ADB, MTP-over-vendor and most OEM modes.
            if (cls == AndroidUsbConstants.USB_CLASS_VENDOR_SPEC) return true
            // Still image / PTP — the classic phone-as-camera interface.
            if (cls == AndroidUsbConstants.USB_CLASS_STILL_IMAGE) return true
        }
        return false
    }
}
