package com.openautolink.carplaylab

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.view.SurfaceView
import android.view.SurfaceHolder
import android.view.View
import android.view.Gravity
import android.view.MotionEvent
import com.openautolink.carplaylab.media.ProjectionReceiver
import com.openautolink.carplaylab.settings.BenchSettings
import com.openautolink.carplaylab.settings.BenchSettingsPolicy
import com.openautolink.carplaylab.settings.BenchDisplayProfile
import com.openautolink.carplaylab.settings.BenchSettingsUi
import android.widget.*
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.transport.BluetoothRfcommDuplexStream
import com.shilapi.xcertplay.transport.Iap2IdentificationClient
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2WirelessIdentification
import com.openautolink.carplaylab.pairing.PairingProbeServer
import com.openautolink.carplaylab.pairing.PhoneActivation
import com.shilapi.xcertplay.iap2.message.Iap2ControlMessages
import java.net.NetworkInterface
import java.net.Inet4Address
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Foreground-only experimental auth probe. It never offers media or a fabricated receiver endpoint. */
class BenchActivity : Activity() {
    private lateinit var surfaceView: SurfaceView
    private lateinit var settingsView: View
    private lateinit var ui: BenchSettingsUi
    private lateinit var enableCarPlay: Switch
    private lateinit var displayChoices: RadioGroup
    private lateinit var settingsEscape: Button
    private lateinit var startMediaButton: Button
    private lateinit var advancedStartButton: Button
    private lateinit var probeButton: Button
    private lateinit var pairingButton: Button
    private lateinit var microphoneButton: Button
    private lateinit var reconnectButton: Button
    private lateinit var returnButton: Button
    private var reconnectRequest = 0L
    private var passwordLoaded = false
    private lateinit var projectionStop: Button
    @Volatile private var projection: ProjectionReceiver? = null
    private lateinit var output: TextView
    private lateinit var phone: Spinner
    private lateinit var mac: EditText
    private lateinit var ssid: EditText
    private lateinit var wifiPassword: EditText
    private lateinit var peerIp: EditText
    @Volatile private var pairingServer: PairingProbeServer? = null
    @Volatile private var activation: PhoneActivation? = null
    private var devices = emptyList<BluetoothDevice>()
    private val busy = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor()
    private val deadlines = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var session: Iap2Session? = null
    @Volatile private var activeProof: ProofState? = null
    @Volatile private var destroyed = false
    @Volatile private var captureResumed = false
    override fun onResume() { super.onResume(); captureResumed = true }
    override fun onPause() { captureResumed = false; super.onPause() }
    private val permissions = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ui = BenchSettingsUi(this)
        val saved = BenchSettingsPolicy.read(getPreferences(MODE_PRIVATE).all)
        val root = FrameLayout(this).apply { setBackgroundColor(BenchSettingsUi.BACKGROUND) }
        surfaceView = SurfaceView(this)
        root.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) { stopAttempt() }
        })
        surfaceView.setOnTouchListener { _, event ->
            if (settingsView.visibility != View.VISIBLE)
                projection?.touch(event.actionMasked, event.x, event.y, surfaceView.width, surfaceView.height)
            true
        }
        settingsView = ui.root
        root.addView(settingsView, FrameLayout.LayoutParams(-1, -1))
        settingsEscape = ui.button("Settings") {
            settingsView.visibility = View.VISIBLE
            ui.show(BenchSettingsUi.Section.CONNECTION)
            updateControls()
        }
        root.addView(settingsEscape, FrameLayout.LayoutParams(ui.dp(152), -2, Gravity.TOP or Gravity.END).apply {
            topMargin = ui.dp(16); rightMargin = ui.dp(16)
        })
        projectionStop = ui.button("Stop CarPlay") { stopAttempt(); report("STOP requested") }.apply { visibility = View.GONE }
        root.addView(projectionStop, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            bottomMargin = ui.dp(16); rightMargin = ui.dp(16)
        })
        buildSettings(savedInstanceState, saved)
        setContentView(root)
        report("LAB_READY experimental native settings. Android Auto production defaults are untouched; no automatic discovery/start.")
        try { wifiPassword.setText(SavedWifiPassword(this).load()); passwordLoaded = true }
        catch (_: Exception) { report("Saved Wi-Fi password unavailable; re-enter it") }
        if (permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) refresh()
        updateControls()
    }

    private fun buildSettings(state: Bundle?, saved: BenchSettings) {
        val connection = ui.sections.getValue(BenchSettingsUi.Section.CONNECTION)
        ui.heading(BenchSettingsUi.Section.CONNECTION, "Your connection", "Experimental tablet CarPlay only. Normal Android Auto / WPP settings and startup are unchanged in the production app. This bench does not run both protocols.")
        enableCarPlay = ui.toggle("Enable CarPlay (experimental)", saved.carPlayEnabled)
        ui.add(connection, enableCarPlay)
        ui.add(connection, ui.text("Off by default. Enabling permits explicit bench tests; it does not start discovery or connect automatically."))
        ui.add(connection, ui.button("Permissions / refresh paired phones") {
            if (permissions.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) requestPermissions(permissions, 1)
            else refresh()
        })
        ui.add(connection, ui.text("Pair your iPhone in Android Bluetooth settings, keep it unlocked, then select it here. A name is not proof of protocol or trust."))
        phone = Spinner(this).apply { minimumHeight = ui.dp(64) }
        ui.add(connection, phone)
        ui.add(connection, ui.button("Open Android Bluetooth settings") {
            startActivity(android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
        })
        ui.add(connection, ui.button("Wi-Fi setup & bench controls") { ui.show(BenchSettingsUi.Section.ADVANCED) })
        startMediaButton = ui.button("Start CarPlay video + audio", primary = true) { startProbe(true, true) }
        ui.add(connection, startMediaButton)
        enableCarPlay.setOnCheckedChangeListener { _, checked ->
            // Opt-in is immediate; profile edits still require an explicit save.
            if (!getPreferences(MODE_PRIVATE).edit().putBoolean("carplay_enabled", checked).commit()) {
                report("CarPlay opt-in save failed; no new attempt allowed")
                enableCarPlay.setOnCheckedChangeListener(null)
                enableCarPlay.isChecked = false
                stopAttempt()
            } else if (!checked) {
                stopAttempt()
                report("CarPlay disabled; current bench attempt canceled. No automatic restart.")
            } else report("CarPlay experimental enabled; choose Connect or an Advanced test explicitly")
            updateControls()
        }

        val display = ui.sections.getValue(BenchSettingsUi.Section.DISPLAY)
        ui.heading(BenchSettingsUi.Section.DISPLAY, "Display profile", "CarPlay H.264 · 30 FPS. These are requested next-session profiles, not proof of negotiated decoder output. 1080p is an experimental candidate; 720p is the conservative bench baseline.")
        displayChoices = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        for (profile in BenchDisplayProfile.entries) {
            val choice = RadioButton(this).apply {
                id = if (profile == BenchDisplayProfile.HD_720) 720 else 1080
                text = if (profile == BenchDisplayProfile.HD_720) "720p · 1280 × 720 · 30 FPS" else "1080p · 1920 × 1080 · 30 FPS (experimental)"
                textSize = 20f; setTextColor(BenchSettingsUi.TEXT); minimumHeight = ui.dp(64)
                buttonTintList = android.content.res.ColorStateList.valueOf(BenchSettingsUi.ACCENT)
            }
            displayChoices.addView(choice)
        }
        displayChoices.check(if (saved.display == BenchDisplayProfile.HD_720) 720 else 1080)
        ui.add(display, displayChoices)
        ui.add(display, ui.text("Save for the next connection, or deliberately Save & reconnect. No live format changes, HEVC control or Android Auto DPI/GAL settings are exposed here."))

        val audio = ui.sections.getValue(BenchSettingsUi.Section.AUDIO)
        ui.heading(BenchSettingsUi.Section.AUDIO, "Sound & microphone", "CarPlay audio output does not require microphone consent. Calls and Siri input remain experimental and depend on a supported encoder, phone requests and routing.")
        microphoneButton = ui.button("Allow optional CarPlay microphone (calls / Siri)") {
            if (!BenchSettingsPolicy.canRequestMicrophone(enableCarPlay.isChecked, busy.get())) {
                report("Enable CarPlay and retire the current attempt before optional microphone setup")
            } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
            } else report("Microphone permission already granted; next session still requires a supported encoder")
        }
        ui.add(audio, microphoneButton)
        ui.add(audio, ui.text("Optional. Declining permission leaves video and speaker audio available. No microphone request is made at launch or when CarPlay is off."))
        ui.add(audio, ui.button("Review Android app permissions") {
            startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:$packageName")))
        })

        val advanced = ui.sections.getValue(BenchSettingsUi.Section.ADVANCED)
        ui.heading(BenchSettingsUi.Section.ADVANCED, "Bench setup & diagnostics", "Shared-network IPv4 lab only. These controls are preserved for explicit authentication, pairing and media experiments, not production dual-protocol integration.")
        mac = ui.field("Tablet Bluetooth address (ADB-provisioned lab override)", state?.getString("receiver_bt_address")
            ?: intent.getStringExtra("receiver_bt_address") ?: saved.receiverAddress)
        ssid = ui.field("Current Wi-Fi SSID (not password)", state?.getString("carplay_ssid") ?: saved.ssid)
        peerIp = ui.field("Selected iPhone Wi-Fi IPv4 address", state?.getString("carplay_peer_ip") ?: saved.peerIp)
        wifiPassword = ui.field("Wi-Fi password (saved encrypted; never logged)", secret = true)
        ui.add(advanced, mac); ui.add(advanced, ssid); ui.add(advanced, peerIp); ui.add(advanced, wifiPassword)
        probeButton = ui.button("Test iPhone identification + authentication") { startProbe(false) }
        pairingButton = ui.button("Test endpoint exchange + AirPlay pairing (no media)") { startProbe(true) }
        advancedStartButton = ui.button("Start CarPlay video + audio", primary = true) { startProbe(true, true) }
        ui.add(advanced, probeButton); ui.add(advanced, pairingButton); ui.add(advanced, advancedStartButton)
        ui.add(advanced, ui.button("Stop test") { stopAttempt(); report("STOP requested") })
        output = ui.text("", 16f).apply { setTextIsSelectable(true) }
        ui.add(advanced, output)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val save = ui.button("Save for next connection") { if (saveSettings()) report("Settings saved for next explicit connection; active stream unchanged") }
        reconnectButton = ui.button("Save & reconnect") { saveAndReconnect() }
        actions.addView(save, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = ui.dp(8) })
        actions.addView(reconnectButton, LinearLayout.LayoutParams(0, -2, 1f))
        ui.footer.addView(actions)
        returnButton = ui.button("Return to projection") { if (projection != null) { settingsView.visibility = View.GONE; updateControls() } }
        ui.footer.addView(returnButton)
    }

    private fun saveSettings(): Boolean {
        val saved = BenchSettings(
            carPlayEnabled = enableCarPlay.isChecked,
            display = if (displayChoices.checkedRadioButtonId == 1080) BenchDisplayProfile.FULL_HD_1080 else BenchDisplayProfile.HD_720,
            selectedPhoneAddress = devices.getOrNull(phone.selectedItemPosition - 1)?.address
                ?: BenchSettingsPolicy.read(getPreferences(MODE_PRIVATE).all).selectedPhoneAddress,
            peerIp = peerIp.text.toString().trim(), ssid = ssid.text.toString(), receiverAddress = mac.text.toString().trim(),
        )
        return try {
            check(passwordLoaded || wifiPassword.text.isNotEmpty())
            SavedWifiPassword(this).save(wifiPassword.text.toString())
            passwordLoaded = true
            val editor = getPreferences(MODE_PRIVATE).edit()
            BenchSettingsPolicy.write(saved).forEach { (key, value) ->
                when (value) { is Boolean -> editor.putBoolean(key, value); is Int -> editor.putInt(key, value); is String -> editor.putString(key, value) }
            }
            check(editor.commit())
            true
        } catch (_: Exception) { report("Settings save failed; reconnect not started; no secret logged"); false }
    }

    private fun saveAndReconnect() {
        if (!enableCarPlay.isChecked) { report("Enable CarPlay before reconnecting"); return }
        if (!saveSettings()) return
        val request = ++reconnectRequest
        activeProof?.cancel()
        closeTransport()
        report("Reconnect requested; waiting for this bench attempt's worker to finish")
        // The single worker completes the old finally/busy retirement before this explicit continuation.
        worker.execute {
            runOnUiThread {
                if (!destroyed && request == reconnectRequest && enableCarPlay.isChecked) startProbe(true, true)
            }
        }
    }

    private fun stopAttempt() {
        ++reconnectRequest
        activeProof?.cancel()
        closeTransport()
    }

    private fun updateControls() {
        if (!::enableCarPlay.isInitialized || destroyed) return
        val enabled = enableCarPlay.isChecked && BenchSettingsPolicy.read(getPreferences(MODE_PRIVATE).all).carPlayEnabled
        val canStart = BenchSettingsPolicy.canStart(enabled, busy.get())
        listOf(startMediaButton, advancedStartButton, probeButton, pairingButton).forEach { it.isEnabled = canStart; it.alpha = if (canStart) 1f else 0.5f }
        microphoneButton.isEnabled = BenchSettingsPolicy.canRequestMicrophone(enabled, busy.get())
        reconnectButton.isEnabled = enabled
        returnButton.visibility = if (projection != null) View.VISIBLE else View.GONE
        projectionStop.visibility = if (busy.get() && settingsView.visibility != View.VISIBLE) View.VISIBLE else View.GONE
        settingsEscape.visibility = if (BenchSettingsPolicy.refreshKeepsSettingsEscape(enabled, busy.get())) View.VISIBLE else View.GONE
        ui.status.text = when { !enabled -> "CarPlay disabled · experimental tablet bench"; busy.get() -> "CarPlay bench attempt active · see Advanced for actual stage"; else -> "CarPlay enabled · ready for an explicit bench test" }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (settingsView.visibility != View.VISIBLE) { settingsView.visibility = View.VISIBLE; updateControls() }
        else if (ui.currentSection != BenchSettingsUi.Section.CONNECTION) ui.show(BenchSettingsUi.Section.CONNECTION)
        else if (projection != null) { settingsView.visibility = View.GONE; updateControls() }
        else super.onBackPressed()
    }


    override fun onRequestPermissionsResult(code: Int, names: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, names, results)
        if (code == 2) {
            report(if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                "Optional microphone consent granted; reconnect to refresh device-filtered offers"
                else "Optional microphone declined; video and audio output remain available")
            return
        }
        if (permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) refresh()
        else report("Permission missing; probe not started")
    }

    private fun refresh() {
        try {
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: error("Bluetooth unavailable")
            check(adapter.isEnabled) { "Enable Bluetooth in Android settings" }
            val selected = devices.getOrNull(phone.selectedItemPosition - 1)?.address
                ?: BenchSettingsPolicy.read(getPreferences(MODE_PRIVATE).all).selectedPhoneAddress
            devices = adapter.bondedDevices.sortedBy { it.name ?: "" }
            phone.adapter = ui.darkAdapter(listOf("Select a paired phone") + devices.map { it.name ?: "Paired device" })
            val restored = BenchSettingsPolicy.selectedPhoneIndex(selected, devices.map { it.address })
            phone.setSelection(if (restored >= 0) restored + 1 else 0)
            @Suppress("DEPRECATION")
            val current = applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid
            if (current != null && current != "<unknown ssid>" && ssid.text.isBlank()) ssid.setText(current.removeSurrounding("\""))
            report("BONDED_DEVICES count=${devices.size}. Select the intended iPhone explicitly.")
        } catch (e: SecurityException) { report("Refresh requires Bluetooth/location permission") }
        catch (e: Exception) { report("Refresh failed: ${e.javaClass.simpleName}: ${e.message}") }
    }

    private fun startProbe(withPairing: Boolean, withMedia: Boolean = false) {
        val enabled = enableCarPlay.isChecked && BenchSettingsPolicy.read(getPreferences(MODE_PRIVATE).all).carPlayEnabled
        if (!BenchSettingsPolicy.canStart(enabled, busy.get())) {
            report(if (!enabled) "Enable CarPlay (experimental) before starting a bench test" else "An attempt is still retiring; wait for TEST_ENDED")
            return
        }
        if (permissions.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            report("Grant Bluetooth and location permissions first"); return
        }
        val device = devices.getOrNull(phone.selectedItemPosition - 1) ?: run { report("Pair and select an iPhone first"); return }
        val receiverMac = mac.text.toString().trim()
        val network = ssid.text.toString()
        val password = if (withPairing) wifiPassword.text.toString() else ""
        val peer = peerIp.text.toString().trim()
        if (withPairing && (password.isEmpty() || !Regex("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$").matches(peer) || peer.split('.').any { it.toInt() > 255 })) {
            report("Enter the actual Wi-Fi password and selected iPhone IPv4 address on the tablet first"); return
        }
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION") val frequency = wifi.connectionInfo.frequency
        @Suppress("DEPRECATION") val securityType = when (wifi.connectionInfo.currentSecurityType) {
            android.net.wifi.WifiInfo.SECURITY_TYPE_PSK -> 2
            android.net.wifi.WifiInfo.SECURITY_TYPE_SAE -> 4
            else -> -1
        }
        if (withPairing && securityType < 0) { report("Wi-Fi security profile unsupported; no guessed configuration sent"); return }
        val channelNumber = when { frequency == 2484 -> 14; frequency in 2412..2472 -> (frequency - 2407) / 5; frequency in 5000..5900 -> (frequency - 5000) / 5; else -> 0 }
        if (withPairing && channelNumber == 0) { report("Unsupported/unavailable Wi-Fi channel; pairing not started"); return }
        if (!saveSettings()) return
        val identification = try {
            check(receiverMac != "02:00:00:00:00:00" && receiverMac != "00:00:00:00:00:00") { "Real tablet Bluetooth address required; do not use Android privacy placeholder" }
            Iap2IdentificationConfig(
                name = "OAL CarPlay Lab", modelIdentifier = "OAL-auth-bench", manufacturer = "OpenAutoLink",
                serialNumber = "oal-lab-private", firmwareVersion = "0.1-auth-bench", hardwareVersion = "tablet",
                wireless = Iap2WirelessIdentification(receiverMac, network), externalAccessoryProtocol = "com.openautolink.carplaylab",
            )
        } catch (e: Exception) { report("Invalid lab configuration: ${e.message}"); return }
        if (withMedia && !surfaceView.holder.surface.isValid) { report("Display surface not ready"); return }
        if (!busy.compareAndSet(false, true)) { report("An attempt is still retiring; wait for TEST_ENDED"); return }
        output.text = ""
        if (withMedia) settingsView.visibility = View.GONE
        updateControls()
        val proof = ProofState()
        activeProof = proof
        worker.execute {
            try {
                check(!destroyed) { "Activity closed" }
                val auth = LocalMfiAuthenticationClient.load(File(noBackupFilesDir, LocalMfiAuthenticationClient.DIRECTORY)) {
                    report("LOCAL_SIGNED challengeBytes=$it (not proof of phone trust)")
                }
                report("LOCAL_IDENTITY_VALIDATED protocolMajor=${auth.protocolMajor()} (not phone acceptance)")
                proof.ensureActive()
                val adapter = getSystemService(BluetoothManager::class.java).adapter
                adapter.cancelDiscovery()
                val selectedSocket = device.createRfcommSocketToServiceRecord(UUID.fromString(IAP2_PHONE_UUID))
                socket = selectedSocket
                if (destroyed) { selectedSocket.close(); error("Activity closed") }
                proof.ensureActive()
                val timeout = deadlines.schedule({ runCatching { selectedSocket.close() } }, 20, TimeUnit.SECONDS)
                report("RFCOMM_CONNECTING timeoutSeconds=20")
                try { selectedSocket.connect() } finally { timeout.cancel(false) }
                report("RFCOMM_CONNECTED")
                val stream = BluetoothRfcommDuplexStream(selectedSocket) { report(it) }
                val channel = Iap2Session.openWireless(stream) // Intentionally NO raw frame trace (cert/challenge/signature secrecy).
                session = channel
                check(!destroyed) { "Activity closed" }
                proof.ensureActive()
                Iap2IdentificationClient(channel).identify(identification, 30_000)
                proof.identificationAccepted()
                report("IPHONE_IDENTIFICATION_ACCEPTED")
                Iap2MfiAuthenticationClient(auth).run(channel, 30_000) { report(it) }
                proof.authenticationAccepted()
                report("IPHONE_AUTHENTICATION_ACCEPTED — real phone trust proven for this attempt.")
                if (withPairing) {
                    proof.ensureActive()
                    val receiverPort: Int
                    val receiverId: String
                    val receiverKey: String
                    if (withMedia) {
                        val receiver = ProjectionReceiver(this, receiverMac, surfaceView.holder.surface, auth,
                            captureAdmission = { captureResumed && !destroyed }) { report(it) }
                        projection = receiver
                        receiver.setRuntimeIdentification(identification)
                        receiver.start(peer)
                        receiverPort = receiver.port; receiverId = receiver.pairingId; receiverKey = receiver.publicKeyHex
                    } else {
                        val server = PairingProbeServer(this, receiverMac) { report(it) }
                        pairingServer = server
                        server.allowPeer(setOf(peer)); server.setAuthenticator(auth); server.start()
                        receiverPort = server.port; receiverId = server.pairingId; receiverKey = server.publicKeyHex
                    }
                    val activator = PhoneActivation(this, peer, receiverMac) { report(it) }
                    activation = activator
                    activator.start()
                    proof.ensureActive()
                    val localAddresses = NetworkInterface.getByName("wlan0").inetAddresses.toList()
                        .filterIsInstance<Inet4Address>().filterNot { it.isLoopbackAddress }.map { it.hostAddress!! }
                    check(localAddresses.isNotEmpty()) { "Wi-Fi IPv4 unavailable" }
                    val offer = EndpointOffer(network, password, channelNumber, securityType, localAddresses,
                        receiverPort, receiverId, receiverKey)
                    projection?.setRuntimeReply(offer::replyTo)
                    for (subscription in Iap2ControlMessages.subscriptions()) { proof.ensureActive(); channel.send(subscription, 5000) }
                    report("ENDPOINT_WAITING maxSeconds=90 addressFamily=IPv4 (IPv6 remains separate/unproven)")
                    val deadline = if (withMedia) Long.MAX_VALUE else System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
                    val replyPolicy = WirelessReplyPolicy()
                    while (System.nanoTime() < deadline && (withMedia || !channel.isClosed)) {
                        proof.ensureActive()
                        val currentReceiver = projection
                        if (withMedia && currentReceiver?.runtimeReady == true && currentReceiver.bluetoothHandoffRequested) {
                            // Close only this attempt's RFCOMM bootstrap; leave Bluetooth/HFP and Wi-Fi live.
                            report("BLUETOOTH_HANDOFF runtimeReady=true; retiring bootstrap only")
                            runCatching { channel.close() }; runCatching { selectedSocket.close() }
                            while (projection === currentReceiver && currentReceiver.runtimeReady) {
                                proof.ensureActive(); Thread.sleep(250)
                            }
                            break
                        }
                        if (channel.isClosed) { Thread.sleep(1000); continue }
                        val frame = try { channel.recv(1000) } catch (failure: java.io.IOException) {
                            if (!withMedia || projection?.runtimeReady != true) throw failure
                            report("BOOTSTRAP_RETIRED runtimeReady=true; wireless projection retained")
                            while (projection?.runtimeReady == true) { proof.ensureActive(); Thread.sleep(250) }
                            break
                        } ?: continue
                        report("CONTROL_RX id=0x${frame.messageId.toString(16)}")
                        val response = offer.replyTo(frame)
                        if (response != null && replyPolicy.allow(frame.messageId)) {
                            proof.ensureActive(); channel.send(response, 5000)
                            report("CONTROL_TX id=0x${response.messageId.toString(16)} (metadata only)")
                        }
                    }
                    report("RECEIVER_WINDOW_ENDED mediaMode=$withMedia")
                }
            } catch (e: SecurityException) {
                report("TEST_FAILED permission revoked; no authentication proof")
            } catch (e: Exception) {
                // Errors are typed status only: never dump a certificate, key, challenge or signature.
                report("TEST_FAILED stage=${if (proof.identified) "authentication" else "bootstrap/identification"} type=${e.javaClass.simpleName} reason=${e.message}")
            } finally {
                closeTransport()
                activeProof = null
                report("TEST_ENDED identified=${proof.identified} authenticated=${proof.authenticated}")
                busy.set(false)
                runOnUiThread { updateControls() }
            }
        }
    }

    private fun closeTransport() {
        val ownedProjection = projection; projection = null
        runCatching { ownedProjection?.close() }
        runOnUiThread { if (!destroyed) { settingsView.visibility = View.VISIBLE; projectionStop.visibility = View.GONE; updateControls() } }
        val ownedActivation = activation; activation = null
        runCatching { ownedActivation?.close() }
        val ownedServer = pairingServer; pairingServer = null
        runCatching { ownedServer?.close() }
        val ownedSession = session; session = null
        val ownedSocket = socket; socket = null
        runCatching { ownedSession?.close() }
        runCatching { ownedSocket?.close() }
    }
    private fun report(message: String) {
        Log.i("OALCarPlayLab", message)
        runOnUiThread { if (!destroyed && ::output.isInitialized) { output.append(message + "\n"); ui.outcome.text = message; updateControls() } }
    }
    override fun onSaveInstanceState(state: Bundle) {
        state.putString("receiver_bt_address", mac.text.toString())
        state.putString("carplay_ssid", ssid.text.toString())
        state.putString("carplay_peer_ip", peerIp.text.toString())
        super.onSaveInstanceState(state)
    }
    override fun onDestroy() {
        destroyed = true
        activeProof?.cancel()
        closeTransport()
        worker.shutdownNow()
        deadlines.shutdownNow()
        super.onDestroy()
    }
    companion object {
        // Exact outgoing iPhone iAP2 service UUID from pinned DiPlay CarPlayController.kt:2637.
        private const val IAP2_PHONE_UUID = "00000000-deca-fade-deca-deafdecacafe"
    }
}
