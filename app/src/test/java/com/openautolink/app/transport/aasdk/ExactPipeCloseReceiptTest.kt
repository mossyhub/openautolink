package com.openautolink.app.transport.aasdk

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.*
import org.junit.Test

class ExactPipeCloseReceiptTest {
    @Test fun failedConcreteCloseMustThrowAndRetainFailureWithoutRetryingDescriptors() {
        var inputCloses = 0
        var outputCloses = 0
        val inputFailure = IOException("input close failed")
        val outputFailure = IOException("output close failed")
        val pipe = AasdkTransportPipe(object : InputStream() {
            override fun read(): Int = -1
            override fun close() { inputCloses++; throw inputFailure }
        }, object : OutputStream() {
            override fun write(b: Int) {}
            override fun close() { outputCloses++; throw outputFailure }
        })
        repeat(2) {
            val failure = runCatching { pipe.close() }.exceptionOrNull()
            assertSame("close failure cannot become an exact-close success", inputFailure, failure)
        }
        assertEquals(1, inputCloses)
        assertEquals(1, outputCloses)
        assertArrayEquals(arrayOf(outputFailure), inputFailure.suppressed)
    }
    @Test fun successfulExactCloseClosesBothDescriptorsOnlyOnce() {
        var inputCloses = 0
        var outputCloses = 0
        val pipe = AasdkTransportPipe(object : InputStream() {
            override fun read(): Int = -1
            override fun close() { inputCloses++ }
        }, object : OutputStream() {
            override fun write(b: Int) {}
            override fun close() { outputCloses++ }
        })
        pipe.close(); pipe.close()
        assertEquals(1, inputCloses)
        assertEquals(1, outputCloses)
    }
}
