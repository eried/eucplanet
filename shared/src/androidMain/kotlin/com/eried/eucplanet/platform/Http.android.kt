package com.eried.eucplanet.platform

import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody

actual class HttpClient actual constructor(private val onResponse: (HttpRequest, HttpResponse) -> Unit) {
    private val http = OkHttpClient.Builder().build()

    actual fun execute(request: HttpRequest): HttpResponse {
        val body = when (val b = request.body) {
            is HttpRequest.Body.Bytes -> RequestBody.create(b.contentType?.toMediaTypeOrNull(), b.bytes)
            is HttpRequest.Body.Text -> RequestBody.create(b.contentType.toMediaTypeOrNull(), b.text)
            is HttpRequest.Body.Form -> FormBody.Builder().apply {
                b.fields.forEach { (k, v) -> add(k, v) }
            }.build()
        }
        val req = Request.Builder().url(request.url).apply {
            request.headers.forEach { (k, v) -> addHeader(k, v) }
        }.post(body).build()
        return http.newCall(req).execute().use { resp ->
            HttpResponse(
                code = resp.code,
                headers = resp.headers.toMultimap().mapValues { it.value.firstOrNull().orEmpty() },
                body = resp.body?.bytes() ?: ByteArray(0),
            )
        }.also { onResponse(request, it) }
    }
}
