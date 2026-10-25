package com.openautolink.carplaylab.telecom

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import com.openautolink.carplaylab.telecom.TelecomAccountDiagnostics.Outcome

/** Exact own handle only. Missing or denied permission is UNKNOWN, never ABSENT. */
internal object OwnPhoneAccountAccess {
    data class Read(val account: PassiveTelecomCensus.Account, val outcome: Outcome)
    fun read(context: Context, handle: PhoneAccountHandle): Read {
        require(handle.componentName.packageName == context.packageName)
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_NUMBERS) != PackageManager.PERMISSION_GRANTED)
            return Read(PassiveTelecomCensus.Account.UNKNOWN, Outcome.READ_PERMISSION_REQUIRED)
        return try {
            val manager = context.getSystemService(TelecomManager::class.java)
                ?: return Read(PassiveTelecomCensus.Account.UNKNOWN, Outcome.NO_MANAGER)
            val own = manager.getPhoneAccount(handle)
            when {
                own == null -> Read(PassiveTelecomCensus.Account.ABSENT, Outcome.NULL_ACCOUNT)
                own.isEnabled -> Read(PassiveTelecomCensus.Account.ENABLED, Outcome.ACCOUNT_ENABLED)
                else -> Read(PassiveTelecomCensus.Account.DISABLED, Outcome.ACCOUNT_DISABLED)
            }
        } catch (error: RuntimeException) { Read(PassiveTelecomCensus.Account.UNKNOWN, TelecomAccountDiagnostics.failure(error)) }
    }
}
