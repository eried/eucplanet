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
            if (saved != null && _pairedAddress.value == null) _pairedAddress.value = saved
        }
    }

    fun adopt(address: String) {
        if (_pairedAddress.value == address) return
        _pairedAddress.value = address
        pairing.save(address)
    }

    private val _current = MutableStateFlow<TpmsReading?>(null)

    /** The reading to show, or null when nothing fresh is reporting. */
    val current: StateFlow<TpmsReading?> = _current.asStateFlow()

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
            val owned = _pairedAddress.value
            if (owned == null) adopt(address)
            else if (owned != address) return
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
        _pairedAddress.value = null
        // Forgetting has to outlive the app too, or a deleted sensor comes
        // back on the next launch.
        pairing.save(null)
        recompute(nowMs)
    }

    /**
     * Re-evaluate without new data, so a sensor that stopped reporting stops
     * being shown. Called on a tick by whoever is watching.
     */
    fun refresh(nowMs: Long = System.currentTimeMillis()) = recompute(nowMs)

    private fun recompute(nowMs: Long) {
        val picked = TpmsPolicy.pick(pairedReading, wheelReading, nowMs)
        _current.value = picked
        _pressureKpa.value = picked?.kpa ?: 0f
    }
}
