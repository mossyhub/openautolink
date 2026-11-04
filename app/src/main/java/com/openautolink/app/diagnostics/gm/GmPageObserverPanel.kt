package com.openautolink.app.diagnostics.gm

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.openautolink.app.secondary.PageReturnDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable
internal fun GmPageObserverPanel() {
    val context=LocalContext.current.applicationContext
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val scope=rememberCoroutineScope()
    var state by remember{mutableStateOf<GmObserverState?>(null)}
    var qualified by remember{mutableStateOf(false)}
    var busy by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf(false)}
    var age by remember{mutableStateOf<Long?>(null)}
    LaunchedEffect(context) {
        var previous=-1L
        while(true) {
            val next=withContext(Dispatchers.IO){GmObserverStore(context).read() to GmObserverGate.qualified(context)}
            state=next.first;qualified=next.second
            age=if(next.first.lastWall>0)((System.currentTimeMillis()-next.first.lastWall).coerceAtLeast(0)/1000)else null
            if(next.first.received!=previous && next.first.received>0) {
                // Cross-process bridge copies only the immutable bounded aggregate into the
                // existing nonblocking page diagnostics sink. No intent/payload is retained.
                PageReturnDiagnostics.tryOffer("gmPageObserver receipts=${next.first.received} ignored=${next.first.ignored} lastReceiptWallMs=${next.first.lastWall} selected=UNKNOWN ownership=NOT_ESTABLISHED senderScope=FRAMEWORK_SIGNATURE_GATE_PRIVILEGED_CLASS")
            }
            previous=next.first.received
            delay(1000)
        }
    }
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Observe GM cluster page events (experimental)")
        Row(Modifier.fillMaxWidth()) {
            Text("Page monitoring / guarded direct-video return",Modifier.weight(1f))
            Switch(checked=state?.enabled==true && qualified,enabled=state!=null && !busy,
                modifier=Modifier.testTag("gmObserverEnabled"),onCheckedChange={enabled->
                    // No saved/stale arm callback: only an explicit current foreground UI action.
                    if(!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))return@Switch
                    busy=true
                    scope.launch {
                        error=!withContext(Dispatchers.IO){GmObserverDiagnostics.setEnabled(context,enabled){
                            scope.isActive && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                        }}
                        val next=withContext(Dispatchers.IO){GmObserverStore(context).read() to GmObserverGate.qualified(context)}
                        state=next.first;qualified=next.second;busy=false
                    }
                })
        }
        val current=state
        Text(when {
            error->"Change unconfirmed; retry. Saved intent and actual component are checked separately."
            current==null->"Checking monitoring configuration"
            !current.available->"Monitoring configuration busy or unavailable; no readiness inferred"
            !current.enabled->"Off"
            !qualified->"Not monitoring — signature/component configuration unavailable"
            current.received==0L->"Monitoring enabled; waiting for vehicle discovery"
            else->"Action receipts: ${current.received}; last receipt ${age ?: 0}s ago"
        },Modifier.testTag("gmObserverStatus"))
        Text("Ignored: ${current?.ignored ?: 0}; queued observations dropped at last write: ${current?.dropped ?: 0}")
        Text("Monitoring enabled by default. Not required for normal projection. Natural vehicle discovery and Android background delivery are unverified; enabling cannot refresh the vehicle's cached membership.")
        Text("Action receipts are not ownership. With explicit direct video active, a fully decoded matching away/return may request the existing own task once, only after successful GPU parking. Missing vendor APIs, stale events, saved Off or Icons-only suppress the attempt. No reconnect, new task or decoder restart. Signature protection admits a privileged/trusted-signer class, not uniquely GM.")
    }
}
