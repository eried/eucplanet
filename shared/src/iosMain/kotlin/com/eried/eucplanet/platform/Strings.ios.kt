package com.eried.eucplanet.platform

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.StringResource

@Composable
actual fun stringResource(res: StringResource): String =
    org.jetbrains.compose.resources.stringResource(res)

@Composable
actual fun stringResource(res: StringResource, vararg formatArgs: Any): String =
    org.jetbrains.compose.resources.stringResource(res).format(*formatArgs)

@Composable
actual fun stringResourceInLanguage(res: StringResource, languageTag: String): String =
    org.jetbrains.compose.resources.stringResource(res)

@Composable
actual fun rememberStringReader(): (StringResource) -> String =
    androidx.compose.runtime.remember {
        { res -> kotlinx.coroutines.runBlocking { org.jetbrains.compose.resources.getString(res) } }
    }

@Composable
actual fun rememberStringFormatter(): (StringResource, List<Any>) -> String =
    androidx.compose.runtime.remember {
        { res, args ->
            val raw = kotlinx.coroutines.runBlocking { org.jetbrains.compose.resources.getString(res) }
            if (args.isEmpty()) raw else raw.format(*args.toTypedArray())
        }
    }
