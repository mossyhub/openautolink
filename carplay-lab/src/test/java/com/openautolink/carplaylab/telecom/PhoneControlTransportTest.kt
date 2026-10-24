package com.openautolink.carplaylab.telecom

import com.shilapi.xcertplay.airplay.*
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class PhoneControlTransportTest {
    private fun field(s: AirPlaySession, n: String) = s.javaClass.getDeclaredField(n).apply { isAccessible = true }
    private fun send(s: AirPlaySession, name: String, current: () -> Boolean): Any {
        val type = runCatching { Class.forName("com.shilapi.xcertplay.airplay.PhoneControl") }.getOrNull()
        assertNotNull("typed phone transport missing", type)
        val value = type!!.enumConstants.single { (it as Enum<*>).name == name }
        return s.javaClass.getMethod("sendPhoneControl", type, kotlin.jvm.functions.Function0::class.java).invoke(s, value, current)
    }
    @Test fun partialWritesAreExplicitAndPressReleaseCannotInterleaveAnotherInput() {
        for (failAt in listOf(0,1,2)) {
            val session = AirPlaySession(Socket(), AirPlayConfig("test", "id", "mac", "950.7.1", AirPlayDisplayConfig(800,480)),
                AirPlayIdentity.generate(), PairingStore(), null, object: AirPlaySessionListener {}, object: AirPlayMediaHandler {})
            val key=ByteArray(32); val peer=ControlCipher(key,key)
            val reports=java.util.concurrent.CopyOnWriteArrayList<Int>()
            val pressed=java.util.concurrent.CountDownLatch(1); val unblock=java.util.concurrent.CountDownLatch(1)
            var writes=0
            val output=object: java.io.OutputStream() {
                override fun write(value: Int) = error("array writes expected")
                override fun write(bytes: ByteArray) {
                    writes++
                    if (writes==failAt) throw java.io.IOException("synthetic partial write")
                    val plain=peer.decrypt(bytes).data
                    val cmd=BplistCodec.decode(plain.copyOfRange(plain.toString(Charsets.ISO_8859_1).indexOf("\r\n\r\n")+4,plain.size)) as Map<*,*>
                    reports.add((cmd["hidReport"] as ByteArray).single().toInt())
                    if (writes==1 && failAt==0) { pressed.countDown(); assertTrue(unblock.await(2,java.util.concurrent.TimeUnit.SECONDS)) }
                }
            }
            val event=object: Socket() { override fun getOutputStream()=output }
            field(session,"eventSocket").set(session,event); field(session,"eventCipher").set(session,ControlCipher(key,key))
            (field(session,"authenticated").get(session) as AtomicBoolean).set(true)
            var outcome: Any?=null
            val phone=Thread { outcome=send(session,"END") {true} }; phone.start()
            if (failAt==0) {
                assertTrue(pressed.await(1,java.util.concurrent.TimeUnit.SECONDS))
                val other=Thread { session.sendMedia(2) }; other.start(); unblock.countDown()
                other.join(2000); assertFalse(other.isAlive)
            }
            phone.join(2000); assertFalse(phone.isAlive)
            assertEquals(when(failAt) { 0->"WRITTEN"; 1->"PRESS_FAILED_RELEASE_UNAVAILABLE"; else->"PRESS_WRITTEN_RELEASE_FAILED" },outcome.toString())
            if (failAt==0) assertEquals(listOf(3,0,2,0), reports)
            session.close()
        }
    }
    @Test fun typedHookAndDropAreAtomicEncryptedReportsWithExactWriterFence() {
        val session = AirPlaySession(Socket(), AirPlayConfig("test", "id", "mac", "950.7.1", AirPlayDisplayConfig(800,480)),
            AirPlayIdentity.generate(), PairingStore(), null, object: AirPlaySessionListener {}, object: AirPlayMediaHandler {})
        try {
            ServerSocket(0).use { server -> Socket("127.0.0.1", server.localPort).use { phone -> server.accept().use { event ->
                val key = ByteArray(32) { (it+1).toByte() }; val peer = ControlCipher(key,key)
                field(session,"eventSocket").set(session,event); field(session,"eventCipher").set(session,ControlCipher(key,key))
                (field(session,"authenticated").get(session) as AtomicBoolean).set(true)
                phone.soTimeout=1000
                fun read(): Map<*,*> {
                    val input=phone.getInputStream(); val len=input.readNBytes(2)
                    val n=(len[0].toInt() and 255) or ((len[1].toInt() and 255) shl 8)
                    val plain=peer.decrypt(len+input.readNBytes(n+16)).data
                    return BplistCodec.decode(plain.copyOfRange(plain.toString(Charsets.ISO_8859_1).indexOf("\r\n\r\n")+4,plain.size)) as Map<*,*>
                }
                for ((name,index) in listOf("ANSWER" to 1,"END" to 3)) {
                    assertEquals("WRITTEN",send(session,name) { true }.toString())
                    for (expected in listOf(index,0)) {
                        val command=read(); assertEquals("hidSendReport",command["type"])
                        assertEquals(AirPlayHid.TELEPHONY_HID_UID.toString(16),command["uuid"])
                        assertArrayEquals(byteArrayOf(expected.toByte()),command["hidReport"] as ByteArray)
                    }
                }
                val current=AtomicBoolean(true); val started=java.util.concurrent.CountDownLatch(1)
                var outcome: Any?=null
                val lock=field(session,"eventWriteLock").get(session)
                lateinit var worker: Thread
                synchronized(lock) {
                    worker=Thread { started.countDown(); outcome=send(session,"END") {current.get()} }
                    worker.start(); assertTrue(started.await(1,java.util.concurrent.TimeUnit.SECONDS)); current.set(false)
                }
                worker.join(1000); assertFalse(worker.isAlive); assertEquals("WITHHELD",outcome.toString())
                phone.soTimeout=100
                assertTrue(runCatching {phone.getInputStream().read()}.exceptionOrNull() is java.net.SocketTimeoutException)
            } } }
        } finally { session.close() }
    }
}
