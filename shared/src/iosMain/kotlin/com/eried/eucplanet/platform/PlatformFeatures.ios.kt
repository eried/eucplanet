package com.eried.eucplanet.platform

/**
 * What the iPhone app does not have yet but will (docs/IOS_PARITY.md,
 * "Coming later or never"). Each shows muted with a "Not on iPhone yet"
 * tap; drop an entry the day its iOS side lands.
 */
actual val missingPlatformFeatures: Set<PlatformFeature> = setOf(
    PlatformFeature.NAVIGATION,
    PlatformFeature.GPS_SENSORS,
    PlatformFeature.RADAR,
    PlatformFeature.TPMS,
    PlatformFeature.CAMERA_STUDIO,
    PlatformFeature.MOTOR_SOUND,
    PlatformFeature.HOME_WIDGETS,
)
