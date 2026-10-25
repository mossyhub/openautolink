package com.openautolink.carplaylab.telecom

import android.Manifest
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowTelecomManager
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], manifest = Config.NONE, shadows = [TelecomAccountBoundaryTest.AccountShadow::class])
class TelecomAccountBoundaryTest {
    @Implements(TelecomManager::class)
    class AccountShadow : ShadowTelecomManager() {
        @Implementation override fun registerPhoneAccount(account: PhoneAccount) {
            registers++
            when (mode) {
                1 -> throw SecurityException("sensitive register text")
                2 -> throw UnsupportedOperationException("sensitive feature text")
                3 -> return
            }
            super.registerPhoneAccount(account)
        }
        @Implementation override fun getPhoneAccount(handle: PhoneAccountHandle): PhoneAccount? {
            reads++
            assertNotEquals("own-account Binder ran on UI thread", android.os.Looper.getMainLooper().thread, Thread.currentThread())
            if (mode == 4) throw SecurityException("sensitive read text")
            return super.getPhoneAccount(handle)
        }
        companion object { var mode = 0; var reads = 0; var registers = 0 }
    }
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var experiment: ManagedTelecomExperiment
    private fun receipt() = File(app.getExternalFilesDir(null), "openautolink/logs/telecom_account_access.log").readText()
    @Before fun reset() {
        AccountShadow.mode = 0; AccountShadow.reads = 0; AccountShadow.registers = 0
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_NUMBERS)
        experiment = ManagedTelecomExperiment(app)
    }
    @After fun cleanup() { AccountShadow.mode = 0; experiment.disable() }
    @Test fun missingManagerAndManagerSecurityAreDistinctFromRegistrationOutcomes() {
        fun controller(denied: Boolean) = ManagedTelecomExperiment(object : android.content.ContextWrapper(app) {
            override fun getSystemService(name: String): Any? {
                if (name == android.content.Context.TELECOM_SERVICE) {
                    if (denied) throw SecurityException("sensitive manager text")
                    return null
                }
                return super.getSystemService(name)
            }
        })
        experiment = controller(false)
        experiment.enable()
        assertTrue(receipt().contains("stage=MANAGER_LOOKUP outcome=NO_MANAGER"))
        experiment = controller(true)
        experiment.enable()
        assertTrue(receipt().contains("stage=MANAGER_LOOKUP outcome=SECURITY_EXCEPTION"))
        assertFalse(receipt().contains("sensitive"))
        assertEquals(0, AccountShadow.registers)
    }
    @Test fun registrationReturnedAndReadDeniedHaveDifferentStagesFromRegistrationDenied() {
        AccountShadow.mode = 4
        assertEquals("REGISTER_RETURNED_READ_UNAVAILABLE", experiment.enable().name)
        val text = receipt()
        assertTrue(text.contains("stage=REGISTER outcome=RETURNED"))
        assertTrue(text.contains("stage=FIRST_OWN_READ outcome=SECURITY_EXCEPTION"))
        assertFalse(text.contains("sensitive"))
        assertFalse(experiment.armed)
        assertEquals(PassiveTelecomCensus.Account.UNKNOWN, experiment.census().ownAccount)
    }
    @Test fun nullReadAfterReturnedRegistrationIsNotRegistrationDenial() {
        AccountShadow.mode = 3
        assertEquals("REGISTER_RETURNED_ACCOUNT_ABSENT", experiment.enable().name)
        assertTrue(receipt().contains("stage=FIRST_OWN_READ outcome=NULL_ACCOUNT"))
    }
    @Test fun registerSecurityAndUnsupportedAreFiniteDistinctOutcomes() {
        AccountShadow.mode = 1; experiment.enable()
        assertTrue(receipt().contains("stage=REGISTER outcome=SECURITY_EXCEPTION"))
        AccountShadow.mode = 2; experiment.enable()
        assertTrue(receipt().contains("stage=REGISTER outcome=UNSUPPORTED_OPERATION"))
        assertEquals(0, AccountShadow.reads)
    }
    @Test fun missingGrantBlocksAllOwnReadsIncludingInspectRefreshAndCleanup() {
        shadowOf(app).denyPermissions(Manifest.permission.READ_PHONE_NUMBERS)
        experiment.enable(); experiment.refresh(); experiment.census(); experiment.disable()
        assertEquals(0, AccountShadow.reads)
        assertEquals(0, AccountShadow.registers)
    }
}
