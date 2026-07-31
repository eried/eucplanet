package com.eried.eucplanet.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eried.eucplanet.R
import com.eried.eucplanet.data.db.AlarmDao
import com.eried.eucplanet.data.model.AlarmComparator
import com.eried.eucplanet.data.model.AlarmMetric
import com.eried.eucplanet.data.model.AlarmRule
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.service.TonePlayer
import com.eried.eucplanet.service.VoiceService
import com.eried.eucplanet.util.VibratorHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AlarmViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val alarmDao: AlarmDao,
    private val tonePlayer: TonePlayer,
    private val voiceService: VoiceService,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    companion object {
        private val RADAR_METRIC_NAMES = setOf(
            AlarmMetric.RADAR_DISTANCE.name,
            AlarmMetric.RADAR_APPROACH_SPEED.name
        )
    }

    val speedUnit: StateFlow<String> = settingsRepository.settings
        .map { com.eried.eucplanet.util.Units.effectiveSpeedUnit(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "kmh")

    val distanceUnit: StateFlow<String> = settingsRepository.settings
        .map { com.eried.eucplanet.util.Units.effectiveDistanceUnit(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "km")

    val tempUnit: StateFlow<String> = settingsRepository.settings
        .map { com.eried.eucplanet.util.Units.effectiveTempUnit(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "C")

    /**
     * The rider's chosen voice locale, fed to the alarm editor so a newly
     * created alarm's default voice template is in the language the TTS
     * engine actually speaks (not the UI language - users with English UI
     * + Spanish voice should get "¡Atención!"). Placeholders like {metric}
     * stay literal regardless of locale.
     */
    val voiceLocale: StateFlow<String> = settingsRepository.settings
        .map { it.voiceLocale }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "en_US")


    private val vibratorHelper = VibratorHelper(context)

    val rules: StateFlow<List<AlarmRule>> = alarmDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** A metric and its rules (most-severe first) -- the unit of priority. */
    data class MetricGroup(val metric: String, val rules: List<AlarmRule>)

    /** Severity score for ordering: higher threshold is more severe for ">=",
     *  lower threshold (negated) is more severe for "<". */
    private fun severityOf(rule: AlarmRule): Float =
        if (AlarmComparator.parse(rule.comparator) == AlarmComparator.LESS_THAN) -rule.threshold
        else rule.threshold

    /** Within a group, most severe first: higher threshold for ">=", lower for "<". */
    private fun severityComparator(): Comparator<AlarmRule> =
        compareByDescending { severityOf(it) }

    /** Group rules by metric (most-severe first inside each); order the groups by
     *  priority = the lowest sortOrder in each group (what the rider dragged).
     *  This is both the display order and the engine's group-priority order. */
    private fun buildGroups(list: List<AlarmRule>): List<MetricGroup> =
        list.groupBy { it.metric }
            .map { (metric, rs) -> metric to rs.sortedWith(severityComparator()) }
            .sortedBy { (_, rs) -> rs.minOf { it.sortOrder } }
            .map { (metric, rs) -> MetricGroup(metric, rs) }

    val groupedRules: StateFlow<List<MetricGroup>> = rules
        .map { buildGroups(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Order rules read top-to-bottom the way [autoSmartSort] would: grouped by
     * metric (enum order), most severe first (higher threshold for `>=`, lower
     * for `<`). Shared by the sort action and the "already sorted?" check so the
     * Auto-sort button can disable itself when there's nothing to do.
     */
    private fun sortComparator(): Comparator<AlarmRule> {
        val metricOrder = AlarmMetric.entries.withIndex().associate { (i, m) -> m.name to i }
        return compareBy<AlarmRule> { metricOrder[it.metric] ?: Int.MAX_VALUE }
            .thenByDescending { severityOf(it) }
    }

    /** True when the list is already in auto-sort order (so Auto-sort is a no-op
     *  and its button should be disabled). Recomputed on every add/remove/swap. */
    val rulesSorted: StateFlow<Boolean> = rules
        .map { list -> list.size < 2 || list == list.sortedWith(sortComparator()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    /**
     * The radar alarm metrics (RADAR_DISTANCE, RADAR_APPROACH_SPEED) only
     * show up in the New-alarm metric dropdown when this is true. The flag
     * stays sticky for users who restored a backup with radar rules or who
     * temporarily unpaired their radar, the rule editor never hides a
     * metric that an existing rule is using.
     */
    val showRadarMetrics: StateFlow<Boolean> = combine(
        settingsRepository.settings.map { it.radarAddress != null },
        alarmDao.observeAll().map { it.any { r -> r.metric in RADAR_METRIC_NAMES } }
    ) { paired, hasRadarRule -> paired || hasRadarRule }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun addRule(rule: AlarmRule) {
        viewModelScope.launch {
            val order = alarmDao.nextSortOrder()
            alarmDao.insert(rule.copy(sortOrder = order))
        }
    }

    fun updateRule(rule: AlarmRule) {
        viewModelScope.launch { alarmDao.update(rule) }
    }

    fun deleteRule(rule: AlarmRule) {
        viewModelScope.launch { alarmDao.delete(rule) }
    }

    fun moveUp(rule: AlarmRule) {
        viewModelScope.launch {
            val all = rules.value.toMutableList()
            val idx = all.indexOfFirst { it.id == rule.id }
            if (idx > 0) {
                val prev = all[idx - 1]
                alarmDao.update(rule.copy(sortOrder = prev.sortOrder))
                alarmDao.update(prev.copy(sortOrder = rule.sortOrder))
            }
        }
    }

    fun moveDown(rule: AlarmRule) {
        viewModelScope.launch {
            val all = rules.value.toMutableList()
            val idx = all.indexOfFirst { it.id == rule.id }
            if (idx >= 0 && idx < all.size - 1) {
                val next = all[idx + 1]
                alarmDao.update(rule.copy(sortOrder = next.sortOrder))
                alarmDao.update(next.copy(sortOrder = rule.sortOrder))
            }
        }
    }

    /** Drag-to-reorder: move the rule at [from] to [to] and renumber sortOrder
     *  so the new order persists. Indices are into the currently displayed list. */
    fun moveRule(from: Int, to: Int) {
        viewModelScope.launch {
            val list = rules.value.toMutableList()
            if (from !in list.indices || to !in list.indices || from == to) return@launch
            list.add(to, list.removeAt(from))
            list.forEachIndexed { i, r -> if (r.sortOrder != i) alarmDao.update(r.copy(sortOrder = i)) }
        }
    }

    /** Drag-to-reorder GROUPS (metric priority). Renumbers every rule's sortOrder
     *  so the new group order -- and most-severe-first within each group -- sticks. */
    fun moveGroup(from: Int, to: Int) {
        viewModelScope.launch {
            val groups = buildGroups(rules.value).toMutableList()
            if (from !in groups.indices || to !in groups.indices || from == to) return@launch
            groups.add(to, groups.removeAt(from))
            var order = 0
            groups.forEach { g ->
                g.rules.forEach { r ->
                    if (r.sortOrder != order) alarmDao.update(r.copy(sortOrder = order))
                    order++
                }
            }
        }
    }

    /**
     * Tidy the list: group rules by metric (in the metric enum's order) and,
     * within each group, put the most severe first -- higher threshold for "≥"
     * rules, lower threshold for "<" rules. Purely cosmetic now that the engine
     * fires the most-relevant rule per metric regardless of order; it just makes
     * the list read the way riders think about it.
     */
    fun autoSmartSort() {
        viewModelScope.launch {
            val sorted = alarmDao.getAll().sortedWith(sortComparator())
            sorted.forEachIndexed { i, r ->
                if (r.sortOrder != i) alarmDao.update(r.copy(sortOrder = i))
            }
        }
    }

    fun previewBeep(frequencyHz: Int, durationMs: Int, count: Int, gapMs: Int, volumePct: Int, transitionPct: Int, waveform: Int = 0, effect: Int = 0) {
        viewModelScope.launch {
            // Play exactly what fires: `count` beeps at the configured pitch, volume,
            // transition shape and timbre (waveform + effect), separated by the
            // configured gap. The modulation ("rises with severity") is auditioned
            // live in the Beep Studio, not here - this button answers "what does my
            // alarm actually sound like".
            tonePlayer.playBeep(frequencyHz, durationMs, count, gapMs, volumePct, durationMs * transitionPct / 100, waveform, effect)
        }
    }

    /** Short tone at a given pitch + volume, for scrubbing the modulation graph.
     *  Uses the studio's current waveform/effect so the scrub matches the timbre. */
    fun previewToneAt(frequencyHz: Int, volumePct: Int) {
        viewModelScope.launch { tonePlayer.playBeep(frequencyHz, 90, 1, 0, volumePct, -1, sWave, sEffect) }
    }

    /** Hold the audio route warm while the alarm editor is open so preview beeps/voice
     *  don't carry the route power-up pop (there's no connected wheel warming it here). */
    fun setPreviewWarm(on: Boolean) = tonePlayer.setPreviewKeepAlive(on)

    // --- Beep Studio live preview ---
    // The studio dialog pushes the live (freq, volume, ...) here. While playing, a
    // continuous streaming tone (TonePlayer.startStream) renders GAPLESSLY and glides
    // as the rider moves the metric / factor sliders, instead of re-firing a fresh
    // one-shot beep every ~80 ms (which left an audible gap between repeats).
    private var studioJob: Job? = null
    private val _studioPlaying = MutableStateFlow(false)
    val studioPlaying: StateFlow<Boolean> = _studioPlaying

    @Volatile private var sFreq = 1000
    @Volatile private var sDur = 300
    @Volatile private var sCount = 1
    @Volatile private var sGap = 100
    @Volatile private var sVol = 100
    @Volatile private var sTrans = 12   // transition ramp, percent of duration
    @Volatile private var sWave = 0     // oscillator shape
    @Volatile private var sEffect = 0   // post effect

    private fun sTransMs() = sDur * sTrans / 100

    fun setStudioTone(frequencyHz: Int, durationMs: Int, count: Int, gapMs: Int, volumePct: Int, transitionPct: Int, waveform: Int = 0, effect: Int = 0) {
        sFreq = frequencyHz; sDur = durationMs; sCount = count; sGap = gapMs; sVol = volumePct; sTrans = transitionPct
        sWave = waveform; sEffect = effect
        // Glide the live continuous tone as the rider drags the sliders (no-op if the
        // current session is a one-shot rather than the stream).
        if (_studioPlaying.value) tonePlayer.updateStream(sFreq, sDur, sGap, sVol, sTransMs(), sWave, sEffect)
    }

    fun toggleStudioPlay(repeat: Boolean = true) {
        if (_studioPlaying.value) { stopStudio(); return }
        _studioPlaying.value = true
        if (sGap <= 0 || repeat) {
            // gap 0 is a continuous tone (repeat is moot); gap>0 + repeat loops the
            // pattern. Either way the streaming tone is gapless and glides live.
            tonePlayer.startStream(sFreq, sDur, sGap, sVol, sTransMs(), sWave, sEffect)
        } else {
            // gap>0 + repeat off: play the pattern once, then clear.
            studioJob = viewModelScope.launch {
                tonePlayer.playBeep(sFreq, sDur, sCount, sGap, sVol, sTransMs(), sWave, sEffect)
                _studioPlaying.value = false
                studioJob = null
            }
        }
    }

    fun stopStudio() {
        if (!_studioPlaying.value && studioJob == null) return
        _studioPlaying.value = false
        tonePlayer.stopStream()
        studioJob?.cancel(); studioJob = null
    }

    override fun onCleared() {
        super.onCleared()
        stopStudio()
    }

    fun previewVoice(text: String, metric: AlarmMetric, threshold: Float) {
        val metricLabel = context.getString(metric.voiceLabelRes)
        // {value} and {threshold} both read back the rider's chosen threshold,
        // so the preview sounds like the alarm they actually configured.
        val thresh = "%.0f".format(threshold)
        val preview = text.ifBlank { context.getString(R.string.alarm_test_default) }
            .replace("{speed}", "35")
            .replace("{battery}", "80")
            .replace("{temp}", "40")
            .replace("{pwm}", "65")
            .replace("{voltage}", "100")
            .replace("{current}", "12")
            .replace("{trip}", "10")
            .replace("{value}", thresh)
            .replace("{metric}", metricLabel)
            .replace("{threshold}", thresh)
        voiceService.speak(preview)
    }

    fun previewVibrate(durationMs: Int) {
        vibratorHelper.oneShot(durationMs.toLong())
    }
}
