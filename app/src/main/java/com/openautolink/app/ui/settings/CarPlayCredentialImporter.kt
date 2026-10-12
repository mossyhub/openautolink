package com.openautolink.app.ui.settings

import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files

/** Owner-authorized provisioning. Call with Context.noBackupFilesDir; sources stay private. */
internal class CarPlayCredentialImporter @JvmOverloads constructor(
    private val noBackupRoot: File,
    private val move: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
) {
    fun importPair(privateKey: InputStream, certificate: InputStream): Boolean = synchronized(LOCK) {
        val target = File(noBackupRoot, LocalMfiAuthenticationClient.DIRECTORY)
        val stage = File(noBackupRoot, "offline-mfi-import")
        val previous = File(noBackupRoot, "offline-mfi-previous")
        try {
            require(noBackupRoot.isDirectory)
            for (path in listOf(target, stage, previous)) {
                require(!Files.isSymbolicLink(path.toPath()))
                if (path.exists()) {
                    require(path.isDirectory)
                    require(path.listFiles().orEmpty().all { !Files.isSymbolicLink(it.toPath()) && it.isFile })
                }
            }
            // Recover a process death between whole-directory renames, never a half pair.
            if (!target.exists() && previous.exists()) check(move(previous, target))
            stage.deleteRecursively()
            check(stage.mkdir())
            writeBounded(privateKey, File(stage, "identity.pk8"))
            writeBounded(certificate, File(stage, "certificate.p7b"))
            LocalMfiAuthenticationClient.load(stage) // P-256, exactly one cert, real matching-key proof.
            previous.deleteRecursively()
            if (target.exists()) check(move(target, previous))
            if (!move(stage, target)) {
                if (previous.exists()) check(move(previous, target))
                return@synchronized false
            }
            previous.deleteRecursively()
            true
        } catch (_: Exception) {
            // If publication throws after moving the old pair, restore it before reporting failure.
            if (!target.exists() && previous.isDirectory && !Files.isSymbolicLink(previous.toPath())) {
                runCatching { move(previous, target) }
            }
            false // Provider/crypto/storage exceptions may contain sensitive values. Never export them.
        } finally {
            runCatching { privateKey.close() }
            runCatching { certificate.close() }
            if (!Files.isSymbolicLink(stage.toPath())) runCatching { stage.deleteRecursively() }
        }
    }

    private fun writeBounded(source: InputStream, file: File) {
        val bytes = ByteArray(MAX_BYTES + 1)
        try {
            var size = 0
            while (size < bytes.size) {
                val count = source.read(bytes, size, bytes.size - size)
                if (count < 0) break
                if (count == 0) {
                    val next = source.read()
                    if (next < 0) break
                    bytes[size++] = next.toByte()
                } else size += count
            }
            require(size in 1..MAX_BYTES)
            FileOutputStream(file).use { output ->
                output.write(bytes, 0, size)
                output.fd.sync()
            }
        } finally {
            bytes.fill(0)
        }
    }

    companion object {
        internal val LOCK = Any()
        private const val MAX_BYTES = 16 * 1024
    }
}
