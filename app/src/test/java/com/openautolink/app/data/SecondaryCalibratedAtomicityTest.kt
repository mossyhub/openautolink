package com.openautolink.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.openautolink.app.secondary.*
import com.openautolink.app.cluster.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File
import java.io.IOException
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32,34], application=android.app.Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SecondaryCalibratedAtomicityTest {
    private val id=SecondaryOutputId("synthetic-atomic")
    private fun prefs(store:DataStore<Preferences>)=AppPreferences::class.java.getDeclaredConstructor(DataStore::class.java)
        .apply { isAccessible=true }.newInstance(store)
    @Test fun transformIoAndCancellationRollbackMarkerAndRecordAndAllowRetry() = runBlocking {
        val root=Files.createTempDirectory(File("build/tmp").apply { mkdirs() }.toPath(),"calibration-atomic-").toFile()
        val job=SupervisorJob()
        val store=PreferenceDataStoreFactory.create(scope=CoroutineScope(job+Dispatchers.IO),produceFile={ File(root,"store.preferences_pb") })
        val old=SecondaryOutputPreference(false,AaSecondaryRole.AUXILIARY,false,true,aaDensityDpi=200)
        val legacy=ClusterOutputPreferences(ClusterOutputSelection(),ClusterDisplayMode.NATIVE_ICONS)
        try {
            store.edit { it[AppPreferences.SECONDARY_DISPLAY_OUTPUTS]=SecondaryDisplayPreferences.encode(SecondaryPreferencesSnapshot(12,mapOf(id to old),legacy)) }
            val before=store.data.first()
            for (failure in listOf(IOException("injected disk boundary"),CancellationException("injected cancellation boundary"))) {
                val failing=object:DataStore<Preferences> {
                    override val data=store.data
                    override suspend fun updateData(transform:suspend (Preferences)->Preferences):Preferences = store.updateData { value ->
                        transform(value); throw failure
                    }
                }
                try { prefs(failing).getSecondaryDisplayPreferences();fail("write failure hidden") }
                catch (caught:Exception) { assertEquals(failure.javaClass,caught.javaClass); assertEquals(failure.message,caught.message) }
                assertEquals(before,store.data.first())
            }
            val migrated=prefs(store).getSecondaryDisplayPreferences()
            assertEquals(13L,migrated.revision);assertEquals(149,migrated.outputs.getValue(id).aaDensityDpi)
            val p=prefs(store)
            val outcomes=coroutineScope { (1..2).map { n -> async(Dispatchers.IO) {
                try { p.updateSecondaryDisplayPreferences(13,mapOf(id to old.copy(aaDensityDpi=180+n))); true }
                catch (_:ConcurrentModificationException) { false }
            } }.awaitAll() }
            assertEquals(1,outcomes.count { it });assertEquals(14L,p.getSecondaryDisplayPreferences().revision)
        } finally { job.cancelAndJoin();root.deleteRecursively() }
    }
}
