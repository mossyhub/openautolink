package com.openautolink.app.transport.aasdk

/**
 * Configuration for the AA Service Discovery Response (SDR).
 *
 * Passed to native code which builds the protobuf SDR that tells
 * the phone what this head unit supports.
 *
 * Fields are accessed from C++ via JNI GetIntField/GetObjectField.
 */
class AasdkSdrConfig(
    /** Video width in pixels (e.g. 1920). */
    @JvmField val videoWidth: Int = 1920,

    /** Video height in pixels (e.g. 1080). */
    @JvmField val videoHeight: Int = 1080,

    /** Video frames per second (e.g. 60). */
    @JvmField val videoFps: Int = 60,

    /** Video DPI for UI scaling (e.g. 160). */
    @JvmField val videoDpi: Int = 160,

    /** Stable inset width margin in pixels. */
    @JvmField val marginWidth: Int = 0,

    /** Stable inset height margin in pixels. */
    @JvmField val marginHeight: Int = 0,

    /**
     * When true (default), C++ ignores [marginWidth]/[marginHeight] and
     * auto-computes per-tier margins from [panelWidth]/[panelHeight] so the
     * codec's inner rect matches panel AR. When false, use the literal
     * margin values (0 = stretch full codec frame onto panel).
     */
    @JvmField val autoMargins: Boolean = true,

    /** Pixel aspect ratio × 10000 (0 = square pixels). */
    @JvmField val pixelAspectE4: Int = 0,

    /** Bluetooth MAC address of the car (for BT pairing service). */
    @JvmField val btMacAddress: String = "",

    /** Vehicle make (e.g. "Chevrolet"). */
    @JvmField val vehicleMake: String = "OpenAutoLink",

    /** Vehicle model (e.g. "Blazer EV"). */
    @JvmField val vehicleModel: String = "Direct",

    /** Vehicle year (e.g. "2024"). */
    @JvmField val vehicleYear: String = "2024",

    /** Driver position: 0=left, 1=right. */
    @JvmField val driverPosition: Int = 0,

    /** Hide clock in AA status bar. */
    @JvmField val hideClock: Boolean = false,

    /** Hide signal strength in AA status bar. */
    @JvmField val hideSignal: Boolean = false,

    /** Hide battery indicator in AA status bar. */
    @JvmField val hideBattery: Boolean = false,

    /** Auto-negotiate video: true = send all resolutions/codecs, false = send only the configured one. */
    @JvmField val autoNegotiate: Boolean = true,

    /** Video codec preference for manual mode: "h264" or "h265". */
    @JvmField val videoCodec: String = "h265",

    /** Physical pixel density of the AAOS display (from DisplayMetrics.densityDpi). */
    @JvmField val realDensity: Int = 0,

    /** AA stable content insets — tells phone where to keep UI inside. */
    @JvmField val safeAreaTop: Int = 0,
    @JvmField val safeAreaBottom: Int = 0,
    @JvmField val safeAreaLeft: Int = 0,
    @JvmField val safeAreaRight: Int = 0,

    /**
     * Target layout width in dp for per-tier DPI scaling (auto-negotiate mode).
     * When > 0, each resolution tier gets a computed DPI so that
     * screenWidthDp = tierPixelWidth * 160 / dpi ≈ this value.
     * 0 = disabled (use videoDpi uniformly for all tiers).
     */
    @JvmField val targetLayoutWidthDp: Int = 0,

    /** Fuel types from VHAL INFO_FUEL_TYPE (e.g. [10] = ELECTRIC). */
    @JvmField val fuelTypes: IntArray = intArrayOf(),

    /** EV connector types from VHAL INFO_EV_CONNECTOR_TYPE (e.g. [1,5] = J1772+CCS1). */
    @JvmField val evConnectorTypes: IntArray = intArrayOf(),

    /**
     * Distance from driver eye to display surface, in millimetres.
     * Sent in VideoConfiguration.viewing_distance — phones use it to pick
     * text/icon sizes that look correct at the actual seating distance.
     * GM uses 700 mm for typical centre-IP screens. 0 = omit field.
     */
    @JvmField val viewingDistanceMm: Int = 0,

    /**
     * VideoConfiguration.decoder_additional_depth — number of extra decoded
     * frames the head unit can buffer beyond the codec's reorder requirement.
     * GM hard-codes 1; matching keeps phone-side encoder pacing predictable.
     * 0 = omit field.
     */
    @JvmField val decoderAdditionalDepth: Int = 0,

    /**
     * Panel rect in pixels — used by C++ to (a) auto-pick landscape vs.
     * portrait codec tiers when auto-negotiating, and (b) compute per-tier
     * width_margin / height_margin so the inner content rect of the codec
     * frame matches the panel aspect ratio. Renderer (ProjectionScreen)
     * uses the SAME formula on the decoded frame so the inner rect lands
     * on the panel and margin pixels clip off-screen → square pixels.
     * 0 = unknown (auto-margin disabled, falls back to user override).
     */
    @JvmField val panelWidth: Int = 0,
    @JvmField val panelHeight: Int = 0,

    /**
     * When true, advertise the AA telephony audio sink so the phone streams
     * in-call audio (and pulls mic input) over AA instead of relying on
     * Bluetooth HFP. Lets users keep BT Call audio disabled on the phone
     * but still hear calls through the car speakers.
     */
    @JvmField val enableTelephonyAudio: Boolean = false,
)
