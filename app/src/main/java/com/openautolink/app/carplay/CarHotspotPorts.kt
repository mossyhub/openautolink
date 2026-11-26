// SPDX-License-Identifier: GPL-3.0-only
package com.openautolink.app.carplay

import com.openautolink.carplaylab.media.ReceiverPorts
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Port plan for wireless CarPlay over the car's own hotspot.
 *
 * Measured on a Chevrolet Blazer EV (GM AAOS) from a laptop joined to the car's hotspot. The
 * hotspot drops 64,502 of 65,535 TCP ports to the head unit without an answer. Let through and
 * free for an app: 7000, 5000, 5001, 6030, 6031, 1935, 30514, 30515 over IPv4, and only 7000,
 * 5001, 6030, 6031 over IPv6. 7000 and 5000 are the usual AirPlay ports. UDP is filtered on a
 * different list; the open datagram ports found over IPv4 were 5020, 6000, 6003, 6004, 6011.
 * Other GM models may differ.
 *
 * Audio: the iPhone stays connected to the car as a Bluetooth media device during a wireless
 * session, and the car's Bluetooth audio takes media focus from CarPlay audio about a second into
 * each stream. Turning off media audio for the iPhone in the car's Bluetooth settings fixes it,
 * but GM turns it back on at every entry and an app cannot change it (BLUETOOTH_PRIVILEGED). So
 * a hotspot session sends its audio over Bluetooth.
 */
object CarHotspotPorts {
    const val CONTROL = 7000
    internal val POOL_IPV4 = listOf(5000, 5001, 6030, 6031, 30514, 30515, 1935)
    internal val POOL_IPV6 = listOf(5001, 6030, 6031)
    internal val AUDIO_IPV4 = listOf(5020, 6000, 6003, 6004, 6011)

    /** "auto" on the car hotspot means IPv4: IPv6 does not have enough open ports for a session. */
    @JvmStatic
    fun family(saved: String, sharedWifi: Boolean): String = if (!sharedWifi && saved == "auto") "ipv4" else saved

    /** Called just before the receiver starts listening. Shared Wi-Fi clears the plan. */
    @JvmStatic
    fun apply(local: InetAddress, sharedWifi: Boolean, status: (String) -> Unit) {
        ReceiverPorts.onStatus = status
        ReceiverPorts.plan = if (sharedWifi) null else plan(local)
        status("CarPlay network stage=port-plan plan=${if (sharedWifi) "none" else "car-hotspot"} family=${if (local is Inet4Address) 4 else 6}")
    }

    internal fun plan(local: InetAddress): ReceiverPorts.Plan {
        val ipv4 = local is Inet4Address
        return ReceiverPorts.Plan(
            local = local,
            control = CONTROL,
            pool = if (ipv4) POOL_IPV4 else POOL_IPV6,
            audio = if (ipv4) AUDIO_IPV4 else POOL_IPV6,
            audioOverBluetooth = true,
        )
    }
}
