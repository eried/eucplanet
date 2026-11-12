package com.eried.eucplanet.service.hud

import android.content.Context
import com.eried.eucplanet.BuildConfig
import com.eried.eucplanet.diagnostics.DiagnosticsLogger
import com.eried.eucplanet.hud.protocol.HudAppPush
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.util.concurrent.TimeUnit

/**
 * Sends the HUD app this phone app carries to the linked HUD, or asks the
 * HUD to remove its app. Only ever on the rider's tap: nothing here runs by
 * itself.
 *
 * The HUD app is packed into the phone app at build time (see the
 * `bundleHudApk` task), so the file always matches this phone build and no
 * download is involved.
 */
class HudAppSender(
    private val context: Context,
    baseClient: OkHttpClient,
    /** "ip:port" of the HUD the link is talking to, null while it is down. */
    private val peer: () -> String?,
) {
    sealed interface State {
        data object Idle : State
        /** [fraction] 0..1 of the file handed to the socket. */
        data class Sending(val fraction: Float) : State
        /** The HUD has it and is asking its rider. */
        data object Sent : State
        data object Failed : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = baseClient.newBuilder()
        .writeTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Version code of the HUD app inside this phone app, 0 when none is. */
    val carriedVersionCode: Int = if (hasAsset()) BuildConfig.HUD_VERSION_CODE else 0
    val carriedVersionName: String = BuildConfig.HUD_VERSION_NAME
    /** The commit the carried HUD app was built from, the phone's own. */
    val carriedBuild: String = BuildConfig.HUD_BUILD_ID

    private fun hasAsset(): Boolean =
        runCatching { context.assets.open(ASSET).use { true } }.getOrDefault(false)

    /** Back to the plain card, e.g. when the HUD drops off. */
    fun reset() {
        if (_state.value !is State.Sending) _state.value = State.Idle
    }

    fun send() {
        if (_state.value is State.Sending) return
        val target = peer() ?: run { _state.value = State.Failed; return }
        _state.value = State.Sending(0f)
        scope.launch {
            val ok = runCatching {
                val bytes = context.assets.open(ASSET).use { it.readBytes() }
                val body = object : RequestBody() {
                    override fun contentType() = "application/vnd.android.package-archive".toMediaType()
                    override fun contentLength() = bytes.size.toLong()
                    override fun writeTo(sink: BufferedSink) {
                        var at = 0
                        while (at < bytes.size) {
                            val n = minOf(CHUNK, bytes.size - at)
                            sink.write(bytes, at, n)
                            sink.flush()
                            at += n
                            _state.value = State.Sending(at.toFloat() / bytes.size)
                        }
                    }
                }
                val req = Request.Builder().url("http://$target${HudAppPush.PATH_UPDATE}").post(body).build()
                http.newCall(req).execute().use { resp ->
                    val answer = resp.body?.string().orEmpty().trim()
                    android.util.Log.i(TAG, "sent to $target: ${resp.code} $answer")
                    DiagnosticsLogger.note("HUD app sent to $target: ${resp.code} $answer")
                    resp.isSuccessful && answer == HudAppPush.ANSWER_READY
                }
            }.onFailure {
                android.util.Log.w(TAG, "send failed", it)
                DiagnosticsLogger.note("HUD app send failed: ${it.message}")
            }
                .getOrDefault(false)
            _state.value = if (ok) State.Sent else State.Failed
        }
    }

    fun remove() {
        if (_state.value is State.Sending) return
        val target = peer() ?: run { _state.value = State.Failed; return }
        scope.launch {
            val ok = runCatching {
                val req = Request.Builder().url("http://$target${HudAppPush.PATH_REMOVE}")
                    .post(ByteArray(0).toRequestBody()).build()
                http.newCall(req).execute().use { resp ->
                    DiagnosticsLogger.note("HUD app remove asked of $target: ${resp.code}")
                    resp.isSuccessful
                }
            }.onFailure { DiagnosticsLogger.note("HUD app remove failed: ${it.message}") }
                .getOrDefault(false)
            _state.value = if (ok) State.Sent else State.Failed
        }
    }

    private companion object {
        const val TAG = "HudAppSender"
        const val ASSET = "hud.apk"
        const val CHUNK = 32 * 1024
    }
}
