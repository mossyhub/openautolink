package com.openautolink.app.carplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayTouchMapperTest {
    @Test fun invalidViewportReleasesAndOutsideDownIsNotAdmitted() {
        val m=CarPlayTouchMapper()
        assertTrue(m.map(0,0,listOf(CarPlayPointer(1,-1f,5f)),100,100).none { it.down })
        m.map(0,0,listOf(CarPlayPointer(1,5f,5f)),100,100)
        assertTrue(m.map(2,0,listOf(CarPlayPointer(1,5f,5f)),0,100).none { it.down })
    }
    @Test fun productionBridgeCopiesEveryPointerAndSendsWholeSnapshot() {
        val root=generateSequence(java.io.File(System.getProperty("user.dir"))) { it.parentFile }
            .first { java.io.File(it,"app/src/main").isDirectory }
        val vm=java.io.File(root,"app/src/main/java/com/openautolink/app/ui/projection/ProjectionViewModel.kt").readText()
        assertTrue(vm.contains("event.getPointerId(index), event.getX(index), event.getY(index)"))
        val receiver=java.io.File(root,"carplay-lab/src/main/java/com/openautolink/carplaylab/media/ProjectionReceiver.kt").readText()
        assertTrue(receiver.contains("session.sendTouch(snapshot)"))
    }
    @Test fun twoFingersMoveAndLiftWithoutReassigningSlots() {
        val m = CarPlayTouchMapper()
        val a = CarPlayPointer(7, 250f, 500f)
        val b = CarPlayPointer(12, 750f, 500f)
        assertTrue(m.map(0,0,listOf(a),1000,1000)[0].down)
        val down = m.map(5,1,listOf(a,b),1000,1000)
        assertEquals(2,down.size); assertTrue(down.all { it.down })
        val moved = m.map(2,0,listOf(b.copy(x=900f),a.copy(x=100f)),1000,1000)
        assertEquals(0.1,moved[0].x,0.0001); assertEquals(0.9,moved[1].x,0.0001)
        val lifted = m.map(6,1,listOf(a,b),1000,1000)
        assertTrue(lifted[0].down); assertFalse(lifted[1].down)
        val remaining = m.map(2,0,listOf(a),1000,1000)
        assertTrue(remaining[0].down); assertFalse(remaining[1].down)
        assertTrue(m.map(1,0,listOf(a),1000,1000).none { it.down })
    }
    @Test fun primaryFingerLiftDoesNotMoveSecondFingerToFirstSlot() {
        val m=CarPlayTouchMapper(); val a=CarPlayPointer(4,10f,10f); val b=CarPlayPointer(9,80f,80f)
        m.map(0,0,listOf(a),100,100);m.map(5,1,listOf(a,b),100,100)
        m.map(6,0,listOf(a,b),100,100)
        val moved=m.map(2,0,listOf(b),100,100)
        assertFalse(moved[0].down);assertTrue(moved[1].down)
    }
    @Test fun cancellationReleasesBothAndThirdFingerIsIgnored() {
        val m=CarPlayTouchMapper();val ps=(0..2).map { CarPlayPointer(it,50f,50f) }
        m.map(0,0,ps.take(1),100,100);m.map(5,1,ps.take(2),100,100)
        assertEquals(2,m.map(5,2,ps,100,100).size)
        assertTrue(m.map(3,0,ps,100,100).none { it.down })
        assertTrue(m.map(2,0,ps,100,100).none { it.down })
    }
}
