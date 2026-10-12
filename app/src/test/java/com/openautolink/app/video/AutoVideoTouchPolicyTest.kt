package com.openautolink.app.video

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoVideoTouchPolicyTest {
    @Test
    fun `manual mode preserves the selected resolution`() {
        assertEquals(
            2560 to 1440,
            AutoVideoTouchPolicy.resolve(
                autoNegotiate = false,
                codec = "h265",
                panelWidth = 2914,
                panelHeight = 1134,
                selectedWidth = 2560,
                selectedHeight = 1440,
            ),
        )
    }

    @Test
    fun `landscape auto mode follows first advertised tier per codec`() {
        assertEquals(
            2560 to 1440,
            AutoVideoTouchPolicy.resolve(true, "h265", 2914, 1134, 2560, 1440),
        )
        assertEquals(
            1920 to 1080,
            AutoVideoTouchPolicy.resolve(true, "h264", 2914, 1134, 2560, 1440),
        )
    }

    @Test
    fun `portrait auto mode follows first advertised tier per codec`() {
        assertEquals(
            1440 to 2560,
            AutoVideoTouchPolicy.resolve(true, "hevc", 1080, 1920, 1440, 2560),
        )
        assertEquals(
            1080 to 1920,
            AutoVideoTouchPolicy.resolve(true, "h264", 1080, 1920, 1440, 2560),
        )
    }
}
