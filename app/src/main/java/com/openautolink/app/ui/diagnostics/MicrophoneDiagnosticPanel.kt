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
        Confirmation("mic-consent", "I consent to this bounded microphone test", controller.consent, controller::updateConsent)
        if (!controller.automotive) Confirmation("mic-stationary", "I confirm the vehicle is stationary", controller.stationary, controller::updateStationary)
        if (controller.busy) {
            Text(if (controller.state.phase == MicrophoneDiagnosticPhase.STOPPING)
                "Stopping · waiting for microphone release" else "${controller.state.phase.name.lowercase().replaceFirstChar { it.uppercase() }} · keep this panel open",
                style = MaterialTheme.typography.titleMedium)
            OutlinedButton(controller::cancel, enabled = controller.state.phase != MicrophoneDiagnosticPhase.STOPPING,
                modifier = Modifier.fillMaxWidth().testTag("mic-cancel")) { Text("Cancel") }
        }
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
