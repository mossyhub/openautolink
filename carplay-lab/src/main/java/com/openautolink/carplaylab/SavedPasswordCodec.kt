package com.openautolink.carplaylab
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
internal object SavedPasswordCodec {
    fun seal(key: SecretKey, password: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        check(cipher.iv.size == 12)
        return cipher.iv + cipher.doFinal(password.toByteArray(Charsets.UTF_8))
    }
    fun open(key: SecretKey, record: ByteArray): String {
        require(record.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, record.copyOfRange(0,12)))
        return String(cipher.doFinal(record.copyOfRange(12,record.size)), Charsets.UTF_8)
    }
}
