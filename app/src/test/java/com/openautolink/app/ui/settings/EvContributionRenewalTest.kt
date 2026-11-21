package com.openautolink.app.ui.settings

import com.openautolink.app.data.EvContributionScope
import com.openautolink.app.data.EvGrantDecision
import com.openautolink.app.diagnostics.EvContributionConsentBinding
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class EvContributionRenewalTest {
    private val url = "https://receiver.invalid:8443/upload?lane=compact"
    private val token = "synthetic-token"

    @Test fun optionsUsesExactEndpointAndOnlyTheExistingCredential() = runBlocking {
        var opened: URL? = null
        val reply = Reply(URL(url), 204, listOf("3"))
        val probe = EvReceiverCapability(openConnection = { opened = it; reply })
        assertTrue(probe.supportsAccuracy(url, token))
        assertEquals(url, opened?.toExternalForm())
        assertEquals("OPTIONS", reply.requestMethod)
        assertEquals(mapOf("X-Upload-Token" to listOf(token)), reply.requestProperties)
        assertFalse(reply.instanceFollowRedirects)
        assertFalse(reply.useCaches)
        assertFalse(reply.doOutput)
        assertTrue(reply.connectTimeout > 0 && reply.readTimeout > 0)
        assertTrue(reply.disconnected)
    }

    @Test fun onlyAnUnambiguous204Schema3ResponseAuthorizes() {
        val bad = listOf(
            200 to listOf("3"), 302 to listOf("3"), 307 to listOf("3"), 401 to listOf("3"),
            404 to emptyList(), 204 to emptyList(), 204 to listOf("2"), 204 to listOf("03"),
            204 to listOf("3, 3"), 204 to listOf("3", "3"),
        )
        for ((status, schemas) in bad) {
            assertFalse("status=$status schemas=$schemas", EvReceiverCapability(
                openConnection = { Reply(it, status, schemas) },
            ).request(url, token))
        }
        assertFalse(EvReceiverCapability(openConnection = { throw IOException("private endpoint") }).request(url, token))
        for (invalid in listOf("http://receiver.invalid/upload", "https://user@receiver.invalid/upload",
            "https://receiver.invalid/upload#fragment", "https://receiver.invalid", "https://receiver.invalid/a/../upload")) {
            assertFalse(EvReceiverCapability(openConnection = { error("must not open $invalid") }).request(invalid, token))
        }
        assertFalse(EvReceiverCapability(openConnection = { error("must not open without a token") }).request(url, ""))
    }

    @Test fun unsupportedReceiverLeavesExistingLegacyGrantInPlace() = runBlocking {
        val fixture = Fixture(legacy = true)
        val legacy = fixture.stored.binding
        assertEquals(EvRenewalResult.RECEIVER_UNVERIFIED, fixture.renew { _, _ -> false })
        assertEquals(legacy, fixture.stored.binding)
        assertEquals(0, fixture.writes)
    }

    @Test fun explicitVerifiedRenewalPersistsOnlyTheExactAccuracyGrant() = runBlocking {
        for (legacy in listOf(false, true)) {
            val fixture = Fixture(legacy)
            assertEquals(0, fixture.writes)
            assertEquals(EvRenewalResult.ENABLED, fixture.renew { target, credential ->
                assertEquals(url, target)
                assertEquals(token, credential)
                true
            })
            val grant = EvContributionConsentBinding.decode(fixture.stored.binding)!!
            assertTrue(grant.accuracy)
            assertTrue(grant.matches(url, token))
            assertFalse(grant.matches("$url-other", token))
            assertFalse(grant.matches(url, "$token-other"))
            assertEquals(1, fixture.writes)
        }
    }

    @Test fun delayedSuccessCannotSurviveRetirementConfigChangesOrLeavingPark() = runBlocking {
        val changes = listOf<Pair<EvRenewalResult, (Fixture) -> Unit>>(
            EvRenewalResult.SUPERSEDED to { it.generation++ },
            EvRenewalResult.SUPERSEDED to { it.stored = EvContributionScope(url, token, false, "") },
            EvRenewalResult.SUPERSEDED to { it.stored = EvContributionScope("$url-other", token, true, it.stored.binding) },
            EvRenewalResult.SUPERSEDED to { it.stored = EvContributionScope(url, "$token-other", true, it.stored.binding) },
            EvRenewalResult.NOT_PARKED to { it.parked = false },
        )
        for ((expected, change) in changes) {
            val fixture = Fixture(legacy = true)
            val entered = CompletableDeferred<Unit>()
            val reply = CompletableDeferred<Boolean>()
            val pending = async { fixture.renew { _, _ -> entered.complete(Unit); reply.await() } }
            entered.await()
            change(fixture)
            reply.complete(true)
            assertEquals(expected, pending.await())
            assertEquals(0, fixture.writes)
        }
    }

    @Test fun leavingSettingsCancelsThePendingGrant() = runBlocking {
        val fixture = Fixture(legacy = false)
        val entered = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Boolean>()
        val pending = async { fixture.renew { _, _ -> entered.complete(Unit); reply.await() } }
        entered.await()
        pending.cancelAndJoin()
        reply.complete(true)
        assertEquals(0, fixture.writes)
    }

    @Test fun noProbeWithoutFreshPark() = runBlocking {
        val fixture = Fixture(legacy = false).apply { parked = false }
        assertEquals(EvRenewalResult.NOT_PARKED, fixture.renew { _, _ -> error("must not probe") })
        assertEquals(0, fixture.writes)
    }

    private inner class Fixture(legacy: Boolean) {
        var stored = EvContributionScope(url, token, legacy,
            if (legacy) EvContributionConsentBinding.create(url, token)!!.encode() else "")
        var writes = 0
        var parked = true
        var generation = 1L
        suspend fun renew(probe: suspend (String, String) -> Boolean) = EvContributionRenewal(
            { ++generation }, { it == generation }, { stored }, { parked }, probe,
            { requestGeneration, expected, binding, rawPark ->
                when {
                    requestGeneration != generation || !expected.sameAs(stored) -> EvGrantDecision.SUPERSEDED
                    !rawPark() -> EvGrantDecision.NOT_PARKED
                    else -> {
                        writes++
                        stored = EvContributionScope(url, token, true, binding)
                        EvGrantDecision.GRANTED
                    }
                }
            },
        ).renew()
    }

    private class Reply(url: URL, private val status: Int, private val schemas: List<String>) : HttpsURLConnection(url) {
        @Volatile var disconnected = false
        override fun getResponseCode() = status
        override fun getHeaderFields(): Map<String, List<String>> = mapOf("x-oaL-eV-scHema" to schemas)
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun connect() = Unit
        override fun getCipherSuite(): String = error("not used")
        override fun getLocalCertificates(): Array<java.security.cert.Certificate>? = null
        override fun getServerCertificates(): Array<java.security.cert.Certificate> = error("not used")
    }
}
