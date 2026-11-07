package com.eried.eucplanet.platform

import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.util.concurrent.TimeUnit

actual class HttpClient actual constructor(
    connectTimeoutMs: Long?,
    readTimeoutMs: Long?,
    private val onResponse: (HttpRequest, HttpResponse) -> Unit,
) {
    private val http = OkHttpClient.Builder().apply {
        connectTimeoutMs?.let { connectTimeout(it, TimeUnit.MILLISECONDS) }
        readTimeoutMs?.let { readTimeout(it, TimeUnit.MILLISECONDS) }
    }.build()

    actual fun execute(request: HttpRequest): HttpResponse {
        val body = when (val b = request.body) {
            HttpRequest.Body.None -> null
            is HttpRequest.Body.Bytes -> RequestBody.create(b.contentType?.toMediaTypeOrNull(), b.bytes)
            is HttpRequest.Body.Text -> RequestBody.create(b.contentType.toMediaTypeOrNull(), b.text)
            is HttpRequest.Body.Form -> FormBody.Builder().apply {
                b.fields.forEach { (k, v) -> add(k, v) }
            }.build()
            is HttpRequest.Body.Multipart -> MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                b.parts.forEach { p ->
                    if (p.fileName == null) addFormDataPart(p.name, p.bytes.decodeToString())
                    else addFormDataPart(
                        p.name, p.fileName,
                        RequestBody.create(p.contentType?.toMediaTypeOrNull(), p.bytes),
                    )
                }
            }.build()
        }
        val req = Request.Builder().url(request.url).apply {
            request.headers.forEach { (k, v) -> addHeader(k, v) }
        }.apply {
            when (request.method) {
                null -> if (body == null) get() else post(body)
                "DELETE" -> if (body == null) delete() else delete(body)
                else -> method(request.method, body)
            }
        }.build()
        return http.newCall(req).execute().use { resp ->
            HttpResponse(
                code = resp.code,
                headers = resp.headers.toMultimap().mapValues { it.value.firstOrNull().orEmpty() },
                body = resp.body?.bytes() ?: ByteArray(0),
            )
        }.also { onResponse(request, it) }
    }
}
