package com.openautolink.app.carplay

/** Overlay visibility changes input admission only, never projection/Surface ownership. */
object CarPlayOverlayPolicy {
    fun forwardTouch(showSettings: Boolean, showDiagnostics: Boolean): Boolean =
        !showSettings && !showDiagnostics
}
