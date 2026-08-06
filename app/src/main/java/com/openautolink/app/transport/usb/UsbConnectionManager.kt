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
) {
    companion object {
        private const val TAG = "UsbConnectionManager"
        private const val ACTION_USB_PERMISSION = "com.openautolink.app.USB_PERMISSION"
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
                    OalLog.i(TAG, "USB device detached")
                    handleDeviceDetached()
                }
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    @Suppress("DEPRECATION")
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    // The dialog has resolved either way — release the guard so a
                    // later, legitimate request (e.g. user retries, or the phone is
                    // re-plugged) is not suppressed.
                    pendingPermissionDevice.set(null)
                    if (granted && device != null) {
                        OalLog.i(TAG, "USB permission granted for: ${device.deviceName}")
                        onPermissionGranted(device)
                    } else {
                        OalLog.w(TAG, "USB permission denied")
                        _status.value = "USB permission denied"
                        _connectionState.value = UsbConnectionState.IDLE
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

    fun stop() {
        if (!isRunning) return
        isRunning = false
        if (activeInstance === this) activeInstance = null
        scanJob?.cancel()
        scanJob = null
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (_: IllegalArgumentException) { }
        closePipe()
        pendingPermissionDevice.set(null)
        _availableDevices.value = emptyList()
        _connectionState.value = UsbConnectionState.IDLE
        _status.value = "Stopped"
    }

    private fun scanExistingDevices() {
        val devices = usbManager.deviceList
        OalLog.i(TAG, "Scanning ${devices.size} existing USB devices")

        // Auto-handle a device already in accessory mode — this is the return
        // path after we triggered an AOA switch on a previous selection, so
        // there's no point re-prompting the user. Permission is still requested
        // (it persists across the re-enumeration on most cars but not all).
        for ((_, device) in devices) {
            if (UsbConstants.isAccessoryDevice(device.vendorId, device.productId)) {
                OalLog.i(TAG, "Found device already in accessory mode: ${device.deviceName}")
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
        if (currentPipe != null || connectClaimed.get()) return
        if (_connectionState.value == UsbConnectionState.PERMISSION_REQUESTED) return

        val candidates = _availableDevices.value
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

    private fun handleDeviceAttached(device: UsbDevice) {
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

    private fun handleDeviceDetached() {
        closePipe()
        // The device is gone, so any dialog we raised for it is moot. Clearing this
        // matters for the car-sleeps-mid-session case: the head unit cuts USB power,
        // the device detaches, and on wake we must be able to prompt again.
        pendingPermissionDevice.set(null)
        _connectionState.value = UsbConnectionState.IDLE
        _status.value = "USB device disconnected"
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
        _connectionState.value = UsbConnectionState.DEVICE_DETECTED
        _status.value = "Requesting permission for ${friendlyNameFor(device)}..."
        requestPermissionOrConnect(device)
    }

    private fun requestPermissionOrConnect(device: UsbDevice) {
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
        if (!pendingPermissionDevice.compareAndSet(null, key)) {
            OalLog.i(TAG, "Permission prompt already in flight for " +
                    "${pendingPermissionDevice.get()} — not raising a second dialog for $key")
            return
        }
        _connectionState.value = UsbConnectionState.PERMISSION_REQUESTED
        _status.value = "Requesting USB permission..."
        val permissionIntent = PendingIntent.getBroadcast(
            context, 0,
            Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_MUTABLE
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun onPermissionGranted(device: UsbDevice) {
        if (UsbConstants.isAccessoryDevice(device.vendorId, device.productId)) {
            // Already in accessory mode — open endpoints
            scope.launch(Dispatchers.IO) {
                connectToAccessory(device)
            }
        } else {
            // Need AOA switch first
            _connectionState.value = UsbConnectionState.SWITCHING_TO_ACCESSORY
            _status.value = "Switching to accessory mode..."
            scope.launch(Dispatchers.IO) {
                performAoaSwitch(device)
            }
        }
    }

    private suspend fun performAoaSwitch(device: UsbDevice) {
        val success = UsbAccessoryMode.switchToAccessory(usbManager, device)
        if (!success) {
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
        while (isRunning && attempts < ACCESSORY_SCAN_MAX_ATTEMPTS) {
            if (currentPipe != null || connectClaimed.get()) {
                OalLog.i(TAG, "AOA re-enumeration poll stopping — connect already claimed")
                return
            }
            val devices = usbManager.deviceList
            for ((_, d) in devices) {
                if (UsbConstants.isAccessoryDevice(d.vendorId, d.productId)) {
                    OalLog.i(TAG, "Accessory device found after AOA switch: ${d.deviceName} " +
                            "(${attempts * ACCESSORY_SCAN_INTERVAL_MS}ms after switch)")
                    _connectionState.value = UsbConnectionState.ACCESSORY_DETECTED
                    requestPermissionOrConnect(d)
                    return
                }
            }
            attempts++
            delay(ACCESSORY_SCAN_INTERVAL_MS)
        }
        OalLog.e(TAG, "Accessory device not found after AOA switch ($attempts attempts)")
        _status.value = "Accessory not found after switch"
        _connectionState.value = UsbConnectionState.IDLE
    }

    private fun connectToAccessory(device: UsbDevice) {
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

        val connection = usbManager.openDevice(device)
        if (connection == null) {
            OalLog.e(TAG, "Failed to open accessory device")
            _status.value = "Failed to open device"
            _connectionState.value = UsbConnectionState.IDLE
            connectClaimed.set(false)
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
            return
        }

        if (!connection.claimInterface(iface, true)) {
            OalLog.e(TAG, "Failed to claim USB interface")
            connection.close()
            _status.value = "Failed to claim interface"
            _connectionState.value = UsbConnectionState.IDLE
            connectClaimed.set(false)
            return
        }

        OalLog.i(TAG, "USB endpoints opened — IN: ${epIn.address} OUT: ${epOut.address} " +
                "maxPacket: ${epIn.maxPacketSize}/${epOut.maxPacketSize}")

        currentConnection = connection
        val pipe = UsbTransportPipe(connection, epIn, epOut)
        currentPipe = pipe

        val transportPipe = AasdkTransportPipe(pipe.toInputStream(), pipe.toOutputStream())

        _connectionState.value = UsbConnectionState.CONNECTED
        _status.value = "Connected via USB"

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
        currentPipe?.close()
        currentPipe = null
        currentConnection = null
        // Release the connect slot so a genuine re-attach (cable replug,
        // detach broadcast, session restart) can establish a new session.
        connectClaimed.set(false)
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
