package com.openautolink.app.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.testTag
import com.openautolink.app.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Passive, local-only support UI. Opening/closing never applies settings or retires projection. */
@Composable
fun SupportOpenAutoLink(modifier: Modifier = Modifier) {
    // Deliberately not saved: leaving this page/settings does not reopen a support prompt.
    var showQr by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth().testTag("supportOpenAutoLink"),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        BoxWithConstraints(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (maxWidth >= 480.dp) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SupportTitle(Modifier.weight(1f))
                    FilledTonalButton(onClick = { showQr = true }, modifier = Modifier.testTag("supportScan")) {
                        Text("Scan with your phone")
                    }
                }
            } else {
                Column {
                    SupportTitle()
                    TextButton(onClick = { showQr = true }, modifier = Modifier.testTag("supportScan")) {
                        Text("Scan with your phone")
                    }
                }
            }
        }
    }
    if (showQr) {
        Dialog(
            onDismissRequest = { showQr = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            SupportQrDialog(onClose = { showQr = false })
        }
    }
}

@Composable
private fun SupportTitle(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.LocalCafe, contentDescription = null, modifier = Modifier.size(20.dp))
        Text("Support OpenAutoLink", style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun SupportQrDialog(onClose: () -> Unit) {
    Surface(
        modifier = Modifier.padding(24.dp).widthIn(max = 760.dp).fillMaxWidth().heightIn(max = 560.dp)
            .testTag("supportQrDialog"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
    ) {
        BoxWithConstraints(modifier = Modifier.padding(24.dp)) {
            val wide = maxWidth >= 560.dp
            val qrSize = minOf(
                if (wide) 320.dp else 280.dp,
                maxWidth,
                (maxHeight - if (wide) 80.dp else 180.dp).coerceAtLeast(160.dp),
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Close stays reachable even on short screens or at large accessibility text sizes.
                Column(
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (wide) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SupportQrImage(qrSize)
                            SupportQrCopy(Modifier.weight(1f))
                        }
                    } else {
                        SupportQrCopy(centered = true)
                        SupportQrImage(qrSize)
                    }
                }
                TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End).testTag("supportClose")) {
                    Text("Close")
                }
            }
        }
    }
}

@Composable
private fun SupportQrCopy(modifier: Modifier = Modifier, centered: Boolean = false) {
    Column(
        modifier = modifier.padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        val alignment = if (centered) TextAlign.Center else TextAlign.Start
        Text("Support OpenAutoLink", style = MaterialTheme.typography.headlineSmall, textAlign = alignment)
        Text("Scan with your phone", style = MaterialTheme.typography.bodyLarge, textAlign = alignment)
        Text("buymeacoffee.com/mossyhub", style = MaterialTheme.typography.bodyLarge, textAlign = alignment)
        Text("Scan while parked", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = alignment)
    }
}

@Composable
private fun SupportQrImage(size: Dp) {
    // QR version 3, M correction; the asset includes a four-module white quiet zone.
    // Never tint, crop or round the QR, including in dark themes.
    Image(
        bitmap = ImageBitmap.imageResource(R.drawable.support_open_auto_link_qr),
        contentDescription = "QR code for https://buymeacoffee.com/mossyhub",
        modifier = Modifier.size(size).background(Color.White).testTag("supportQrImage"),
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.None,
    )
}
