package com.eried.eucplanet.util

import android.content.Context
import com.eried.eucplanet.platform.getString

/** [Units.speedUnitRes] read through Android's resources, for code with a Context. */
fun Units.speedUnit(context: Context, unit: String): String = context.getString(speedUnitRes(unit))
