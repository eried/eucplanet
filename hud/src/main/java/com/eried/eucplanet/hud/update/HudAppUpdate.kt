package com.eried.eucplanet.hud.update

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.util.Log
import com.eried.eucplanet.hud.BuildConfig
import com.eried.eucplanet.hud.net.HudDiag
import com.eried.eucplanet.hud.protocol.HudAppPush
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * The HUD app arriving from the phone, and the phone asking the HUD to
 * remove itself.
 *
 * Installing a HUD by hand means a cable, a file manager and the unit's own
 * installer. Once this app is on the HUD, the phone can send the next one
 * over the link: the file lands here and goes straight to Android's own
 * prompt. The rider already asked for it with a tap on the phone, and
 * Android asks on the HUD before anything installs or leaves, so the HUD
 * adds no question of its own in between.
 */
object HudAppUpdate {
    private const val TAG = "HudAppUpdate"

    /** Marks the install result coming back to the activity. */
    const val ACTION_INSTALL_STATUS = "com.eried.eucplanet.hud.INSTALL_STATUS"

    /** A file that arrived and passed the checks. */
    data class Pending(val file: File, val versionName: String)

    /** Just arrived: the activity hands it to Android at once. */
    private val _arrived = MutableStateFlow<Pending?>(null)
    val arrived: StateFlow<Pending?> = _arrived.asStateFlow()

    /** True from the phone's remove request until the activity acts on it. */
    private val _removeAsked = MutableStateFlow(false)
    val removeAsked: StateFlow<Boolean> = _removeAsked.asStateFlow()

    /** A short line for the rider when an install did not go through. */
    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()

    /** Where a posted file is written before it is checked. A new post
     *  replaces whatever was waiting. */
    fun incomingFile(context: Context): File {
        _arrived.value = null
        return File(File(context.cacheDir, "update").apply { mkdirs() }, "hud.apk")
    }

    /**
     * A posted [file] of [total] bytes has landed: keep it if it is this app and
     * not a step back. Returns one of the [HudAppPush] answers.
     */
    fun received(context: Context, file: File, total: Long): String {
        if (total > HudAppPush.MAX_BYTES) {
            file.delete()
            return HudAppPush.ANSWER_TOO_BIG
        }
        @Suppress("DEPRECATION")
        val info = runCatching { context.packageManager.getPackageArchiveInfo(file.path, 0) }.getOrNull()
        if (info == null || info.packageName != context.packageName) {
            file.delete()
            HudDiag.log("update", "file from the phone is not this app ($total bytes)")
            return HudAppPush.ANSWER_NOT_AN_APP
        }
        @Suppress("DEPRECATION")
        val code = info.versionCode
        if (!HudAppPush.accepts(BuildConfig.VERSION_CODE, code)) {
            file.delete()
            HudDiag.log("update", "file from the phone is older ($code, running ${BuildConfig.VERSION_CODE})")
            return HudAppPush.ANSWER_OLDER
        }
        HudDiag.log("update", "received ${info.versionName} ($code), $total bytes")
        _problem.value = null
        _arrived.value = Pending(file, info.versionName ?: "")
        return HudAppPush.ANSWER_READY
    }

    /** Hand the file to Android, which asks the rider before it installs. */
    fun install(activity: Activity) {
        val p = _arrived.value ?: return
        _arrived.value = null
        Thread {
            try {
                val installer = activity.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(activity.packageName)
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    session.openWrite("hud", 0, p.file.length()).use { out ->
                        p.file.inputStream().use { it.copyTo(out) }
                        session.fsync(out)
                    }
                    val back = Intent(activity, activity.javaClass).setAction(ACTION_INSTALL_STATUS)
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                    session.commit(PendingIntent.getActivity(activity, id, back, flags).intentSender)
                }
                HudDiag.log("update", "handed ${p.versionName} to the installer")
            } catch (t: Throwable) {
                Log.w(TAG, "install failed", t)
                HudDiag.log("update", "install failed: ${t.message}")
                p.file.delete()
                _problem.value = t.message ?: t.javaClass.simpleName
            }
        }.start()
    }

    /**
     * The installer's answer, delivered to the activity. Android wants the
     * rider's word before it installs, and says so by handing back the
     * prompt to show.
     */
    fun onInstallStatus(activity: Activity, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val prompt = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (prompt != null) runCatching { activity.startActivity(prompt) }
                    .onFailure { _problem.value = it.message }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                HudDiag.log("update", "installer said no: $why")
                // Nothing is kept for a second try: the phone sends it again
                // on a tap. The rider pressing Cancel on Android's prompt is
                // not a fault.
                File(File(activity.cacheDir, "update"), "hud.apk").delete()
                _problem.value = if (status == PackageInstaller.STATUS_FAILURE_ABORTED) null else why
            }
        }
    }

    fun dismissProblem() { _problem.value = null }

    /** The phone asked for this app to go. The activity shows Android's prompt. */
    fun askRemove() {
        HudDiag.log("update", "phone asked to remove the HUD app")
        _removeAsked.value = true
    }

    /** Open Android's own "uninstall this app?" prompt. */
    fun remove(activity: Activity) {
        _removeAsked.value = false
        @Suppress("DEPRECATION")
        runCatching {
            activity.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${activity.packageName}")))
        }.onFailure { _problem.value = it.message }
    }
}
