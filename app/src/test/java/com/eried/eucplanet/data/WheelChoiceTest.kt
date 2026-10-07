package com.eried.eucplanet.data

import com.eried.eucplanet.data.repository.TripDerive
import com.eried.eucplanet.data.repository.WheelChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The change-wheel picker files a trip where eucviewer files it.
 *
 * eucviewer labels a wheel by brand and model, falls back to the advertised
 * BLE name, shows the MAC as a last resort, and groups by label plus serial
 * or MAC. Picking a known wheel there copies the whole identity onto the
 * trip; a custom entry is a name and nothing else. The picker keyed on the
 * BLE name alone, which showed one physical V14 under three labels across
 * the two tools once the recorder started writing brand and model and the
 * wheel had been renamed.
 */
class WheelChoiceTest {

    @get:Rule val tmp = TemporaryFolder()

    // --- the label, eucviewer's formula ----------------------------------

    @Test fun `brand and model make the label`() {
        assertEquals("Inmotion V12", WheelChoice(name = "V12-9F3A", brand = "Inmotion", model = "V12").label)
    }

    @Test fun `a model that already names its brand is not doubled`() {
        // The new recorder writes model = "InMotion V14 50GB" with brand
        // "InMotion": eucviewer shows "InMotion V14 50GB", not "InMotion
        // InMotion V14 50GB". Erwin's 22 newest trips are exactly this.
        val w = WheelChoice(name = "Adventure-E0000298", brand = "InMotion", model = "InMotion V14 50GB")
        assertEquals("InMotion V14 50GB", w.label)
    }

    @Test fun `without a model the BLE name is the label`() {
        // The old recorder wrote only a name: 297 of Erwin's trips.
        assertEquals("Inmotion V14", WheelChoice(name = "Inmotion V14").label)
    }

    @Test fun `with nothing but a MAC the MAC is the label`() {
        assertEquals("Wheel AABBCCDDEEFF", WheelChoice(mac = "AABBCCDDEEFF").label)
    }

    @Test fun `the key tells two wheels of one model apart`() {
        val a = WheelChoice(brand = "Inmotion", model = "V14", mac = "AAAAAAAAAAAA")
        val b = WheelChoice(brand = "Inmotion", model = "V14", mac = "BBBBBBBBBBBB")
        assertEquals(a.label, b.label)
        assertTrue(a.key != b.key)
    }

    @Test fun `the cache JSON round-trips`() {
        val w = WheelChoice(name = "V12-9F3A", mac = "aa:bb:cc:dd:ee:ff", brand = "Inmotion", model = "V12", serial = "S1")
        val back = WheelChoice.fromJson(w.toJson())!!
        assertEquals("AABBCCDDEEFF", back.mac)
        assertEquals(w.copy(mac = "AABBCCDDEEFF"), back)
        assertEquals(null, WheelChoice.fromJson("{}"))
    }

    // --- what reaches the file -------------------------------------------

    private fun fileWith(vararg extra: String) = tmp.newFile().apply {
        val rows = extra.mapIndexed { i, e ->
            "2026-02-01 10:00:0$i.000,10,80,1,80,90,52.1,4.3,100,$e"
        }
        writeText("date,speed,voltage,current,power,battery,latitude,longitude,mileage,extra\n" +
            rows.joinToString("\n") + "\n")
    }

    @Test fun `a known wheel copies its whole identity into the file`() {
        // Reassign an old name-only trip to the fully identified wheel: every
        // line eucviewer reads arrives, so it lands in the same group there.
        val src = fileWith("wheel.name=Inmotion V14", "", "", "", "", "")
        val dest = tmp.newFile()
        val wheel = WheelChoice(name = "Adventure-E0000298", mac = "aa:bb:cc:dd:ee:ff",
            brand = "InMotion", model = "InMotion V14 50GB", serial = "S1")
        TripDerive.rewriteWheelIdentity(src, dest, wheel.extraFields())
        val t = dest.readText()
        for (line in listOf("wheel.name=Adventure-E0000298", "wheel.mac=AABBCCDDEEFF",
                "wheel.brand=InMotion", "wheel.model=InMotion V14 50GB", "wheel.serial=S1")) {
            assertTrue("missing $line", t.contains(line))
        }
        assertTrue("the old name survived", !t.contains("Inmotion V14\n"))
    }

    @Test fun `a custom wheel is one line, and the old identity goes`() {
        // eucviewer's custom entry is { name } and nothing else. Leaving the
        // old wheel's model behind would keep the trip filed under it there.
        val src = fileWith("wheel.name=Adventure-E0000298", "wheel.mac=AABBCCDDEEFF",
            "wheel.brand=InMotion", "wheel.model=InMotion V14 50GB", "wheel.firmware=2.1")
        val dest = tmp.newFile()
        TripDerive.rewriteWheelIdentity(src, dest, WheelChoice(name = "Lynx").extraFields())
        val t = dest.readText()
        assertTrue(t.contains("wheel.name=Lynx"))
        for (stale in listOf("wheel.mac=", "wheel.brand=", "wheel.model=", "wheel.firmware=")) {
            assertTrue("$stale survived a custom reassignment", !t.contains(stale))
        }
    }

    @Test fun `a reconnect block is rewritten too, not just the first`() {
        // Reconnects re-emit the identity; eucviewer attributes by row span,
        // so a second block left on the old wheel could out-vote the first.
        val src = fileWith("wheel.name=Old", "", "wheel.name=Old", "")
        val dest = tmp.newFile()
        TripDerive.rewriteWheelIdentity(src, dest, WheelChoice(name = "New").extraFields())
        val t = dest.readText()
        assertEquals(2, t.split("wheel.name=New").size - 1)
        assertTrue(!t.contains("wheel.name=Old"))
    }
}
