package com.openautolink.app.ui.projection

import com.openautolink.app.diagnostics.UploadFailureCode
import com.openautolink.app.diagnostics.UploadResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class LogUploadState { IDLE, UPLOADING, SUCCESS, ERROR }

internal data class LogUploadFeedback(
    val state: LogUploadState = LogUploadState.IDLE,
    val message: String? = null,
)

/** ViewModel-owned feedback survives menu dismissal. Only a new manual upload replaces it. */
internal class LogUploadAction {
    private val _feedback = MutableStateFlow(LogUploadFeedback())
    val feedback = _feedback.asStateFlow()
    private val actionLock = Any()
    private var activeAttempt: Any? = null

    /** The operation must offload blocking disk/network work, as LogUploader requires. */
    fun upload(scope: CoroutineScope, operation: suspend () -> UploadResult) {
        if (!scope.isActive) return
        val attempt = Any()
        synchronized(actionLock) {
            if (activeAttempt != null) return
            activeAttempt = attempt
            _feedback.value = LogUploadFeedback(LogUploadState.UPLOADING, "Uploading")
        }
        val job = scope.launch {
            val result = try {
                operation().also { currentCoroutineContext().ensureActive() }
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (_: Exception) {
                UploadResult.Failure("", UploadFailureCode.UNKNOWN)
            }
            finish(attempt, when (result) {
                is UploadResult.Success -> LogUploadFeedback(LogUploadState.SUCCESS,
                    "Uploaded: ${result.fileCount} files. ${result.coverage}")
                is UploadResult.Failure -> failureFeedback(result)
            })
        }
        // Also covers owner cancellation before the launched coroutine ever enters its body.
        job.invokeOnCompletion { cause ->
            if (cause != null) finish(attempt, failureFeedback(UploadResult.Failure("",
                if (cause is CancellationException) UploadFailureCode.CANCELED else UploadFailureCode.UNKNOWN)))
        }
    }

    private fun finish(attempt: Any, feedback: LogUploadFeedback) = synchronized(actionLock) {
        if (activeAttempt === attempt) {
            _feedback.value = feedback
            activeAttempt = null
        }
    }

    private fun failureFeedback(result: UploadResult.Failure): LogUploadFeedback {
        val code = when (result.code) {
            UploadFailureCode.HTTP -> result.httpStatus?.takeIf { it in 100..599 }?.let { "HTTP_$it" } ?: "HTTP"
            UploadFailureCode.UNKNOWN -> "UPLOAD_FAILED"
            else -> result.code.name
        }
        val hint = when (result.code) {
            UploadFailureCode.DISABLED -> "Enable Upload Logs in Settings."
            UploadFailureCode.NOT_CONFIGURED -> "Set the upload URL and token in Settings."
            UploadFailureCode.HTTPS_REQUIRED -> "Set a valid HTTPS upload URL in Settings."
            UploadFailureCode.NO_LOGS -> "No eligible evidence; collect diagnostics and retry (File Logging is optional)."
            UploadFailureCode.SNAPSHOT -> "Source logs retained; retry the log snapshot."
            UploadFailureCode.HTTP -> "Source logs retained; check upload settings or retry later."
            UploadFailureCode.NETWORK -> "Source logs retained; check internet and retry."
            UploadFailureCode.CANCELED -> "Upload canceled; retry when ready."
            UploadFailureCode.UNKNOWN -> "Retry the upload."
        }
        // Deliberately never display reason, exception text, URLs, tokens or response bodies.
        return LogUploadFeedback(LogUploadState.ERROR, "Upload failed ($code). $hint")
    }
}
