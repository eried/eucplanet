package com.eried.eucplanet.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Rule 12 says every user-facing string is translated into every locale the app
 * ships. That is easy to honour on the day and easy to forget on the next one,
 * so this reads the resource files rather than trusting anybody's memory.
 *
 * It also checks the format arguments, because a translation that drops a
 * `%1$s` does not fail to build: it throws at the moment a rider asks a
 * question the app cannot answer, which is the worst possible moment.
 */
class VoiceStringCoverageTest {

    private val resDir = File("src/main/res")

    private val voiceKeys = listOf(
        "action_chip_voice_listen",
        "voice_commands_title",
        "voice_commands_enable_desc",
        "voice_commands_bind_desc",
        "voice_command_vocabulary",
        "voice_listening",
        "voice_answer_off",
        "voice_answer_unsupported",
        "voice_answer_nodata",
        "voice_answer_setup",
        "voice_answer_no_mic_permission",
        "voice_mic_grant",
        "voice_answer_unknown",
        "voice_help_terms",
        "voice_stat_max_terms",
        "voice_stat_min_terms",
        "voice_stat_avg_terms",
        "voice_stat_peak_terms",
        "voice_stat_answer",
        "voice_answer_examples",
        "voice_special_connected",
        "voice_special_not_connected",
        "voice_special_uptime",
        "voice_special_weather",
        "voice_weather_great",
        "voice_weather_good",
        "voice_weather_ok",
        "voice_weather_poor",
        "voice_weather_bad",
        "voice_weather_snow",
        "voice_weather_rain",
        "voice_weather_wind",
        "voice_weather_cold",
        "voice_weather_hot",
        "voice_weather_night",
        "voice_special_dark",
        "voice_special_daylight",
        "voice_special_daylight_all_day",
        "voice_special_nav",
        "voice_special_nav_none",
        "voice_special_last_trip",
        "voice_special_no_trips",
        "voice_duration_h_m",
        "voice_duration_m",
        "voice_sp_weather_terms",
        "voice_sp_daylight_terms",
        "voice_sp_connected_terms",
        "voice_sp_uptime_terms",
        "voice_sp_nav_terms",
        "voice_sp_last_trip_terms",
        "voice_sp_report_terms",
        "voice_action_confirm",
        "voice_action_cancelled",
        "voice_yes_terms",
        "voice_act_light_on_terms",
        "voice_act_light_off_terms",
        "voice_act_lock_terms",
        "voice_act_unlock_terms",
        "voice_act_horn_terms",
        "voice_act_record_start_terms",
        "voice_act_record_stop_terms",
        "voice_act_reset_trip_terms",
        "voice_answer_mic_busy",
        "voice_answer_which",
    )

    /** Every values* directory that carries a translation. */
    private fun localeDirs(): List<File> =
        resDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("values") }
            ?.filter { File(it, "strings.xml").exists() }
            ?.sortedBy { it.name }
            ?: emptyList()

    private fun stringsIn(dir: File): Map<String, String> {
        val text = File(dir, "strings.xml").readText()
        val re = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        return re.findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun `the app really does ship the locales this test thinks it does`() {
        // A guard on the guard: if the res folder moves, the loop below would
        // pass by checking nothing at all.
        val dirs = localeDirs()
        assertTrue("found only ${dirs.size} locale folders under $resDir", dirs.size >= 20)
    }

    @Test
    fun `every voice string exists in every locale`() {
        val missing = mutableListOf<String>()
        for (dir in localeDirs()) {
            val strings = stringsIn(dir)
            for (key in voiceKeys) {
                if (strings[key].isNullOrBlank()) missing += "${dir.name}/$key"
            }
        }
        assertEquals("untranslated: $missing", emptyList<String>(), missing)
    }

    @Test
    fun `no translation is left as the English text`() {
        // Copying English in is the usual way a locale looks complete and is
        // not. Short words legitimately coincide across languages, so this only
        // looks at the sentences, where a match is not a coincidence.
        val sentences = listOf(
            "voice_answer_off", "voice_answer_unsupported",
            "voice_answer_unknown", "voice_commands_enable_desc",
        )
        val english = stringsIn(File(resDir, "values"))
        val copied = mutableListOf<String>()
        for (dir in localeDirs()) {
            if (dir.name == "values") continue
            val strings = stringsIn(dir)
            for (key in sentences) {
                if (strings[key] == english[key]) copied += "${dir.name}/$key"
            }
        }
        assertEquals("still English: $copied", emptyList<String>(), copied)
    }

    @Test
    fun `every translation keeps the format arguments it is given`() {
        // A dropped %1$s does not fail the build. It throws when a rider asks
        // something the app cannot answer, which is the worst moment for it.
        val argRe = Regex("""%\d\$[sd]""")
        val english = stringsIn(File(resDir, "values"))
        val broken = mutableListOf<String>()
        for (dir in localeDirs()) {
            val strings = stringsIn(dir)
            for (key in voiceKeys) {
                val expected = argRe.findAll(english[key] ?: "").map { it.value }.toSortedSet()
                val actual = argRe.findAll(strings[key] ?: "").map { it.value }.toSortedSet()
                if (expected != actual) broken += "${dir.name}/$key expected $expected got $actual"
            }
        }
        assertEquals("format arguments lost: $broken", emptyList<String>(), broken)
    }
}
