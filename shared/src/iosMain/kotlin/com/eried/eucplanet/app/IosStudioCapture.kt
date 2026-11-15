package com.eried.eucplanet.app

import com.eried.eucplanet.platform.Log
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVCaptureAudioDataOutput
import platform.AVFoundation.AVCaptureAudioDataOutputSampleBufferDelegateProtocol
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVMediaTypeAudio
import platform.CoreMedia.CMSampleBufferRef
import platform.darwin.NSObject
import platform.darwin.dispatch_queue_create

// AVFoundation capture for the Overlay Studio: the microphone for a take.

/**
 * The default microphone streaming CMSampleBuffers to [onSample] on a
 * capture queue of its own, while the app's audio session is held for
 * recording (IosAudioSession), so a spoken alert does not cut it off.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosMicCapture private constructor(
    private val session: AVCaptureSession,
    private val delegate: NSObject,
) {
    private var stopped = false

    fun stop() {
        if (stopped) return
        stopped = true
        session.stopRunning()
        IosAudioSession.releaseRecording()
    }

    companion object {
        private const val TAG = "StudioMic"

        fun start(onSample: (CMSampleBufferRef) -> Unit): IosMicCapture? {
            val device = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeAudio) ?: run {
                Log.w(TAG, "No microphone")
                return null
            }
            val input = AVCaptureDeviceInput.deviceInputWithDevice(device, null) ?: return null
            val session = AVCaptureSession()
            // The app's own session decides the category (IosAudioSession).
            session.automaticallyConfiguresApplicationAudioSession = false
            val output = AVCaptureAudioDataOutput()
            if (!session.canAddInput(input) || !session.canAddOutput(output)) return null
            session.addInput(input)
            session.addOutput(output)
            val delegate = object : NSObject(), AVCaptureAudioDataOutputSampleBufferDelegateProtocol {
                override fun captureOutput(
                    output: AVCaptureOutput,
                    didOutputSampleBuffer: CMSampleBufferRef?,
                    fromConnection: AVCaptureConnection,
                ) {
                    didOutputSampleBuffer?.let(onSample)
                }
            }
            output.setSampleBufferDelegate(delegate, dispatch_queue_create("eucplanet.studio.mic", null))
            IosAudioSession.acquireRecording()
            session.startRunning()
            return IosMicCapture(session, delegate)
        }
    }
}
