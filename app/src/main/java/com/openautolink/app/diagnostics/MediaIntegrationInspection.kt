package com.openautolink.app.diagnostics

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import org.xmlpull.v1.XmlPullParser

/** Raw local rows deliberately have no data-class toString or serialization API. Activity memory only. */
class MediaIntegrationResult internal constructor(private val text: String, private val coverage: MediaCoverage,
    val own: MediaOwnSnapshot) {
    fun renderLocalText(): String = text
    fun toUploadSummary(): MediaCoverage = coverage
}

/** Explicit refresh only: public registration/resource inspection, never binds a controller/session. */
class MediaIntegrationInspection(private val context: Context,
    private val provider: MediaSnapshotProvider = MediaSnapshotProvider { MediaDiagnosticHooks.own() }) {
    fun collect(): MediaIntegrationResult {
        val wall = System.currentTimeMillis()
        val started = SystemClock.elapsedRealtime()
        val own = try { provider.read() } catch (_: Exception) { MediaOwnSnapshot() }
        val pm = context.packageManager
        val out = BoundedText()
        var processed = 0; var failed = 0; var truncated = 0
        var mediaHandler = MediaHandlerKind.NOT_COLLECTED
        var homeHandler = MediaHandlerKind.NOT_COLLECTED
        var resources = MediaResourceCounts()
        val hosts = linkedSetOf<String>()
        fun field(block: () -> String): String = try { safe(block(), 240) }
            catch (_: SecurityException) { failed++; "DENIED" }
            catch (_: Exception) { failed++; "FAILED" }
        out.line("Public registrations only; absence is not exclusion, selection, audio ownership or OEM receipt.")
        out.line("Own boundary: initialized=${own.initialized} token=${own.tokenPresent} active=${own.active} phone=${own.phonePlaying} submitted=${own.submitted} cached=${own.cached} mode=${own.mode} owner=${own.owner} admission=${own.admission}")
        for (action in listOf("android.media.browse.MediaBrowserService", "androidx.media3.session.MediaLibraryService")) {
            out.line("Action $action")
            val rows = try { pm.queryIntentServices(Intent(action), PackageManager.GET_META_DATA) }
                catch (_: SecurityException) { failed++; out.line("query=DENIED visible=UNKNOWN"); continue }
                catch (_: Exception) { failed++; out.line("query=FAILED visible=UNKNOWN"); continue }
            val entries = rows.mapNotNull { row ->
                try { row.serviceInfo?.let { it.packageName + "/" + it.name } ?: run { failed++; null } }
                catch (_: Exception) { failed++; null }
            }.distinct().sorted().take(64)
            val unique = rows.mapNotNull { try { it.serviceInfo?.let { s -> s.packageName + "/" + s.name } } catch (_: Exception) { null } }.distinct().size
            val omitted = (unique - entries.size).coerceAtLeast(0); truncated += omitted
            out.line("raw=${rows.size} unique=$unique processed=${entries.size} truncated=$omitted")
            for (key in entries) {
                processed++
                try {
                    val s = rows.first { it.serviceInfo?.let { s -> s.packageName + "/" + s.name } == key }.serviceInfo!!
                    out.line("service=${safe(key, 240)}")
                    out.line(" label=" + field { safe(pm.getApplicationLabel(s.applicationInfo).toString(), 100) })
                    out.line(" manifest_enabled=${s.enabled} exported=${s.exported} permission=${safe(s.permission ?: "NONE", 240)}")
                    out.line(" component_override=" + field { pm.getComponentEnabledSetting(ComponentName(s.packageName, s.name)).toString() } +
                        " app_override=" + field { pm.getApplicationEnabledSetting(s.packageName).toString() })
                    val xml = try { s.applicationInfo.metaData?.getInt("com.android.automotive", 0) ?: 0 } catch (_: Exception) { failed++; 0 }
                    if (xml != 0) {
                        out.line(" automotive=" + field {
                            val values = linkedSetOf<String>(); var events = 0; var exhausted = false
                            pm.getResourcesForApplication(s.applicationInfo).getXml(xml).use { parser ->
                                while (parser.eventType != XmlPullParser.END_DOCUMENT && events < 512) {
                                    if (parser.eventType == XmlPullParser.START_TAG && parser.name == "uses") {
                                        if (values.size < 16) values += safe(parser.getAttributeValue(null, "name") ?: "", 80)
                                        else exhausted = true
                                    }
                                    events++; parser.next()
                                }
                                if (events >= 512) exhausted = true
                            }
                            if (exhausted) { truncated++; "TRUNCATED ${values.joinToString()}" } else "PRESENT ${values.joinToString()}"
                        })
                    }
                } catch (_: SecurityException) { failed++; out.line("row=DENIED") }
                catch (_: Exception) { failed++; out.line("row=FAILED") }
            }
        }
        for (intent in listOf(Intent(MEDIA_ACTION), Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))) {
            out.line("Handlers ${intent.action} home=${intent.categories?.contains(Intent.CATEGORY_HOME) == true}")
            val handler = try {
                val resolved = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
                out.line("default=" + if (resolved == null) "UNRESOLVED" else safe(resolved.packageName + "/" + resolved.name, 240))
                when (resolved?.packageName) {
                    null -> MediaHandlerKind.UNRESOLVED
                    "com.android.car.media" -> MediaHandlerKind.FIXED_MEDIA_HOST
                    else -> MediaHandlerKind.OTHER
                }
            } catch (_: SecurityException) { failed++; out.line("default=DENIED"); MediaHandlerKind.DENIED }
            catch (_: Exception) { failed++; out.line("default=FAILED"); MediaHandlerKind.FAILED }
            if (intent.action == MEDIA_ACTION) mediaHandler = handler else homeHandler = handler
            val rows = try { pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY) }
                catch (_: SecurityException) { failed++; out.line("query=DENIED"); continue }
                catch (_: Exception) { failed++; out.line("query=FAILED"); continue }
            val keys = rows.mapNotNull { try { it.activityInfo?.let { a -> a.packageName + "/" + a.name } ?: run { failed++; null } } catch (_: Exception) { failed++; null } }.distinct().sorted()
            truncated += (keys.size - 16).coerceAtLeast(0)
            out.line("raw=${rows.size} unique=${keys.size} processed=${minOf(keys.size, 16)}")
            keys.take(16).forEach { key ->
                processed++
                out.line("handler=${safe(key, 240)}")
                try {
                    val a = rows.first { it.activityInfo?.let { a -> a.packageName + "/" + a.name } == key }.activityInfo!!
                    out.line(" label=" + field { safe(pm.getApplicationLabel(a.applicationInfo).toString(), 100) })
                    out.line(" component_override=" + field { pm.getComponentEnabledSetting(ComponentName(a.packageName, a.name)).toString() })
                    if (hosts.size < 8) hosts += a.packageName else if (a.packageName !in hosts) truncated++
                } catch (_: Exception) { failed++; out.line("row=FAILED") }
            }

        }
        hosts.forEach { host ->
            out.line("Host resources ${safe(host, 240)}")
            for (namespace in listOf(host, "android")) {
                val resourceText = try {
                    val res = pm.getResourcesForApplication(host)
                    val id = res.getIdentifier("custom_media_packages", "array", namespace)
                    if (id == 0) { resources = resources.copy(absent = resources.absent + 1); "ABSENT (not evidence of an empty filter)" } else {
                        val array = res.obtainTypedArray(id)
                        try {
                            val count = minOf(array.length(), 64)
                            val values = (0 until count).map { index -> field { array.getString(index) ?: "NULL" } }
                            if (array.length() > count) { truncated++; resources = resources.copy(truncated = resources.truncated + 1); "TRUNCATED count=$count ${values.joinToString()}" }
                            else { resources = resources.copy(present = resources.present + 1); "PRESENT count=$count ${values.joinToString()}" }
                        } finally { array.recycle() }
                    }
                } catch (_: SecurityException) { failed++; resources = resources.copy(denied = resources.denied + 1); "DENIED" }
                catch (_: Exception) { failed++; resources = resources.copy(failed = resources.failed + 1); "FAILED" }
                out.line(" namespace=${safe(namespace, 240)} custom_media_packages=$resourceText")
            }
        }
        if (out.truncated) truncated++
        val coverage = MediaCoverage(if (failed > 0) MediaCoverageStatus.PARTIAL else if (truncated > 0) MediaCoverageStatus.TRUNCATED else MediaCoverageStatus.COMPLETE,
            processed, failed, truncated, capturedWall = System.currentTimeMillis(), elapsedMs = (SystemClock.elapsedRealtime() - started).coerceAtLeast(0), startWall = wall,
            mediaHandler = mediaHandler, homeHandler = homeHandler, resources = resources)
        out.line("status=${coverage.status} failed=$failed truncated=$truncated elapsed_ms=${coverage.elapsedMs}")
        return MediaIntegrationResult(out.finish(), coverage, own)
    }
    private class BoundedText {
        private val text = StringBuilder(); private var bytes = 0
        var truncated = false; private set
        fun line(value: String) {
            if (truncated) return
            val b = (value + "\n").toByteArray(Charsets.UTF_8)
            if (bytes + b.size > 128 * 1024 - 128) { truncated = true; return }
            text.append(value).append('\n'); bytes += b.size
        }
        fun finish() = text.toString() + if (truncated) "output=TRUNCATED bound=131072_utf8_bytes\n" else "output=COMPLETE\n"
    }
    companion object {
        const val MEDIA_ACTION = "android.car.intent.action.MEDIA_TEMPLATE"
        fun safe(value: String, codePoints: Int): String = buildString {
            value.codePoints().limit(codePoints.toLong()).forEach { cp ->
                if (Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT.toInt() || cp == 0x2028 || cp == 0x2029) append('?') else appendCodePoint(cp)
            }
        }
    }
}
