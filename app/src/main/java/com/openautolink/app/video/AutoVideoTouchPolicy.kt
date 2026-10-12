package com.openautolink.app.video

/** Keeps Kotlin touch scaling aligned with native auto-negotiation tier ordering. */
object AutoVideoTouchPolicy {
    fun resolve(
        autoNegotiate: Boolean,
        codec: String,
        panelWidth: Int,
        panelHeight: Int,
        selectedWidth: Int,
        selectedHeight: Int,
    ): Pair<Int, Int> {
        if (!autoNegotiate) return selectedWidth to selectedHeight
        val portrait = panelWidth > 0 && panelHeight > 0 && panelWidth < panelHeight
        val hevc = codec.equals("h265", ignoreCase = true) ||
            codec.equals("hevc", ignoreCase = true)
        return when {
            hevc && portrait -> 1440 to 2560
            hevc -> 2560 to 1440
            portrait -> 1080 to 1920
            else -> 1920 to 1080
        }
    }
}
