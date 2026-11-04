package com.openautolink.app.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.openautolink.app.cluster.ClusterDisplayMode
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.secondary.*
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

internal data class DriverVideoRestoreControls(
    val label:String = "Restore driver display video",
    val action:(() -> Unit)? = null,
    val pending:Boolean = false,
    val message:String? = null,
)

/** Existing projection menu only. Observation never launches/restores a window. */
@Composable
internal fun rememberProductionDriverVideoRestore():DriverVideoRestoreControls? {
    val context=LocalContext.current.applicationContext
    val runtime=SecondaryOutputRuntime.peekForDiagnostics() ?: return null
    val preferences=remember(context) { AppPreferences.getInstance(context) }
    val savedFlow=remember(preferences) {
        preferences.secondaryDisplayPreferences.map { it as SecondaryPreferencesSnapshot? }.catch { emit(null) }
    }
    val saved by savedFlow.collectAsState(initial=null)
    val statuses by runtime.statuses.collectAsState()
    val restoreGeneration by runtime.restoreGenerations.collectAsState()
    SideEffect { runtime.updateRestorePolicyGeneration(saved?.takeIf { it.error==null }?.revision) }
    val video=saved?.takeIf { it.error==null && it.legacyCluster.selection.mode==ClusterDisplayMode.MAP_VIDEO }
    val eligible=statuses.values.singleOrNull { it.advertised && it.phoneSelected && video?.outputs?.get(it.id)?.enabled==true }?.id
    val failed=video!=null && statuses.values.any { video.outputs[it.id]?.enabled==true && !it.advertised &&
        it.reason in setOf(SecondaryReason.HOST_UNAVAILABLE,SecondaryReason.CODEC_UNAVAILABLE,SecondaryReason.RETIREMENT_PENDING) }
    val scope=rememberCoroutineScope()
    var pending by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var request by remember { mutableStateOf<CompletableFuture<DriverVideoRestoreResult>?>(null) }
    DisposableEffect(runtime,eligible,saved?.revision,restoreGeneration) {
        onDispose { request?.cancel(false);request=null;pending=false;message=null }
    }
    if(eligible==null)return if(failed) DriverVideoRestoreControls(label="Reconnect required to enable driver display") else null
    return DriverVideoRestoreControls(action={
        if(!pending) {
            pending=true;message=null
            val exact=runtime.restoreDriverDisplayVideo();request=exact
            scope.launch {
                try {
                    val outcome=SecondaryOutputRuntime.await(exact)
                    if(request===exact)message=when(outcome) {
                        DriverVideoRestoreResult.ATTACHED -> "Driver display window attached"
                        DriverVideoRestoreResult.BLOCKED -> "Restore unavailable — current video window not ready"
                        DriverVideoRestoreResult.TIMED_OUT -> "No window acknowledgement — select Maps, then try again"
                    }
                } catch(cancelled:CancellationException) { exact.cancel(false);throw cancelled }
                catch(_:Exception) { if(request===exact)message="Restore unavailable — current video window not ready" }
                finally { if(request===exact) { request=null;pending=false } }
            }
        }
    },pending=pending,message=if(pending) "Restoring driver display window…" else message)
}
