package com.openautolink.carplaylab.media

import android.view.Surface
import com.openautolink.carplaylab.pairing.PairingPersistence
import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.media.CarPlayVideoSelection
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.*
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.os.ParcelFileDescriptor
import com.openautolink.carplaylab.usb.Ipv6NcmBridge
import com.shilapi.xcertplay.transport.NcmUsbBridge
import com.shilapi.xcertplay.transport.EthernetIpv6Codec
import org.mockito.ArgumentMatchers.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32], manifest=Config.NONE, instrumentedPackages=["com.openautolink.carplaylab.media"])
class ManualBluetoothReceiverTest {
    private fun forwardedNetworkBytes(): List<ByteArray> {
        val blocks=mutableListOf<ByteArray>()
        val connection=mock(UsbDeviceConnection::class.java)
        `when`(connection.bulkTransfer(any(UsbEndpoint::class.java),any(ByteArray::class.java),anyInt(),anyInt())).thenAnswer {
            blocks += it.getArgument<ByteArray>(1).copyOf(); it.getArgument<Int>(2)
        }
        val ncm=NcmUsbBridge(connection,mock(UsbEndpoint::class.java),mock(UsbEndpoint::class.java),null,emptyList(),null,{})
        val host=byteArrayOf(2,0,0,0,0,17);val peer=byteArrayOf(2,5,4,3,2,1)
        val bridge=Ipv6NcmBridge(ncm,mock(ParcelFileDescriptor::class.java),host) {}
        fun packet(multicast:Boolean)=ByteArray(64).apply {
            this[0]=0x60;this[5]=24;this[6]=58;this[40]=136.toByte()
            InetAddress.getByName("fe80::ff:fe00:11").address.copyInto(this,8)
            InetAddress.getByName(if(multicast) "ff02::1" else "fe80::2").address.copyInto(this,24)
        }
        try {
            bridge.forwardOutbound(packet(true))
            bridge.forwardInbound(EthernetIpv6Codec.build(peer,host,packet(false)),ByteArrayOutputStream())
            bridge.forwardOutbound(packet(false))
            return blocks
        } finally { ncm.close() }
    }
    @Test fun actualSerializedInfoSeparatesBluetoothFromRetainedDeviceIdentityAndTrust() {
        val app=RuntimeEnvironment.getApplication()
        val dir=File(app.noBackupFilesDir,"pairing-probe")
        dir.deleteRecursively()
        val identity=PairingPersistence.openReceiver(dir).use { it.identity }
        val originalState=File(dir,"state.bin").readBytes()
        val legacy="02:00:00:00:00:11"
        val manual="AA:BB:CC:DD:EE:10"
        var baselineNetwork: List<ByteArray>? = null
        for (override in listOf(manual,null)) {
            val surface=mock(Surface::class.java);`when`(surface.isValid).thenReturn(true)
            val lines=java.util.concurrent.CopyOnWriteArrayList<String>()
            val receiver=ProjectionReceiver(app,legacy,surface,mock(MfiAuthenticator::class.java),
                display=CarPlayDisplayPreferences(qualifiedVideoSelection=CarPlayVideoSelection(1280,720,30,"test.avc")),
                wired=true,advertisedBluetoothAddress=override,onStatus=lines::add)
            try {
                receiver.setRuntimeIdentification(Iap2IdentificationConfig("OAL","OAL","OAL","test","1","Android",1))
                val ip=NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp }
                    .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
                    .first { !it.isLoopbackAddress && !it.isAnyLocalAddress && !it.isMulticastAddress && !it.isLinkLocalAddress }
                val peer=ServerSocket().use { probe ->
                    probe.bind(InetSocketAddress(InetAddress.getByName("::"),0))
                    Socket().use { client ->
                        client.bind(InetSocketAddress(ip,0));client.connect(InetSocketAddress(ip,probe.localPort))
                        probe.accept().use { it.inetAddress }
                    }
                }
                receiver.start(peer.hostAddress!!)
                Socket().apply { bind(InetSocketAddress(ip,0));connect(InetSocketAddress(ip,receiver.port));soTimeout=5000 }.use { phone ->
                    repeat(2) {
                        phone.getOutputStream().apply { write("GET /info RTSP/1.0\r\nCSeq: ${it+1}\r\nContent-Length: 0\r\n\r\n".toByteArray());flush() }
                        val input=phone.getInputStream();val header=ByteArrayOutputStream()
                        while(!header.toByteArray().takeLast(4).toByteArray().contentEquals("\r\n\r\n".toByteArray())) {
                            val b=input.read();check(b>=0);header.write(b);check(header.size()<8192)
                        }
                        val text=header.toString("US-ASCII");assertTrue(text.startsWith("RTSP/1.0 200"))
                        val length=text.split("\r\n").first { it.startsWith("Content-Length:") }.substringAfter(':').trim().toInt()
                        val body=ByteArray(length);var read=0
                        while(read<length) { val n=input.read(body,read,length-read);check(n>0);read+=n }
                        val info=BplistCodec.decode(body) as Map<*,*>
                        assertEquals(listOf(override ?: legacy),info["bluetoothIDs"])
                        assertEquals(legacy,info["deviceID"])
                    }
                }
                assertEquals(identity.pairingId,receiver.pairingId)
                assertEquals(identity.publicKeyHex,receiver.publicKeyHex)
                // Actual NCM NTB forwarding with a fixed descriptor/fallback fixture, not a second random draw.
                val network = forwardedNetworkBytes()
                if (baselineNetwork == null) baselineNetwork = network
                else baselineNetwork!!.zip(network).forEach { (a,b) -> assertArrayEquals(a,b) }
                assertEquals(2,network.size)
            } finally { receiver.close();assertTrue(receiver.awaitClosed(10000)) }
            assertArrayEquals(originalState,File(dir,"state.bin").readBytes())
            assertFalse(lines.any { manual in it || legacy in it })
        }
    }
}
