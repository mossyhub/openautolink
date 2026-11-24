package com.openautolink.app.voice

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionVoiceAssistantRouterTest {
    private val owners = mutableListOf<Any>()

    @After fun clearRoutes() { owners.forEach(ProjectionVoiceAssistantRouter::clear) }

    @Test fun routesAssistantGestureOffTheCallerThread() {
        val owner = Any().also(owners::add)
        val caller = Thread.currentThread()
        var invokedOn: Thread? = null
        val invoked = CountDownLatch(1)
        ProjectionVoiceAssistantRouter.install(owner) {
            invokedOn = Thread.currentThread()
            invoked.countDown()
            true
        }

        assertTrue(ProjectionVoiceAssistantRouter.request("test"))
        assertTrue(invoked.await(3, TimeUnit.SECONDS))
        assertTrue(invokedOn !== caller)
    }

    @Test fun staleOwnerCannotClearOrInvokeSuccessor() {
        val first = Any().also(owners::add)
        val second = Any().also(owners::add)
        var firstCalls = 0
        var secondCalls = 0
        val invoked = CountDownLatch(1)
        ProjectionVoiceAssistantRouter.install(first) { firstCalls++; true }
        ProjectionVoiceAssistantRouter.install(second) { secondCalls++; invoked.countDown(); true }

        ProjectionVoiceAssistantRouter.clear(first)
        assertTrue(ProjectionVoiceAssistantRouter.request("test"))
        assertTrue(invoked.await(3, TimeUnit.SECONDS))
        assertEquals(0, firstCalls)
        assertEquals(1, secondCalls)
    }

    @Test fun noLiveProjectionRefusesTheGesture() {
        val owner = Any().also(owners::add)
        ProjectionVoiceAssistantRouter.install(owner) { true }
        ProjectionVoiceAssistantRouter.clear(owner)
        assertFalse(ProjectionVoiceAssistantRouter.request("test"))
    }
}
