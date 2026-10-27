package com.openautolink.app.diagnostics

import android.system.Os
import android.system.StructStat
import java.io.*
import java.nio.file.*
import org.robolectric.annotation.*

/** Host filesystem boundary: Robolectric's default fstat reports all zeroes on these SDKs. */
@Implements(Os::class)
class MediaApkOsShadow {
    companion object {
        private val files = java.util.IdentityHashMap<FileDescriptor, Pair<String, FileInputStream>>()
        @JvmStatic @Resetter fun reset() { files.values.forEach { try { it.second.close() } catch (_: Exception) { } }; files.clear() }
        @JvmStatic @Implementation fun open(path: String, flags: Int, mode: Int): FileDescriptor {
            if (Files.isSymbolicLink(Path.of(path))) throw IOException("no follow")
            val input = FileInputStream(path); files[input.fd] = path to input; return input.fd
        }
        @JvmStatic @Implementation fun close(fd: FileDescriptor) { files.remove(fd)?.second?.close() }
        @JvmStatic @Implementation fun fstat(fd: FileDescriptor): StructStat = stat(files.getValue(fd).first)
        @JvmStatic @Implementation fun lstat(path: String): StructStat = stat(path)
        private fun stat(path: String): StructStat {
            val a = Files.readAttributes(Path.of(path), "unix:*", LinkOption.NOFOLLOW_LINKS)
            val modified = (a["lastModifiedTime"] as java.nio.file.attribute.FileTime).toMillis() / 1000
            return StructStat(a["dev"] as Long, a["ino"] as Long, a["mode"] as Int, 1, 0, 0, 0,
                a["size"] as Long, modified, modified, modified, 4096, 1)
        }
    }
}
