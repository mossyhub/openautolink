package com.openautolink.app.ui.diagnostics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.shilapi.xcertplay.media.CarPlayMicrophoneCaptureRate
import com.shilapi.xcertplay.media.CarPlayMicrophoneChannelSelection
import com.shilapi.xcertplay.media.CarPlayMicrophoneChannels
import com.shilapi.xcertplay.media.CarPlayMicrophoneCodec
import com.shilapi.xcertplay.media.MicrophoneSource

@Composable
fun MicrophoneDiagnosticPanel(controller: MicrophoneDiagnosticController) {
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(controller, lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ ->
            controller.updateForeground(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
        }
        lifecycle.addObserver(observer)
        controller.updateForeground(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
        onDispose {
            lifecycle.removeObserver(observer)
            controller.updateForeground(false)
        }
    }
    androidx.compose.runtime.LaunchedEffect(controller) {
        while (true) {
            controller.refresh()
            kotlinx.coroutines.delay(100)
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Microphone diagnostic", style = MaterialTheme.typography.headlineSmall)
        Text("Disconnect all projection phones. Leave the iPhone unpaired. No native calls during this test.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Opt-in only · up to 3 seconds per arm · 15-second comparison deadline. A stalled recorder may take longer to release. No audio saved or played; normal capture settings stay unchanged.", style = MaterialTheme.typography.bodySmall)
        Text("Up to 12 completed numeric comparisons are retained for manual Upload Logs; ordinary upload age/size limits apply.", style = MaterialTheme.typography.bodySmall)
        Text("Local parked capture", style = MaterialTheme.typography.titleLarge)
        Confirmation("mic-consent", "I consent to this bounded microphone test", controller.consent, controller::updateConsent)
        if (!controller.automotive) Confirmation("mic-stationary", "I confirm the vehicle is stationary", controller.stationary, controller::updateStationary)
        if (controller.busy) {
            Text(if (controller.state.phase == MicrophoneDiagnosticPhase.STOPPING)
                "Stopping · waiting for microphone release" else "${controller.state.phase.name.lowercase().replaceFirstChar { it.uppercase() }} · keep this panel open",
                style = MaterialTheme.typography.titleMedium)
            OutlinedButton(controller::cancel, enabled = controller.state.phase != MicrophoneDiagnosticPhase.STOPPING,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("mic-cancel")) { Text("Cancel") }
        }
        CustomCaptureControls(controller)
        OutlinedButton(controller::inspect, enabled = controller.canStart,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("mic-inspect")) { Text("Inspect inputs · no capture") }
        val comparisons = listOf(
            MicrophoneDiagnosticComparison.CHANNELS to "Compare channels · mono → 2ch → mono (24 kHz)",
            MicrophoneDiagnosticComparison.RATE to "Compare rate · 24 → 48 → 24 kHz (mono)",
            MicrophoneDiagnosticComparison.PREFERRED to "Compare input · default → named → default (24 kHz mono)",
            MicrophoneDiagnosticComparison.SOURCES to "Optional parked sources · recognition (6) → communication (7) → recognition (6) · 24 kHz mono",
        )
        comparisons.forEach { (comparison, label) ->
            Button(onClick = { controller.start(comparison) }, enabled = controller.canStart,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("mic-start-${comparison.name}")) { Text(label) }
        }
        Text("Channels, rate and input: VOICE_RECOGNITION (6). Sources: VOICE_RECOGNITION (6) → VOICE_COMMUNICATION (7) → VOICE_RECOGNITION (6). No effects attached; no active-projection capture.", style = MaterialTheme.typography.bodySmall)
        if (controller.state.phase == MicrophoneDiagnosticPhase.COMPLETE) controller.state.result?.let {
            Text(when (controller.summaryStored) {
                true -> "Numeric summary saved for manual Upload Logs."
                false -> "Summary could not be saved. The onscreen results remain available."
                null -> "Numeric summary · storage confirmation pending."
            }, style = MaterialTheme.typography.bodySmall)
            MicrophoneDiagnosticSummary(it)
        }
        Text("Outcome: ${controller.state.result?.reason ?: controller.state.reason}", Modifier.testTag("mic-outcome"),
            style = MaterialTheme.typography.bodySmall)
        LiveExperimentControls(controller)
    }
}

@Composable
private fun CustomCaptureControls(controller: MicrophoneDiagnosticController) {
    val config = controller.custom
    val enabled = !controller.busy
    Choices("Local source", listOf(1 to "MIC (1)", 6 to "Recognition (6)", 7 to "Communication (7)"),
        config.source, enabled, { "mic-custom-source-$it" }) { controller.updateCustom(config.copy(source = it)) }
    Choices("Local capture rate", listOf(16000 to "16 kHz", 24000 to "24 kHz", 48000 to "48 kHz"),
        config.sampleRate, enabled, { "mic-custom-rate-$it" }) { controller.updateCustom(config.copy(sampleRate = it)) }
    Choices("Local channels", listOf(1 to "Mono", 2 to "Two channels"),
        config.channelCount, enabled, { "mic-custom-channels-$it" }) { controller.updateCustom(config.copy(channelCount = it)) }
    Choices("Local input", listOf(false to "Default input", true to "Unique named input"),
        config.preferredNamedInput, enabled, { "mic-custom-input-${if (it) "named" else "default"}" }) {
        controller.updateCustom(config.copy(preferredNamedInput = it))
    }
    Text("Named input must match uniquely. Setter acceptance is not route proof. Custom capture is one arm, not a control/test/control comparison.",
        style = MaterialTheme.typography.bodySmall)
    Button(controller::startCustom, enabled = controller.canStart,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("mic-custom-run")) { Text("Run custom · one arm") }
}

@Composable
private fun LiveExperimentControls(controller: MicrophoneDiagnosticController) {
    HorizontalDivider()
    Text("CarPlay microphone experiment", style = MaterialTheme.typography.titleLarge)
    val status = controller.liveStatus
    Text(when {
        status.armed -> "Armed for next CarPlay · expires in ${(status.remainingMillis + 999) / 1000} seconds"
        controller.liveExpired -> "Expired · OFF"
        else -> "OFF · normal CarPlay defaults"
    }, Modifier.testTag("mic-live-status"), style = MaterialTheme.typography.titleMedium)
    Text("Memory only · 15 minutes · next connection / new recorder only. Leaving this page keeps the armed profile but clears consent. Stop/reconnect to apply changes; disarm/reset never changes an active recorder.",
        style = MaterialTheme.typography.bodySmall)
    status.profile?.let {
        Text("Armed request: ${it.source ?: "automatic"} · ${it.rate} · ${it.channels} · ${it.selection} · codec preference ${it.codec}",
            style = MaterialTheme.typography.bodySmall)
    }
    val profile = controller.liveProfile
    val enabled = !controller.busy
    Choices("Next-session source", listOf< Pair<MicrophoneSource?, String> >(
        null to "Automatic", MicrophoneSource.MIC to "MIC (1)",
        MicrophoneSource.VOICE_RECOGNITION to "Recognition (6)", MicrophoneSource.VOICE_COMMUNICATION to "Communication (7)"),
        profile.source, enabled, { "mic-live-source-${it?.name ?: "AUTO"}" }) {
        controller.updateLiveProfile(profile.copy(source = it))
    }
    Choices("Local capture rate · not RTP clock", listOf(CarPlayMicrophoneCaptureRate.NEGOTIATED to "Negotiated",
        CarPlayMicrophoneCaptureRate.HZ_24000 to "24 kHz", CarPlayMicrophoneCaptureRate.HZ_48000 to "48 kHz"),
        profile.rate, enabled, { "mic-live-rate-${it.name}" }) { controller.updateLiveProfile(profile.copy(rate = it)) }
    Choices("Capture channels", listOf(CarPlayMicrophoneChannels.MONO to "Mono", CarPlayMicrophoneChannels.TWO to "Two channels"),
        profile.channels, enabled, { "mic-live-channels-${it.name}" }) {
        controller.updateLiveProfile(profile.copy(channels = it, selection =
            if (it == CarPlayMicrophoneChannels.MONO) CarPlayMicrophoneChannelSelection.AVERAGE else profile.selection))
    }
    Choices("Two-channel mix to negotiated mono", CarPlayMicrophoneChannelSelection.entries.map { it to it.name.lowercase().replaceFirstChar(Char::uppercase) },
        profile.selection, enabled, { "mic-live-selection-${it.name}" },
        choiceEnabled = { profile.channels == CarPlayMicrophoneChannels.TWO }) {
        controller.updateLiveProfile(profile.copy(selection = it))
    }
    Choices("Microphone codec negotiation preference", CarPlayMicrophoneCodec.entries.map { it to it.name },
        profile.codec, enabled, { "mic-live-codec-${it.name}" }) { controller.updateLiveProfile(profile.copy(codec = it)) }
    Text("Codec preference restricts existing qualified microphone offers only; the phone selects the wire codec. A restriction may prevent connection. Disarm + Reconnect restores default offers. No wireless LPCM is added; no midstream renegotiation.",
        Modifier.testTag("mic-live-codec-note"), style = MaterialTheme.typography.bodySmall)
    Text("Actual wire codec: unavailable in this panel. Use actual microphone setup/capture receipts in manual Upload Logs, not the requested preference. Caller audio, intelligibility and echo remain unverified.",
        style = MaterialTheme.typography.bodySmall)
    Confirmation("mic-live-consent", "I consent to arming this future CarPlay microphone experiment", controller.liveConsent, controller::updateLiveConsent)
    Text("Arm requires this foreground panel, fresh actual Park and no active projection. Local test consent and manual stationary confirmation do not authorize Arm.",
        style = MaterialTheme.typography.bodySmall)
    Button(controller::armLive, enabled = controller.canArmLive,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("mic-live-arm")) { Text("Arm for next CarPlay · 15 minutes") }
    OutlinedButton(controller::disarmLive,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("mic-live-disarm")) { Text("Disarm · future owners only") }
    OutlinedButton(controller::resetLive, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("mic-live-reset")) { Text("Reset draft and disarm") }
}

/** Native selected semantics and at-least-48dp targets, including compact screens. */
@Composable
private fun <T> Choices(title: String, choices: List<Pair<T, String>>, selected: T, enabled: Boolean,
    tag: (T) -> String, choiceEnabled: (T) -> Boolean = { true }, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        choices.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (value, label) ->
                    FilterChip(selected = value == selected, onClick = { onSelect(value) },
                        enabled = enabled && choiceEnabled(value), label = { Text(label) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(tag(value)))
                }
            }
        }
    }
}

@Composable
private fun MicrophoneDiagnosticSummary(result: MicrophoneDiagnosticResult) {
    HorizontalDivider()
    Text("Numeric summary", style = MaterialTheme.typography.titleLarge)
    Text("Signal is not proof of speech or phone acceptance.", color = MaterialTheme.colorScheme.primary)
    Text("Setter accepted is not proof of the selected route.", style = MaterialTheme.typography.bodySmall)
    Text("Named-input match: ${result.inventory.match}", Modifier.testTag("mic-inventory"))
    Text("Display bounds: 16 inputs · 16 values per capability · 3 arms · 2 channels", style = MaterialTheme.typography.bodySmall)
    result.inventory.inputs.take(16).forEachIndexed { index, caps ->
        Text("Input ${index + 1} · Rates: ${capabilities(caps.sampleRates, caps.ratesUnconstrained)}\n" +
            "Masks: ${capabilities(caps.channelMasks, caps.masksUnconstrained)} · index masks: ${capabilities(caps.channelIndexMasks, caps.indexMasksUnconstrained)}" +
            if (caps.truncated) " · truncated" else "",
            Modifier.testTag("mic-capabilities-${index + 1}"), style = MaterialTheme.typography.bodySmall)
    }
    result.arms.take(3).forEachIndexed { index, arm ->
        val tag = "mic-arm-${index + 1}"
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Arm ${arm.ordinal}", Modifier.testTag(tag), style = MaterialTheme.typography.titleMedium)
                SummaryRow("$tag-requested", "Requested: ${shape(arm.requested)}")
                SummaryRow("$tag-actual", "Recorder: ${shape(arm.actual)}")
                SummaryRow("$tag-client", "Client: ${shape(arm.client)}")
                SummaryRow("$tag-device", "Device: ${shape(arm.device)}")
                SummaryRow("$tag-route", "Preferred requested: ${flag(arm.preferredRequested)} · accepted: ${flag(arm.preferredAccepted)}\n" +
                    "Named route match: ${flag(arm.routeMatchesNamedInput)} · same as first: ${flag(arm.routeSameAsFirst)} · stable: ${flag(arm.routeStable)}")
                if (arm.statistics.frames == 0L) Text("No samples captured", color = MaterialTheme.colorScheme.onSurfaceVariant)
                SummaryRow("$tag-frames", "Frames: ${arm.statistics.frames} · nonzero: ${arm.statistics.nonzeroFrames}")
                arm.statistics.channels.take(2).forEachIndexed { channel, level ->
                    SummaryRow("$tag-channel-${channel + 1}", "Channel ${channel + 1}: ${level(level)}")
                }
                SummaryRow("$tag-sum", "Sum: ${level(arm.statistics.sum)}")
                SummaryRow("$tag-difference", "Difference: ${level(arm.statistics.difference)}")
                SummaryRow("$tag-mean", "Mean: ${level(arm.statistics.mean)}")
            }
        }
    }
}

@Composable
private fun SummaryRow(tag: String, value: String) {
    Text(value, Modifier.testTag(tag), style = MaterialTheme.typography.bodySmall,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
}

private fun capabilities(values: List<Int>, unconstrained: Boolean): String =
    if (unconstrained) "unconstrained" else values.take(16).joinToString().ifEmpty { "unknown" } +
        if (values.size > 16) " …" else ""

private fun shape(shape: MicrophoneDiagnosticShape?): String = shape?.let {
    "source ${it.source} · ${it.sampleRate} Hz · ${it.channelCount} ch · mask ${it.channelMask} · index ${it.channelIndexMask}"
} ?: "unknown"

private fun flag(value: Boolean?): String = when (value) { true -> "yes"; false -> "no"; null -> "unknown" }
private fun level(level: MicrophoneDiagnosticLevel?): String = level?.let {
    val rms = if (it.rms.isFinite()) String.format(java.util.Locale.ROOT, "%.5g", it.rms) else "unknown"
    "samples ${it.samples} · nonzero ${it.nonzero} · peak ${it.peak} · RMS $rms"
} ?: "unknown"

@Composable
private fun Confirmation(tag: String, text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange, Modifier.testTag(tag))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
