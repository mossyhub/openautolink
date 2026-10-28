package com.openautolink.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.openautolink.app.media.OalMediaSessionManager

/** An explicit user action, independent of the OEM browser's availability. */
@Composable
internal fun CarPlayResumeAudio() {
    val context=LocalContext.current
    val manager=remember(context) { OalMediaSessionManager.getInstance(context) }
    var revision by remember(manager) { mutableIntStateOf(0) }
    DisposableEffect(manager) {
        val observer=Any()
        manager.observeBrowse(observer) { revision++ }
        onDispose { manager.removeBrowseObserver(observer) }
    }
    val resume=remember(manager,revision) { manager.captureCarPlayAudioResume() }
    Text("CarPlay · Playback",style=MaterialTheme.typography.titleMedium)
    Text("After returning from another source, resume music and navigation audio.",
        style=MaterialTheme.typography.bodyMedium)
    OutlinedButton(onClick={ resume?.invoke() },enabled=resume!=null,
        modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("resumeCarPlayAudio")) {
        Text("Resume audio")
    }
}
