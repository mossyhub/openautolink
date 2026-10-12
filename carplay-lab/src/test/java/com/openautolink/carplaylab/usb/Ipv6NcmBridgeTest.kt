// SPDX-License-Identifier: GPL-3.0-only
package com.openautolink.carplaylab.usb
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
class Ipv6NcmBridgeTest {
 private fun packet(src:String,dst:String)=ByteArray(40).apply {
  this[0]=0x60; InetAddress.getByName(src).address.copyInto(this,8)
  InetAddress.getByName(dst).address.copyInto(this,24)
 }
 @Test fun onlyLocalLinkAndScopedDiscoveryCrossNcm() {
  assertTrue(Ipv6NcmBridge.linkLocal(packet("fe80::1","fe80::2")))
  assertTrue(Ipv6NcmBridge.linkLocal(packet("::","ff02::1:ff00:2")))
  assertFalse(Ipv6NcmBridge.linkLocal(packet("2001:db8::1","fe80::2")))
  assertFalse(Ipv6NcmBridge.linkLocal(packet("fe80::1","2001:db8::2")))
  assertFalse(Ipv6NcmBridge.linkLocal(packet("fe80::1","ff05::1")))
  assertFalse(Ipv6NcmBridge.linkLocal(ByteArray(39)))
 }
}
