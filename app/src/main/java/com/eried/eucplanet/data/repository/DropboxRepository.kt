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
import okhttp3.Response
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
/**
 * What a Dropbox move did, rather than just whether it worked.
 *
 * A caller archiving a trip has to be able to undo its own half-finished work,
 * and [Moved.toPath] is where the file really went - move_v2 renames on a
 * collision, so it is not always the path that was asked for. [Absent] is a
 * file that was never on Dropbox, which for archiving is the state we wanted
 * anyway and not an error.
 */
sealed interface MoveOutcome {
    data class Moved(val toPath: String) : MoveOutcome
    data object Absent : MoveOutcome
    data object Failed : MoveOutcome
}

class DropboxRepository @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) {

    private val http = OkHttpClient()

    /**
     * True when Dropbox last answered 429 and the retries did not clear it.
     *
     * Dropbox rate-limits per account, not per file, so a rider pulling a
     * library of two thousand trips can hit it partway through - and every
     * limited request looked exactly like a missing file: a null, a skipped
     * trip, and a sync that reported a number without a reason. The sync reads
     * this to tell the rider what actually happened.
     */
    @Volatile
    var rateLimited: Boolean = false
        private set

    /** Cleared at the start of a sync, so the flag describes this run. */
    fun clearRateLimited() { rateLimited = false }

    /**
     * Run [attempt] and, when Dropbox says it is rate limited, wait the number
     * of seconds it asks for and try again.
     *
     * Dropbox sends retry_after in the body and in a header. Honouring it is
     * the difference between a sync that pauses for a second and one that
     * silently drops a trip: the API is telling us exactly when it will answer.
     */
    private fun <T> withRateLimitRetry(what: String, attempt: () -> Pair<Response, T?>): T? {
        var wait = 0L
        repeat(RATE_LIMIT_TRIES) { round ->
            if (wait > 0) Thread.sleep(wait)
            val (resp, value) = attempt()
            if (resp.code != 429) return value
            wait = retryAfterMs(resp)
            Log.w("DBXSHARE", "$what rate limited, waiting ${wait}ms (attempt ${round + 1})")
            resp.close()
        }
        rateLimited = true
        Log.w("DBXSHARE", "$what still rate limited after $RATE_LIMIT_TRIES attempts")
        return null
    }

    /** How long Dropbox asked us to wait, from the header or the body. */
    private fun retryAfterMs(resp: Response): Long {
        val header = resp.header("Retry-After")?.toLongOrNull()
        if (header != null) return (header * 1000L).coerceIn(500L, 30_000L)
        val body = runCatching { resp.peekBody(512).string() }.getOrNull().orEmpty()
        // Dropbox puts it inside the error object:
        //   {"error":{"reason":{...},"retry_after":1},"error_summary":""}
        val secs = runCatching {
            JSONObject(body).optJSONObject("error")?.optLong("retry_after")
        }.getOrNull()?.takeIf { it > 0 }
        return ((secs ?: 2L) * 1000L).coerceIn(500L, 30_000L)
    }

    /**
     * Set by [startLinkFlow], read by [handleAuthCallback].
     *
     * Kept on disk, not in memory. The rider leaves the app to authorise in a
     * browser, which is exactly when Android is most willing to reclaim it, and
     * a verifier lost that way cannot be recovered: the callback arrives at a
     * fresh process, the exchange fails, and all the rider sees is that linking
     * "failed" - again on every retry, since each one loses it the same way.
     *
     * Its own small file rather than AppSettings: it is scratch state for one
     * link attempt, not a setting, and it is cleared the moment it is used.
     */
    private val linkPrefs by lazy {
        context.getSharedPreferences("dropbox_link", Context.MODE_PRIVATE)
    }

    private var pendingVerifier: String?
        get() = linkPrefs.getString("verifier", null)
        set(value) {
            linkPrefs.edit().apply {
                if (value == null) remove("verifier") else putString("verifier", value)
            }.apply()
        }

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
        val code = uri.getQueryParameter("code")
        if (code == null) {
            Log.w("DBXSHARE", "link callback carried no code")
            return@withContext false
        }
        val verifier = pendingVerifier
        if (verifier == null) {
            // The app was reclaimed while the rider was in the browser.
            Log.w("DBXSHARE", "link callback arrived with no verifier stored")
            return@withContext false
        }
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
                if (!resp.isSuccessful) {
                    Log.w("DBXSHARE", "token exchange HTTP " + resp.code + ": " +
                        resp.body?.string()?.take(300))
                    return@withContext false
                }
                val json = JSONObject(resp.body?.string().orEmpty())
                val access = json.optString("access_token").ifBlank {
                    Log.w("DBXSHARE", "token exchange returned no access_token")
                    return@withContext false
                }
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
            Log.w("DBXSHARE", "token exchange failed: " + e.message)
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
        withRateLimitRetry("upload $remotePath") {
            try {
                val resp = http.newCall(req).execute()
                if (resp.code == 429) return@withRateLimitRetry resp to null
                resp.use {
                    if (!it.isSuccessful) {
                        Log.w("DBXSHARE", "upload HTTP ${it.code}: ${it.body?.string()?.take(300)}")
                    }
                    it to (if (it.isSuccessful) true else null)
                }
            } catch (e: Exception) {
                Log.w("DBXSHARE", "upload exception: ${e.message}")
                errorResponse(req) to null
            }
        } ?: false
    }

    /** A stand-in response for a request that never reached Dropbox, so the
     *  retry helper can treat it as a plain failure rather than a rate limit. */
    private fun errorResponse(req: Request): Response = Response.Builder()
        .request(req).protocol(okhttp3.Protocol.HTTP_1_1).code(599).message("no response").build()

    /**
     * Move [from] to [to] inside the App Folder.
     *
     * Used to archive a trip whose data now lives inside another one: a piece
     * that was extended into a longer ride, or the original a split replaced.
     * Deleting it would be the obvious move and the wrong one - the rider may
     * still want the raw ride - so it goes to a subfolder instead, which also
     * takes it out of the /trips listing the sync walks, so it stops coming
     * back down on the next sync.
     *
     * autorename, because a later combine can produce a file named like one
     * already archived and the archive must never overwrite itself.
     *
     * A file that is not there is reported as done: it means the trip never
     * reached Dropbox, which is the state archiving was trying to arrive at.
     */
    suspend fun moveFile(from: String, to: String): MoveOutcome = withContext(Dispatchers.IO) {
        val token = activeAccessToken() ?: return@withContext MoveOutcome.Failed
        val body = JSONObject().apply {
            put("from_path", from)
            put("to_path", to)
            put("autorename", true)
            put("allow_ownership_transfer", false)
        }
        val req = Request.Builder()
            .url("https://api.dropboxapi.com/2/files/move_v2")
            .addHeader("Authorization", "Bearer $token")
            .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), body.toString()))
            .build()
        try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    // autorename means the file may not have landed at [to],
                    // and the caller needs where it actually went to be able
                    // to put it back.
                    val landed = runCatching {
                        JSONObject(text).optJSONObject("metadata")?.optString("path_display")
                    }.getOrNull()?.ifBlank { null } ?: to
                    return@use MoveOutcome.Moved(landed)
                }
                if (resp.code == 409 && text.contains("not_found")) {
                    Log.i("DBXSHARE", "move: $from is not on Dropbox, nothing to archive")
                    return@use MoveOutcome.Absent
                }
                Log.w("DBXSHARE", "move HTTP ${resp.code}: ${text.take(300)}")
                MoveOutcome.Failed
            }
        } catch (e: Exception) {
            Log.w("DBXSHARE", "move exception: ${e.message}")
            MoveOutcome.Failed
        }
    }

    /**
     * Move many files in one go, for archiving a whole library at once.
     *
     * "Delete all" over a rider with two thousand trips is two thousand round
     * trips one at a time, which is minutes of waiting and a rate limit
     * waiting at the end of it. move_batch_v2 takes up to a thousand entries
     * per call and hands back a job to poll, so the same work is a handful of
     * requests.
     *
     * @return true when every entry moved (or was already gone)
     */
    suspend fun moveFilesBatch(pairs: List<Pair<String, String>>): Boolean =
        withContext(Dispatchers.IO) {
            if (pairs.isEmpty()) return@withContext true
            val token = activeAccessToken() ?: return@withContext false
            pairs.chunked(BATCH_MAX).all { chunk -> moveChunk(token, chunk) }
        }

    private fun moveChunk(token: String, chunk: List<Pair<String, String>>): Boolean {
        val body = JSONObject().apply {
            put("autorename", true)
            put("entries", org.json.JSONArray().apply {
                chunk.forEach { (from, to) ->
                    put(JSONObject().apply { put("from_path", from); put("to_path", to) })
                }
            })
        }
        val started = postJson(token, "files/move_batch_v2", body) ?: return false
        // Small batches come back done; larger ones hand over a job id.
        if (started.optString(".tag") == "complete") return true
        val job = started.optString("async_job_id").ifBlank { return false }
        // check_v2, not check: a v2 batch job polled through the v1 endpoint
        // answers internal_error, which reads as a failed move even though
        // Dropbox has already done it - the files end up archived there while
        // the phone still holds its copies.
        repeat(POLL_TRIES) {
            Thread.sleep(POLL_WAIT_MS)
            val check = postJson(token, "files/move_batch/check_v2",
                JSONObject().apply { put("async_job_id", job) }) ?: return false
            when (check.optString(".tag")) {
                "complete" -> return true
                "failed" -> return false
            }
        }
        Log.w("DBXSHARE", "move_batch still running after ${POLL_TRIES * POLL_WAIT_MS} ms")
        return false
    }

    private fun postJson(token: String, endpoint: String, body: JSONObject): JSONObject? {
        val req = Request.Builder()
            .url("https://api.dropboxapi.com/2/$endpoint")
            .addHeader("Authorization", "Bearer $token")
            .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), body.toString()))
            .build()
        return try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w("DBXSHARE", "$endpoint HTTP ${resp.code}: ${text.take(300)}")
                    null
                } else JSONObject(text)
            }
        } catch (e: Exception) {
            Log.w("DBXSHARE", "$endpoint exception: ${e.message}")
            null
        }
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
        withRateLimitRetry("download $remotePath") {
            try {
                val resp = http.newCall(req).execute()
                if (resp.code == 429) return@withRateLimitRetry resp to null
                resp.use {
                    if (!it.isSuccessful) {
                        Log.w("DBXSHARE", "download HTTP ${it.code} for $remotePath")
                        it to null
                    } else it to it.body?.bytes()
                }
            } catch (e: Exception) {
                Log.w("DBXSHARE", "download exception: ${e.message}")
                // No response to inspect: report it as a non-429 failure.
                errorResponse(req) to null
            }
        }
    }

    /** Metadata for one remote file from a folder listing. [size] is the byte
     *  count Dropbox holds, the stable signal for "already uploaded" (a trip
     *  CSV's modified-time can be bumped locally, its content-length cannot). */
    data class RemoteFile(val serverModified: Long, val size: Long)

    /** Map of file-name → [RemoteFile] for the given Dropbox folder (App-Folder
     *  relative). Empty map on "not_found" (folder doesn't exist yet — normal on
     *  first link). Null on auth / network failure so caller can distinguish
     *  "no files" from "couldn't check". */
    /** One page of a folder listing: the files on it, and where to continue. */
    internal data class ListPage(
        val files: Map<String, RemoteFile>,
        val cursor: String?,
        val hasMore: Boolean,
    )

    suspend fun listFolder(remoteFolder: String): Map<String, RemoteFile>? = withContext(Dispatchers.IO) {
        val token = activeAccessToken() ?: return@withContext null
        var absent = false
        val all = collectPages(warn = { Log.w("DBXSHARE", it) }) { cursor ->
            val (url, body) = if (cursor == null) {
                "https://api.dropboxapi.com/2/files/list_folder" to JSONObject().apply {
                    put("path", remoteFolder)
                    put("recursive", false)
                    put("include_deleted", false)
                    // A hint only: Dropbox may return fewer, and says so. The
                    // loop is what actually gets every entry.
                    put("limit", PAGE_LIMIT)
                }
            } else {
                "https://api.dropboxapi.com/2/files/list_folder/continue" to JSONObject().apply {
                    put("cursor", cursor)
                }
            }
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), body.toString()))
                .build()
            try {
                http.newCall(req).execute().use { resp ->
                    // 409 on the first call is "folder doesn't exist yet", which
                    // is normal before the first upload. On a continue it means
                    // the cursor went stale, which is a failed listing, not an
                    // empty one.
                    if (resp.code == 409 && cursor == null) {
                        absent = true
                        return@use null
                    }
                    if (!resp.isSuccessful) {
                        Log.w("DBXSHARE", "list_folder HTTP ${resp.code}: ${resp.body?.string()?.take(300)}")
                        return@use null
                    }
                    parseListPage(JSONObject(resp.body?.string().orEmpty()))
                }
            } catch (e: Exception) {
                Log.w("DBXSHARE", "list_folder exception: ${e.message}")
                null
            }
        }
        if (absent) emptyMap() else all
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
                // No link means nothing can sync, so clear the pending flag + count
                // too (and with them the persistent "Syncing trips…" indicator).
                dropboxSyncPending = false,
                dropboxPendingCount = 0,
                dropboxSyncTotal = 0,
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
        /** move_batch_v2 takes at most this many entries per call. */
        private const val BATCH_MAX = 1000
        /** How many times to wait out a 429 before giving up on a file. */
        private const val RATE_LIMIT_TRIES = 4
        private const val POLL_TRIES = 60
        private const val POLL_WAIT_MS = 500L

        const val APP_KEY = "5auhxf7gswy7j54"
        const val REDIRECT_URI = "db-$APP_KEY://1/connect"

        /** Page size asked for. Dropbox treats it as approximate and can return
         *  fewer, so it saves round trips on a big folder and nothing more. */
        private const val PAGE_LIMIT = 2000

        /** Runaway guard. At [PAGE_LIMIT] a folder would have to hold millions
         *  of trips to reach this, so hitting it means something is wrong with
         *  the cursor rather than with the rider's collection. */
        private const val MAX_LIST_PAGES = 500

        /**
         * Walk every page of a listing.
         *
         * Dropbox pages this endpoint and decides the page size itself: it is not
         * documented, not guaranteed, and `limit` is only approximate. A client that
         * reads the first page and stops sees a folder that ends early, which this
         * one did. The damage is not only missing downloads: the sync treats a local
         * file whose remote twin is past the last visible page as local-only and
         * uploads it again on every single sync, forever, because uploading cannot
         * bring it into view. One rider had 22 trips doing exactly that.
         *
         * [fetch] returns null for a failed page. A failure returns null overall
         * rather than the pages gathered so far: a partial listing is
         * indistinguishable from a smaller folder to every caller, and would set off
         * that same re-upload of everything missing from it.
         */
        internal suspend fun collectPages(
                maxPages: Int = MAX_LIST_PAGES,
                warn: (String) -> Unit = {},
                fetch: suspend (cursor: String?) -> ListPage?,
            ): Map<String, RemoteFile>? {
            val out = mutableMapOf<String, RemoteFile>()
            var cursor: String? = null
            var pages = 0
            while (true) {
                val page = fetch(cursor) ?: return null
                out.putAll(page.files)
                pages++
                if (!page.hasMore) break
                cursor = page.cursor ?: return null  // more to come but nowhere to go
                if (pages >= maxPages) {
                    // Better to report "couldn't check" than to hand back a folder
                    // that stops in the middle.
                    warn("list_folder stopped after $maxPages pages (${out.size} entries)")
                    return null
                }
            }
                if (pages > 1) warn("list_folder walked $pages pages, ${out.size} entries")
                return out
        }

        /** Files on one listing page, plus the cursor for the next. Folders and
         *  deleted entries are skipped: callers only ever want files. */
        internal fun parseListPage(json: JSONObject): ListPage {
            val entries = json.optJSONArray("entries")
            val files = mutableMapOf<String, RemoteFile>()
            for (i in 0 until (entries?.length() ?: 0)) {
                val e = entries!!.getJSONObject(i)
                if (e.optString(".tag") != "file") continue
                val name = e.optString("name")
                if (name.isBlank()) continue
                val epoch = try {
                    java.time.OffsetDateTime.parse(e.optString("server_modified")).toEpochSecond()
                } catch (_: Exception) { 0L }
                files[name] = RemoteFile(epoch, e.optLong("size", -1L))
            }
            return ListPage(
                files = files,
                cursor = json.optString("cursor").ifBlank { null },
                hasMore = json.optBoolean("has_more", false),
            )
        }


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
