package com.eried.eucplanet.widget

import android.appwidget.AppWidgetManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
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

    @Test fun noTwoWidgetsShareAName() {
        // Two entries reading the same in the picker is the same problem in a
        // smaller form: the rider still cannot tell which one they are dragging.
        val labels = labels()
        assertEquals("duplicate widget names: $labels", labels.size, labels.toSet().size)
    }
}
