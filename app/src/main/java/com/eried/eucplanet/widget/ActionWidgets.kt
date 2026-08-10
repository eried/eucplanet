package com.eried.eucplanet.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.view.View
import android.widget.RemoteViews
import com.eried.eucplanet.R
import com.eried.eucplanet.data.model.WidgetActionType
import com.eried.eucplanet.data.repository.SettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Hilt access from a plain BroadcastReceiver.
 *
 * An EntryPoint rather than @AndroidEntryPoint on every provider: these widgets
 * are one base class with several thin subclasses, and annotating each one just
 * to reach a single repository would be noise.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun settingsRepository(): SettingsRepository
}

/**
 * Button-only widgets: one or two of the rider's configured actions, filling
 * the cell.
 *
 * Separate providers rather than one resizable widget. RemoteViews cannot
 * reflow, and Android has no way to ask "which slot should this instance show"
 * without a configuration activity opening on every placement. Shipping the
 * combinations as their own entries means the rider picks the one they want
 * from the widget list and it works immediately, with nothing to configure.
 *
 * ## Why these read settings, not just the snapshot
 *
 * The label a rider sees prefers [EucWidget.Snapshot], because that carries
 * live state: "Light off" while the light is on, the way the notification's own
 * buttons read. But the snapshot only exists once WheelService has pushed one.
 * A rider who places a button widget before ever connecting a wheel would
 * otherwise get a blank tile, which is what the first version did. So the
 * action itself comes from settings, and the snapshot only refines the wording.
 */
abstract class ActionWidgetBase(
    /** Which configured action slots this widget shows, in order. */
    private val slotIndices: List<Int>,
) : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val keys = readActionKeys(appContext)
                val views = buildViews(appContext, keys, slotIndices)
                appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, views) }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {

        private suspend fun readActionKeys(context: Context): List<String> {
            val repo = EntryPointAccessors
                .fromApplication(context, WidgetEntryPoint::class.java)
                .settingsRepository()
            // The standalone list, not the big widget's row: these are
            // configured independently.
            return WidgetActionType.slots(repo.get().widget.standaloneActions)
        }

        /**
         * Repaint every placed button widget. Called from [EucWidget.render] so
         * one telemetry push keeps every surface in step.
         */
        fun renderAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val appContext = context.applicationContext
            // The snapshot already holds the configured keys once the service
            // has pushed once, so this path needs no settings read and can stay
            // synchronous.
            val snap = EucWidget.Snapshot.load(appContext)
            // buttonKeys still drives routing: the service writes the standalone
            // keys into the snapshot alongside their labels.
            val keys = snap.standaloneKeys
            PROVIDERS.forEach { (cls, slots) ->
                val ids = manager.getAppWidgetIds(ComponentName(appContext, cls))
                if (ids.isEmpty()) return@forEach
                val views = buildViews(appContext, keys, slots)
                ids.forEach { manager.updateAppWidget(it, views) }
            }
        }

        /** True when any button widget is placed, so the service keeps pushing. */
        fun anyPlaced(context: Context): Boolean {
            val manager = AppWidgetManager.getInstance(context) ?: return false
            return PROVIDERS.any { (cls, _) ->
                manager.getAppWidgetIds(ComponentName(context, cls)).isNotEmpty()
            }
        }

        private val PROVIDERS: List<Pair<Class<*>, List<Int>>> = listOf(
            EucButtonWidget::class.java to listOf(0),
            EucButton2Widget::class.java to listOf(1),
            EucButton3Widget::class.java to listOf(2),
            EucButtons12Widget::class.java to listOf(0, 1),
            EucButtons23Widget::class.java to listOf(1, 2),
        )

        private val TEXT_IDS = listOf(R.id.widget_act_text_1, R.id.widget_act_text_2)
        private val ICON_IDS = listOf(R.id.widget_act_icon_1, R.id.widget_act_icon_2)
        private val CELL_IDS = listOf(R.id.widget_act_cell_1, R.id.widget_act_cell_2)

        private fun buildViews(
            context: Context,
            keys: List<String>,
            slots: List<Int>,
        ): RemoteViews {
            // One cell gets its own layout rather than a hidden second cell,
            // so the picker's static preview matches what the rider will get.
            val views = RemoteViews(
                context.packageName,
                if (slots.size <= 1) R.layout.widget_euc_action_single
                else R.layout.widget_euc_actions,
            )
            // Live labels when the service has produced them, so a button reads
            // "Light off" while the light is on rather than the generic action
            // name. Falls back to the picker label before any telemetry.
            val snapshot = EucWidget.Snapshot.load(context)

            // The single-cell layout has no second cell to address, so only
            // walk as many cells as this layout actually contains.
            val cells = minOf(slots.size, CELL_IDS.size)
            (0 until cells).forEach { cell ->
                val slot = slots[cell]
                val key = keys.getOrNull(slot) ?: WidgetActionType.NONE
                val type = WidgetActionType.byKey(key)
                val label = snapshot.standaloneButtons.getOrNull(slot)?.takeIf { it.isNotBlank() }
                    ?: type?.let { context.getString(it.pickerLabel) }
                    ?: context.getString(R.string.widget_button_unset)
                views.setTextViewText(TEXT_IDS[cell], label)
                val icon = EucWidget.iconFor(key)
                views.setImageViewResource(ICON_IDS[cell], icon)
                views.setViewVisibility(
                    ICON_IDS[cell], if (icon == 0) View.GONE else View.VISIBLE
                )
                // Distinct request codes per slot, or the two cells of a pair
                // would share one PendingIntent and both fire the same action.
                EucWidget.actionIntentFor(context, key, requestCode = 300 + slot)?.let {
                    views.setOnClickPendingIntent(CELL_IDS[cell], it)
                }
            }
            return views
        }
    }
}

/** 1x1, the rider's first configured action. */
class EucButtonWidget : ActionWidgetBase(listOf(0))

/** 1x1, the second action. */
class EucButton2Widget : ActionWidgetBase(listOf(1))

/** 1x1, the third action. */
class EucButton3Widget : ActionWidgetBase(listOf(2))

/** 2x1, the first and second actions. */
class EucButtons12Widget : ActionWidgetBase(listOf(0, 1))

/** 2x1, the second and third actions. */
class EucButtons23Widget : ActionWidgetBase(listOf(1, 2))
