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
    fun cameraButtonOnACameraScreenHandsTheCameraToTheRecorder() {
        // Josh: the MotoEye camera button starts the recorder, which needs
        // the camera the preview has. While a camera screen was up the
        // recorder failed on every camera.
        val c = onScreen(HudUiController.Screen.Camera)
        c.handOffCamera()
        assertTrue(c.cameraHandedOff)
    }

    @Test
    fun cameraButtonOnOtherScreensLeavesTheCameraAlone() {
        val c = onScreen(HudUiController.Screen.Telemetry)
        c.handOffCamera()
        assertFalse(c.cameraHandedOff)
    }

    @Test
    fun upOrDownTakesTheCameraBackWithoutChangingIt() {
        val c = onScreen(HudUiController.Screen.CustomCam)
        c.handOffCamera()
        c.upAction()
        assertFalse(c.cameraHandedOff)
        assertEquals("the first press only brings the picture back", 0, c.cameraIndex)
        c.handOffCamera()
        c.downAction()
        assertFalse(c.cameraHandedOff)
        assertEquals(270, c.cameraRotation)
    }

    @Test
    fun anotherScreenTakesTheCameraBack() {
        val c = HudUiController().apply {
            applyEnabledScreens(listOf("Camera", "Telemetry"))
        }
        c.handOffCamera()
        c.nextScreen()
        assertFalse(c.cameraHandedOff)
    }

    @Test
    fun theCameraButtonIsPassedOnAndHandsOff() {
        // The camera button is not one of ours: it must reach the MotoEye
        // and free the camera on the way.
        assertTrue(HudKeys.handsOffCamera(android.view.KeyEvent.KEYCODE_CAMERA))
        assertTrue("an unknown remote key too", HudKeys.handsOffCamera(android.view.KeyEvent.KEYCODE_FOCUS))
    }

    @Test
    fun ourOwnButtonsKeepTheCamera() {
        listOf(
            android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN,
            android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
            android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER,
            android.view.KeyEvent.KEYCODE_BACK, android.view.KeyEvent.KEYCODE_ESCAPE,
            android.view.KeyEvent.KEYCODE_BUTTON_L1, android.view.KeyEvent.KEYCODE_BUTTON_R1,
            android.view.KeyEvent.KEYCODE_VOLUME_UP, android.view.KeyEvent.KEYCODE_VOLUME_DOWN,
        ).forEach { assertFalse("key $it", HudKeys.handsOffCamera(it)) }
    }

    @Test
    fun anotherAppHoldingTheCameraHandsItOff() {
        // Josh: the recorder stopped fine, then could not start again while
        // the HUD was open, because the preview opened the camera the moment
        // it was free.
        val inUse = androidx.camera.core.CameraState.ERROR_CAMERA_IN_USE
        val tooMany = androidx.camera.core.CameraState.ERROR_MAX_CAMERAS_IN_USE
        assertTrue(com.eried.eucplanet.hud.ui.screens.takenByAnotherApp(inUse, 0))
        assertTrue(com.eried.eucplanet.hud.ui.screens.takenByAnotherApp(tooMany, 5_000))
        assertFalse("our own camera switch", com.eried.eucplanet.hud.ui.screens.takenByAnotherApp(tooMany, 300))
        assertFalse(com.eried.eucplanet.hud.ui.screens.takenByAnotherApp(null, 5_000))
        assertFalse(com.eried.eucplanet.hud.ui.screens.takenByAnotherApp(
            androidx.camera.core.CameraState.ERROR_CAMERA_DISABLED, 5_000))
    }

    @Test
    fun guideSaysHowToGetThePictureBack() {
        val c = onScreen(HudUiController.Screen.Camera)
        c.handOffCamera()
        assertEquals("Show camera", c.shortActions().up)
    }

    @Test
    fun thePreviewComesBackWhenTheRecorderLetsGo() {
        // Josh: the DVR works, but the picture had to be brought back by hand
        // after every clip.
        val c = onScreen(HudUiController.Screen.Camera)
        c.handOffCamera(byKey = true)
        c.onOtherAppCamera(inUse = false)
        assertTrue("our own camera closing is not the recorder", c.cameraHandedOff)
        c.onOtherAppCamera(inUse = true)
        c.handOffCamera(byKey = true)
        assertTrue("the stop press keeps it off while recording", c.cameraHandedOff)
        c.onOtherAppCamera(inUse = false)
        assertFalse(c.cameraHandedOff)
    }

    @Test
    fun withoutTheCameraButtonThePreviewStaysOff() {
        // The recorder's next start only frees the camera if its button
        // reaches the HUD; otherwise taking it back would block that start.
        val c = onScreen(HudUiController.Screen.Camera)
        c.handOffCamera()
        c.onOtherAppCamera(inUse = true)
        c.onOtherAppCamera(inUse = false)
        assertTrue(c.cameraHandedOff)
    }

    @Test
    fun aNewHandOffWaitsForTheRecorderAgain() {
        val c = onScreen(HudUiController.Screen.Camera)
        c.handOffCamera(byKey = true)
        c.onOtherAppCamera(inUse = true)
        c.onOtherAppCamera(inUse = false)
        c.handOffCamera(byKey = true)
        c.onOtherAppCamera(inUse = false)
        assertTrue(c.cameraHandedOff)
    }

    @Test
    fun theRearViewKeepsTheCameraWhileItIsOnScreen() {
        // The camera app let go for a moment while still in front, and the
        // preview took the camera back under it.
        val c = onScreen(HudUiController.Screen.Camera)
        c.handOffCamera(byKey = true)
        c.onHudFocus(false)
        c.onOtherAppCamera(inUse = true)
        c.onOtherAppCamera(inUse = false)
        assertTrue(c.cameraHandedOff)
        c.onHudFocus(true)
        assertFalse(c.cameraHandedOff)
    }
}
