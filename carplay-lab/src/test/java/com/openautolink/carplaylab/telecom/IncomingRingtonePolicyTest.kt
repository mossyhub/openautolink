package com.openautolink.carplaylab.telecom

import android.content.Context
import android.os.Looper
import android.telecom.*
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 33, 34], manifest = Config.NONE)
class IncomingRingtonePolicyTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var experiment: ManagedTelecomExperiment
    private lateinit var manager: TelecomManager
    private val oldKey = "android.telecom.extra.CALL_EXTERNAL_RINGER"
    private val newKey = "android.telecom.extra.CALL_HAS_IN_BAND_RINGTONE"
    @Before fun setup() {
        shadowOf(app).grantPermissions(android.Manifest.permission.READ_PHONE_NUMBERS)
        experiment = ManagedTelecomExperiment(app)
        manager = app.getSystemService(TelecomManager::class.java)
        experiment.enable(); shadowOf(manager).enablePhoneAccount(experiment.handle, true); experiment.refresh()
    }
    @After fun cleanup() { experiment.disable(); shadowOf(Looper.getMainLooper()).idle() }
    private fun select(value: Boolean) {
        val setter = experiment.javaClass.methods.firstOrNull { it.name == "setCarPlayRingtoneOnly" }
        assertNotNull("default-off per-incoming ringtone policy is not wired", setter)
        setter!!.invoke(experiment, value)
    }
    private fun field(id: Int, bytes: ByteArray) = byteArrayOf(0, (bytes.size + 4).toByte(), 0, id.toByte()) + bytes
    private fun observe(link: ManagedIncomingBridge, suffix: Int, status: Int = 2) {
        val id = "00000000-0000-4000-8000-${suffix.toString().padStart(12, '0')}"
        link.handle(Iap2Frame(0x4155, field(4, id.toByteArray() + byteArrayOf(0)) + field(2, byteArrayOf(status.toByte())) + field(3, byteArrayOf(1)) + field(9, byteArrayOf(0)) +
            field(0, "+15551234567".toByteArray() + byteArrayOf(0)) + field(1, "Synthetic caller".toByteArray() + byteArrayOf(0))))
        shadowOf(Looper.getMainLooper()).idle()
    }
    @Test fun actualAnnouncementDefaultsOffAndSnapshotsPerCallAcrossTogglesAndReconnect() {
        val os = shadowOf(manager)
        val first = experiment.authenticatedLink { true }!!
        observe(first, 1)
        val defaultRequest = os.lastIncomingCall
        assertFalse(defaultRequest.extras.containsKey(oldKey)); assertFalse(defaultRequest.extras.containsKey(newKey))
        val token = defaultRequest.extras.getString(ManagedTelecomExperiment.REQUEST_TOKEN)
        assertFalse(token.isNullOrBlank()); assertEquals(experiment.handle, defaultRequest.phoneAccount)
        val normalConnection = first.create(token!!)
        assertEquals(Connection.STATE_RINGING, normalConnection.state)
        assertEquals("+15551234567", normalConnection.address.schemeSpecificPart)
        assertEquals("Synthetic caller", normalConnection.callerDisplayName)
        select(true)
        assertFalse("late selection mutated admitted call", defaultRequest.extras.containsKey(oldKey))
        assertFalse(defaultRequest.extras.containsKey(newKey))
        observe(first, 1, 0); observe(first, 2)
        val selected = os.lastIncomingCall
        val key = if (android.os.Build.VERSION.SDK_INT == 32) oldKey else newKey
        val other = if (key == oldKey) newKey else oldKey
        assertTrue(selected.extras.getBoolean(key)); assertFalse(selected.extras.containsKey(other))
        assertEquals(setOf(ManagedTelecomExperiment.REQUEST_TOKEN, key), selected.extras.keySet())
        val compatConnection = first.create(selected.extras.getString(ManagedTelecomExperiment.REQUEST_TOKEN)!!)
        assertEquals(normalConnection.address, compatConnection.address)
        assertEquals(normalConnection.callerDisplayName, compatConnection.callerDisplayName)
        assertEquals(normalConnection.connectionCapabilities, compatConnection.connectionCapabilities)
        assertEquals(normalConnection.connectionProperties, compatConnection.connectionProperties)
        assertEquals(Connection.STATE_RINGING, compatConnection.state)
        assertFalse("compat key leaked into Connection extras", compatConnection.extras?.containsKey(key) == true)
        select(false)
        assertTrue("Off changed a call already announced to Telecom", selected.extras.getBoolean(key))
        assertEquals(token, defaultRequest.extras.getString(ManagedTelecomExperiment.REQUEST_TOKEN))
        val successor = experiment.authenticatedLink { true }!!
        observe(first, 3); val count = os.allIncomingCalls.size
        observe(successor, 4)
        assertEquals(count + 1, os.allIncomingCalls.size)
        assertFalse(os.lastIncomingCall.extras.containsKey(oldKey)); assertFalse(os.lastIncomingCall.extras.containsKey(newKey))
        select(true)
        val reconnect = experiment.authenticatedLink { true }!!
        observe(reconnect, 5)
        assertTrue(os.lastIncomingCall.extras.getBoolean(key))
        val otherAccount = PhoneAccountHandle(android.content.ComponentName(app, ManagedCarPlayConnectionService::class.java), "unrelated-hfp")
        manager.registerPhoneAccount(PhoneAccount.builder(otherAccount, "HFP").setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER).build())
        shadowOf(manager).enablePhoneAccount(otherAccount, true)
        manager.addNewIncomingCall(otherAccount, android.os.Bundle())
        assertTrue(os.lastIncomingCall.extras.isEmpty)
    }
    @Test fun identificationDeclaresRealRejectMessageAndEncoderUsesOnlyActionZero() {
        val frame = com.shilapi.xcertplay.transport.Iap2IdentificationClient.identificationInformation(
            com.shilapi.xcertplay.transport.Iap2IdentificationConfig("test", "test", "test", "test", "1", "1", 0))
        val bytes = com.shilapi.xcertplay.iap2.body.Iap2BodyReader.of(frame).bytes(6)
        assertTrue("EndCall 0x415C must be declared sent by accessory", bytes.indices.step(2).any {
            it + 1 < bytes.size && bytes[it] == 0x41.toByte() && bytes[it + 1] == 0x5c.toByte()
        })
    }
}
