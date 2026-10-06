package com.eried.eucplanet.service

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * One piece of a spoken announcement: a report from [VoiceReportPlan.KNOWN]
 * read now or as a statistic over the stats window, or the rider's own words.
 */
data class VoicePill(
    val item: String,
    val stat: Stat = Stat.NOW,
    /** The rider's words, for [MESSAGE] only. */
    val text: String = "",
) {
    /** The statistics a spoken question already knows how to say. */
    enum class Stat { NOW, MAX, MIN, AVG, PEAK }

    companion object {
        const val MESSAGE = "Message"
        /** Long enough for a reminder, short enough to keep a periodic announcement brief. */
        const val MESSAGE_MAX = 60
    }
}

/**
 * The saved form of a pill list, one string in [com.eried.eucplanet.data.model.VoiceReportSettings].
 *
 * `Speed:NOW|Speed:MAX|Message:Drink+water`. Message text is URL-encoded so
 * a rider's comma, colon or bar cannot split it. Blank means "never edited",
 * which [VoiceReportPlan.pills] answers from the older per-report switches;
 * an emptied list is saved as [EMPTY] so it stays empty.
 */
object VoicePills {
    const val EMPTY = "NONE"

    fun encode(pills: List<VoicePill>): String =
        if (pills.isEmpty()) EMPTY
        else pills.joinToString("|") { p ->
            if (p.item == VoicePill.MESSAGE) "${VoicePill.MESSAGE}:" + URLEncoder.encode(p.text, "UTF-8")
            else "${p.item}:${p.stat.name}"
        }

    /**
     * Tolerant on purpose: settings travel through backups and other builds,
     * so an unknown report is dropped and an unknown or unsupported statistic
     * reads as NOW rather than losing the pill.
     */
    fun decode(saved: String): List<VoicePill> {
        if (saved.isBlank() || saved == EMPTY) return emptyList()
        return saved.split("|").mapNotNull { token ->
            val item = token.substringBefore(":")
            val rest = token.substringAfter(":", "")
            when {
                item == VoicePill.MESSAGE -> runCatching { URLDecoder.decode(rest, "UTF-8") }.getOrNull()
                    ?.take(VoicePill.MESSAGE_MAX)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { VoicePill(VoicePill.MESSAGE, text = it) }
                item !in VoiceReportPlan.KNOWN -> null
                else -> {
                    val stat = VoicePill.Stat.entries.firstOrNull { it.name == rest } ?: VoicePill.Stat.NOW
                    VoicePill(item, if (VoiceReportPlan.statKey(item) != null) stat else VoicePill.Stat.NOW)
                }
            }
        }
    }
}
