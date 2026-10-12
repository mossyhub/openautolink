// SPDX-License-Identifier: GPL-3.0-only
package com.openautolink.carplaylab.usb

import com.shilapi.xcertplay.transport.LockdownPairRecord
import java.io.*
import java.security.MessageDigest

/** Private no_backup directory supplied by app. No phone identifiers in filenames or diagnostics. */
class UsbLockdownTrust(private val directory: File) {
    private fun file(serial: String): File {
        require(serial.isNotBlank())
        val digest=MessageDigest.getInstance("SHA-256").digest(serial.toByteArray())
        return File(directory,digest.joinToString("") { "%02x".format(it) }+".bin")
    }
    fun load(serial:String):LockdownPairRecord? {
        val f=file(serial); if(!f.exists()) return null
        require(f.length() in 1..131072)
        return DataInputStream(f.inputStream()).use { input ->
            check(input.readInt()==1)
            val host=input.readUTF(); val buid=input.readUTF(); val wifi=input.readUTF()
            fun bytes():ByteArray { val size=input.readInt(); require(size in 1..16384); return ByteArray(size).also(input::readFully) }
            val record=LockdownPairRecord.restore(host,buid,wifi,bytes(),bytes(),bytes(),bytes(),bytes(),bytes())
            check(input.read()==-1); record
        }
    }
    fun save(serial:String,record:LockdownPairRecord) {
        check(directory.isDirectory || directory.mkdirs())
        directory.setReadable(false,false);directory.setWritable(false,false);directory.setExecutable(false,false)
        directory.setReadable(true,true);directory.setWritable(true,true);directory.setExecutable(true,true)
        val target=file(serial); val staging=File(directory,target.name+".new")
        try {
            FileOutputStream(staging).use { stream ->
                staging.setReadable(false,false);staging.setWritable(false,false)
                staging.setReadable(true,true);staging.setWritable(true,true)
                val output=DataOutputStream(stream);output.writeInt(1)
                output.writeUTF(record.hostId);output.writeUTF(record.systemBuid);output.writeUTF(record.wifiMacAddress)
                listOf(record.devicePublicKeyPem,record.deviceCertificatePem,record.hostPrivateKeyPem,
                    record.hostCertificatePem,record.rootPrivateKeyPem,record.rootCertificatePem).forEach {
                    require(it.size in 1..16384);output.writeInt(it.size);output.write(it);it.fill(0)
                }
                output.flush();stream.fd.sync()
            }
            check(staging.renameTo(target)) { "Private trust publication failed" }
        } finally { staging.delete() }
    }
}
