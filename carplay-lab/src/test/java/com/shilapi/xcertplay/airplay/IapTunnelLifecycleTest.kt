package com.shilapi.xcertplay.airplay

import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class IapTunnelLifecycleTest {
    @Test fun cleanPeerEofNotifiesOwner() {
        val opened = CountDownLatch(1)
        val ended = CountDownLatch(1)
        var cause: Throwable? = null
        val tunnel = IapTunnel(ByteArray(32), expectedPeer = InetAddress.getByName("127.0.0.1"))
        try {
            val port = tunnel.listen(object : IapTunnel.Listener {
                override fun onOpen(remoteAddress: String?) { opened.countDown() }
                override fun onClosed(failure: Throwable?) { cause = failure; ended.countDown() }
            })
            Socket("127.0.0.1", port).use {
                assertTrue(opened.await(2, TimeUnit.SECONDS))
            }
            assertTrue("Clean peer EOF must notify owner", ended.await(2, TimeUnit.SECONDS))
            assertNull(cause)
        } finally { tunnel.close() }
    }
}
