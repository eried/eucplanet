package com.eried.eucplanet.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import com.eried.eucplanet.data.model.AppSettings
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
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
            // Explicitly request every scope we use. Upload needs
            // files.content.write, restore/eucviewer needs files.content.read,
            // the "Inspect online" / share-link needs sharing.write (a missing
            // sharing.write was returning 401 missing_scope on createSharedLink),
            // and the account label needs account_info.read. These must also be
            // enabled in the Dropbox app console's Permissions tab.
            .appendQueryParameter(
                "scope",
                "account_info.read files.content.write files.content.read sharing.write sharing.read"
            )
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

    /**
     * Upload [bytes] to the App-Folder path [remotePath] (e.g.
     * "/trips/trip_20260622_010203.csv"). Overwrites any existing file
     * at the same path — caller is responsible for picking a path that
     * doesn't collide with someone else's edit, or for comparing
     * server_modified timestamps first via [listFolder].
     *
     * Returns true on success.
     */
    suspend fun uploadFile(remotePath: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val token = activeAccessToken() ?: return@withContext false
        val args = JSONObject().apply {
            put("path", remotePath)
            put("mode", "overwrite")
            put("autorename", false)
            put("mute", true)
            put("strict_conflict", false)
        }
        val mediaOctet = "application/octet-stream".toMediaTypeOrNull()
        val req = Request.Builder()
            .url("https://content.dropboxapi.com/2/files/upload")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Dropbox-API-Arg", args.toString())
            .post(okhttp3.RequestBody.create(mediaOctet, bytes))
            .build()
        try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w("DBXSHARE", "upload HTTP ${resp.code}: ${resp.body?.string()?.take(300)}")
                }
                resp.isSuccessful
            }
        } catch (e: Exception) { Log.w("DBXSHARE", "upload exception: ${e.message}"); false }
    }

    /**
     * Download [remotePath] from the App Folder and return the raw bytes.
     * Returns null on auth / network failure or if the file is missing.
     */
    suspend fun downloadFile(remotePath: String): ByteArray? = withContext(Dispatchers.IO) {
        val token = activeAccessToken() ?: return@withContext null
        val args = JSONObject().apply { put("path", remotePath) }
        val req = Request.Builder()
            .url("https://content.dropboxapi.com/2/files/download")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Dropbox-API-Arg", args.toString())
            .post(okhttp3.RequestBody.create(null, ByteArray(0)))
            .build()
        try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body?.bytes()
            }
        } catch (e: Exception) { null }
    }

    /** Map of file-name → server_modified epoch-seconds for the given
     *  Dropbox folder (App-Folder relative). Empty map on "not_found"
     *  (folder doesn't exist yet — normal on first link). Null on auth
     *  / network failure so caller can distinguish "no files" from
     *  "couldn't check". */
    suspend fun listFolder(remoteFolder: String): Map<String, Long>? = withContext(Dispatchers.IO) {
        val token = activeAccessToken() ?: return@withContext null
        val body = JSONObject().apply {
            put("path", remoteFolder)
            put("recursive", false)
            put("include_deleted", false)
        }
        val req = Request.Builder()
            .url("https://api.dropboxapi.com/2/files/list_folder")
            .addHeader("Authorization", "Bearer $token")
            .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), body.toString()))
            .build()
        try {
            http.newCall(req).execute().use { resp ->
                if (resp.code == 409) return@withContext emptyMap()  // folder absent
                if (!resp.isSuccessful) return@withContext null
                val json = JSONObject(resp.body?.string().orEmpty())
                val entries = json.optJSONArray("entries") ?: return@withContext emptyMap()
                val out = mutableMapOf<String, Long>()
                for (i in 0 until entries.length()) {
                    val e = entries.getJSONObject(i)
                    if (e.optString(".tag") != "file") continue
                    val name = e.optString("name")
                    val mod = e.optString("server_modified")  // ISO-8601
                    val epoch = try {
                        java.time.OffsetDateTime.parse(mod).toEpochSecond()
                    } catch (_: Exception) { 0L }
                    if (name.isNotBlank()) out[name] = epoch
                }
                out
            }
        } catch (e: Exception) { null }
    }

    /**
     * Create (or fetch the existing) public shared link for [remotePath].
     * The returned `?dl=0` URL renders a Dropbox preview; appending `?dl=1`
     * downloads the raw file. Returns null on failure.
     */
    /** Error `.tag` from the most recent share-link attempt (e.g.
     *  "email_not_verified"), or null after a success. Lets the UI explain
     *  *why* a share failed instead of a generic message. */
    @Volatile
    var lastShareErrorTag: String? = null
        private set

    /** The Dropbox error `.tag` from a response body, or null. */
    private fun errorTag(body: String): String? =
        runCatching { JSONObject(body).optJSONObject("error")?.optString(".tag")?.ifBlank { null } }.getOrNull()

    suspend fun createSharedLink(remotePath: String): String? = withContext(Dispatchers.IO) {
        val token = activeAccessToken() ?: return@withContext null
        val body = JSONObject().apply {
            put("path", remotePath)
            put("settings", JSONObject().apply {
                put("requested_visibility", "public")
                put("audience", "public")
                put("access", "viewer")
            })
        }
        val req = Request.Builder()
            .url("https://api.dropboxapi.com/2/sharing/create_shared_link_with_settings")
            .addHeader("Authorization", "Bearer $token")
            .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), body.toString()))
            .build()
        val created = try {
            http.newCall(req).execute().use { resp ->
                val txt = resp.body?.string().orEmpty()
                if (resp.isSuccessful) JSONObject(txt).optString("url").ifBlank { null }
                else {
                    Log.w("DBXSHARE", "createSharedLink HTTP ${resp.code}: ${txt.take(300)}")
                    val tag = errorTag(txt)
                    // "shared_link_already_exists" isn't a real failure (the link
                    // is in the body). Surface any other tag (e.g.
                    // email_not_verified) so the UI can explain it.
                    if (tag != null && tag != "shared_link_already_exists") lastShareErrorTag = tag
                    JSONObject(txt).optJSONObject("error")
                        ?.optJSONObject("shared_link_already_exists")
                        ?.optJSONObject("metadata")
                        ?.optString("url")?.ifBlank { null }
                }
            }
        } catch (e: Exception) { Log.w("DBXSHARE", "createSharedLink exception: ${e.message}"); null }
        // Bulletproof fallback: if we couldn't create the link OR parse the
        // existing one out of the 409, ask Dropbox for the file's existing
        // shared links directly. This is what made re-sharing the same trip
        // fail after the first share.
        val result = created ?: listSharedLink(remotePath, token)
        if (result != null) lastShareErrorTag = null
        result
    }

    /** First existing public shared link for [remotePath], or null. */
    private suspend fun listSharedLink(remotePath: String, token: String): String? = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("path", remotePath)
            put("direct_only", true)
        }
        val req = Request.Builder()
            .url("https://api.dropboxapi.com/2/sharing/list_shared_links")
            .addHeader("Authorization", "Bearer $token")
            .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), body.toString()))
            .build()
        try {
            http.newCall(req).execute().use { resp ->
                val txt = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w("DBXSHARE", "list_shared_links HTTP ${resp.code}: ${txt.take(300)}")
                    return@withContext null
                }
                val links = JSONObject(txt).optJSONArray("links") ?: return@withContext null
                if (links.length() == 0) null
                else links.optJSONObject(0)?.optString("url")?.ifBlank { null }
            }
        } catch (e: Exception) { Log.w("DBXSHARE", "list_shared_links exception: ${e.message}"); null }
    }

    /**
     * A direct-download link for [remotePath], valid ~4 hours. Only needs
     * files.content.read (NOT the sharing.* scopes), so it always works as a
     * fallback when createSharedLink can't (missing sharing.write, etc.) and
     * never hits the 409 "already shared" path. A fresh link every call.
     */
    suspend fun getTemporaryLink(remotePath: String): String? = withContext(Dispatchers.IO) {
        val token = activeAccessToken() ?: return@withContext null
        val body = JSONObject().apply { put("path", remotePath) }
        val req = Request.Builder()
            .url("https://api.dropboxapi.com/2/files/get_temporary_link")
            .addHeader("Authorization", "Bearer $token")
            .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), body.toString()))
            .build()
        try {
            http.newCall(req).execute().use { resp ->
                val txt = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w("DBXSHARE", "get_temporary_link HTTP ${resp.code}: ${txt.take(300)}")
                    errorTag(txt)?.let { lastShareErrorTag = it }
                    return@withContext null
                }
                lastShareErrorTag = null
                JSONObject(txt).optString("link").ifBlank { null }
            }
        } catch (e: Exception) { Log.w("DBXSHARE", "get_temporary_link exception: ${e.message}"); null }
    }

    /**
     * Return a currently-valid access token, refreshing via the stored
     * refresh token if the cached one is within 60 s of expiry. Returns
     * null if the rider isn't linked or the refresh call fails.
     */
    private suspend fun activeAccessToken(): String? {
        val s = settingsRepository.get()
        if (s.dropboxAccessToken.isBlank()) return null
        val nowMs = System.currentTimeMillis()
        if (s.dropboxAccessTokenExpiresAt > nowMs + 60_000L) return s.dropboxAccessToken
        if (s.dropboxRefreshToken.isBlank()) return s.dropboxAccessToken  // best effort
        return refreshAccessToken(s.dropboxRefreshToken)
    }

    private suspend fun refreshAccessToken(refreshToken: String): String? = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", APP_KEY)
            .build()
        val req = Request.Builder()
            .url("https://api.dropbox.com/oauth2/token")
            .post(body)
            .build()
        try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w("DBXSHARE", "token refresh HTTP ${resp.code}: ${resp.body?.string()?.take(300)}")
                    return@withContext null
                }
                val json = JSONObject(resp.body?.string().orEmpty())
                val access = json.optString("access_token").ifBlank { return@withContext null }
                val ttlSec = json.optLong("expires_in", 14400L)
                val expiresAt = System.currentTimeMillis() + ttlSec * 1000L
                settingsRepository.update {
                    it.copy(
                        dropboxAccessToken = access,
                        dropboxAccessTokenExpiresAt = expiresAt,
                    )
                }
                access
            }
        } catch (e: Exception) { null }
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
