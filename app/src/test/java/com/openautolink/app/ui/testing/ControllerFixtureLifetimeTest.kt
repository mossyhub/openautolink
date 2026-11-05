package com.openautolink.app.ui.testing

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.openautolink.app.carplay.CarPlayCallController
import com.openautolink.app.carplay.CallReceiptFileSyncShadow
import com.openautolink.app.data.AppPreferences
import com.openautolink.carplaylab.telecom.HfpCallGuard
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32,34],application=Application::class,shadows=[CallReceiptFileSyncShadow::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ControllerFixtureLifetimeTest {
    private class Store:DataStore<Preferences> {
        val state=MutableStateFlow<Preferences>(emptyPreferences())
        override val data:Flow<Preferences> = state
        override suspend fun updateData(transform:suspend (Preferences)->Preferences):Preferences = transform(state.value).also {state.value=it}
    }
    @Test fun exactSingletonAndContextStayOwnedUntilRealControllerChildrenAndHfpWorkerFinish() {
        closeCarPlayCallControllerTestProcessAndAwait()
        val prefs=AppPreferences::class.java.getDeclaredConstructor(DataStore::class.java).apply {isAccessible=true}.newInstance(Store())
        val controller=CarPlayCallController(RuntimeEnvironment.getApplication(),prefs)
        synchronized(CarPlayCallController.Companion) {
            assertNull(ReflectionHelpers.getStaticField<CarPlayCallController?>(CarPlayCallController::class.java,"instance"))
            ReflectionHelpers.setStaticField(CarPlayCallController::class.java,"instance",controller)
        }
        val scope=ReflectionHelpers.getField<CoroutineScope>(controller,"scope")
        val job=requireNotNull(scope.coroutineContext[Job])
        val guard=ReflectionHelpers.getField<HfpCallGuard>(controller,"guard")
        val hfp=ReflectionHelpers.getField<ScheduledExecutorService>(guard,"worker")
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val observer=Executors.newSingleThreadExecutor()
        val child=scope.launch(start=CoroutineStart.UNDISPATCHED) {
            try {awaitCancellation()} finally {withContext(NonCancellable) {
                entered.countDown();check(release.await(3,TimeUnit.SECONDS))
            }}
        }
        val held=observer.submit {
            try {
                assertTrue(entered.await(2,TimeUnit.SECONDS))
                // Keep the child held while observing the exact slot, so an unjoined raw close
                // cannot win a scheduling race by completing just after this first observation.
                val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(1)
                while(ReflectionHelpers.getStaticField<CarPlayCallController?>(CarPlayCallController::class.java,"instance")===controller && System.nanoTime()<until)Thread.sleep(2)
                assertSame("Do not forget the owner while its Android poller can still run",controller,
                    ReflectionHelpers.getStaticField<CarPlayCallController?>(CarPlayCallController::class.java,"instance"))
                assertFalse(job.isCompleted)
                assertFalse(child.isCompleted)
            } finally {release.countDown()}
        }
        try {
            closeCarPlayCallControllerTestProcessAndAwait()
            held.get(3,TimeUnit.SECONDS)
            assertTrue(job.isCompleted);assertTrue(child.isCompleted);assertTrue(hfp.isTerminated)
            assertNull(ReflectionHelpers.getStaticField<CarPlayCallController?>(CarPlayCallController::class.java,"instance"))
        } finally {
            release.countDown()
            closeCarPlayCallControllerAndAwait(controller)
            synchronized(CarPlayCallController.Companion) {
                if(ReflectionHelpers.getStaticField<CarPlayCallController?>(CarPlayCallController::class.java,"instance")===controller)
                    ReflectionHelpers.setStaticField(CarPlayCallController::class.java,"instance",null)
            }
            observer.shutdown();assertTrue(observer.awaitTermination(3,TimeUnit.SECONDS))
            assertTrue(com.openautolink.carplaylab.telecom.NativeCallDiagnostics.flush())
        }
    }
}
