package com.eried.eucplanet.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eried.eucplanet.R
import com.eried.eucplanet.data.model.MetricCatalog
import com.eried.eucplanet.service.VoiceReportPlan
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.voice.VoiceVocabulary

/**
 * What can I say.
 *
 * Generated from the same catalogs the matcher listens against, never a
 * hand-written list, so it cannot promise a rider a phrase that does not work
 * or quietly omit one that does. Rule 10 asks exactly this of any surface that
 * previews what the app will do.
 *
 * The names are the catalog's own localised labels, which is also what a rider
 * reads on a tile, so the list doubles as the answer to "what do I call this".
 */
/**
 * @param languageTag the language the rider speaks commands in. Blank keeps
 *   the interface language, which is right only while the two agree. A list
 *   of English words shown to a rider whose commands are matched in Russian
 *   is worse than no list: every phrase on it is one the app will not answer
 *   to.
 */
@Composable
fun VoiceVocabularyDialog(onDismiss: () -> Unit, languageTag: String = "") {
    val base = androidx.compose.ui.platform.LocalContext.current
    val localized = androidx.compose.runtime.remember(languageTag, base) {
        if (languageTag.isBlank()) {
            base
        } else {
            val locale = java.util.Locale.forLanguageTag(languageTag.replace('_', '-'))
            val cfg = android.content.res.Configuration(base.resources.configuration)
                .apply { setLocale(locale) }
            base.createConfigurationContext(cfg)
        }
    }
    // Every stringResource below reads through LocalContext, so swapping it
    // here translates the whole page at once rather than at fifty call sites.
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalContext provides localized
    ) {
        VoiceVocabularyDialogContent(onDismiss)
    }
}

@Composable
private fun VoiceVocabularyDialogContent(onDismiss: () -> Unit) {
    val metricNames = MetricCatalog.all.associate { it.key to stringResource(it.spokenLabelRes ?: it.labelRes) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val reportNames = com.eried.eucplanet.voice.VoicePhrases
        .resolve(com.eried.eucplanet.voice.VoicePhrases.REPORTS) { ctx.getString(it) }
    // The same phrase lists the matcher listens against, so the list cannot
    // promise something that will not work or omit something that will. It
    // used to build only metrics, reports and the split, which left every
    // special and action off the one page that explains them.
    val terms = VoiceVocabulary.build(
        metricNames = metricNames,
        reportNames = reportNames,
        splitName = stringResource(R.string.voice_split_term),
        helpPhrases = stringResource(R.string.voice_help_terms),
        specialPhrases = com.eried.eucplanet.voice.VoicePhrases
            .resolve(com.eried.eucplanet.voice.VoicePhrases.SPECIALS) { ctx.getString(it) },
        actionPhrases = com.eried.eucplanet.voice.VoicePhrases
            .resolve(com.eried.eucplanet.voice.VoicePhrases.ACTIONS) { ctx.getString(it) },
    )
    // Metric and report names overlap by design (Speed is both), so the list a
    // rider reads is the set of distinct things they can say. Specials and
    // actions carry several phrasings per key, and listing every one of them
    // would bury the metrics: the first is the canonical one, and the matcher
    // accepts the rest whether or not they are written down.
    val names = terms
        .groupBy { if (it.kind == VoiceVocabulary.Kind.METRIC ||
                it.kind == VoiceVocabulary.Kind.REPORT ||
                it.kind == VoiceVocabulary.Kind.SPLIT
            ) it.name else it.key
        }
        // Carry the kind through, not just the name. Half this list answers
        // with a number off the wheel and half does something or answers
        // about the world, and read as one alphabetical column there was no
        // way to tell "light" from "load".
        .map { (_, group) ->
            val term = group.first()
            term.name to (
                term.kind == VoiceVocabulary.Kind.METRIC ||
                    term.kind == VoiceVocabulary.Kind.REPORT ||
                    term.kind == VoiceVocabulary.Kind.SPLIT
                )
        }
        .distinctBy { it.first }
        .sortedBy { it.first }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.voice_command_vocabulary)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.voice_command_vocabulary_tap),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.appColors.textSecondary,
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(names) { (name, isReading) ->
                        // A reference, not a control. Tapping used to speak the
                        // answer, which made a list of words look like a list of
                        // buttons and invited a rider to press one instead of
                        // reading it.
                        Text(
                            buildAnnotatedString {
                                append(name)
                                // The star, dimmed: it is a note about the
                                // entry rather than part of what to say, and
                                // at full weight it read as a character a
                                // rider was supposed to pronounce.
                                if (!isReading) {
                                    withStyle(
                                        SpanStyle(color = MaterialTheme.appColors.textSecondary)
                                    ) { append(" *") }
                                }
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.voice_command_vocabulary_legend),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.appColors.textSecondary,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
        },
    )
}
