package com.openautolink.app.cluster

import org.junit.Assert.*
import org.junit.Test

class ClusterOutputPolicyTest {
    @Test fun freshSelectionRetainsNativeIconsWithoutOptingIntoMapVideo() {
        val selection = ClusterOutputSelection()
        assertEquals(ClusterDisplayMode.NATIVE_ICONS, selection.mode)
        assertTrue(selection.androidAuto)
        assertTrue(selection.carPlay)
        assertFalse(selection.nativeIconsWithMap)
        for (protocol in ClusterProjectionProtocol.entries) {
            assertFalse(ClusterOutputPolicy.shouldOfferMapVideo(selection, protocol))
            assertTrue(ClusterOutputPolicy.shouldPublishNativeIcons(selection, protocol, hasSourcedManeuver = true))
            assertFalse(ClusterOutputPolicy.shouldPublishNativeIcons(selection, protocol, hasSourcedManeuver = false))
        }
    }

    @Test fun everyModeProtocolAndCoexistChoiceHasIndependentOutputAuthority() {
        for (mode in ClusterDisplayMode.entries) for (aa in listOf(false, true))
            for (cp in listOf(false, true)) for (nativeWithMap in listOf(false, true))
                for (protocol in ClusterProjectionProtocol.entries) for (sourced in listOf(false, true)) {
                    val selection = ClusterOutputSelection(mode, aa, cp, nativeWithMap)
                    // Retired protocol preferences are compatibility shadows, not runtime vetoes.
                    val enabled = true
                    val context = "$selection protocol=$protocol sourced=$sourced"
                    assertEquals(context, enabled && mode == ClusterDisplayMode.MAP_VIDEO,
                        ClusterOutputPolicy.shouldOfferMapVideo(selection, protocol))
                    val expectedNative = sourced &&
                        (mode == ClusterDisplayMode.NATIVE_ICONS || (enabled && mode == ClusterDisplayMode.MAP_VIDEO && nativeWithMap))
                    assertEquals(context, expectedNative,
                        ClusterOutputPolicy.shouldPublishNativeIcons(selection, protocol, sourced))
                }
    }

    @Test fun retiredProtocolVetoCannotSuppressVideoOrExplicitSourcedNativeChoice() {
        val selection = ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO, androidAuto = false, nativeIconsWithMap = true)
        assertTrue(ClusterOutputPolicy.shouldOfferMapVideo(selection, ClusterProjectionProtocol.ANDROID_AUTO))
        assertTrue(ClusterOutputPolicy.shouldPublishNativeIcons(selection, ClusterProjectionProtocol.ANDROID_AUTO, true))
        assertTrue(ClusterOutputPolicy.shouldOfferMapVideo(selection, ClusterProjectionProtocol.CARPLAY))
        assertFalse(ClusterOutputPolicy.shouldPublishNativeIcons(selection, ClusterProjectionProtocol.CARPLAY, false))
        assertTrue(ClusterOutputPolicy.shouldPublishNativeIcons(selection, ClusterProjectionProtocol.CARPLAY, true))
        assertEquals(ClusterDisplayMode.MAP_VIDEO, selection.mode)
    }
}
