package com.openautolink.app.transport

import android.content.Context
import android.util.Base64
import android.util.Log
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.diagnostics.DiagnosticLog
import com.openautolink.app.session.BridgeInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Manages bridge binary updates: checks GitHub Releases, downloads the binary,
 * and pushes it to the bridge over the OAL control channel.
 *
 * The update flow:
 * 1. On bridge hello, compare bridge_sha256 against cached latest release SHA-256
 * 2. If mismatch + auto-update enabled → download binary from GitHub
 * 3. Send bridge_update_offer → wait for accept → stream chunks → complete
 * 4. Bridge verifies and restarts; app auto-reconnects
 */
class BridgeUpdateManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val sendMessage: suspend (ControlMessage) -> Unit
) {
    companion object {
        private const val TAG = "BridgeUpdateManager"
        private const val GITHUB_API = "https://api.github.com"
        private const val ASSET_NAME = "openautolink-headless"
        private const val CHUNK_SIZE = 49152 // 48KB decoded per chunk (base64 = ~65KB JSON line)
        private const val CACHE_DIR = "bridge_updates"
        private const val VERSION_CHECK_CACHE_MS = 3600_000L // 1 hour
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val preferences = AppPreferences.getInstance(context)

    private val _updateState = MutableStateFlow(BridgeUpdateState.IDLE)
    val updateState: StateFlow<BridgeUpdateState> = _updateState.asStateFlow()

    private val _updateMessage = MutableStateFlow("")
    val updateMessage: StateFlow<String> = _updateMessage.asStateFlow()

    private val _latestVersion = MutableStateFlow<String?>(null)
    val latestVersion: StateFlow<String?> = _latestVersion.asStateFlow()

    private val _bridgeVersion = MutableStateFlow<String?>(null)
    val bridgeVersion: StateFlow<String?> = _bridgeVersion.asStateFlow()

    private val _lastCheckTime = MutableStateFlow<Long?>(null)
    val lastCheckTime: StateFlow<Long?> = _lastCheckTime.asStateFlow()

    private val _updateHistory = MutableStateFlow<List<UpdateHistoryEntry>>(emptyList())
    val updateHistory: StateFlow<List<UpdateHistoryEntry>> = _updateHistory.asStateFlow()

    private var updateJob: Job? = null
    private var transferJob: Job? = null
    private var cachedBinaryPath: String? = null
    private var cachedBinarySha256: String? = null
    private var cachedBinaryVersion: String? = null
    private var lastVersionCheckMs = 0L
    private var cachedRelease: GitHubRelease? = null
    private var lastPushedSha: String? = null  // SHA of the binary we last pushed to the bridge
    private var isManualCheck = false  // true when triggered from Settings UI

    /**
     * Called when bridge hello is received. Checks if an update is needed
     * and starts the update flow if so.
     */
    fun onBridgeConnected(bridgeInfo: BridgeInfo) {
        _bridgeVersion.value = bridgeInfo.bridgeVersion
        // Reset state on reconnect — don't carry stale APPLIED/FAILED from last session
        if (_updateState.value == BridgeUpdateState.APPLIED ||
            _updateState.value == BridgeUpdateState.FAILED ||
            _updateState.value == BridgeUpdateState.OFFERING ||
            _updateState.value == BridgeUpdateState.DEFERRED) {
            _updateState.value = BridgeUpdateState.IDLE
            _updateMessage.value = ""
        }
        // Clear version check cache so we re-check after reconnect
        lastVersionCheckMs = 0
        updateJob?.cancel()
        transferJob?.cancel()
        updateJob = scope.launch {
            try {
                checkAndUpdate(bridgeInfo)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed: ${e.message}")
                DiagnosticLog.w("update", "Update check failed: ${e.message}")
                _updateState.value = BridgeUpdateState.IDLE
            } finally {
                isManualCheck = false
            }
        }
    }

    /**
     * Handle a bridge update response message.
     */
    fun onUpdateMessage(message: ControlMessage) {
        when (message) {
            is ControlMessage.BridgeUpdateAccept -> {
                Log.i(TAG, "Bridge accepted update — starting transfer")
                DiagnosticLog.i("update", "Bridge accepted update — starting transfer")
                _updateState.value = BridgeUpdateState.TRANSFERRING
                _updateMessage.value = "Transferring update..."
                addHistory("Transfer started")
                transferJob?.cancel()
                transferJob = scope.launch { transferBinary() }
            }
            is ControlMessage.BridgeUpdateReject -> {
                Log.i(TAG, "Bridge rejected update: ${message.reason}")
                DiagnosticLog.i("update", "Bridge rejected update: ${message.reason}")
                _updateState.value = BridgeUpdateState.IDLE
                _updateMessage.value = when (message.reason) {
                    "disabled" -> "Bridge updates disabled (dev mode)"
                    "in_session" -> "Bridge busy (phone connected)"
                    else -> "Update rejected: ${message.reason}"
                }
                addHistory("Rejected: ${message.reason}")
            }
            is ControlMessage.BridgeUpdateStatus -> {
                Log.i(TAG, "Bridge update status: ${message.status} — ${message.message}")
                DiagnosticLog.i("update", "Bridge update: ${message.status} — ${message.message}")
                _updateMessage.value = message.message
                when (message.status) {
                    "verified", "applying" -> _updateState.value = BridgeUpdateState.APPLYING
                    "deferred" -> {
                        _updateState.value = BridgeUpdateState.DEFERRED
                        _updateMessage.value = "Update ready — will apply when phone disconnects"
                        addHistory("Deferred: waiting for phone disconnect")
                    }
                    "applied" -> {
                        _updateState.value = BridgeUpdateState.APPLIED
                        _updateMessage.value = "Update applied — bridge restarting..."
                        addHistory("Applied: ${cachedBinaryVersion ?: "unknown"}")
                    }
                    "failed" -> {
                        _updateState.value = BridgeUpdateState.FAILED
                        addHistory("Failed: ${message.message}")
                    }
                }
            }
            else -> {}
        }
    }

    /**
     * Force a manual update check (triggered from settings UI).
     * Bypasses the build_source guard — user explicitly confirmed they want
     * to overwrite a local/dev build with the GitHub release.
     */
    fun triggerManualCheck(bridgeInfo: BridgeInfo?) {
        if (bridgeInfo == null) return
        lastVersionCheckMs = 0 // bypass cache
        lastPushedSha = null // allow manual retry
        isManualCheck = true
        onBridgeConnected(bridgeInfo)
    }

    private suspend fun checkAndUpdate(bridgeInfo: BridgeInfo) {
        val autoUpdate = preferences.bridgeAutoUpdate.first()
        if (!autoUpdate) {
            Log.d(TAG, "Bridge auto-update disabled in app settings")
            _updateState.value = BridgeUpdateState.IDLE
            _updateMessage.value = "Auto-update disabled"
            return
        }

        val bridgeSha = bridgeInfo.bridgeSha256
        if (bridgeSha.isNullOrBlank()) {
            Log.d(TAG, "Bridge didn't report SHA-256 — skipping update check")
            _updateState.value = BridgeUpdateState.IDLE
            return
        }

        _updateState.value = BridgeUpdateState.CHECKING
        _updateMessage.value = "Checking for updates..."
        DiagnosticLog.i("update", "Check started: bridge=${bridgeInfo.bridgeVersion} sha=${bridgeSha.take(12)}...")

        // Check GitHub for latest release
        val release = fetchLatestRelease() ?: run {
            _updateState.value = BridgeUpdateState.IDLE
            _updateMessage.value = "Could not check for updates (no internet?)"
            addHistory("Check failed: no internet")
            return
        }

        _latestVersion.value = release.version
        _lastCheckTime.value = System.currentTimeMillis()

        // Find the bridge binary asset
        val asset = release.assets.find { it.name == ASSET_NAME } ?: run {
            Log.w(TAG, "No $ASSET_NAME asset in release ${release.version}")
            _updateState.value = BridgeUpdateState.IDLE
            _updateMessage.value = "No bridge binary in latest release"
            return
        }

        // Compare SHA-256 of running bridge against the release asset hash
        // We need to download to compute SHA — or use the asset checksum if published
        // For now: download the binary and check its hash against the bridge's reported hash
        val localFile = downloadAsset(asset, release.version) ?: run {
            _updateState.value = BridgeUpdateState.IDLE
            _updateMessage.value = "Download failed"
            return
        }

        val localSha = computeSha256(localFile)
        cachedBinaryPath = localFile.absolutePath
        cachedBinarySha256 = localSha
        cachedBinaryVersion = release.version

        DiagnosticLog.i("update", "SHA compare: bridge=${bridgeSha.take(12)}... github=${localSha.take(12)}... match=${localSha == bridgeSha}")

        if (localSha == bridgeSha) {
            Log.i(TAG, "Bridge is up to date (${release.version})")
            _updateState.value = BridgeUpdateState.UP_TO_DATE
            _updateMessage.value = "Bridge ${release.version} is up to date"
            addHistory("Up to date: ${release.version}")
            return
        }

        Log.i(TAG, "Bridge update available: ${bridgeInfo.bridgeVersion ?: "?"} → ${release.version}")
        DiagnosticLog.i("update", "Bridge update available: ${bridgeInfo.bridgeVersion ?: "?"} → ${release.version}")
        _updateState.value = BridgeUpdateState.UPDATE_AVAILABLE
        _updateMessage.value = "Update available: ${release.version}"
        addHistory("Update available: ${bridgeInfo.bridgeVersion ?: "?"} → ${release.version}")

        // Guard: bridge is running a local/dev build — don't auto-overwrite it.
        // Manual trigger from Settings bypasses this (user explicitly confirmed).
        val buildSource = bridgeInfo.buildSource
        if (buildSource == "local" && !isManualCheck) {
            Log.i(TAG, "Bridge is a local/dev build — skipping auto-update")
            DiagnosticLog.i("update", "Bridge has build_source=local — requires manual confirmation to overwrite with GitHub release")
            _updateMessage.value = "Bridge is a dev build — use Settings to update to ${release.version}"
            addHistory("Skipped: bridge is a local/dev build")
            return
        }

        // Guard: if we already pushed this exact binary and the bridge still has a
        // different SHA, something replaced it (dev deployed a local build). Don't loop.
        if (lastPushedSha != null && lastPushedSha == localSha) {
            Log.i(TAG, "Already pushed this binary — bridge SHA still differs (dev build?)")
            DiagnosticLog.i("update", "Already pushed SHA ${localSha.take(12)}... — bridge didn't keep it (dev override?)")
            _updateMessage.value = "Update available (bridge was overridden)"
            addHistory("Skipped: already pushed, bridge has different binary")
            return
        }
        lastPushedSha = localSha

        // Send offer to bridge
        val autoApply = preferences.bridgeAutoApply.first()
        _updateState.value = BridgeUpdateState.OFFERING
        _updateMessage.value = "Offering update to bridge..."
        sendMessage(ControlMessage.BridgeUpdateOffer(
            version = release.version,
            size = localFile.length().toInt(),
            sha256 = localSha,
            autoApply = autoApply
        ))
        // Wait for bridge_update_accept or bridge_update_reject via onUpdateMessage()
    }

    private suspend fun transferBinary() {
        val path = cachedBinaryPath ?: return
        val sha = cachedBinarySha256 ?: return
        val file = File(path)
        if (!file.exists()) {
            _updateState.value = BridgeUpdateState.FAILED
            _updateMessage.value = "Cached binary not found"
            return
        }

        try {
            withContext(Dispatchers.IO) {
                val bytes = file.readBytes()
                val totalChunks = (bytes.size + CHUNK_SIZE - 1) / CHUNK_SIZE
                var offset = 0

                for (i in 0 until totalChunks) {
                    // Abort if state was reset (e.g., TCP dropped, reconnect reset state)
                    if (_updateState.value != BridgeUpdateState.TRANSFERRING) {
                        Log.w(TAG, "Transfer aborted: state changed to ${_updateState.value}")
                        return@withContext
                    }

                    val chunkEnd = minOf(offset + CHUNK_SIZE, bytes.size)
                    val chunk = bytes.copyOfRange(offset, chunkEnd)
                    val b64 = Base64.encodeToString(chunk, Base64.NO_WRAP)

                    sendMessage(ControlMessage.BridgeUpdateData(
                        offset = offset,
                        length = chunk.size,
                        data = b64
                    ))

                    offset = chunkEnd

                    // Throttle to avoid overwhelming the control channel
                    // ~48KB per chunk at ~20 chunks/sec = ~1MB/s
                    delay(50)

                    if (i % 10 == 0) {
                        val pct = (offset * 100L / bytes.size).toInt()
                        _updateMessage.value = "Transferring: $pct%"
                    }
                }
            }

            // Signal transfer complete (only if still in TRANSFERRING state)
            if (_updateState.value == BridgeUpdateState.TRANSFERRING) {
                _updateMessage.value = "Transfer complete, verifying..."
                DiagnosticLog.i("update", "Transfer complete: ${file.length()} bytes sent")
                sendMessage(ControlMessage.BridgeUpdateComplete(sha256 = sha))
            }
            // Wait for bridge_update_status via onUpdateMessage()

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Transfer failed: ${e.message}")
            _updateState.value = BridgeUpdateState.FAILED
            _updateMessage.value = "Transfer failed: ${e.message}"
        }
    }

    private suspend fun fetchLatestRelease(): GitHubRelease? {
        val now = System.currentTimeMillis()
        if (now - lastVersionCheckMs < VERSION_CHECK_CACHE_MS) {
            // Return cached result if recent
            return cachedRelease
        }

        return withContext(Dispatchers.IO) {
            try {
                val owner = preferences.githubRepoOwner.first()
                val repo = preferences.githubRepoName.first()
                val url = URL("$GITHUB_API/repos/$owner/$repo/releases/latest")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000

                if (conn.responseCode != 200) {
                    Log.w(TAG, "GitHub API returned ${conn.responseCode}")
                    conn.disconnect()
                    return@withContext null
                }

                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                lastVersionCheckMs = System.currentTimeMillis()

                val release = parseRelease(body)
                cachedRelease = release
                release
            } catch (e: Exception) {
                Log.w(TAG, "GitHub API call failed: ${e.message}")
                null
            }
        }
    }

    private fun parseRelease(jsonBody: String): GitHubRelease? {
        return try {
            val obj = json.parseToJsonElement(jsonBody).jsonObject
            val tagName = obj["tag_name"]?.jsonPrimitive?.content ?: return null
            val version = tagName.removePrefix("v")

            val assets = obj["assets"]?.jsonArray?.mapNotNull { assetEl ->
                val assetObj = assetEl.jsonObject
                val name = assetObj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val downloadUrl = assetObj["browser_download_url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val size = assetObj["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0
                GitHubAsset(name, downloadUrl, size)
            } ?: emptyList()

            GitHubRelease(version, assets)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse release JSON: ${e.message}")
            null
        }
    }

    private suspend fun downloadAsset(asset: GitHubAsset, version: String): File? {
        val cacheDir = File(context.filesDir, CACHE_DIR)
        cacheDir.mkdirs()
        val targetFile = File(cacheDir, "${ASSET_NAME}-$version")

        // Return cached if exists
        if (targetFile.exists() && targetFile.length() == asset.size) {
            Log.d(TAG, "Using cached binary: ${targetFile.absolutePath}")
            return targetFile
        }

        return withContext(Dispatchers.IO) {
            try {
                _updateMessage.value = "Downloading bridge binary..."
                Log.i(TAG, "Downloading ${asset.downloadUrl}")
                DiagnosticLog.i("update", "Downloading bridge binary: ${asset.name}")

                val url = URL(asset.downloadUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 30_000
                conn.readTimeout = 60_000
                conn.instanceFollowRedirects = true

                if (conn.responseCode != 200) {
                    Log.w(TAG, "Download returned ${conn.responseCode}")
                    conn.disconnect()
                    return@withContext null
                }

                val tempFile = File(cacheDir, "${ASSET_NAME}-$version.tmp")
                conn.inputStream.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output, bufferSize = 8192)
                    }
                }
                conn.disconnect()

                // Atomically rename
                tempFile.renameTo(targetFile)

                // Clean up old cached versions
                cacheDir.listFiles()?.forEach { f ->
                    if (f.name.startsWith(ASSET_NAME) && f != targetFile) {
                        f.delete()
                    }
                }

                Log.i(TAG, "Downloaded ${targetFile.length()} bytes to ${targetFile.absolutePath}")
                targetFile
            } catch (e: Exception) {
                Log.w(TAG, "Download failed: ${e.message}")
                null
            }
        }
    }

    private suspend fun computeSha256(file: File): String {
        return withContext(Dispatchers.IO) {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    fun cancel() {
        updateJob?.cancel()
        updateJob = null
        transferJob?.cancel()
        transferJob = null
        _updateState.value = BridgeUpdateState.IDLE
    }

    private fun addHistory(event: String) {
        val entry = UpdateHistoryEntry(
            timestamp = System.currentTimeMillis(),
            event = event
        )
        val current = _updateHistory.value.toMutableList()
        current.add(0, entry) // newest first
        if (current.size > 20) current.removeAt(current.lastIndex) // cap at 20
        _updateHistory.value = current
    }
}

enum class BridgeUpdateState {
    IDLE,
    CHECKING,
    UP_TO_DATE,
    UPDATE_AVAILABLE,
    OFFERING,
    TRANSFERRING,
    APPLYING,
    DEFERRED,
    APPLIED,
    FAILED
}

data class UpdateHistoryEntry(
    val timestamp: Long,
    val event: String
)

private data class GitHubRelease(
    val version: String,
    val assets: List<GitHubAsset>
)

private data class GitHubAsset(
    val name: String,
    val downloadUrl: String,
    val size: Long
)
