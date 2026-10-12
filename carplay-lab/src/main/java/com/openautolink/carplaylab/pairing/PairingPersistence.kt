// SPDX-License-Identifier: GPL-3.0-only
package com.openautolink.carplaylab.pairing

import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.PairingStore
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import java.io.*
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** One atomically replaced, app-private state file; never migrates/imports other identities. */
internal class PairingPersistence(private val directory: File) : Closeable {
    private val lockFile: RandomAccessFile
    private val lock: FileLock
    private val state: File
    private var closed = false
    private val controllers = linkedMapOf<String, ByteArray>()
    val identity: AirPlayIdentity
    val pairings: PairingStore

    init {
        require(!Files.isSymbolicLink(directory.toPath()))
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create pairing state directory" }
        privatePermissions(directory, true)
        state = File(directory, "state.bin")
        val lockPath = File(directory, "state.lock")
        require(!Files.isSymbolicLink(state.toPath()) && !Files.isSymbolicLink(lockPath.toPath()))
        lockFile = RandomAccessFile(lockPath, "rw")
        try {
            privatePermissions(lockPath, false)
            lock = try { lockFile.channel.tryLock() ?: throw IOException("Pairing state already open") }
            catch (_: java.nio.channels.OverlappingFileLockException) { throw IOException("Pairing state already open") }
        } catch (failure: Exception) { lockFile.close(); throw failure }
        try {
            identity = if (state.exists()) load() else AirPlayIdentity.generate().also { persist(it, controllers) }
            // Populate without rewriting, then install durable callback.
            var loading = true
            pairings = PairingStore { id, key ->
                if (!loading) synchronized(this) {
                    check(!closed) { "Pairing state closed" }
                    require(controllers.isEmpty() || controllers.containsKey(id)) { "Single selected controller only" }
                    val next = linkedMapOf<String, ByteArray>().also { it.putAll(controllers); it[id] = key.copyOf() }
                    persist(identity, next)
                    controllers.clear(); controllers.putAll(next)
                }
            }
            controllers.forEach { (id, key) -> pairings.save(id, key) }
            loading = false
        } catch (failure: Exception) { lock.release(); lockFile.close(); throw failure }
    }

    private fun load(): AirPlayIdentity {
        require(state.length() in 50..2048) { "Invalid pairing state size" }
        privatePermissions(state, false)
        DataInputStream(FileInputStream(state)).use { input ->
            require(input.readInt() == MAGIC) { "Invalid pairing state version" }
            val id = input.readUTF()
            require(UUID.fromString(id).toString() == id) { "Invalid pairing ID" }
            val seed = ByteArray(32).also(input::readFully)
            val pub = Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded
            val count = input.readInt()
            require(count in 0..1) { "Controller limit" }
            repeat(count) {
                val controllerId = input.readUTF()
                require(controllerId.isNotEmpty() && controllerId.toByteArray(Charsets.UTF_8).size <= 256)
                controllers[controllerId] = ByteArray(32).also(input::readFully)
            }
            require(input.read() == -1) { "Trailing pairing state" }
            return AirPlayIdentity(seed, pub, id)
        }
    }

    private fun persist(identity: AirPlayIdentity, entries: Map<String, ByteArray>) {
        val temporary = File(directory, "state.new")
        require(!Files.isSymbolicLink(temporary.toPath()))
        try {
            FileOutputStream(temporary).use { file ->
                privatePermissions(temporary, false)
                val out = DataOutputStream(file)
                out.writeInt(MAGIC)
                out.writeUTF(identity.pairingId)
                out.write(identity.privateKey)
                out.writeInt(entries.size)
                entries.forEach { (id, key) -> out.writeUTF(id); out.write(key) }
                out.flush()
                file.fd.sync()
            }
            // Never fall back to non-atomic overwrite: a damaged identity must fail closed.
            Files.move(temporary.toPath(), state.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        try { lock.release() } finally { lockFile.close() }
    }

    private fun privatePermissions(file: File, directory: Boolean) {
        check(file.setReadable(false, false) && file.setWritable(false, false))
        check(file.setExecutable(false, false))
        check(file.setReadable(true, true) && file.setWritable(true, true))
        if (directory) check(file.setExecutable(true, true))
    }
    companion object {
        private const val MAGIC = 0x50425031
        /** Accessory identity is locally generated, not an MFi credential or proof of phone trust.
         * Keep the same exclusive owner across admission/control retirement; never import or reset state. */
        fun openReceiver(directory: File): PairingPersistence = PairingPersistence(directory)
    }
}
