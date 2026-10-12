package com.openautolink.carplaylab
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.KeyGenerator
class SavedWifiPasswordTest {
    @Test fun encryptedRecordRestoresAndRejectsTampering() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val record = SavedPasswordCodec.seal(key, "test-only-passphrase")
        assertFalse(String(record, Charsets.ISO_8859_1).contains("test-only-passphrase"))
        assertEquals("test-only-passphrase", SavedPasswordCodec.open(key, record))
        record[record.lastIndex] = (record.last().toInt() xor 1).toByte()
        try { SavedPasswordCodec.open(key, record); fail("Tampered ciphertext accepted") } catch (_: java.security.GeneralSecurityException) {}
    }
}
