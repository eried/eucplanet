package com.eried.eucplanet.ui.navigator

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import com.eried.eucplanet.ui.dashboard.qrBitmap
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File

/**
 * The route QR as a picture to send: the code, then where the route goes and
 * how far, on white. A printed or forwarded image has to say which route it is
 * on its own, with no app around it.
 *
 * Fixed black-on-white, like the QR itself: this is an exported image, not
 * themed UI, and a scanner needs the contrast whatever theme sent it.
 */
object RouteQrCard {
    private const val WIDTH = 1080
    private const val MARGIN = 72

    fun render(link: String, title: String, distance: String, footer: String): Bitmap {
        val qrSide = WIDTH - 2 * MARGIN
        val qr = qrBitmap(link, qrSide, ErrorCorrectionLevel.M)
        val textWidth = WIDTH - 2 * MARGIN
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK; textSize = 46f; typeface = Typeface.DEFAULT_BOLD
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF333333.toInt(); textSize = 38f
        }
        val footPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF777777.toInt(); textSize = 30f
        }
        fun layout(text: String, paint: TextPaint, lines: Int) =
            StaticLayout.Builder.obtain(text, 0, text.length, paint, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).build()
        val titleL = layout(title, titlePaint, 3)
        val distL = layout(distance, bodyPaint, 1)
        val footL = layout(footer, footPaint, 2)
        val height = MARGIN + qrSide + 48 + titleL.height + 16 + distL.height + 36 + footL.height + MARGIN
        val out = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(android.graphics.Color.WHITE)
        c.drawBitmap(Bitmap.createScaledBitmap(qr, qrSide, qrSide, false), MARGIN.toFloat(), MARGIN.toFloat(), null)
        var y = (MARGIN + qrSide + 48).toFloat()
        for ((l, gap) in listOf(titleL to 16, distL to 36, footL to 0)) {
            c.save(); c.translate(MARGIN.toFloat(), y); l.draw(c); c.restore()
            y += l.height + gap
        }
        return out
    }

    /** Write the card to the shared cache and open the share sheet with it and the link. */
    fun share(
        context: Context, link: String, text: String,
        title: String, distance: String, footer: String, chooserTitle: String,
    ) {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "route_qr.png")
        file.outputStream().use { render(link, title, distance, footer).compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            // Apps that drop the picture still pass the route on.
            putExtra(Intent.EXTRA_TEXT, text)
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}
