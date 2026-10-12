package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.session.Iap2Session
import java.io.IOException
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class Iap2RetirementTest {
    @Test fun historicalEofDoesNotPreventExactWorkerRetirementAcknowledgment() {
        val stream = object : BlockingDuplexByteStream {
            override fun send(data: ByteArray) {}
            override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray = byteArrayOf()
            override fun close() {}
        }
        val session = Iap2Session.openWireless(stream)
        runCatching { session.recv(200) }
        runCatching { session.close() }
        assertTrue(session.awaitClosed(1000))
    }

    @Test fun closeCannotAcknowledgeAStillBlockedLinkWorker() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stream = object : BlockingDuplexByteStream {
            override fun send(data: ByteArray) {}
            override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return byteArrayOf()
            }
            override fun close() {} // Fault injection: stream refuses to unblock its receive.
        }
        val session = Iap2Session.openWireless(stream)
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            runCatching { session.close() }
            assertFalse(session.awaitClosed(10))
        } finally { release.countDown() }
        assertTrue(session.awaitClosed(1000))
    }

    @Test fun rfcommRetirementIncludesTheReaderNotOnlySocketClose() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val socket = object : BluetoothRfcommSocketAccess {
            override fun inputStream(): InputStream = object : InputStream() {
                override fun read(): Int {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    return -1
                }
            }
            override fun outputStream() = ByteArrayOutputStream()
            override fun close() { release.countDown() }
        }
        val stream = BluetoothRfcommDuplexStream(socket)
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        assertFalse(stream.awaitClosed(10))
        stream.close()
        assertTrue(stream.awaitClosed(1000))
    }
}
