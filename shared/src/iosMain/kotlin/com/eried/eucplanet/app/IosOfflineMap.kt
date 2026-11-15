package com.eried.eucplanet.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import com.eried.eucplanet.data.model.OfflineRoutingMode
import com.eried.eucplanet.nav.offline.map.OfflineMapDrawer
import com.eried.eucplanet.nav.offline.map.OfflineMapJs
import com.eried.eucplanet.nav.offline.map.TileCanvas
import com.eried.eucplanet.nav.offline.map.TilePath
import com.eried.eucplanet.nav.offline.map.TileText
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.platform.toNSData
import com.eried.eucplanet.resources.Res
import com.eried.eucplanet.ui.settings.offline.OfflineDataHost
import com.eried.eucplanet.ui.settings.offline.OfflineGridPage
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.PaintStrokeCap
import org.jetbrains.skia.PaintStrokeJoin
import org.jetbrains.skia.Path
import org.jetbrains.skia.PathBuilder
import org.jetbrains.skia.PathEffect
import org.jetbrains.skia.PathFillMode
import org.jetbrains.skia.PathMeasure
import org.jetbrains.skia.RSXform
import org.jetbrains.skia.Surface
import org.jetbrains.skia.TextBlob
import org.jetbrains.skia.Typeface
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSByteCountFormatter
import platform.Foundation.NSByteCountFormatterCountStyleFile
import platform.Foundation.NSHTTPURLResponse
import platform.UIKit.UIColor
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKURLSchemeHandlerProtocol
import platform.WebKit.WKURLSchemeTaskProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_set_target_queue
import platform.darwin.dispatch_get_global_queue
import platform.darwin.DISPATCH_QUEUE_PRIORITY_LOW

/**
 * The downloaded map on the iPhone: the shared [OfflineMapDrawer] drawing
 * with Skia, as PNG for the Leaflet maps ([IosOfflineMapScheme]) and as an
 * image for the studio's map element (IosMapTiles). Android draws the same
 * calls on its Canvas (OfflineMapRenderer).
 */
internal object IosOfflineMap {
    /** Leaflet asks for this; [IosOfflineMapScheme] answers it. */
    const val SCHEME = "eucoffline"
    private const val TILE_URL = "$SCHEME://tile/{z}/{x}/{y}.png"

    /** One handler for every web view (an NSObject subclass cannot be a Kotlin object). */
    private val scheme by lazy { IosOfflineMapScheme() }

    /** Leaflet's tiles are drawn at twice their size, as Android's, so text stays sharp. */
    const val WEB_PIXELS = 512

    val drawer: OfflineMapDrawer by lazy { OfflineMapDrawer(IosOfflineStorage.dir, IosOfflineFiles) }

    /** The page's `eucOfflineMap`, following the rider's "Prefer offline". */
    val js: String
        get() = OfflineMapJs.js(
            TILE_URL,
            IosAppGraph.settings.current.value.offlineRouting.mode == OfflineRoutingMode.PREFER,
        )

    /** The tile as PNG bytes, or null when no square has it. */
    fun png(z: Int, x: Int, y: Int, size: Int): ByteArray? =
        image(z, x, y, size)?.encodeToData(EncodedImageFormat.PNG, 100)?.bytes

    /** The tile for a Compose canvas, or null when no square has it. */
    fun bitmap(z: Int, x: Int, y: Int, size: Int): ImageBitmap? = image(z, x, y, size)?.toComposeImageBitmap()

    private fun image(z: Int, x: Int, y: Int, size: Int): Image? = runCatching {
        drawer.render(z, x, y, size, { SkiaTileCanvas(it) }) { it.finish() }
    }.onFailure { Log.w("OfflineMap", "$z/$x/$y: $it") }.getOrNull()

    /** A web view set up for a Leaflet page with the offline map in it, its scripts in [content]. */
    fun configuration(content: WKUserContentController = WKUserContentController()): WKWebViewConfiguration =
        WKWebViewConfiguration().apply {
            setURLSchemeHandler(scheme, forURLScheme = SCHEME)
            IosNetwork.blockWeb(content)
            userContentController = content
        }
}

/** [TileCanvas] on a Skia raster surface, standing in for Android's Canvas and Paint. */
internal class SkiaTileCanvas(size: Int) : TileCanvas {
    private val surface = Surface.makeRasterN32Premul(size, size)
    private val c = surface.canvas
    override val width = size
    override val height = size

    /** The calls kept until the path is drawn: Skia builds a path in one go (PathBuilder). */
    private class P : TilePath {
        private val ops = ArrayList<Float>()
        private var evenOdd = false
        private var built: Path? = null
        val path: Path
            get() = built ?: PathBuilder(if (evenOdd) PathFillMode.EVEN_ODD else PathFillMode.WINDING).let { b ->
                var i = 0
                while (i < ops.size) {
                    when (ops[i]) {
                        0f -> { b.moveTo(ops[i + 1], ops[i + 2]); i += 3 }
                        1f -> { b.lineTo(ops[i + 1], ops[i + 2]); i += 3 }
                        else -> { b.closePath(); i += 1 }
                    }
                }
                b.detach().also { built = it }
            }
        override fun moveTo(x: Float, y: Float) { ops += 0f; ops += x; ops += y; built = null }
        override fun lineTo(x: Float, y: Float) { ops += 1f; ops += x; ops += y; built = null }
        override fun close() { ops += 2f; built = null }
        override fun evenOdd() { evenOdd = true; built = null }
    }

    private val TilePath.path get() = (this as P).path

    override fun newPath(): TilePath = P()

    override fun drawColor(argb: Int) {
        c.drawPaint(Paint().apply { color = argb })
    }

    override fun fill(path: TilePath, argb: Int) {
        c.drawPath(path.path, Paint().apply {
            isAntiAlias = true
            mode = PaintMode.FILL
            color = argb
        })
    }

    override fun stroke(path: TilePath, argb: Int, width: Float, dash: Float) {
        c.drawPath(path.path, Paint().apply {
            isAntiAlias = true
            mode = PaintMode.STROKE
            strokeCap = PaintStrokeCap.ROUND
            strokeJoin = PaintStrokeJoin.ROUND
            strokeWidth = width
            color = argb
            if (dash > 0f) pathEffect = PathEffect.makeDash(floatArrayOf(dash, dash), 0f)
        })
    }

    private fun font(style: TileText) = Font(if (style.bold) bold else regular, style.size)

    private fun textPaint(style: TileText) = Paint().apply {
        isAntiAlias = true
        color = style.argb
    }

    private fun haloPaint(style: TileText) = Paint().apply {
        isAntiAlias = true
        mode = PaintMode.STROKE
        color = style.haloArgb
        strokeWidth = style.haloWidth
        strokeJoin = if (style.roundHalo) PaintStrokeJoin.ROUND else PaintStrokeJoin.MITER
    }

    // Without a font the labels are left out: Skia's empty default crashed measuring.
    private fun hasFont(style: TileText) = (if (style.bold) bold else regular) != null

    override fun measureText(text: String, style: TileText): Float =
        if (hasFont(style)) font(style).measureTextWidth(text) else 0f

    override fun drawText(text: String, x: Float, y: Float, style: TileText) {
        if (!hasFont(style)) return
        val f = font(style)
        val left = if (style.centered) x - f.measureTextWidth(text) / 2 else x
        c.drawString(text, left, y, f, haloPaint(style))
        c.drawString(text, left, y, f, textPaint(style))
    }

    /** Each glyph along the path, as Android's drawTextOnPath sets them; past the end they are left off. */
    override fun drawTextOnPath(text: String, path: TilePath, hOffset: Float, vOffset: Float, style: TileText) {
        if (!hasFont(style)) return
        val f = font(style)
        val glyphs = f.getStringGlyphs(text)
        val widths = f.getWidths(glyphs)
        val pm = PathMeasure(path.path, false, 1f)
        val len = pm.length
        val keep = ArrayList<Short>()
        val xforms = ArrayList<RSXform>()
        var at = hOffset
        for (i in glyphs.indices) {
            val w = widths[i]
            val mid = at + w / 2
            at += w
            if (mid < 0 || mid > len) continue
            val p = pm.getPosition(mid) ?: continue
            val t = pm.getTangent(mid) ?: continue
            // The glyph's left end on its baseline, [vOffset] to the right of the path's way.
            val tx = p.x - t.x * w / 2 - t.y * vOffset
            val ty = p.y - t.y * w / 2 + t.x * vOffset
            keep += glyphs[i]
            xforms += RSXform(t.x, t.y, tx, ty)
        }
        if (keep.isEmpty()) return
        val blob = TextBlob.makeFromRSXform(keep.toShortArray(), xforms.toTypedArray(), f) ?: return
        c.drawTextBlob(blob, 0f, 0f, haloPaint(style))
        c.drawTextBlob(blob, 0f, 0f, textPaint(style))
    }

    override fun length(path: TilePath): Float = PathMeasure(path.path, false, 1f).length

    override fun bounds(path: TilePath): FloatArray {
        val b = path.path.computeTightBounds()
        return floatArrayOf(b.left, b.top, b.right, b.bottom)
    }

    fun finish(): Image = surface.makeImageSnapshot().also { surface.close() }

    private companion object {
        val regular: Typeface? by lazy { systemFace(FontStyle.NORMAL) }
        val bold: Typeface? by lazy { systemFace(FontStyle.BOLD) }

        /** A named system family: the unnamed default draws no glyphs on iOS. */
        fun systemFace(style: FontStyle): Typeface? =
            listOf("Helvetica Neue", "Helvetica", "Arial", null).firstNotNullOfOrNull { family ->
                FontMgr.default.matchFamilyStyle(family, style)
                    ?.takeIf { Font(it, 12f).measureTextWidth("Oslo") > 0f }
            }
    }
}

/**
 * Answers the Leaflet maps' `eucoffline://tile/z/x/y.png` with a drawn tile,
 * or an empty 404 when no square has it, as Android's WebView intercept
 * (OfflineMapWeb.intercept) does for its host. Drawn off the main thread.
 */
@OptIn(BetaInteropApi::class)
internal class IosOfflineMapScheme : NSObject(), WKURLSchemeHandlerProtocol {
    private val lock = SynchronizedObject()
    private val stopped = HashSet<Long>()
    private var next = 0L
    private val ids = HashMap<WKURLSchemeTaskProtocol, Long>()

    // Two tiles drawn at a time, at a low priority: a page asks for dozens at
    // once, and drawing them all together starved the router on launch.
    private val queues = List(2) {
        dispatch_queue_create("eucoffline.tiles.$it", null).also { q ->
            dispatch_set_target_queue(q, dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_LOW.toLong(), 0u))
        }
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, startURLSchemeTask: WKURLSchemeTaskProtocol) {
        val task = startURLSchemeTask
        val id = synchronized(lock) { (++next).also { ids[task] = it } }
        val url = task.request.URL ?: return
        val parts = (url.path ?: "").trim('/').split('/')
        dispatch_async(queues[(id % queues.size).toInt()]) {
            val z = parts.getOrNull(0)?.toIntOrNull()
            val x = parts.getOrNull(1)?.toIntOrNull()
            val y = parts.getOrNull(2)?.substringBefore('.')?.toIntOrNull()
            val png = if (z != null && x != null && y != null) IosOfflineMap.png(z, x, y, IosOfflineMap.WEB_PIXELS) else null
            dispatch_async(dispatch_get_main_queue()) {
                val gone = synchronized(lock) {
                    ids.remove(task)
                    stopped.remove(id)
                }
                if (gone) return@dispatch_async
                val response = NSHTTPURLResponse(
                    uRL = url,
                    statusCode = if (png != null) 200 else 404,
                    HTTPVersion = "HTTP/1.1",
                    headerFields = mapOf<Any?, Any?>(
                        "Content-Type" to "image/png",
                        "Access-Control-Allow-Origin" to "*",
                        "Cache-Control" to "no-store",
                    ),
                )
                runCatching {
                    task.didReceiveResponse(response)
                    task.didReceiveData((png ?: ByteArray(0)).toNSData())
                    task.didFinish()
                }.onFailure { Log.w("OfflineMap", "tile answer: $it") }
            }
        }
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, stopURLSchemeTask: WKURLSchemeTaskProtocol) {
        synchronized(lock) { ids[stopURLSchemeTask]?.let { stopped += it } }
    }
}

/**
 * The offline areas screen's platform side on the iPhone: file sizes as
 * iOS writes them, and the squares map in a WKWebView with Leaflet inlined
 * (as the route builder's), its `AndroidOffline` calls through a script
 * message handler.
 */
internal object IosOfflineDataHost : OfflineDataHost {
    override fun formatSize(bytes: Long): String =
        NSByteCountFormatter.stringFromByteCount(bytes, NSByteCountFormatterCountStyleFile)

    @OptIn(ExperimentalForeignApi::class, ExperimentalComposeUiApi::class)
    @Composable
    override fun GridMap(
        html: String,
        backgroundArgb: Int,
        onTile: (String) -> Unit,
        onPage: (OfflineGridPage) -> Unit,
        onPageFinished: () -> Unit,
        modifier: Modifier,
    ) {
        val tile = rememberUpdatedState(onTile)
        val finished = rememberUpdatedState(onPageFinished)
        val delegate = remember { MapNavigationDelegate { finished.value() } }
        val handler = remember { GridScriptHandler { tile.value(it) } }
        val page = remember(html) { inlineLeaflet(html) }
        UIKitView(
            factory = {
                val content = WKUserContentController()
                content.addUserScript(
                    WKUserScript(
                        source = BRIDGE_JS,
                        injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
                        forMainFrameOnly = true,
                    )
                )
                content.addScriptMessageHandler(handler, name = "AndroidOffline")
                val config = IosOfflineMap.configuration(content)
                val web = WKWebView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0), configuration = config)
                web.navigationDelegate = delegate
                web.scrollView.scrollEnabled = false
                web.scrollView.bounces = false
                web.opaque = false
                web.backgroundColor = UIColor(
                    red = ((backgroundArgb shr 16) and 0xFF) / 255.0,
                    green = ((backgroundArgb shr 8) and 0xFF) / 255.0,
                    blue = (backgroundArgb and 0xFF) / 255.0,
                    alpha = 1.0,
                )
                web.loadHTMLString(page, baseURL = null)
                onPage(OfflineGridPage { js -> web.evaluateJavaScript(js, completionHandler = null) })
                web
            },
            modifier = modifier,
            properties = UIKitInteropProperties(interactionMode = UIKitInteropInteractionMode.NonCooperative),
            onRelease = { web ->
                web.configuration.userContentController.removeScriptMessageHandlerForName("AndroidOffline")
                web.stopLoading()
                web.navigationDelegate = null
            },
        )
    }

    /** The page's Leaflet tags swapped for the bundled files, as the route builder does. */
    @OptIn(ExperimentalResourceApi::class)
    private fun inlineLeaflet(html: String): String = runCatching {
        runBlocking {
            val css = Res.readBytes("files/leaflet.css").decodeToString()
            val js = Res.readBytes("files/leaflet.js").decodeToString()
            html.replace("<link rel=\"stylesheet\" href=\"leaflet.css\"/>", "<style>\n$css\n</style>")
                .replace("<script src=\"leaflet.js\"></script>", "<script>\n$js\n</script>")
        }
    }.getOrElse {
        Log.w("OfflineData", "No bundled Leaflet (${it.message})")
        html
    }

    /** `AndroidOffline.onTile(name)`, posted to the message handler. */
    private const val BRIDGE_JS =
        "window.AndroidOffline = { onTile: function(n) { " +
            "try { window.webkit.messageHandlers.AndroidOffline.postMessage(String(n)); } catch (e) {} } };"
}

/** A tapped square from the page, on the main thread. */
private class GridScriptHandler(private val onTile: (String) -> Unit) : NSObject(), WKScriptMessageHandlerProtocol {
    override fun userContentController(
        userContentController: WKUserContentController,
        didReceiveScriptMessage: WKScriptMessage,
    ) {
        (didReceiveScriptMessage.body as? String)?.let(onTile)
    }
}
