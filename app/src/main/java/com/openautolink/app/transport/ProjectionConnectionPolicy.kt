package com.openautolink.app.transport

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process preference admission, separate from cable and protocol ownership. Unknown is closed.
 * Revision tickets prevent an ON/OFF round trip from reviving an old wireless producer. */
class ProjectionConnectionPolicy {
    data class Snapshot(val loaded: Boolean = false, val wiredOnly: Boolean = false, val revision: Long = 0, val retirementRequired: Boolean = false) {
        val wirelessAllowed: Boolean get() = loaded && !wiredOnly && !retirementRequired
    }
    private val current = MutableStateFlow(Snapshot())
    val state = current.asStateFlow()
    val wirelessAllowed: Boolean get() = current.value.wirelessAllowed
    fun permits(transport: String): Boolean = transport == "usb" || wirelessAllowed
    fun wirelessTicket(): Long? = current.value.takeIf { it.wirelessAllowed }?.revision
    fun admits(ticket: Long?): Boolean = ticket != null && current.value.let { it.wirelessAllowed && it.revision == ticket }
    @Synchronized internal fun observe(wiredOnly: Boolean) {
        val old = current.value
        if (!old.loaded || old.wiredOnly != wiredOnly) current.value = Snapshot(true, wiredOnly, old.revision + 1, old.retirementRequired || wiredOnly)
    }
    @Synchronized fun retired(revision: Long) {
        if (current.value.revision == revision) current.value = current.value.copy(retirementRequired = false)
    }
    @Synchronized internal fun unavailable() {
        current.value = Snapshot(revision = current.value.revision + 1, retirementRequired = true)
    }
}
