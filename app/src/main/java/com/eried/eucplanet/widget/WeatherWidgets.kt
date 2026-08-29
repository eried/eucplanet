package com.eried.eucplanet.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.eried.eucplanet.MainActivity
import com.eried.eucplanet.R
import com.eried.eucplanet.weather.WeatherFace
import kotlin.math.roundToInt

/**
 * Weather on the home screen, in three sizes.
 *
 * All three paint the same [WeatherSnapshot], so a rider with more than one
 * placed never sees them disagree. None of them fetch: a widget is a broadcast
 * receiver with ten seconds to live, so refreshing is [WeatherWidgetWorker]'s
 * job and these only draw what it left behind.
 *
 * Like the rest of `widget/`, the layouts carry literal colours: RemoteViews
 * are inflated in the launcher's process, outside the app's theme system.
 *
 * ## What a widget cannot do, and what these do instead
 *
 * There is no scrubbing here and there cannot be: the launcher hosts these
 * views and never hands our process a touch. The forecast curve is a bitmap
 * painted by [WeatherGraph] and shipped as pixels, and the only interaction is
 * a tap, which opens the panel in the app where the gestures live.
 */
abstract class WeatherWidgetBase(private val layout: Int, private val size: Size) :
    AppWidgetProvider() {

    enum class Size { TINY, COMPACT, PANEL }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val snapshot = WeatherSnapshot.load(context)
        appWidgetIds.forEach { id ->
            appWidgetManager.updateAppWidget(id, build(context, snapshot, sizeOf(appWidgetManager, id)))
        }
        // A freshly placed widget has nothing to draw, and a stale one is worth
        // catching up. The worker itself decides whether a fetch is due.
        WeatherWidgetWorker.requestRefresh(context, force = false)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        // Resized: the graph is a bitmap cut to the old size, so it has to be
        // repainted or it stretches.
        appWidgetManager.updateAppWidget(
            appWidgetId,
            build(context, WeatherSnapshot.load(context), sizeOf(appWidgetManager, appWidgetId)),
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            WeatherWidgetWorker.requestRefresh(context, force = true)
            // Paint the spinner state at once: the fetch is seconds away and a
            // button that does nothing visible reads as broken.
            renderAll(context, refreshing = true)
        }
        super.onReceive(context, intent)
    }

    private fun sizeOf(manager: AppWidgetManager, id: Int): Pair<Int, Int> {
        val o = manager.getAppWidgetOptions(id)
        val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        return w to h
    }

    private fun build(
        context: Context,
        s: WeatherSnapshot,
        cellsDp: Pair<Int, Int>,
        refreshing: Boolean = false,
    ): RemoteViews = buildViews(context, layout, size, s, cellsDp, refreshing)

    companion object {
        const val ACTION_REFRESH = "com.eried.eucplanet.widget.WEATHER_REFRESH"
        /** Extra on the launch intent: open the dashboard with the weather
         *  panel already unfolded, which is the whole point of tapping. */
        const val EXTRA_OPEN_WEATHER = "open_weather"

        private val PROVIDERS = listOf(
            WeatherScoreWidget::class.java,
            WeatherCompactWidget::class.java,
            WeatherPanelWidget::class.java,
        )

        /** Repaint every placed weather widget from the stored snapshot. */
        fun renderAll(context: Context, refreshing: Boolean = false) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val s = WeatherSnapshot.load(context)
            PROVIDERS.forEach { cls ->
                val ids = manager.getAppWidgetIds(ComponentName(context, cls))
                if (ids.isEmpty()) return@forEach
                val spec = specOf(cls)
                ids.forEach { id ->
                    val o = manager.getAppWidgetOptions(id)
                    val cells = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) to
                        o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
                    manager.updateAppWidget(
                        id, buildViews(context, spec.first, spec.second, s, cells, refreshing),
                    )
                }
            }
        }

        /** True when the rider has at least one weather widget placed, so the
         *  worker can stop scheduling itself for everyone who has none. */
        fun anyPlaced(context: Context): Boolean {
            val manager = AppWidgetManager.getInstance(context) ?: return false
            return PROVIDERS.any {
                manager.getAppWidgetIds(ComponentName(context, it)).isNotEmpty()
            }
        }

        private fun specOf(cls: Class<*>): Pair<Int, Size> = when (cls) {
            WeatherScoreWidget::class.java -> R.layout.widget_weather_score to Size.TINY
            WeatherCompactWidget::class.java -> R.layout.widget_weather_compact to Size.COMPACT
            else -> R.layout.widget_weather_panel to Size.PANEL
        }

        /**
         * The render path, reachable from an instrumented test.
         *
         * A widget only fails where it is inflated, in the launcher, and the
         * only symptom there is a grey "Problem loading widget" box. This lets
         * a test hand the real RemoteViews to a real view tree instead.
         */
        internal fun renderForTest(
            context: Context,
            layout: Int,
            wDp: Int,
            hDp: Int,
        ): RemoteViews = buildViews(
            context, layout, sizeForLayout(layout),
            WeatherSnapshot.load(context), wDp to hDp, refreshing = false,
        )

        private fun sizeForLayout(layout: Int): Size = when (layout) {
            R.layout.widget_weather_score -> Size.TINY
            R.layout.widget_weather_compact -> Size.COMPACT
            else -> Size.PANEL
        }

        /** "+4", "0", "-3": signed because the neutral point is the question. */
        fun signedScore(score: Float): String {
            val v = score.roundToInt()
            return if (v > 0) "+$v" else "$v"
        }

        private fun buildViews(
            context: Context,
            layout: Int,
            size: Size,
            s: WeatherSnapshot,
            cellsDp: Pair<Int, Int>,
            refreshing: Boolean,
        ): RemoteViews {
            val views = RemoteViews(context.packageName, layout)
            val face = WeatherFace.byKey(s.faceKey)
            val known = s.hasData

            views.setTextViewText(R.id.ww_face, if (known) face.emoji else "🛰")
            views.setTextViewText(
                R.id.ww_score,
                if (known) signedScore(s.score) else context.getString(R.string.widget_weather_dash),
            )
            views.setTextColor(
                R.id.ww_score,
                if (known) WeatherGraph.colorFor(s.score) else 0xFF9AA0A6.toInt(),
            )

            if (size == Size.TINY) {
                // One cell has room for one detail. The temperature is the
                // useful one while the reading is current; once it is old
                // enough that the number above may be wrong, the widget says
                // so instead, because a stale "+4" is worse than no answer.
                views.setTextViewText(R.id.ww_tiny, tinyLineOf(context, s))
            }

            if (size != Size.TINY) {
                // The verdict in words, which is what the panel leads with.
                views.setTextViewText(
                    R.id.ww_message,
                    if (known) context.getString(face.textRes)
                    else context.getString(R.string.widget_weather_never),
                )
                views.setTextViewText(R.id.ww_place, s.place)
                views.setViewVisibility(
                    R.id.ww_place, if (s.place.isBlank()) View.GONE else View.VISIBLE,
                )
                views.setTextViewText(R.id.ww_stamp, stampOf(context, s, refreshing))
                views.setOnClickPendingIntent(R.id.ww_refresh, refreshIntent(context))

                val bmp = if (known) {
                    val (wDp, hDp) = cellsDp
                    val d = context.resources.displayMetrics.density
                    // The graph gets the widget's own width and the share of
                    // its height the layout gives it, so a resized widget
                    // repaints at the size it actually occupies.
                    val wPx = ((if (wDp > 0) wDp else 250) * d).roundToInt()
                    val hPx = ((if (hDp > 0) hDp else 110) * d * graphShare(size)).roundToInt()
                    WeatherGraph.render(
                        s.series, s.seriesStartMs, s.seriesStepMs,
                        wPx, hPx, withHours = size == Size.PANEL,
                    )
                } else null
                if (bmp != null) {
                    views.setImageViewBitmap(R.id.ww_graph, bmp)
                    views.setViewVisibility(R.id.ww_graph, View.VISIBLE)
                } else {
                    views.setViewVisibility(R.id.ww_graph, View.GONE)
                }
            }

            if (size == Size.PANEL) {
                views.setTextViewText(R.id.ww_temp, s.tempLabel)
                views.setTextViewText(R.id.ww_wind, s.windLabel)
                views.setTextViewText(
                    R.id.ww_window,
                    context.getString(R.string.widget_weather_window_fmt, s.windowHours),
                )
            }

            views.setOnClickPendingIntent(R.id.ww_root, openPanelIntent(context))
            return views
        }

        /** How much of the widget's height the curve may take. The tiny one has
         *  no graph; the compact one is mostly graph; the panel keeps room for
         *  its readouts. */
        private fun graphShare(size: Size): Float = when (size) {
            Size.TINY -> 0f
            Size.COMPACT -> 0.55f
            Size.PANEL -> 0.45f
        }

        /** Anything older than this and the 1x1 admits its age rather than
         *  showing a temperature next to a score from another afternoon. The
         *  worker refreshes hourly, so reaching three hours means something is
         *  actually wrong: no network, or nowhere to ask about. */
        private const val STALE_MS = 3 * 60 * 60 * 1000L

        private fun tinyLineOf(context: Context, s: WeatherSnapshot): String {
            if (!s.hasData) return context.getString(R.string.widget_weather_never_short)
            val age = System.currentTimeMillis() - s.fetchedAtMs
            if (age >= STALE_MS) {
                return context.getString(R.string.widget_weather_hour_fmt, (age / 3_600_000L).toInt())
            }
            return s.tempLabel
        }

        private fun stampOf(context: Context, s: WeatherSnapshot, refreshing: Boolean): String {
            if (refreshing) return context.getString(R.string.widget_weather_refreshing)
            if (!s.hasData) return context.getString(R.string.widget_weather_never_short)
            val mins = ((System.currentTimeMillis() - s.fetchedAtMs) / 60_000L).toInt()
            return when {
                mins < 1 -> context.getString(R.string.widget_weather_now)
                mins < 60 -> context.getString(R.string.widget_weather_min_fmt, mins)
                else -> context.getString(R.string.widget_weather_hour_fmt, mins / 60)
            }
        }

        private fun openPanelIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context, REQ_OPEN,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(EXTRA_OPEN_WEATHER, true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private fun refreshIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context, REQ_REFRESH,
                Intent(context, WeatherCompactWidget::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private const val REQ_OPEN = 7301
        private const val REQ_REFRESH = 7302
    }
}

/** 1x1: a face, and the number that answers "is it EUC time?". */
class WeatherScoreWidget :
    WeatherWidgetBase(R.layout.widget_weather_score, Size.TINY)

/** The collapsed panel: verdict, freshness, and the curve for the window. */
class WeatherCompactWidget :
    WeatherWidgetBase(R.layout.widget_weather_compact, Size.COMPACT)

/** The expanded panel: the curve with hours, plus temperature and wind. */
class WeatherPanelWidget :
    WeatherWidgetBase(R.layout.widget_weather_panel, Size.PANEL)
