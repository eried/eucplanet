package com.eried.eucplanet.ble.gps

import com.eried.eucplanet.ble.BleDevice
import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.ble.virtual.VirtualWheelRegistry
import com.eried.eucplanet.data.model.ExternalGpsSample
import com.eried.eucplanet.data.model.ExternalGpsSource
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.platform.System
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.concurrent.Volatile

/**
 * The virtual RaceBox: a GPS box that lives in the app, for trying the
 * external GPS screens with no box in the room (the simulator has no
 * Bluetooth at all). Listed in the pairing card with Service Mode on, as the
 * virtual wheels are on the scan screen, under the pseudo-address
 * [ADDRESS].
 *
 * It speaks the real wire format: every tick it builds a RaceBox Data
 * Message (UBX class 0xFF id 0x01, the extended frame with the G-force and
 * battery), splits it into 20-byte notifications as a link at the default
 * MTU would, and feeds them through the paired adapter's [ExternalGpsAdapter.decode],
 * so the parser, its reassembly and its gravity filter run exactly as for a
 * real box.
 */
object VirtualExternalGps {
    const val ADDRESS = VirtualWheelRegistry.ADDRESS_PREFIX + "RACEBOX"
    const val NAME = "RaceBox Mini (virtual)"

    val scanResult = ExternalGpsScanResult(
        device = BleDevice(name = NAME, address = ADDRESS, rssi = 0),
        source = ExternalGpsSource.RACEBOX,
    )

    fun isVirtual(address: String?): Boolean =
        address != null && address.startsWith(VirtualWheelRegistry.ADDRESS_PREFIX)
}

/**
 * The frames the virtual box streams. Pure, so the tests can drive it.
 *
 * With a wheel connected it rides at the wheel's own (uncalibrated) speed
 * less [WHEEL_READS_FAST_PCT], the way a real wheel's speedo reads a little
 * high, so the Compare tab's calibration has something true to find. With
 * no wheel it runs its own loop: up to 30 km/h and back over 20 s. It turns
 * gently, starts where the phone's position assist (MGA-INI-POS_LLH) said
 * it is, else in Oslo, and reports a 3D fix with 12 satellites and a 87%
 * battery.
 */
class VirtualRaceBox(
    private var latDeg: Double = DEFAULT_LAT,
    private var lonDeg: Double = DEFAULT_LON,
) {
    private var headingDeg = 0.0
    private var lastSpeedKmh = 0f
    private var lastMs: Long? = null
    private var iTow = 0L

    /** Takes the post-connect writes the repository sent, as a real box does. */
    fun onInitWrites(writes: List<ByteArray>) {
        for (w in writes) {
            // MGA-INI-POS_LLH: class 0x13 id 0x40 type 0x01, lat / lon at payload 4 / 8.
            if (w.size >= 6 + 20 + 2 && w[2] == 0x13.toByte() && w[3] == 0x40.toByte() && w[6] == 0x01.toByte()) {
                latDeg = readI32(w, 6 + 4) * 1e-7
                lonDeg = readI32(w, 6 + 8) * 1e-7
            }
        }
    }

    /** The speed in km/h at [elapsedMs] into the connection. */
    fun speedKmh(elapsedMs: Long, wheelRawKmh: Float?): Float {
        if (wheelRawKmh != null) return (wheelRawKmh * (1f - WHEEL_READS_FAST_PCT / 100f)).coerceAtLeast(0f)
        val t = (elapsedMs % LOOP_MS) / LOOP_MS.toDouble()
        return (15.0 * (1.0 - cos(2 * PI * t))).toFloat()
    }

    /** One complete Data Message for [nowMs], [elapsedMs] into the connection. */
    fun frame(nowMs: Long, elapsedMs: Long, wheelRawKmh: Float?): ByteArray {
        val speed = speedKmh(elapsedMs, wheelRawKmh)
        val dtS = lastMs?.let { ((nowMs - it).coerceIn(0, 1_000)) / 1000.0 } ?: 0.0
        lastMs = nowMs
        val mps = speed / 3.6
        // A slow left-hand sweep: 3 degrees a second while moving.
        if (mps > 0.3) headingDeg = (headingDeg + 3.0 * dtS) % 360.0
        val dist = mps * dtS
        val rad = headingDeg * PI / 180.0
        latDeg += dist * cos(rad) / 111_320.0
        lonDeg += dist * sin(rad) / (111_320.0 * cos(latDeg * PI / 180.0))
        val forwardG = if (dtS > 0) ((speed - lastSpeedKmh) / 3.6 / dtS / 9.80665).toFloat() else 0f
        lastSpeedKmh = speed
        // Centripetal: speed times the turn rate.
        val lateralG = if (mps > 0.3) (mps * (3.0 * PI / 180.0) / 9.80665).toFloat() else 0f
        iTow += (dtS * 1000).toLong()

        val p = ByteArray(80)
        writeI32(p, 0, iTow.toInt())
        val t = UtcTime.of(nowMs)
        p[4] = (t.year and 0xFF).toByte(); p[5] = ((t.year ushr 8) and 0xFF).toByte()
        p[6] = t.month.toByte(); p[7] = t.day.toByte()
        p[8] = t.hour.toByte(); p[9] = t.minute.toByte(); p[10] = t.second.toByte()
        p[11] = 0x37                                     // date, time and fully resolved
        p[20] = 3                                        // 3D fix
        p[21] = 0x01                                     // fix OK
        p[23] = 12                                       // satellites
        writeI32(p, 24, (lonDeg * 1e7).roundToInt())
        writeI32(p, 28, (latDeg * 1e7).roundToInt())
        writeI32(p, 32, 140_000)                         // WGS altitude, mm
        writeI32(p, 36, 120_000)                         // MSL altitude, mm
        writeI32(p, 40, 800)                             // horizontal accuracy, mm
        writeI32(p, 44, 1_200)                           // vertical accuracy, mm
        writeI32(p, 48, (mps * 1000).roundToInt())       // speed, mm/s
        writeI32(p, 52, (headingDeg * 1e5).roundToInt()) // heading, deg 1e-5
        writeI32(p, 56, 150)                             // speed accuracy, mm/s
        writeI32(p, 60, 500_000)                         // heading accuracy
        p[64] = 90; p[65] = 0                            // PDOP 0.90
        p[67] = BATTERY_PCT.toByte()                     // on battery, 87%
        // Raw G-force in milli-g, the box standing upright as the default
        // axis map reads it: X lateral, Y up (gravity), Z forward.
        writeI16(p, 68, (lateralG * 1000).roundToInt())
        writeI16(p, 70, 1000)
        writeI16(p, 72, (forwardG * 1000).roundToInt())
        return ubxFrame(classId = 0xFF, msgId = 0x01, payload = p)
    }

    companion object {
        const val DEFAULT_LAT = 59.9139
        const val DEFAULT_LON = 10.7387
        const val WHEEL_READS_FAST_PCT = 3f
        const val BATTERY_PCT = 87
        const val LOOP_MS = 20_000L

        /** The pieces a 23-byte MTU carries a frame in. */
        fun chunks(frame: ByteArray, size: Int = 20): List<ByteArray> =
            (frame.indices step size).map { frame.copyOfRange(it, minOf(it + size, frame.size)) }

        private fun readI32(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
                ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

        private fun writeI32(b: ByteArray, o: Int, v: Int) {
            b[o] = (v and 0xFF).toByte(); b[o + 1] = ((v ushr 8) and 0xFF).toByte()
            b[o + 2] = ((v ushr 16) and 0xFF).toByte(); b[o + 3] = ((v ushr 24) and 0xFF).toByte()
        }

        private fun writeI16(b: ByteArray, o: Int, v: Int) {
            b[o] = (v and 0xFF).toByte(); b[o + 1] = ((v ushr 8) and 0xFF).toByte()
        }
    }
}

/**
 * The [ExternalGpsLink] the repository talks to: the phone's own link for a
 * real box, the [VirtualRaceBox] for [VirtualExternalGps.ADDRESS]. For a real
 * box it only passes calls and values through, so a paired RaceBox or Dragy
 * runs exactly as on the platform link.
 */
class ExternalGpsLinkRouter(
    private val real: ExternalGpsLink,
    private val wheelRawKmh: () -> Float?,
    private val tickMs: Long = 40L,
) : ExternalGpsLink {

    // Unconfined, so the real link's values land here before its setter returns.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val ticker = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _connectionState = MutableStateFlow(real.connectionState.value)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _samples = MutableSharedFlow<ExternalGpsSample>(extraBufferCapacity = 16)
    override val samples: SharedFlow<ExternalGpsSample> = _samples.asSharedFlow()

    @Volatile private var virtualJob: Job? = null
    private val virtualActive: Boolean get() = virtualJob != null

    init {
        scope.launch { real.connectionState.collect { if (!virtualActive) _connectionState.value = it } }
        scope.launch { real.samples.collect { if (!virtualActive) _samples.tryEmit(it) } }
    }

    override fun connect(address: String, adapter: ExternalGpsAdapter, initWrites: List<ByteArray>) {
        if (!VirtualExternalGps.isVirtual(address)) {
            stopVirtual()
            _connectionState.value = real.connectionState.value
            real.connect(address, adapter, initWrites)
            return
        }
        stopVirtual()
        real.disconnect()
        Log.i(TAG, "Connecting to the virtual ${adapter.source.displayName} (${initWrites.size} init writes)")
        _connectionState.value = ConnectionState.CONNECTING
        virtualJob = ticker.launch {
            delay(300)
            _connectionState.value = ConnectionState.INITIALIZING
            val box = VirtualRaceBox().also { it.onInitWrites(initWrites) }
            delay(200)
            _connectionState.value = ConnectionState.CONNECTED
            val start = System.currentTimeMillis()
            while (true) {
                val now = System.currentTimeMillis()
                for (piece in VirtualRaceBox.chunks(box.frame(now, now - start, wheelRawKmh()))) {
                    adapter.decode(piece)?.let { _samples.tryEmit(it) }
                }
                delay(tickMs)
            }
        }
    }

    override fun disconnect() {
        stopVirtual()
        real.disconnect()
        _connectionState.value = real.connectionState.value
    }

    private fun stopVirtual() {
        virtualJob?.cancel()
        virtualJob = null
    }

    private companion object {
        const val TAG = "ExtGpsRouter"
    }
}
