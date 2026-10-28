package com.eried.eucplanet.platform

/**
 * The few HTTP calls shared code makes (Dropbox's REST API), blocking, to run
 * inside `withContext(Dispatchers.IO)` the way the OkHttp calls ran.
 *
 * Android sends them through OkHttp exactly as before: a [Body.Text] goes out
 * as `RequestBody.create(mediaType, text)` (OkHttp adds `; charset=utf-8`), a
 * [Body.Form] as a `FormBody`, so the bytes on the wire do not change. iOS
 * sends the same requests through NSURLSession.
 */
class HttpRequest(
    val url: String,
    val body: Body,
    val headers: List<Pair<String, String>> = emptyList(),
) {
    sealed interface Body {
        /** Raw bytes; [contentType] null sends no Content-Type, like `create(null, bytes)`. */
        class Bytes(val contentType: String?, val bytes: ByteArray) : Body
        /** Text with a content type that gains `; charset=utf-8`, like `create(type, text)`. */
        class Text(val contentType: String, val text: String) : Body
        /** `application/x-www-form-urlencoded`, like OkHttp's FormBody. */
        class Form(val fields: List<Pair<String, String>>) : Body
    }
}

/** A complete response: the body is read in full, as `body.string()` did. */
class HttpResponse(
    val code: Int,
    headers: Map<String, String>,
    val body: ByteArray,
) {
    private val headers = headers.mapKeys { it.key.lowercase() }

    val isSuccessful: Boolean get() = code in 200..299

    fun header(name: String): String? = headers[name.lowercase()]

    fun text(): String = body.decodeToString()

    /** The first [byteCount] bytes as text, like OkHttp's `peekBody(n).string()`. */
    fun peekText(byteCount: Int): String = body.copyOf(minOf(byteCount, body.size)).decodeToString()
}

/**
 * [onResponse] sees every response before its caller does, which is where a
 * refusal is noticed whichever call it lands on (OkHttp's interceptor did this).
 * [execute] throws when no response arrives.
 */
expect class HttpClient(onResponse: (HttpRequest, HttpResponse) -> Unit) {
    fun execute(request: HttpRequest): HttpResponse
}
