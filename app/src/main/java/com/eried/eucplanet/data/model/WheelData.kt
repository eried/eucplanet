package com.eried.eucplanet.data.model

data class WheelData(
    val speed: Float = 0f,
    val voltage: Float = 0f,
    val current: Float = 0f,
    val batteryPercent: Int = 0,
    val battery1Percent: Float = 0f,
    val battery2Percent: Float = 0f,
    val pwm: Float = 0f,
    val torque: Float = 0f,
    /** Motor phase current in A (signed: negative on regen / braking, like
     *  [current] and [torque]). Only some wheels report or expose it; on the P6
     *  it is derived from torque (the wheel sends no phase-current field), so it
     *  stays 0 on wheels that neither send nor derive it. */
    val phaseCurrent: Float = 0f,
    val temperatures: List<Float> = emptyList(),
    val maxTemperature: Float = 0f,
    val tripDistance: Float = 0f,        // km
    val totalDistance: Float = 0f,       // km
    val pitchAngle: Float = 0f,
    val rollAngle: Float = 0f,
    /** Rider GPS latitude in degrees. 0 when there is no fix / not recorded. */
    val latitude: Double = 0.0,
    /** Rider GPS longitude in degrees. 0 when there is no fix / not recorded. */
    val longitude: Double = 0.0,
    /** Battery percent of the paired external GPS box (RaceBox / Dragy), or -1
     *  when none is paired / it doesn't report it. Merged in by the Overlay
     *  Studio (like latitude/longitude) so an overlay number can show it; not
     *  wheel telemetry, so it stays -1 on the plain wheel stream. */
    val externalGpsBatteryPercent: Int = -1,
    /** Ground speed in km/h from the paired external GPS box (RaceBox / Dragy),
     *  or -1 when none is paired / no fresh sample. Merged in like the battery /
     *  lat / long above so an overlay or HUD element can show it. */
    val externalGpsSpeedKmh: Float = -1f,
    /** Ground speed in km/h from the PHONE's fused GPS, or -1 when no fix.
     *  Distinct from externalGpsSpeedKmh (a paired box); merged in like lat/long
     *  so an overlay / HUD element can show the phone GPS speed. */
    val gpsSpeedKmh: Float = -1f,
    /** Altitude in metres above sea level from the PHONE's fused GPS, or NaN
     *  when there is no fix or the fix carries no altitude. Merged in beside
     *  lat/long and gpsSpeedKmh, and NaN rather than -1 because a rider below
     *  sea level is a real reading, not a missing one. */
    val gpsAltitudeM: Float = Float.NaN,
    /** Running trip-meter distance in km (the connect-scoped car odometer), or -1
     *  when not merged in. Not wheel telemetry, so it stays -1 on the plain wheel
     *  stream; the Overlay Studio / HUD merge it in like gpsSpeedKmh so an overlay
     *  number can show it. */
    val tripMeterKm: Float = -1f,
    /** Phone IMU acceleration magnitude in g, 0 for trips recorded before this. */
    val gForce: Float = 0f,
    /** Phone IMU lateral acceleration in g (+right). 0 for trips recorded before this. */
    val accelX: Float = 0f,
    /** Phone IMU forward acceleration in g (+forward). 0 for trips recorded before this. */
    val accelY: Float = 0f,
    /** Forward G estimated from wheel-speed change (dv/dt / g), orientation-independent:
     *  unlike the IMU axes above. Drives the FORWARD_G dashboard metric. */
    val forwardGFromSpeed: Float = 0f,
    val batteryPower: Int = 0,
    val motorPower: Int = 0,
    /** Wh drawn from the battery since connect (discharge integral) - the same
     *  connection-scoped energy the Battery screen shows as "used". Backs the
     *  WH_CONSUMED "Energy" tile and, over trip distance, WH_PER_KM. 0 until the
     *  first integration tick. */
    val whConsumed: Float = 0f,
    /** Wh returned to the battery since connect (regen / charge integral). Backs
     *  the REGEN_WH "Regen" tile. */
    val whRegen: Float = 0f,
    /**
     * Net Wh per km over the rider's dashboard rolling window: energy out minus
     * regen, divided by the distance covered in that same window. Both ends come
     * from the same two cumulative series, so the numerator and denominator
     * cannot describe different stretches of road. NaN until the window holds
     * enough distance to divide by. Backs WH_PER_KM.
     *
     * A rate, unlike [whConsumed], which is a running total. The two do not
     * reconcile by division and are not meant to: this one answers what the
     * ride is costing right now, and it moves when the road tilts.
     */
    val whPerKmRecent: Float = Float.NaN,
    /**
     * Remaining range in km at the recent consumption rate, or NaN while either
     * that rate or the pack's Wh-per-percent is still unknown. Backs
     * RANGE_ESTIMATE.
     *
     * Wh-per-percent is learned from this ride rather than from a pack size we
     * do not know: energy spent against battery percent dropped. It needs a few
     * percent of drop before it says anything, and it is only ever as good as
     * the wheel's own percentage.
     */
    val rangeKmEstimate: Float = Float.NaN,
    val dynamicSpeedLimit: Float = 0f,
    val dynamicCurrentLimit: Float = 0f,
    val lightOn: Boolean = false,
    /** True when the wheel reports it is charging via an explicit firmware flag
     *  (InMotion V14/V12 state-byte bit 7, KingSong 0xB9). Inference-only
     *  families (Begode/Veteran/Ninebot/InMotion V1) leave this false; charging
     *  for them is derived from sustained negative current in WheelRepository. */
    val charging: Boolean = false,
    /** Tire pressure in kPa from a bound TPMS sensor the wheel relays (InMotion
     *  P6: realtime 0x87 frame, u16le at body[78]). 0 = no sensor / not reported.
     *  Display converts: psi = kPa x 0.145038, bar = kPa / 100. */
    val tirePressureKpa: Float = 0f,
    val pcMode: Int = -1,  // 0=lock, 1=drive, 2=shutdown, 3=idle (-1=unknown/no telemetry yet)
    /**
     * Lock state as the wheel reports it in its own telemetry, or null when the
     * family does not report one.
     *
     * Separate from [pcMode] because it is not always in the work-mode field.
     * On InMotion V1 the work mode reads a constant "Drive" whether the wheel is
     * locked or not; the lock lives in its own flag byte, which is why reading
     * lock out of pcMode never worked. Null rather than false so a family that
     * says nothing is never mistaken for one saying "unlocked".
     */
    val lockedReported: Boolean? = null,
    /** Tiltback / max-speed threshold the wheel firmware reports in its telemetry,
     *  in km/h. -1 = the active adapter doesn't surface this. Used so the
     *  Settings UI reflects what the wheel is actually enforcing (set via the
     *  vendor app on Veteran, where our app has no write command). */
    val wheelMaxSpeedKmh: Float = -1f,
    /** Alarm-speed threshold the wheel firmware reports, in km/h. -1 = unknown. */
    val wheelAlarmSpeedKmh: Float = -1f,
    /** BLE link RSSI in dBm (negative), read off the GATT link (not wheel
     *  telemetry). 0 = unknown / not yet read. Backs the BT_RSSI metric. */
    val rssiDbm: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * Carry the fields no wheel reports across a telemetry frame.
     *
     * A decoded frame is built from what the parser returned, so every field the
     * parser does not fill arrives at its default. Most of those are wheel
     * fields the wheel simply did not send, and a default is the honest answer.
     * These are not: they are worked out on the phone, by loops in
     * WheelRepository that run on their own cadence, so a frame that resets them
     * is throwing away the only copy.
     *
     * That is what emptied the dashboard's CONSUMPTION and RANGE tiles. The
     * ride-efficiency loop wrote them once a second and the next frame, 250 ms
     * later on an InMotion V1, put NaN back, so the tiles answered for an
     * instant a few times a ride and read blank the rest of the time. The
     * g-force fields sit behind the same defect at 8 and 10 Hz.
     */
    fun carryPhoneSideFrom(previous: WheelData): WheelData = copy(
        gForce = previous.gForce,
        accelX = previous.accelX,
        accelY = previous.accelY,
        forwardGFromSpeed = previous.forwardGFromSpeed,
        whPerKmRecent = previous.whPerKmRecent,
        rangeKmEstimate = previous.rangeKmEstimate,
    )
}
