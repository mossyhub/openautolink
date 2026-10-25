package com.openautolink.app.diagnostics

import java.io.Closeable
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Metadata contains only log basenames and filesystem times, never endpoint credentials. */
internal data class UploadSelection(val source: File, val name: String, val bytes: Long, val modified: Long, val ev: Boolean)
internal data class UploadSkipped(val name: String, val ev: Boolean, val reason: String)
internal data class UploadPlan(val selected: List<UploadSelection>, val skipped: List<UploadSkipped>, val now: Long)
internal class LogUploadArchive(
    val file: File, val plan: UploadPlan,
    val regularTruncated: Int, val evTruncated: Int,
    val flushConfirmed: Boolean,
    val bluetoothFlushConfirmed: Boolean = true,
) : Closeable {
    val evFiles: Int get() = plan.selected.count { it.ev }
    val evOmitted: Int get() = plan.skipped.count { it.ev && it.reason != "outside_ev_retention" }
    val regularOmitted: Int get() = plan.skipped.count { !it.ev }
    val summary: String get() = "EV $evFiles included, $evOmitted omitted, $evTruncated partial; " +
        "regular $regularOmitted omitted, $regularTruncated partial" +
        (if (flushConfirmed) "" else "; EV flush unconfirmed") +
        "; Bluetooth ${plan.selected.count { BluetoothReconnectRecorder.runName.matches(it.source.name) }} included, " +
        "${plan.skipped.count { BluetoothReconnectRecorder.runName.matches(it.name) }} omitted" +
        (if (bluetoothFlushConfirmed) "" else "; Bluetooth flush unconfirmed")
    override fun close() { file.delete() }

    companion object {
        const val MANIFEST_RESERVE = 64 * 1024L
        const val MAX_RAW_BYTES = 25L * 1024 * 1024
        const val MAX_MEMBER_BYTES = 16L * 1024 * 1024 - 1

        fun select(files: List<File>, now: Long): UploadPlan {
            val selected = mutableListOf<UploadSelection>()
            val skipped = mutableListOf<UploadSkipped>()
            val used = mutableSetOf("upload_manifest.log")
            var remaining = MAX_RAW_BYTES - MANIFEST_RESERVE
            val unique = files.distinctBy { it.canonicalPath }
            val localRun = Regex("microphone_diagnostic_run_[0-9]{19}\\.log")
            fun capture(file: File) = CaptureTerminalLogStore.runName.matches(file.name)
            fun local(file: File) = localRun.matches(file.name)
            fun bluetooth(file: File) = BluetoothReconnectRecorder.runName.matches(file.name)
            fun telecom(file: File) = file.name == com.openautolink.carplaylab.telecom.TelecomAccountDiagnostics.FILE_NAME ||
                file.name == com.openautolink.carplaylab.telecom.NativeCallDiagnostics.FILE_NAME
            fun telecomLimit(file: File) = if (file.name == com.openautolink.carplaylab.telecom.NativeCallDiagnostics.FILE_NAME)
                com.openautolink.carplaylab.telecom.NativeCallDiagnostics.MAX_BYTES else com.openautolink.carplaylab.telecom.TelecomAccountDiagnostics.MAX_BYTES
            val telecomReceipts = unique.filter { telecom(it) && it.length() <= telecomLimit(it) }
                .groupBy { it.name }.values.map { it.maxBy { file -> file.lastModified() } }.toSet()
            val bluetoothRuns = unique.filter { bluetooth(it) && it.length() <= BluetoothReconnectRecorder.MAX_BYTES &&
                it.lastModified() >= now - BluetoothReconnectRecorder.TTL_MS }
                .sortedByDescending { it.lastModified() }.take(3).toSet()
            val captureRuns = unique.filter { capture(it) && it.length() <= CaptureTerminalLogStore.MAX_BYTES }
                .sortedByDescending { it.name }.take(CaptureTerminalLogStore.MAX_RUNS).toSet()
            val localRuns = unique.filter { local(it) && it.length() <= 32768 }
                .sortedByDescending { it.name }.take(12).toSet()
            val localAlias = unique.filter { it.name == "microphone_diagnostic.log" && it.length() <= 32768 }
                .filter { alias -> localRuns.any { run -> run.length()==alias.length() && run.readBytes().contentEquals(alias.readBytes()) } }.toSet()
            unique.sortedWith(compareByDescending<File> { it in captureRuns || it in localRuns || it in bluetoothRuns || it in telecomReceipts }
                    .thenByDescending { it.name.startsWith("ev_", true) }.thenByDescending { it.lastModified() })
                .forEachIndexed { index, file ->
                    val ev = file.name.startsWith("ev_", true)
                    val safe = file.name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(160)
                    var name = if (safe.endsWith(".log", true)) safe else "$safe.log"
                    if (!used.add(name.lowercase(java.util.Locale.ROOT))) {
                        name = "${name.dropLast(4)}_source${index}.log"
                        while (!used.add(name.lowercase(java.util.Locale.ROOT))) name = "_$name"
                    }
                    val bytes = file.length()
                    val modified = file.lastModified()
                    val window = if (ev) 7 * 86_400_000L else 6 * 3_600_000L
                    val retainedCapture = file in captureRuns || file in localRuns || file in bluetoothRuns || file in telecomReceipts
                    val reason = when {
                        !file.isFile || !file.name.endsWith(".log", true) -> "not_log_file"
                        file in localAlias -> "duplicate_latest_alias"
                        telecom(file) && bytes > telecomLimit(file) -> "telecom_receipt_bytes_limit"
                        telecom(file) && file !in telecomReceipts -> "outside_latest_telecom_receipt"
                        bluetooth(file) && bytes > BluetoothReconnectRecorder.MAX_BYTES -> "bluetooth_member_bytes_limit"
                        bluetooth(file) && modified < now - BluetoothReconnectRecorder.TTL_MS -> "outside_bluetooth_retention"
                        bluetooth(file) && file !in bluetoothRuns -> "outside_latest_three_bluetooth"
                        capture(file) && bytes > CaptureTerminalLogStore.MAX_BYTES -> "capture_member_bytes_limit"
                        local(file) && bytes > 32768 -> "diagnostic_member_bytes_limit"
                        (capture(file) || local(file)) && !retainedCapture -> "outside_latest_twelve"
                        !retainedCapture && modified < now - window -> if (ev) "outside_ev_retention" else "outside_recent_window"
                        bytes > MAX_MEMBER_BYTES -> "member_bytes_limit"
                        selected.size >= 63 -> "member_count_limit"
                        bytes > remaining -> "total_bytes_limit"
                        else -> null
                    }
                    if (reason != null) skipped += UploadSkipped(name, ev, reason)
                    else {
                        selected += UploadSelection(file, name, bytes, modified, ev)
                        remaining -= bytes
                    }
                }
            return UploadPlan(selected, skipped, now)
        }

        fun create(plan: UploadPlan, directory: File, flushConfirmed: Boolean = true, bluetoothFlushConfirmed: Boolean = true): LogUploadArchive {
            val file = File.createTempFile("oal_upload_", ".zip", directory)
            var regularTruncated = 0
            var evTruncated = 0
            val records = mutableListOf<String>()
            try {
                ZipOutputStream(file.outputStream().buffered()).use { zip ->
                    val buffer = ByteArray(32 * 1024)
                    plan.selected.forEachIndexed { index, selection ->
                        java.io.RandomAccessFile(selection.source, "r").use { source ->
                            if (source.length() < selection.bytes) throw java.io.EOFException("log shortened during snapshot")
                            if (source.length() == selection.bytes && selection.source.lastModified() != selection.modified) {
                                throw java.io.IOException("log changed during snapshot")
                            }
                            // A writer may be midway through a UTF-8 code point at the captured length.
                            var limit = selection.bytes
                            if (limit > 0) {
                                val tail = ByteArray(minOf(4L, limit).toInt())
                                source.seek(limit - tail.size)
                                source.readFully(tail)
                                var lead = tail.lastIndex
                                while (lead > 0 && (tail[lead].toInt() and 0xc0) == 0x80) lead--
                                val value = tail[lead].toInt() and 0xff
                                val width = when {
                                    value < 0x80 -> 1
                                    value in 0xc2..0xdf -> 2
                                    value in 0xe0..0xef -> 3
                                    value in 0xf0..0xf4 -> 4
                                    else -> 1
                                }
                                if (tail.size - lead < width) limit -= tail.size - lead
                            }
                            source.seek(0)
                            zip.putNextEntry(ZipEntry(selection.name))
                            var remaining = limit
                            while (remaining > 0) {
                                val count = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                if (count < 0) throw java.io.EOFException("log shortened during snapshot")
                                zip.write(buffer, 0, count)
                                remaining -= count
                            }
                            zip.closeEntry()
                            if (source.length() < selection.bytes ||
                                (source.length() == selection.bytes && selection.source.lastModified() != selection.modified)) {
                                throw java.io.IOException("log changed during snapshot")
                            }
                            val grown = source.length() > selection.bytes
                            val partial = grown || limit < selection.bytes
                            if (partial) { if (selection.ev) evTruncated++ else regularTruncated++ }
                            records += "selected=${selection.name} source_index=$index " +
                                "source_name=${selection.source.name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(160)} " +
                                "selected_bytes=${selection.bytes} uploaded_bytes=$limit modified_ms=${selection.modified} " +
                                "partial=$partial appended_after_snapshot=$grown"
                        }
                    }
                    val manifest = buildString {
                        appendLine("upload_manifest_version=1 snapshot_ms=${plan.now}")
                        appendLine("coverage=filesystem_mtime_not_continuous_drive")
                        appendLine("snapshot=bounded_prefix_not_atomic_content; later_appends_not_included")
                        appendLine("ev_window_ms=${7 * 86_400_000L} regular_window_ms=${6 * 3_600_000L}")
                        appendLine("ev_flush_confirmed=$flushConfirmed")
                        appendLine("bluetooth_flush_confirmed=$bluetoothFlushConfirmed bluetooth_window_ms=${BluetoothReconnectRecorder.TTL_MS}")
                        appendLine("ev_files=${plan.selected.count { it.ev }} regular_files=${plan.selected.count { !it.ev }}")
                        appendLine("regular_truncated=$regularTruncated ev_truncated=$evTruncated skipped_count=${plan.skipped.size}")
                        appendLine("selected_mtime_min_ms=${plan.selected.minOfOrNull { it.modified }} selected_mtime_max_ms=${plan.selected.maxOfOrNull { it.modified }}")
                        records.forEach { appendLine(it) }
                        plan.skipped.groupingBy { "${if (it.ev) "ev" else "regular"}:${it.reason}" }.eachCount().forEach { (reason, count) ->
                            appendLine("skipped_reason=$reason count=$count")
                        }
                        plan.skipped.take(100).forEach { appendLine("skipped=${it.name} reason=${it.reason}") }
                        appendLine("skipped_details_omitted=${(plan.skipped.size - 100).coerceAtLeast(0)}")
                    }.toByteArray(Charsets.UTF_8)
                    check(manifest.size <= MANIFEST_RESERVE) { "manifest exceeds reserved budget" }
                    zip.putNextEntry(ZipEntry("upload_manifest.log"))
                    zip.write(manifest)
                    zip.closeEntry()
                }
                return LogUploadArchive(file, plan, regularTruncated, evTruncated, flushConfirmed, bluetoothFlushConfirmed)
            } catch (e: Exception) {
                file.delete()
                throw e
            }
        }
    }
}
