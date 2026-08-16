package com.openautolink.app.transport.bluetooth

/**
 * Defines whether the Bluetooth SDP advertiser can still accept a phone.
 *
 * A start flag alone is insufficient: the Bluetooth socket can die while the
 * object survives across an ignition cycle. In that case the accept-loop job is
 * complete and the SDP record is no longer live even if teardown never cleared
 * the old flag.
 */
internal object AdvertiserLiveness {
    fun isLive(startRequested: Boolean, acceptLoopActive: Boolean): Boolean =
        startRequested && acceptLoopActive

    fun shouldRecoverAfterExit(startRequested: Boolean, socketDied: Boolean): Boolean =
        startRequested && socketDied
}
