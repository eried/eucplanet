package com.eried.eucplanet.platform

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.StringResource

@Composable
actual fun stringResource(res: StringResource): String =
    org.jetbrains.compose.resources.stringResource(res)

@Composable
actual fun stringResource(res: StringResource, vararg formatArgs: Any): String =
    org.jetbrains.compose.resources.stringResource(res).format(*formatArgs)
