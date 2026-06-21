package com.eried.eucplanet.cloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.random.Random

private val JSON = Json { ignoreUnknownKeys = true }

/**
 * Canonical JSON identical to the server's
 * `json.dumps(sort_keys=True, separators=(",",":"), ensure_ascii=False)` — used to
 * compute the attestation `request_hash`. Port of Android's `CanonicalJson`. The
 * envelope is float-free by design (MetaBuilder omits floats), so number handling
 * is just the primitive's literal text.
 */
fun canonicalJson(el: JsonElement): String = when (el) {
    is JsonObject -> el.entries.sortedBy { it.key }
        .joinToString(",", "{", "}") { (k, v) -> "${jsonQuote(k)}:${canonicalJson(v)}" }
    is JsonArray -> el.joinToString(",", "[", "]") { canonicalJson(it) }
    is JsonPrimitive -> if (el.isString) jsonQuote(el.content) else el.content // numbers/bools/null literal
}

private fun jsonQuote(s: String): String = buildString {
    append('"')
    for (c in s) when (c) {
        '\\' -> append("\\\\")
        '"' -> append("\\\"")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> if (c < ' ') append("\\u").append(c.code.toString(16).padStart(4, '0')) else append(c)
    }
    append('"')
}

/**
 * Add the stub attestation to a payload/meta envelope: strip any existing
 * `attestation`, hash the canonical form, then attach `{type:"play_integrity",
 * token:"", request_hash:<sha256>}`. The dev server accepts the empty (stub)
 * token; real App Attest can replace this later. Returns the serialized envelope.
 */
fun withStubAttestation(payloadJson: String): String {
    val obj = JSON.parseToJsonElement(payloadJson).jsonObject
    val stripped = JsonObject(obj.filterKeys { it != "attestation" })
    val hash = sha256Hex(canonicalJson(stripped).encodeToByteArray())
    val out = buildJsonObject {
        stripped.forEach { (k, v) -> put(k, v) }
        putJsonObject("attestation") {
            put("type", "play_integrity")
            put("token", "")
            put("request_hash", hash)
        }
    }
    return JSON.encodeToString(JsonObject.serializer(), out)
}

/** RFC-4122 v4 UUID (lowercase) for store_id / trip_uuid. Pure Kotlin. */
fun uuid4(): String {
    val b = ByteArray(16)
    Random.nextBytes(b)
    b[6] = ((b[6].toInt() and 0x0f) or 0x40).toByte() // version 4
    b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte() // variant 1
    fun h(i: Int) = (b[i].toInt() and 0xff).toString(16).padStart(2, '0')
    return "${h(0)}${h(1)}${h(2)}${h(3)}-${h(4)}${h(5)}-${h(6)}${h(7)}-${h(8)}${h(9)}-${h(10)}${h(11)}${h(12)}${h(13)}${h(14)}${h(15)}"
}
