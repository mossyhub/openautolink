package com.openautolink.app.ui.settings

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openautolink.app.data.CarPlaySettings
import com.openautolink.app.data.CarPlaySettingsPolicy
import com.openautolink.carplaylab.media.CarPlayDisplayPolicy
import com.openautolink.carplaylab.media.CarPlayDisplayPreferences
import com.openautolink.carplaylab.media.CarPlayResolution

/** Pure draft policy: only exact locally qualified profiles are selectable. */
internal object CarPlaySettingsUiPolicy {
    fun selectResolution(current: CarPlaySettings, resolutionId: String,
        qualifiedProfiles: List<CarPlayDisplayPreferences>): CarPlaySettings {
        if (current.fitToWindow && resolutionId in setOf("720p", "1080p", "1440p", "4k"))
            return current.copy(resolution = resolutionId)
        val rates = qualifiedProfiles.filter { it.resolution.id == resolutionId }.map { it.fps }.distinct()
        val fps = current.fps.takeIf { it in rates }
            ?: 30.takeIf { it in rates }
            ?: rates.minWithOrNull(compareBy<Int> { kotlin.math.abs(it - current.fps) }.thenBy { it })
            ?: return current
        return current.copy(resolution = resolutionId, fps = fps, fpsDraft = null)
    }
}

/** Real OAL settings content; no BenchActivity, protocol startup, or secret instance-state storage. */
@Composable
fun CarPlaySettingsSection(viewModel: SettingsViewModel) {
    val settings by viewModel.carPlaySettings.collectAsStateWithLifecycle()
    val phones by viewModel.carPlayPhones.collectAsStateWithLifecycle()
    val identityReady by viewModel.carPlayIdentityReady.collectAsStateWithLifecycle()
    val passwordSaved by viewModel.carPlayPasswordSaved.collectAsStateWithLifecycle()
    val status by viewModel.carPlaySettingsStatus.collectAsStateWithLifecycle()
    val saving by viewModel.carPlaySettingsSaving.collectAsStateWithLifecycle()
    val loaded by viewModel.carPlaySettingsLoaded.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val bluetoothPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.refreshCarPlaySetup()
    }

    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("CarPlay", style = MaterialTheme.typography.titleLarge)
            Text("Enable iPhone projection", style = MaterialTheme.typography.bodyMedium)
        }
        Switch(checked = settings.enabled, onCheckedChange = viewModel::updateCarPlayEnabled,
            enabled = !saving && loaded, modifier = Modifier.testTag("carPlayEnabled"))
    }
    Text("CarPlay connection", style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("usb" to "USB", "wireless" to "Wireless").forEach { (mode, label) ->
            FilterChip(selected = settings.connectionMode == mode, enabled = !saving && loaded,
                onClick = { viewModel.updateCarPlayDraft { it.copy(connectionMode = mode) } },
                label = { Text(label) }, modifier = Modifier.testTag("carPlayConnection_$mode"))
        }
    }
    SettingsHelp("CarPlay's normal connection is independent of Android Auto. USB uses the local USB bridge and requires USB/VPN consent; iPhone may ask for Trust. Wireless uses iPhone Bluetooth and the car Wi-Fi; wireless CarPlay is unverified in the car. Selecting a connection does not enable CarPlay. Changes apply with Save & Reconnect.")
    if (!settings.enabled) return

    val draft = settings
    // Local echo is recreated from private ViewModel memory when returning to this tab.
    var password: String? by remember { mutableStateOf(viewModel.pendingCarPlayPassword()) }
    var advanced by remember { mutableStateOf(false) }
    var phoneMenu by remember { mutableStateOf(false) }
    var phoneDetails by remember { mutableStateOf(false) }
    var authenticationExpanded by remember { mutableStateOf(false) }
    // Availability only; credentials are validated/installed by the connection owner, not Settings.
    val bundledIdentityAvailable = remember(context) {
        runCatching { context.assets.list("offline-mfi").orEmpty().toSet()
            .containsAll(listOf("identity.pk8", "certificate.p7b")) }.getOrDefault(false)
    }
    var setupHelp by remember { mutableStateOf(false) }
    var selectedKey by remember { mutableStateOf<android.net.Uri?>(null) }
    var selectedCertificate by remember { mutableStateOf<android.net.Uri?>(null) }
    val importing by viewModel.carPlayCredentialImportBusy.collectAsStateWithLifecycle()
    val importStatus by viewModel.carPlayCredentialImportStatus.collectAsStateWithLifecycle()
    var pickerError by remember { mutableStateOf(false) }
    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        selectedKey = uri // Temporary URI grant only; no raw key, filename or persisted grant.
    }
    val certificatePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        selectedCertificate = uri
    }
    LaunchedEffect(settings.enabled) { viewModel.refreshCarPlaySetup() }

    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (draft.connectionMode == "wireless") {
            Text("Paired iPhone", style = MaterialTheme.typography.titleMedium)
            Text("Pair your iPhone in GM / iPhone Bluetooth settings. No extra OAL pairing is needed.",
                style = MaterialTheme.typography.bodyMedium)
            val selected = CarPlaySettingsPolicy.selectedPhone(draft.phoneAddress, phones)
            Column {
                OutlinedButton(onClick = { phoneMenu = true }, enabled = !saving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("carPlayPhone")) {
                    Text(selected?.let { it.name.ifBlank { it.address } }
                        ?: if (draft.phoneAddress.isEmpty()) "Select a paired phone" else "Saved phone unavailable — select a paired phone")
                }
                DropdownMenu(expanded = phoneMenu, onDismissRequest = { phoneMenu = false }) {
                    DropdownMenuItem(text = { Text("No phone selected") }, onClick = {
                        viewModel.updateCarPlayDraft { it.copy(phoneAddress = "") }; phoneMenu = false
                    })
                    phones.forEach { phone ->
                        DropdownMenuItem(text = { Text(phone.name.ifBlank { phone.address }) }, onClick = {
                            viewModel.updateCarPlayDraft { it.copy(phoneAddress = phone.address) }; phoneMenu = false
                        })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT) }) { Text("Refresh phones") }
                OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) { Text("Bluetooth settings") }
            }
            TextButton(onClick = { phoneDetails = !phoneDetails }) { Text(if (phoneDetails) "Hide phone details" else "Phone details") }
            if (phoneDetails) {
                phones.forEach { phone ->
                    OutlinedButton(onClick = {
                        viewModel.updateCarPlayDraft { it.copy(phoneAddress = phone.address) }
                        phoneMenu = false
                    }, enabled = !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text("${phone.name.ifBlank { "Unnamed phone" }} · ${phone.address}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                SettingsHelp("Select the paired iPhone OAL should use. The saved Bluetooth address identifies it; a name alone does not establish trust.")
            }
        }
        HorizontalDivider()
        TextButton(onClick = { authenticationExpanded = !authenticationExpanded },
            modifier = Modifier.testTag("carPlayAuthentication")) {
            Text(if (authenticationExpanded) "Hide authentication" else "Authentication")
        }
        if (authenticationExpanded) {
            Text(when {
                identityReady -> "Authentication configured on this head unit."
                bundledIdentityAvailable -> "Authentication bundled; validated on first connect."
                else -> "This build has no bundled authentication. Import a compatible identity below."
            }, style = MaterialTheme.typography.bodySmall)
            Text("Import is for setup or recovery. It does not pair your iPhone.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { setupHelp = true }, enabled = !saving && !importing) {
                Text(if (identityReady || bundledIdentityAvailable) "Replace authentication identity" else "Import authentication identity")
            }
            if (importStatus.isNotEmpty()) Text(importStatus, style = MaterialTheme.typography.bodySmall)
        }
        if (draft.connectionMode == "wireless") {
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced" else "Advanced network") }
        }
        if (advanced && draft.connectionMode == "wireless") {
            Text(CarPlaySettingsPolicy.requiredAction(draft, phones, identityReady || bundledIdentityAvailable, passwordSaved),
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("carPlayRequiredAction"))
            Text("Use your car hotspot's actual details. OAL does not change the hotspot.",
                style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Use shared Wi-Fi / LAN instead of car AP", Modifier.weight(1f))
                Switch(checked = draft.sharedWifiOverride, enabled = !saving, onCheckedChange = {
                    val shared = it
                    viewModel.updateCarPlayDraft { current -> current.copy(sharedWifiOverride = shared,
                        interfaceName = if (current.interfaceName in listOf("wlan0", "ap_br_swlan0")) {
                            if (shared) "wlan0" else "ap_br_swlan0"
                        } else current.interfaceName) }
                })
            }
            Text("Address family", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("auto" to "Auto", "ipv4" to "IPv4", "ipv6" to "IPv6").forEach { (family, label) ->
                    FilterChip(selected = draft.networkFamily == family, enabled = !saving,
                        onClick = { viewModel.updateCarPlayDraft { it.copy(networkFamily = family) } },
                        label = { Text(label) }, modifier = Modifier.testTag("carPlayFamily_$family"))
                }
            }
            SettingsHelp("Auto uses IPv6 on the car hotspot and IPv4 on shared LAN. Match both endpoints when overriding.")
            CarPlayField(if (draft.sharedWifiOverride) "Phone numeric IP (required)" else "Phone numeric IP (optional)",
                draft.peerAddress, !saving) { value -> viewModel.updateCarPlayDraft { it.copy(peerAddress = value) } }
            if (!draft.sharedWifiOverride) {
                SettingsHelp("Leave phone IP empty for first connection or a previously trusted iPhone. Pair in system Bluetooth settings and accept CarPlay on the iPhone. Use a trusted hotspot for first setup.")
            }
            CarPlayField("Network interface", draft.interfaceName, !saving) { value -> viewModel.updateCarPlayDraft { it.copy(interfaceName = value) } }
            CarPlayField("Exact local address (optional)", draft.localAddress, !saving) { value ->
                viewModel.updateCarPlayDraft { it.copy(localAddress = value) }
            }
            SettingsHelp("Leave local address empty to use this interface's address. OAL checks IPv6 scope automatically.")
            if (!draft.sharedWifiOverride) {
                CarPlayField("Existing AP frequency (MHz)", draft.apFrequencyMhz.toString(), !saving) { value ->
                    viewModel.updateCarPlayDraft { it.copy(apFrequencyMhz = value.toIntOrNull() ?: 0) }
                }
                Text("AP security", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("wpa2" to "WPA2", "wpa3" to "WPA3").forEach { (security, label) ->
                        FilterChip(selected = draft.apSecurity == security, enabled = !saving,
                            onClick = { viewModel.updateCarPlayDraft { it.copy(apSecurity = security) } },
                            label = { Text(label) }, modifier = Modifier.testTag("carPlayApSecurity_$security"))
                    }
                }
                Text("Match the actual car AP frequency and security; these CarPlay overrides never change Android Auto WPP settings.", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Shared Wi-Fi uses the connected network's actual frequency and security; saved car AP overrides are retained.", style = MaterialTheme.typography.bodySmall)
            }
            CarPlayField("Actual Wi-Fi SSID", draft.ssid, !saving) { value -> viewModel.updateCarPlayDraft { it.copy(ssid = value) } }
            OutlinedTextField(value = password ?: "", onValueChange = { password = it; viewModel.updateCarPlayPassword(it) }, enabled = !saving,
                label = { Text(if (passwordSaved) "New passphrase (unchanged keeps saved)" else "Wi-Fi passphrase") },
                supportingText = { Text("Stored encrypted, excluded from backups. Saved with Save & Reconnect.") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("carPlayPassphrase"))
            if (passwordSaved) TextButton(onClick = { password = ""; viewModel.updateCarPlayPassword("") }, enabled = !saving) { Text("Clear saved passphrase on Save & Reconnect") }
            CarPlayField("Head unit Bluetooth MAC", draft.receiverAddress, !saving) { value ->
                viewModel.updateCarPlayDraft { it.copy(receiverAddress = value) }
            }
            SettingsHelp("On GM, open the car's Settings app → System → About, then scroll to Bluetooth address. Enter that address, not the phone address or Wi-Fi BSSID. Android's 02:00:00:00:00:00 placeholder cannot be used.")
        }
        Text("Changes apply with Save & Reconnect.", style = MaterialTheme.typography.bodySmall)
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodyMedium)
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Android Auto settings below apply only to Android phones.", style = MaterialTheme.typography.bodySmall)
    }
    if (setupHelp) {
        AlertDialog(onDismissRequest = {
            if (!importing) { setupHelp = false; selectedKey = null; selectedCertificate = null }
        }, title = { Text("Import authentication identity") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Import only while parked and disconnected. Choose a matching P-256 PKCS#8 key (identity.pk8) and one X.509 certificate (certificate.p7b, DER or PKCS#7), each at most 16 KiB. Failed import keeps the existing identity.")
                    SettingsHelp("Files need a system document provider, such as USB storage. Import replaces the pair in private, backup-excluded storage; it does not pair or trust an iPhone.")
                    OutlinedButton(enabled = !importing, onClick = {
                        pickerError = false
                        try { keyPicker.launch(arrayOf("*/*")) }
                        catch (_: android.content.ActivityNotFoundException) { pickerError = true }
                    }) { Text(if (selectedKey == null) "Choose private key" else "Private key selected") }
                    OutlinedButton(enabled = !importing, onClick = {
                        pickerError = false
                        try { certificatePicker.launch(arrayOf("*/*")) }
                        catch (_: android.content.ActivityNotFoundException) { pickerError = true }
                    }) { Text(if (selectedCertificate == null) "Choose certificate" else "Certificate selected") }
                    if (pickerError) Text("No system document picker is available on this car. A compatible file provider is required.")
                    if (importStatus.isNotEmpty()) Text(importStatus)
                }
            }, confirmButton = {
                TextButton(enabled = !saving && !importing && selectedKey != null && selectedCertificate != null,
                    onClick = {
                        viewModel.importCarPlayCredentials(requireNotNull(selectedKey), requireNotNull(selectedCertificate))
                        selectedKey = null; selectedCertificate = null
                    }) { Text(if (importing) "Importing…" else "Import selected pair") }
            }, dismissButton = {
                TextButton(enabled = !importing, onClick = {
                    setupHelp = false; selectedKey = null; selectedCertificate = null
                }) { Text("Close") }
            })
    }
}

/** Physical display width is CarPlay metadata, not Android Auto DPI or a live-stream change. */
@Composable
fun CarPlayDisplaySettingsSection(
    viewModel: SettingsViewModel,
    onNavigateToCarPlaySafeAreaEditor: () -> Unit,
) {
    val settings by viewModel.carPlaySettings.collectAsStateWithLifecycle()
    val saving by viewModel.carPlaySettingsSaving.collectAsStateWithLifecycle()
    val status by viewModel.carPlaySettingsStatus.collectAsStateWithLifecycle()
    if (!settings.enabled) return

    var sliderWidthMm by remember(settings.widthPhysicalMm) { mutableStateOf(settings.widthPhysicalMm) }
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("CarPlay")
        Text("CarPlay safe area", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = onNavigateToCarPlaySafeAreaEditor, enabled = !saving,
            modifier = Modifier.testTag("carPlaySafeAreaEditor")) { Text("Calibrate safe controls") }
        Text("Background can extend to the edges; controls stay inside your manual safe rectangle. No automatic curve detection.",
            style = MaterialTheme.typography.bodySmall)
        Text("Safe edges: T ${settings.safeTop / 100.0}% · B ${settings.safeBottom / 100.0}% · L ${settings.safeLeft / 100.0}% · R ${settings.safeRight / 100.0}%",
            style = MaterialTheme.typography.bodySmall)
        Text("CarPlay UI scale", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Auto sizing", Modifier.weight(1f))
            Switch(checked = settings.autoScale, enabled = !saving,
                onCheckedChange = { automatic ->
                    viewModel.updateCarPlayDraft { it.copy(autoScale = automatic) }
                }, modifier = Modifier.testTag("carPlayAutoScale"))
        }
        Text("Auto uses the next session's viewport. Turn off for manual UI size.", style = MaterialTheme.typography.bodySmall)
        Text("Manual UI size", style = MaterialTheme.typography.titleMedium)
        // Follow AA DPI's local drag + finished draft update; larger UI is to the right.
        Slider(
            value = (500 - sliderWidthMm).toFloat(),
            onValueChange = { sliderWidthMm = 500 - it.toInt() },
            onValueChangeFinished = {
                viewModel.updateCarPlayDraft { it.copy(widthPhysicalMm = sliderWidthMm) }
            },
            valueRange = 100f..400f,
            steps = 0,
            enabled = !saving && !settings.autoScale,
            modifier = Modifier.fillMaxWidth(0.7f).testTag("carPlayUiScale"),
        )
        Row(Modifier.fillMaxWidth(0.7f), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Smaller UI", style = MaterialTheme.typography.bodySmall)
            Text("Larger UI", style = MaterialTheme.typography.bodySmall)
        }
        Text("Larger UI shows less content; smaller UI shows more.", style = MaterialTheme.typography.bodySmall)
        SettingsHelp("Calibration: physical width $sliderWidthMm mm. Smaller physical width makes the UI larger. Auto estimates width from the viewport; this is CarPlay metadata, not Android Auto DPI.")
        Text("Changes apply with Save & Reconnect.", style = MaterialTheme.typography.bodySmall)
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodyMedium)
        HorizontalDivider()
        Text("Android Auto settings below apply only to Android phones.", style = MaterialTheme.typography.bodySmall)
    }
}

/** Next-session video requests only; connection setup and encrypted passphrase stay untouched. */
@Composable
fun CarPlayVideoSettingsSection(viewModel: SettingsViewModel) {
    val settings by viewModel.carPlaySettings.collectAsStateWithLifecycle()
    val saving by viewModel.carPlaySettingsSaving.collectAsStateWithLifecycle()
    val status by viewModel.carPlaySettingsStatus.collectAsStateWithLifecycle()
    if (!settings.enabled) return

    val draft = settings
    var resolutionMenu by remember { mutableStateOf(false) }
    var fpsMenu by remember { mutableStateOf(false) }
    val probe = remember { CarPlayDisplayPolicy.androidProbe() }
    val availableDisplays = remember(settings.widthPhysicalMm, probe) {
        CarPlayDisplayPolicy.availableDisplays(
            CarPlayDisplayPreferences(widthPhysicalMm = settings.widthPhysicalMm), probe)
    }
    val videoError = draft.videoError()
    val request = if (videoError == null) runCatching { draft.displayRequest() }.getOrNull() else null
    val supportedProfile = remember(request, probe) {
        request != null && CarPlayDisplayPolicy.isQualified(request, probe)
    }
    val resolution = CarPlayResolution.entries.firstOrNull { it.id == draft.resolution }
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("CarPlay")
        Text("Resolution / detail", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Fit to window (standard detail tiers)", Modifier.weight(1f))
            Switch(checked = draft.windowDetail, enabled = !saving && !draft.autoResolution &&
                draft.resolution in setOf("720p", "1080p", "1440p", "4k"),
                onCheckedChange = { fit -> viewModel.updateCarPlayDraft { it.copy(fitToWindow = fit) } },
                modifier = Modifier.testTag("carPlayFitToWindow"))
        }
        SettingsHelp("With Fit to window, 720p/1080p/1440p/4K select encoded detail at the measured available window aspect in either display mode. Off preserves exact preset geometry and aspect-fits it. Custom and geometry-named presets always remain exact; saved values are never changed by this switch.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(true to "Automatic", false to "Manual").forEach { (automatic, label) ->
                OutlinedButton(enabled = !saving, onClick = {
                    viewModel.updateCarPlayDraft { it.copy(autoResolution = automatic) }
                }, modifier = Modifier.testTag("carPlayResolutionPolicy_$automatic")) {
                    Text(if (draft.autoResolution == automatic) "$label ✓" else label)
                }
            }
        }
        SettingsHelp("Automatic selects one exact decoder-qualified 1440p-class geometry at the measured display aspect, with bounded lower-detail and safe 720p30 fallback. It does not ask iPhone to choose a resolution or add another screen. Manual values are retained.")
        Column {
            OutlinedButton(onClick = { resolutionMenu = true }, enabled = !saving && !draft.autoResolution,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("carPlayResolutionMenu")) {
                Text(if (draft.windowDetail) "${resolution?.id} detail · window aspect"
                    else resolution?.label ?: "Choose resolution")
            }
            DropdownMenu(expanded = resolutionMenu, onDismissRequest = { resolutionMenu = false }) {
                CarPlayResolution.PRESETS.filter { resolution ->
                    (draft.fitToWindow && resolution.id in setOf("720p", "1080p", "1440p", "4k")) ||
                    availableDisplays.any { profile -> profile.resolution == resolution }
                }.forEach { resolution ->
                    DropdownMenuItem(text = { Text(if (draft.fitToWindow && resolution.id in setOf("720p", "1080p", "1440p", "4k"))
                        "${resolution.id} detail · window aspect" else resolution.label) }, enabled = !saving,
                        modifier = Modifier.testTag("carPlayResolution_${resolution.id}"), onClick = {
                            viewModel.updateCarPlayDraft { current ->
                                CarPlaySettingsUiPolicy.selectResolution(current, resolution.id, availableDisplays)
                            }
                            resolutionMenu = false
                            fpsMenu = false
                        })
                }
                DropdownMenuItem(text = { Text("Custom") }, enabled = !saving, onClick = {
                    viewModel.updateCarPlayDraft { it.copy(resolution = "custom") }
                    resolutionMenu = false; fpsMenu = false
                }, modifier = Modifier.testTag("carPlayResolution_custom"))
            }
        }
        if (!draft.autoResolution && draft.resolution == "custom") {
            OutlinedTextField(value = draft.widthDraft ?: draft.widthPixels.toString(),
                onValueChange = { value -> viewModel.updateCarPlayDraft {
                    it.copy(widthPixels = value.toIntOrNull() ?: 0, widthDraft = value)
                } }, enabled = !saving, label = { Text("Width (pixels)") },
                isError = videoError != null, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("carPlayCustomWidth"))
            OutlinedTextField(value = draft.heightDraft ?: draft.heightPixels.toString(),
                onValueChange = { value -> viewModel.updateCarPlayDraft {
                    it.copy(heightPixels = value.toIntOrNull() ?: 0, heightDraft = value)
                } }, enabled = !saving, label = { Text("Height (pixels)") },
                isError = videoError != null, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("carPlayCustomHeight"))
            Text("Positive even dimensions; the exact size and rate must qualify on this decoder.",
                style = MaterialTheme.typography.bodySmall)
        }
        Text("Maximum FPS", style = MaterialTheme.typography.titleMedium)
        Column {
            OutlinedButton(onClick = { fpsMenu = true }, enabled = !saving && request != null,
                modifier = Modifier.testTag("carPlayFps")) { Text("Common / saved rates") }
            DropdownMenu(expanded = fpsMenu, onDismissRequest = { fpsMenu = false }) {
                CarPlayDisplayPreferences.QUICK_FRAME_RATES.filter { fps ->
                    request != null && (draft.usesWindowGeometry || CarPlayDisplayPolicy.isQualified(request.copy(fps = fps), probe))
                }.forEach { fps ->
                    DropdownMenuItem(text = { Text("$fps maximum FPS") }, onClick = {
                        viewModel.updateCarPlayDraft { it.copy(fps = fps, fpsDraft = null) }; fpsMenu = false
                    })
                }
            }
        }
        OutlinedTextField(value = draft.fpsDraft ?: draft.fps.toString(),
            onValueChange = { value -> viewModel.updateCarPlayDraft {
                it.copy(fps = value.toIntOrNull() ?: 0, fpsDraft = value)
            } }, enabled = !saving, label = { Text("Custom maximum FPS (1–60)") },
            isError = videoError != null, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("carPlayCustomFps"))
        Text("Codec", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("h264" to "H.264", "hevc" to "Automatic (H.264 + qualified HEVC)").forEach { (id, label) ->
                OutlinedButton(enabled = !saving, onClick = {
                    viewModel.updateCarPlayDraft { it.copy(videoCodec = id) }
                }, modifier = Modifier.testTag("carPlayCodec_$id")) {
                    Text(if (draft.videoCodec == id) "$label ✓" else label)
                }
            }
        }
        SettingsHelp("Automatic codec always retains H.264 and offers HEVC only when the same exact size, rate and profile qualify. H.264 remains the default. Optional HEVC has been verified on one phone/display after a negotiation correction, not on every device. If connection fails, select H.264 and Save & Reconnect. Local qualification is not phone acceptance; no automatic retry is performed.")
        if (videoError != null) Text(videoError, color = MaterialTheme.colorScheme.error)
        else if (!draft.usesWindowGeometry && !supportedProfile) Text("This exact size and maximum FPS are not supported by the local decoder.",
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        SettingsHelp("Maximum FPS is a cap, not a constant achieved rate. 30 and 60 are common choices; 1–60 is the current product range, not a protocol limit. Local decoder support does not verify iPhone acceptance.")
        Text("Changes apply with Save & Reconnect.", style = MaterialTheme.typography.bodySmall)
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodyMedium)
        HorizontalDivider()
        Text("Android Auto settings below apply only to Android phones.", style = MaterialTheme.typography.bodySmall)
    }
}
@Composable
private fun CarPlayField(label: String, value: String, enabled: Boolean, onValueChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onValueChange, enabled = enabled,
        label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}
