package com.openautolink.app.announcements

import android.util.Log
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.openautolink.app.ui.components.OalAlertDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive

internal fun announcementDisplayId(view: android.view.View): Int? =
    if (view.isAttachedToWindow) view.display?.displayId else null

/** Only mounted by the normal Main projection root. Pending/failed metadata never gates its UI. */
@Composable
internal fun ReleaseAnnouncementHost(
    store: ReleaseAnnouncementStore?,
    versionCode: Int,
    unobstructed: Boolean,
    onOpenSettings: () -> Unit,
) {
    val announcement = remember(versionCode) { ReleaseAnnouncements.forVersion(versionCode) } ?: return
    if (store == null) return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val context = LocalContext.current
    val view = LocalView.current
    var displayId by remember(view) { mutableStateOf<Int?>(null) }
    DisposableEffect(view) {
        fun observeDisplay() { displayId = announcementDisplayId(view) }
        val attachment = object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: android.view.View) = observeDisplay()
            override fun onViewDetachedFromWindow(v: android.view.View) { displayId = null }
        }
        val originalObserver = view.viewTreeObserver
        val layout = android.view.ViewTreeObserver.OnGlobalLayoutListener { observeDisplay() }
        view.addOnAttachStateChangeListener(attachment)
        originalObserver.addOnGlobalLayoutListener(layout)
        observeDisplay()
        onDispose {
            view.removeOnAttachStateChangeListener(attachment)
            val currentObserver = view.viewTreeObserver
            if (currentObserver.isAlive) currentObserver.removeOnGlobalLayoutListener(layout)
            if (originalObserver !== currentObserver && originalObserver.isAlive)
                originalObserver.removeOnGlobalLayoutListener(layout)
        }
    }
    // LocalContext can legitimately be a nonvisual configuration/application wrapper. Only an
    // attached actual Compose View supplies display authority; missing authority stays closed.
    val normalDisplay = displayId == android.view.Display.DEFAULT_DISPLAY &&
        announcementDisplayId(view) == android.view.Display.DEFAULT_DISPLAY
    var finished by remember(store, announcement) { mutableStateOf(false) }
    var visible by remember(store, announcement) { mutableStateOf(false) }
    // The normal application already constructs this backend. get/hasAdmittedCall are read-only:
    // no account preparation, registration, service launch or call mutation occurs here.
    val backend = remember(context) { com.openautolink.carplaylab.telecom.ManagedTelecomExperiment.get(context.applicationContext) }
    var nativeCallActive by remember(backend) { mutableStateOf(backend.hasAdmittedCall) }
    LaunchedEffect(backend, lifecycleState, finished, unobstructed, visible) {
        nativeCallActive = backend.hasAdmittedCall
        // Never poll while metadata is pending, another root owns the dialog, or it is hidden.
        if (visible && !finished && unobstructed && lifecycleState == Lifecycle.State.RESUMED) {
            while (true) {
                kotlinx.coroutines.delay(250)
                nativeCallActive = backend.hasAdmittedCall
            }
        }
    }
    val eligible = !finished && unobstructed && !nativeCallActive && normalDisplay && lifecycleState == Lifecycle.State.RESUMED
    val owner = remember(store, announcement) { Any() }
    val openSettings by rememberUpdatedState(onOpenSettings)
    LaunchedEffect(store, announcement, eligible) {
        visible = false
        if (!eligible) return@LaunchedEffect
        try {
            val acknowledged = store.isAcknowledged(announcement)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (acknowledged) {
                finished = true
            } else if (!backend.hasAdmittedCall && store.claim(announcement, owner)) {
                visible = true
                try { awaitCancellation() }
                finally { store.release(announcement, owner); visible = false }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            // Optional startup metadata failing must not replace projection with a loading/error UI.
            finished = true
            Log.w("ReleaseAnnouncement", "Optional announcement read failed")
        }
    }
    if (visible && eligible) {
        val dismiss = {
            visible = false
            finished = true
            store.dismiss(announcement, owner)
            Unit
        }
        OalAlertDialog(
            modifier = Modifier.testTag("releaseAnnouncement"),
            onDismissRequest = dismiss,
            title = { Text(announcement.title) },
            text = { Text(announcement.body) },
            confirmButton = {
                TextButton(onClick = { dismiss(); openSettings() }, modifier = Modifier.heightIn(min = 48.dp).testTag("releaseAnnouncementOpenSettings")) {
                    Text("Open settings")
                }
            },
            dismissButton = {
                TextButton(onClick = dismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("releaseAnnouncementGotIt")) { Text("Got it") }
            },
        )
    }
}
