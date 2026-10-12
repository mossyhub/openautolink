package com.openautolink.app.carplay

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class CarPlayPersistentFailureTest {
    @Test fun typedFailureSurvivesEndedMarkerAndReceiverRetirementWithoutKeepingException() {
        val ended = CountDownLatch(1)
        val latest = AtomicReference(CarPlayUiState())
        val failure = CarPlayConnectionFailure(CarPlayFailureKind.RUNTIME_STARTUP_TIMEOUT, true,
            IllegalStateException("private provisioning secret"))
        val coordinator = CarPlayCoordinator(CarPlayOwnership(), {}) {
            latest.set(it)
            if (it.selected && !it.busy) ended.countDown()
        }
        try {
            assertTrue(coordinator.connect(true) { report -> object : CarPlayRuntime {
                override fun run() { throw failure }
                override fun cancel() {}
                override fun close() { report("media stage=session-ended") }
            } })
            assertTrue(ended.await(3, TimeUnit.SECONDS))
            val state = latest.get()
            val field = state.javaClass.declaredFields.firstOrNull { it.name == "failure" }
            assertNotNull("Retired state must retain a safe typed failure DTO", field)
            field!!.isAccessible = true
            val dto = field.get(state)
            assertNotNull(dto)
            assertFalse(dto is Throwable)
            val fields = dto.javaClass.declaredFields.filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }.associateBy { it.name }
            assertEquals(setOf("kind", "manualPeer", "userGuidance"), fields.keys)
            fields.values.forEach { it.isAccessible = true }
            assertEquals(failure.kind, fields.getValue("kind").get(dto))
            assertEquals(true, fields.getValue("manualPeer").get(dto))
            assertEquals(failure.userGuidance, fields.getValue("userGuidance").get(dto))
            assertFalse(state.toString().contains("private provisioning secret"))
        } finally { coordinator.close() }
    }
}
