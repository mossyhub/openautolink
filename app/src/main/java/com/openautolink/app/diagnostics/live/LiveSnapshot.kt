package com.openautolink.app.diagnostics.live

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import com.openautolink.app.session.SessionManager
import com.openautolink.app.secondary.SecondaryOutputRuntime
import org.json.JSONArray
import org.json.JSONObject

/** Actual in-process producer values only. Never creates SessionManager/optional host or reads VHAL/GPS. */
internal object LiveSnapshot {
    fun read(context: Context, savedRevision: Long): JSONObject {
        val session = SessionManager.instanceOrNull()
        val runtime = SecondaryOutputRuntime.peekForDiagnostics()
        val outputs = JSONArray()
        runtime?.statuses?.value?.values?.take(8)?.forEachIndexed { index, s ->
            outputs.put(JSONObject().put("id",index).put("reason",s.reason.name)
                .put("advertised",s.advertised).put("selected",s.phoneSelected).put("attached",s.hostAttached)
                .put("received",s.received).put("decoded",s.decoded).put("presented",s.presented).put("dropped",s.dropped))
        }
        val displays = JSONArray()
        runCatching { context.getSystemService(DisplayManager::class.java)?.displays?.take(16)?.forEach { d ->
            val mode = d.mode
            displays.put(JSONObject().put("id",d.displayId).put("valid",d.isValid).put("on",d.state==Display.STATE_ON)
                .put("width",mode.physicalWidth).put("height",mode.physicalHeight).put("rotation",d.rotation))
        } }
        val root = JSONObject().put("state",session?.sessionState?.value?.name ?: "unavailable")
            .put("activeEpoch",session?.settingsSessionEpoch?.value ?: 0L)
            .put("savedRevision",savedRevision).put("outputs",outputs).put("displays",displays)
            .put("oemVisibility","unknown").put("receipts",LiveLogSink.snapshotReceipts())
            .put("gmPageObserver",com.openautolink.app.diagnostics.gm.GmObserverDiagnostics.snapshot(context))
        runtime?.liveHostedDisplay?.value?.host?.let { h ->
            root.put("host",JSONObject().put("id",h.displayId).put("canvasWidth",h.surfaceSize.width).put("canvasHeight",h.surfaceSize.height)
                .put("visibleWidth",h.visible.width).put("visibleHeight",h.visible.height)
                .put("stable",JSONObject().put("width",h.stable.width).put("height",h.stable.height)))
        }
        // Save outcome/GPU retirement and full geometry receipts remain exact timestamped log
        // records, not guessed from a UI selection or promoted to OEM visibility proof.
        return root
    }
}
