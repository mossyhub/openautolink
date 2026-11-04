package com.openautolink.app.announcements

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class ReleaseAnnouncementTest {
    @Test fun displayAuthorityRequiresAttachedVisualViewAndNeverDefaultsUnknownToMain() {
        val view = io.mockk.mockk<android.view.View>()
        val display = io.mockk.mockk<android.view.Display>()
        io.mockk.every { view.isAttachedToWindow } returns false
        assertNull(announcementDisplayId(view))
        io.mockk.every { view.isAttachedToWindow } returns true
        io.mockk.every { view.display } returns null
        assertNull(announcementDisplayId(view))
        io.mockk.every { view.display } returns display
        io.mockk.every { display.displayId } returns 2
        assertEquals(2, announcementDisplayId(view))
        io.mockk.every { display.displayId } returns android.view.Display.DEFAULT_DISPLAY
        assertEquals(android.view.Display.DEFAULT_DISPLAY, announcementDisplayId(view))
        io.mockk.every { view.isAttachedToWindow } returns false
        assertNull(announcementDisplayId(view))
        io.mockk.unmockkAll()
    }

    @Test fun onlyExplicitExactReleaseIsAnnounced() {
        assertNull(ReleaseAnnouncements.forVersion(571))
        assertNull(ReleaseAnnouncements.forVersion(574))
        assertNull(ReleaseAnnouncements.forVersion(576))
        assertNull(ReleaseAnnouncements.forVersion(573)) // even if 572 was never acknowledged
        assertNull(ReleaseAnnouncements.forVersion(1000))
        val announcement = requireNotNull(ReleaseAnnouncements.forVersion(572))
        assertEquals("driver-display-maps-572", announcement.id)
        assertEquals(572, announcement.versionCode)
        assertEquals("Maps on your driver display", announcement.title)
        assertEquals("OAL can now show Android Auto and CarPlay maps on your driver display.\n\nTo enable it, open Settings → Secondary Displays and turn on Driver-display video.", announcement.body)
    }

    @Test fun explicitlyAuthored575ReusesApprovedIntroWithoutAnnouncingFutureReleases() {
        val old = requireNotNull(ReleaseAnnouncements.forVersion(572))
        val current = requireNotNull(ReleaseAnnouncements.forVersion(575))
        assertEquals("driver-display-maps-575", current.id)
        assertEquals(575, current.versionCode)
        assertEquals(old.title, current.title)
        assertEquals(old.body, current.body)
        assertNotEquals(old.acknowledgementKey, current.acknowledgementKey)
        for (quiet in listOf(573, 574, 576, 1000)) assertNull(ReleaseAnnouncements.forVersion(quiet))
    }

    @Test fun dismissalSurvivesActualDataStoreColdReopenWithoutChangingOtherPreferences() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as Application
        val file = File(app.filesDir, "announcement-${java.util.UUID.randomUUID()}.preferences_pb")
        var job = SupervisorJob()
        fun open() = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file })
        val announcement = requireNotNull(ReleaseAnnouncements.forVersion(572))
        var data = open()
        val unrelated = stringPreferencesKey("unrelated-draft-setting")
        data.edit { it[unrelated] = "preserve" }
        var store = ReleaseAnnouncementStore(data, CoroutineScope(job + Dispatchers.IO))
        assertFalse(store.isAcknowledged(announcement))
        val owner = Any()
        assertTrue(store.claim(announcement, owner))
        assertFalse(store.claim(announcement, Any()))
        store.release(announcement, Any()) // stale owner cannot release current prompt
        assertFalse(store.claim(announcement, Any()))
        store.dismiss(announcement, owner).join()
        assertFalse(store.claim(announcement, Any()))
        job.cancelAndJoin()
        job = SupervisorJob()
        try {
            data = open()
            store = ReleaseAnnouncementStore(data, CoroutineScope(job + Dispatchers.IO))
            assertTrue(store.isAcknowledged(announcement))
            assertEquals("preserve", data.data.first()[unrelated])
            val explicitFuture = announcement.copy(id = "explicit-future-feature", versionCode = 580)
            assertFalse(store.isAcknowledged(explicitFuture))
            val nextOwner = Any()
            assertTrue(store.claim(explicitFuture, nextOwner))
            store.dismiss(explicitFuture, nextOwner).join()
            assertTrue(store.isAcknowledged(explicitFuture))
            assertTrue(ReleaseAnnouncementStore(data, CoroutineScope(job + Dispatchers.IO)).isAcknowledged(explicitFuture))
        } finally { job.cancelAndJoin(); file.delete() }
    }

    @Test fun failedWriteHidesInMemoryWithoutClaimingColdDurability() = runBlocking {
        val job = SupervisorJob()
        val data = object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
            override val data = kotlinx.coroutines.flow.flowOf(androidx.datastore.preferences.core.emptyPreferences())
            override suspend fun updateData(transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences): androidx.datastore.preferences.core.Preferences {
                throw java.io.IOException("injected optional write failure")
            }
        }
        try {
            val a = requireNotNull(ReleaseAnnouncements.forVersion(572))
            val store = ReleaseAnnouncementStore(data, CoroutineScope(job + Dispatchers.IO))
            val owner = Any()
            assertFalse(store.isAcknowledged(a)); assertTrue(store.claim(a, owner))
            store.dismiss(a, owner).join()
            assertTrue(store.isAcknowledged(a))
            assertFalse(store.claim(a, Any()))
            assertFalse(ReleaseAnnouncementStore(data, CoroutineScope(job + Dispatchers.IO)).isAcknowledged(a))
        } finally { job.cancelAndJoin() }
    }

    @Test fun merelyShowingAndReleasingDoesNotPersistAcknowledgement() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as Application
        val file = File(app.filesDir, "unacknowledged-${java.util.UUID.randomUUID()}.preferences_pb")
        val job = SupervisorJob()
        try {
            val data = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file })
            val store = ReleaseAnnouncementStore(data, CoroutineScope(job + Dispatchers.IO))
            val a = requireNotNull(ReleaseAnnouncements.forVersion(572))
            val owner = Any()
            assertTrue(store.claim(a, owner)); store.release(a, owner)
            assertFalse(store.isAcknowledged(a))
            assertTrue(data.data.first().asMap().isEmpty())
            assertTrue(store.claim(a, Any()))
        } finally { job.cancelAndJoin(); file.delete() }
    }
}
