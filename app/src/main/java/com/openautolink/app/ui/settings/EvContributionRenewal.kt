package com.openautolink.app.ui.settings

import com.openautolink.app.data.AppPreferences
import com.openautolink.app.data.EvContributionScope
import com.openautolink.app.data.EvGrantDecision
import com.openautolink.app.diagnostics.EvContributionConsentBinding
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.URI
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume

/** Bodyless admission check, not an upload. Never reconstruct or redirect the receiving URL. */
internal class EvReceiverCapability(
    private val openConnection: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
) {
    suspend fun supportsAccuracy(url: String, token: String): Boolean = withTimeoutOrNull(8_000L) {
        suspendCancellableCoroutine { continuation ->
            val task = FutureTask<Unit> {
                continuation.resume(request(url, token))
            }
            continuation.invokeOnCancellation {
                task.cancel(true)
                executor.remove(task)
            }
            try {
                if (!task.isCancelled) executor.execute(task)
            } catch (_: RejectedExecutionException) {
                continuation.resume(false)
            }
        }
    } ?: false

    internal fun request(url: String, token: String): Boolean {
        if (EvContributionConsentBinding.createAccuracy(url, token) == null) return false
        val uri = URI(url.trim())
        if (url != url.trim() || uri.rawPath != uri.normalize().rawPath ||
            (uri.port != -1 && uri.port !in 1..65535) || token.any { it == '\r' || it == '\n' }) return false
        return try {
            val connection = openConnection(uri.toURL())
            try {
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = 4_000
                connection.readTimeout = 4_000
                connection.requestMethod = "OPTIONS"
                connection.setRequestProperty("X-Upload-Token", token)
                connection.responseCode == 204 && connection.headerFields.entries
                    .filter { it.key?.equals("X-OAL-EV-Schema", ignoreCase = true) == true }
                    .flatMap { it.value } == listOf("3")
            } finally {
                connection.disconnect()
            }
        } catch (_: IOException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private companion object {
        // Bound workers and queued probes even if platform DNS/TLS ignores interruption.
        val executor = ThreadPoolExecutor(0, 1, 15L, TimeUnit.SECONDS, ArrayBlockingQueue(1),
            { runnable -> Thread(runnable, "ev-receiver-capability").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy())
    }
}

internal enum class EvRenewalResult(val message: String) {
    ENABLED("Expanded EV contribution enabled."),
    NOT_PARKED("Park the vehicle and try again. Your existing choice was not changed."),
    CONFIGURATION_REQUIRED("Configure an HTTPS upload URL and token, then try again."),
    RECEIVER_UNVERIFIED("Could not verify expanded EV support on the configured receiver. Your existing choice was not changed."),
    SUPERSEDED("The contribution setting or receiver configuration changed. Review it and try again."),
    SAVE_FAILED("Could not save your EV contribution choice. Try again."),
}

/** A positive reply is usable only by the explicit choice that requested it. */
internal class EvContributionRenewal(
    private val begin: () -> Long?,
    private val isCurrent: (Long) -> Boolean,
    private val readSaved: suspend () -> EvContributionScope,
    private val rawParkCurrent: () -> Boolean,
    private val supportsAccuracy: suspend (String, String) -> Boolean,
    private val grant: suspend (Long, EvContributionScope, String, () -> Boolean) -> EvGrantDecision,
) {
    constructor(
        preferences: AppPreferences,
        rawParkCurrent: () -> Boolean,
        supportsAccuracy: suspend (String, String) -> Boolean,
    ) : this(preferences::beginEvContributionRenewal, preferences::isEvRenewalCurrent,
        { preferences.evContributionScope.first() }, rawParkCurrent, supportsAccuracy,
        preferences::grantExpandedEvContribution)

    suspend fun renew(): EvRenewalResult {
        val generation = begin() ?: return EvRenewalResult.SUPERSEDED
        val request = readSaved()
        if (!isCurrent(generation)) return EvRenewalResult.SUPERSEDED
        if (!rawParkCurrent()) return EvRenewalResult.NOT_PARKED
        val binding = EvContributionConsentBinding.createAccuracy(request.url, request.token)
            ?: return EvRenewalResult.CONFIGURATION_REQUIRED
        val verified = supportsAccuracy(request.url, request.token)
        currentCoroutineContext().ensureActive()
        if (!isCurrent(generation)) return EvRenewalResult.SUPERSEDED
        if (!verified) return EvRenewalResult.RECEIVER_UNVERIFIED
        return when (grant(generation, request, binding.encode(), rawParkCurrent)) {
            EvGrantDecision.GRANTED -> if (isCurrent(generation)) EvRenewalResult.ENABLED else EvRenewalResult.SUPERSEDED
            EvGrantDecision.SUPERSEDED -> EvRenewalResult.SUPERSEDED
            EvGrantDecision.NOT_PARKED -> EvRenewalResult.NOT_PARKED
        }
    }
}
