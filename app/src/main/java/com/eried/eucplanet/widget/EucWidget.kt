package com.eried.eucplanet.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.eried.eucplanet.MainActivity
import com.eried.eucplanet.R
import com.eried.eucplanet.data.model.WidgetActionType
import com.eried.eucplanet.data.model.WidgetMetricType
import com.eried.eucplanet.data.model.WidgetSettings
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.service.WheelService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Home screen widget: speed, trip distance and charge, plus a horn button and a
 * toggle for the periodic voice announcements.
 *
 * Built with RemoteViews rather than Compose or Glance because a widget is
 * inflated inside the launcher's process, where only the classic view set
 * exists. That also puts it outside the app's theme system, so its colours are
 * literals in the layout instead of `MaterialTheme.appColors` tokens.
 *
 * The widget never talks to BLE itself. Buttons fire the same [WheelService]
 * actions the ongoing notification already uses, so there is one implementation
 * of "sound the horn" rather than two.
 *
 * ## Why the last painted state is persisted
 *
 * The launcher re-inflates a widget on its own schedule: a reboot, a rotation,
 * a theme change, or the launcher process being restarted all call [onUpdate]
 * with no telemetry to hand. Without a stored snapshot the widget would flash
 * back to "No wheel" and dashes mid-ride until the next push. SharedPreferences
 * is read synchronously, which a broadcast receiver needs.
 */
@AndroidEntryPoint
class EucWidget : AppWidgetProvider() {

    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val stored = Snapshot.load(context)
        // Paint what we already know immediately, so a launcher restart mid-ride
        // never blanks the widget while the settings read is in flight.
        val views = buildViews(context, stored)
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, views) }
        if (stored.isConfigured) return

        // Nothing stored yet: a rider who places the widget before the service
        // has ever pushed would otherwise get a card with a header and nothing
        // under it, which reads as broken. Fill the captions and button labels
        // from their settings so it looks like the picker preview, with dashes
        // where the numbers will go.
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val hydrated = stored.hydrate(appContext, settingsRepository.get().widget)
                // render, not a bare updateAppWidget: this SAVES the snapshot.
                // Painting without saving left the stored snapshot at its blank
                // default, so the next thing to call Snapshot.load - the voice
                // button's own repaint - loaded all-blank captions and NONE
                // keys and emptied the widget, taking the standalone buttons
                // with it. The widget looked right until the first tap.
                render(appContext, hydrated)
            } catch (e: Exception) {
                Log.e(TAG, "Widget hydrate failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        // Hilt injects inside its generated onReceive, so this must run first.
        super.onReceive(context, intent)
        if (intent.action != ACTION_TOGGLE_VOICE) return

        // Written here rather than handed to WheelService. The widget lives on
        // the launcher, so the app is in the background, and startService from
        // there throws unless the service is already running: tapping the
        // button did nothing at all whenever no wheel was connected.
        //
        // goAsync keeps the receiver alive across the settings write, which is
        // a suspending DataStore call.
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // voiceEnabled is the flag the periodic-report loop reads.
                // voicePeriodicEnabled is retired: writing it flipped nothing a
                // rider could hear while the button relabelled itself, which is
                // exactly what "the voice button does nothing" looked like.
                val on = !settingsRepository.get().voiceEnabled
                settingsRepository.update { it.copy(voiceEnabled = on) }
                // Repaint immediately: with no wheel connected there is no
                // telemetry coming to refresh the label, so relabel it here.
                render(
                    appContext,
                    Snapshot.load(appContext).copy(voiceOn = on).relabelVoice(appContext),
                )
            } catch (e: Exception) {
                Log.e(TAG, "Widget voice toggle failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * What the widget is currently showing.
     *
     * [connected] is passed in rather than inferred from the presence of
     * telemetry. WheelData is a StateFlow that keeps its last value forever, so
     * "we have numbers" is not the same as "a wheel is on the other end": the
     * first version of this widget inferred it that way and latched on
     * "Connected" for the rest of the process.
     */
    data class Snapshot(
        val connected: Boolean = false,
        /** Three values and three captions, already formatted in the rider's
         *  units, one per configured metric slot. */
        val values: List<String> = List(WidgetMetricType.SLOTS) { DASH },
        val captions: List<String> = List(WidgetMetricType.SLOTS) { "" },
        /** Button labels for the big widget, one per configured action slot.
         *  Blank hides it. */
        val buttons: List<String> = List(WidgetActionType.SLOTS) { "" },
        /** Action keys behind those buttons, so taps route without re-reading
         *  settings in the receiver. */
        val buttonKeys: List<String> = List(WidgetActionType.SLOTS) { WidgetActionType.NONE },
        /** Live labels for the STANDALONE button widgets, whose actions are
         *  configured separately from the big widget's. */
        val standaloneButtons: List<String> = List(WidgetActionType.SLOTS) { "" },
        /** Their action keys, so a repaint driven by telemetry can route taps
         *  without reading settings on the main thread. */
        val standaloneKeys: List<String> = List(WidgetActionType.SLOTS) { WidgetActionType.NONE },
        val voiceOn: Boolean = false,
        /** Whether a trip is recording, so a RECORD button can send stop rather
         *  than start. See [actionIntentFor]. */
        val recording: Boolean = false,
    ) {
        /**
         * Whether WheelService has ever described this widget. Captions and
         * button labels are written together on every push, so either one being
         * present means the snapshot is real rather than the empty default.
         */
        val isConfigured: Boolean
            get() = captions.any { it.isNotBlank() } || buttons.any { it.isNotBlank() }

        /**
         * The rider's configured slots as labels, for the window between
         * placing the widget and the first telemetry push.
         *
         * Through [buttonLabel] with no state, so these read exactly as they
         * will once a wheel connects to something resting: "Light on", not the
         * picker's "Light on / off".
         */
        /**
         * Re-label the VOICE slots from [voiceOn], leaving every other button
         * alone.
         *
         * Toggling voice from the widget flipped the setting but left the
         * button reading "Voice on" afterwards. The label is stored text, and
         * with no wheel connected WheelService is not pushing a new snapshot to
         * correct it, so the one button that works without a wheel was also the
         * one whose label never caught up. Only VOICE is touched: the others
         * may be carrying live wheel state this path knows nothing about.
         */
        fun relabelVoice(context: Context): Snapshot {
            fun relabel(labels: List<String>, keys: List<String>) =
                labels.mapIndexed { i, label ->
                    if (WidgetActionType.byKey(keys.getOrElse(i) { WidgetActionType.NONE })
                        == WidgetActionType.VOICE
                    ) buttonLabel(context, WidgetActionType.VOICE.key, voiceOn = voiceOn)
                    else label
                }
            return copy(
                buttons = relabel(buttons, buttonKeys),
                standaloneButtons = relabel(standaloneButtons, standaloneKeys),
            )
        }

        fun hydrate(context: Context, settings: WidgetSettings): Snapshot {
            val metricKeys = WidgetMetricType.slots(settings.metrics)
            val actionKeys = WidgetActionType.slots(settings.actions)
            val standaloneKeys = WidgetActionType.slots(settings.standaloneActions)
            fun label(key: String) = buttonLabel(context, key)
            return copy(
                captions = metricKeys.map { key ->
                    WidgetMetricType.byKey(key)?.let { context.getString(it.pickerLabel) } ?: ""
                },
                buttons = actionKeys.map(::label),
                buttonKeys = actionKeys,
                standaloneButtons = standaloneKeys.map(::label),
                standaloneKeys = standaloneKeys,
            )
        }

        companion object {
            private const val PREFS = "euc_widget"
            const val DASH = "--"

            // A control character, not a comma: these hold formatted values
            // like "1,234 km" in locales that group with commas, which a comma
            // separator would split in the middle of a number.
            private const val SEP = "\u001F"

            private fun pack(list: List<String>) = list.joinToString(SEP)

            private fun unpack(s: String, n: Int, fill: String): List<String> {
                val parts = if (s.isEmpty()) emptyList() else s.split(SEP)
                return (0 until n).map { parts.getOrNull(it)?.ifEmpty { fill } ?: fill }
            }

            fun load(context: Context): Snapshot {
                val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                return Snapshot(
                    connected = p.getBoolean("connected", false),
                    values = unpack(p.getString("values", "") ?: "", WidgetMetricType.SLOTS, DASH),
                    captions = unpack(p.getString("captions", "") ?: "", WidgetMetricType.SLOTS, ""),
                    buttons = unpack(p.getString("buttons", "") ?: "", WidgetActionType.SLOTS, ""),
                    buttonKeys = unpack(
                        p.getString("buttonKeys", "") ?: "",
                        WidgetActionType.SLOTS, WidgetActionType.NONE,
                    ),
                    standaloneButtons = unpack(
                        p.getString("standaloneButtons", "") ?: "",
                        WidgetActionType.SLOTS, "",
                    ),
                    standaloneKeys = unpack(
                        p.getString("standaloneKeys", "") ?: "",
                        WidgetActionType.SLOTS, WidgetActionType.NONE,
                    ),
                    voiceOn = p.getBoolean("voiceOn", false),
                    recording = p.getBoolean("recording", false),
                )
            }

            fun save(context: Context, s: Snapshot) {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean("connected", s.connected)
                    .putString("values", pack(s.values))
                    .putString("captions", pack(s.captions))
                    .putString("buttons", pack(s.buttons))
                    .putString("buttonKeys", pack(s.buttonKeys))
                    .putString("standaloneButtons", pack(s.standaloneButtons))
                    .putString("standaloneKeys", pack(s.standaloneKeys))
                    .putBoolean("voiceOn", s.voiceOn)
                    .putBoolean("recording", s.recording)
                    .apply()
            }
        }
    }

    companion object {

        private const val TAG = "EucWidget"

        /** Sent by the widget's voice button; handled in [onReceive]. */
        private const val ACTION_TOGGLE_VOICE = "com.eried.eucplanet.widget.TOGGLE_VOICE"

        /**
         * Deliberately does nothing. [onReceive] ignores every action but
         * [ACTION_TOGGLE_VOICE], so a button wired to this swallows its tap.
         *
         * A disabled button with no click handler is not inert: the tap falls
         * through to the widget root, which opens the app. Greying a button out
         * and then having it launch EUC Planet is worse than leaving it live.
         */
        private const val ACTION_NOOP = "com.eried.eucplanet.widget.NOOP"

        private const val DASH = "--"

        /** Icon for a configured action, or 0 for none. */
        fun iconFor(key: String): Int = when (key) {
            WidgetActionType.HORN.key -> R.drawable.ic_widget_horn
            WidgetActionType.LIGHT.key -> R.drawable.ic_widget_light
            WidgetActionType.LOCK.key -> R.drawable.ic_widget_lock
            WidgetActionType.RECORD.key -> R.drawable.ic_widget_record
            WidgetActionType.VOICE.key -> R.drawable.ic_widget_voice
            WidgetActionType.DISCONNECT.key -> R.drawable.ic_widget_disconnect
            else -> 0
        }

        /**
         * The tap target for one configured action.
         *
         * Shared with [EucButtonWidget] so a widget action means the same thing
         * wherever it appears. Voice is the one handled in this receiver rather
         * than by the service: it carries a persistent state, and toggling it
         * has to work with no wheel connected.
         */
        fun actionIntentFor(
            context: Context,
            key: String,
            requestCode: Int,
            /**
             * Whether a trip is being recorded right now.
             *
             * Light and lock are toggles on the far side, so one action covers
             * both directions. Recording is not: the service has separate start
             * and stop actions, and the notification already picks between them.
             * The widget always sent START, so a button reading "Stop" restarted
             * the recording instead of ending it.
             */
            recording: Boolean = false,
        ): PendingIntent? =
            when (key) {
                WidgetActionType.VOICE.key ->
                    broadcastIntent(context, ACTION_TOGGLE_VOICE, requestCode)
                WidgetActionType.HORN.key ->
                    serviceIntent(context, WheelService.ACTION_HORN, requestCode)
                WidgetActionType.LIGHT.key ->
                    serviceIntent(context, WheelService.ACTION_TOGGLE_LIGHT, requestCode)
                WidgetActionType.LOCK.key ->
                    serviceIntent(context, WheelService.ACTION_TOGGLE_LOCK, requestCode)
                WidgetActionType.RECORD.key -> serviceIntent(
                    context,
                    if (recording) WheelService.ACTION_STOP_RECORDING
                    else WheelService.ACTION_START_RECORDING,
                    requestCode,
                )
                WidgetActionType.DISCONNECT.key ->
                    serviceIntent(context, WheelService.ACTION_DISCONNECT, requestCode)
                else -> null
            }

        /** Repaint every placed widget from [snapshot], and remember it. */
        fun render(context: Context, snapshot: Snapshot) {
            Snapshot.save(context, snapshot)
            // The one-button widget reads the same snapshot, so one push keeps
            // both surfaces in step.
            ActionWidgetBase.renderAll(context)
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, EucWidget::class.java))
            if (ids.isEmpty()) return
            val views = buildViews(context, snapshot)
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        /** True when the rider has at least one widget placed. Lets the service
         *  skip the work entirely for everyone who does not use them. */
        fun isPlaced(context: Context): Boolean {
            val manager = AppWidgetManager.getInstance(context) ?: return false
            // Either surface counts: the one-button widget reads the same
            // snapshot, so the service must keep pushing for it too.
            return manager.getAppWidgetIds(
                ComponentName(context, EucWidget::class.java)
            ).isNotEmpty() || ActionWidgetBase.anyPlaced(context)
        }

        private val VALUE_IDS = listOf(R.id.widget_speed, R.id.widget_distance, R.id.widget_battery)
        private val CAPTION_IDS =
            listOf(R.id.widget_speed_label, R.id.widget_distance_label, R.id.widget_battery_label)
        /** The clickable button containers. */
        private val BUTTON_IDS =
            listOf(R.id.widget_btn_horn, R.id.widget_btn_voice, R.id.widget_btn_third)
        private val BUTTON_ICON_IDS = listOf(
            R.id.widget_btn_horn_icon, R.id.widget_btn_voice_icon, R.id.widget_btn_third_icon,
        )
        private val BUTTON_TEXT_IDS = listOf(
            R.id.widget_btn_horn_text, R.id.widget_btn_voice_text, R.id.widget_btn_third_text,
        )

        private fun buildViews(context: Context, s: Snapshot): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_euc)

            views.setTextViewText(
                R.id.widget_status,
                context.getString(
                    if (s.connected) R.string.widget_connected else R.string.widget_disconnected
                )
            )
            // Disconnected shows dashes rather than the last live numbers, which
            // a rider glancing at the launcher would read as current. The
            // captions stay, so the widget still says WHAT it is showing.
            VALUE_IDS.forEachIndexed { i, id ->
                views.setTextViewText(id, if (s.connected) s.values.getOrElse(i) { DASH } else DASH)
            }
            CAPTION_IDS.forEachIndexed { i, id ->
                val caption = s.captions.getOrElse(i) { "" }
                views.setTextViewText(id, caption)
                // An unconfigured slot collapses rather than leaving a gap.
                views.setViewVisibility(id, if (caption.isBlank()) View.GONE else View.VISIBLE)
                views.setViewVisibility(
                    VALUE_IDS[i], if (caption.isBlank()) View.INVISIBLE else View.VISIBLE
                )
            }

            BUTTON_IDS.forEachIndexed { i, id ->
                val text = s.buttons.getOrElse(i) { "" }
                val key = s.buttonKeys.getOrElse(i) { WidgetActionType.NONE }
                // Label and icon are separate views inside the button, so the
                // pair centres together. A compound drawable would pin the icon
                // to the button's edge while the text centred, which is what
                // made the row look scattered.
                views.setTextViewText(BUTTON_TEXT_IDS[i], text)
                views.setImageViewResource(BUTTON_ICON_IDS[i], iconFor(key))
                views.setViewVisibility(
                    BUTTON_ICON_IDS[i],
                    if (iconFor(key) == 0) View.GONE else View.VISIBLE,
                )
                views.setViewVisibility(id, if (text.isBlank()) View.GONE else View.VISIBLE)
                dimIfDisabled(views, s, key, BUTTON_TEXT_IDS[i], BUTTON_ICON_IDS[i])
                val intent = if (isEnabled(s, key)) {
                    actionIntentFor(context, key, requestCode = 100 + i, recording = s.recording)
                } else {
                    // Swallow the tap rather than leave the button uncovered,
                    // which would let it through to the root's open-app intent.
                    noopIntent(context, requestCode = 190 + i)
                }
                intent?.let { views.setOnClickPendingIntent(id, it) }
            }

            // Tapping anywhere that is not a button opens the app.
            views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))
            return views
        }

        /** Enabled content, and the same at 38% for a disabled button. The
         *  widget sits outside the theme system, so these match the literals in
         *  widget_euc.xml rather than a token. */
        private const val ON_COLOR = 0xFFFFFFFF.toInt()
        private const val OFF_COLOR = 0x61FFFFFF.toInt()
        private const val OFF_ALPHA = 97

        /**
         * Whether tapping this button can do anything right now.
         *
         * Horn, light, lock and disconnect all send a command to the wheel, so
         * with nothing connected there is nobody to send it to.
         *
         * VOICE and RECORD are not wheel commands. Voice toggles the periodic
         * announcements, which is an app setting. Recording writes a trip from
         * whatever the phone can see, so with no wheel it still produces a
         * GPS-only ride, and the Recorder screen lets a rider start one exactly
         * that way. Greying these out would have the widget contradict the app.
         */
        fun isEnabled(s: Snapshot, key: String): Boolean = s.connected ||
            WidgetActionType.byKey(key) in ALWAYS_AVAILABLE

        /** Actions that do not need a wheel on the other end. */
        private val ALWAYS_AVAILABLE =
            setOf(WidgetActionType.VOICE, WidgetActionType.RECORD)

        /**
         * Grey out a button that cannot act.
         *
         * Deliberately the same label either way. A disconnected wheel has no
         * light state to read, but "Light on" greyed out already says both that
         * the light is off and that the widget cannot change it, so a second set
         * of disconnected labels would be duplication for nothing.
         */
        fun dimIfDisabled(
            views: RemoteViews,
            s: Snapshot,
            key: String,
            textId: Int,
            iconId: Int,
        ) {
            val on = isEnabled(s, key)
            views.setTextColor(textId, if (on) ON_COLOR else OFF_COLOR)
            views.setInt(iconId, "setImageAlpha", if (on) 255 else OFF_ALPHA)
        }

        /**
         * What a button should say, given what the wheel is doing.
         *
         * The single place any surface names an action on a button, and
         * deliberately the notification's own strings rather than new ones.
         *
         * There are two vocabularies here and mixing them is what made buttons
         * read "Light on / off". `pickerLabel` names a SLOT, so it has to cover
         * both directions: in a settings dropdown "Light on" would be a lie,
         * because the slot toggles. A button names the NEXT TAP, so it is
         * "Light on" or "Light off" and never both. Only the settings dropdowns
         * use pickerLabel now.
         *
         * The defaults are the resting state, which is also the truth before a
         * wheel connects: nothing is lit, locked or recording. That lets the
         * paths with no telemetry to hand call this with no state and still get
         * the label the notification would show.
         */
        fun buttonLabel(
            context: Context,
            key: String,
            lightOn: Boolean = false,
            locked: Boolean = false,
            recording: Boolean = false,
            voiceOn: Boolean = false,
        ): String = when (WidgetActionType.byKey(key)) {
            null -> ""
            WidgetActionType.HORN -> context.getString(R.string.notif_btn_horn)
            WidgetActionType.LIGHT -> context.getString(
                if (lightOn) R.string.notif_btn_light_off else R.string.notif_btn_light_on
            )
            WidgetActionType.LOCK -> context.getString(
                if (locked) R.string.notif_btn_unlock else R.string.notif_btn_lock
            )
            WidgetActionType.RECORD -> context.getString(
                if (recording) R.string.notif_btn_stop_record else R.string.notif_btn_record
            )
            WidgetActionType.VOICE -> context.getString(
                if (voiceOn) R.string.widget_voice_off else R.string.widget_voice_on
            )
            WidgetActionType.DISCONNECT -> context.getString(R.string.notif_btn_disconnect)
        }

        private fun openAppIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        private fun serviceIntent(context: Context, action: String, requestCode: Int): PendingIntent =
            PendingIntent.getService(
                context, requestCode,
                Intent(context, WheelService::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        /** A tap target that consumes the tap and does nothing. See [ACTION_NOOP]. */
        fun noopIntent(context: Context, requestCode: Int): PendingIntent =
            broadcastIntent(context, ACTION_NOOP, requestCode)

        private fun broadcastIntent(context: Context, action: String, requestCode: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context, requestCode,
                Intent(context, EucWidget::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
    }
}
