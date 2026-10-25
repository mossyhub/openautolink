package com.openautolink.app.ui.diagnostics

import android.Manifest
import android.app.Application
import android.media.AudioRecord
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[32],shadows=[JavaVr48PermissionTest.RevokedBuilder::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class JavaVr48PermissionTest {
    @Implements(AudioRecord.Builder::class)
    class RevokedBuilder {
        @Implementation fun build(): AudioRecord {
            builds++
            throw SecurityException("permission revoked during allocation")
        }
        companion object { @Volatile var builds = 0 }
    }

    private fun finish(run: JavaVr48Run): com.shilapi.xcertplay.media.NativeCaptureOwnerRegistry.Receipt {
        run.begin()
        val deadline = System.nanoTime() + 3_000_000_000L
        while (System.nanoTime() < deadline) {
            run.pollClosedJoin()?.let { run.disposeJoined(); return it }
            Thread.sleep(5)
        }
        run.cancel()
        throw AssertionError("capture worker did not publish joined receipt")
    }

    @Test fun deniedPublicPermissionStopsBeforeBuilderEvenWhenWorkerPolicyAllows() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        RevokedBuilder.builds = 0
        val receipt = finish(JavaVr48Run(app) { true })
        assertEquals("actual permission check must exclude allocation", 0, RevokedBuilder.builds)
        assertEquals(2, receipt.termination)
    }

    @Test fun permissionRevokedDuringAllocationPublishesCanceledJoinedReceipt() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        RevokedBuilder.builds = 0
        val receipt = finish(JavaVr48Run(app) { true })
        assertEquals(1, RevokedBuilder.builds)
        assertEquals("revocation is cancellation, not an unexplained recorder error", 2, receipt.termination)
        assertFalse(receipt.recoveryRequiresRestart)
    }

    @Test fun workerPolicyStillExcludesAllocationWithGrantedPermission() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        RevokedBuilder.builds = 0
        assertEquals(2, finish(JavaVr48Run(app) { false }).termination)
        assertEquals(0, RevokedBuilder.builds)
    }
}
