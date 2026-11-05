package com.eried.eucplanet.voice

/** What the dashboard renders while a session runs. */
sealed interface VoiceCommandUiState {
    data object Idle : VoiceCommandUiState
    data object Listening : VoiceCommandUiState
    data class Heard(val text: String) : VoiceCommandUiState
    data class Spoke(val text: String) : VoiceCommandUiState
}
