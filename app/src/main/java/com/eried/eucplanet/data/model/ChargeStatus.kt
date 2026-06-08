package com.eried.eucplanet.data.model

/**
 * High-level charging state surfaced app-wide — drives the dashboard spark icon
 * and the Charging Monitor screen. Derived in WheelRepository from the explicit
 * firmware charging flag (InMotion V14/V12, KingSong 0xB9) or, for families that
 * don't report it, from sustained negative current.
 */
enum class ChargeStatus {
    /** No wheel connected. */
    Disconnected,

    /** Connected but not charging (parked or riding). */
    Idle,

    /** Actively charging, below 100%. */
    Charging,

    /** On the charger at 100% — charge complete. */
    Full
}
