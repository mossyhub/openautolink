package com.openautolink.app.media

import android.content.Context
import android.graphics.Bitmap
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.openautolink.app.carplay.MetadataHostOwnership
import com.openautolink.carplaylab.metadata.CarPlayMediaMetadata
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class CarPlayMediaPublicationTest {
    @After fun cleanup() { unmockkAll() }
    @Test fun artworkDecodeIsBoundedBeforePixelAllocation() {
        val manager = OalMediaSessionManager::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(mockk<Context>())
        val decode = OalMediaSessionManager::class.java.getDeclaredMethod("decodeCarPlayArtwork", ByteArray::class.java).apply { isAccessible = true }
        mockkStatic(android.graphics.BitmapFactory::class)
        every { android.graphics.BitmapFactory.decodeByteArray(any(), any(), any(), any()) } answers {
            val options = arg<android.graphics.BitmapFactory.Options>(3)
            if (options.inJustDecodeBounds) { options.outWidth = 4096; options.outHeight = 2048; null }
            else mockk<Bitmap> { every { width } returns 256; every { height } returns 128 }
        }
        assertNull(decode.invoke(manager, ByteArray(2 * 1024 * 1024 + 1)))
        verify(exactly = 0) { android.graphics.BitmapFactory.decodeByteArray(any(), any(), any(), any()) }
        assertNotNull(decode.invoke(manager, byteArrayOf(1)))
        verify { android.graphics.BitmapFactory.decodeByteArray(any(), any(), any(), match { !it.inJustDecodeBounds && it.inSampleSize == 16 }) }
    }

    @Test fun nullablePlaybackAndPositionRemainUnknownAndArtExplicitlyClears() {
        val context = mockk<Context>()
        val manager = OalMediaSessionManager::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(context)
        val session = mockk<MediaSessionCompat>(relaxed = true)
        OalMediaSessionManager::class.java.getDeclaredField("mediaSession").apply { isAccessible = true }.set(manager, session)
        val oldArt = mockk<Bitmap>()
        OalMediaSessionManager::class.java.getDeclaredField("cachedBitmap").apply { isAccessible = true }.set(manager, oldArt)
        mockkConstructor(MediaMetadataCompat.Builder::class)
        every { anyConstructed<MediaMetadataCompat.Builder>().putString(any(), any()) } answers { self as MediaMetadataCompat.Builder }
        every { anyConstructed<MediaMetadataCompat.Builder>().putLong(any(), any()) } answers { self as MediaMetadataCompat.Builder }
        every { anyConstructed<MediaMetadataCompat.Builder>().putBitmap(any(), any()) } answers { self as MediaMetadataCompat.Builder }
        every { anyConstructed<MediaMetadataCompat.Builder>().build() } returns mockk()
        mockkConstructor(PlaybackStateCompat.Builder::class)
        every { anyConstructed<PlaybackStateCompat.Builder>().setActions(any()) } answers { self as PlaybackStateCompat.Builder }
        every { anyConstructed<PlaybackStateCompat.Builder>().setState(any(), any(), any()) } answers { self as PlaybackStateCompat.Builder }
        every { anyConstructed<PlaybackStateCompat.Builder>().build() } returns mockk()
        val method = OalMediaSessionManager::class.java.methods.find { it.name == "updateCarPlay" }
        assertNotNull("Existing process manager must accept nullable CP state and explicit art clear", method)
        val owner = Any(); assertTrue(MetadataHostOwnership.process.claim(owner))
        try {
            method!!.invoke(manager, owner, CarPlayMediaMetadata(title = "Title"), null, true)
            verify { anyConstructed<PlaybackStateCompat.Builder>().setState(PlaybackStateCompat.STATE_NONE, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 0f) }
            verify(exactly = 0) { anyConstructed<MediaMetadataCompat.Builder>().putBitmap(any(), any()) }
            assertNull(OalMediaSessionManager::class.java.getDeclaredField("cachedBitmap").apply { isAccessible = true }.get(manager))
        } finally { MetadataHostOwnership.process.retire(owner) {} }
    }
}
