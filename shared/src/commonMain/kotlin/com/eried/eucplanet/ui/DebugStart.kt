package com.eried.eucplanet.ui

/**
 * Screenshot/debug harness hook. Returns a screen name to auto-open a demo ride
 * on at launch (so a headless Simulator run can photograph any screen without UI
 * automation), or null for the normal Scan-first flow. iOS reads the
 * `EUC_DEMO_SCREEN` env var; Android always returns null.
 */
expect fun debugStartScreen(): String?
