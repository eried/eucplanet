package com.eried.eucplanet.di

import com.eried.eucplanet.ble.transport.BleTransport
import com.eried.eucplanet.ble.transport.createBleTransport
import org.koin.core.context.startKoin
import org.koin.core.error.KoinApplicationAlreadyStartedException
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Shared (platform-independent) DI graph. Both apps resolve from this; it's the
 * Koin side of the strangler migration off Hilt. v1 singletons that have been
 * de-`@Inject`'d and moved to `:shared` register here; `:app`'s Hilt `@Provides`
 * delegate to these (so the same instance backs both DI systems on Android).
 */
val commonModule: Module = module {
    single<BleTransport> { createBleTransport() }
}

/**
 * Platform-specific DI graph. Android binds context-scoped stores (DataStore,
 * file access) here; iOS binds its NS* actuals. Empty until those pieces land.
 */
expect fun platformModule(): Module

/**
 * The single entry point both platforms call to stand up DI. Idempotent: a
 * second call (Android Application re-create, Compose re-instantiating the iOS
 * view controller) is swallowed, so we never crash on Koin's already-started
 * error. (We catch the exception rather than probe `GlobalContext`, which is a
 * JVM-only declaration and won't resolve on Kotlin/Native.)
 *
 * [extra] lets a platform inject additional modules (e.g. Android's
 * `androidContext`-bound module) without the shared code knowing about them.
 */
object KoinInitializer {
    fun start(extra: List<Module> = emptyList()) {
        try {
            startKoin {
                modules(commonModule, platformModule())
                if (extra.isNotEmpty()) modules(extra)
            }
        } catch (_: KoinApplicationAlreadyStartedException) {
            // Already initialized — no-op.
        }
    }
}
