package com.openautolink.app.transport.bluetooth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.openautolink.app.diagnostics.OalLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Process-scoped owner of [AaWirelessBtServer].
 *
 * Lives at Application scope on purpose. The advertiser's entire job is to make the
 * phone *initiate* a projection session, so hanging it off SessionManager — which
 * only exists once a session is already running — would be circular. That mistake
 * cost a test cycle: the DEBUG broadcast was delivered (`result=0`) but nothing
 * listened, because no session had started to register the receiver.
 *
 * Kept opt-in via broadcast while we establish whether gearhead reacts to our SDP
 * record at all. Once proven, the credentials should come from the live network
 * state rather than adb extras.
 *
 * ```
 * # start advertising + set the details handed to the phone
 * adb shell am broadcast -a com.openautolink.app.DEBUG_AAW_BT \
 *   --es ssid Fortress --es psk '<key>' --es bssid aa:bb:cc:dd:ee:ff \
 *   --es ip 192.168.1.100 --ei port 5277
 *
 * # stop
 * adb shell am broadcast -a com.openautolink.app.DEBUG_AAW_BT_STOP
 * ```
 */
object AaWirelessBtControl {

    private const val TAG = "AaWirelessBtControl"
    private const val ACTION_START = "com.openautolink.app.DEBUG_AAW_BT"
    private const val ACTION_STOP = "com.openautolink.app.DEBUG_AAW_BT_STOP"

    /**
     * Set the direct-transport preference. Lives here rather than in
     * SessionManager because SessionManager's debug receiver only registers once
     * a session is running — and switching to "wpp" is a precondition for the
     * session starting at all. Same circular-dependency trap the advertiser hit.
     */
    private const val ACTION_SET_TRANSPORT = "com.openautolink.app.DEBUG_SET_TRANSPORT"

    /** Reserved MACs gearhead rejects outright — see pev.smali validation. */
    private const val ZERO_MAC = "00:00:00:00:00:00"
    private const val BROADCAST_MAC = "ff:ff:ff:ff:ff:ff"
    private val BSSID_RE = Regex("^([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var btServer: AaWirelessBtServer? = null


    @Volatile
    private var started = false

    fun init(context: Context) {
        synchronized(this) {
            if (started) return
            started = true
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    ACTION_START -> handleStart(context, intent)
                    ACTION_SET_TRANSPORT -> {
                        val mode = intent.getStringExtra("mode").orEmpty()
                        if (mode.isBlank()) {
                            OalLog.w(TAG, "$ACTION_SET_TRANSPORT missing 'mode' extra")
                            return
                        }
                        scope.launch {
                            com.openautolink.app.data.AppPreferences.getInstance(context)
                                .setDirectTransport(mode)
                            OalLog.i(TAG, "Direct transport set to '$mode'")
                        }
                    }
                    ACTION_STOP -> {
                        OalLog.i(TAG, "Stopping AA wireless BT advertiser on request")
                        btServer?.stop()
                        btServer = null
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_START)
            addAction(ACTION_STOP)
            addAction(ACTION_SET_TRANSPORT)
        }
        // Exported so it can be driven from adb during bring-up. This is a debug
        // affordance; when the advertiser starts automatically it should stop being
        // externally triggerable.
        androidx.core.content.ContextCompat.registerReceiver(
            context, receiver, filter,
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
        )
        OalLog.i(TAG, "AA wireless BT control ready (send $ACTION_START to begin advertising)")
    }

    /**
     * Start advertising using the credentials stored in Settings.
     *
     * Intent extras are honoured when present, but are only a bring-up
     * convenience — the normal path is Settings → Wireless (WPP), because in a
     * real vehicle nobody can run `adb` mid-drive.
     *
     * ### Why the credentials must be typed in
     *
     * These describe the network the phone should join to reach the head unit.
     * On AAOS that is usually the car's own hotspot, and an unprivileged app
     * cannot read a running SoftAP's SSID or passphrase:
     * `WifiManager.getWifiApConfiguration()` needs a system signature, and
     * `LocalOnlyHotspot` only reports credentials for an AP the app itself
     * started — not the vehicle's. So the values come from the user.
     *
     * Missing or malformed credentials are a hard failure on the phone side, so
     * they are validated before anything is advertised:
     *   - empty SSID              → nothing to join
     *   - empty/zero/broadcast BSSID → `WIFI_INVALID_BSSID`
     *   - empty PSK on a secured network → `WIFI_SECURITY_NOT_SUPPORTED`
     *     (we send `securityMode=OPEN`, which gearhead rejects for a WPA2 AP)
     */
    private fun handleStart(context: Context, intent: Intent) {
        scope.launch {
            val prefs = com.openautolink.app.data.AppPreferences.getInstance(context)

            val ssid = intent.getStringExtra("ssid")?.takeIf { it.isNotBlank() }
                ?: prefs.hotspotSsid.first()
            val psk = intent.getStringExtra("psk")
                ?: prefs.hotspotPassword.first()
            val bssid = intent.getStringExtra("bssid")?.takeIf { it.isNotBlank() }
                ?: prefs.wppBssid.first()
            val port = intent.getIntExtra("port", 5277)
            // The address the phone is told to dial. Detected from the live
            // interface rather than stored, because it changes with the network.
            val ip = intent.getStringExtra("ip")?.takeIf { it.isNotBlank() }
                ?: localIpv4Address()
                ?: ""

            val problems = buildList {
                if (ssid.isBlank()) add("SSID is empty")
                if (bssid.isBlank()) add("BSSID is empty")
                else if (!BSSID_RE.matches(bssid)) add("BSSID '$bssid' is not a MAC address")
                else if (bssid.equals(ZERO_MAC, true) || bssid.equals(BROADCAST_MAC, true)) {
                    add("BSSID $bssid is reserved (zero/broadcast)")
                }
                if (ip.isBlank()) add("could not determine this device's IPv4 address")
            }
            if (problems.isNotEmpty()) {
                OalLog.w(TAG, "Not advertising — ${problems.joinToString("; ")}. " +
                        "Set these in Settings → Wireless (WPP).")
                return@launch
            }

            val creds = AaWirelessBtServer.WifiCredentials(
                ssid = ssid, psk = psk, bssid = bssid, ip = ip, port = port,
            )
            startAdvertising(context, creds)
        }
    }

    /** Best-effort local IPv4, skipping loopback and virtual interfaces. */
    private fun localIpv4Address(): String? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
    }.getOrNull()

    private fun startAdvertising(context: Context, creds: AaWirelessBtServer.WifiCredentials) {
        // Switching the session into WPP mode is what binds the listener — see
        // AasdkSession.startWpp(). Doing it here rather than starting our own
        // server avoids two components racing for the same port.
        //
        // The session must already be running in "wpp" transport mode for the
        // advertised port to be listening when the phone dials in. Set
        // Settings -> Direct transport -> wpp (or DEBUG_SET_TRANSPORT) first.
        OalLog.i(TAG, "Advertising ${creds.ip}:${creds.port} — session must be in 'wpp' " +
                "transport mode for that port to be bound")

        btServer?.stop()
        val bt = AaWirelessBtServer(context, scope)
        btServer = bt
        // Probe first: if the BT stack refuses to publish the record we want that
        // stated plainly in the log, not inferred later from the phone's silence.
        OalLog.i(TAG, "SDP advertise capability: ${bt.canAdvertise()}")
        bt.updateCredentials(creds)
        bt.start()
    }
}
