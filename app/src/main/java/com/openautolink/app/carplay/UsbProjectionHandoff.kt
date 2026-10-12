package com.openautolink.app.carplay

import com.openautolink.app.transport.bluetooth.AaWirelessBtControl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Production Switch/Retry teardown, shared by both cable protocols. Caller serializes dispatch. */
internal class UsbProjectionHandoff(private val receipt: (Stage) -> Unit = {}) {
    enum class Stage { CLAIMED, ADVERTISER, CARPLAY, ANDROID_AUTO, RETIRED, ADMITTED, RESTORED, FAILED }
    private var lease: AaWirelessBtControl.UsbHandoffToken? = null
    private var fullyRetired = false
    private val _failure = MutableStateFlow<String?>(null)
    val failure = _failure.asStateFlow()
    private var stage = Stage.CLAIMED
    private fun record(next: Stage) { stage = next; receipt(next) }

    fun claim(generation: Long) {
        if (lease?.generation != generation) lease = AaWirelessBtControl.reserveUsbHandoff(generation)
        fullyRetired = false
        _failure.value = null
        record(Stage.CLAIMED)
    }

    suspend fun retire(releaseCarPlay: suspend () -> Unit, stopAndroidAuto: suspend () -> Unit) {
        fullyRetired = false
        lease?.let {
            record(Stage.ADVERTISER)
            AaWirelessBtControl.retireForUsb(it)
        }
        record(Stage.CARPLAY)
        releaseCarPlay()
        record(Stage.ANDROID_AUTO)
        stopAndroidAuto()
        fullyRetired = true
        record(Stage.RETIRED)
    }

    fun admitted() { _failure.value = null; record(Stage.ADMITTED) }
    fun failed() {
        // No exception messages, phone identifiers, network values or authentication data.
        _failure.value = "USB switch could not finish stopping projection (${stage.name.lowercase()}). " +
            "Tap Retry USB to try stopping it again. If it still fails, unplug the phone and retry."
        record(Stage.FAILED)
    }
    fun dismissFailure() { _failure.value = null }
    fun release() {
        check(fullyRetired) { "USB handoff retirement not acknowledged" }
        lease?.let { check(AaWirelessBtControl.releaseUsbHandoff(it)) { "USB handoff superseded" } }
        lease = null
        _failure.value = null
        record(Stage.RESTORED)
    }
}
