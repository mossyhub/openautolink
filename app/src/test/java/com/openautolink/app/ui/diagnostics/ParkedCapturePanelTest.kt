package com.openautolink.app.ui.diagnostics

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import com.openautolink.app.ui.testing.createOalComposeRule
import com.shilapi.xcertplay.media.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[32],qualifiers="w800dp-h600dp-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
class ParkedCapturePanelTest {
    @get:Rule val compose=createOalComposeRule()
    @Test fun simpleComparisonNeedsStopNotConsentParkOrBluetoothAcknowledgementAndCancelRetainsOwner() {
        val tasks=java.util.ArrayDeque<Runnable>()
        val registry=NativeCaptureOwnerRegistry(Executor {tasks.add(it)}){false}
        var completed=false;var created=0
        val comparison=ParkedCaptureComparison(registry,{ _,_->
            created++
            object:NumericCaptureRun {
                override val numbers:LongArray?=null
                override fun begin(){}
                override fun cancel(){}
                override fun pollClosedJoin()=if(completed)NativeCaptureOwnerRegistry.Receipt(2,0,0,0,false) else null
                override fun disposeJoined(){}
            }
        },{true})
        var park=false;var stopped=false;var returns=0
        val controller=MicrophoneDiagnosticController(automotive=false,eligibility={true}).also {
            it.parkedComparison=comparison;it.parkedEligibility={stopped}
            it.stopProjection={stopped=true};it.returnProjection={returns++}
        }
        fun drain(){while(tasks.isNotEmpty())tasks.remove().run()}
        compose.setContent { MaterialTheme { MicrophoneDiagnosticPanel(controller) } }
        try {
            val start=compose.onNodeWithTag("mic-parked-compare")
            start.assertIsNotEnabled()
            compose.onNodeWithTag("mic-consent").assertDoesNotExist()
            compose.onNodeWithTag("mic-stationary").assertDoesNotExist()
            compose.onNodeWithTag("mic-no-bluetooth").assertDoesNotExist()
            compose.onNodeWithTag("mic-parked-stop-projection").performScrollTo().performClick()
            compose.runOnIdle {controller.refresh()}
            start.performScrollTo().assertIsEnabled().performClick()
            compose.runOnIdle {drain();assertEquals(1,created)}
            start.assertIsNotEnabled()
            compose.onNodeWithTag("mic-cancel").performScrollTo().performClick()
            compose.runOnIdle {
                comparison.poll();drain()
                assertNull(MicrophoneCaptureFence.tryProjectionClaim())
                controller.updateForeground(false)
                completed=true;comparison.poll();drain();comparison.poll();controller.refresh()
                assertFalse(comparison.busy)
            }
            compose.onNodeWithTag("mic-parked-return").performScrollTo().performClick()
            compose.runOnIdle {assertEquals(1,returns)}
        } finally {
            completed=true;comparison.cancel();repeat(3){comparison.poll();drain()}
        }
    }
}
