package com.eried.eucplanet.data.store

import com.eried.eucplanet.data.model.OverlayElement
import com.eried.eucplanet.data.model.OverlayElementType
import com.eried.eucplanet.data.model.OverlayPreset
import com.eried.eucplanet.data.model.ViewportConfig
import com.eried.eucplanet.data.model.ViewportLayout
import com.eried.eucplanet.data.model.ViewportSourceType
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serialises an [OverlayPreset] to / from JSON. Every field is read with an
 * `optX(name, default)` against a fresh default object, so a preset written by
 * an older or newer app version still loads — unknown fields are ignored,
 * missing fields keep their default. User images live inline as base64, so the
 * `.json` is fully self-contained and portable between phones.
 */
object OverlayPresetJson {

    /** Bumped only if the schema changes incompatibly; readers stay tolerant. */
    const val VERSION = 1

    fun toJson(preset: OverlayPreset): JSONObject = JSONObject().apply {
        put("version", VERSION)
        put("name", preset.name)
        put("layout", preset.layout.name)
        put("dividers", JSONArray().apply { preset.dividers.forEach { put(it.toDouble()) } })
        put("dividerColor", preset.dividerColor)
        put("dividerThickness", preset.dividerThickness.toDouble())
        put("viewports", JSONArray().apply {
            preset.viewports.forEach { vp ->
                put(JSONObject().apply {
                    put("source", vp.source.name)
                    put("cameraKey", vp.cameraKey)
                    put("solidColor", vp.solidColor)
                    if (vp.imageData != null) put("imageData", vp.imageData)
                    put("gradientColors", JSONArray().apply {
                        vp.gradientColors.forEach { put(it) }
                    })
                    put("gradientStops", JSONArray().apply {
                        vp.gradientStops.forEach { put(it.toDouble()) }
                    })
                    put("gradientAngle", vp.gradientAngle.toDouble())
                    put("gradientRadial", vp.gradientRadial)
                })
            }
        })
        put("elements", JSONArray().apply {
            preset.elements.forEach { el -> put(elementToJson(el)) }
        })
    }

    fun fromJson(json: JSONObject): OverlayPreset {
        val layout = enumOr(json.optString("layout"), ViewportLayout.SINGLE)
        val dividers = json.optJSONArray("dividers")?.let { arr ->
            (0 until arr.length()).map { arr.optDouble(it, 0.5).toFloat() }
        } ?: layout.defaultDividers()
        val viewports = json.optJSONArray("viewports")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::viewportFromJson) }
        }.orEmpty().ifEmpty { listOf(ViewportConfig()) }
        val elements = json.optJSONArray("elements")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::elementFromJson) }
        }.orEmpty()
        val default = OverlayPreset()
        return OverlayPreset(
            name = json.optString("name", ""),
            layout = layout,
            dividers = dividers,
            viewports = viewports,
            elements = elements,
            dividerColor = json.optLong("dividerColor", default.dividerColor),
            dividerThickness = json.optDouble(
                "dividerThickness", default.dividerThickness.toDouble()
            ).toFloat()
        ).normalized() // keep viewport / divider counts consistent with layout
    }

    private fun viewportFromJson(o: JSONObject): ViewportConfig {
        val d = ViewportConfig()
        // Back-compat: pre-multicamera presets stored a cameraFront boolean.
        val cameraKey = when {
            o.has("cameraKey") -> o.optString("cameraKey", d.cameraKey)
            o.has("cameraFront") -> if (o.optBoolean("cameraFront")) "FRONT" else "BACK"
            else -> d.cameraKey
        }
        return ViewportConfig(
            source = enumOr(o.optString("source"), d.source),
            cameraKey = cameraKey,
            solidColor = o.optLong("solidColor", d.solidColor),
            imageData = if (o.has("imageData")) o.optString("imageData") else d.imageData,
            gradientColors = o.optJSONArray("gradientColors")?.let { arr ->
                (0 until arr.length()).map { arr.optLong(it) }
            }?.takeIf { it.isNotEmpty() } ?: d.gradientColors,
            gradientStops = o.optJSONArray("gradientStops")?.let { arr ->
                (0 until arr.length()).map { arr.optDouble(it, 0.0).toFloat() }
            }?.takeIf { it.isNotEmpty() } ?: d.gradientStops,
            gradientAngle = o.optDouble("gradientAngle", d.gradientAngle.toDouble()).toFloat(),
            gradientRadial = o.optBoolean("gradientRadial", d.gradientRadial)
        )
    }

    private fun elementToJson(el: OverlayElement): JSONObject = JSONObject().apply {
        put("id", el.id)
        put("type", el.type.name)
        put("x", el.x.toDouble())
        put("y", el.y.toDouble())
        put("width", el.width.toDouble())
        put("rotationDeg", el.rotationDeg.toDouble())
        put("opacity", el.opacity.toDouble())
        put("metric", el.metric)
        put("showLabel", el.showLabel)
        put("text", el.text)
        put("textAlign", el.textAlign)
        put("badgeStacked", el.badgeStacked)
        put("badgeShowVersion", el.badgeShowVersion)
        put("graphWindowSec", el.graphWindowSec)
        put("gaugeMax", el.gaugeMax.toDouble())
        put("foreground", el.foreground)
        put("background", el.background)
        put("cameraKey", el.cameraKey)
        if (el.imageData != null) put("imageData", el.imageData)
        put("chromaKeyEnabled", el.chromaKeyEnabled)
        put("chromaKeyColor", el.chromaKeyColor)
        put("chromaKeyTolerance", el.chromaKeyTolerance.toDouble())
        put("clockStyle", el.clockStyle)
        put("clockShowDate", el.clockShowDate)
        put("mapStyle", el.mapStyle)
        put("mapZoom", el.mapZoom)
        put("mapRotateWithHeading", el.mapRotateWithHeading)
        put("mapTrace", el.mapTrace)
    }

    private fun elementFromJson(o: JSONObject): OverlayElement? {
        val type = runCatching { OverlayElementType.valueOf(o.optString("type")) }
            .getOrNull() ?: return null
        val d = OverlayElement(type = type)
        return OverlayElement(
            id = o.optString("id", d.id),
            type = type,
            x = o.optDouble("x", d.x.toDouble()).toFloat(),
            y = o.optDouble("y", d.y.toDouble()).toFloat(),
            width = o.optDouble("width", d.width.toDouble()).toFloat(),
            rotationDeg = o.optDouble("rotationDeg", d.rotationDeg.toDouble()).toFloat(),
            opacity = o.optDouble("opacity", d.opacity.toDouble()).toFloat(),
            metric = o.optString("metric", d.metric),
            showLabel = o.optBoolean("showLabel", d.showLabel),
            text = o.optString("text", d.text),
            textAlign = o.optString("textAlign", d.textAlign),
            badgeStacked = o.optBoolean("badgeStacked", d.badgeStacked),
            badgeShowVersion = o.optBoolean("badgeShowVersion", d.badgeShowVersion),
            graphWindowSec = o.optInt("graphWindowSec", d.graphWindowSec),
            gaugeMax = o.optDouble("gaugeMax", d.gaugeMax.toDouble()).toFloat(),
            foreground = o.optLong("foreground", d.foreground),
            background = o.optLong("background", d.background),
            cameraKey = when {
                o.has("cameraKey") -> o.optString("cameraKey", d.cameraKey)
                o.has("cameraFront") -> if (o.optBoolean("cameraFront")) "FRONT" else "BACK"
                else -> d.cameraKey
            },
            imageData = if (o.has("imageData")) o.optString("imageData") else null,
            chromaKeyEnabled = o.optBoolean("chromaKeyEnabled", d.chromaKeyEnabled),
            chromaKeyColor = o.optLong("chromaKeyColor", d.chromaKeyColor),
            chromaKeyTolerance = o.optDouble(
                "chromaKeyTolerance", d.chromaKeyTolerance.toDouble()
            ).toFloat(),
            clockStyle = o.optString("clockStyle", d.clockStyle),
            clockShowDate = o.optBoolean("clockShowDate", d.clockShowDate),
            mapStyle = o.optString("mapStyle", d.mapStyle),
            mapZoom = o.optInt("mapZoom", d.mapZoom),
            mapRotateWithHeading = o.optBoolean(
                "mapRotateWithHeading", d.mapRotateWithHeading
            ),
            mapTrace = o.optBoolean("mapTrace", d.mapTrace)
        )
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default
}
