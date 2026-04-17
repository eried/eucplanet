package com.eried.evendarkerbot.util

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

object LocaleHelper {
    fun apply(tag: String) {
        val list = if (tag.isBlank() || tag == "en") {
            LocaleListCompat.forLanguageTags("en")
        } else {
            LocaleListCompat.forLanguageTags(tag)
        }
        AppCompatDelegate.setApplicationLocales(list)
    }

    fun current(): String {
        val list = AppCompatDelegate.getApplicationLocales()
        return if (list.isEmpty) "en" else list.get(0)?.language ?: "en"
    }
}
