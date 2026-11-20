package com.eried.eucplanet.service.hud.engo

import com.eried.eucplanet.data.model.EngoElement
import com.eried.eucplanet.data.model.EngoHudSettings
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

    // JUnit makes an instance per test, so the folder is only made here, never cleared.
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

    private fun el(key: String, stat: String = "CURRENT", style: String = "BLOCKS", window: Int = 15) =
        EngoElement(key, stat, style, window)

    /** The mockup's readings: speed, top speed, then motor temp, PWM and phase current. */
    private val mockup = listOf(
        el("SPEED", style = "NUMBER"), el("SPEED", "MAX", style = "NUMBER"),
        el("MOTOR_TEMP"), el("LOAD", style = "SMOOTH"), el("PHASE_CURRENT", style = "GRAPH"),
    )

    private fun engo(layout: String, elements: List<EngoElement> = mockup, font: String = "PIXEL") =
        EngoHudSettings(layout = layout, elements = elements, font = font)

    private fun page(e: EngoHudSettings, nav: Boolean = false, speed: Float = 63f) =
        EngoDemoSource.page(e, u, 65, 85, navigating = nav, nowMs = now, speedKmh = speed)

    /** Every layout in every number font, for the font comparison sheet. */
    @Test
    fun renderEveryLayoutInEveryFont() {
        val bigOnes = listOf(el("SPEED", style = "NUMBER"), el("LOAD", style = "SMOOTH"), el("BATTERY"))
        val quad = listOf(el("SPEED"), el("LOAD", style = "GRAPH"), el("VOLTAGE", style = "SMOOTH"), el("PHASE_CURRENT"))
        for (font in listOf("PIXEL", "DIGITAL", "BOLD")) {
            val f = font.lowercase()
            save("f-1cockpit-$f", page(engo("COCKPIT", font = font)))
            save("f-2big-$f", page(engo("BIG", bigOnes, font)))
            save("f-3quad-$f", page(engo("QUAD", quad, font)))
            save("f-4turn-$f", page(engo("TURN", font = font), nav = true))
            save("f-5minimal-$f", page(engo("MINIMAL", font = font)))
        }
    }

    @Test
    fun renderThePages() {
        save("01-cockpit", page(engo("COCKPIT")))
        save("02-cockpit-route", page(engo("COCKPIT"), nav = true))
        save("03-big", page(engo("BIG", listOf(el("SPEED", style = "SMOOTH"), el("LOAD", style = "GRAPH"), el("BATTERY")))))
        save("04-quad", page(engo("QUAD", listOf(
            el("SPEED", style = "GRAPH", window = 5), el("LOAD", style = "BLOCKS"),
            el("MOTOR_TEMP", style = "SMOOTH"), el("BATTERY", style = "NUMBER")))))
        save("05-turn-route", page(engo("TURN"), nav = true))
        save("06-turn-idle", page(engo("TURN")))
        save("07-minimal", page(engo("MINIMAL", listOf(el("SPEED", style = "NUMBER")))))
        save("08-minimal-graph", page(engo("MINIMAL", listOf(el("SPEED", style = "GRAPH", window = 30)))))
        save("09-cockpit-digital", page(engo("COCKPIT", font = "DIGITAL")))
        save("10-cockpit-bold", page(engo("COCKPIT", font = "BOLD")))
        save("11-big-digital", page(engo("BIG", listOf(el("SPEED", style = "NUMBER"), el("LOAD"), el("BATTERY")), "DIGITAL")))
        save("12-quad-bold", page(engo("QUAD", listOf(
            el("SPEED"), el("LOAD", style = "GRAPH"), el("VOLTAGE", style = "SMOOTH"), el("MOTOR_TEMP")), "BOLD")))
        save("13-minimal-route", page(engo("MINIMAL"), nav = true))
        save("14-pwm-high", EngoDemoSource.page(engo("COCKPIT"), u, 40, 55, false, now, 63f))
        save("15-engo2-grey", page(engo("COCKPIT"), nav = true), color = false)
        val zeros = EngoDemoSource.page(engo("COCKPIT"), u, 65, 85, false, now, zeros = true)
        assertTrue("a wheel at rest reads 0", zeros.elements.filterNotNull().all { it.value.trimStart('-').toFloat() == 0f })
        save("17-cockpit-zeros", zeros)
        save("18-quad-digital", page(engo("QUAD", listOf(
            el("SPEED"), el("LOAD", style = "GRAPH"), el("VOLTAGE", style = "SMOOTH"), el("PHASE_CURRENT")), "DIGITAL")))
        save("16-mph-stats", EngoDemoSource.page(
            engo("COCKPIT", listOf(el("SPEED", style = "NUMBER"), el("LOAD", "MAX", style = "NUMBER"),
                el("SPEED", "AVG", style = "GRAPH", window = 60), el("MOTOR_POWER", "SUSTAINED_PEAK"), el("VOLTAGE"))),
            EngoMetrics.Units(speed = "mph", temp = "F", distance = "mi"), 65, 85, true, now, 118f))
    }
}
