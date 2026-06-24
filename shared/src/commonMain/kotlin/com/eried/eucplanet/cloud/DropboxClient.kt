@file:OptIn(ExperimentalEncodingApi::class)

package com.eried.eucplanet.cloud

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.client.statement.HttpResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random

/** Tokens returned by the Dropbox OAuth token endpoint. */
data class DropboxTokens(val accessToken: String, val refreshToken: String, val expiresAtMs: Long)

/**
 * Dropbox API v2 client — faithful port of Android's DropboxRepository over Ktor
 * (multiplatform; the Android app uses OkHttp). Implements the PKCE OAuth flow
 * (the redirect itself is done by the platform: ASWebAuthenticationSession on iOS),
 * token exchange/refresh, account label, and App-Folder file upload/download.
 *
 * The same app key + `db-<key>://` redirect as Android, so no Dropbox-console
 * change is needed beyond the scopes already enabled there.
 */
class DropboxClient(
    private val client: HttpClient = HttpClient(),
    private val nowMs: () -> Long = ::epochNow,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Random 64-char PKCE code-verifier from the unreserved set. */
    fun newVerifier(): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        return buildString { repeat(64) { append(alphabet[Random.nextInt(alphabet.length)]) } }
    }

    /** PKCE S256 challenge = base64url(SHA256(verifier)), no padding. */
    fun challengeFor(verifier: String): String {
        val digest = hexToBytes(sha256Hex(verifier.encodeToByteArray()))
        return Base64.UrlSafe.encode(digest).trimEnd('=')
    }

    /** The consent URL the platform opens in a web-auth session. */
    fun authorizeUrl(challenge: String): String =
        "https://www.dropbox.com/oauth2/authorize" +
            "?client_id=$APP_KEY" +
            "&response_type=code" +
            "&code_challenge=$challenge" +
            "&code_challenge_method=S256" +
            "&token_access_type=offline" +
            "&scope=" + SCOPES.replace(" ", "%20") +
            "&redirect_uri=$REDIRECT_URI"

    /** Exchange the auth code (from the redirect) for tokens. */
    suspend fun exchangeCode(code: String, verifier: String): DropboxTokens? = try {
        val resp = client.submitForm(
            url = "https://api.dropbox.com/oauth2/token",
            formParameters = Parameters.build {
                append("code", code)
                append("grant_type", "authorization_code")
                append("client_id", APP_KEY)
                append("code_verifier", verifier)
                append("redirect_uri", REDIRECT_URI)
            },
        )
        tokensFrom(resp)
    } catch (e: Exception) { null }

    /** Refresh the access token when it has expired (offline refresh token). */
    suspend fun refresh(refreshToken: String): DropboxTokens? = try {
        val resp = client.submitForm(
            url = "https://api.dropbox.com/oauth2/token",
            formParameters = Parameters.build {
                append("grant_type", "refresh_token")
                append("refresh_token", refreshToken)
                append("client_id", APP_KEY)
            },
        )
        tokensFrom(resp)?.copy(refreshToken = refreshToken)
    } catch (e: Exception) { null }

    private suspend fun tokensFrom(resp: HttpResponse): DropboxTokens? {
        if (!resp.status.isSuccess()) return null
        val obj = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val access = obj["access_token"]?.jsonPrimitive?.contentOrNull ?: return null
        val refresh = obj["refresh_token"]?.jsonPrimitive?.contentOrNull ?: ""
        val ttl = obj["expires_in"]?.jsonPrimitive?.longOrNull ?: 14_400L
        return DropboxTokens(access, refresh, nowMs() + ttl * 1000L)
    }

    /** Display name of the linked account (for the settings label). */
    suspend fun accountLabel(accessToken: String): String? = try {
        val resp = client.post("https://api.dropboxapi.com/2/users/get_current_account") {
            headers { append("Authorization", "Bearer $accessToken") }
            // This RPC takes a `null` JSON body.
            contentType(ContentType.Application.Json)
            setBody("null")
        }
        if (!resp.status.isSuccess()) null
        else (json.parseToJsonElement(resp.bodyAsText()).jsonObject["name"] as? JsonObject)
            ?.get("display_name")?.jsonPrimitive?.contentOrNull
    } catch (e: Exception) { null }

    /** Upload bytes to an App-Folder path (e.g. "/trips/foo.csv"), overwriting. */
    suspend fun uploadFile(accessToken: String, remotePath: String, bytes: ByteArray): Boolean = try {
        val arg = "{\"path\":\"$remotePath\",\"mode\":\"overwrite\",\"autorename\":false,\"mute\":true}"
        val resp = client.post("https://content.dropboxapi.com/2/files/upload") {
            headers {
                append("Authorization", "Bearer $accessToken")
                append("Dropbox-API-Arg", arg)
            }
            contentType(ContentType.Application.OctetStream)
            setBody(bytes)
        }
        resp.status.isSuccess()
    } catch (e: Exception) { false }

    private fun hexToBytes(hex: String) = ByteArray(hex.length / 2) {
        hex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }

    companion object {
        const val APP_KEY = "5auhxf7gswy7j54"
        const val REDIRECT_URI = "db-5auhxf7gswy7j54://1/connect"
        const val SCOPES = "account_info.read files.content.write files.content.read sharing.write sharing.read"
    }
}

private fun epochNow(): Long = com.eried.eucplanet.util.nowEpochMillis()
