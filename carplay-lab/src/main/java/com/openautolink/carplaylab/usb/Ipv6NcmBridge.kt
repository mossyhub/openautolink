// SPDX-License-Identifier: GPL-3.0-only
// Adapted from shilapi/DiPlay Ipv6NcmBridge; see UPSTREAM-LICENSE.
package com.openautolink.carplaylab.usb
import android.os.ParcelFileDescriptor
import com.shilapi.xcertplay.transport.*
import java.io.*
import java.util.concurrent.atomic.AtomicBoolean

class Ipv6NcmBridge(private val ncm: NcmUsbBridge, private val tun: ParcelFileDescriptor,
    private val hostMac: ByteArray, private val onError: () -> Unit) : AutoCloseable {
 private val running=AtomicBoolean(false)
 @Volatile private var peerMac: ByteArray?=null
 private val threads=mutableListOf<Thread>()
 fun start() {
  check(running.compareAndSet(false,true))
  fun worker(name:String, action:()->Unit) { threads += Thread({
    try { action() } catch (_:Exception) { if(running.get()) onError() }
   },name).apply { isDaemon=true; start() } }
  worker("oal-usb-ipv6-in") {
   val output=FileOutputStream(tun.fileDescriptor)
   while(running.get()) {
    val frame=ncm.recv(1000) ?: continue
    val view=EthernetIpv6Codec.parseIpv6View(frame) ?: continue
    val packet=frame.copyOfRange(view.payloadOffset,view.payloadOffset+view.payloadLength)
    if(!linkLocal(packet)) continue
    peerMac=view.sourceMac
    output.write(packet)
   }
  }
  worker("oal-usb-ipv6-out") {
   val input=FileInputStream(tun.fileDescriptor); val buffer=ByteArray(4096)
   while(running.get()) {
    val count=input.read(buffer); if(count<0) throw EOFException()
    if(count==0) { Thread.sleep(1); continue }
    val packet=buffer.copyOf(count); if(!linkLocal(packet)) continue
    val ipv6=EthernetIpv6Codec.addNeighborAdvertisementTargetMac(packet,hostMac)
    val mac=EthernetIpv6Codec.multicastDestinationMac(ipv6) ?: peerMac ?: continue
    ncm.send(EthernetIpv6Codec.build(hostMac,mac,ipv6),2000)
   }
  }
 }
 override fun close() {
  running.set(false); ncm.close(); tun.close()
  threads.filter { it!==Thread.currentThread() }.forEach { it.join(3000); check(!it.isAlive) }
 }
 companion object {
  fun linkLocal(packet:ByteArray):Boolean {
   if(packet.size<40 || (packet[0].toInt() ushr 4 and 15)!=6) return false
   fun local(offset:Int)=packet[offset]==0xfe.toByte() && (packet[offset+1].toInt() and 0xc0)==0x80
   fun multicast(offset:Int)=packet[offset]==0xff.toByte() && (packet[offset+1].toInt() and 15)==2
   fun unspecified(offset:Int)=(offset until offset+16).all { packet[it]==0.toByte() }
   return (local(8) || unspecified(8)) && (local(24) || multicast(24))
  }
 }
}
