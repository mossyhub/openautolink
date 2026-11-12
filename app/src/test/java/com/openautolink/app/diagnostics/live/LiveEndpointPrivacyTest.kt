package com.openautolink.app.diagnostics.live

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import com.openautolink.app.ui.testing.createOalComposeRule
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32,35],application=Application::class,manifest=Config.NONE)
class LiveEndpointPrivacyTest {
    private val process=LiveProcessTestFixture()
    private val compose=createOalComposeRule()
    @get:Rule val rules:org.junit.rules.TestRule=org.junit.rules.RuleChain.outerRule(process).around(compose)
    private val app get()=RuntimeEnvironment.getApplication() as Application
    private val record get()=File(app.noBackupFilesDir,"oal-live-launch-consent.bin")
    private fun await(p:()->Boolean){val end=System.nanoTime()+5_000_000_000;while(!p()&&System.nanoTime()<end)Thread.sleep(5);assertTrue(p())}

    @Test fun cleanSettingsShowsBlankAndRequiresExplicitHttpsApply() {
        assertFalse(record.exists())
        LiveDiagnostics.initialize(app)
        compose.waitUntil(5000){LiveDiagnostics.ready.value}
        compose.setContent{MaterialTheme{Column(Modifier.verticalScroll(rememberScrollState())){LiveDiagnosticsSettings()}}}
        val server=compose.onNodeWithTag("liveDiagnosticsServer")
        server.performScrollTo().assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText,androidx.compose.ui.text.AnnotatedString("")))
        assertNull(LiveDiagnostics.endpoint.value)
        compose.onNodeWithTag("liveDiagnosticsPairCode").assertIsNotEnabled()
        compose.onNodeWithTag("liveDiagnosticsEnable").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("liveDiagnosticsAutoConnect").performScrollTo().assertIsNotEnabled().assertIsOff()
        server.performScrollTo().performTextReplacement("http://localhost/oal")
        compose.onNodeWithTag("liveDiagnosticsApplyServer").assertIsNotEnabled()
        server.performTextReplacement("https://localhost:10443/oal")
        compose.onNodeWithTag("liveDiagnosticsEnable").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("liveDiagnosticsApplyServer").performScrollTo().performClick()
        compose.waitUntil(5000){!LiveDiagnostics.busy.value && LiveDiagnostics.endpoint.value!=null}
        compose.onNodeWithTag("liveDiagnosticsEnable").performScrollTo().assertIsEnabled()
        assertFalse(LiveConsentStore(app).load())
    }

    @Test fun missingAndCorruptStorageCannotCreateWireOrReadPairingEvenForValidInvitation() {
        val states=listOf<ByteArray?>(null,byteArrayOf(),byteArrayOf(2),"corrupt".toByteArray(),
            "{\"endpoint\":\"http://localhost/oal\",\"enabled\":true}".toByteArray(),
            "{\"endpoint\":\"\",\"enabled\":true}".toByteArray())
        states.forEach { bytes->
            record.delete();if(bytes!=null)record.writeBytes(bytes)
            val store=LiveConsentStore(app)
            assertNull("absent or invalid record must not select historical server",store.loadEndpoint())
            assertFalse(store.load())
            val calls=AtomicInteger();val reads=AtomicInteger();val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val owner=LiveDiagnosticsOwner(app,scope,object:LiveCredentials {
                override fun load():String?{reads.incrementAndGet();return "orphaned-legacy-credential"}
                override fun save(credential:String){error("no pairing writes")}
                override fun clear(){error("no pairing deletes")}
            },store,endpointWireFactory={calls.incrementAndGet();error("no wire before Apply")},networkFactory={calls.incrementAndGet();error("no network before Apply")})
            try {
                owner.restore();await{owner.ready.value};owner.enable("one-two-three-four-five")
                await{owner.status.value.contains("Apply")}
                owner.updateAutoConnect(true);await{owner.status.value.contains("Apply")}
                owner.revoke();await{owner.status.value.contains("Apply")}
                assertFalse(owner.autoConnect.value);assertFalse(store.load());assertNull(store.loadEndpoint())
                assertEquals(0,calls.get());assertEquals(0,reads.get())
            }finally{owner.stop();runBlocking{scope.coroutineContext[Job]!!.cancelAndJoin()}}
        }
    }

    @Test fun noFacadeDefaultAndCorruptTypedOrOversizedRecordsFailClosed() {
        assertNull(LiveDiagnostics.endpoint.value)
        listOf(
            "{\"endpoint\":\"https://custom.example/oal\",\"enabled\":\"true\"}",
            "{\"endpoint\":\"https://custom.example/oal\",\"enabled\":1}",
            " ".repeat(8193)
        ).forEach { text ->
            record.writeText(text)
            assertNull(LiveConsentStore(app).loadEndpoint())
            assertFalse(LiveConsentStore(app).load())
        }
        record.delete()
        assertTrue(runCatching{LiveConsentStore(app).save(true)}.isFailure)
        assertNull(LiveConsentStore(app).loadEndpoint())
    }

    @Test fun onlyPersistedHistoricalChoiceMigratesAndCustomReopenKeepsNamespace() {
        val store=LiveConsentStore(app)
        record.writeBytes(byteArrayOf(0));assertEquals(LiveEndpoint.LEGACY,store.loadEndpoint());assertFalse(store.load())
        record.writeBytes(byteArrayOf(1));assertEquals(LiveEndpoint.LEGACY,store.loadEndpoint());assertTrue(store.load())
        val custom=LiveEndpoint.parse("https://CUSTOM.example:443/support/")
        store.saveEndpoint(custom);store.save(true)
        assertEquals(custom,LiveConsentStore(app).loadEndpoint());assertTrue(LiveConsentStore(app).load())
        record.writeText("{\"endpoint\":\"https://custom.example/support\"}")
        assertNull(store.loadEndpoint());assertFalse(store.load())
    }
}
