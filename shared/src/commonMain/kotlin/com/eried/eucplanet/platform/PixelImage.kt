package com.eried.eucplanet.platform

import androidx.compose.ui.graphics.ImageBitmap

/** A picture from [pixels], one 0xAARRGGBB value per pixel, row by row. */
expect fun argbImageBitmap(pixels: IntArray, width: Int, height: Int): ImageBitmap
