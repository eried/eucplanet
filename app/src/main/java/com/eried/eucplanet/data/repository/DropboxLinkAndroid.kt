package com.eried.eucplanet.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Open Dropbox's OAuth consent page in a Chrome Custom Tab. The user picks
 * "Allow"; Dropbox redirects to `db-<APPKEY>://1/connect?code=…` which our
 * MainActivity intent-filter catches.
 */
fun DropboxRepository.startLinkFlow(context: Context) {
    val intent = CustomTabsIntent.Builder().build().intent.apply {
        data = Uri.parse(beginLink())
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

/** The `db-<APPKEY>:/...?code=AUTH_CODE` redirect MainActivity receives. */
suspend fun DropboxRepository.handleAuthCallback(uri: Uri): Boolean =
    handleAuthCode(uri.getQueryParameter("code"))

/**
 * The PKCE verifier, on disk, not in memory. The rider leaves the app to
 * authorise in a browser, which is exactly when Android is most willing to
 * reclaim it; its own small file, cleared the moment it is used.
 */
class SharedPrefsDropboxLinkScratch(context: Context) : DropboxLinkScratch {
    private val prefs by lazy { context.getSharedPreferences("dropbox_link", Context.MODE_PRIVATE) }

    override var verifier: String?
        get() = prefs.getString("verifier", null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove("verifier") else putString("verifier", value)
            }.apply()
        }
}
