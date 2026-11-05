package com.eried.eucplanet.platform

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequestUseProtocolCachePolicy
import platform.Foundation.NSURLSession
import platform.Foundation.addValue
import platform.Foundation.create
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual class HttpClient actual constructor(
    connectTimeoutMs: Long?,
    private val readTimeoutMs: Long?,
    private val onResponse: (HttpRequest, HttpResponse) -> Unit,
) {

    actual fun execute(request: HttpRequest): HttpResponse {
        val url = NSURL.URLWithString(request.url) ?: throw IllegalArgumentException("Bad URL ${request.url}")
        // NSURLSession has one per-request timeout, covering the wait for data;
        // 60 s is its own default.
        val req = NSMutableURLRequest.requestWithURL(
            url, NSURLRequestUseProtocolCachePolicy, (readTimeoutMs ?: 60_000L) / 1000.0,
        )
        request.headers.forEach { (k, v) -> req.addValue(v, forHTTPHeaderField = k) }
        val (type, bytes) = when (val b = request.body) {
            HttpRequest.Body.None -> null to null
            is HttpRequest.Body.Bytes -> b.contentType to b.bytes
            is HttpRequest.Body.Text -> "${b.contentType}; charset=utf-8" to b.text.encodeToByteArray()
            is HttpRequest.Body.Form -> "application/x-www-form-urlencoded" to
                b.fields.joinToString("&") { (k, v) -> formEncode(k) + "=" + formEncode(v) }.encodeToByteArray()
        }
        req.setHTTPMethod(if (bytes == null) "GET" else "POST")
        type?.let { req.setValue(it, forHTTPHeaderField = "Content-Type") }
        bytes?.let { req.setHTTPBody(it.toNSData()) }

        val done = dispatch_semaphore_create(0)
        var result: HttpResponse? = null
        var failure: String? = null
        NSURLSession.sharedSession.dataTaskWithRequest(req) { data, response, error ->
            val http = response as? NSHTTPURLResponse
            if (http == null) {
                failure = error?.localizedDescription ?: "no response"
            } else {
                val headers = http.allHeaderFields.entries.associate { (k, v) -> k.toString() to v.toString() }
                result = HttpResponse(http.statusCode.toInt(), headers, data?.toByteArray() ?: ByteArray(0))
            }
            dispatch_semaphore_signal(done)
        }.resume()
        dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER)
        val resp = result ?: throw IllegalStateException(failure ?: "no response")
        onResponse(request, resp)
        return resp
    }

    /** OkHttp's FormBody encoding: everything outside the unreserved set, space as %20. */
    private fun formEncode(s: String): String = buildString {
        for (b in s.encodeToByteArray()) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "-._~*") append(ch)
            else append('%').append(c.toString(16).uppercase().padStart(2, '0'))
        }
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val n = length.toInt()
    if (n == 0) return ByteArray(0)
    return ByteArray(n).apply { usePinned { memcpy(it.addressOf(0), bytes, length) } }
}
