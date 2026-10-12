// SPDX-License-Identifier: GPL-3.0-only
package com.openautolink.carplaylab.usb
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import com.shilapi.xcertplay.transport.LockdownPairRecord
class UsbLockdownTrustTest {
 @Test fun trustSurvivesExactPrivateSerialWithoutLeakingSerialInFilename() {
  val dir=Files.createTempDirectory(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")),"oal-usb-trust").toFile()
  val bytes="fixture-material".toByteArray()
  val record=LockdownPairRecord.restore("test-host","test-buid","00:11:22:33:44:55",bytes,bytes,bytes,bytes,bytes,bytes)
  try {
   val trust=UsbLockdownTrust(dir);trust.save("private-selected-phone",record)
   assertEquals("test-host",trust.load("private-selected-phone")?.hostId)
   assertArrayEquals(bytes,trust.load("private-selected-phone")?.rootPrivateKeyPem)
   assertNull(trust.load("different-phone"))
   assertFalse(dir.list()!!.any { it.contains("private-selected-phone") })
   assertTrue(dir.list()!!.none { it.endsWith(".new") })
  } finally { dir.deleteRecursively() }
 }
 @Test fun corruptOversizedTrustFailsClosed() {
  val dir=Files.createTempDirectory(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")),"oal-usb-trust").toFile()
  try {
   val trust=UsbLockdownTrust(dir); val bytes=byteArrayOf(1)
   trust.save("selected",LockdownPairRecord.restore("a","b","c",bytes,bytes,bytes,bytes,bytes,bytes))
   dir.listFiles()!!.single().writeBytes(ByteArray(131073))
   assertThrows(IllegalArgumentException::class.java) { trust.load("selected") }
  } finally { dir.deleteRecursively() }
 }
}
