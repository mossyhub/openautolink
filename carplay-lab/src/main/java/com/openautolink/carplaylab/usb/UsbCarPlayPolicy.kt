// SPDX-License-Identifier: GPL-3.0-only
package com.openautolink.carplaylab.usb

object UsbCarPlayPolicy {
    fun isCandidate(vendor: Int, interfaces: List<Triple<Int, Int, Int>>): Boolean = vendor == 0x05ac && Triple(255, 254, 2) in interfaces
    fun acceptReplacement(selected: String, candidate: String, detached: Boolean, elapsedMs: Long) = detached && elapsedMs in 0..15_000 && selected.isNotBlank() && selected == candidate
}