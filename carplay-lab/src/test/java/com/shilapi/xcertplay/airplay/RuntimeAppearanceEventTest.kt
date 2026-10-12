package com.shilapi.xcertplay.airplay

import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

/** Generated keys and real loopback AEAD. Reflection installs test transport, not production routing. */
class RuntimeAppearanceEventTest {
    private fun field(session: AirPlaySession, name: String) = session.javaClass.getDeclaredField(name).apply { isAccessible = true }
    @Test fun readyOnlyNightWriteIsAuthenticatedEncryptedAndNeverRetainedBeforeReadiness() {
        val config = AirPlayConfig("test", "test", "test", "950.7.1", AirPlayDisplayConfig(800,480))
        val session = AirPlaySession(Socket(), config, AirPlayIdentity.generate(), PairingStore(), null,
            object : AirPlaySessionListener {}, object : AirPlayMediaHandler {})
        ServerSocket(0).use { server ->
            Socket("127.0.0.1", server.localPort).use { phone ->
                server.accept().use { event ->
                    val key = ByteArray(32) { (it + 1).toByte() }
                    val peerCipher = ControlCipher(key, key)
                    assertFalse(session.encryptedEventReady)
                    assertFalse(session.setNightModeIfEventReady(false))
                    assertNull(field(session,"pendingNightMode").get(session))
                    field(session,"eventSocket").set(session,event)
                    field(session,"eventCipher").set(session,ControlCipher(key,key))
                    assertFalse(session.encryptedEventReady)
                    (field(session,"authenticated").get(session) as AtomicBoolean).set(true)
                    assertTrue(session.encryptedEventReady)
                    assertTrue(session.setNightModeIfEventReady(false))
                    phone.soTimeout = 1000
                    val input = phone.getInputStream()
                    val length = input.readNBytes(2)
                    val n = (length[0].toInt() and 255) or ((length[1].toInt() and 255) shl 8)
                    val plain = peerCipher.decrypt(length + input.readNBytes(n + 16)).data
                    val text = plain.toString(Charsets.ISO_8859_1)
                    assertTrue(text.startsWith("POST /command"))
                    val body = plain.copyOfRange(text.indexOf("\r\n\r\n") + 4, plain.size)
                    val command = BplistCodec.decode(body) as Map<*,*>
                    assertEquals("setNightMode", command["type"])
                    assertEquals(false, (command["params"] as Map<*,*>)["nightMode"])
                    session.close()
                    assertFalse(session.encryptedEventReady)
                    assertFalse(session.setNightModeIfEventReady(true))
                }
            }
        }
    }
}
