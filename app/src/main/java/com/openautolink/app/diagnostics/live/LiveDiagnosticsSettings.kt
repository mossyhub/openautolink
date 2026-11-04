package com.openautolink.app.diagnostics.live

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.*
import com.openautolink.app.ui.settings.SettingsGroup
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun LiveDiagnosticsSettings() {
    val context=LocalContext.current
    val status by LiveDiagnostics.status.collectAsStateWithLifecycle()
    val autoConnect by LiveDiagnostics.autoConnect.collectAsStateWithLifecycle()
    val endpoint by LiveDiagnostics.endpoint.collectAsStateWithLifecycle()
    val ready by LiveDiagnostics.ready.collectAsStateWithLifecycle()
    val busy by LiveDiagnostics.busy.collectAsStateWithLifecycle()
    var server by remember(endpoint,ready) { mutableStateOf(endpoint.base) }
    val parsed=remember(server){runCatching{LiveEndpoint.parse(server)}.getOrNull()}
    val changed=parsed!=endpoint
    var code by remember { mutableStateOf("") } // never saved across Activity/process restoration
    var showCode by remember { mutableStateOf(false) }
    LaunchedEffect(endpoint){code="";showCode=false}
    SettingsGroup {
        Text("Owner live diagnostics",style=MaterialTheme.typography.titleMedium)
        Text("Off by default. Configure while parked. Sends read-only current display/session facts and ALL app diagnostic lines while enabled; also tees existing own-process logcat capture when it is already running. No screen/control commands, location or audio payloads.")
    }

    SettingsGroup {
        OutlinedTextField(value=server,onValueChange={if(it.length<=2048)server=it},
            enabled=ready && !busy,label={Text("Diagnostics server URL")},singleLine=true,
            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri),isError=parsed==null,
            supportingText={Text(if(parsed==null)"Enter an HTTPS URL without user info, query or fragment." else "Include the base path, for example https://server.example/oal.")},
            modifier=Modifier.fillMaxWidth().testTag("liveDiagnosticsServer"))
        OutlinedButton(onClick={code="";showCode=false;LiveDiagnostics.applyEndpoint(context,server)},
            enabled=ready && !busy && parsed!=null && changed,modifier=Modifier.testTag("liveDiagnosticsApplyServer")){Text("Apply server")}
        Text("Applying stops diagnostics and turns off auto-connect. Pairings stay encrypted per server; Enable uses only this server's pairing. Editing alone changes nothing. Stop and Revoke use the applied server.")
    }

    SettingsGroup {
        Row(Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("liveDiagnosticsAutoConnect")
            .toggleable(value=autoConnect,enabled=ready && !busy && !changed,role=Role.Switch,onValueChange={LiveDiagnostics.updateAutoConnect(context,it)}),
            verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            Text("Auto-connect on OAL launch",Modifier.weight(1f))
            Switch(checked=autoConnect,onCheckedChange=null)
        }
        Text("Uses this server’s remembered pairing when OAL opens. Custom HTTPS servers can use a local network without internet. Does not launch OAL when it is closed.")
        Text("Stop pauses this run only. Turn off auto-connect to stay Off after the next launch.")
    }

    SettingsGroup {
        Text("Secrets/coordinate/identity-bearing log lines are redacted. Full live delivery is best effort: 512 records/2 MiB RAM backlog, max16 KiB/message, max64 KiB JSON/second; overflow/oversize/contended records are counted, not silently sampled. Server retains at most512 records/2 MiB and one hour idle. No historical file backfill; LogUploader is unchanged.")
        Text("Status: $status",modifier=Modifier.testTag("liveDiagnosticsStatus"))
    }

    SettingsGroup {
        OutlinedTextField(value=code,onValueChange={if(it.length<=160){code=it;if(it.isEmpty())showCode=false}},label={Text("Five-word owner invitation (first pairing only)")},
            enabled=ready && !busy && !changed,
            visualTransformation=if(showCode)VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),
            trailingIcon={IconButton(onClick={showCode=!showCode},enabled=ready && !busy && !changed,
                modifier=Modifier.size(48.dp).testTag("liveDiagnosticsShowInvitation")){
                Icon(if(showCode)Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription=if(showCode)"Hide invitation" else "Show invitation")}},
            singleLine=true,modifier=Modifier.fillMaxWidth().testTag("liveDiagnosticsPairCode"))
        Button(onClick={val invitation=code;code="";showCode=false;LiveDiagnostics.enable(context,invitation)},
            enabled=ready && !busy && !changed,modifier=Modifier.testTag("liveDiagnosticsEnable")){Text("Enable read-only")}
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick={code="";showCode=false;LiveDiagnostics.stop()},modifier=Modifier.testTag("liveDiagnosticsStop")){Text("Stop")}
            OutlinedButton(onClick={code="";showCode=false;LiveDiagnostics.revoke(context)},enabled=ready && !busy,modifier=Modifier.testTag("liveDiagnosticsRevoke")){Text("Revoke owner")}
        }
        Text("Pairing is stored encrypted on this device. Stop clears the local backlog; Revoke also invalidates server access. FIRST_PRESENT is software draw/swap, not proof of a visible vehicle screen.")
    }

    Spacer(Modifier.height(16.dp))
}
