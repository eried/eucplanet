package com.eried.eucplanet.di

import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Android platform DI. Empty for now; Android context-bound stores (settings
 * DataStore, file access, the Room DAO bridge) register here as the v1 graph
 * migrates off Hilt. The `:app` Application supplies `androidContext` via the
 * `extra` modules passed to [KoinInitializer.start].
 */
actual fun platformModule(): Module = module { }
