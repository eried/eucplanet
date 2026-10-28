package com.eried.eucplanet.platform

actual typealias JSONException = org.json.JSONException
actual typealias JSONObject = org.json.JSONObject
actual typealias JSONArray = org.json.JSONArray

actual val JSONNull: Any get() = org.json.JSONObject.NULL

actual fun jsonQuote(value: String?): String = org.json.JSONObject.quote(value)
