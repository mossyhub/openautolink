package com.openautolink.app.media

import android.app.Application
import android.content.ComponentName
import android.support.v4.media.session.MediaSessionCompat
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[32,34],manifest=Config.NONE,shadows=[BrowseExtrasShadow::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class PhoneMediaDiscoveryTest {
    @After fun close() { OalMediaSessionManager.instanceOrNull()?.release(); OalMediaBrowserService.mediaSessionToken=null }
    @Test fun neutralBrowsableRootSurvivesServiceRebind() {
        val manager=OalMediaSessionManager.getInstance(RuntimeEnvironment.getApplication())
        manager.initialize()
        OalMediaBrowserService.updateSessionToken(manager.getSessionToken()!!)
        repeat(2) {
            val controller=Robolectric.buildService(OalMediaBrowserService::class.java).create()
            try {
                assertEquals("oal_media_root",controller.get().onGetRoot("host",1,null).rootId)
                assertEquals(manager.getSessionToken(),controller.get().sessionToken)
            } finally { controller.destroy() }
        }
    }
    @Test fun disconnectedRootHasOneBrowsableFolderAndNoFakePlayableRow() {
        val manager=OalMediaSessionManager.getInstance(RuntimeEnvironment.getApplication()); manager.initialize()
        val method=manager.javaClass.methods.firstOrNull { it.name=="browseChildren" }
        assertNotNull("bounded browse backend must exist",method)
        @Suppress("UNCHECKED_CAST")
        fun children(id:String)=method!!.invoke(manager,id) as List<android.support.v4.media.MediaBrowserCompat.MediaItem>
        val root=children("oal_media_root")
        assertEquals(1,root.size); assertTrue(root.single().isBrowsable)
        assertEquals("Phone audio",root.single().description.title.toString())
        assertEquals("Connect a phone",root.single().description.subtitle.toString())
        assertTrue(children("oal_phone_audio").isEmpty()); assertTrue(children("bogus").isEmpty())
    }
    @Test fun ownerQualifiedAaDispatchApiIsPresentInsteadOfLateCurrentPhoneFallback() {
        assertNotNull("browse command must carry the captured native owner/generation",OalMediaSessionManager.MediaControlCallback::class.java.methods.firstOrNull { it.name=="onCommandForOwner" })
    }
    @Test fun audioLauncherBackendExistsWithoutOpeningOrConnectingAtConstruction() {
        assertNotNull("public-action backend must be present",runCatching { Class.forName("com.openautolink.app.media.OalMediaHostLauncher") }.getOrNull())
    }
    @Test fun browseExtraIsClassNameNotFlattenedComponent() {
        val app=RuntimeEnvironment.getApplication()
        val manager=OalMediaSessionManager.getInstance(app); manager.initialize()
        val session=manager.javaClass.getDeclaredField("mediaSession").apply { isAccessible=true }.get(manager) as MediaSessionCompat
        val extras=BrowseExtrasShadow.extras
        val name=extras?.getString("android.media.session.BROWSE_SERVICE")
        assertEquals(OalMediaBrowserService::class.java.name,name)
        assertEquals(ComponentName(app,OalMediaBrowserService::class.java),ComponentName(app.packageName,name!!))
    }
}

@org.robolectric.annotation.Implements(android.media.session.MediaSession::class)
class BrowseExtrasShadow: org.robolectric.shadows.ShadowMediaSession() {
    companion object { var extras: android.os.Bundle?=null }
    @org.robolectric.annotation.Implementation fun setExtras(value:android.os.Bundle?) { extras=value?.let { android.os.Bundle(it) } }
}
