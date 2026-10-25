package com.openautolink.carplaylab.telecom

import android.content.Context
import android.content.pm.PackageManager
import android.telecom.PhoneAccountHandle
import com.openautolink.carplaylab.telecom.TelecomAccountDiagnostics.Stage

/** Read-only account/feature inspection. No registration, account enumeration or settings launch. */
object PassiveTelecomCensus {
    enum class Account { ABSENT, ENABLED, DISABLED, UNKNOWN }
    data class Result(val sdk: Int, val ownAccount: Account,
        val connectionServiceFeature: Boolean? = null, val telecomFeature: Boolean? = null,
        val automotiveFeature: Boolean? = null, val telecomPresent: Boolean = false,
        val voiceCapable: Boolean? = null, val settingsResolvable: Boolean? = null,
        val readPermissionGranted: Boolean = false,
        val ownReadOutcome: TelecomAccountDiagnostics.Outcome = TelecomAccountDiagnostics.Outcome.READ_PERMISSION_REQUIRED,
        val settingsAvailability: TelecomAccountSettings.Availability = TelecomAccountSettings.Availability.UNAVAILABLE,
        val receiptSaved: Boolean = false)
    fun read(context: Context, handle: PhoneAccountHandle, generation: Long = 0): Result {
        require(handle.componentName.packageName == context.packageName)
        val manager = try { context.getSystemService(android.telecom.TelecomManager::class.java) } catch (_: RuntimeException) { null }
        val own = OwnPhoneAccountAccess.read(context, handle)
        val saved = TelecomAccountDiagnostics.record(context, Stage.CENSUS_OWN_READ, own.outcome, generation)
        val pm = context.packageManager
        fun feature(name: String): Boolean? = try { pm.hasSystemFeature(name) } catch (_: RuntimeException) { null }
        val voice = try { context.getSystemService(android.telephony.TelephonyManager::class.java)?.isVoiceCapable }
            catch (_: RuntimeException) { null }
        val settings = TelecomAccountSettings.inspect(context).availability
        val settingsSaved = TelecomAccountDiagnostics.record(context, Stage.SETTINGS_LOOKUP,
            TelecomAccountDiagnostics.Outcome.valueOf(settings.name), generation)
        val connection = feature(PackageManager.FEATURE_CONNECTION_SERVICE)
        val telecomFeature = feature(PackageManager.FEATURE_TELECOM)
        val automotive = feature(PackageManager.FEATURE_AUTOMOTIVE)
        val granted = context.checkSelfPermission(android.Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED
        fun number(value: Boolean?) = if (value == null) -1 else if (value) 1 else 0
        val inspectSaved = TelecomAccountDiagnostics.recordInspection(context, intArrayOf(android.os.Build.VERSION.SDK_INT,
            number(connection), number(telecomFeature), number(automotive), number(manager != null), number(voice), number(granted),
            own.outcome.ordinal, settings.ordinal), generation)
        return Result(android.os.Build.VERSION.SDK_INT, own.account, connection, telecomFeature, automotive, manager != null, voice,
            settings != TelecomAccountSettings.Availability.UNAVAILABLE, granted,
            own.outcome, settings, saved && settingsSaved && inspectSaved)
    }
}
