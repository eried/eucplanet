package com.eried.eucplanet.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.eried.eucplanet.voice.androidLanguageStrings

/**
 * What can I say, in the language the rider speaks commands in: the shared
 * [VoiceVocabularyList] over strings read through a context set to it.
 * Blank keeps the interface language.
 */
@Composable
fun VoiceVocabularyDialog(onDismiss: () -> Unit, languageTag: String = "") {
    val context = LocalContext.current
    val strings = remember(languageTag, context) { androidLanguageStrings(context, languageTag) }
    VoiceVocabularyList(onDismiss = onDismiss, strings = strings)
}
