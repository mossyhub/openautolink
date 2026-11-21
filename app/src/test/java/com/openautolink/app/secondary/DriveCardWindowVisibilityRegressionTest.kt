package com.openautolink.app.secondary

import android.app.Application
import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.hardware.display.DisplayManager
import android.os.Looper
import android.view.Display
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import com.openautolink.app.cluster.ClusterDirectVideoActivity
import com.openautolink.app.cluster.PixelRect
import com.openautolink.app.drivecard.DriveCardLayout
import com.openautolink.app.drivecard.DriveCardPreview
import com.openautolink.app.drivecard.DriveCardView
import com.openautolink.app.input.ProcessVehicleDataRuntime
import com.openautolink.app.transport.ControlMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** SOURCE-ONLY frozen packet. No new production API or replacement framework dispatch.
 * Fixture derives from unchanged DriveCardHostLifecycleTest.exercise, lines 39-80,122-158.
 * Intended behavior assertions, NOT assertThrows(NPE): baseline RED must be run by parent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class,
    shadows = [SecondaryHostRegistryTest.PermissionShadow::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class DriveCardWindowVisibilityRegressionTest {
    private val context get() = RuntimeEnvironment.getApplication() as Application
    private fun idle() = shadowOf(Looper.getMainLooper()).idle() // due-now ONLY, never idleFor
    private fun <T> field(target: Any, name: String): T = ReflectionHelpers.getField(target, name)
    private fun await(label: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5) // original fixture budget
        while (!condition() && System.nanoTime() < deadline) { idle(); Thread.sleep(5) }
        assertTrue(label, condition())
    }
    private fun <T> completed(label: String, future: CompletableFuture<T>): T {
        await(label) { future.isDone }
        return future.get(5, TimeUnit.SECONDS)
    }
    private fun surface(): Surface = mockk { every { isValid } returns true }

    private inner class Fixture(val enabled: Boolean, val holdAttach: Boolean, val presentInitially: Boolean) {
        val previousContexts = System.getProperty("robolectric.createActivityContexts")
        val previousPermission = SecondaryHostRegistryTest.PermissionShadow.allowed
        val vehicles = MutableStateFlow(ControlMessage.VehicleData())
        val authority = RestoreAuthority()
        val attachAck = CompletableFuture<Boolean>()
        val suspendAck = CompletableFuture<Boolean>()
        var withdrawAck = CompletableFuture<Boolean>()
        var cleaning = false
        val readyReceipts = mutableListOf<SecondaryHostReceipt>()
        val suspendReceipts = mutableListOf<SecondaryHostReceipt>()
        val withdrawReceipts = mutableListOf<SecondaryHostReceipt>()
        val revoked = mutableListOf<SecondaryHostOwner>()
        val handles = mutableListOf<HostHandle>()
        val controllers = mutableListOf<ActivityController<ClusterDirectVideoActivity>>()
        val destroyed = mutableSetOf<ActivityController<ClusterDirectVideoActivity>>()
        var mocked = false
        var displayId: Int? = null
        lateinit var descriptor: SecondaryDisplayDescriptor
        lateinit var geometry: SecondaryHostGeometry
        var registry: SecondaryHostRegistry? = null
        lateinit var handle: HostHandle
        lateinit var intent: Intent
        lateinit var token: String
        lateinit var controller: ActivityController<ClusterDirectVideoActivity>
        val activity get() = controller.get()
        val root get() = activity.findViewById<ViewGroup>(android.R.id.content)
        val entry: Any get() = field<Set<Any>>(requireNotNull(registry), "entries").single {
            field<SecondaryHostOwner>(it, "owner") === handle.owner
        }
        val card: DriveCardView get() = requireNotNull(root.findViewWithTag(DriveCardView.TAG))
        lateinit var texture: TextureView

        fun setup() {
            assertSame(Application::class.java, context.javaClass)
            assertSame(Looper.getMainLooper(), Looper.myLooper())
            System.setProperty("robolectric.createActivityContexts", "true")
            val id = ShadowDisplayManager.addDisplay("w1280dp-h480dp-mdpi", "Drive Card visibility fixture")
            displayId = id
            val display = context.getSystemService(DisplayManager::class.java).getDisplay(id)
            shadowOf(display).setFlags(Display.FLAG_PRESENTATION)
            shadowOf(display).setState(Display.STATE_ON)
            val facts = requireNotNull(PublicDisplayFacts.read(context, id))
            descriptor = SecondaryDisplayDescriptor(SecondaryOutputId("drivecard-visibility-$id"), id, 1,
                "Fixture", facts.width, facts.height, facts.densityDpi, SecondaryRoute.GM_HOSTED,
                SecondaryReason.NONE, "Unknown")
            geometry = SecondaryHostGeometry(facts.width, facts.height, PixelRect(0, 0, facts.width, facts.height))
            mockkObject(ProcessVehicleDataRuntime); mocked = true
            every { ProcessVehicleDataRuntime.latestVehicleData() } returns vehicles
            every { ProcessVehicleDataRuntime.isActive() } returns true
            SecondaryHostRegistryTest.PermissionShadow.allowed = true
            val consumer = object : HostConsumer {
                override fun onReady(receipt: SecondaryHostReceipt): CompletableFuture<Boolean> {
                    readyReceipts += receipt
                    return if (cleaning) CompletableFuture.completedFuture(true) else attachAck
                }
                override fun onSuspend(receipt: SecondaryHostReceipt): CompletableFuture<Boolean> {
                    suspendReceipts += receipt
                    return if (cleaning) CompletableFuture.completedFuture(true) else suspendAck
                }
                override fun onWithdraw(receipt: SecondaryHostReceipt): CompletableFuture<Boolean> {
                    withdrawReceipts += receipt
                    return if (cleaning) CompletableFuture.completedFuture(true) else withdrawAck
                }
                override fun onRevoke(owner: SecondaryHostOwner) { revoked += owner }
            }
            registry = SecondaryHostRegistry(context, MutableStateFlow(SecondaryInventorySnapshot(1, listOf(descriptor))), consumer)
            handle = requireNotNull(registry).create(context, descriptor, 1, retainedWindow = true,
                restoreAuthority = authority, driveCardEnabled = enabled, driveCardCarPlay = true,
                driveCardLayout = DriveCardLayout.DEFAULT)
            handles += handle
            idle()
            intent = requireNotNull(shadowOf(context).nextStartedActivity).putExtra("driveCardEnabled", true)
            token = requireNotNull(intent.getStringExtra(SecondaryHostRegistry.TOKEN_EXTRA))
            controller = newActivity()
            assertFalse(activity.isFinishing)
            assertEquals(descriptor.displayId, activity.display!!.displayId)
            assertEquals(1, root.childCount)
            texture = root.getChildAt(0) as TextureView
            // Identity is production's anonymous registered TextureView, never a generic stand-in.
            assertSame(texture, field<TextureView>(activity, "textureView"))
            assertNotEquals(TextureView::class.java, texture.javaClass)
            if (presentInitially) {
                SecondaryHostRegistry.present(activity, token, surface(), geometry)
                assertEquals(1, readyReceipts.size)
                assertSame(handle.owner, readyReceipts.single().owner)
            }
            assertNull(root.findViewWithTag<View>(DriveCardView.TAG))
            if (presentInitially && !holdAttach) {
                attachAck.complete(true); idle()
                assertSame(readyReceipts.single(), completed("initial ready", handle.ready))
                assertEquals(if (enabled) 2 else 1, root.childCount)
                assertEquals(if (enabled) 1 else 0, vehicles.subscriptionCount.value)
                if (enabled) {
                    assertSame(card, root.getChildAt(1)); assertEquals(DriveCardView.TAG, card.tag)
                    assertTrue(card.isCurrentCard())
                    val w = geometry.width; val h = geometry.height
                    root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, w, h)
                }
            }
            assertEquals(0, suspendReceipts.size); assertEquals(0, withdrawReceipts.size)
            assertFalse(field<Boolean>(entry, "suspended"))
        }
        fun newActivity(): ActivityController<ClusterDirectVideoActivity> {
            val next = ActivityController.of(ClusterDirectVideoActivity(), intent,
                ActivityOptions.makeBasic().setLaunchDisplayId(descriptor.displayId).toBundle())
            controllers += next // own before any fallible lifecycle call
            next.create().start().resume()
            return next
        }
        fun oldJob(view: DriveCardView): Job = requireNotNull(field<CoroutineScope?>(view, "scope")).coroutineContext[Job]!!
        fun lease(): DriveCardPreview.Lease = requireNotNull(DriveCardPreview.start(descriptor))
        fun dispatch(visibility: Int, fromDecor: Boolean = false) {
            assertSame(Looper.getMainLooper(), Looper.myLooper())
            if (fromDecor) {
                assertSame(root, activity.window.decorView.findViewById<View>(android.R.id.content))
                activity.window.decorView.dispatchWindowVisibilityChanged(visibility)
            } else root.dispatchWindowVisibilityChanged(visibility)
        }
        fun retiredImmediately(old: DriveCardView, job: Job, lease: DriveCardPreview.Lease,
            identity: RestoreAuthority.Identity) {
            assertFalse(old.isCurrentCard()); assertTrue(job.isCancelled)
            assertNull(field<CoroutineScope?>(old, "scope"))
            assertFalse(lease.isActive); assertFalse(lease.update(DriveCardLayout.DEFAULT.move(-.01, 0.0)))
            assertNull(DriveCardPreview.start(descriptor))
            assertFalse(authority.matches(identity))
            assertTrue(field<Boolean>(entry, "suspended"))
            assertNull(field<Any?>(entry, "pending"))
            assertEquals(1, suspendReceipts.size)
            assertSame(readyReceipts.single(), suspendReceipts.single())
            assertFalse(suspendAck.isDone)
            assertFalse(field<CompletableFuture<Boolean>>(entry, "suspension").isDone)
            assertTrue("suspending is NOT terminal owner revocation", handle.owner.isCurrent)
            assertEquals(0, revoked.size); assertEquals(0, withdrawReceipts.size)
        }
        fun removed(old: DriveCardView) {
            idle()
            assertNull(old.parent); assertEquals(1, root.childCount)
            assertSame(texture, root.getChildAt(0)); assertSame(root, texture.parent)
            assertEquals(0, vehicles.subscriptionCount.value)
            assertNull(DriveCardPreview.start(descriptor))
        }
        fun acknowledgePark() {
            val exact = field<CompletableFuture<Boolean>>(entry, "suspension")
            assertFalse(exact.isDone)
            suspendAck.complete(true) // on Main => existing registry completion runs inline, NO idle
            assertTrue(exact.getNow(false))
            assertSame(suspendReceipts.single(), field<SecondaryHostReceipt>(entry, "parkedReceipt"))
            assertNull(field<SecondaryHostReceipt?>(entry, "receipt"))
            assertTrue(handle.readyForOffer().getNow(false))
        }
        fun duplicate(): HostHandle = requireNotNull(registry).create(context, descriptor, 2,
            retainedWindow = true, driveCardEnabled = true, driveCardCarPlay = true).also { handles += it }
        fun cleanup() {
            // No singleton reset or token/claim erasure. Finish exact futures, then real retry.
            cleaning = true
            attachAck.complete(true); suspendAck.complete(true); withdrawAck.complete(true)
            val errors = mutableListOf<Throwable>()
            fun attempt(block: () -> Unit) { try { block() } catch (failure: Throwable) { errors += failure } }
            handles.asReversed().forEach { owned -> attempt {
                assertTrue("exact owner retirement", completed("exact owner cleanup", owned.retire()))
                assertFalse(owned.owner.isCurrent)
            } }
            attempt { registry?.close(); idle() }
            controllers.asReversed().filterNot { it in destroyed }.forEach { owned -> attempt {
                owned.pause().stop().destroy(); destroyed += owned
            } }
            attempt { idle(); assertEquals(0, vehicles.subscriptionCount.value) }
            attempt { displayId?.let { ShadowDisplayManager.removeDisplay(it) }; idle() }
            if (mocked) unmockkObject(ProcessVehicleDataRuntime)
            SecondaryHostRegistryTest.PermissionShadow.allowed = previousPermission
            if (previousContexts == null) System.clearProperty("robolectric.createActivityContexts")
            else System.setProperty("robolectric.createActivityContexts", previousContexts)
            if (errors.isNotEmpty()) throw errors.first().also { first -> errors.drop(1).forEach(first::addSuppressed) }
        }
    }
    private fun fixture(enabled: Boolean = true, holdAttach: Boolean = false,
        presentInitially: Boolean = true, block: Fixture.() -> Unit) {
        val f = Fixture(enabled, holdAttach, presentInitially)
        var original: Throwable? = null
        try { f.setup(); f.block() }
        catch (failure: Throwable) { original = failure; throw failure }
        finally { try { f.cleanup() } catch (failure: Throwable) {
            if (original != null) original.addSuppressed(failure) else throw failure
        } }
    }

    private fun visibility(visibility: Int, decor: Boolean = false) = fixture {
        val old = card; val job = oldJob(old); val preview = lease()
        val identity = requireNotNull(authority.snapshot())
        // NO hierarchy listener or assertion inside callback: preserve actual framework throwable.
        try { dispatch(visibility, decor) } catch (failure: Throwable) {
            System.err.println("VISIBILITY_ACTUAL_THROWABLE sdk=${android.os.Build.VERSION.SDK_INT} visibility=$visibility decor=$decor")
            failure.printStackTrace(System.err)
            throw failure // never manufacture/assert an expected framework line number
        }
        retiredImmediately(old, job, preview, identity)
        assertSame("physical removal waits for later Main turn", root, old.parent)
        assertSame(old, root.getChildAt(1)); assertEquals(2, root.childCount)
        val bitmap = Bitmap.createBitmap(geometry.width, geometry.height, Bitmap.Config.ARGB_8888)
        try {
            old.draw(Canvas(bitmap))
            val pixels = IntArray(geometry.width * geometry.height)
            bitmap.getPixels(pixels, 0, geometry.width, 0, 0, geometry.width, geometry.height)
            assertTrue("stopped parented card draws no stale pixels", pixels.all { it == 0 })
        } finally { bitmap.recycle() }
        removed(old) // does not complete suspendAck
        assertFalse(suspendAck.isDone)
        acknowledgePark()
        assertEquals(1, readyReceipts.size)
    }
    @Test fun goneDispatchReturnsAndRetiresAuthorityBeforeHeldGpuAck() = visibility(View.GONE)
    @Test fun invisibleDispatchReturnsAndRetiresAuthorityBeforeHeldGpuAck() = visibility(View.INVISIBLE)
    @Test fun decorGoneDispatchReachesActualRegisteredTexture() = visibility(View.GONE, decor = true)

    @Test fun removalWitnessDoesNotThrowInsideCallbackOrMaskFrameworkNpe() = fixture {
        val old = card
        var inside = false; var removals = 0; var nested = 0
        root.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View?, child: View?) = Unit
            override fun onChildViewRemoved(parent: View?, child: View?) {
                if (child === old) { removals++; if (inside) nested++ }
            }
        })
        var actual: Throwable? = null
        try {
            inside = true
            try { dispatch(View.GONE) } catch (failure: Throwable) { actual = failure }
            finally { inside = false }
            System.err.println("VISIBILITY_MUTATION_WITNESS sdk=${android.os.Build.VERSION.SDK_INT} nested=$nested removals=$removals")
            actual?.let { it.printStackTrace(System.err) }
            if (actual != null) throw actual
            assertEquals("no structural removal during framework callback", 0, nested)
            assertEquals(0, removals)
            removed(old)
            assertEquals("exact old View removed once on later turn", 1, removals)
        } finally { root.setOnHierarchyChangeListener(null) }
    }

    @Test fun disabledCardGoneIsPositiveRealCallbackControl() = fixture(enabled = false) {
        val exact = readyReceipts.single()
        dispatch(View.GONE)
        assertEquals(1, root.childCount); assertSame(texture, root.getChildAt(0))
        assertEquals(listOf(exact), suspendReceipts); assertFalse(suspendAck.isDone)
        assertEquals(0, vehicles.subscriptionCount.value); assertNull(DriveCardPreview.start(descriptor))
        acknowledgePark()
    }
    @Test fun disabledCardInvisibleIsPositiveRealCallbackControl() = fixture(enabled = false) {
        dispatch(View.INVISIBLE)
        assertEquals(1, root.childCount); assertEquals(1, suspendReceipts.size)
        assertFalse(suspendAck.isDone); acknowledgePark()
    }
    @Test fun visibleCallbackPreservesExactCurrentCardAndReceipt() = fixture {
        val old = card; val preview = lease(); val exact = readyReceipts.single()
        dispatch(View.VISIBLE); idle()
        assertSame(old, card); assertTrue(old.isCurrentCard()); assertTrue(preview.isActive)
        assertSame(exact, field<SecondaryHostReceipt>(entry, "receipt"))
        assertEquals(1, readyReceipts.size); assertEquals(0, suspendReceipts.size)
        assertEquals(1, vehicles.subscriptionCount.value)
    }
    @Test fun realRegistrySuccessorInstalledBeforeOldRemovalDrainSurvives() = fixture {
        val old = card; val oldPreview = lease(); val saved = old.layout
        assertTrue(oldPreview.update(saved.move(-.02, -.01)))
        dispatch(View.GONE)
        assertSame(root, old.parent)
        acknowledgePark() // synchronous exact ack; do NOT drain old removal
        SecondaryHostRegistry.resume(activity, token)
        SecondaryHostRegistry.present(activity, token, surface(), geometry)
        // attachAck is completed: actual registry onReady/showDriveCard executes synchronously.
        val next = field<DriveCardView>(activity, "driveCard")
        assertNotSame(old, next); assertTrue(next.isCurrentCard())
        val nextPreview = lease()
        assertEquals(saved, next.layout); assertEquals(3, root.childCount)
        assertSame(old, root.getChildAt(1)); assertSame(next, root.getChildAt(2))
        oldPreview.stop(); assertFalse(oldPreview.update(saved.move(-.01, 0.0)))
        idle()
        assertNull(old.parent); assertEquals(2, root.childCount)
        assertSame(texture, root.getChildAt(0)); assertSame(next, root.getChildAt(1))
        assertTrue(next.isCurrentCard()); assertTrue(nextPreview.isActive)
        assertEquals(saved, next.layout); assertEquals(1, vehicles.subscriptionCount.value)
        assertEquals(2, readyReceipts.size); assertEquals(1, suspendReceipts.size)
        assertEquals(0, withdrawReceipts.size)
        nextPreview.stop()
    }
    @Test fun repeatedHiddenCallbacksRemoveExactOldCardOnce() = fixture {
        val old = card; val job = oldJob(old); var removals = 0
        root.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View?, child: View?) = Unit
            override fun onChildViewRemoved(parent: View?, child: View?) { if (child === old) removals++ }
        })
        try {
            dispatch(View.GONE); dispatch(View.INVISIBLE)
            assertTrue(job.isCancelled); assertEquals(0, removals)
            removed(old); assertEquals(1, removals)
            dispatch(View.GONE); idle(); assertEquals(1, removals)
            assertEquals(1, suspendReceipts.size); assertEquals(1, readyReceipts.size)
        } finally { root.setOnHierarchyChangeListener(null) }
    }
    @Test fun retirementCanClearEntryBeforeExactOldRemovalDrain() = fixture {
        val old = card
        dispatch(View.GONE)
        val exactEntry = entry
        val terminal = handle.retire()
        assertFalse(handle.owner.isCurrent); assertFalse(terminal.isDone)
        assertEquals(0, withdrawReceipts.size) // still waits for held suspend ack
        suspendAck.complete(true)
        assertEquals(1, withdrawReceipts.size)
        assertSame(suspendReceipts.single(), withdrawReceipts.single())
        assertFalse(terminal.isDone); assertSame(root, old.parent)
        withdrawAck.complete(true) // real terminal acknowledgment, entry/token removed INLINE
        assertTrue(terminal.getNow(false)); assertNull(field<Any?>(exactEntry, "activity"))
        assertFalse(field<Set<Any>>(requireNotNull(registry), "entries").contains(exactEntry))
        removed(old) // cleanup must not require old token/activity/owner to remain current
    }
    private fun failedAck(exceptional: Boolean) = fixture {
        val old = card
        dispatch(View.GONE); removed(old)
        val pending = field<CompletableFuture<Boolean>>(entry, "suspension")
        if (exceptional) suspendAck.completeExceptionally(IllegalStateException("fixture GPU failure"))
        else suspendAck.complete(false)
        assertFalse(completed("failed suspend ack", pending))
        assertFalse(handle.readyForOffer().getNow(true))
        val terminal = handle.retire()
        assertFalse(handle.owner.isCurrent); assertFalse(terminal.isDone)
        assertSame(readyReceipts.single(), withdrawReceipts.single())
        withdrawAck.complete(false)
        assertFalse(completed("failed terminal ack", terminal))
        val denied = duplicate()
        assertTrue(denied.ready.isCompletedExceptionally); assertFalse(denied.owner.isCurrent)
        assertNull(shadowOf(context).nextStartedActivity)
        // Positive exact retry, no resets: fixture failure cannot poison the process lease.
        withdrawAck = CompletableFuture.completedFuture(true)
        assertTrue(completed("real terminal retry", handle.retire()))
        assertTrue(completed("denied owner's cleanup", denied.retire()))
        val admitted = duplicate()
        assertTrue(admitted.owner.isCurrent)
        assertNotNull(shadowOf(context).nextStartedActivity)
        assertTrue(completed("new unbound owner cleanup", admitted.retire()))
    }
    @Test fun falseGpuAckKeepsDebtFencedUntilExactRealRetry() = failedAck(false)
    @Test fun exceptionalGpuAckKeepsDebtFencedUntilExactRealRetry() = failedAck(true)

    @Test fun initialHiddenBeforeFirstOfferNeverFalselyAdmitsCardAndCanLaterParkExactAttach() =
        fixture(holdAttach = true, presentInitially = false) {
            // First actual window callback arrives before any renderer-ready offer/ack.
            assertEquals(0, readyReceipts.size)
            dispatch(View.GONE)
            assertTrue(field<Boolean>(entry, "suspended"))
            SecondaryHostRegistry.present(activity, token, surface(), geometry)
            assertEquals("no borrowed receipt may be invented while hidden", 0, readyReceipts.size)
            assertFalse(handle.ready.isDone); assertFalse(handle.readyForOffer().getNow(true))
            assertEquals(1, root.childCount); assertSame(texture, root.getChildAt(0))
            assertNull(root.findViewWithTag<View>(DriveCardView.TAG))
            assertNull(DriveCardPreview.start(descriptor)); assertEquals(0, vehicles.subscriptionCount.value)
            // Existing API rejects pre-offer hidden presents. A REAL visible callback is needed
            // to admit the first real offer; do not synthesize resume or a hidden GPU receipt.
            dispatch(View.VISIBLE)
            SecondaryHostRegistry.present(activity, token, surface(), geometry)
            assertEquals(1, readyReceipts.size); assertFalse(attachAck.isDone)
            dispatch(View.GONE) // hidden again BEFORE that exact onReady acknowledgement
            assertEquals(0, suspendReceipts.size); assertFalse(handle.ready.isDone)
            attachAck.complete(true)
            assertEquals(1, suspendReceipts.size); assertFalse(handle.ready.isDone)
            assertNull(root.findViewWithTag<View>(DriveCardView.TAG))
            assertNull(DriveCardPreview.start(descriptor)); assertEquals(0, vehicles.subscriptionCount.value)
            acknowledgePark()
            assertSame(readyReceipts.single(), completed("initial hidden exact parked preparation", handle.ready))
            assertTrue(completed("initial hidden parked offer", handle.readyForOffer()))
            assertEquals(1, root.childCount); assertEquals(0, withdrawReceipts.size)
        }

    @Test fun attachAckAfterRealVisibilitySuspensionPositivelyParksWithoutCard() = fixture(holdAttach = true) {
        dispatch(View.GONE)
        assertTrue(field<Boolean>(entry, "suspended")); assertEquals(0, suspendReceipts.size)
        assertFalse(handle.ready.isDone)
        attachAck.complete(true)
        assertEquals(1, suspendReceipts.size); assertFalse(handle.ready.isDone)
        assertNull(root.findViewWithTag<View>(DriveCardView.TAG))
        assertEquals(0, vehicles.subscriptionCount.value)
        acknowledgePark()
        assertSame(readyReceipts.single(), completed("late attach parked preparation", handle.ready))
        assertTrue(completed("parked offer readiness", handle.readyForOffer()))
        assertEquals(1, root.childCount)
    }
    @Test fun recreatedActivityRejectsOldRootTokenReceiptAndLateRelease() = fixture {
        val previousController = controller; val previous = activity; val oldRoot = root
        val old = card; val oldPreview = lease(); val oldReceipt = readyReceipts.single()
        previousController.pause().stop().destroy(); destroyed += previousController
        suspendAck.complete(true)
        controller = newActivity()
        SecondaryHostRegistry.present(activity, token, surface(), geometry); idle()
        val next = card; val nextPreview = lease(); val exact = readyReceipts.last()
        assertNotSame(old, next); assertTrue(next.isCurrentCard())
        oldRoot.dispatchWindowVisibilityChanged(View.GONE)
        ReflectionHelpers.callInstanceMethod<Any>(previous, "onStop")
        assertFalse(SecondaryHostRegistry.suspend(previous, token).getNow(true))
        assertFalse(SecondaryHostRegistry.suspend(activity, "foreign-token").getNow(true))
        assertTrue(SecondaryHostRegistry.suspend(activity, token, oldReceipt.surface).getNow(false))
        var released = 0
        SecondaryHostRegistry.releaseTexture(token, oldReceipt.surface) { released++ }
        oldPreview.stop(); assertFalse(oldPreview.update(DriveCardLayout.DEFAULT))
        idle()
        assertEquals(1, released); assertTrue(nextPreview.isActive); assertTrue(next.isCurrentCard())
        assertSame(exact, field<SecondaryHostReceipt>(entry, "receipt"))
        assertSame(next, card); assertEquals(2, root.childCount)
        assertEquals(1, vehicles.subscriptionCount.value); assertEquals(1, suspendReceipts.size)
    }
    @Test fun directSuspendControlStillRetiresImmediatelyWithoutTraversal() = fixture {
        val old = card; val job = oldJob(old); val preview = lease()
        val identity = requireNotNull(authority.snapshot())
        SecondaryHostRegistry.suspend(activity, token)
        retiredImmediately(old, job, preview, identity)
        removed(old)
    }
    @Test fun activityStopRetiresCollectorsAndPreviewWithExactRemoval() = fixture {
        val old = card; val job = oldJob(old); val preview = lease()
        controller.pause().stop()
        assertTrue(job.isCancelled); assertFalse(old.isCurrentCard()); assertFalse(preview.isActive)
        removed(old)
        controller.destroy(); destroyed += controller
    }
    @Test fun activityDestroyBeforeRemovalDrainKeepsCleanupLocalToOldView() = fixture {
        val old = card; val job = oldJob(old); val preview = lease(); val oldRoot = root
        controller.pause().stop().destroy(); destroyed += controller
        assertTrue(job.isCancelled); assertFalse(preview.isActive)
        assertFalse(old.isCurrentCard()); assertSame(oldRoot, old.parent)
        assertNull(field<Any?>(entry, "activity"))
        idle()
        assertNull(old.parent); assertEquals(1, oldRoot.childCount)
        assertSame(texture, oldRoot.getChildAt(0)); assertEquals(0, vehicles.subscriptionCount.value)
    }
    @Test fun ownerRetirementImmediatelyCancelsCardButDoesNotFabricateGpuAck() = fixture {
        val old = card; val job = oldJob(old); val preview = lease()
        val terminal = handle.retire()
        assertFalse(handle.owner.isCurrent); assertFalse(old.isCurrentCard())
        assertTrue(job.isCancelled); assertFalse(preview.isActive)
        assertNull(DriveCardPreview.start(descriptor))
        assertFalse(terminal.isDone); assertFalse(withdrawAck.isDone)
        assertSame(readyReceipts.single(), withdrawReceipts.single())
        assertSame(root, old.parent)
        removed(old)
        assertFalse(terminal.isDone) // physical View removal is not GPU retirement
        withdrawAck.complete(true)
        assertTrue(completed("exact owner terminal ack", terminal))
    }
    @Test fun currentPredicateExpiryThroughRealVehicleCollectorRemovesOldCard() = fixture {
        val old = card; val job = oldJob(old); val preview = lease()
        // Finishing invalidates the real card predicate before onStop/registry retirement.
        // Unlike ShadowDisplay.setState, this does not dispatch a display-listener
        // retirement and accidentally turn the card-local control into another case.
        activity.finish()
        assertTrue(activity.isFinishing)
        assertTrue("owner remains current before collector expiry", handle.owner.isCurrent)
        assertTrue(requireNotNull(PublicDisplayFacts.read(context, descriptor.displayId)).on)
        vehicles.value = ControlMessage.VehicleData(speedKmh = 1f)
        await("real collector expiry removes card") { old.parent == null }
        assertTrue(job.isCancelled); assertFalse(preview.isActive)
        assertEquals(0, vehicles.subscriptionCount.value)
        assertNull(DriveCardPreview.start(descriptor))
        assertTrue("expiry here is card-local, not registry retirement", handle.owner.isCurrent)
        assertEquals(0, revoked.size); assertEquals(0, suspendReceipts.size)
        assertEquals(0, withdrawReceipts.size)
    }
}
