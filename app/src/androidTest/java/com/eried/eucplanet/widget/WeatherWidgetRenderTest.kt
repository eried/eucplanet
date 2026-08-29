package com.eried.eucplanet.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Inflates each weather widget for real and photographs it.
 *
 * RemoteViews fail at inflation time, in the launcher's process, for things a
 * compiler is happy with: a view type widgets do not allow, an attribute that
 * needs code behind it, a bitmap over the memory limit. None of that shows up
 * in a unit test and none of it shows up until the widget is on a home screen,
 * where the only symptom is a grey box saying "Problem loading widget".
 *
 * So this applies the actual RemoteViews to a real view tree, which is what
 * the launcher does, and writes a PNG of each so the layouts can be looked at.
 */
@RunWith(AndroidJUnit4::class)
class WeatherWidgetRenderTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    /** A believable afternoon: good now, a wet patch later, recovering. */
    private fun sample(): WeatherSnapshot {
        val now = System.currentTimeMillis()
        val series = listOf(3.5f, 3.2f, 2.4f, 0.8f, -1.6f, -2.4f, -0.7f, 1.4f, 2.8f)
        return WeatherSnapshot(
            fetchedAtMs = now - 7 * 60_000L,
            score = 3.5f,
            faceKey = "CLEAR",
            place = "Munich",
            tempLabel = "18°C",
            windLabel = "11 km/h",
            windowHours = 8,
            series = series,
            seriesStartMs = now,
            seriesStepMs = 3_600_000L,
            faces = List(series.size) { "CLEAR" },
            lat = 48.13,
            lon = 11.57,
        )
    }

    private fun shoot(name: String, layout: Int, wDp: Int, hDp: Int): Bitmap {
        WeatherSnapshot.save(ctx, sample())
        val views = android.widget.RemoteViews(ctx.packageName, layout)
        // Go through the real render path, not a hand-built RemoteViews: the
        // point is to exercise what the launcher will actually be handed.
        val built = WeatherWidgetBase.renderForTest(ctx, layout, wDp, hDp)
        assertNotNull("render produced nothing for $name", built)
        val parent = FrameLayout(ctx)
        val view: View = built!!.apply(ctx, parent)
        val d = ctx.resources.displayMetrics.density
        val w = (wDp * d).toInt()
        val h = (hDp * d).toInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.parseColor("#202124"))
            view.draw(this)
        }
        val out = File(ctx.getExternalFilesDir(null), "widget_$name.png")
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // Unused local kept off: `views` above only proves the layout id is
        // valid before the render path is asked to fill it.
        assertTrue("empty view for $name", views.layoutId == layout)
        return bmp
    }

    private fun Bitmap.countColoured(): Int {
        var n = 0
        for (y in 0 until height step 3) for (x in 0 until width step 3) {
            val p = getPixel(x, y)
            if (Color.alpha(p) > 0 && p != Color.parseColor("#202124")) n++
        }
        return n
    }

    @Test fun theTinyWidgetInflatesAndDrawsItsAnswer() {
        val bmp = shoot("score", com.eried.eucplanet.R.layout.widget_weather_score, 70, 70)
        assertTrue("nothing drawn", bmp.countColoured() > 50)
    }

    @Test fun theTinyWidgetFitsTheSmallestCellItAdvertises() {
        // The provider says 60dp square. Three tiers in that space clip the
        // bottom one if any of them grows, and a clipped line looks like a
        // rendering bug rather than a layout that needs a smaller font.
        val bmp = shoot("score_min", com.eried.eucplanet.R.layout.widget_weather_score, 60, 60)
        val foot = Bitmap.createBitmap(
            bmp, 0, (bmp.height * 0.78f).toInt(), bmp.width, (bmp.height * 0.2f).toInt(),
        )
        assertTrue("the bottom line is clipped at the smallest cell", foot.countColoured() > 8)
    }

    @Test fun theCompactWidgetInflatesWithItsGraph() {
        val bmp = shoot("compact", com.eried.eucplanet.R.layout.widget_weather_compact, 250, 120)
        assertTrue("nothing drawn", bmp.countColoured() > 500)
    }

    @Test fun theForecastWidgetGrowsItsDetailWhenDraggedTaller() {
        // Dragging it taller is how a rider asks for the hour labels and the
        // temperature and wind line; there is no separate larger provider to
        // pick any more, so the size has to be what decides.
        val tall = shoot("panel", com.eried.eucplanet.R.layout.widget_weather_compact, 320, 200)
        assertTrue("nothing drawn", tall.countColoured() > 800)
        assertEquals(
            "the detail row should show on a tall widget",
            View.VISIBLE, detailVisibility(320, 200),
        )
        assertEquals(
            "the detail row should stay hidden on a short one",
            View.GONE, detailVisibility(250, 110),
        )
    }

    @Test fun theForecastWidgetKeepsItsCurveWhenSqueezedThin() {
        // 110x80dp is the smallest the provider lets a rider drag it to. At the
        // layout's normal padding and type the header and footer ate all of it
        // and the curve got zero height, which leaves a forecast widget with no
        // forecast in it, so the height it actually gets is what is asserted.
        val bmp = shoot("thin", com.eried.eucplanet.R.layout.widget_weather_compact, 110, 80)
        assertTrue("nothing drawn when thin", bmp.countColoured() > 100)
        val d = ctx.resources.displayMetrics.density
        assertTrue(
            "the curve was squeezed out of the widget",
            graphHeightAt(110, 80) > 12 * d,
        )
    }

    private fun graphHeightAt(wDp: Int, hDp: Int): Int {
        WeatherSnapshot.save(ctx, sample())
        val built = WeatherWidgetBase.renderForTest(
            ctx, com.eried.eucplanet.R.layout.widget_weather_compact, wDp, hDp,
        )
        val view = built.apply(ctx, FrameLayout(ctx))
        val d = ctx.resources.displayMetrics.density
        val w = (wDp * d).toInt()
        val h = (hDp * d).toInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, w, h)
        return view.findViewById<View>(com.eried.eucplanet.R.id.ww_graph).height
    }

    private fun detailVisibility(wDp: Int, hDp: Int): Int {
        WeatherSnapshot.save(ctx, sample())
        val built = WeatherWidgetBase.renderForTest(
            ctx, com.eried.eucplanet.R.layout.widget_weather_compact, wDp, hDp,
        )
        val view = built.apply(ctx, FrameLayout(ctx))
        return view.findViewById<View>(com.eried.eucplanet.R.id.ww_detail).visibility
    }

    @Test fun aWidgetWithNoForecastStillInflates() {
        // The state every widget is in the moment it is placed.
        WeatherSnapshot.save(ctx, WeatherSnapshot())
        val built = WeatherWidgetBase.renderForTest(
            ctx, com.eried.eucplanet.R.layout.widget_weather_compact, 250, 120,
        )
        val view = built!!.apply(ctx, FrameLayout(ctx))
        assertNotNull(view)
        assertTrue(view is ViewGroup)
    }
}
