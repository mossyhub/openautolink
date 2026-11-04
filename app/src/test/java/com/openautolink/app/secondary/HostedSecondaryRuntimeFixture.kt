package com.openautolink.app.secondary

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Looper
import android.view.Display
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.openautolink.app.cluster.*
import com.openautolink.app.data.*
import com.openautolink.app.session.SessionManager
import com.openautolink.app.transport.aasdk.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Typed runtime/prepared-owner fixture. Reflection is limited to installing the same current
 * SessionManager bindings used by its connection path; no retired singular-output API is emulated. */
internal class HostedSecondaryRuntimeFixture(val displayId: Int = 7) {
    private class Store : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }
    private val oldProduct = Build.PRODUCT
    private val oldId = Build.ID
    private val oldRevision = Build.VERSION.INCREMENTAL
    private val app = RuntimeEnvironment.getApplication()
    val display = mockk<Display>(relaxed = true)
    val displays = mockk<DisplayManager>(relaxed = true)
    private val packages = mockk<android.content.pm.PackageManager>(relaxed = true)
    val context: Context
    val preferences = AppPreferences::class.java.getDeclaredConstructor(DataStore::class.java)
        .apply { isAccessible = true }.newInstance(Store())
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val manager: SessionManager
    val session: AasdkSession
    val descriptor = SecondaryDisplayDescriptor(SecondaryOutputId("fixture-gm"), displayId, 1, "GM", 2000, 810, 160,
        SecondaryRoute.GM_HOSTED, SecondaryReason.NONE, "Qualified driver cluster")
    val inventory = MutableStateFlow(SecondaryInventorySnapshot(1, listOf(descriptor)))
    val runtime: SecondaryOutputRuntime
    var prepared: PreparedAaSecondaryOutputs? = null
        private set
    var bridge: ClusterProjectionOutput? = null
        private set
    private var epoch = 0L
    init {
        val profile = ClusterDisplayProfiles.AEGEAN_ORANGE_239
        ReflectionHelpers.setStaticField(Build::class.java, "PRODUCT", profile.vehicle.product)
        ReflectionHelpers.setStaticField(Build::class.java, "ID", profile.vehicle.buildFamily)
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "INCREMENTAL", profile.vehicle.buildRevision.toString())
        every { display.displayId } returns displayId
        every { display.isValid } returns true
        every { display.state } returns Display.STATE_ON
        every { display.flags } returns 0
        every { display.deviceProductInfo } returns null
        every { display.getRealSize(any()) } answers { firstArg<android.graphics.Point>().set(2000, 810) }
        every { displays.displays } returns arrayOf(display)
        every { displays.getDisplay(displayId) } returns display
        context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getPackageManager(): android.content.pm.PackageManager = packages
            override fun getSystemService(name: String): Any? = if (name == Context.DISPLAY_SERVICE) displays else super.getSystemService(name)
        }
        mockkObject(AppPreferences.Companion)
        every { AppPreferences.getInstance(any()) } returns preferences
        runtime = SecondaryOutputRuntime(context, inventory, { mapping, name ->
            ClusterProjectionOutput(mapping, avcDecoderName = name).also { bridge = it }
        }, { _, _ -> "exact.avc" })
        manager = SessionManager(scope, context)
        session = mockk(relaxed = true)
        field("secondaryRuntime\$delegate").set(manager, lazyOf(runtime))
    }
    fun field(name: String) = SessionManager::class.java.getDeclaredField(name).apply { isAccessible = true }
    fun snapshot(selection: ClusterOutputSelection) = SecondaryPreferencesSnapshot(epoch,
        mapOf(descriptor.id to SecondaryOutputPreference(true, AaSecondaryRole.CLUSTER, true, true)),
        ClusterOutputPreferences(selection, selection.mode.takeUnless { it == ClusterDisplayMode.OFF }))
    suspend fun prepare(selection: ClusterOutputSelection): PreparedAaSecondaryOutputs {
        check(await(manager.retireClusterVideoOutput())) { "Previous exact owner has not retired" }
        field("secondaryRetirement").set(manager, null)
        val result = runtime.prepareAndroidAuto(++epoch, snapshot(selection))
        prepared = result
        field("preparedSecondaryOutputs").set(manager, result)
        field("_clusterManager").set(manager, result.hostedManager)
        @Suppress("UNCHECKED_CAST")
        (field("_clusterLayoutSelectionReason").get(manager) as MutableStateFlow<ClusterLayoutFallbackReason?>).value = result.layoutSelectionReason
        result.bind(session)
        return result
    }
    fun mapping(): ClusterVideoMapping = runtime.mapping(descriptor.id) as ClusterVideoMapping
    fun offer(selection: ClusterOutputSelection): AasdkSecondaryVideoOffer? = runBlocking(Dispatchers.IO) { prepare(selection).offers.singleOrNull() }
    fun close(expectedRetired: Boolean = true) {
        try {
            assertEquals(expectedRetired, await(manager.retireClusterVideoOutput()))
            if (expectedRetired) assertTrue(await(runtime.close()))
        } finally {
            (field("scope").get(manager) as CoroutineScope).cancel(); scope.cancel()
            unmockkAll()
            ReflectionHelpers.setStaticField(Build::class.java, "PRODUCT", oldProduct)
            ReflectionHelpers.setStaticField(Build::class.java, "ID", oldId)
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "INCREMENTAL", oldRevision)
        }
    }
    companion object {
        fun <T> await(result: CompletableFuture<T>): T {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!result.isDone && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
            return result.get(0, TimeUnit.SECONDS)
        }
    }
}
