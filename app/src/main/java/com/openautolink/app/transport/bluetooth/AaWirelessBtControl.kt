package com.openautolink.app.transport.bluetooth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.openautolink.app.diagnostics.OalLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    private fun handleStart(context: Context, intent: Intent) {
        val ssid = intent.getStringExtra("ssid").orEmpty()
        if (ssid.isBlank()) {
            OalLog.w(TAG, "$ACTION_START missing required 'ssid' extra")
            return
        }
        val creds = AaWirelessBtServer.WifiCredentials(
            ssid = ssid,
            psk = intent.getStringExtra("psk").orEmpty(),
            bssid = intent.getStringExtra("bssid").orEmpty(),
            ip = intent.getStringExtra("ip").orEmpty(),
            port = intent.getIntExtra("port", 5277),
        )

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
