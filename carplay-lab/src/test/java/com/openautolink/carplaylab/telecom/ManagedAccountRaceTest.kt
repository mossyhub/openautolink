package com.openautolink.carplaylab.telecom

import android.telecom.PhoneAccount
import android.telecom.TelecomManager
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowTelecomManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], manifest = Config.NONE, shadows = [ManagedAccountRaceTest.AccountShadow::class])
class ManagedAccountRaceTest {
    @Implements(TelecomManager::class)
    class AccountShadow : ShadowTelecomManager() {
        @Implementation override fun registerPhoneAccount(account: PhoneAccount) {
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            super.registerPhoneAccount(account)
        }
        @Implementation override fun getPhoneAccount(handle: android.telecom.PhoneAccountHandle): PhoneAccount? {
            if (unsupportedReads) throw UnsupportedOperationException("synthetic unavailable API")
            if (blockReads) {
                readCalls.incrementAndGet()
                readEntered.countDown()
                check(readRelease.await(3, TimeUnit.SECONDS))
            }
            lastReadThread = Thread.currentThread().name
            return super.getPhoneAccount(handle)
        }
        companion object {
            @Volatile var blockReads = false
            @Volatile var unsupportedReads = false
            var readEntered = CountDownLatch(1)
            val readCalls = java.util.concurrent.atomic.AtomicInteger()
            var readRelease = CountDownLatch(1)
            @Volatile var lastReadThread = ""
            var entered = CountDownLatch(1)
            var release = CountDownLatch(1)
        }
    }
    @Before fun reset() {
        AccountShadow.entered = CountDownLatch(1); AccountShadow.release = CountDownLatch(1)
        AccountShadow.blockReads = false; AccountShadow.unsupportedReads = false; AccountShadow.readEntered = CountDownLatch(1)
        AccountShadow.readRelease = CountDownLatch(1); AccountShadow.lastReadThread = ""; AccountShadow.readCalls.set(0)
    }
    @Test fun liveAuthorityDoesNotReadBinderOnMainAndExpiredReadFailsClosed() {
        val context = RuntimeEnvironment.getApplication()
        val experiment = ManagedTelecomExperiment(context)
        AccountShadow.release.countDown()
        experiment.enable()
        val manager = context.getSystemService(TelecomManager::class.java)
        org.robolectric.Shadows.shadowOf(manager).enablePhoneAccount(experiment.handle, true)
        experiment.refresh()
        AccountShadow.lastReadThread = ""
        assertTrue(experiment.armed)
        assertEquals("armed synchronously read Binder on callback thread", "", AccountShadow.lastReadThread)
        AccountShadow.blockReads = true
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(3))
        assertFalse("expired authority is not enabled", experiment.armed)
        assertTrue(AccountShadow.readEntered.await(2, TimeUnit.SECONDS))
        repeat(40) { assertFalse(experiment.armed) } // coalesces while a read is in flight
        assertEquals(1,AccountShadow.readCalls.get())
        AccountShadow.blockReads = false; AccountShadow.readRelease.countDown()
        experiment.disable()
    }
    @Test fun idleAuthenticatedLinkRenewsAuthorityWithoutIngressAndRevokesOnDisabledRead() {
        val context=RuntimeEnvironment.getApplication();val experiment=ManagedTelecomExperiment(context)
        AccountShadow.release.countDown();experiment.enable()
        val manager=context.getSystemService(TelecomManager::class.java)
        org.robolectric.Shadows.shadowOf(manager).enablePhoneAccount(experiment.handle,true);experiment.refresh()
        val link=experiment.authenticatedLink { true }!!
        AccountShadow.blockReads=true
        assertTrue("idle runtime has no off-main own-account renewal",AccountShadow.readEntered.await(2,TimeUnit.SECONDS))
        AccountShadow.blockReads=false // only release the already-entered worker via its latch below
        org.robolectric.Shadows.shadowOf(manager).enablePhoneAccount(experiment.handle,false)
        AccountShadow.readRelease.countDown()
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
        while(link.cleanup==ManagedIncomingBridge.Cleanup.OPEN && System.nanoTime()<deadline) Thread.yield()
        assertNotEquals(ManagedIncomingBridge.Cleanup.OPEN,link.cleanup)
        // PENDING is published before the main post: await the exact worker's completed post first.
        experiment.census()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(link.awaitClosed(0));assertFalse(experiment.armed)
        experiment.disable()
    }
    @Test fun delayedAccountReadCannotRestoreAuthorityAfterConcurrentDisable() {
        val context=RuntimeEnvironment.getApplication();val experiment=ManagedTelecomExperiment(context)
        AccountShadow.release.countDown();experiment.enable()
        val manager=context.getSystemService(TelecomManager::class.java)
        org.robolectric.Shadows.shadowOf(manager).enablePhoneAccount(experiment.handle,true);experiment.refresh()
        AccountShadow.blockReads=true
        val read=Thread { experiment.refresh() }.also { it.start() }
        assertTrue(AccountShadow.readEntered.await(2,TimeUnit.SECONDS))
        val epoch=experiment.javaClass.getDeclaredField("epoch").apply { isAccessible=true };val old=epoch.getLong(experiment)
        val disable=Thread { experiment.disable() }.also { it.start() }
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
        while(epoch.getLong(experiment)==old && System.nanoTime()<deadline) Thread.yield()
        assertTrue(epoch.getLong(experiment)>old);assertFalse(experiment.armed)
        AccountShadow.blockReads=false;AccountShadow.readRelease.countDown()
        listOf(read,disable).forEach { it.join(3000);assertFalse(it.isAlive) }
        assertFalse(experiment.armed);assertFalse(experiment.nativeControlsEnabled)
        assertNull(manager.getPhoneAccount(experiment.handle))
    }
    @Test fun unsupportedReadIsUnknownNotAbsentOrEnabledAndCannotAuthorizeControl() {
        val context=RuntimeEnvironment.getApplication(); val experiment=ManagedTelecomExperiment(context)
        AccountShadow.release.countDown();experiment.enable()
        val manager=context.getSystemService(TelecomManager::class.java)
        org.robolectric.Shadows.shadowOf(manager).enablePhoneAccount(experiment.handle,true);experiment.refresh()
        assertTrue(experiment.armed)
        AccountShadow.unsupportedReads=true
        assertEquals(ManagedTelecomExperiment.Result.DENIED_OR_UNAVAILABLE,experiment.refresh())
        assertFalse(experiment.armed)
        assertEquals(PassiveTelecomCensus.Account.UNKNOWN,experiment.census().ownAccount)
        assertTrue("unsupported authority must remain visible UNKNOWN",experiment.observationStatus().contains("accountAuthority=UNKNOWN"))
        assertEquals(ManagedTelecomExperiment.ControlResult.ACCOUNT_REQUIRED,experiment.enableNativeControls())
        AccountShadow.unsupportedReads=false;experiment.disable()
    }
    @Test fun successorConsentCannotBeRemovedBySupersededDisableOrStaleRegister() {
        val context=RuntimeEnvironment.getApplication(); val experiment=ManagedTelecomExperiment(context)
        val old=Thread { experiment.enable() }.also { it.start() }
        assertTrue(AccountShadow.entered.await(2,TimeUnit.SECONDS))
        val epoch=experiment.javaClass.getDeclaredField("epoch").apply { isAccessible=true }
        val previous=epoch.getLong(experiment)
        val disable=Thread { experiment.disable() }.also { it.start() }
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
        while(epoch.getLong(experiment)==previous && System.nanoTime()<deadline) Thread.yield()
        assertTrue(epoch.getLong(experiment)>previous)
        val revoked=epoch.getLong(experiment)
        val result=java.util.concurrent.atomic.AtomicReference<ManagedTelecomExperiment.Result>()
        val successor=Thread { result.set(experiment.enable()) }.also { it.start() }
        while(epoch.getLong(experiment)==revoked && System.nanoTime()<deadline) Thread.yield()
        assertTrue(epoch.getLong(experiment)>revoked)
        AccountShadow.release.countDown()
        listOf(old,disable,successor).forEach { it.join(3000);assertFalse(it.isAlive) }
        assertEquals(ManagedTelecomExperiment.Result.REGISTERED_USER_ENABLE_REQUIRED,result.get())
        assertNotNull(context.getSystemService(TelecomManager::class.java).getPhoneAccount(experiment.handle))
        experiment.disable()
    }
    @Test fun disableWhileRegisterBlockedCannotLeavePersistentAccountAfterBothComplete() {
        val context = RuntimeEnvironment.getApplication()
        val experiment = ManagedTelecomExperiment(context)
        val register = Thread { experiment.enable() }.also { it.start() }
        assertTrue(AccountShadow.entered.await(2, TimeUnit.SECONDS))
        val disabled = CountDownLatch(1)
        val disable = Thread { experiment.disable(); disabled.countDown() }.also { it.start() }
        // Disable revokes immediately, but removal must not overtake the in-flight register.
        assertFalse("disable falsely acknowledged before pending register", disabled.await(150, TimeUnit.MILLISECONDS))
        AccountShadow.release.countDown()
        register.join(3000); disable.join(3000)
        assertFalse(register.isAlive); assertFalse(disable.isAlive)
        assertFalse(experiment.armed)
        assertNull(context.getSystemService(TelecomManager::class.java).getPhoneAccount(experiment.handle))
    }
}
