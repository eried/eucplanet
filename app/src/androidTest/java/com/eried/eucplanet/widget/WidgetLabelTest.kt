package com.eried.eucplanet.widget

import android.appwidget.AppWidgetManager
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every widget has to introduce itself in the picker.
 *
 * A receiver without `android:label` is listed under the application's name, so
 * a rider opening the widget picker saw "EUC Planet" eight times in a row with
 * no way to tell a horn button from the weather. Nothing in a build catches
 * that: the manifest is valid, the widgets work, and the only symptom is in a
 * list nobody screenshots.
 *
 * So this reads the labels back the way the launcher does.
 */
@RunWith(AndroidJUnit4::class)
class WidgetLabelTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun labels(): List<String> {
        val pm = ctx.packageManager
        return AppWidgetManager.getInstance(ctx)
            .getInstalledProvidersForPackage(ctx.packageName, null)
            .map { it.loadLabel(pm).trim() }
    }

    @Test fun everyWidgetHasItsOwnNameInThePicker() {
        val appName = ctx.applicationInfo.loadLabel(ctx.packageManager).toString().trim()
        val labels = labels()
        assertTrue("no widgets found at all", labels.size >= 8)
        labels.forEach {
            assertTrue("a widget is still listed as the app itself: $it", it != appName)
            assertTrue("a widget has no name", it.isNotBlank())
        }
    }

    @Test fun everyWidgetShowsSomethingInThePicker() {
        // A provider with no preview is an empty box in the gallery, which is
        // what the weather widgets shipped as. Data-driven from the providers
        // themselves, so a widget added later is covered without being listed.
        val pm = ctx.packageManager
        AppWidgetManager.getInstance(ctx)
            .getInstalledProvidersForPackage(ctx.packageName, null)
            .forEach { info ->
                val name = info.provider.shortClassName
                assertTrue(
                    "$name has neither a preview layout nor a preview image",
                    info.previewLayout != 0 || info.previewImage != 0,
                )
                if (info.previewLayout != 0) {
                    // Inflate it the way the launcher does: a preview layout
                    // using a view RemoteViews does not allow fails here rather
                    // than as "Problem loading widget" in the gallery.
                    val view = RemoteViews(ctx.packageName, info.previewLayout)
                        .apply(ctx, FrameLayout(ctx))
                    assertNotNull("$name has a preview that will not inflate", view)
                }
                assertNotNull(info.loadLabel(pm))
            }
    }

    @Test fun theButtonPreviewHintFitsOneCell() {
        // The hint shares a 1x1 cell with a 24dp icon. Rendered at 60dp, the
        // smallest cell the providers advertise, and photographed so the fit
        // can be seen rather than assumed.
        val d = ctx.resources.displayMetrics.density
        val px = (60 * d).toInt()
        val view = RemoteViews(ctx.packageName, com.eried.eucplanet.R.layout.widget_act_1_preview)
            .apply(ctx, FrameLayout(ctx))
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(px, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(px, android.view.View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, px, px)
        val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bmp).apply {
            drawColor(android.graphics.Color.parseColor("#202124"))
            view.draw(this)
        }
        java.io.File(ctx.getExternalFilesDir(null), "widget_act_preview.png")
            .outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        // Anything drawn in the bottom third means the label survived the cell.
        var ink = 0
        for (y in (px * 2 / 3) until px) for (x in 0 until px step 2) {
            if (bmp.getPixel(x, y) != android.graphics.Color.parseColor("#202124")) ink++
        }
        assertTrue("the hint did not fit the smallest cell", ink > 20)
    }

    @Test fun noTwoWidgetsShareAName() {
        // Two entries reading the same in the picker is the same problem in a
        // smaller form: the rider still cannot tell which one they are dragging.
        val labels = labels()
        assertEquals("duplicate widget names: $labels", labels.size, labels.toSet().size)
    }
}
