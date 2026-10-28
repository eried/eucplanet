package com.eried.eucplanet.platform

/**
 * The part of `org.json` shared code uses, same names, so a moved file only
 * swaps the import. Android is the framework's own `org.json`, so the phone
 * app reads and writes exactly what it did. iOS is a port of the same AOSP
 * classes, so a settings file written on one phone reads back the same on the
 * other: key order, number text and string escapes all match.
 */
expect open class JSONException(message: String?) : Exception

expect class JSONObject {
    constructor()
    constructor(json: String)
    constructor(copyFrom: Map<*, *>)

    fun length(): Int
    fun has(name: String): Boolean
    fun isNull(name: String): Boolean
    fun opt(name: String): Any?
    fun remove(name: String): Any?
    fun keys(): Iterator<String>
    fun names(): JSONArray?

    fun put(name: String, value: Boolean): JSONObject
    fun put(name: String, value: Double): JSONObject
    fun put(name: String, value: Int): JSONObject
    fun put(name: String, value: Long): JSONObject
    fun put(name: String, value: Any?): JSONObject
    fun putOpt(name: String?, value: Any?): JSONObject

    fun get(name: String): Any
    fun getBoolean(name: String): Boolean
    fun getDouble(name: String): Double
    fun getInt(name: String): Int
    fun getLong(name: String): Long
    fun getString(name: String): String
    fun getJSONArray(name: String): JSONArray
    fun getJSONObject(name: String): JSONObject

    fun optBoolean(name: String): Boolean
    fun optBoolean(name: String, fallback: Boolean): Boolean
    fun optDouble(name: String): Double
    fun optDouble(name: String, fallback: Double): Double
    fun optInt(name: String): Int
    fun optInt(name: String, fallback: Int): Int
    fun optLong(name: String): Long
    fun optLong(name: String, fallback: Long): Long
    fun optString(name: String): String
    fun optString(name: String, fallback: String): String
    fun optJSONArray(name: String): JSONArray?
    fun optJSONObject(name: String): JSONObject?

    fun toString(indentSpaces: Int): String
}

expect class JSONArray {
    constructor()
    constructor(json: String)
    constructor(copyFrom: Collection<*>)

    fun length(): Int
    fun isNull(index: Int): Boolean
    fun opt(index: Int): Any?
    fun remove(index: Int): Any?

    fun put(value: Boolean): JSONArray
    fun put(value: Double): JSONArray
    fun put(value: Int): JSONArray
    fun put(value: Long): JSONArray
    fun put(value: Any?): JSONArray

    fun get(index: Int): Any
    fun getBoolean(index: Int): Boolean
    fun getDouble(index: Int): Double
    fun getInt(index: Int): Int
    fun getLong(index: Int): Long
    fun getString(index: Int): String
    fun getJSONArray(index: Int): JSONArray
    fun getJSONObject(index: Int): JSONObject

    fun optBoolean(index: Int): Boolean
    fun optBoolean(index: Int, fallback: Boolean): Boolean
    fun optDouble(index: Int): Double
    fun optDouble(index: Int, fallback: Double): Double
    fun optInt(index: Int): Int
    fun optInt(index: Int, fallback: Int): Int
    fun optLong(index: Int): Long
    fun optLong(index: Int, fallback: Long): Long
    fun optString(index: Int): String
    fun optString(index: Int, fallback: String): String
    fun optJSONArray(index: Int): JSONArray?
    fun optJSONObject(index: Int): JSONObject?

    fun toString(indentSpaces: Int): String
}

/** `JSONObject.NULL`: the value an explicit `null` in the text reads back as. */
expect val JSONNull: Any

/** `JSONObject.quote`: [value] as a JSON string literal, quotes included. */
expect fun jsonQuote(value: String?): String
