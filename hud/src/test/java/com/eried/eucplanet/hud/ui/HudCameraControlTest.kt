package com.eried.eucplanet.hud.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        c.restoreCamera(index = 3, rotations = mapOf(3 to 45))
        assertEquals(3, c.cameraIndex)
        assertEquals(270, c.cameraRotation)
        c.restoreCamera(index = -2, rotations = mapOf(0 to -90))
        assertEquals(0, c.cameraIndex)
        assertEquals(270, c.cameraRotation)
    }

    @Test
    fun eachCameraKeepsItsOwnTurn() {
        // Josh: turning the front camera also turned the rear one.
        val c = onScreen(HudUiController.Screen.Camera)
        c.onCamerasFound(2)
        val saved = mutableListOf<Pair<Int, Int>>()
        c.onCameraChanged = { i, r -> saved += i to r }
        c.upAction()
        c.downAction()
        c.downAction()
        assertEquals(1, c.cameraIndex)
        assertEquals(90, c.cameraRotation)
        c.upAction()
        assertEquals(0, c.cameraIndex)
        assertEquals(270, c.cameraRotation)
        c.upAction()
        assertEquals(90, c.cameraRotation)
        assertEquals(1 to 90, saved.last())
    }

    @Test
    fun upWrapsAtTheCamerasFound() {
        val c = onScreen(HudUiController.Screen.Camera)
        c.onCamerasFound(2)
        c.upAction()
        c.upAction()
        assertEquals(0, c.cameraIndex)
    }

    @Test
    fun savedIndexPastTheLastCameraFallsBackToTheFirst() {
        val c = HudUiController()
        c.restoreCamera(index = 3, rotations = mapOf(0 to 180))
        c.onCamerasFound(2)
        assertEquals(0, c.cameraIndex)
        assertEquals(180, c.cameraRotation)
    }

    @Test
    fun holdOnACameraScreenHandsTheCameraToTheRecorder() {
        // Josh: the MotoEye recorder starts on a held button and needs the
        // camera the preview has. Once the preview showed the rear camera,
        // the recorder could not start.
        val c = onScreen(HudUiController.Screen.Camera)
        c.onRemoteHold()
        assertTrue(c.cameraHandedOff)
    }

    @Test
    fun holdOnOtherScreensLeavesTheCameraAlone() {
        val c = onScreen(HudUiController.Screen.Telemetry)
        c.onRemoteHold()
        assertFalse(c.cameraHandedOff)
    }

    @Test
    fun upOrDownTakesTheCameraBackWithoutChangingIt() {
        val c = onScreen(HudUiController.Screen.CustomCam)
        c.onRemoteHold()
        c.upAction()
        assertFalse(c.cameraHandedOff)
        assertEquals("the first press only brings the picture back", 0, c.cameraIndex)
        c.onRemoteHold()
        c.downAction()
        assertFalse(c.cameraHandedOff)
        assertEquals(270, c.cameraRotation)
    }

    @Test
    fun anotherScreenTakesTheCameraBack() {
        val c = HudUiController().apply {
            applyEnabledScreens(listOf("Camera", "Telemetry"))
        }
        c.onRemoteHold()
        c.nextScreen()
        assertFalse(c.cameraHandedOff)
    }

    @Test
    fun guideSaysHowToGetThePictureBack() {
        val c = onScreen(HudUiController.Screen.Camera)
        c.onRemoteHold()
        assertEquals("Show camera", c.shortActions().up)
    }
}
