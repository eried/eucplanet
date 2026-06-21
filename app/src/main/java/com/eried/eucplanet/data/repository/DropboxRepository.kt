package com.eried.eucplanet.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.eried.eucplanet.data.model.AppSettings
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Dropbox API client + OAuth (PKCE) handler.
 *
 * We deliberately do NOT pull in the Dropbox Java SDK (~5 MB, mostly
 * classes for endpoints we don't use). Instead we hit Dropbox's REST API
 * v2 directly with OkHttp — a few calls for /oauth2/token,
 * /users/get_current_account, /files/upload, /files/download,
 * /files/list_folder.
 *
 * Auth uses PKCE so we never ship the app secret in the APK. The flow:
 *   1. [startLinkFlow] generates a code verifier + SHA-256 challenge,
 *      stashes the verifier in [pendingVerifier], and opens a Chrome
 *      Custom Tab to Dropbox's /oauth2/authorize endpoint.
 *   2. After the user authorises, Dropbox redirects to our `db-<APPKEY>:/`
 *      scheme. MainActivity catches that, hands the URI to
 *      [handleAuthCallback], which POSTs to /oauth2/token to exchange the
 *      `code` for an access + refresh token pair.
 *   3. Tokens land in [SettingsRepository]; from then on [linked] is true.
 */
@Singleton
class DropboxRepository @Inject constructor(
    private val settingsRepository: SettingsRepository,
) {

    private val http = OkHttpClient()

    /** Set by [startLinkFlow], read by [handleAuthCallback]. The verifier
     *  has to survive the trip out to Dropbox and back, but lives only in
     *  memory — losing it on process death just forces the rider to tap
     *  Link again, which is fine. */
    @Volatile private var pendingVerifier: String? = null

    val linked: Flow<Boolean> =
        settingsRepository.settings.map { it.dropboxAccessToken.isNotBlank() }

    val accountLabel: Flow<String> =
        settingsRepository.settings.map { it.dropboxAccountLabel }

    /**
     * Open Dropbox's OAuth consent page in a Chrome Custom Tab. The user
     * picks "Allow"; Dropbox redirects to `db-<APPKEY>://1/connect?code=…`
     * which our MainActivity intent-filter catches.
     */
    fun startLinkFlow(context: Context) {
        val verifier = randomCodeVerifier()
        pendingVerifier = verifier
        val challenge = codeChallenge(verifier)
        val uri = Uri.parse("https://www.dropbox.com/oauth2/authorize").buildUpon()
            .appendQueryParameter("client_id", APP_KEY)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("token_access_type", "offline")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .build()
        val intent = CustomTabsIntent.Builder().build().intent.apply {
            data = uri
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Handle the `db-<APPKEY>:/...?code=AUTH_CODE` redirect from Dropbox.
     * Exchanges the code for tokens and persists them. Returns true on
     * success so the caller (MainActivity) can surface a snackbar.
     */
    suspend fun handleAuthCallback(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val code = uri.getQueryParameter("code") ?: return@withContext false
        val verifier = pendingVerifier ?: return@withContext false
        pendingVerifier = null
        val body = FormBody.Builder()
            .add("code", code)
            .add("grant_type", "authorization_code")
            .add("client_id", APP_KEY)
            .add("code_verifier", verifier)
            .add("redirect_uri", REDIRECT_URI)
            .build()
        val req = Request.Builder()
            .url("https://api.dropbox.com/oauth2/token")
            .post(body)
            .build()
        try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext false
                val json = JSONObject(resp.body?.string().orEmpty())
                val access = json.optString("access_token").ifBlank { return@withContext false }
                val refresh = json.optString("refresh_token", "")
                val ttlSec = json.optLong("expires_in", 14400L)
                val expiresAt = System.currentTimeMillis() + ttlSec * 1000L
                val accountLabel = fetchAccountLabel(access).orEmpty()
                settingsRepository.update {
                    it.copy(
                        dropboxAccessToken = access,
                        dropboxRefreshToken = refresh.ifBlank { it.dropboxRefreshToken },
                        dropboxAccessTokenExpiresAt = expiresAt,
                        dropboxAccountLabel = accountLabel,
                    )
                }
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Drop the local tokens — does NOT revoke them on Dropbox's side
     *  (which would need another HTTPS call). Future iteration could add
     *  /auth/token/revoke; for now the token simply ages out. */
    suspend fun unlink() {
        settingsRepository.update {
            it.copy(
                dropboxAccessToken = "",
                dropboxRefreshToken = "",
                dropboxAccessTokenExpiresAt = 0L,
                dropboxAccountLabel = "",
            )
        }
    }

    /** Best-effort fetch of the linked account's display name / email so
     *  the Settings row can show "Linked: ride@example.com" rather than
     *  a generic "Linked". Falls back to "Dropbox" on any error. */
    private fun fetchAccountLabel(accessToken: String): String? = try {
        val req = Request.Builder()
            .url("https://api.dropboxapi.com/2/users/get_current_account")
            .addHeader("Authorization", "Bearer $accessToken")
            .post(okhttp3.RequestBody.create(null, ByteArray(0)))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null
            else {
                val json = JSONObject(resp.body?.string().orEmpty())
                json.optString("email").ifBlank {
                    json.optJSONObject("name")?.optString("display_name").orEmpty()
                }
            }
        }
    } catch (e: Exception) {
        null
    }

    companion object {
        const val APP_KEY = "5auhxf7gswy7j54"
        const val REDIRECT_URI = "db-$APP_KEY://1/connect"

        /** RFC 7636 — 43-128 chars from a fixed unreserved set. */
        private fun randomCodeVerifier(): String {
            val alphabet = ('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('-', '.', '_', '~')
            val rnd = java.security.SecureRandom()
            return (1..64).map { alphabet[rnd.nextInt(alphabet.size)] }.joinToString("")
        }

        /** SHA-256 of the verifier, base64url-encoded without padding. */
        private fun codeChallenge(verifier: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(verifier.toByteArray(Charsets.US_ASCII))
            return android.util.Base64.encodeToString(
                digest,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
            )
        }
    }
}
