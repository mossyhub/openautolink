package com.shilapi.xcertplay.transport

import java.io.IOException

/**
 * Transport boundary used by the MFi register layer. Implementations perform blocking I/O away
 * from Android's main thread and use [I2cTransportException] for expected transport failures.
 */
interface I2cTransport {
    /**
     * Executes one I2C transaction. [address7Bit] must be in `0x00..0x7f` and [readLength] must
     * be non-negative. An implementation writes [writeData], then, when [readLength] is positive,
     * performs a repeated-start read and returns exactly [readLength] bytes. Empty [writeData] or
     * a zero [readLength] represent read-only or write-only transactions respectively.
     */
    @Throws(I2cTransportException::class)
    fun transaction(address7Bit: Int, writeData: ByteArray, readLength: Int): ByteArray

}

/** Expected, recoverable I2C transport failures. */
sealed class I2cTransportException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class InvalidRequest(message: String) : I2cTransportException(message)
    class DeviceUnavailable(message: String, cause: Throwable? = null) : I2cTransportException(message, cause)
    class PermissionDenied(message: String) : I2cTransportException(message)
    class TimedOut(message: String, cause: Throwable? = null) : I2cTransportException(message, cause)
    class Nack(message: String) : I2cTransportException(message)
    class Protocol(message: String, cause: Throwable? = null) : I2cTransportException(message, cause)
}
