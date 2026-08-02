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
    /** Median-filtered phone / external GPS ground speed in km/h, or -1 when no
     *  fix. Back the GPS_SPEED_SMOOTH / EXT_GPS_SPEED_SMOOTH overlay metrics: the
     *  raw fields above spike on a bad Doppler sample, these are noise-rejected
     *  (see GpsSpeedFilter). Merged in like gpsSpeedKmh. */
    val gpsSpeedFilteredKmh: Float = -1f,
    val externalGpsSpeedFilteredKmh: Float = -1f,
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
    /** Forward G estimated from wheel-speed change (dv/dt / g) — orientation-independent,
     *  unlike the IMU axes above. Drives the FORWARD_G dashboard metric. */
    val forwardGFromSpeed: Float = 0f,
    val batteryPower: Int = 0,
    val motorPower: Int = 0,
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
    /** Tiltback / max-speed threshold the wheel firmware reports in its telemetry,
     *  in km/h. -1 = the active adapter doesn't surface this. Used so the
     *  Settings UI reflects what the wheel is actually enforcing (set via the
     *  vendor app on Veteran, where our app has no write command). */
    val wheelMaxSpeedKmh: Float = -1f,
    /** Alarm-speed threshold the wheel firmware reports, in km/h. -1 = unknown. */
    val wheelAlarmSpeedKmh: Float = -1f,
    val timestamp: Long = System.currentTimeMillis()
)
