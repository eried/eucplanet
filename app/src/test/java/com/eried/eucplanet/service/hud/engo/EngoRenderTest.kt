package com.eried.eucplanet.service.hud.engo

import com.eried.eucplanet.data.model.EngoHudSettings
import com.eried.eucplanet.data.model.EngoSlot
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/**
 * Renders the ride page, from the commands the glasses are sent, into PNGs
 * under build/engo-renders, to look at the page without glasses.
 */
class EngoRenderTest {

    private val out = File("build/engo-renders").apply { mkdirs() }

    private fun save(name: String, page: EngoPage, color: Boolean = true) {
        val px = EngoRaster.render(EngoLayout.render(page, EngoCaps(color)), color)
        val f = File(out, "$name.png")
        f.writeBytes(png(px, EngoRaster.W, EngoRaster.H, scale = 3))
        assertTrue(f.length() > 0)
    }

    /** A plain RGB PNG (the unit test classpath has no java.awt). */
    private fun png(px: IntArray, w: Int, h: Int, scale: Int): ByteArray {
        val ow = w * scale; val oh = h * scale
        val raw = ByteArrayOutputStream()
        for (y in 0 until oh) {
            raw.write(0) // filter: none
            for (x in 0 until ow) {
                val c = px[(y / scale) * w + x / scale]
                raw.write((c shr 16) and 0xFF); raw.write((c shr 8) and 0xFF); raw.write(c and 0xFF)
            }
        }
        val z = ByteArrayOutputStream()
        DeflaterOutputStream(z).use { it.write(raw.toByteArray()) }
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        d.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        fun chunk(type: String, data: ByteArray) {
            d.writeInt(data.size)
            val t = type.toByteArray(Charsets.US_ASCII)
            d.write(t); d.write(data)
            val crc = CRC32(); crc.update(t); crc.update(data)
            d.writeInt(crc.value.toInt())
        }
        val ihdr = ByteArrayOutputStream().also { b ->
            DataOutputStream(b).apply { writeInt(ow); writeInt(oh); write(8); write(2); write(0); write(0); write(0) }
        }.toByteArray()
        chunk("IHDR", ihdr)
        chunk("IDAT", z.toByteArray())
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    private val now = 1_000_000L
    private val u = EngoMetrics.Units()

    @Test
    fun renderThePages() {
        val bars = EngoHudSettings()
        val graphs = EngoHudSettings(graphs = true)
        val mockup = EngoHudSettings(
            top = EngoSlot("SPEED", "MAX"),
            slots = listOf(EngoSlot("MOTOR_TEMP"), EngoSlot("LOAD"), EngoSlot("PHASE_CURRENT")),
        )
        save("1-bars", EngoDemoSource.page(bars, u, 65, 85, navigating = false, nowMs = now))
        save("2-graphs", EngoDemoSource.page(graphs, u, 65, 85, navigating = false, nowMs = now))
        save("3-bars-nav", EngoDemoSource.page(mockup, u, 65, 85, navigating = true, nowMs = now, speedKmh = 63f))
        save("4-graphs-nav", EngoDemoSource.page(mockup.copy(graphs = true), u, 65, 85, true, now, 63f))
        save("5-pwm-high", EngoDemoSource.page(bars, u, 40, 55, navigating = false, nowMs = now))
        save("6-engo2-grey", EngoDemoSource.page(mockup, u, 65, 85, true, now, 63f), color = false)
        save("7-mph-stats", EngoDemoSource.page(
            EngoHudSettings(top = EngoSlot("LOAD", "MAX"), slots = listOf(
                EngoSlot("SPEED", "AVG"), EngoSlot("MOTOR_POWER", "SUSTAINED_PEAK"), EngoSlot("VOLTAGE"))),
            EngoMetrics.Units(speed = "mph", temp = "F", distance = "mi"), 65, 85, true, now, 118f))
    }
}
