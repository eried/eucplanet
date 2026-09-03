package com.eried.eucplanet.tpms

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place that knows the tyre's pressure, whoever measured it.
 *
 * Pressure used to have exactly one producer: [com.eried.eucplanet.ble
 * .InMotionV2Parser] reading the P6's realtime frame, straight onto WheelData
 * where every surface picked it up. That works until there are two sources,
 * and then "the tire pressure" needs somewhere to be decided rather than being
 * whatever wrote last.
 *
 * Sources push in here ([submitWheel], [submitPaired]) and [current] answers.
 * The rules live in [TpmsPolicy] so they are testable without a sensor.
 *
 * Not yet the value the dashboard reads: WheelData still carries the wheel's
 * own field and every tile, alarm, widget and HUD reads that. Moving them over
 * is worth doing when a second source actually exists, so the migration can be
 * checked against a real sensor instead of a guess about one.
 */
@Singleton
class TpmsRepository @Inject constructor(
    private val pairing: TpmsPairingStore,
) {

    /** For tests, which want the rules without a settings store behind them. */
    constructor() : this(TpmsPairingStore.None)

    private var wheelReading: TpmsReading? = null
    private var pairedReading: TpmsReading? = null

    private val _pairedAddress = MutableStateFlow<String?>(null)

    /**
     * The sensor the rider owns, kept so the section has something to show and
     * something to remove. One at a time: a wheel has one tyre, and two would
     * leave "the tire pressure" meaning whichever spoke last.
     */
    val pairedAddress: StateFlow<String?> = _pairedAddress.asStateFlow()

    fun adopt(address: String) {
        if (_sensors.value.any { it.address == address }) return
        _sensors.value = _sensors.value + SensorState(address)
        _pairedAddress.value = _sensors.value.firstOrNull()?.address
        pairing.saveAll(_sensors.value.map { it.address })
    }

    /** Forget one cap, leaving the rider's other wheels alone. */
    fun forget(address: String) {
        _sensors.value = _sensors.value.filterNot { it.address == address }
        _pairedAddress.value = _sensors.value.firstOrNull()?.address
        pairing.saveAll(_sensors.value.map { it.address })
        recompute(System.currentTimeMillis())
    }

    private val _current = MutableStateFlow<TpmsReading?>(null)

    /** The reading to show, or null when nothing fresh is reporting. */
    val current: StateFlow<TpmsReading?> = _current.asStateFlow()

    /**
     * Everything one cap is currently saying.
     *
     * Temperature and battery sit beside the pressure rather than folded into
     * it: a cap measures the air it sits in, so a hot tyre reads high, and a
     * rider looking at a pressure that drifted wants to know whether the tyre
     * or the weather moved.
     *
     * [kpa] is null until the sensor has spoken since the app started. That is
     * not a fault: these caps transmit when the pressure moves and stay quiet
     * on a settled tyre, so a fresh app legitimately has a paired sensor and
     * no number for it.
     */
    data class SensorState(
        val address: String,
        val kpa: Float? = null,
        val tempC: Float? = null,
        val volts: Float? = null,
        val atMs: Long = 0L,
    )

    private val _sensors = MutableStateFlow<List<SensorState>>(emptyList())

    /** Every paired cap, in the order the rider added them. */
    val sensors: StateFlow<List<SensorState>> = _sensors.asStateFlow()

    init {
        // Below _pairedAddress on purpose: initialisers run in declaration
        // order, so loading from up beside the constructor read a field that
        // did not exist yet and threw the moment a store answered straight
        // away.
        //
        // A sensor paired on a previous run is still the rider's sensor. Only
        // taken if nothing has been adopted since, so a scan that found one
        // while this was still loading is not overwritten by an older answer.
        pairing.load { saved ->
            if (saved.isNotEmpty() && _sensors.value.isEmpty()) {
                _sensors.value = saved.map { SensorState(it) }
                _pairedAddress.value = saved.first()
            }
        }
    }

    private fun mutate(address: String, block: (SensorState) -> SensorState) {
        val list = _sensors.value
        val i = list.indexOfFirst { it.address == address }
        _sensors.value =
            if (i >= 0) list.toMutableList().also { it[i] = block(it[i]) }
            else list + block(SensorState(address))
    }

    fun submitPairedTemp(address: String, celsius: Float?) {
        if (celsius != null) mutate(address) { it.copy(tempC = celsius) }
    }

    fun submitPairedVolts(address: String, volts: Float?) {
        if (volts != null) mutate(address) { it.copy(volts = volts) }
    }

    /** Convenience for the surfaces that only want a number; 0 when silent. */
    val pressureKpa: StateFlow<Float>
        get() = _pressureKpa.asStateFlow()
    private val _pressureKpa = MutableStateFlow(0f)

    /** The wheel relayed a value from a sensor bound to it. */
    fun submitWheel(kpa: Float, nowMs: Long = System.currentTimeMillis()) {
        wheelReading = TpmsPolicy.readingOf(kpa, TpmsSource.WHEEL, nowMs) ?: wheelReading
        recompute(nowMs)
    }

    /** A directly paired sensor reported. */
    /**
     * A sensor reported. The first one seen becomes the rider's; after that
     * only that one is listened to.
     *
     * It used to take whichever packet arrived, so two caps in range - a
     * second tyre, a neighbour's car - traded the reading back and forth and
     * the pressure looked wrong because it was two tyres at once.
     */
    fun submitPaired(kpa: Float, address: String? = null, nowMs: Long = System.currentTimeMillis()) {
        if (address != null) {
            // Every paired cap is heard, not just the first one. A rider with
            // three wheels has three caps, and the one that just spoke is the
            // one with something to say.
            if (_sensors.value.none { it.address == address }) adopt(address)
            mutate(address) { it.copy(kpa = kpa, atMs = nowMs) }
        }
        pairedReading = TpmsPolicy.readingOf(kpa, TpmsSource.PAIRED, nowMs) ?: pairedReading
        recompute(nowMs)
    }

    /**
     * Forget a paired sensor, so the wheel's own is believed again.
     *
     * Unpairing is the one thing that should fall back, because the rider
     * asked for it. A paired sensor going quiet must not.
     */
    fun forgetPaired(nowMs: Long = System.currentTimeMillis()) {
        pairedReading = null
        _sensors.value = emptyList()
        _pairedAddress.value = null
        // Forgetting has to outlive the app too, or a deleted sensor comes
        // back on the next launch.
        pairing.saveAll(emptyList())
        recompute(nowMs)
    }

    /**
     * Re-evaluate without new data, so a sensor that stopped reporting stops
     * being shown. Called on a tick by whoever is watching.
     */
    fun refresh(nowMs: Long = System.currentTimeMillis()) = recompute(nowMs)

    /**
     * The sensor currently speaking for the tyre: a cap's address, or null
     * when it is the wheel's own relayed reading (or nothing at all).
     */
    private val _activeAddress = MutableStateFlow<String?>(null)
    val activeAddress: StateFlow<String?> = _activeAddress.asStateFlow()

    /** True while the wheel's own reading is the one being shown. */
    private val _wheelIsActive = MutableStateFlow(false)
    val wheelIsActive: StateFlow<Boolean> = _wheelIsActive.asStateFlow()

    private fun recompute(nowMs: Long) {
        // Which source speaks for the tyre. Sticky on purpose: see
        // TpmsPolicy.pickActive.
        val capCandidates = _sensors.value
            .filter { it.kpa != null }
            .map { TpmsPolicy.Candidate(it.address, TpmsSource.PAIRED, it.atMs) }
            // A reading submitted without an address still counts as external.
            // Not every caller has one, and dropping it here would quietly
            // hand the tyre back to the wheel.
            .ifEmpty {
                listOfNotNull(
                    pairedReading?.let { TpmsPolicy.Candidate(null, TpmsSource.PAIRED, it.atMs) }
                )
            }
        val candidates = capCandidates +
            listOfNotNull(wheelReading?.let { TpmsPolicy.Candidate(null, TpmsSource.WHEEL, it.atMs) })
        val active = TpmsPolicy.pickActive(candidates, nowMs, _activeAddress.value)
        _activeAddress.value = active?.address
        _wheelIsActive.value = active?.source == TpmsSource.WHEEL

        // The published reading follows whichever sensor is active, so every
        // surface agrees with the badge in settings.
        val picked = when {
            active == null -> null
            active.source == TpmsSource.WHEEL -> wheelReading
            active.address == null -> pairedReading
            else -> _sensors.value.firstOrNull { it.address == active.address }
                ?.let { s -> s.kpa?.let { TpmsReading(it, TpmsSource.PAIRED, s.atMs) } }
        }
        _current.value = picked
        _pressureKpa.value = picked?.kpa ?: 0f
    }
}
