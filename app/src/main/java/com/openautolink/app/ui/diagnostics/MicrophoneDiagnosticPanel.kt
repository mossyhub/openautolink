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
import com.shilapi.xcertplay.media.CarPlayMicrophoneQualificationHistory
import com.shilapi.xcertplay.media.CarPlayMicrophoneSpeechCapture

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
        Text("Stop projection before local tests. Disconnect phone Bluetooth yourself; this panel never changes Bluetooth or routing.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Numeric levels only; no audio saved, played or sent on projection.", style = MaterialTheme.typography.bodySmall)
        Text("Up to 12 completed numeric comparisons are retained for manual Upload Logs; ordinary upload age/size limits apply.", style = MaterialTheme.typography.bodySmall)
        Text("Local capture", style = MaterialTheme.typography.titleLarge)
        if (controller.busy) {
            Text(if (controller.parkedBusy) controller.parkedStatus else if (controller.state.phase == MicrophoneDiagnosticPhase.STOPPING)
                "Stopping · waiting for microphone release" else "${controller.state.phase.name.lowercase().replaceFirstChar { it.uppercase() }} · keep this panel open",
                style = MaterialTheme.typography.titleMedium)
            OutlinedButton(controller::cancel, enabled = controller.state.phase != MicrophoneDiagnosticPhase.STOPPING,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("mic-cancel")) { Text("Cancel") }
        }
        ParkedNativeComparisonControls(controller)
        CustomCaptureControls(controller)
        OutlinedButton(controller::inspect, enabled = controller.canStart,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("mic-inspect")) { Text("Inspect inputs · no capture") }
        val comparisons = listOf(
            MicrophoneDiagnosticComparison.CHANNELS to "Compare channels · mono → 2ch → mono (24 kHz)",
            MicrophoneDiagnosticComparison.RATE to "Compare rate · 24 → 48 → 24 kHz (mono)",
            MicrophoneDiagnosticComparison.PREFERRED to "Compare input · default → named → default (24 kHz mono)",
            MicrophoneDiagnosticComparison.SOURCES to "Optional sources · recognition (6) → communication (7) → recognition (6) · 24 kHz mono",
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
        CarPlayPlayingPublicationControls(controller)
        LiveExperimentControls(controller)
    }
}

@Composable
private fun ParkedNativeComparisonControls(controller: MicrophoneDiagnosticController) {
    Text("Java / native AAudio comparison", style=MaterialTheme.typography.titleLarge)
    Text("Test with phone Bluetooth disconnected. Numeric levels only; no audio saved.")
    Text("Two sequential 2-second measurements · recognition · 48 kHz · two channels. Permission, AppOps, call mode and microphone privacy still apply. MMAP unknown.", style=MaterialTheme.typography.bodySmall)
    OutlinedButton(controller::stopProjectionForDiagnostic, enabled=!controller.busy,
        modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("mic-parked-stop-projection")) { Text("Stop projection for diagnostic") }
    Text(controller.parkedProjectionStatus, Modifier.testTag("mic-parked-projection-status"))
    Button(controller::startParked, enabled=controller.canStartParked,
        modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("mic-parked-compare")) { Text("Run comparison · 2 seconds each") }
    Text(controller.parkedStatus, Modifier.testTag("mic-parked-status"))
    controller.parkedResults.forEach { values ->
        if(values.size==32) {
            Text(if(values[0]==0L) "Java" else "AAudio",style=MaterialTheme.typography.titleMedium)
            Text("termination ${values[1]} · error ${values[2]} · stopError ${values[3]} · closeError ${values[4]} · restart ${values[5]} · joined ${values[6]} · close ${values[7]}")
            Text("actual preset ${values[8]} · rate ${values[9]} · channels ${values[10]} · format ${values[11]} · sharing ${values[12]} · performance ${values[13]} · mask ${values[14]} · session topology ${values[15]} · privacy ${values[16]} · stable ${values[17]} · MMAP UNKNOWN")
            Text(if(values[19]==0L) "No positive frames read · not measured silence" else "Positive frames ${values[19]} · zero-valued samples are counted; not proof of speech")
            for(i in listOf(21,26)) Text("Channel ${if(i==21)1 else 2}: samples ${values[i]} · nonzero ${values[i+1]} · peak ${values[i+2]} · squares ${values[i+3]} · clipped ${values[i+4]}")
        }
    }
    OutlinedButton(controller::returnToProjection,
        modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("mic-parked-return")) { Text("Return to projection") }
    Text("Return waits for close + join. Failed close requires app restart. Projection stays paused until Return.",style=MaterialTheme.typography.bodySmall)
    HorizontalDivider()
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
    val aaStyle = status.profile?.aaStyle == true
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Use Android Auto capture path (experimental)", Modifier.weight(1f))
        Switch(checked = aaStyle, enabled = aaStyle || (!status.armed && controller.canArmLive),
            onCheckedChange = { if (it) controller.armAaStyle() else controller.disarmLive() },
            modifier = Modifier.testTag("mic-aa-style"))
    }
    Text(if (status.armed && !aaStyle) "AA-style unavailable: turn the existing manual/independent profile Off first."
        else if (aaStyle) "AA-style acquisition selected. Turn Off before selecting a different profile."
        else "Default off. Classic recognition 16 kHz mono, AA post-start effects. CarPlay wire format remains negotiated; not the AA protocol pipeline.", style = MaterialTheme.typography.bodySmall)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Live microphone profile")
        Switch(checked = status.armed, enabled = status.armed || controller.canArmLive,
            onCheckedChange = { if (it) controller.armLive() else controller.disarmLive() },
            modifier = Modifier.testTag("mic-live-enabled"))
    }
    Text(if (status.armed) "On · until Off or app restart" else "OFF · normal CarPlay defaults",
        Modifier.testTag("mic-live-status"), style = MaterialTheme.typography.titleMedium)
    Text("Apply on reconnect / new recorder only. An active recorder keeps its original profile.",
        style = MaterialTheme.typography.bodySmall)
    status.profile?.let {
        Text(it.independent?.let { selected -> "Selected independent profile: history ${selected.history} · Siri capture ${selected.speechCapture} · genuine AUTO offers" }
            ?: "Selected request: ${it.source ?: "automatic"} · ${it.rate} · ${it.channels} · ${it.selection} · codec preference ${it.codec}",
            style = MaterialTheme.typography.bodySmall)
    }
    val profile = controller.liveProfile
    val enabled = !controller.busy && !aaStyle
    val independent = controller.independentSelection
    Text("Independent Siri lab · history and live capture", style = MaterialTheme.typography.titleMedium)
    Choices("Qualification history bundle · genuine order / offers",
        listOf(CarPlayMicrophoneQualificationHistory.NORMAL to "Normal · five probes",
            CarPlayMicrophoneQualificationHistory.MANUAL_VR48_TWO to "Manual VR48/two · nine probes"),
        independent.history, enabled, { "mic-independent-history-${it.name}" }) {
        controller.updateIndependentSelection(independent.copy(history = it))
    }
    Choices("Live Siri rate + channels + conversion package · recognition (6)",
        listOf(CarPlayMicrophoneSpeechCapture.VR24_MONO to "VR24 mono", CarPlayMicrophoneSpeechCapture.VR48_TWO to "VR48 two · average"),
        independent.speechCapture, enabled, { "mic-independent-live-${it.name}" }) {
        controller.updateIndependentSelection(independent.copy(speechCapture = it))
    }
    Text("History and live capture are separate diagnostic choices. Compare one choice at a time; unsupported combinations are withheld.", style = MaterialTheme.typography.bodySmall)
    Text(if (controller.independentSupported) "Actual qualification and negotiated wire must admit this selection; unsupported capture/wire is rejected without fallback. Phone chooses codec; no forced offers."
        else "Unsupported: manual VR48/two history never qualifies VR24 mono. Choose normal history or VR48 two; no extra recorder will be opened.",
        Modifier.testTag("mic-independent-admission"), style = MaterialTheme.typography.bodySmall)
    Text("Stop and await recorder/receiver retirement before applying a new profile. Nonzero levels do not prove understood speech.", style = MaterialTheme.typography.bodySmall)
    Button(controller::armIndependentLive, enabled = controller.canArmIndependent && !aaStyle,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("mic-independent-arm")) {
        Text("Apply independent profile · reconnect")
    }
    HorizontalDivider()
    Text("Legacy coupled manual profile · qualification and live capture together", style = MaterialTheme.typography.titleMedium)
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
    Text("Phone selects the codec from qualified offers. Restricting offers may prevent connection. Off + reconnect restores defaults.",
        Modifier.testTag("mic-live-codec-note"), style = MaterialTheme.typography.bodySmall)
    Text("Actual negotiated codec is in manual Upload Logs. Caller audio and echo remain unverified.",
        style = MaterialTheme.typography.bodySmall)
    Button(controller::armLive, enabled = controller.canArmLive && !aaStyle,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("mic-live-arm")) { Text("Apply profile · reconnect") }
    OutlinedButton(controller::disarmLive,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("mic-live-disarm")) { Text("Off · future recorders only") }
    OutlinedButton(controller::resetLive, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("mic-live-reset")) { Text("Reset profile and turn off") }
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
