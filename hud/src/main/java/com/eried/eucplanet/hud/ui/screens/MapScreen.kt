package com.eried.eucplanet.hud.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.eried.eucplanet.hud.R
import com.eried.eucplanet.hud.net.HudTileCache
import com.eried.eucplanet.hud.protocol.HudState
import com.eried.eucplanet.hud.ui.parseHexColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Simple Web-Mercator XYZ tile stitcher.
 *
 * No MapLibre, no Leaflet — we draw a 4×3 (or whatever fits) grid of 256-px
 * raster tiles fetched from the phone's [HudTileCache], centred on the
 * rider's last GPS fix, then stamp a rider marker on top. Pan tracks GPS,
 * UP/DOWN on the remote zoom in/out (driven by [zoom]).
 *
 * Rationale for rolling our own instead of pulling MapLibre into the HUD
 * APK: the HUD has tight binary-size budgets, MapLibre is ~12 MB of native
 * libraries per ABI, and we don't need any of its routing/styling/vector
 * features here. We're rendering "you are here" plus the next 200 m of
 * map. A 4×3 grid does that.
 */
@Composable
fun MapScreen(hud: HudState, zoom: Float, peer: String?, cache: HudTileCache) {
    val ctx = LocalContext.current
    val accent = parseHexColor(hud.accentArgb)
    // Cache is owned by HudActivity and passed in: previously the cache
    // was remember { } inside this composable, which threw away every
    // tile when the rider switched away from the Map screen and forced
    // a re-fetch on every return. Lifting it keeps tiles warm across
    // screen navigation.

    Box(Modifier.fillMaxSize().background(Color(0xFF101010))) {
        if (!hud.gpsHasFix) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = ctx.getString(R.string.hud_map_offline),
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
            return
        }

        var size by remember { mutableStateOf(IntSize.Zero) }
        // Bitmap version counter: bumped whenever the cache loads a tile we
        // requested. Used purely as a recomposition trigger; the tile data
        // itself lives inside [cache].
        var tick by remember { mutableStateOf(0) }

        val z = zoom.toInt().coerceIn(3, 19)
        val (cx, cy) = lonLatToTileFloat(hud.longitude, hud.latitude, z)

        // Kick off async fetches for the visible tile window whenever the
        // viewport center or zoom changes. The fetch coroutine bumps `tick`
        // so the Canvas recomposes once new bitmaps arrive.
        LaunchedEffect(z, cx.toInt(), cy.toInt(), size) {
            if (size.width == 0 || size.height == 0) return@LaunchedEffect
            val cols = (size.width / 256) + 2
            val rows = (size.height / 256) + 2
            withContext(Dispatchers.IO) {
                val originX = floor(cx).toInt() - cols / 2
                val originY = floor(cy).toInt() - rows / 2
                for (dy in 0 until rows) for (dx in 0 until cols) {
                    val tx = originX + dx
                    val ty = originY + dy
                    if (tx < 0 || ty < 0) continue
                    cache.requestTile(z, tx, ty) { tick++ }
                }
            }
        }

        Canvas(
            modifier = Modifier.fillMaxSize(),
            onDraw = {
                size = IntSize(this.size.width.toInt(), this.size.height.toInt())
                // No-op recompose driver. Reading `tick` makes the Canvas
                // re-execute when tiles finish loading.
                @Suppress("UNUSED_EXPRESSION") tick

                val cols = (this.size.width / 256f).toInt() + 2
                val rows = (this.size.height / 256f).toInt() + 2
                val originX = floor(cx).toInt() - cols / 2
                val originY = floor(cy).toInt() - rows / 2
                val centerPx = Offset(this.size.width / 2f, this.size.height / 2f)
                val originTilePx = Offset(
                    centerPx.x - ((cx - originX) * 256f),
                    centerPx.y - ((cy - originY) * 256f)
                )

                // Rotate the map so the rider's direction of travel always
                // points up. Counter-rotate so the canvas coordinate frame
                // turns with the heading. NaN heading (no GPS bearing yet)
                // falls back to a north-up view.
                val headingDeg = if (hud.gpsHeadingDeg.isNaN()) 0f
                    else -hud.gpsHeadingDeg
                rotate(degrees = headingDeg, pivot = centerPx) {
                    // Draw each 256-px tile into a 258×258 destination
                    // box so adjacent tiles overlap by ~1 px on every
                    // edge. The drawImage(topLeft = ...) overload with a
                    // floating-point Offset produces visible seam lines
                    // under rotation because sub-pixel sampling at the
                    // shared edge falls into a half-pixel gap (the
                    // bilinear filter samples background pixels on both
                    // sides). The integer-offset + oversized-dst overload
                    // lets the neighbour cover the seam at the cost of
                    // <1 px of repeated edge content, which is invisible.
                    for (dy in 0 until rows) for (dx in 0 until cols) {
                        val tx = originX + dx
                        val ty = originY + dy
                        if (tx < 0 || ty < 0) continue
                        val bm = cache.peek(z, tx, ty)
                        val tlx = (originTilePx.x + dx * 256f).roundToInt()
                        val tly = (originTilePx.y + dy * 256f).roundToInt()
                        if (bm != null) {
                            drawImage(
                                image = bm.asImageBitmap(),
                                srcOffset = IntOffset.Zero,
                                srcSize = IntSize(256, 256),
                                dstOffset = IntOffset(tlx, tly),
                                dstSize = IntSize(258, 258),
                                filterQuality = FilterQuality.Low
                            )
                        } else {
                            drawRect(
                                color = Color(0xFF1A1A1A),
                                topLeft = Offset(tlx.toFloat(), tly.toFloat()),
                                size = Size(258f, 258f)
                            )
                        }
                    }
                }

                // Rider marker: filled accent dot with a white ring so it
                // reads against any map colour. Drawn OUTSIDE the rotate
                // block so it stays straight (it's a fixed compass-rose
                // marker, not part of the rotating world).
                drawCircle(color = Color.White, radius = 12.dp.toPx(), center = centerPx)
                drawCircle(color = accent, radius = 9.dp.toPx(), center = centerPx)
            }
        )

        // Zoom indicator (bottom-left).
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp)
                .background(Color(0xAA000000))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text("Z $z", color = Color.White, fontSize = 14.sp)
        }

        // Speed overlay (bottom-right). Uses the rider's preferred unit
        // for consistency with the Dashboard + Camera screens.
        val displaySpeed = com.eried.eucplanet.hud.ui.HudUnits.speed(hud.speedKmh, hud.unitSpeed)
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp)
                .background(Color(0xAA000000))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            androidx.compose.foundation.layout.Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "%.0f".format(displaySpeed),
                    color = accent,
                    fontSize = 22.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Text(
                    text = " ${com.eried.eucplanet.hud.ui.HudUnits.speedSuffix(hud.unitSpeed)}",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 12.sp
                )
            }
        }
    }
}

/** WGS84 (lon, lat) -> fractional XYZ tile coordinates at zoom z. Returns
 *  (tileX, tileY) where the integer parts are the tile indices and the
 *  fractional parts give the pixel offset inside that tile. */
private fun lonLatToTileFloat(lon: Double, lat: Double, z: Int): Pair<Float, Float> {
    val n = (1 shl z).toDouble()
    val x = (lon + 180.0) / 360.0 * n
    val latRad = lat * PI / 180.0
    val y = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n
    return x.toFloat() to y.toFloat()
}

/** Inverse of [lonLatToTileFloat] — kept here in case the navigator screen
 *  wants to project the next-turn marker onto the same tile grid later. */
@Suppress("unused")
private fun tileToLonLat(x: Double, y: Double, z: Int): Pair<Double, Double> {
    val n = (1 shl z).toDouble()
    val lonDeg = x / n * 360.0 - 180.0
    val latRad = atan(sinh(PI * (1.0 - 2.0 * y / n)))
    return lonDeg to (latRad * 180.0 / PI)
}

@Suppress("unused")
private fun stubKeepImports() {
    // Suppress "unused import" lints for BitmapFactory / Dispatchers etc.
    BitmapFactory.Options()
}
