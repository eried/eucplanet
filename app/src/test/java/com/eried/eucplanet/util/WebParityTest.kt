package com.eried.eucplanet.util

import com.eried.eucplanet.data.store.DefaultUnits
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * The app against the rules it shares with eucstats and the Trip Viewer.
 *
 * `web_parity_rules.json` is a copy of eucstats `tests/parity_rules.json`
 * (the viewer keeps the same copy in `tools/parity_rules.json`). One rider
 * uses all three, so the same phone or browser language has to land on the
 * same language everywhere, under the same name in the picker. Change the
 * file only together with the other two projects; docs/WEB_PARITY.md is
 * the readable version.
 */
class WebParityTest {

    private val rules = JSONObject(
        checkNotNull(javaClass.classLoader?.getResource("web_parity_rules.json")) {
            "web_parity_rules.json is missing from the test resources"
        }.readText()
    )
    private val before: Locale = Locale.getDefault()

    @After fun restoreLocale() = Locale.setDefault(before)

    @Test fun `the picker lists the same languages under the same names`() {
        val agreed = rules.getJSONObject("languages")
        val want = agreed.keys().asSequence().associateWith { agreed.getString(it) }.toSortedMap()
        val have = LocaleHelper.SUPPORTED.associate { it.tag to it.nativeName }.toSortedMap()
        assertEquals(want, have)
    }

    @Test fun `a system language lands on the code the web sites pick`() {
        val cases = rules.getJSONArray("language_mapping")
        val wrong = mutableListOf<String>()
        for (i in 0 until cases.length()) {
            val tag = cases.getJSONArray(i).getString(0)
            val want = cases.getJSONArray(i).getString(1)
            Locale.setDefault(Locale.forLanguageTag(tag))
            val got = LocaleHelper.detectSystemLanguage()
            if (got != want) wrong += "$tag: web $want, app $got"
        }
        assertEquals("system language, what the web picks, what the app picks", emptyList<String>(), wrong)
    }

    @Test fun `starting units are the three the web sites start in`() {
        fun code(u: DefaultUnits.Units) =
            "${u.distance}-${u.temp.lowercase()}-${u.altitude}"
        val codes = rules.getJSONObject("units").getJSONArray("codes")
        val known = (0 until codes.length()).map { codes.getString(it) }.toSet()
        // The web picks by timezone and the app by country, so the cases are
        // countries here; the outcomes are the web's.
        val want = mapOf(
            "US" to "mi-f-ft", "PR" to "mi-f-ft", "GU" to "mi-f-ft", "LR" to "mi-f-ft",
            "GB" to "mi-c-m", "JE" to "mi-c-m", "GG" to "mi-c-m", "IM" to "mi-c-m", "MM" to "mi-c-m",
            "NO" to "km-c-m", "IE" to "km-c-m", "CA" to "km-c-m", "MX" to "km-c-m",
            "AU" to "km-c-m", "JP" to "km-c-m", "" to "km-c-m",
        )
        for ((country, expected) in want) {
            val got = code(DefaultUnits.pick(country, country))
            assertEquals("country '$country'", expected, got)
            assert(got in known) { "$got is not one of the shared unit codes" }
        }
        assertEquals(rules.getJSONObject("units").getString("default"), code(DefaultUnits.METRIC))
    }
}
