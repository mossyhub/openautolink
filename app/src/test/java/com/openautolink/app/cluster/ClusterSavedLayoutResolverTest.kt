package com.openautolink.app.cluster

import com.openautolink.app.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ClusterSavedLayoutResolverTest {
    private val profile = ClusterDisplayProfiles.AEGEAN_ORANGE_239
    private val host = ClusterHostGeometry(7, profile.panel, profile.panel.bounds, profile.panel.bounds)
    private val bindings = listOf(ClusterDisplayBinding(profile.role, 7))
    private fun automatic(host: ClusterHostGeometry = this.host) = requireNotNull(
        ClusterDisplayProfiles.qualify(profile.vehicle, bindings, host))
    private val scope = ClusterLayoutScope(profile.vehicle, profile.id, profile.role, profile.panel)
    private fun saved() = SavedClusterLayout(scope, host.visible, PixelRect(200, 80, 1800, 720), PixelRect(650, 200, 1300, 600))

    @Test fun suspendReadUsesFullScopeAndActualCanvasAwayFromCallerThread() = runBlocking {
        val auto = automatic()
        val caller = Thread.currentThread()
        var calls = 0
        val result = ClusterSavedLayoutResolver.resolve(auto) { actualScope, canvas ->
            calls++
            assertNotSame(caller, Thread.currentThread())
            assertEquals(scope, actualScope)
            assertEquals(host.visible, canvas)
            ClusterLayoutStorage.decode(saved().encode(), actualScope, canvas)
        }
        assertEquals(1, calls)
        assertEquals(saved().safe, result.display.safe)
    }

    @Test fun readFailureFallsBackButCancellationPropagates() = runBlocking {
        val auto = automatic()
        val result = ClusterSavedLayoutResolver.resolve(auto) { _, _ -> throw java.io.IOException("unavailable") }
        assertSame(auto, result.display)
        assertEquals(ClusterLayoutFallbackReason.READ_FAILED, result.fallbackReason)
        val cancellation = kotlinx.coroutines.CancellationException("cancelled")
        try {
            ClusterSavedLayoutResolver.resolve(auto) { _, _ -> throw cancellation }
            fail("Cancellation must not be swallowed")
        } catch (actual: kotlinx.coroutines.CancellationException) {
            // Coroutine stack-trace recovery may copy the exception across Dispatchers.IO.
            assertEquals(cancellation.message, actual.message)
            assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === cancellation })
        }
    }

    @Test fun cancellingOwningJobNeverPublishesFallback() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val returned = java.util.concurrent.atomic.AtomicBoolean(false)
        val task = launch {
            ClusterSavedLayoutResolver.resolve(automatic()) { _, _ ->
                entered.complete(Unit)
                awaitCancellation()
            }
            returned.set(true)
        }
        try {
            withTimeout(5000) { entered.await() }
            task.cancel()
            withTimeout(5000) { task.join() }
            assertTrue(task.isCancelled)
            assertFalse(returned.get())
        } finally {
            task.cancel()
            task.join()
        }
    }

    @Test fun explicitOverrideRequalifiesOnlyTheExactObservedHostAndBinding() {
        val auto = automatic()
        val custom = ClusterSavedLayoutResolver.resolveReceipt(auto, ClusterLayoutRead(saved(), null)).display
        val receipt = requireNotNull(custom.validatedLayout)
        val rebound = requireNotNull(ClusterDisplayProfiles.qualify(profile.vehicle, bindings, host, receipt))
        assertEquals(custom.effectiveOutput, rebound.effectiveOutput)
        assertEquals(custom.safe, rebound.safe)
        assertSame(receipt, rebound.validatedLayout)
        assertEquals(profile.output, requireNotNull(ClusterDisplayProfiles.qualify(profile.vehicle, bindings, host)).effectiveOutput)
        assertNull(ClusterDisplayProfiles.qualify(profile.vehicle, bindings, host.copy(visible = PixelRect(0, 135, 2000, 810)), receipt))
        assertNull(ClusterDisplayProfiles.qualify(profile.vehicle, bindings, host.copy(stable = PixelRect(0, 100, 2000, 810)), receipt))
        assertNull(ClusterDisplayProfiles.qualify(profile.vehicle, listOf(ClusterDisplayBinding(profile.role, 8)), host.copy(displayId = 8), receipt))
        // Firmware is provenance for the sourced default, not admission authority. The
        // exact observed host/binding and validated calibration must still match above/below.
        assertSame(receipt, requireNotNull(ClusterDisplayProfiles.qualify(
            profile.vehicle.copy(buildRevision = 240), bindings, host, receipt)).validatedLayout)
        assertNull(ClusterDisplayProfiles.qualify(profile.vehicle, bindings + ClusterDisplayBinding(ClusterVehicleRole.CENTER, 7), host, receipt))
        assertNull(ClusterDisplayProfiles.qualify(profile.vehicle, listOf(ClusterDisplayBinding(ClusterVehicleRole.CENTER, 7)), host, receipt))
    }

    @Test fun resetReceiptRestoresExactAutomaticAndPreservesStorageReasons() {
        val auto = automatic()
        for (reason in ClusterLayoutUnavailableReason.entries) {
            val result = ClusterSavedLayoutResolver.resolveReceipt(auto, ClusterLayoutRead(null, reason))
            assertSame(auto, result.display)
            assertEquals(ClusterLayoutFallbackReason.valueOf(reason.name), result.fallbackReason)
            assertEquals(ClusterVideoGeometry.androidAuto(auto).phoneSafeInsets, ClusterVideoGeometry.androidAuto(result.display).phoneSafeInsets)
        }
        assertEquals(ClusterLayoutFallbackReason.INVALID_RECORD,
            ClusterSavedLayoutResolver.resolveReceipt(auto, ClusterLayoutRead(null, null)).fallbackReason)
    }

    @Test fun everyDurableScopeFieldMustMatchIncludingBuildAndProfileId() {
        val staleScopes = listOf(
            scope.copy(vehicle = scope.vehicle.copy(product = "other")),
            scope.copy(vehicle = scope.vehicle.copy(sdk = 33)),
            scope.copy(vehicle = scope.vehicle.copy(buildFamily = "other")),
            scope.copy(vehicle = scope.vehicle.copy(buildRevision = 240)),
            scope.copy(profileId = "other"),
            scope.copy(panel = PixelSize(2100, 810)),
        )
        val auto = automatic()
        for (stale in staleScopes) {
            val result = ClusterSavedLayoutResolver.resolveReceipt(auto, ClusterLayoutRead(saved().copy(scope = stale), null))
            assertSame(auto, result.display)
            assertEquals(ClusterLayoutFallbackReason.SCOPE_MISMATCH, result.fallbackReason)
        }
    }

    @Test fun clippedCanvasDoesNotSilentlyClipCustomOutputOrEssentialSafe() {
        val clippedHost = host.copy(visible = PixelRect(0, 135, 2000, 810))
        val auto = automatic(clippedHost)
        val saved = saved()
        val result = ClusterSavedLayoutResolver.resolveReceipt(auto, ClusterLayoutRead(saved, null))
        assertSame(auto, result.display)
        assertEquals(ClusterLayoutFallbackReason.CANVAS_CHANGED, result.fallbackReason)
        assertEquals(PixelRect(98, 135, 1904, 745), result.display.effectiveOutput)
    }

    @Test fun essentialSafeInsideOutputButOutsideProtocolFitFallsBackWithReason() {
        val auto = automatic()
        val unusable = saved().copy(output = PixelRect(200, 80, 1800, 720), safe = PixelRect(200, 80, 1800, 720))
        val result = ClusterSavedLayoutResolver.resolveReceipt(auto, ClusterLayoutRead(unusable, null))
        assertSame(auto, result.display)
        assertEquals(ClusterLayoutFallbackReason.GEOMETRY_REJECTED, result.fallbackReason)
    }

    @Test fun inconsistentReceiptNeverActivatesCustom() {
        val auto = automatic()
        val result = ClusterSavedLayoutResolver.resolveReceipt(auto,
            ClusterLayoutRead(saved(), ClusterLayoutUnavailableReason.INVALID_RECORD))
        assertSame(auto, result.display)
        assertEquals(ClusterLayoutFallbackReason.INVALID_RECORD, result.fallbackReason)
    }

    @Test fun clippedHostAcceptsFreshMatchingCanvasWithoutChangingItsBounds() {
        val clippedHost = host.copy(visible = PixelRect(0, 135, 2000, 810), stable = PixelRect(0, 120, 2000, 810))
        val auto = automatic(clippedHost)
        val custom = saved().copy(canvas = requireNotNull(clippedHost.visible.intersect(clippedHost.stable)),
            output = PixelRect(200, 150, 1800, 750))
        val result = ClusterSavedLayoutResolver.resolveReceipt(auto, ClusterLayoutRead(custom, null))
        assertNull(result.fallbackReason)
        assertSame(clippedHost, result.display.host)
        assertEquals(custom.output, result.display.effectiveOutput)
        assertEquals(custom.safe, result.display.safe)
        assertNotEquals(ClusterVideoGeometry.androidAuto(auto).phoneSafeInsets, ClusterVideoGeometry.androidAuto(result.display).phoneSafeInsets)
    }

    @Test fun invalidPersistedSafeOrHostBoundsFailClosedThroughRealDecoder() {
        val auto = automatic()
        for (invalid in listOf(
            saved().encode().replace("[650,200,1300,600]", "[0,0,1300,600]"),
            saved().encode().replace("[200,80,1800,720]", "[200,80,2100,720]"),
            saved().encode().replace("[650,200,1300,600]", "[650,200,650,600]"),
            saved().encode().replace("[650,200,1300,600]", "[650,200,32769,600]"),
        )) {
            val receipt = ClusterLayoutStorage.decode(invalid, scope, host.visible)
            assertNull(receipt.custom)
            val result = ClusterSavedLayoutResolver.resolveReceipt(auto, receipt)
            assertSame(auto, result.display)
            assertEquals(ClusterLayoutFallbackReason.INVALID_RECORD, result.fallbackReason)
        }
        val reset = ClusterSavedLayoutResolver.resolveReceipt(auto, ClusterLayoutStorage.decode(null, scope, host.visible))
        assertSame(auto, reset.display)
        assertEquals(ClusterLayoutFallbackReason.NO_CUSTOM_LAYOUT, reset.fallbackReason)
    }

    @Test fun immutableOverrideRetainsNoMutableAndroidRectOrEditorAlias() {
        val intent = saved()
        val result = ClusterSavedLayoutResolver.resolveReceipt(automatic(), ClusterLayoutRead(intent, null))
        val receipt = requireNotNull(result.display.validatedLayout)
        val edited = intent.copy(safe = PixelRect(700, 250, 1200, 550))
        assertNotEquals(edited.safe, receipt.safe)
        assertEquals(PixelRect(650, 200, 1300, 600), result.display.safe)
        assertEquals(PixelRect(622, 165, 1374, 669), result.display.profile.safe)
        for (type in listOf(PixelRect::class.java, ClusterValidatedLayout::class.java, ClusterHostGeometry::class.java)) {
            for (field in type.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }) {
                assertTrue(java.lang.reflect.Modifier.isFinal(field.modifiers))
                assertNotEquals("android.graphics.Rect", field.type.name)
            }
        }
    }

    @Test fun customLayoutChangesBothProtocolMappingsWithoutChangingSourceOrHost() {
        val auto = automatic()
        val custom = saved()
        val result = ClusterSavedLayoutResolver.resolveReceipt(auto, ClusterLayoutRead(custom, null))
        assertEquals(custom.output, result.display.effectiveOutput)
        assertEquals(custom.safe, result.display.safe)
        assertSame(auto.profile, result.display.profile)
        assertSame(auto.host, result.display.host)
        assertEquals(7, result.display.displayId)
        assertNull(result.fallbackReason)
        for (mapping in listOf(ClusterVideoGeometry.androidAuto(result.display), ClusterVideoGeometry.carPlay(result.display))) {
            assertTrue(custom.safe.contains(mapping.drawableToHost.forward(mapping.safeInDrawable).inward()))
            assertNotEquals(PixelInsets.ZERO, mapping.phoneSafeInsets)
        }
        assertEquals(PixelInsets(360, 96, 400, 96), ClusterVideoGeometry.androidAuto(result.display).phoneSafeInsets)
        assertEquals(PixelInsets(360, 80, 400, 80), ClusterVideoGeometry.carPlay(result.display).phoneSafeInsets)
        assertNotEquals(ClusterVideoGeometry.androidAuto(auto).phoneSafeInsets, ClusterVideoGeometry.androidAuto(result.display).phoneSafeInsets)
        assertNotEquals(ClusterVideoGeometry.carPlay(auto).phoneSafeInsets, ClusterVideoGeometry.carPlay(result.display).phoneSafeInsets)
    }
}
