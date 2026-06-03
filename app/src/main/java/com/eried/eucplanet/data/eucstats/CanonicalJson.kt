package com.eried.eucplanet.data.eucstats

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Serializes a meta envelope to the SAME bytes as the server's
 *  json.dumps(sort_keys=True, separators=(",",":"), ensure_ascii=False),
 *  with the `attestation` key removed, then SHA-256 (lowercase hex). */
object CanonicalJson {

    fun requestHash(meta: JSONObject): String {
        val stripped = JSONObject(meta.toString())
        stripped.remove("attestation")
        val canon = canonical(stripped)
        val digest = MessageDigest.getInstance("SHA-256").digest(canon.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { k ->
            "${quote(k)}:${canonical(value.get(k))}"
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> quote(value)
        is Boolean -> if (value) "true" else "false"
        is Int, is Long -> value.toString()
        is Double -> if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
        else -> quote(value.toString())
    }

    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when (c) {
            '\\' -> sb.append("\\\\"); '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n"); '\r' -> sb.append("\\r"); '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        return sb.append("\"").toString()
    }
}
