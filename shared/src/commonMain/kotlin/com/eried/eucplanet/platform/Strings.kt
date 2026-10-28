package com.eried.eucplanet.platform

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.StringResource

/**
 * A string shared code names as `Res.string.<key>`, shown in the rider's
 * language. Same call shape as `stringResource(R.string.<key>)`, so a moved
 * file only swaps the import and the `R.` for `Res.`.
 *
 * Android looks the key up in its own resources and formats with
 * `Resources.getString`, so the text and formatting are exactly what the phone
 * app showed before. iOS reads the Compose copy generated from the same
 * strings.xml (shared/compose-strings.gradle.kts) and formats with
 * [JavaFormat], so `%1$s`, `%.1f` and `%%` behave as on Android.
 */
@Composable
expect fun stringResource(res: StringResource): String

@Composable
expect fun stringResource(res: StringResource, vararg formatArgs: Any): String
