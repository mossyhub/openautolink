package com.openautolink.app.diagnostics.live

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Separate OAL credential, never Mossy/upload auth. Encrypted, excluded from backup/transfer. */
internal interface LiveCredentials {
    fun load(): String?
    fun save(credential: String)
    fun clear()
    fun load(endpoint:LiveEndpoint):String? = if(endpoint==LiveEndpoint.LEGACY)load() else null
    fun save(endpoint:LiveEndpoint,credential:String) { require(endpoint==LiveEndpoint.LEGACY);save(credential) }
    fun clear(endpoint:LiveEndpoint) { require(endpoint==LiveEndpoint.LEGACY);clear() }
}
internal class LiveCredentialStore(private val context: Context, private val endpoint:LiveEndpoint=LiveEndpoint.LEGACY) : LiveCredentials {
    private val suffix=java.security.MessageDigest.getInstance("SHA-256").digest(endpoint.base.toByteArray()).joinToString(""){"%02x".format(it)}
    private val file by lazy { AtomicFile(File(context.noBackupFilesDir,if(endpoint==LiveEndpoint.LEGACY)"oal-live-owner.bin" else "oal-live-owner-$suffix.bin")) }
    private val alias = if(endpoint==LiveEndpoint.LEGACY)"oal-live-observer-v1" else "oal-live-observer-$suffix"
    override fun load(endpoint:LiveEndpoint)=LiveCredentialStore(context,endpoint).load()
    override fun save(endpoint:LiveEndpoint,credential:String)=LiveCredentialStore(context,endpoint).save(credential)
    override fun clear(endpoint:LiveEndpoint)=LiveCredentialStore(context,endpoint).clear()
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias,null) as? SecretKey)?.let { return it }
        check(create) { "Observer identity unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun load(): String? {
        if (!file.baseFile.exists()) return null
        val bytes = file.openRead().use { input ->
            val result = java.io.ByteArrayOutputStream(); val chunk=ByteArray(256)
            while (true) { val n=input.read(chunk); if(n<0)break; result.write(chunk,0,n); check(result.size()<=1024) }
            result.toByteArray()
        }
        check(bytes.size>28)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(false),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        val credential=String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.US_ASCII)
        check(credential.matches(Regex("[A-Za-z0-9_-]{43}")))
        return credential
    }
    override fun save(credential: String) {
        require(credential.matches(Regex("[A-Za-z0-9_-]{43}")))
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key(true))
        check(cipher.iv.size==12)
        val bytes=cipher.iv+cipher.doFinal(credential.toByteArray(Charsets.US_ASCII))
        val stream=file.startWrite()
        try { stream.write(bytes);file.finishWrite(stream) }catch(e:Exception){file.failWrite(stream);throw e}
    }
    override fun clear() { file.delete();KeyStore.getInstance("AndroidKeyStore").apply { load(null);deleteEntry(alias) } }
}
