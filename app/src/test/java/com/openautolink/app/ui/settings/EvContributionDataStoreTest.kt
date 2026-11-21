package com.openautolink.app.ui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.data.EvContributionScope
import com.openautolink.app.data.EvReceiverField
import com.openautolink.app.data.EvReceiverWrite
import com.openautolink.app.diagnostics.EvContributionConsentBinding
import com.openautolink.app.diagnostics.EvFreshParkGate
import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.transport.VehiclePropertyObservation
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EvContributionDataStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val url = "https://receiver.invalid:8443/compact?lane=ev"
    private val token = "fixture-token"

    @Test fun queuedGrantRechecksRawParkAndRetainsLegacyConsent() = heldGrant(EvRenewalResult.NOT_PARKED) { f ->
        f.raw.set(gear(8))
        assertFalse(f.rawPark())
        null
    }

    @Test fun queuedGrantRejectsSupersessionBeforeOffCanWrite() = heldGrant(EvRenewalResult.SUPERSEDED) { f ->
        val off = f.prefs.reserveEvReceiverWrite(EvReceiverField.OFF)
        assertFalse(f.prefs.isEvCaptureScopeCurrent(f.before.revision))
        assertNull(f.prefs.beginEvContributionRenewal())
        off
    }

    @Test fun queuedGrantChecksSavedCredentialInsideDataStore() = heldGrant(EvRenewalResult.SUPERSEDED,
        predecessor = { this[AppPreferences.LOG_UPLOAD_TOKEN] = "replacement-token" },
        expectedToken = "replacement-token") { null }

    @Test fun parkedSameScopeQueuedGrantPersistsExactV3Binding() = heldGrant(EvRenewalResult.ENABLED) { null }

    @Test fun cancelledPaneCannotWriteQueuedGrant() = heldGrant(null) { null }

    @Test fun delayedUnauthorizedClearCannotEraseAnIdenticalButNewerGrant() = runBlocking {
        withTimeout(10_000) {
            withStore { f ->
                val renewal = EvContributionRenewal(f.prefs, f::rawPark) { _, _ -> true }
                assertEquals(EvRenewalResult.ENABLED, renewal.renew())
                val older = f.prefs.evContributionScope.first()
                assertEquals(EvRenewalResult.ENABLED, renewal.renew())
                val newer = f.prefs.evContributionScope.first()
                assertEquals(older.binding, newer.binding)
                assertTrue(newer.revision > older.revision)
                f.prefs.clearEvContributionIfCurrent(older)
                assertTrue(newer.sameAs(f.prefs.evContributionScope.first()))
                f.prefs.clearEvContributionIfCurrent(newer)
                val after = f.prefs.evContributionScope.first()
                assertFalse(after.enabled)
                assertEquals("", after.binding)
                assertEquals(url, after.url)
                assertEquals(token, after.token)
                assertFalse(f.prefs.isEvCaptureScopeCurrent(newer.revision))
            }
        }
    }

    @Test fun delayedSameFieldWriteCannotOverwriteNewerEndpointOrClearNewerFence() = runBlocking {
        withTimeout(10_000) {
            withStore { f ->
                val older = f.prefs.reserveEvReceiverWrite(EvReceiverField.URL, "https://older.invalid/compact")
                val newer = f.prefs.reserveEvReceiverWrite(EvReceiverField.URL, "https://newer.invalid/compact")
                f.prefs.finishEvReceiverWrite(older)
                assertFalse(f.prefs.isEvCaptureScopeCurrent(f.before.revision))
                f.prefs.applyEvReceiverWrite(newer)
                f.prefs.finishEvReceiverWrite(newer)
                f.prefs.applyEvReceiverWrite(older)
                val after = f.prefs.evContributionScope.first()
                assertEquals("https://newer.invalid/compact", after.url)
                assertEquals(token, after.token)
                assertEquals(f.before.binding, after.binding)
                assertFalse(f.prefs.isEvCaptureScopeCurrent(f.before.revision))
                assertTrue(f.prefs.isEvCaptureScopeCurrent(after.revision))
            }
        }
    }

    private fun heldGrant(
        expected: EvRenewalResult?,
        predecessor: MutablePreferences.() -> Unit = {},
        expectedToken: String = token,
        invalidate: (Fixture) -> EvReceiverWrite?,
    ) = runBlocking {
        withTimeout(10_000) {
            withStore { f ->
                val held = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val precedingWrite = async {
                    f.real.edit { prefs ->
                        held.complete(Unit)
                        release.await()
                        prefs.predecessor()
                    }
                }
                try {
                    held.await()
                    val renewal = EvContributionRenewal(f.prefs, f::rawPark) { target, credential ->
                        assertEquals(url, target)
                        assertEquals(token, credential)
                        true
                    }
                    val queued = async { renewal.renew() }
                    // The production grant reached updateData while the real store's prior transform is held.
                    f.grantSubmitted.await()
                    assertFalse(queued.isCompleted)
                    val off = invalidate(f)
                    if (expected == null) queued.cancelAndJoin()
                    release.complete(Unit)
                    precedingWrite.await()
                    if (expected != null) assertEquals(expected, queued.await())
                    val saved = f.prefs.evContributionScope.first()
                    assertTrue(saved.enabled)
                    assertEquals(url, saved.url)
                    assertEquals(expectedToken, saved.token)
                    val decoded = checkNotNull(EvContributionConsentBinding.decode(saved.binding))
                    if (expected == EvRenewalResult.ENABLED) {
                        assertTrue(decoded.accuracy)
                        assertTrue(decoded.matches(url, token))
                        assertTrue(saved.revision > f.before.revision)
                        assertTrue(f.prefs.isEvCaptureScopeCurrent(saved.revision))
                    } else {
                        assertEquals(f.before.binding, saved.binding)
                        assertFalse(decoded.accuracy)
                        assertEquals(f.before.revision, saved.revision)
                    }
                    if (off != null) {
                        f.prefs.applyEvReceiverWrite(off)
                        f.prefs.finishEvReceiverWrite(off)
                        val disabled = f.prefs.evContributionScope.first()
                        assertFalse(disabled.enabled)
                        assertEquals("", disabled.binding)
                        assertEquals(url, disabled.url)
                        assertEquals(token, disabled.token)
                    }
                } finally {
                    release.complete(Unit)
                }
            }
        }
    }

    private suspend fun withStore(block: suspend (Fixture) -> Unit) {
        val job = SupervisorJob()
        val real = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) {
            File(folder.root, "ev.preferences_pb")
        }
        try {
            real.edit {
                it[AppPreferences.LOG_UPLOAD_URL] = url
                it[AppPreferences.LOG_UPLOAD_TOKEN] = token
                it[AppPreferences.EV_CONTRIBUTION_CONSENT] = true
                it[AppPreferences.EV_CONTRIBUTION_BINDING] = EvContributionConsentBinding.create(url, token)!!.encode()
            }
            val submitted = CompletableDeferred<Unit>()
            val observed = object : DataStore<Preferences> by real {
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                    submitted.complete(Unit)
                    return real.updateData(transform)
                }
            }
            val prefs = AppPreferences::class.java.getDeclaredConstructor(DataStore::class.java)
                .apply { isAccessible = true }.newInstance(observed)
            block(Fixture(real, prefs, submitted, prefs.evContributionScope.first()))
        } finally {
            withContext(NonCancellable) { job.cancelAndJoin() }
        }
    }

    private inner class Fixture(
        val real: DataStore<Preferences>, val prefs: AppPreferences, val grantSubmitted: CompletableDeferred<Unit>,
        val before: EvContributionScope,
    ) {
        val raw = AtomicReference(gear(4))
        fun rawPark(): Boolean = EvFreshParkGate().run {
            observe(raw.get(), 40_000)
            rawParkAuthorization(40_000)
        }
    }

    private fun gear(value: Int) = ControlMessage.VehicleData(gearRaw = value, ignitionState = 4,
        vhalRegistrationGeneration = 1L, evObservationMetadata = mapOf("GEAR_SELECTION" to
            VehiclePropertyObservation(40_000_000_000L, 40_000, 0, registrationGeneration = 1L,
                propertyId = 0x11400400, subscriptionActive = true, sequence = 1L)))
}
