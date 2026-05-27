package com.eried.eucplanet.service.hud

import android.util.Log
import com.eried.eucplanet.data.repository.WheelRepository
import com.eried.eucplanet.hud.protocol.HudCommand
import com.eried.eucplanet.nav.NavigationEngine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes the small set of commands the HUD remote can issue into the same
 * repositories the phone UI uses. Lives in its own class so [HudServer]
 * doesn't reach across the dependency graph; everything that touches state
 * goes through here.
 */
@Singleton
class HudCommandSink @Inject constructor(
    private val wheelRepository: WheelRepository,
    private val navigationEngine: NavigationEngine
) {
    companion object { private const val TAG = "HudCommandSink" }

    fun dispatch(cmd: HudCommand) {
        when (cmd) {
            is HudCommand.Pair ->
                Log.i(TAG, "HUD paired: id=${cmd.hudId} v=${cmd.hudVersion}")
            HudCommand.ToggleLight -> {
                Log.i(TAG, "HUD command: ToggleLight")
                wheelRepository.toggleLight()
            }
            HudCommand.Horn -> {
                Log.i(TAG, "HUD command: Horn")
                wheelRepository.sendHorn()
            }
            HudCommand.StopNavigation -> {
                Log.i(TAG, "HUD command: StopNavigation")
                navigationEngine.stop()
            }
        }
    }
}
