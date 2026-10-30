package com.openautolink.carplaylab.telecom

import android.Manifest
import android.telecom.TelecomManager
import android.util.AtomicFile
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], manifest = Config.NONE, shadows = [TelecomReceiptAgeTest.StorageShadow::class, TelecomReceiptAgeTest.ReadShadow::class])
class TelecomReceiptAgeTest {
    @Implements(TelecomManager::class)
    class ReadShadow : org.robolectric.shadows.ShadowTelecomManager() {
        @Implementation override fun getPhoneAccount(handle: android.telecom.PhoneAccountHandle): android.telecom.PhoneAccount? {
            val own = super.getPhoneAccount(handle)
            if (holdOwnReceipt) {
                // Force this actual own-read result through the real bounded writer, not its dedupe fast path.
                java.io.File(RuntimeEnvironment.getApplication().getExternalFilesDir(null), "openautolink/logs/telecom_account_access.log").delete()
                StorageShadow.block = true
            }
            return own
        }
        companion object { @Volatile var holdOwnReceipt = false }
    }
    @Implements(AtomicFile::class)
    class StorageShadow {
        @RealObject private lateinit var real: AtomicFile
        @Implementation fun finishWrite(stream: FileOutputStream) {
            // Only the receipt written after this own-account read may advance
            // the age-test barrier; unrelated asynchronous native receipts may race it.
            val selected = block && real.baseFile.name == TelecomAccountDiagnostics.FILE_NAME
            if (selected) entered.countDown()
            selectionObserver?.invoke(real.baseFile.name, selected)
            if (selected) check(release.await(3, TimeUnit.SECONDS))
            Shadow.directlyOn<Any, AtomicFile>(real, AtomicFile::class.java, "finishWrite", ClassParameter.from(FileOutputStream::class.java, stream))
        }
        companion object {
            @Volatile var block = false
            @Volatile var selectionObserver: ((String, Boolean) -> Unit)? = null
            var entered = CountDownLatch(1)
            var release = CountDownLatch(1)
        }
    }
    @Test fun unrelatedAtomicFileCannotFundTheOwnReadAgeBarrier() {
        val app = RuntimeEnvironment.getApplication()
        StorageShadow.block = true
        StorageShadow.entered = CountDownLatch(1); StorageShadow.release = CountDownLatch(1)
        val observed = CountDownLatch(1)
        val selected = java.util.concurrent.atomic.AtomicReference<Boolean?>()
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val target = java.io.File(app.cacheDir, "unrelated-receipt-probe.log")
        StorageShadow.selectionObserver = { name, held ->
            if (name == target.name) { selected.set(held); observed.countDown() }
        }
        val writer = Thread {
            try {
                val atomic = AtomicFile(target)
                val stream = atomic.startWrite()
                stream.write(byteArrayOf(1))
                atomic.finishWrite(stream)
            } catch (error: Throwable) { failure.set(error) }
        }
        try {
            writer.start()
            assertTrue("actual unrelated AtomicFile boundary not reached", observed.await(2, TimeUnit.SECONDS))
            assertEquals("unrelated receipt cannot stand in for completed own-account read", false, selected.get())
            assertEquals("wrong file released the age-test barrier", 1L, StorageShadow.entered.count)
        } finally {
            StorageShadow.selectionObserver = null; StorageShadow.block = false; StorageShadow.release.countDown()
            writer.join(3000); assertFalse(writer.isAlive); assertNull(failure.get()); target.delete()
        }
    }
    @Test fun slowReceiptPersistenceMustNotMakeAnOldOwnReadFresh() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_NUMBERS)
        ReadShadow.holdOwnReceipt = false
        StorageShadow.block = false; StorageShadow.entered = CountDownLatch(1); StorageShadow.release = CountDownLatch(1)
        val experiment = ManagedTelecomExperiment(app)
        var worker: Thread? = null
        try {
            experiment.enable()
            shadowOf(app.getSystemService(TelecomManager::class.java)).enablePhoneAccount(experiment.handle, true)
            StorageShadow.block = true
            val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
            worker = Thread { try { experiment.refresh() } catch (error: Throwable) { failure.set(error) } }.also { it.start() }
            assertTrue("receipt write not reached", StorageShadow.entered.await(2, TimeUnit.SECONDS))
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(3))
            StorageShadow.block = false; StorageShadow.release.countDown()
            worker.join(3000); assertFalse(worker.isAlive); assertNull(failure.get())
            assertFalse("receipt I/O reset the age of the already completed account read", experiment.armed)
        } finally {
            StorageShadow.block = false; StorageShadow.release.countDown(); worker?.join(3000)
            experiment.disable()
        }
    }
    @Test fun revocationDuringInitialOwnReadReceiptCannotSelectOrReportEnabled() = revokeDuringReceipt(initial = true)
    @Test fun revocationDuringRefreshReceiptCannotReportCachedEnabled() = revokeDuringReceipt(initial = false)
    private fun revokeDuringReceipt(initial: Boolean) {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_NUMBERS)
        ReadShadow.holdOwnReceipt = false
        StorageShadow.block = false; StorageShadow.entered = CountDownLatch(1); StorageShadow.release = CountDownLatch(1)
        val experiment = ManagedTelecomExperiment(app)
        var worker: Thread? = null
        try {
            if (!initial) {
                experiment.enable()
                shadowOf(app.getSystemService(TelecomManager::class.java)).enablePhoneAccount(experiment.handle, true)
            }
            ReadShadow.holdOwnReceipt = true
            val result = java.util.concurrent.atomic.AtomicReference<ManagedTelecomExperiment.Result>()
            val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
            worker = Thread { try { result.set(if (initial) experiment.enable() else experiment.refresh()) } catch (error: Throwable) { failure.set(error) } }.also { it.start() }
            assertTrue("own-read receipt not reached", StorageShadow.entered.await(2, TimeUnit.SECONDS))
            shadowOf(app).denyPermissions(Manifest.permission.READ_PHONE_NUMBERS)
            ReadShadow.holdOwnReceipt = false; StorageShadow.block = false; StorageShadow.release.countDown()
            worker.join(3000); assertFalse(worker.isAlive); assertNull(failure.get())
            assertEquals("READ_PERMISSION_REQUIRED", result.get()?.name)
            assertFalse(experiment.armed)
            assertTrue(experiment.observationStatus().contains("accountAuthority=UNKNOWN/read_permission_required"))
            if (initial) assertFalse("revoked initial enable established process selection", experiment.enabledForProcess)
        } finally {
            ReadShadow.holdOwnReceipt = false; StorageShadow.block = false; StorageShadow.release.countDown(); worker?.join(3000)
            experiment.disable()
        }
    }
}
