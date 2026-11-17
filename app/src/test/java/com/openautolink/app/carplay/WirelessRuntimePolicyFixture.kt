package com.openautolink.app.carplay

import android.Manifest
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.view.Surface
import com.openautolink.app.ui.settings.CarPlayMfiAssetBootstrap
import com.openautolink.carplaylab.SavedWifiPassword
import com.openautolink.carplaylab.media.ProjectionReceiver
import com.openautolink.carplaylab.media.CarPlayDisplayPreferences
import com.openautolink.carplaylab.pairing.PhoneActivation
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.mfi.*
import com.shilapi.xcertplay.transport.*
import io.mockk.*
import java.net.NetworkInterface
import java.net.Inet4Address
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real OalCarPlayRuntime.runConnection and steady-state loop; only wire/crypto/OS boundaries fake. */
class WirelessRuntimePolicyFixture(context: Context) : AutoCloseable {
    val receiver = mockk<ProjectionReceiver>(relaxed = true)
    val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
    val lastStatus = java.util.concurrent.atomic.AtomicReference("not started")
    val runThread = java.util.concurrent.atomic.AtomicReference<Thread?>()
    val iterations = AtomicInteger()
    val bootstrapRetired = java.util.concurrent.atomic.AtomicBoolean()
    val entered = CountDownLatch(1)
    val resume = CountDownLatch(1)
    val runtime: OalCarPlayRuntime
    init {
        val nic = NetworkInterface.getNetworkInterfaces().toList().first { n ->
            n.isUp && !n.isLoopback && n.inetAddresses.toList().any { it is Inet4Address }
        }
        val local = nic.inetAddresses.toList().filterIsInstance<Inet4Address>().first().hostAddress!!
        val device = mockk<BluetoothDevice>(relaxed = true)
        val adapter = mockk<BluetoothAdapter>(relaxed = true)
        val manager = mockk<BluetoothManager>()
        val socket = mockk<BluetoothSocket>(relaxed = true)
        val channel = mockk<Iap2Session>(relaxed = true)
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.noBackupFilesDir } returns context.noBackupFilesDir
        every { ctx.applicationContext } returns ctx
        every { ctx.checkSelfPermission(any()) } returns PackageManager.PERMISSION_GRANTED
        every { ctx.getSystemService(BluetoothManager::class.java) } returns manager
        every { ctx.getSystemService(Context.NSD_SERVICE) } returns mockk<android.net.nsd.NsdManager>(relaxed = true)
        every { ctx.getSystemService(android.net.nsd.NsdManager::class.java) } returns mockk(relaxed = true)
        every { manager.adapter } returns adapter
        every { adapter.isEnabled } returns true
        every { adapter.bondedDevices } returns setOf(device)
        every { device.address } returns "AA:BB:CC:DD:EE:10"
        every { device.bondState } returns BluetoothDevice.BOND_BONDED
        every { device.createRfcommSocketToServiceRecord(any()) } returns socket
        mockkConstructor(SavedWifiPassword::class, CarPlayMfiAssetBootstrap::class,
            Iap2IdentificationClient::class, Iap2MfiAuthenticationClient::class,
            PhoneActivation::class, BluetoothRfcommDuplexStream::class)
        mockkObject(LocalMfiAuthenticationClient.Companion, Iap2Session.Companion)
        every { anyConstructed<SavedWifiPassword>().load() } returns "fixture-password"
        every { anyConstructed<CarPlayMfiAssetBootstrap>().install() } returns CarPlayMfiAssetBootstrap.Result.NOT_BUNDLED
        every { LocalMfiAuthenticationClient.load(any(), any()) } returns mockk(relaxed = true)
        every { Iap2Session.openWireless(any(), any(), any()) } returns channel
        every { anyConstructed<Iap2IdentificationClient>().identify(any(), any(), any()) } just Runs
        every { anyConstructed<Iap2MfiAuthenticationClient>().run(any(), any(), any(), any()) } just Runs
        every { anyConstructed<PhoneActivation>().start() } just Runs
        every { anyConstructed<BluetoothRfcommDuplexStream>().awaitClosed(any()) } returns true
        // The runtime's constructor factory remains real; forward its receiver API to one wire fake.
        every { receiver.setHostUiHandler(any()) } just Runs
        every { receiver.clusterVideoPilotStatus } returns null
        every { receiver.displayConfiguration } returns CarPlayDisplayPreferences()
        every { receiver.inputAdvertised } returns false
        every { receiver.setRuntimeIdentification(any()) } just Runs
        every { receiver.authorizeControllerEnrollment(any()) } just Runs
        every { receiver.start(any<com.openautolink.carplaylab.network.CarPlayNetworkSelection>()) } just Runs
        every { receiver.port } returns 12345
        every { receiver.pairingId } returns "fixture"
        every { receiver.publicKeyHex } returns "00".repeat(32)
        every { receiver.setRuntimeReply(any()) } just Runs
        every { receiver.runtimeFailureStage } returns null
        every { receiver.runtimeReady } answers { iterations.incrementAndGet(); true }
        every { receiver.bluetoothHandoffRequested } returns false
        every { receiver.close() } just Runs
        every { receiver.awaitClosed(any()) } returns true
        every { channel.recv(any()) } answers {
            entered.countDown()
            check(resume.await(10, TimeUnit.SECONDS))
            Thread.sleep(5)
            null
        }
        every { channel.close() } answers { bootstrapRetired.set(true); Unit }
        every { channel.isClosed } returns false
        every { channel.awaitClosed(any()) } returns true
        runtime = spyk(OalCarPlayRuntime(ctx, CarPlayRuntimeConfig(device.address, "192.0.2.1", nic.name,
            false, "fixture", "AA:BB:CC:DD:EE:20", 2412, CarPlayDisplayPreferences(), networkFamily = "ipv4"), mockk<Surface>(), { lastStatus.set(it) }))
        every { runtime.run() } answers {
            runThread.set(Thread.currentThread())
            try { callOriginal() } catch (problem: Throwable) { failure.set(problem); throw problem }
        }
        every { runtime.createClusterReceiver(any()) } answers {
            val install = OalCarPlayRuntime::class.java.getDeclaredMethod("ownReceiver", Function0::class.java).apply { isAccessible = true }
            install.invoke(runtime, { receiver }) as ProjectionReceiver
        }
    }
    override fun close() {
        resume.countDown()
        runtime.cancel(); runtime.close()
        unmockkConstructor(SavedWifiPassword::class, CarPlayMfiAssetBootstrap::class,
            Iap2IdentificationClient::class, Iap2MfiAuthenticationClient::class,
            PhoneActivation::class, BluetoothRfcommDuplexStream::class)
        unmockkObject(LocalMfiAuthenticationClient.Companion, Iap2Session.Companion)
    }
}
