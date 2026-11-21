package com.openautolink.app.carplay;

import com.openautolink.app.data.CarPlaySettings;
import org.junit.Test;
import static org.junit.Assert.*;

/** Promotion keeps the ordinary Automatic/H.264 choice and retires the old pilot veto. */
public class CarPlayHevcDefaultTest {
    @Test public void freshSettingsEnableQualifiedClusterHevcByDefault() {
        assertTrue(new CarPlaySettings().getClusterHevcEnabled());
        assertEquals("hevc",new CarPlaySettings().getVideoCodec());
    }
    @Test public void legacyInitialOffCannotPermanentlyVetoAutomaticCodec() {
        CarPlaySettings settings=CarPlaySettings.Companion.decode("{\"clusterHevcEnabled\":false,\"videoCodec\":\"hevc\"}");
        assertTrue(settings.getClusterHevcEnabled());
        assertTrue(settings.displayRequest().getPreferHevc());
    }
    @Test public void explicitMainH264RemainsAWorkingOptOut() {
        CarPlaySettings settings=CarPlaySettings.Companion.decode("{\"clusterHevcEnabled\":false,\"videoCodec\":\"h264\"}");
        assertEquals("h264",settings.getVideoCodec());
        assertFalse(settings.displayRequest().getPreferHevc());
    }
    @Test public void retiredMalformedFlagCannotDiscardUnrelatedSavedSettings() {
        CarPlaySettings settings=CarPlaySettings.Companion.decode("{\"clusterHevcEnabled\":\"old\",\"videoCodec\":\"hevc\",\"connectionMode\":\"wireless\",\"fps\":45}");
        assertTrue(settings.getClusterHevcEnabled());
        assertEquals("wireless",settings.getConnectionMode());
        assertEquals(45,settings.getFps());
    }
}
