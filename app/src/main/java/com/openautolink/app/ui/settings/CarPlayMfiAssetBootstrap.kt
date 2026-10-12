package com.openautolink.app.ui.settings

import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import java.io.File
import java.io.InputStream
import java.nio.file.Files


/** Optional internal-build bootstrap. No credential values or exception text leave this owner. */
internal class CarPlayMfiAssetBootstrap(
    private val noBackupRoot: File,
    private val openAsset: (String) -> InputStream?,
) {
    enum class Result { INSTALLED, EXISTING_VALID, NOT_BUNDLED, INVALID_BUNDLE, INVALID_EXISTING }

    fun install(): Result = synchronized(CarPlayCredentialImporter.LOCK) {
        val target = File(noBackupRoot, LocalMfiAuthenticationClient.DIRECTORY)
        val previous = File(noBackupRoot, "offline-mfi-previous")
        // Recover a whole-directory importer publication before any bundled fallback.
        if (!target.exists() && previous.exists()) {
            if (Files.isSymbolicLink(target.toPath()) || Files.isSymbolicLink(previous.toPath()) ||
                !previous.isDirectory || !previous.renameTo(target)) return@synchronized Result.INVALID_EXISTING
        }
        if (target.exists() || Files.isSymbolicLink(target.toPath())) {
            return@synchronized if (runCatching {
                require(!Files.isSymbolicLink(target.toPath()))
                require(target.listFiles().orEmpty().all { it.isFile && !Files.isSymbolicLink(it.toPath()) })
                LocalMfiAuthenticationClient.load(target)
            }.isSuccess) Result.EXISTING_VALID else Result.INVALID_EXISTING
        }
        var key: InputStream? = null
        var certificate: InputStream? = null
        try {
            key = openAsset("offline-mfi/identity.pk8")
            certificate = openAsset("offline-mfi/certificate.p7b")
            return when {
                key == null && certificate == null -> Result.NOT_BUNDLED
                key == null || certificate == null -> Result.INVALID_BUNDLE
                CarPlayCredentialImporter(noBackupRoot).importPair(key, certificate) -> Result.INSTALLED
                else -> Result.INVALID_BUNDLE
            }
        } catch (_: Exception) {
            return Result.INVALID_BUNDLE
        } finally {
            runCatching { key?.close() }
            runCatching { certificate?.close() }
        }
    }
}
