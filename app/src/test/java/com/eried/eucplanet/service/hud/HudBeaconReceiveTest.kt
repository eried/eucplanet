package com.eried.eucplanet.service.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class HudBeaconReceiveTest {

    private fun listener(): DatagramSocket = DatagramSocket(null).apply {
        reuseAddress = true
        soTimeout = 200
        bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
    }

    private fun send(to: DatagramSocket, text: String) {
        DatagramSocket().use { s ->
            val bytes = text.toByteArray(Charsets.US_ASCII)
            s.send(DatagramPacket(bytes, bytes.size, InetAddress.getLoopbackAddress(), to.localPort))
        }
    }

    @Test
    fun `beacons are handed over while the loop is wanted`() {
        val wanted = AtomicBoolean(true)
        val got = CopyOnWriteArrayList<String>()
        listener().use { socket ->
            val t = thread {
                receiveWhile(socket, { wanted.get() }) { p -> got += String(p.data, 0, p.length, Charsets.US_ASCII) }
            }
            send(socket, "one")
            send(socket, "two")
            val deadline = System.currentTimeMillis() + 3_000
            while (got.size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(20)
            wanted.set(false)
            t.join(3_000)
            assertFalse("the loop did not end", t.isAlive)
        }
        assertEquals(listOf("one", "two"), got.toList())
    }

    @Test
    fun `a loop nobody wants any more ends even when no beacon ever arrives`() {
        // The case that piled up: a recycled listener with no HUD in range
        // waited for a packet for ever and held its thread and its socket.
        val wanted = AtomicBoolean(true)
        listener().use { socket ->
            val t = thread { receiveWhile(socket, { wanted.get() }) { } }
            Thread.sleep(300)
            assertTrue("it should still be listening", t.isAlive)
            wanted.set(false)
            t.join(3_000)
            assertFalse("the loop did not end", t.isAlive)
        }
    }
}
