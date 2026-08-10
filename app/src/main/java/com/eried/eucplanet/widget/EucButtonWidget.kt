package com.eried.eucplanet.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.eried.eucplanet.R

/**
 * A widget that is only a button: the rider's FIRST configured action, filling
 * the whole cell.
 *
 * A separate provider rather than a small size of [EucWidget]. RemoteViews does
 * not reflow, so resizing the full widget down only crushes three numbers and
 * two buttons into an unreadable strip; a one-tap horn on the home screen wants
 * its own shape, not a shrunken dashboard.
 *
 * It deliberately has no configuration of its own. It follows the same slots as
 * the full widget, so a rider sets their actions once and both surfaces agree.
 * Reusing [EucWidget]'s stored snapshot also means it needs no telemetry feed:
 * the label is already there.
 */
class EucButtonWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val views = buildViews(context)
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, views) }
    }

    companion object {

        /** Repaint every placed one-button widget. Called by [EucWidget.render]
         *  so both stay in step from a single push. */
        fun render(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(
                ComponentName(context, EucButtonWidget::class.java)
            )
            if (ids.isEmpty()) return
            val views = buildViews(context)
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        fun isPlaced(context: Context): Boolean {
            val manager = AppWidgetManager.getInstance(context) ?: return false
            return manager.getAppWidgetIds(
                ComponentName(context, EucButtonWidget::class.java)
            ).isNotEmpty()
        }

        private fun buildViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_euc_button)
            val snap = EucWidget.Snapshot.load(context)
            val label = snap.buttons.firstOrNull().orEmpty()
            val key = snap.buttonKeys.firstOrNull().orEmpty()

            // Nothing configured in slot one: say so rather than showing a blank
            // tile the rider cannot explain.
            views.setTextViewText(
                R.id.widget_btn_single,
                label.ifBlank { context.getString(R.string.widget_button_unset) },
            )
            EucWidget.actionIntentFor(context, key, requestCode = 200)?.let {
                views.setOnClickPendingIntent(R.id.widget_btn_single, it)
            }
            return views
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
    }
}
