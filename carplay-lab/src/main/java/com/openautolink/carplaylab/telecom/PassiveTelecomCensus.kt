package com.openautolink.carplaylab.telecom

import android.content.Context
import android.telecom.PhoneAccountHandle

/** Dormant read-only diagnostic. No registration, launches, listeners or manifest changes. */
object PassiveTelecomCensus {
    enum class Account { ABSENT, ENABLED, DISABLED, UNKNOWN }
    data class Result(val sdk: Int, val ownAccount: Account,
        val connectionServiceFeature: Boolean? = null, val telecomFeature: Boolean? = null,
        val automotiveFeature: Boolean? = null, val telecomPresent: Boolean = false,
        val voiceCapable: Boolean? = null, val settingsResolvable: Boolean? = null)
    fun read(context: Context, handle: PhoneAccountHandle): Result {
        require(handle.componentName.packageName == context.packageName)
        val manager = context.getSystemService(android.telecom.TelecomManager::class.java)
        val account = try {
            if (manager == null) Account.UNKNOWN
            else manager.getPhoneAccount(handle)?.let { if (it.isEnabled) Account.ENABLED else Account.DISABLED }
                ?: Account.ABSENT
        } catch (_: SecurityException) { Account.UNKNOWN }
          catch (_: RuntimeException) { Account.UNKNOWN }
        val pm = context.packageManager
        fun feature(name: String): Boolean? = try { pm.hasSystemFeature(name) } catch (_: RuntimeException) { null }
        val voice = try { context.getSystemService(android.telephony.TelephonyManager::class.java)?.isVoiceCapable }
            catch (_: RuntimeException) { null }
        val settings = try {
            val info = pm.resolveActivity(android.content.Intent(android.telecom.TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS),
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
            info != null && info.exported && info.enabled
        } catch (_: RuntimeException) { null }
        return Result(android.os.Build.VERSION.SDK_INT, account,
            feature(android.content.pm.PackageManager.FEATURE_CONNECTION_SERVICE),
            feature(android.content.pm.PackageManager.FEATURE_TELECOM),
            feature(android.content.pm.PackageManager.FEATURE_AUTOMOTIVE), manager != null, voice, settings)
    }
}
