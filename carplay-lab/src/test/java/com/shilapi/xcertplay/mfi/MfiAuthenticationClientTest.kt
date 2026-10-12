package com.shilapi.xcertplay.mfi

import com.shilapi.xcertplay.transport.I2cTransport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MfiAuthenticationClientTest {
    @Test
    fun readsCertificateInIncrementing128ByteWindows() {
        val certificate = bytes(300) { it }
        val transport = ScriptedTransport(
            select(0x30), pureRead(2, bytes(1, 44)),
            select(0x31), pureRead(128, certificate.copyOfRange(0, 128)),
            select(0x32), pureRead(128, certificate.copyOfRange(128, 256)),
            select(0x33), pureRead(44, certificate.copyOfRange(256, 300)),
        )

        val result = MfiAuthenticationClient(transport, 0x11).readCertificate()

        assertArrayEquals(certificate, result)
        assertEquals(0, transport.unconsumedSteps)
    }

    @Test
    fun signsChallengeWithSeparatedRegisterSelectAndRead() {
        val challenge = bytes(20) { it + 1 }
        val signature = bytes(128) { 0xa0 + it }
        val transport = ScriptedTransport(
            write(0x20, bytes(0, 20)),
            write(0x21, challenge),
            write(0x10, bytes(1)),
            select(0x10), pureRead(1, bytes(0x10)),
            select(0x11), pureRead(2, bytes(0, 128)),
            select(0x12), pureRead(128, signature),
        )

        val result = MfiAuthenticationClient(transport, 0x10).signChallenge(challenge)

        assertArrayEquals(signature, result)
        assertEquals(0, transport.unconsumedSteps)
    }

    private class ScriptedTransport(vararg steps: Step) : I2cTransport {
        private val steps = ArrayDeque(steps.toList())
        val unconsumedSteps: Int get() = steps.size

        override fun transaction(address7Bit: Int, writeData: ByteArray, readLength: Int): ByteArray {
            assertTrue("Expected a scripted transaction", steps.isNotEmpty())
            val step = steps.removeFirst()
            assertTrue("Unexpected I2C address 0x${address7Bit.toString(16)}", address7Bit in 0x10..0x11)
            assertArrayEquals(step.writeData, writeData)
            assertEquals(step.readLength, readLength)
            return step.response.copyOf()
        }
    }

    private data class Step(val writeData: ByteArray, val readLength: Int, val response: ByteArray)

    private fun select(register: Int): Step = Step(bytes(register), 0, ByteArray(0))

    private fun pureRead(readLength: Int, response: ByteArray): Step =
        Step(ByteArray(0), readLength, response)

    private fun write(register: Int, payload: ByteArray): Step =
        Step(bytes(register) + payload, 0, ByteArray(0))

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private fun bytes(size: Int, value: (Int) -> Int): ByteArray = ByteArray(size) { value(it).toByte() }
}
