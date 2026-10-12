package com.openautolink.carplaylab

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** App-private, backup-excluded ciphertext; nonexportable Android Keystore key. */
class SavedWifiPassword(context: Context) {
    private val file = File(context.noBackupFilesDir, "bench-wifi-password.enc")
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(ALIAS, null)
        if (existing != null) return existing as SecretKey
        check(!file.exists()) { "Saved password key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    fun load(): String = if (file.exists()) SavedPasswordCodec.open(key(), file.readBytes()) else ""
    fun save(value: String) {
        if (value.isEmpty()) { check(!file.exists() || file.delete()); return }
        val ciphertext = SavedPasswordCodec.seal(key(), value)
        val next = File(file.parentFile, file.name + ".new")
        try {
            java.io.FileOutputStream(next).use { out -> out.write(ciphertext); out.fd.sync() }
            check(next.setReadable(false,false) && next.setWritable(false,false))
            check(next.setReadable(true,true) && next.setWritable(true,true))
            Files.move(next.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } finally { next.delete() }
    }
    companion object { private const val ALIAS = "oal-carplay-bench-wifi-v1" }
}
