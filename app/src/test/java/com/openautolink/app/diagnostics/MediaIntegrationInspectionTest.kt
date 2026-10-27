package com.openautolink.app.diagnostics

import android.content.Context
import android.content.pm.*
import android.content.res.Resources
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [32, 34], application = android.app.Application::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class MediaIntegrationInspectionTest {
    @Test fun perEntryFailureContinuesAndOnlyLocalProjectionContainsNames() {
        val pm = mockk<PackageManager>()
        val context = mockk<Context>()
        every { context.packageManager } returns pm
        fun entry(name: String) = ResolveInfo().apply { serviceInfo = ServiceInfo().apply {
            packageName = "privacy.canary.$name"; this.name = "Service"; permission = "private.permission";
            enabled = true; exported = true; applicationInfo = ApplicationInfo().apply { enabled = true }
        } }
        every { pm.queryIntentServices(any(), any<Int>()) } returns listOf(entry("first"), entry("middle"), entry("last"))
        every { pm.getComponentEnabledSetting(any()) } throws SecurityException("private error")
        every { pm.getApplicationEnabledSetting(any()) } returns PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        every { pm.getApplicationLabel(any()) } returns "Label\n\u202ecanary"
        every { pm.queryIntentActivities(any(), any<Int>()) } returns emptyList()
        every { pm.resolveActivity(any(), any<Int>()) } returns null
        val result = MediaIntegrationInspection(context).collect()
        val local = result.renderLocalText()
        assertTrue(local.contains("privacy.canary.last"))
        assertTrue(local.contains("DENIED"))
        assertFalse(local.contains('\u202e'))
        assertTrue(local.toByteArray().size <= 128 * 1024)
        assertEquals(6, result.toUploadSummary().processed)
        assertFalse(result.toUploadSummary().toString().contains("privacy"))
        verify(exactly = 2) { pm.queryIntentServices(any(), any<Int>()) }
    }
    @Test fun finiteHandlerProjectionDistinguishesDeniedAndUnresolvedWithoutPackageNames() {
        val pm = mockk<PackageManager>(); val context = mockk<Context>()
        every { context.packageManager } returns pm
        every { pm.queryIntentServices(any(), any<Int>()) } returns emptyList()
        every { pm.queryIntentActivities(any(), any<Int>()) } returns emptyList()
        every { pm.resolveActivity(match { it.action == MediaIntegrationInspection.MEDIA_ACTION }, any<Int>()) } throws SecurityException("private.canary")
        every { pm.resolveActivity(match { it.action == android.content.Intent.ACTION_MAIN }, any<Int>()) } returns null
        val result = MediaIntegrationInspection(context).collect().toUploadSummary()
        assertEquals(MediaHandlerKind.DENIED, result.mediaHandler)
        assertEquals(MediaHandlerKind.UNRESOLVED, result.homeHandler)
        assertEquals(MediaCoverageStatus.PARTIAL, result.status)
    }
    @Test fun hostResourceCoverageIsFiniteAndContinuesAfterNamespaceDenial() {
        val pm = mockk<PackageManager>(); val context = mockk<Context>(); val resources = mockk<Resources>()
        every { context.packageManager } returns pm
        every { pm.queryIntentServices(any(), any<Int>()) } returns emptyList()
        val handler = ResolveInfo().apply { activityInfo = ActivityInfo().apply {
            packageName = "privacy.canary.host"; name = "Activity"; applicationInfo = ApplicationInfo()
        } }
        every { pm.queryIntentActivities(any(), any<Int>()) } returns listOf(handler)
        every { pm.resolveActivity(any(), any<Int>()) } returns null
        every { pm.getApplicationLabel(any()) } returns "privateLabel"
        every { pm.getComponentEnabledSetting(any()) } returns 0
        every { pm.getResourcesForApplication("privacy.canary.host") } returns resources
        every { resources.getIdentifier("custom_media_packages", "array", "privacy.canary.host") } throws SecurityException("private error")
        every { resources.getIdentifier("custom_media_packages", "array", "android") } returns 0
        val result = MediaIntegrationInspection(context).collect()
        assertEquals(1, result.toUploadSummary().resources.denied)
        assertEquals(1, result.toUploadSummary().resources.absent)
        assertTrue(result.renderLocalText().contains("ABSENT (not evidence of an empty filter)"))
        assertFalse(result.toUploadSummary().toString().contains("privacy.canary"))
    }
    @Test fun sanitizedTextIsBoundedByCodePointsAndUtf8() {
        val value = MediaIntegrationInspection.safe("😀".repeat(300) + "\n\u0000\u202e", 100)
        assertEquals(100, value.codePointCount(0, value.length))
        assertFalse(value.contains('\n'))
        assertFalse(value.contains('\u202e'))
    }
}
