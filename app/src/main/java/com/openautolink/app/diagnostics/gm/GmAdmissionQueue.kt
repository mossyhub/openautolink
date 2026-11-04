package com.openautolink.app.diagnostics.gm

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Nonwaiting producer; one bounded FIFO drain. Failed scheduling abandons only observations. */
internal class GmAdmissionQueue<T>(
    private val capacity:Int=64,
    private val schedule:(Runnable)->Unit,
    private val consume:(T)->Unit,
) {
    init { require(capacity>0) }
    private val lock=ReentrantLock()
    private val queue=ArrayDeque<T>()
    private var running=false
    private val losses=AtomicLong()
    val dropped:Long get()=losses.get()
    fun offer(value:T):Boolean {
        if(!lock.tryLock()){losses.incrementAndGet();return false}
        var start=false
        try {
            if(queue.size>=capacity){losses.incrementAndGet();return false}
            queue.addLast(value)
            if(!running){running=true;start=true}
        }finally{lock.unlock()}
        if(start)try{schedule(Runnable{drain()})}catch(_:Exception){
            // No consumer/log callback runs synchronously on this failure path.
            lock.withLock{losses.addAndGet(queue.size.toLong());queue.clear();running=false}
            return false
        }
        return true
    }
    private fun drain() {
        while(true){
            val next=lock.withLock{
                if(queue.isEmpty()){running=false;return}
                queue.removeFirst()
            }
            try{consume(next)}catch(_:Exception){losses.incrementAndGet()}
        }
    }
    fun isIdle():Boolean=lock.withLock{!running && queue.isEmpty()}
}
