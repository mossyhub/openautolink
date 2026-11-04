package com.openautolink.app.secondary

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CompletableFuture

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = SecondaryHostRegistryTest.HostApplication::class,
    shadows = [SecondaryHostRegistryTest.PermissionShadow::class, SecondaryHostRegistryTest.SurfaceBoundary::class,
        SecondaryHostRegistryTest.RootInsetsBoundary::class])
class SecondaryHostRegistryTest {
    class HostApplication : Application() {
        var lastOptions: android.os.Bundle? = null
        var lastIntent: android.content.Intent? = null
        override fun startActivity(intent: android.content.Intent, options: android.os.Bundle?) {
            lastIntent = intent; lastOptions = options
            super.startActivity(intent, options)
        }
    }
    /** Robolectric's stock holder always returns null; model only the OS-owned surface/insets. */
    @org.robolectric.annotation.Implements(android.view.SurfaceView::class)
    class SurfaceBoundary : org.robolectric.shadows.ShadowSurfaceView() {
        var borrowed: android.view.Surface? = null
        private val controlledHolder by lazy {
            object : android.view.SurfaceHolder by fakeSurfaceHolder {
                override fun getSurface(): android.view.Surface? = borrowed
            }
        }
        @org.robolectric.annotation.Implementation
        override fun getHolder(): android.view.SurfaceHolder = controlledHolder
    }
    @org.robolectric.annotation.Implements(className = "android.view.ViewRootImpl", isInAndroidSdk = false)
    class RootInsetsBoundary : org.robolectric.shadows.ShadowViewRootImpl() {
        @org.robolectric.annotation.Implementation
        override fun getWindowInsets(forceConstruct: Boolean): android.view.WindowInsets =
            supplied ?: super.getWindowInsets(forceConstruct)
        companion object { var supplied: android.view.WindowInsets? = null }
    }
    private object HostActivityShadow { var selected: android.view.Display? = null }
    private var previousActivityContexts: String? = null
    @org.junit.Before fun enableRealActivityContexts() {
        previousActivityContexts = System.getProperty("robolectric.createActivityContexts")
        System.setProperty("robolectric.createActivityContexts", "true")
    }
    @org.junit.After fun restoreActivityContexts() {
        previousActivityContexts?.let { System.setProperty("robolectric.createActivityContexts", it) }
            ?: System.clearProperty("robolectric.createActivityContexts")
    }
    private fun buildActivity(intent: android.content.Intent) =
        org.robolectric.android.controller.ActivityController.of(SecondaryProjectionActivity(), intent,
            android.app.ActivityOptions.makeBasic().setLaunchDisplayId(
                HostActivityShadow.selected?.displayId ?: android.view.Display.DEFAULT_DISPLAY).toBundle())
    @org.robolectric.annotation.Implements(android.app.ActivityManager::class)
    class PermissionShadow : org.robolectric.shadows.ShadowActivityManager() {
        @org.robolectric.annotation.Implementation
        fun isActivityStartAllowedOnDisplay(context: Context, id: Int, intent: android.content.Intent): Boolean {
            queried = intent; queriedId = id; return allowed
        }
        companion object {
            var allowed = true
            var queried: android.content.Intent? = null
            var queriedId = -1
        }
    }
    @org.junit.Before fun resetBoundaries() {
        RootInsetsBoundary.supplied = null
        HostActivityShadow.selected = null
        PermissionShadow.allowed = true
        PermissionShadow.queried = null
    }
    private fun live(): SecondaryDisplayDescriptor {
        val id = org.robolectric.shadows.ShadowDisplayManager.addDisplay("w800dp-h480dp-mdpi", "Public fixture")
        shadowOf(Looper.getMainLooper()).idle()
        val display = context.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(id)
        shadowOf(display).setFlags(android.view.Display.FLAG_PRESENTATION)
        shadowOf(display).setState(android.view.Display.STATE_ON)
        val facts = PublicDisplayFacts.read(context, id)!!
        assertTrue("Fixture must expose a real usable public display", facts.publicRoute)
        HostActivityShadow.selected = display
        return descriptor.copy(displayId = id, width = facts.width, height = facts.height, densityDpi = facts.densityDpi)
    }
    private fun launched(): android.content.Intent {
        shadowOf(Looper.getMainLooper()).idle()
        return requireNotNull(shadowOf(context as Application).nextStartedActivity) { "Expected the real queued Activity launch" }
    }
    private fun surface(): android.view.Surface = io.mockk.mockk { io.mockk.every { isValid } returns true }
    private val geometry = SecondaryHostGeometry(800, 480, com.openautolink.app.cluster.PixelRect(0, 0, 800, 480))

    @Test fun exactIntentAndActualActivityAreRequiredBeforeSurfaceAdmission() {
        val display = live()
        val consumer = Consumer()
        val registry = SecondaryHostRegistry(context, MutableStateFlow(SecondaryInventorySnapshot(1, listOf(display))), consumer)
        val handle = registry.create(context, display, 31)
        val intent = launched()
        var controller: org.robolectric.android.controller.ActivityController<SecondaryProjectionActivity>? = null
        try {
            assertEquals(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK, intent.flags)
            assertEquals(setOf(SecondaryHostRegistry.TOKEN_EXTRA), intent.extras!!.keySet())
            assertEquals(display.displayId, PermissionShadow.queriedId)
            assertSame(PermissionShadow.queried, (context as HostApplication).lastIntent)
            assertEquals(display.displayId, org.robolectric.util.ReflectionHelpers.callStaticMethod<android.app.ActivityOptions>(
                android.app.ActivityOptions::class.java, "fromBundle",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(android.os.Bundle::class.java, context.lastOptions)).launchDisplayId)
            assertEquals(intent.component, PermissionShadow.queried!!.component)
            assertEquals(intent.extras!!.getString(SecondaryHostRegistry.TOKEN_EXTRA), PermissionShadow.queried!!.extras!!.getString(SecondaryHostRegistry.TOKEN_EXTRA))
            assertFalse(handle.ready.isDone)
            controller = buildActivity(intent).create().start().resume()
            assertFalse(controller.get().isFinishing)
            val borrowed = surface()
            SecondaryHostRegistry.present(controller.get(), intent.getStringExtra(SecondaryHostRegistry.TOKEN_EXTRA), borrowed, geometry)
            assertSame(borrowed, handle.ready.get().surface)
            assertSame(handle.owner, handle.ready.get().owner)
            assertEquals(geometry, handle.ready.get().geometry)
            assertEquals(1, consumer.ready)
            assertTrue(handle.retire().get())
            assertEquals(1, consumer.withdrawn)
            io.mockk.verify(exactly = 0) { borrowed.release() }
        } finally { handle.retire(); registry.close(); controller?.pause()?.stop()?.destroy() }
    }
    @Test fun failedDetachBlocksSameIdentityAcrossRegistryInstancesUntilRealRetry() {
        val display = live()
        val consumer = Consumer().apply { detach = CompletableFuture.completedFuture(false) }
        val flow = MutableStateFlow(SecondaryInventorySnapshot(1, listOf(display)))
        val first = SecondaryHostRegistry(context, flow, consumer)
        val second = SecondaryHostRegistry(context, flow, Consumer())
        val handle = first.create(context, display, 1)
        val intent = launched()
        val controller = buildActivity(intent).create()
        var successor: HostHandle? = null
        try {
            SecondaryHostRegistry.present(controller.get(), intent.getStringExtra(SecondaryHostRegistry.TOKEN_EXTRA), surface(), geometry)
            assertTrue(handle.ready.isDone)
            assertFalse(handle.retire().get())
            assertFalse(handle.owner.isCurrent)
            successor = second.create(context, display, 2)
            assertTrue(successor.ready.isCompletedExceptionally)
            assertNull(shadowOf(context as Application).nextStartedActivity)
            consumer.detach = CompletableFuture.completedFuture(true)
            assertTrue(handle.retire().get())
            // Only the exact failed owner can clear debt; the rejected successor cannot.
            assertTrue(successor.retire().get())
            val retry = second.create(context, display, 3)
            val retryIntent = launched()
            val retryController = buildActivity(retryIntent).create()
            try {
                SecondaryHostRegistry.present(retryController.get(), retryIntent.getStringExtra(SecondaryHostRegistry.TOKEN_EXTRA), surface(), geometry)
                assertFalse(retry.ready.isCompletedExceptionally)
                assertTrue(retry.ready.isDone)
                assertTrue(retry.retire().get())
            } finally { retry.retire(); retryController.destroy() }
        } finally {
            consumer.detach = CompletableFuture.completedFuture(true)
            handle.retire(); successor?.retire(); first.close(); second.close(); controller.destroy()
        }
    }
    @Test fun wrongActualDisplayAndStaleEpochNeverReachConsumer() {
        val display = live()
        val consumer = Consumer()
        val flow = MutableStateFlow(SecondaryInventorySnapshot(1, listOf(display)))
        val registry = SecondaryHostRegistry(context, flow, consumer)
        val stale = registry.create(context, display.copy(attachmentEpoch = 0), 1)
        assertTrue(stale.ready.isCompletedExceptionally)
        assertNull(shadowOf(context as Application).nextStartedActivity)
        val handle = registry.create(context, display, 1)
        val intent = launched()
        HostActivityShadow.selected = null
        val controller = buildActivity(intent).create()
        try {
            assertTrue(controller.get().isFinishing)
            assertTrue(handle.ready.isCompletedExceptionally)
            assertEquals(0, consumer.ready)
        } finally { handle.retire(); registry.close(); controller.destroy() }
    }
    @Test fun pendingAttachRetirementWaitsForRendererAndGeometryMutationRevokes() {
        val display = live()
        val consumer = Consumer().apply { attach = CompletableFuture() ; detach = CompletableFuture() }
        val registry = SecondaryHostRegistry(context, MutableStateFlow(SecondaryInventorySnapshot(1, listOf(display))), consumer)
        val handle = registry.create(context, display, 1)
        val intent = launched()
        val controller = buildActivity(intent).create()
        try {
            val borrowed = surface()
            SecondaryHostRegistry.present(controller.get(), intent.getStringExtra(SecondaryHostRegistry.TOKEN_EXTRA), borrowed, geometry)
            SecondaryHostRegistry.present(controller.get(), intent.getStringExtra(SecondaryHostRegistry.TOKEN_EXTRA), borrowed,
                geometry.copy(safe = com.openautolink.app.cluster.PixelRect(0, 10, 800, 480)))
            val retired = handle.retire()
            assertFalse(retired.isDone)
            assertFalse(handle.owner.isCurrent)
            assertEquals(0, consumer.withdrawn)
            consumer.attach.complete(true)
            assertEquals(1, consumer.withdrawn)
            assertFalse(retired.isDone)
            consumer.detach.complete(true)
            assertTrue(retired.get())
            assertTrue(handle.ready.isCompletedExceptionally)
        } finally {
            consumer.attach.complete(true); consumer.detach.complete(true)
            handle.retire(); registry.close(); controller.destroy()
        }
    }
    @Test fun permissionDenialAndLiveRevocationNeverAdmitSurface() {
        val display = live()
        val consumer = Consumer()
        val registry = SecondaryHostRegistry(context, MutableStateFlow(SecondaryInventorySnapshot(1, listOf(display))), consumer)
        try {
            PermissionShadow.allowed = false
            val denied = registry.create(context, display, 1)
            assertTrue(denied.ready.isCompletedExceptionally)
            assertNull(shadowOf(context as Application).nextStartedActivity)
            PermissionShadow.allowed = true
            val handle = registry.create(context, display, 2)
            val intent = launched()
            val controller = buildActivity(intent).create()
            try {
                PermissionShadow.allowed = false
                SecondaryHostRegistry.present(controller.get(), intent.getStringExtra(SecondaryHostRegistry.TOKEN_EXTRA), surface(), geometry)
                assertTrue(handle.ready.isCompletedExceptionally)
                assertEquals(0, consumer.ready)
            } finally { handle.retire(); controller.destroy() }
        } finally { registry.close() }
    }
    @Test fun stopAHasNoAuthorityOverBAndReplayedTokenCannotStealB() {
        val a = live()
        val b = live().copy(id = SecondaryOutputId("other"))
        val consumer = Consumer()
        val registry = SecondaryHostRegistry(context, MutableStateFlow(SecondaryInventorySnapshot(1, listOf(a, b))), consumer)
        HostActivityShadow.selected = context.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(a.displayId)
        val first = registry.create(context, a, 1)
        val aIntent = launched()
        val ac = buildActivity(aIntent).create().start().resume()
        SecondaryHostRegistry.present(ac.get(), aIntent.getStringExtra(SecondaryHostRegistry.TOKEN_EXTRA), surface(), geometry)
        HostActivityShadow.selected = context.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(b.displayId)
        val second = registry.create(context, b, 1)
        val bIntent = launched()
        val bc = buildActivity(bIntent).create().start().resume()
        try {
            SecondaryHostRegistry.present(bc.get(), bIntent.getStringExtra(SecondaryHostRegistry.TOKEN_EXTRA), surface(), geometry)
            assertFalse(second.ready.isCompletedExceptionally)
            assertTrue(second.ready.isDone)
            val replay = buildActivity(bIntent).create()
            assertTrue(replay.get().isFinishing)
            replay.destroy()
            ac.pause().stop()
            assertFalse(first.owner.isCurrent)
            assertTrue(second.owner.isCurrent)
            assertEquals(1, consumer.withdrawn)
            // Old A's OS destruction cannot withdraw B.
            ac.destroy()
            assertTrue(second.owner.isCurrent)
        } finally { first.retire(); second.retire(); registry.close(); bc.pause().stop().destroy() }
    }
    @Test fun duplicateDisplayLocatorAndChangedAttachmentAreDenied() {
        val display = live()
        val alias = display.copy(id = SecondaryOutputId("alias"))
        val consumer = Consumer()
        val flow = MutableStateFlow(SecondaryInventorySnapshot(1, listOf(display, alias)))
        val registry = SecondaryHostRegistry(context, flow, consumer)
        try {
            val duplicate = registry.create(context, display, 1)
            assertTrue(duplicate.ready.isCompletedExceptionally)
            assertNull(shadowOf(context as Application).nextStartedActivity)
            flow.value = SecondaryInventorySnapshot(2, listOf(display))
            val handle = registry.create(context, display, 2)
            val intent = launched()
            flow.value = SecondaryInventorySnapshot(3, listOf(display.copy(attachmentEpoch = 2)))
            shadowOf(Looper.getMainLooper()).idle()
            val late = buildActivity(intent).create()
            assertTrue(late.get().isFinishing)
            assertTrue(handle.ready.isCompletedExceptionally)
            assertEquals(0, consumer.ready)
            late.destroy(); handle.retire()
        } finally { registry.close() }
    }
    @Test fun realActivityFreezesMeasuredCanvasAndSafeInsets() {
        val display = live()
        val consumer = Consumer()
        val registry = SecondaryHostRegistry(context, MutableStateFlow(SecondaryInventorySnapshot(1, listOf(display))), consumer)
        val handle = registry.create(context, display, 1)
        val intent = launched()
        val texture = android.graphics.SurfaceTexture(0)
        val borrowed = android.view.Surface(texture)
        val controller = buildActivity(intent).create().start().resume()
        try {
            // Visibility/layout/insets may precede the OS creating any Surface.
            controller.visible()
            val activity = controller.get()
            val root = shadowOf(activity).contentView as android.widget.FrameLayout
            val view = root.getChildAt(0) as android.view.SurfaceView
            val specW = android.view.View.MeasureSpec.makeMeasureSpec(800, android.view.View.MeasureSpec.EXACTLY)
            val specH = android.view.View.MeasureSpec.makeMeasureSpec(480, android.view.View.MeasureSpec.EXACTLY)
            activity.window.decorView.measure(specW, specH)
            activity.window.decorView.layout(0, 0, 800, 480)
            root.measure(specW, specH); root.layout(0, 0, 800, 480)
            val insets = android.view.WindowInsets.Builder().setInsets(android.view.WindowInsets.Type.systemBars(),
                android.graphics.Insets.of(0, 20, 0, 10)).build()
            val boundary = org.robolectric.shadow.api.Shadow.extract<SurfaceBoundary>(view)
            RootInsetsBoundary.supplied = insets
            assertEquals(insets, view.rootWindowInsets)
            view.dispatchApplyWindowInsets(insets)
            val callbacks = boundary.fakeSurfaceHolder.callbacks.toList()
            assertEquals(1, callbacks.size)
            callbacks.forEach { it.surfaceChanged(view.holder, android.graphics.PixelFormat.OPAQUE, 800, 480) }
            assertNull(view.holder.surface)
            assertFalse("null Surface cannot establish readiness", handle.ready.isDone)
            assertEquals(0, consumer.ready)
            assertTrue(handle.owner.isCurrent)
            boundary.borrowed = borrowed
            assertTrue(borrowed.isValid)
            callbacks.forEach { it.surfaceCreated(view.holder) }
            callbacks.forEach { it.surfaceChanged(view.holder, android.graphics.PixelFormat.OPAQUE, 800, 480) }
            assertTrue("actual SurfaceView measured receipt", handle.ready.isDone)
            assertFalse(handle.ready.isCompletedExceptionally)
            assertSame(view.holder.surface, handle.ready.get().surface)
            assertEquals(800, handle.ready.get().geometry.width)
            assertEquals(480, handle.ready.get().geometry.height)
            assertEquals(com.openautolink.app.cluster.PixelRect(0, 20, 800, 470), handle.ready.get().geometry.safe)
            assertFalse(view.isFocusable)
            assertFalse(view.isClickable)
            assertTrue(activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
            assertTrue(activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        } finally {
            assertTrue(handle.retire().get())
            registry.close(); controller.pause().stop().destroy()
            borrowed.release(); texture.release()
        }
    }
    /** Public callbacks must retain their exact first rejection in the real file sink. */
    @Test fun publicDisplayChangedBeforeSurfacePersistsExactRevokeBranch() = withPersistedDiagnostics { text ->
        val display = live()
        val consumer = Consumer()
        val registry = SecondaryHostRegistry(context, MutableStateFlow(SecondaryInventorySnapshot(1, listOf(display))), consumer)
        val handle = registry.create(context, display, 558)
        val intent = launched()
        val controller = buildActivity(intent).create().start().resume()
        try {
            assertTrue(handle.owner.isCurrent)
            assertFalse(handle.ready.isDone)
            val before = PublicDisplayFacts.read(context, display.displayId)
            org.robolectric.shadows.ShadowDisplayManager.changeDisplay(display.displayId, "w800dp-h480dp-mdpi")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(before, PublicDisplayFacts.read(context, display.displayId))
            assertFalse(handle.owner.isCurrent)
            assertTrue(handle.ready.isCompletedExceptionally)
            assertEquals(1, consumer.revoked)
            assertEquals(0, consumer.ready)
            assertTrue(handle.retire().get())
            val saved = text()
            assertTrue(saved, saved.contains("stage=host_revoke reason=display_changed"))
            assertTrue(saved, saved.contains("session=558 physical=${display.displayId} attachment=1"))
            assertTrue(saved, saved.contains("hadSurface=false attaching=false"))
            assertTrue(saved, saved.contains("live=800x480@160 valid=true on=true private=false presentation=true builtin=false"))
            assertEquals(1, Regex("stage=host_revoke").findAll(saved).count())
        } finally { handle.retire(); registry.close(); controller.pause().stop().destroy() }
    }

    @Test fun realSurfaceCallbacksPersistInitialGeometryThenChangedSafeAreaRevoke() = withPersistedDiagnostics { text ->
        val display = live()
        val consumer = Consumer()
        val registry = SecondaryHostRegistry(context, MutableStateFlow(SecondaryInventorySnapshot(1, listOf(display))), consumer)
        val handle = registry.create(context, display, 559)
        val intent = launched()
        val texture = android.graphics.SurfaceTexture(0)
        val borrowed = android.view.Surface(texture)
        val controller = buildActivity(intent).create().start().resume()
        try {
            controller.visible()
            val activity = controller.get()
            val root = shadowOf(activity).contentView as android.widget.FrameLayout
            val view = root.getChildAt(0) as android.view.SurfaceView
            val w = android.view.View.MeasureSpec.makeMeasureSpec(800, android.view.View.MeasureSpec.EXACTLY)
            val h = android.view.View.MeasureSpec.makeMeasureSpec(480, android.view.View.MeasureSpec.EXACTLY)
            activity.window.decorView.measure(w, h); activity.window.decorView.layout(0, 0, 800, 480)
            root.measure(w, h); root.layout(0, 0, 800, 480)
            fun insets(top: Int) = android.view.WindowInsets.Builder().setInsets(
                android.view.WindowInsets.Type.systemBars(), android.graphics.Insets.of(0, top, 0, 0)).build()
            RootInsetsBoundary.supplied = insets(0)
            val boundary = org.robolectric.shadow.api.Shadow.extract<SurfaceBoundary>(view)
            val callbacks = boundary.fakeSurfaceHolder.callbacks.toList()
            callbacks.forEach { it.surfaceChanged(view.holder, android.graphics.PixelFormat.OPAQUE, 800, 480) }
            assertFalse(handle.ready.isDone)
            boundary.borrowed = borrowed
            callbacks.forEach { it.surfaceCreated(view.holder) }
            callbacks.forEach { it.surfaceChanged(view.holder, android.graphics.PixelFormat.OPAQUE, 800, 480) }
            assertTrue(handle.owner.isCurrent)
            assertSame(borrowed, handle.ready.get().surface)
            RootInsetsBoundary.supplied = insets(20)
            assertEquals(20, view.rootWindowInsets!!.getInsets(android.view.WindowInsets.Type.systemBars()).top)
            view.dispatchApplyWindowInsets(RootInsetsBoundary.supplied!!)
            assertFalse(handle.owner.isCurrent)
            assertEquals(1, consumer.ready)
            assertEquals(1, consumer.withdrawn)
            val saved = text()
            assertTrue(saved, saved.contains("stage=host_surface geometry=800x480 safe=0,0,800,480"))
            assertTrue(saved, saved.contains("stage=host_geometry_change previous=800x480 safe=0,0,800,480 next=800x480 safe=0,20,800,480 surfaceChanged=false"))
            assertTrue(saved, saved.contains("stage=host_revoke reason=geometry_changed"))
        } finally {
            handle.retire(); registry.close(); controller.pause().stop().destroy()
            borrowed.release(); texture.release()
        }
    }

    @Test fun publicRevocationInvalidatesOwnerBeforeSynchronousFileIo() {
        val display = live()
        val consumer = Consumer()
        val registry = SecondaryHostRegistry(context, MutableStateFlow(SecondaryInventorySnapshot(1, listOf(display))), consumer)
        val handle = registry.create(context, display, 560)
        val intent = launched()
        val controller = buildActivity(intent).create().start().resume()
        val previous = com.openautolink.app.diagnostics.DiagnosticLog.fileLogWriter
        val writer = io.mockk.mockk<com.openautolink.app.diagnostics.FileLogWriter>()
        var authorityAtIo: Boolean? = null
        io.mockk.every { writer.write(any()) } answers {
            if (firstArg<com.openautolink.app.diagnostics.LocalLogEntry>().message.contains("stage=host_revoke"))
                authorityAtIo = handle.owner.isCurrent
            Unit
        }
        try {
            com.openautolink.app.diagnostics.DiagnosticLog.fileLogWriter = writer
            org.robolectric.shadows.ShadowDisplayManager.changeDisplay(display.displayId, "w800dp-h480dp-mdpi")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("File IO must never postpone packet-authority revocation", false, authorityAtIo)
            assertFalse(handle.owner.isCurrent)
            assertEquals(1, consumer.revoked)
            assertTrue(handle.retire().get())
        } finally {
            com.openautolink.app.diagnostics.DiagnosticLog.fileLogWriter = previous
            handle.retire(); registry.close(); controller.pause().stop().destroy()
        }
    }

    private fun withPersistedDiagnostics(block: (() -> String) -> Unit) {
        val writer = com.openautolink.app.diagnostics.FileLogWriter(context)
        val directory = java.io.File(context.getExternalFilesDir(null), "openautolink/logs")
        org.robolectric.shadows.ShadowStatFs.registerStats(directory, 100000, 100000, 100000)
        val previous = com.openautolink.app.diagnostics.DiagnosticLog.fileLogWriter
        val verbose = com.openautolink.app.diagnostics.DiagnosticLog.isVerbose()
        try {
            com.openautolink.app.diagnostics.DiagnosticLog.setVerbose(false)
            val path = requireNotNull(writer.start())
            com.openautolink.app.diagnostics.DiagnosticLog.fileLogWriter = writer
            block { writer.stop(); java.io.File(path).readText() }
        } finally {
            com.openautolink.app.diagnostics.DiagnosticLog.fileLogWriter = previous
            writer.stop()
            com.openautolink.app.diagnostics.DiagnosticLog.setVerbose(verbose)
            org.robolectric.shadows.ShadowStatFs.unregisterStats(directory)
        }
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val descriptor = SecondaryDisplayDescriptor(SecondaryOutputId("test"), 7, 1, "Unknown",
        800, 480, 160, SecondaryRoute.PUBLIC_ACTIVITY, SecondaryReason.NONE, "Unknown")
    class Consumer : HostConsumer {
        var attach = CompletableFuture.completedFuture(true)
        var detach = CompletableFuture.completedFuture(true)
        var ready = 0
        var revoked = 0
        var withdrawn = 0
        override fun onReady(receipt: SecondaryHostReceipt): CompletableFuture<Boolean> {
            ready++; return attach
        }
        override fun onRevoke(owner: SecondaryHostOwner) { revoked++ }
        override fun onWithdraw(receipt: SecondaryHostReceipt): CompletableFuture<Boolean> {
            withdrawn++; return detach
        }
    }
    @Test fun missingLiveDisplayFailsClosedWithoutLaunching() {
        val consumer = Consumer()
        val flow = MutableStateFlow(SecondaryInventorySnapshot(1, listOf(descriptor)))
        val registry = SecondaryHostRegistry(context, flow, consumer)
        try {
            val handle = registry.create(context, descriptor, 10)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(handle.ready.isCompletedExceptionally)
            assertTrue(handle.retire().get())
            assertEquals(0, consumer.ready)
            assertEquals(0, consumer.withdrawn)
            assertNull(shadowOf(context as Application).nextStartedActivity)
        } finally { registry.close() }
    }
    @Test fun inventedTokenCannotAdmitActivity() {
        val intent = android.content.Intent(context, SecondaryProjectionActivity::class.java)
            .putExtra(SecondaryHostRegistry.TOKEN_EXTRA, "invented")
        val controller = buildActivity(intent).create()
        assertTrue(controller.get().isFinishing)
        controller.destroy()
    }
}
