package com.openautolink.companion.service

/** Distinguishes Android's null-intent START_STICKY redelivery from explicit commands. */
internal object CompanionRestartPolicy {
    fun shouldRestoreTransport(
        intentWasNull: Boolean,
        transportDesired: Boolean,
    ): Boolean = intentWasNull && transportDesired
}
