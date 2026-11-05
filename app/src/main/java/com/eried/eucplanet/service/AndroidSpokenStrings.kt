package com.eried.eucplanet.service

import android.content.Context
import com.eried.eucplanet.platform.getString
import org.jetbrains.compose.resources.StringResource

/** [SpokenStrings] from a Context already set to the voice's locale. */
class AndroidSpokenStrings(private val vctx: Context) : SpokenStrings {
    override fun text(res: StringResource, vararg args: Any): String =
        if (args.isEmpty()) vctx.getString(res) else vctx.getString(res, *args)

    override fun timeNow(): String =
        android.text.format.DateFormat.getTimeFormat(vctx).format(java.util.Date())
}
