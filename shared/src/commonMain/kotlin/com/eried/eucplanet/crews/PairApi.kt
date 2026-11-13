package com.eried.eucplanet.crews

import com.eried.eucplanet.platform.HttpClient
import com.eried.eucplanet.platform.HttpRequest
import com.eried.eucplanet.platform.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/** What the server says a pairing would grant, shown before the rider approves anything. */
data class PairOffer(
    val scope: String,
    val grants: List<String>,
    val expiresInSeconds: Int,
)

sealed interface PairResult {
    data object Ok : PairResult
    /** The code expired, was already used, or never existed. */
    data object Expired : PairResult
    data object RateLimited : PairResult
    data class Failed(val code: Int, val detail: String?) : PairResult
    data object Unreachable : PairResult
}

/**
 * The app's half of the crews pairing handshake: two calls, and that is the whole of the
 * phone's involvement in crews.
 *
 * It takes the server origin per call rather than from the injected base url, because the
 * origin comes off the scanned code. Everything else the app does keeps using the compiled-in
 * base url; nothing here changes where rides are uploaded.
 */
class PairApi(private val client: HttpClient) {

    /**
     * Ask what a code is for, before showing an approve button.
     *
     * A confirmation screen that only says "Allow?" is a screen people learn to tap through,
     * so the app asks the server what it is being asked to approve first. The approve
     * button only shows once the server has answered.
     */
    suspend fun describe(link: PairLink): PairOffer? = withContext(Dispatchers.IO) {
        val req = HttpRequest("${link.apiBase}/pair/describe?code=${link.code}", HttpRequest.Body.None)
        runCatching {
            client.execute(req).let { resp ->
                if (!resp.isSuccessful) return@let null
                val o = JSONObject(resp.text())
                val grants = o.optJSONArray("grants")
                PairOffer(
                    scope = o.optString("scope", "crew"),
                    grants = buildList {
                        for (i in 0 until (grants?.length() ?: 0)) add(grants!!.optString(i))
                    },
                    expiresInSeconds = o.optInt("expires_in", 0),
                )
            }
        }.getOrNull()
    }

    /**
     * Approve it: tell the server this code belongs to this rider.
     *
     * The store_id leaves the phone here and nowhere else in this flow, which is the reason
     * [PairLink.trust] decides whether the host is allowed before this is ever called.
     */
    suspend fun confirm(link: PairLink, storeId: String): PairResult = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("code", link.code)
            .put("store_id", storeId)
        // "application/json; charset=utf-8", as OkHttp sent it.
        val req = HttpRequest("${link.apiBase}/pair/confirm", HttpRequest.Body.Text("application/json", payload.toString()))
        try {
            client.execute(req).let { resp ->
                val body = resp.text()
                when {
                    resp.isSuccessful -> PairResult.Ok
                    resp.code == 410 -> PairResult.Expired
                    resp.code == 429 -> PairResult.RateLimited
                    else -> {
                        val detail = runCatching {
                            JSONObject(body).optString("detail").ifEmpty { null }
                        }.getOrNull()
                        PairResult.Failed(resp.code, detail)
                    }
                }
            }
        } catch (e: Exception) {
            PairResult.Unreachable      // no connectivity, bad host, timeout
        }
    }
}
