package com.openautolink.app.secondary

import com.openautolink.app.data.ClusterOutputPreferences
import java.util.Collections

/** Producer identity, preserved verbatim. Not a locator or render permission. */
@JvmInline value class SecondaryOutputId(val value: String) {
    init { require(value.isNotBlank() && value.length <= 512) { "Secondary output key must be nonblank and at most 512 characters" } }
}
enum class AaSecondaryRole { CLUSTER, AUXILIARY }
/** Presentation default only, never display admission. Reuse the existing qualified GM product. */
internal fun defaultAaSecondaryRole(vehicleProduct: String): AaSecondaryRole =
    if (vehicleProduct == com.openautolink.app.cluster.ClusterDisplayProfiles.AEGEAN_ORANGE_239.vehicle.product)
        AaSecondaryRole.AUXILIARY else AaSecondaryRole.CLUSTER

/** Null is unset; every explicit saved role and all geometry/protocol intent remain verbatim. */
internal fun SecondaryOutputPreference.withDefaultAaRole(vehicleProduct: String): SecondaryOutputPreference =
    if (aaRole != null) this else copy(aaRole = defaultAaSecondaryRole(vehicleProduct))
enum class SecondaryRoute { GM_HOSTED, PUBLIC_ACTIVITY, INVENTORY_ONLY }
enum class SecondaryReason {
    NONE, UNKNOWN_BUILTIN, AMBIGUOUS_IDENTITY, PRIVATE_DISPLAY, NO_PUBLIC_ROUTE,
    DISCONNECTED, DISABLED, NO_AA_ROLE, LAUNCH_DENIED, HOST_PENDING, HOST_UNAVAILABLE,
    CODEC_UNAVAILABLE, RETIREMENT_PENDING, CARPLAY_DESTINATION_CONFLICT,
    OUTPUT_LIMIT, PHONE_NOT_SELECTED, EXPERIMENTAL_AUXILIARY
}
data class SecondaryDisplayDescriptor(
    val id: SecondaryOutputId,
    val displayId: Int,
    val attachmentEpoch: Long,
    val name: String,
    val width: Int, val height: Int, val densityDpi: Int,
    val route: SecondaryRoute,
    val reason: SecondaryReason,
    val physicalRoleLabel: String,
) {
    init { require(displayId >= 0 && attachmentEpoch >= 0); require(width > 0 && height > 0 && densityDpi > 0) }
}

/** Exact scope, not an OEM qualification. Dimensions are the panel, not requested codec geometry. */
data class SecondaryLayoutScope(
    val outputId: SecondaryOutputId,
    val vehicle: String,
    val buildFingerprint: String,
    val panelWidth: Int, val panelHeight: Int, val densityDpi: Int,
) {
    init {
        require(vehicle.isNotBlank() && vehicle.length <= 512)
        require(buildFingerprint.isNotBlank() && buildFingerprint.length <= 512)
        require(panelWidth > 0 && panelHeight > 0 && densityDpi > 0)
    }
}
/** Finite relative fractions of the actual measured drawable canvas. */
data class SecondaryLayoutBox(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    init {
        require(listOf(left, top, right, bottom).all { it.isFinite() && it in 0.0..1.0 })
        require(left < right && top < bottom)
    }
    fun contains(other: SecondaryLayoutBox): Boolean =
        left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom
}
/** Must be revalidated against live scope, actual canvas and safe insets before offering video. */
data class SecondaryLayoutPreference(
    val scope: SecondaryLayoutScope,
    val output: SecondaryLayoutBox,
    val safe: SecondaryLayoutBox,
    val directSafe: SecondaryLayoutBox? = null,
    val directSafeAutomatic: Boolean = false,
) { init { require(output.contains(safe)) } }

data class SecondaryOutputPreference(
    val enabled: Boolean = false,
    val aaRole: AaSecondaryRole? = null,
    // Compatibility shadows, round-tripped verbatim. Enabled outputs follow the active protocol.
    val androidAuto: Boolean = false,
    val carPlay: Boolean = false,
    val layout: SecondaryLayoutPreference? = null,
    val aaDensityDpi: Int? = null,
) {
    init { require(aaDensityDpi == null || aaDensityDpi in 80..320) }
}

/** A regular value class avoids generated data-class copy bypassing defensive collection copying. */
class SecondaryPreferencesSnapshot(
    val revision: Long,
    outputs: Map<SecondaryOutputId, SecondaryOutputPreference>,
    val legacyCluster: ClusterOutputPreferences,
    val error: String? = null,
) {
    val outputs: Map<SecondaryOutputId, SecondaryOutputPreference> = Collections.unmodifiableMap(LinkedHashMap(outputs))
    init { require(revision >= 0) }
    fun copy(revision: Long = this.revision, outputs: Map<SecondaryOutputId, SecondaryOutputPreference> = this.outputs,
        legacyCluster: ClusterOutputPreferences = this.legacyCluster, error: String? = this.error) =
        SecondaryPreferencesSnapshot(revision, outputs, legacyCluster, error)
    operator fun component1() = revision
    operator fun component2() = outputs
    operator fun component3() = legacyCluster
    operator fun component4() = error
    override fun equals(other: Any?) = other is SecondaryPreferencesSnapshot && revision == other.revision &&
        outputs == other.outputs && legacyCluster == other.legacyCluster && error == other.error
    override fun hashCode() = arrayOf(revision, outputs, legacyCluster, error).contentHashCode()
    override fun toString() = "SecondaryPreferencesSnapshot(revision=$revision, outputs=$outputs, legacyCluster=$legacyCluster, error=$error)"
}
class SecondaryInventorySnapshot(val revision: Long, displays: List<SecondaryDisplayDescriptor>) {
    val displays: List<SecondaryDisplayDescriptor> = Collections.unmodifiableList(ArrayList(displays))
    init { require(revision >= 0) }
    fun copy(revision: Long = this.revision, displays: List<SecondaryDisplayDescriptor> = this.displays) =
        SecondaryInventorySnapshot(revision, displays)
    operator fun component1() = revision
    operator fun component2() = displays
    override fun equals(other: Any?) = other is SecondaryInventorySnapshot && revision == other.revision && displays == other.displays
    override fun hashCode() = 31 * revision.hashCode() + displays.hashCode()
    override fun toString() = "SecondaryInventorySnapshot(revision=$revision, displays=$displays)"
}
data class SecondaryOutputStatus(
    val id: SecondaryOutputId,
    val reason: SecondaryReason = SecondaryReason.NONE,
    val advertised: Boolean = false,
    val phoneSelected: Boolean = false,
    val hostAttached: Boolean = false,
    val received: Long = 0, val decoded: Long = 0, val presented: Long = 0,
    val dropped: Long = 0,
) { init { require(received >= 0 && decoded >= 0 && presented >= 0 && dropped >= 0) } }
