/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.desklink.mobile.backends.lan

import org.desklink.mobile.helpers.LineTooLongException
import org.desklink.mobile.helpers.readLineBounded
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetAddress

class UnpairedLimitsTest {
    private val a = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1))
    private val b = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 2))

    @Test
    fun aStrangerLineIsBoundedAt256KiBAndAPairedOneIsMoreGenerous() {
        val big = ByteArray(300 * 1024) { 'x'.code.toByte() } + byteArrayOf('\n'.code.toByte())
        assertThrows(LineTooLongException::class.java) { readLineBounded(ByteArrayInputStream(big), UnpairedLimits.MAX_UNPAIRED_LINE) }
        val ok = ByteArray(200 * 1024) { 'x'.code.toByte() } + byteArrayOf('\n'.code.toByte())
        assertTrue(readLineBounded(ByteArrayInputStream(ok), UnpairedLimits.MAX_UNPAIRED_LINE).length > 200_000)
        assertTrue(readLineBounded(ByteArrayInputStream(big), UnpairedLimits.MAX_PAIRED_LINE).length > 300_000)
        assertTrue("a beacon is small", UnpairedLimits.MAX_IDENTITY_PACKET <= 64 * 1024)
    }

    @Test
    fun atMost42UnpairedDevicesAndTwoPerAddress() {
        var now = 0L
        val limits = UnpairedLimits { now }
        assertTrue(limits.admit("one", a))
        assertTrue(limits.admit("two", a))
        assertFalse("a third from one address", limits.admit("three", a))
        assertTrue("the same device again", limits.admit("one", a))
        val many = UnpairedLimits { now }
        repeat(UnpairedLimits.MAX_UNPAIRED_DEVICES) { assertTrue(many.admit("id$it", InetAddress.getByAddress(byteArrayOf(10, 0, (it / 100).toByte(), (it % 100).toByte())))) }
        assertFalse("the 43rd", many.admit("extra", b))
        now += UnpairedLimits.FORGET_AFTER_MS
        assertTrue("forgotten after a quiet ten minutes", many.admit("extra", b))
    }

    @Test
    fun aDeviceThatPairedNoLongerCounts() {
        val limits = UnpairedLimits { 0L }
        limits.admit("x", a); limits.admit("y", a)
        limits.release("x")
        assertTrue(limits.admit("z", a))
    }

    @Test
    fun overARealSocketAStrangersOversizedLineIsRefusedAndALaterNormalOneIsNot() {
        java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val writer = Thread {
                java.net.Socket(InetAddress.getLoopbackAddress(), server.localPort).use { out ->
                    out.getOutputStream().apply {
                        write(ByteArray(300 * 1024) { 'x'.code.toByte() }); write('\n'.code); flush()
                    }
                }
            }.apply { start() }
            server.accept().use { accepted ->
                val input = accepted.getInputStream().buffered()
                assertThrows(LineTooLongException::class.java) { readLineBounded(input, UnpairedLimits.MAX_UNPAIRED_LINE) }
            }
            writer.join()
        }
        java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val writer = Thread {
                java.net.Socket(InetAddress.getLoopbackAddress(), server.localPort).use { out ->
                    out.getOutputStream().apply { write("{\"type\":\"desklink.ping\"}\n".toByteArray()); flush() }
                }
            }.apply { start() }
            server.accept().use { accepted ->
                assertTrue(readLineBounded(accepted.getInputStream().buffered(), UnpairedLimits.MAX_UNPAIRED_LINE).contains("desklink.ping"))
            }
            writer.join()
        }
    }
}
