package com.eried.eucplanet.di

import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * iOS platform DI. Empty for now; iOS NS*-backed actuals (settings store, file
 * access, location/audio) register here as the v1 graph migrates off Hilt.
 */
actual fun platformModule(): Module = module { }
