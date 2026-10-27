package com.eried.eucplanet.hud.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * UP and DOWN on the camera screens pick the camera and turn the picture.
 *
 * A MotoEye can report its rear camera as the front one and mount the sensor
 * another way round than the E6 the 270 degree default was tuned on, so the
 * rider sets both once on the glasses and the HUD keeps them.
 */
class HudCameraControlTest {

    private fun onScreen(screen: HudUiController.Screen) = HudUiController().apply {
        applyEnabledScreens(listOf(screen.name))
    }

    @Test
    fun defaultsAreTheE6Setup() {
        val c = HudUiController()
        assertEquals(0, c.cameraIndex)
        assertEquals(270, c.cameraRotation)
    }

    @Test
    fun upStepsToTheNextCameraAndSaves() {
        val c = onScreen(HudUiController.Screen.Camera)
        var saved: Pair<Int, Int>? = null
        c.onCameraChanged = { i, r -> saved = i to r }
        c.upAction()
        assertEquals(1, c.cameraIndex)
        assertEquals(1 to 270, saved)
    }

    @Test
    fun downTurnsNinetyDegreesAndWraps() {
        val c = onScreen(HudUiController.Screen.CustomCam)
        c.downAction()
        assertEquals(0, c.cameraRotation)
        c.downAction()
        c.downAction()
        // Two presses from the default is the 180 degree turn Josh needs.
        assertEquals(180, c.cameraRotation)
    }

    @Test
    fun upAndDownLeaveTheCameraAloneOnOtherScreens() {
        val c = onScreen(HudUiController.Screen.Telemetry)
        c.upAction()
        c.downAction()
        assertEquals(0, c.cameraIndex)
        assertEquals(270, c.cameraRotation)
    }

    @Test
    fun guideNamesTheCameraActions() {
        val a = onScreen(HudUiController.Screen.Camera).shortActions()
        assertEquals("Next camera", a.up)
        assertEquals("Rotate", a.down)
    }

    @Test
    fun restoreRejectsAnglesThatAreNotQuarterTurns() {
        val c = HudUiController()
        c.restoreCamera(index = 3, rotation = 45)
        assertEquals(3, c.cameraIndex)
        assertEquals(270, c.cameraRotation)
        c.restoreCamera(index = -2, rotation = -90)
        assertEquals(0, c.cameraIndex)
        assertEquals(270, c.cameraRotation)
    }
}
