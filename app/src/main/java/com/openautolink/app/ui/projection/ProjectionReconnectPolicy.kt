package com.openautolink.app.ui.projection

/** Reconnect routing is separate from receiver ownership and preference persistence. */
internal fun dispatchSavedProjectionReconnect(
    carPlayEnabled: Boolean,
    carPlaySelected: Boolean,
    connectCarPlay: () -> Unit,
    reconnectCarPlay: () -> Unit,
    reconnectAndroidAuto: () -> Unit,
    returnToAndroidAuto: () -> Unit,
) {
    when {
        carPlayEnabled && carPlaySelected -> reconnectCarPlay()
        carPlayEnabled -> connectCarPlay()
        carPlaySelected -> returnToAndroidAuto()
        else -> reconnectAndroidAuto()
    }
}
