package com.eried.eucplanet.diagnostics

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import com.eried.eucplanet.BuildConfig
import com.eried.eucplanet.ble.BleConnectionManager
import com.eried.eucplanet.ble.WheelAdapter
import com.eried.eucplanet.data.repository.WheelRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Backs the Wheel Diagnostics dialog. Holds direct refs to the BLE manager
 * (so test commands can be fired) and the wheel adapter (so its diagnostic
 * command list comes through with no UI plumbing). Logger state is read
 * straight from the [DiagnosticsLogger] singleton.
 */
@HiltViewModel
class WheelDiagnosticsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bleManager: BleConnectionManager,
    private val wheelAdapter: WheelAdapter,
    private val wheelRepository: WheelRepository
) : ViewModel() {

    val entries: StateFlow<List<DiagnosticsLogger.Entry>> = DiagnosticsLogger.entries
    val enabled: StateFlow<Boolean> = DiagnosticsLogger.enabled

    /** Drop a NOTE with what's connected so the share log carries identity context. */
    fun captureSessionInfo() {
        val data = wheelRepository.wheelData.value
        val state = bleManager.connectionState.value
        DiagnosticsLogger.info("app v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) build ${BuildConfig.BUILD_STAMP}")
        DiagnosticsLogger.info("connection state: $state")
        DiagnosticsLogger.info("wheel adapter: ${wheelAdapter.familyId}")
        wheelRepository.modelName.value?.let {
            DiagnosticsLogger.info("model: $it")
        }
        wheelRepository.firmwareVersion.value?.let {
            DiagnosticsLogger.info("firmware: $it")
        }
        DiagnosticsLogger.info("battery=${data.batteryPercent}%  voltage=${data.voltage}V  speed=${data.speed} km/h  lightOn=${data.lightOn}")
    }

    fun diagnosticCommands(): List<DiagnosticCommand> = wheelAdapter.getDiagnosticCommands()

    fun fireCommand(cmd: DiagnosticCommand) {
        DiagnosticsLogger.cmd(cmd.label, cmd.bytes)
        bleManager.writeCommand(cmd.bytes)
    }

    enum class WrapMode { LITERAL, WRAP_EXTENDED, WRAP_V14_SHORT }

    fun fireRawHex(hexInput: String, mode: WrapMode = WrapMode.LITERAL): Boolean {
        val bytes = wrap(hexInput, mode) ?: return false
        DiagnosticsLogger.cmd("RAW(${mode.name})", bytes)
        bleManager.writeCommand(bytes)
        return true
    }

    /** Render the bytes that would actually go on the wire, given the wrap mode. */
    fun previewHex(hexInput: String, mode: WrapMode): String {
        val bytes = wrap(hexInput, mode) ?: return ""
        return bytes.joinToString(" ") { "%02x".format(it) }
    }

    /** Re-emit the user input with consistent spacing every two hex chars. */
    fun formatHex(hexInput: String): String {
        val bytes = parseHex(hexInput) ?: return hexInput
        return bytes.joinToString(" ") { "%02x".format(it) }
    }

    private fun wrap(input: String, mode: WrapMode): ByteArray? {
        val bytes = parseHex(input) ?: return null
        return when (mode) {
            WrapMode.LITERAL -> bytes
            WrapMode.WRAP_EXTENDED -> {
                if (bytes.isEmpty()) return null
                com.eried.eucplanet.ble.InMotionV2Protocol.buildExtendedPacket(
                    bytes[0],
                    if (bytes.size > 1) bytes.copyOfRange(1, bytes.size) else byteArrayOf()
                )
            }
            WrapMode.WRAP_V14_SHORT -> {
                if (bytes.isEmpty()) return null
                com.eried.eucplanet.ble.InMotionV2Protocol.buildPacket(
                    com.eried.eucplanet.ble.InMotionV2Protocol.Flags.DEFAULT,
                    bytes[0],
                    if (bytes.size > 1) bytes.copyOfRange(1, bytes.size) else byteArrayOf()
                )
            }
        }
    }

    fun addComment(text: String) {
        if (text.isBlank()) return
        DiagnosticsLogger.comment(text.trim())
    }

    fun stopDiagnostics() {
        DiagnosticsLogger.disable()
        DiagnosticsLogger.clear()
    }

    /** Build a share intent backed by a temp .txt under the app's cache dir. */
    fun buildShareIntent(): Intent? {
        val cacheDir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(cacheDir, "diagnostics-$stamp.txt")
        return try {
            file.writeText(DiagnosticsLogger.render())
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "EUC Planet diagnostics ${file.name}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseHex(input: String): ByteArray? {
        val cleaned = input.replace("0x", "", ignoreCase = true)
            .replace(",", " ")
            .replace("-", " ")
            .trim()
        if (cleaned.isBlank()) return null
        // Tolerate "AA BB CC" or "AABBCC" or mixed.
        val tokens = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }
        val joined = if (tokens.size == 1) tokens[0] else tokens.joinToString("")
        if (joined.length % 2 != 0) return null
        return try {
            ByteArray(joined.length / 2) { i ->
                joined.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (_: Exception) {
            null
        }
    }
}
