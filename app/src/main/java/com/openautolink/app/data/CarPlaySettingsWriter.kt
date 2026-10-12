package com.openautolink.app.data

import kotlinx.coroutines.CancellationException

/** Password is an ephemeral Apply argument, never part of the configuration/state record.
 * null preserves the existing encrypted secret; empty explicitly clears it.
 */
class CarPlaySettingsWriter(
    private val saveConfiguration: suspend (CarPlaySettings) -> Unit,
    private val saveEncryptedPassword: (String) -> Unit,
) {
    suspend fun save(settings: CarPlaySettings, password: String?): Boolean = try {
        if (password != null) saveEncryptedPassword(password)
        saveConfiguration(settings)
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false // No exception message/stack can leak secret or provisioning material.
    }
}
