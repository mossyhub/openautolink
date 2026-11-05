package com.openautolink.app.cluster

import android.app.*
import android.content.Intent
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Looper
import android.view.*
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.openautolink.app.data.*
import com.openautolink.app.secondary.*
import com.openautolink.app.session.SessionManager
import com.openautolink.app.transport.aasdk.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Session connection path with the same direct Activity/TextureView boundary as the runtime
 * tests. The only optional acknowledgment control is on this exact output's retirement;
 * callOriginal always discharges the real GPU work first. No registry/lease reset. */
internal class DirectSessionRuntimeFixture {
    private class Store : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }
    private val oldProduct = Build.PRODUCT
    private val oldId = Build.ID
    private val oldRevision = Build.VERSION.INCREMENTAL
    private val oldContexts = System.getProperty("robolectric.createActivityContexts")
    private val oldProcess = ReflectionHelpers.getStaticField<SecondaryOutputRuntime?>(SecondaryOutputRuntime::class.java,"process")
    val context = RuntimeEnvironment.getApplication() as Application
    val preferences = AppPreferences::class.java.getDeclaredConstructor(DataStore::class.java)
        .apply { isAccessible = true }.newInstance(Store())
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val displayId = ShadowDisplayManager.addDisplay("w2000dp-h810dp-mdpi","Direct session fixture")
    val display = context.getSystemService(DisplayManager::class.java).getDisplay(displayId)
    val descriptor = SecondaryDisplayDescriptor(SecondaryOutputId("direct-session"),displayId,1,"GM",2000,810,160,
        SecondaryRoute.GM_HOSTED,SecondaryReason.NONE,"Qualified driver cluster")
    val inventory = MutableStateFlow(SecondaryInventorySnapshot(1,listOf(descriptor)))
    val retireCalls = AtomicInteger()
    val detached = AtomicInteger()
    val initialized = AtomicInteger()
    var initializeGate: java.util.concurrent.CountDownLatch? = null
    var retirementAck: CompletableFuture<Boolean>? = null
    val bridges = java.util.concurrent.CopyOnWriteArrayList<ClusterProjectionOutput>()
    private val controlledBridges = mutableListOf<ClusterProjectionOutput>()
    val physical = mutableListOf<ActivityController<ClusterDirectVideoActivity>>()
    private val textures = mutableListOf<Pair<TextureView,SurfaceTexture>>()
    var attachHosts = true
    private val launches = ArrayDeque<Intent>()
    val runtime: SecondaryOutputRuntime
    val manager: SessionManager
    val session = mockk<AasdkSession>(relaxed = true)
    var prepared: PreparedAaSecondaryOutputs? = null
        private set
    init {
        System.setProperty("robolectric.createActivityContexts","true")
        ReflectionHelpers.setStaticField(Build::class.java,"PRODUCT",ClusterDisplayProfiles.AEGEAN_ORANGE_239.vehicle.product)
        shadowOf(display).setFlags(Display.FLAG_PRESENTATION); shadowOf(display).setState(Display.STATE_ON)
        mockkObject(AppPreferences.Companion)
        every { AppPreferences.getInstance(any()) } returns preferences
        runtime = SecondaryOutputRuntime(context,inventory,{ mapping,name ->
            val driver = object : ClusterRenderDriver {
                override fun initialize() {
                    initialized.incrementAndGet()
                    initializeGate?.let { check(it.await(5,TimeUnit.SECONDS)) }
                }
                override fun configure(config: ClusterCodecConfig) = Unit
                override fun submit(frame: ClusterEncodedFrame) = true
                override fun poll() = ClusterRenderProgress(0,0)
                override fun stopDecoder() = Unit
                override fun clearOutput() = Unit
                override fun detach() = true.also { detached.incrementAndGet() }
                override fun parkWindow() = true
                override fun resumeWindow(surface: Surface) = true
            }
            ClusterProjectionOutput(mapping,ClusterRenderDriverFactory { _,_ -> driver },name).also { bridges += it }
        },{ _,_ -> "exact.avc" })
        ReflectionHelpers.setStaticField(SecondaryOutputRuntime::class.java,"process",runtime)
        manager = SessionManager(scope,context)
    }
    fun controlRetirement(acknowledgment: CompletableFuture<Boolean>) {
        // Install only after successful preparation, outside its original deadline. No
        // constructor-wide interception and no substitute for the actual renderer cleanup.
        val bridge = requireNotNull(owner()?.bridge)
        retirementAck = acknowledgment
        mockkObject(bridge)
        controlledBridges += bridge
        every { bridge.retire(any()) } answers {
            retireCalls.incrementAndGet()
            val original = callOriginal() as CompletableFuture<Boolean>
            val ack = retirementAck
            if (ack == null) original else original.thenCompose { cleared ->
                if (cleared) ack else CompletableFuture.completedFuture(false)
            }
        }
    }
    fun field(name: String) = SessionManager::class.java.getDeclaredField(name).apply { isAccessible = true }
    fun owner(): SecondaryOutputRuntime.RuntimeOutput? {
        val lock = ReflectionHelpers.getField<Any>(runtime,"lock")
        return synchronized(lock) {
            ReflectionHelpers.getField<Map<SecondaryOutputId,SecondaryOutputRuntime.RuntimeOutput>>(runtime,"owners")[descriptor.id]
        }
    }
    fun pump(condition: () -> Boolean) {
        val deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime()<deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            var intent = shadowOf(context).nextStartedActivity
            while (intent != null) { launches.addLast(intent); intent = shadowOf(context).nextStartedActivity }
            if (attachHosts) while (launches.isNotEmpty()) attach(launches.removeFirst())
            Thread.sleep(2)
        }
        assertTrue("Actual direct session boundary timed out: ${runtime.statuses.value}",condition())
    }
    private fun attach(intent: Intent) {
        assertEquals(ClusterDirectVideoActivity::class.java.name,intent.component?.className)
        val controller = ActivityController.of(ClusterDirectVideoActivity(),intent,
            ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()).create().start().resume().visible()
        physical += controller
        val activity = controller.get()
        if (activity.isFinishing) return
        assertEquals(displayId,activity.display!!.displayId)
        val view = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as TextureView
        activity.window.decorView.measure(View.MeasureSpec.makeMeasureSpec(2000,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(810,View.MeasureSpec.EXACTLY))
        activity.window.decorView.layout(0,0,2000,810); view.layout(0,0,2000,810)
        ClusterDirectVideoLifecycleTest.showOrdinaryWindow(view)
        val texture = SurfaceTexture(0)
        textures += view to texture
        ClusterDirectVideoLifecycleTest.TextureBoundary.textures[view] = texture
        view.surfaceTextureListener!!.onSurfaceTextureAvailable(texture,2000,810)
    }
    fun freeze(selection: ClusterOutputSelection, layout: SecondaryLayoutPreference? = null): SecondaryPreferencesSnapshot = runBlocking {
        preferences.setClusterOutputSelection(selection)
        val old = preferences.getSecondaryDisplayPreferences()
        preferences.updateSecondaryDisplayPreferences(old.revision,mapOf(descriptor.id to
            SecondaryOutputPreference(true,AaSecondaryRole.CLUSTER,true,true,layout)))
        preferences.getSecondaryDisplayPreferences()
    }
    fun prepare(selection: ClusterOutputSelection, layout: SecondaryLayoutPreference? = null): PreparedAaSecondaryOutputs {
        freeze(selection,layout)
        val pending = scope.async { manager.prepareAndroidAutoSecondaryOutputs(context) }
        pump { pending.isCompleted }
        return runBlocking { pending.await() }.also { prepared = it; it.bind(session) }
    }
    fun offer(selection: ClusterOutputSelection) = prepare(selection).offers.singleOrNull()
    fun mapping(): VideoMapping = requireNotNull(runtime.mapping(descriptor.id))
    fun <T> await(result: CompletableFuture<T>): T { pump { result.isDone }; return result.get(0,TimeUnit.SECONDS) }
    fun close(expectedRetired: Boolean = true) {
        val managerJob=requireNotNull((field("scope").get(manager) as CoroutineScope).coroutineContext[Job])
        val fixtureJob=requireNotNull(scope.coroutineContext[Job])
        var acknowledged=false
        try {
            assertEquals(expectedRetired,await(manager.retireClusterVideoOutput()))
            assertTrue("Current runtime reservation closes after exact cleanup",await(runtime.close()))
            acknowledged=true
        } finally {
            // SessionManager owns a separate scope; cancelling/joining the fixture is not its retirement.
            managerJob.cancel();fixtureJob.cancel()
            pump { managerJob.isCompleted && fixtureJob.isCompleted }
            runBlocking { managerJob.join();fixtureJob.join() }
            textures.forEach { (view,texture) -> view.surfaceTextureListener?.onSurfaceTextureDestroyed(texture) }
            physical.forEach { if (!it.get().isDestroyed) it.pause().stop().destroy() }
            textures.forEach { (view,texture) -> ClusterDirectVideoLifecycleTest.TextureBoundary.textures.remove(view); texture.release() }
            textures.clear();physical.clear()
            if(acknowledged) {
                val registry=ReflectionHelpers.getField<SecondaryHostRegistry>(runtime,"registry")
                val runtimeJob=requireNotNull(ReflectionHelpers.getField<CoroutineScope>(runtime,"scope").coroutineContext[Job])
                val registryJob=requireNotNull(ReflectionHelpers.getField<CoroutineScope>(registry,"scope").coroutineContext[Job])
                val intake=ReflectionHelpers.getField<Thread>(registry,"intakeThread")
                // Production close cancels these workers; observe and join, never manufacture success.
                pump { runtimeJob.isCompleted && registryJob.isCompleted && !intake.isAlive }
                runBlocking { runtimeJob.join();registryJob.join() }
                synchronized(SecondaryOutputRuntime.Companion) {
                    assertSame("Do not release another runtime's process pointer",runtime,SecondaryOutputRuntime.peekForDiagnostics())
                    ReflectionHelpers.setStaticField(SecondaryOutputRuntime::class.java,"process",oldProcess)
                }
                ShadowDisplayManager.removeDisplay(displayId)
                ReflectionHelpers.setStaticField(Build::class.java,"PRODUCT",oldProduct)
                ReflectionHelpers.setStaticField(Build::class.java,"ID",oldId)
                ReflectionHelpers.setStaticField(Build.VERSION::class.java,"INCREMENTAL",oldRevision)
                if (oldContexts == null) System.clearProperty("robolectric.createActivityContexts")
                else System.setProperty("robolectric.createActivityContexts",oldContexts)
                controlledBridges.forEach { unmockkObject(it) }
                unmockkObject(AppPreferences.Companion)
            }
            // Failed/unacknowledged close retains this pointer and its mocks for safe retry/diagnosis.
        }
    }
}
