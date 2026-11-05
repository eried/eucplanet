package com.eried.eucplanet.ui.dashboard

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.service.VoiceService

/** VoiceService as the shared dashboard's [DashboardVoice]. */
class AndroidDashboardVoice(private val voice: VoiceService) : DashboardVoice {
    override fun initialize() = voice.initialize()
    override fun welcomeOnce() = voice.welcomeOnce()
    override fun speakWelcomeNow() = voice.speakWelcomeNow()
    override fun announceTrigger(data: WheelData, settings: AppSettings, isRecording: Boolean) =
        voice.announceTrigger(data, settings, isRecording = isRecording)
    override fun shutdown() = voice.shutdown()
}
