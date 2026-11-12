package com.openautolink.companion.connection

import android.content.ContextWrapper
import android.content.Intent
import com.openautolink.companion.diagnostics.CompanionLog
import io.mockk.*
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],application=android.app.Application::class)
class ProxyPublicationWatchdogTest {
 private val launches=AtomicInteger()
 private val context get()=object:ContextWrapper(RuntimeEnvironment.getApplication()) {
  override fun sendBroadcast(intent:Intent){launches.incrementAndGet()}
  override fun startActivity(intent:Intent){}
 }
 @Test fun predecessorCannotOverwriteSuccessor()=RegressionCases.publication(context)
 @Test fun admittedConnectedCannotOutliveStop()=RegressionCases.connectedAfterStop(context)
 @Test fun reentrantSuccessorPublishesLast()=RegressionCases.reentrantSuccessor(context)
 @Test fun currentConnectedPublishes()=RegressionCases.currentEvent(context)
 private fun watchdog(kind:String) {
  mockkObject(CompanionLog)
  var hook:((String)->Unit)?=null
  every {CompanionLog.w(any(),any())} answers {hook?.invoke(secondArg())}
  try {RegressionCases.watchdog(context,kind,{hook=it},{launches.get()})}finally{unmockkObject(CompanionLog)}
 }
 @Test fun elapsedWatchdogCannotReplaceSuccessor()=watchdog("replacement")
 @Test fun elapsedWatchdogCannotLaunchAfterConnected()=watchdog("connected")
 @Test fun elapsedWatchdogCannotLaunchAfterStop()=watchdog("stop")
 @Test fun currentWatchdogRetriesThenCyclesCarOnly()=RegressionCases.retryExhaustion(context,{launches.get()})
}
