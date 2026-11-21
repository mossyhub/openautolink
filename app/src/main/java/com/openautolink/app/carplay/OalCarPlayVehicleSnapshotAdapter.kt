package com.openautolink.app.carplay

import android.os.SystemClock
import com.openautolink.app.input.ProcessVehicleDataRuntime
import com.openautolink.app.input.VehicleTelemetryFreshness
import com.openautolink.app.transport.ControlMessage
import com.openautolink.carplaylab.intake.OalEvSnapshot
import com.openautolink.carplaylab.intake.OalCarDataCapabilities
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

/** Read-only process StateFlow adapter. Does not attach/replace AA's single projection consumer. */
class OalCarPlayVehicleSnapshotAdapter(
    // The forwarder's snapshot includes CarPlay-only warning updates. The
    // coordinator's raw-batch state remains the source for other consumers.
    private val source: () -> StateFlow<ControlMessage.VehicleData>? = { ProcessVehicleDataRuntime.forwarderOrNull()?.latestVehicleData },
    private val elapsedMs: () -> Long = SystemClock::elapsedRealtime,
    private val pilotConfiguration: () -> CarPlayEvPilotConfiguration = { CarPlayEvPilotConfiguration() },
) {
    private enum class ScaleBasis { usable, reported, manual, absent }
    private var pilotFrozen = false
    private var frozenPilot: CarPlayEvPilotConfiguration? = null
    private var frozenModel: com.openautolink.carplaylab.intake.OalEvRoutingModel? = null
    private var frozenGeneration: Long? = null
    private var frozenScaleBasis = ScaleBasis.absent
    private var linkCaptured = false
    private var savedOwnerRetired = false
    private var linkPilot: CarPlayEvPilotConfiguration? = null
    private var linkGeneration: Long? = null
    private var warningSource: StateFlow<ControlMessage.VehicleData>? = null
    private var linkMake: String? = null
    private var linkModel: String? = null
    private var linkYear: String? = null
    private var productionCapabilities: OalCarDataCapabilities? = null
    private var heldFullRange: Int? = null
    private var fullRangeAdmittedMs: Long? = null
    private val lastDiagnostics = mutableMapOf<Pair<String,String>,Long>()
    fun snapshot(): OalEvSnapshot? = snapshot {}

    /** One source read freezes both the identification metadata and implemented live fields. */
    @Synchronized fun capabilities(location: Boolean, receipt: (String) -> Unit = {}): OalCarDataCapabilities {
        productionCapabilities?.let { return it }
        val sourceFlow = source()
        val data = sourceFlow?.value
        if (!linkCaptured) {
            warningSource = sourceFlow
            linkCaptured = true
            linkPilot = runCatching { pilotConfiguration() }.getOrNull()
            linkGeneration = data?.vhalRegistrationGeneration
            linkMake = data?.carMake
            linkModel = data?.carModel
            linkYear = data?.carYear
        }
        if (evSharingDisabled()) {
            runCatching { receipt("carplay-ev-pilot readiness=disabled enabled=false basis=absent frozen=true model=false") }
            return OalCarDataCapabilities(location = location,
                outsideTemperature = snapshot(data, receipt, warningSourceCurrent = sourceFlow === warningSource)?.outsideTemperatureC != null)
                .also { productionCapabilities = it }
        }
        val now = elapsedMs()
        val initial = snapshot(data, receipt, now, warningSourceCurrent = sourceFlow === warningSource)
        val electric = data?.fuelTypes?.toSet() == setOf(10)
        // No late sample, preference write or missing-profile recovery can expand this link.
        if (!pilotFrozen) {
            pilotFrozen = true
            var configured = linkPilot
            val savedSharing = configured?.profileId == CarPlayEvPilotConfiguration.SAVED_SHARING
            val reported = reportedScale(data, now)
            val actual = actualScale(data, now)
            val preset = savedSharing && configured?.averageChargeKw.isNullOrEmpty() && matchesProductionSource(data, reported)
            val production = configured?.profileId == CarPlayEvPilotConfiguration.GM_ROUTING || preset
            val sourceProfile = production || savedSharing || configured?.profileId == CarPlayEvPilotConfiguration.GM_SOURCE
            val basis = when {
                !sourceProfile -> if (configured?.pilotCapacityWh?.isNotEmpty() == true) ScaleBasis.manual else ScaleBasis.absent
                actual != null -> ScaleBasis.usable
                !production && configured?.modelCapacityKwh?.isNotEmpty() == true -> ScaleBasis.manual
                reported != null -> ScaleBasis.reported
                else -> ScaleBasis.absent
            }
            var readiness = when {
                configured == null -> "configuration_unavailable"
                !configured.enabled -> "disabled"
                sourceProfile && !production && configured.averageChargeKw.isEmpty() -> "charging_profile_required"
                !sourceProfile && configured.profileId != CarPlayEvPilotConfiguration.CUSTOM -> "unsupported_profile"
                configured.validationError() != null -> "invalid_configuration"
                data == null || !electric || initial?.currentChargeWh == null || (data.vhalRegistrationGeneration ?: 0) <= 0 -> "source_unavailable"
                production && !matchesProductionSource(data, reported) -> "source_identity"
                savedSharing && (identityLabel(data.carMake) == null || identityLabel(data.carModel) == null) -> "source_identity"
                sourceProfile && !production && !savedSharing && !configured.acceptsGmSource(data.carMake,data.carModel) -> "source_identity"
                !sourceProfile && (data.carMake != configured.sourceMake || data.carModel != configured.sourceModel) -> "source_identity"
                sourceProfile && initial?.rangeKm == null -> "source_range"
                sourceProfile && basis == ScaleBasis.absent -> "capacity_required"
                else -> "model_invalid"
            }
            val model = if (readiness == "model_invalid" && configured != null && data != null && initial != null) {
                if (sourceProfile) {
                    val built = when {
                        production -> configured.gmRoutingEstimate(initial.currentChargeWh, data.rangeKm, actual, checkNotNull(reported), data.carYear)
                        savedSharing -> configured.savedEstimate(initial.currentChargeWh, data.rangeKm, actual, reported)
                        else -> configured.sourceEstimate(initial.currentChargeWh, data.rangeKm, actual, reported)
                    }
                    configured = configured.copy(sourceMake=data.carMake!!,sourceModel=data.carModel!!)
                    built
                } else configured.modelOrNull()
            } else null
            if (model != null && initial!!.currentChargeWh!! <= model.pilotCapacityWh) {
                frozenPilot = configured
                frozenModel = model
                frozenGeneration = data!!.vhalRegistrationGeneration
                frozenScaleBasis = basis
                readiness = "ready"
            }
            runCatching { receipt("carplay-ev-pilot readiness=$readiness enabled=${configured?.enabled==true} basis=$basis frozen=true model=${frozenModel!=null}") }
        }
        val make = identityLabel(data?.carMake)
        val model = identityLabel(data?.carModel)
        val year = data?.carYear?.takeIf { it.length == 4 && it.all { c -> c in '0'..'9' } && it.toInt() in 1980..2099 }
        val identityQualified = electric && (data?.vhalRegistrationGeneration ?: 0) > 0
        val name = if (identityQualified && make != null && model != null) listOfNotNull(year,make,model).joinToString(" ") else null
        // INFO_EV_CONNECTOR_TYPE is VHAL, not deprecated CarInfoManager ordinals.
        val connectors = if (identityQualified) data?.evConnectorTypes.orEmpty().fold(0) { mask, vhal ->
            val wire = when (vhal) { 1 -> 2; 2 -> 4; 5 -> 0; 6 -> 1; 10 -> 6; 11 -> 5; else -> null }
            mask or (wire?.let { 1 shl it } ?: 0)
        } else 0
        val offered = if (linkPilot?.profileId == CarPlayEvPilotConfiguration.GM_ROUTING && frozenModel == null)
            snapshot(data, receipt, now, warningSourceCurrent = sourceFlow === warningSource) else initial
        // Chevrolet MY24 ordering guide: C234 small-pack AWD family, CCS1/J1772.
        // No VHAL-rated watts are known; never substitute live power or the pilot's modeled rate.
        val savedSharing = linkPilot?.profileId == CarPlayEvPilotConfiguration.SAVED_SHARING
        val referenceStatus = if (savedSharing) matchesProductionSource(data, reportedScale(data, now)) && frozenModel != null
            else qualifiedPilot(data, reportedScale(data, now))
        val ratedMy24 = referenceStatus && frozenModel != null && year == "2024" &&
            data?.evConnectorTypes?.toSet() == setOf(1, 5) &&
            (savedSharing || linkPilot?.profileId == CarPlayEvPilotConfiguration.GM_ROUTING)
        return OalCarDataCapabilities(location, offered?.rangeKm != null,
            offered?.currentChargeWh != null, offered?.maxChargeWh != null, name, connectors,
            outsideTemperature = offered?.outsideTemperatureC != null, evRoutingModel = frozenModel,
            electricRangeWarning = offered?.electricRangeWarning != null,
            maximumRange = referenceStatus && offered?.maxChargeWh != null && offered.currentChargeWh != null && offered.rangeKm != null,
            maximumDcChargePowerWatts = 150_000.takeIf { ratedMy24 },
            maximumAcChargePowerWatts = 11_500.takeIf { ratedMy24 })
            .also { if (savedSharing || linkPilot?.profileId == CarPlayEvPilotConfiguration.GM_ROUTING) productionCapabilities = it }
    }

    private fun identityLabel(raw: String?): String? = raw?.takeIf {
        it.isNotBlank() && it.toByteArray(Charsets.UTF_8).size <= 80 &&
            it.none { c -> c.isISOControl() || c.isSurrogate() || Character.getType(c) == Character.FORMAT.toInt() }
    }?.trim()?.replace(Regex(" +"), " ")?.takeUnless {
        it.lowercase(java.util.Locale.ROOT) in setOf("unknown", "null", "n/a", "none", "unspecified")
    }

    // Narrow VHAL applicability fingerprint, not VIN/trim detection. Do not select by capacity
    // alone or infer CCS from brand. A different year/pack/port gets ordinary status only.
    private fun matchesProductionSource(data: ControlMessage.VehicleData?, reportedWh: Long?): Boolean =
        data != null && identityLabel(data.carMake).equals("Chevrolet", ignoreCase = true) &&
            identityLabel(data.carModel).equals("C234", ignoreCase = true) &&
            CarPlayEvPilotConfiguration.c234PilotPower(data.carYear) != null &&
            reportedWh != null && reportedWh in 80_000L..89_500L &&
            data.evConnectorTypes?.let { 5 in it } == true

    private fun reason(data: ControlMessage.VehicleData?, raw: Float?, property: String, now: Long,
        limit: Double, positive: Boolean=false, minimum: Double=0.0): String {
        if (raw == null) return "absent"
        val metadata = observationReason(data, property, now)
        if (metadata != "available") return metadata
        if (!raw.isFinite() || raw.toDouble() < minimum || raw.toDouble() > limit || (positive && raw <= 0)) return "bounds"
        return "available"
    }

    private fun observationReason(data: ControlMessage.VehicleData?, property: String, now: Long): String {
        if (data == null) return "metadata"
        if (property != "ENV_OUTSIDE_TEMPERATURE" && data.fuelTypes?.toSet() != setOf(10)) return "fuel"
        val observation = data.evObservationMetadata[property] ?: return "metadata"
        val generation = data.vhalRegistrationGeneration
        if (generation == null || generation <= 0 || observation.registrationGeneration != generation) return "generation"
        if (observation.source != "vhal") return "source"
        if (!observation.subscriptionActive) return "inactive"
        if (observation.status != 0) return "status"
        if (!VehicleTelemetryFreshness.propertyMatches(observation,property)) return "property"
        val timestamp = observation.timestampElapsedNanos ?: return "timestamp"
        if (timestamp <= 0 || now < timestamp/1_000_000) return "timestamp"
        if (!VehicleTelemetryFreshness.sourceTimeConfirmed(observation,property,now,30_000)) return "source_age"
        if (now < observation.receivedElapsedMs || observation.receivedElapsedMs < 0 || now-observation.receivedElapsedMs > 30_000) return "receipt_age"
        return "available"
    }

    private fun reportedScale(data: ControlMessage.VehicleData?, now: Long): Long? =
        data?.evBatteryCapacityWh?.takeIf {
            reason(data,it,"INFO_EV_BATTERY_CAPACITY",now,250_000.0,true,10_000.0)=="available"
        }?.toLong()

    private fun actualScale(data: ControlMessage.VehicleData?, now: Long): Long? =
        data?.evCurrentBatteryCapacityWh?.takeIf {
            reason(data,it,"EV_CURRENT_BATTERY_CAPACITY",now,4294967295.0,true)=="available"
        }?.toLong()

    /** Scoped reference status is opt-in; identity/generation replacement never inherits a link. */
    private fun qualifiedPilot(data: ControlMessage.VehicleData?, reported: Long?): Boolean {
        val configured = if (linkCaptured) linkPilot else runCatching { pilotConfiguration() }.getOrNull()
        if (configured?.enabled != true || data == null || data.fuelTypes?.toSet() != setOf(10) ||
            (data.vhalRegistrationGeneration ?: 0) <= 0) return false
        val sameLink = data.vhalRegistrationGeneration == linkGeneration && data.carMake == linkMake && data.carModel == linkModel
        return when (configured.profileId) {
            CarPlayEvPilotConfiguration.GM_ROUTING -> linkCaptured && (!pilotFrozen || frozenModel != null) &&
                sameLink && data.carYear == linkYear &&
                matchesProductionSource(data, reported)
            CarPlayEvPilotConfiguration.GM_SOURCE -> configured.validationError() == null &&
                configured.acceptsGmSource(data.carMake,data.carModel) && (!linkCaptured || sameLink)
            else -> false
        }
    }

    private fun sameSavedOwner(data: ControlMessage.VehicleData?, sameFlow: Boolean): Boolean =
        sameFlow && data != null && data.vhalRegistrationGeneration == linkGeneration &&
            data.carMake == linkMake && data.carModel == linkModel && data.carYear == linkYear

    private fun evSharingDisabled(): Boolean = linkPilot?.let {
        !it.enabled && (it.profileId == CarPlayEvPilotConfiguration.GM_ROUTING ||
            it.profileId == CarPlayEvPilotConfiguration.SAVED_SHARING)
    } == true

    /** A status read never rewrites the original HAL event timestamp. Receipt liveness stays bounded. */
    @Synchronized fun snapshot(receipt: (String) -> Unit): OalEvSnapshot? {
        val sourceFlow = source()
        return snapshot(sourceFlow?.value, receipt, warningSourceCurrent = sourceFlow === warningSource)
    }

    private fun snapshot(data: ControlMessage.VehicleData?, receipt: (String) -> Unit, now: Long = elapsedMs(),
        warningSourceCurrent: Boolean): OalEvSnapshot? {
        val generation = data?.vhalRegistrationGeneration
        fun age(timestampMs: Long?): Long = if (timestampMs == null || timestampMs < 0 || now < timestampMs) -1
            else (now-timestampMs).coerceAtMost(99_999_999)
        fun report(label: String,property: String,result: String) {
            val key=label to result
            val last=lastDiagnostics[key]
            if (last != null && now >= last && now-last < 1000) return
            lastDiagnostics[key]=now
            val observation=data?.evObservationMetadata?.get(property)
            val status=observation?.status?.takeIf {it in 0..9} ?: -1
            val sameGeneration=generation != null && generation>0 && observation?.registrationGeneration==generation
            val sourceAge=age(observation?.timestampElapsedNanos?.takeIf {it>0}?.div(1_000_000))
            val receiptAge=age(observation?.receivedElapsedMs)
            val mode=observation?.changeMode?.takeIf {it in 0..2}?.toString() ?: "unknown"
            val readAge=age(observation?.statusReadElapsedMs)
            runCatching {receipt("carplay-car-data-source property=$label reason=$result status=$status active=${observation?.subscriptionActive==true} generation=$sameGeneration sourceAgeMs=$sourceAge receiptAgeMs=$receiptAge changeMode=$mode statusReadAgeMs=$readAge")}
        }
        // Outside temperature is independent of fuel type and the link's EV sharing choice.
        val temperatureReason = reason(data,data?.ambientTempC,"ENV_OUTSIDE_TEMPERATURE",now,32767.0,minimum=-32768.0)
        val temperature = data?.ambientTempC?.takeIf {temperatureReason=="available"}?.roundToInt()
        report("temperature","ENV_OUTSIDE_TEMPERATURE",temperatureReason)
        val savedSharing = linkPilot?.profileId == CarPlayEvPilotConfiguration.SAVED_SHARING
        if (savedSharing && !sameSavedOwner(data, warningSourceCurrent)) savedOwnerRetired = true
        if (evSharingDisabled() || savedOwnerRetired) return temperature?.let { OalEvSnapshot(outsideTemperatureC = it) }

        val rangeReason = reason(data,data?.rangeKm,"RANGE_REMAINING",now,65535.0)
        val maxReason = reason(data,data?.evCurrentBatteryCapacityWh,"EV_CURRENT_BATTERY_CAPACITY",now,4294967295.0,true)
        var currentReason = reason(data,data?.evBatteryLevelWh,"EV_BATTERY_LEVEL",now,4294967295.0)
        val range = data?.rangeKm?.takeIf {rangeReason=="available"}?.toInt()
        val actual = actualScale(data,now)
        val reported = reportedScale(data,now)
        val scopedStatus = if (savedSharing) data?.fuelTypes?.toSet() == setOf(10) && (generation ?: 0) > 0
            else qualifiedPilot(data, reported)
        val referenceRange = scopedStatus && (!savedSharing || matchesProductionSource(data, reported))
        // Never derive a warning from SOC/range thresholds, nor admit a replacement VHAL owner.
        val electricWarning = data?.evLowRangeWarning?.takeIf {
            linkCaptured && warningSourceCurrent && scopedStatus && range != null && data.carYear == linkYear &&
                data.evObservationMetadata["FUEL_LEVEL_LOW"]?.propertyId == 0x11200405 &&
                observationReason(data, "FUEL_LEVEL_LOW", now) == "available"
        }
        val max = actual ?: reported.takeIf { scopedStatus }
        val wireBasis = when { actual != null -> "usable"; max != null -> "reported"; else -> "absent" }
        var current = data?.evBatteryLevelWh?.takeIf {currentReason=="available"}?.toLong()
        if (current != null && max != null && current > max) { current=null; currentReason="inconsistent" }
        report("range","RANGE_REMAINING",rangeReason)
        report("current","EV_BATTERY_LEVEL",currentReason)
        report("maximum","EV_CURRENT_BATTERY_CAPACITY",maxReason)
        report("reported_capacity","INFO_EV_BATTERY_CAPACITY",reason(data,data?.evBatteryCapacityWh,"INFO_EV_BATTERY_CAPACITY",now,250_000.0,true,10_000.0))
        // Freeze JSON/calibration once, but revalidate its source and owner on every update.
        val configured = frozenPilot
        val scaleCurrent = when (frozenScaleBasis) {
            ScaleBasis.usable -> actual != null
            ScaleBasis.reported -> reported != null
            ScaleBasis.manual -> true
            ScaleBasis.absent -> false
        }
        val admission = when {
            frozenModel == null || configured == null -> "not_frozen"
            generation != frozenGeneration || data?.carMake != configured.sourceMake || data?.carModel != configured.sourceModel -> "owner"
            data?.fuelTypes?.toSet() != setOf(10) -> "source"
            configured.profileId == CarPlayEvPilotConfiguration.GM_ROUTING && !scopedStatus -> "source"
            savedSharing && configured.averageChargeKw.isEmpty() && !matchesProductionSource(data, reported) -> "source"
            savedSharing && (range == null || data?.rangeKm?.let { it > 0f } != true) -> "range"
            !scaleCurrent -> "scale"
            current == null || !frozenModel!!.acceptsCurrentCharge(current,max) -> "current"
            configured.profileId in setOf(CarPlayEvPilotConfiguration.GM_SOURCE, CarPlayEvPilotConfiguration.GM_ROUTING) && range == null -> "range"
            else -> "admitted"
        }
        val model = frozenModel.takeIf { admission == "admitted" }
        val percent = if (scopedStatus && current != null && max != null && max > 0)
            (current.toDouble() / max * 100_000).toLong().coerceIn(0,100_000) else null
        // The zero is a reference sender convention, not a measured battery reserve.
        val minimum = 0L.takeIf { scopedStatus && max != null }
        val chargeReason = reason(data,data?.evChargeState?.toFloat(),"EV_CHARGE_STATE",now,4.0,minimum=1.0)
        val charging = data?.evChargeState?.takeIf { scopedStatus && chargeReason == "available" }
            ?.let { it == 1 }
        val candidate = if (referenceRange && current != null && max != null && max > 0 && range != null &&
            current.toDouble() / max >= 0.2) {
            val estimate = data!!.rangeKm!!.toDouble() * max / current
            if (estimate.isFinite() && estimate > 0 && estimate <= 65535)
                ((estimate / 10).roundToInt() * 10).takeIf { it in 100..1500 } else null
        } else null
        if (candidate == null) {
            // Never retain an estimate across invalid/currently unavailable source inputs.
            heldFullRange = null
            fullRangeAdmittedMs = null
        } else {
            val admittedAt = fullRangeAdmittedMs
            if (heldFullRange == null || admittedAt == null || now < admittedAt || now-admittedAt >= 60_000) {
                heldFullRange = candidate
                fullRangeAdmittedMs = now
            }
        }
        val socBasis = wireBasis.takeIf { percent != null } ?: "absent"
        runCatching { receipt("carplay-ev-admission wireBasis=$wireBasis socBasis=$socBasis modelBasis=$frozenScaleBasis model=$admission fullRange=${if (heldFullRange != null) "available" else "absent"} charging=${if (charging != null) "available" else "absent"}") }
        if (range == null && current == null && max == null && temperature == null) return null
        return OalEvSnapshot(range,current,max,temperature,model,
            minimumChargeWh=minimum,chargePercentMilli=percent,charging=charging,fullRangeKm=heldFullRange,
            electricRangeWarning=electricWarning)
    }

    companion object {
        internal fun savedPilot(settings: com.openautolink.app.data.CarPlaySettings?) = CarPlayEvPilotConfiguration(
            enabled = settings?.evDataEnabled == true,
            profileId = CarPlayEvPilotConfiguration.SAVED_SHARING,
            averageChargeKw = settings?.evAverageDcChargeKw.orEmpty(),
        )

        /** Uses the existing atomic saved CarPlay record, never another preference owner/collector. */
        fun forContext(context: android.content.Context): OalCarPlayVehicleSnapshotAdapter {
            val app = context.applicationContext
            return OalCarPlayVehicleSnapshotAdapter(pilotConfiguration = {
                savedPilot(runCatching {
                    kotlinx.coroutines.runBlocking {
                        kotlinx.coroutines.withTimeoutOrNull(1500L) {
                            com.openautolink.app.data.AppPreferences.getInstance(app).carPlaySettings.first()
                        }
                    }
                }.getOrNull())
            })
        }
    }
}
