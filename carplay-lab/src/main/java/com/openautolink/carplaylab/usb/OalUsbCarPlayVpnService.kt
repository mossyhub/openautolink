// SPDX-License-Identifier: GPL-3.0-only
package com.openautolink.carplaylab.usb

import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor

/** Wired-only TUN. Does not replace owners or host a second AirPlay receiver. */
class OalUsbCarPlayVpnService : VpnService() {
    inner class LocalBinder : Binder() { val service get() = this@OalUsbCarPlayVpnService }
    private var owner: Any? = null
    private var descriptor: ParcelFileDescriptor? = null
    override fun onBind(intent: Intent?): IBinder = if (intent?.action == SERVICE_INTERFACE)
        super.onBind(intent)!! else LocalBinder()
    @Synchronized fun attach(token: Any, address: String): ParcelFileDescriptor {
        check(owner == null) { "USB VPN already owned" }
        require(java.net.InetAddress.getByName(address).isLinkLocalAddress)
        val fd = Builder().addAddress(address, 64).addRoute("fe80::", 64)
            .addAllowedApplication(packageName).setSession("OpenAutoLink USB")
            .setMtu(1500).setBlocking(true).establish() ?: error("USB VPN consent unavailable")
        descriptor = fd; owner = token
        return fd
    }
    @Synchronized fun detach(token: Any) {
        if (owner !== token) return
        descriptor?.close(); descriptor = null; owner = null
    }
    override fun onRevoke() { synchronized(this) { descriptor?.close(); descriptor=null; owner=null }; super.onRevoke() }
    override fun onDestroy() { onRevoke(); super.onDestroy() }
}
