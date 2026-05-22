package com.eried.eucplanet.ui.studio.recording

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Canvas
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records the Overlay Studio to an MP4 **without** MediaProjection — so there
 * is no "share your screen" consent dialog.
 *
 * Video: the caller draws the studio straight onto the H.264 [MediaCodec]'s
 * input [Surface] with a hardware [Canvas] (see [submitFrame]) — the GPU does
 * the scale and the RGB→YUV colour conversion, so there is no per-pixel CPU
 * work and no read-back.
 * Audio (optional): the device microphone is read on a background thread and
 * fed to an AAC [MediaCodec]. Both streams are interleaved into one MP4 by a
 * shared [MediaMuxer].
 *
 * The muxer writes to a **local cache file**, not a MediaStore descriptor:
 * per-sample writes to a MediaStore fd go through FUSE and stall the encode
 * loop ~25 ms a frame. The finished file is published to the gallery in one
 * sequential copy by [finish].
 *
 * Threading: [start] / [submitFrame] / [finish] run on the caller's capture
 * thread; the microphone runs on its own thread. The muxer is the only shared
 * object and every access to it is synchronized on [muxerLock].
 */
class StudioVideoEncoder(
    private val context: Context,
    private val withAudio: Boolean
) {
    // --- Video ---
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var muxer: MediaMuxer? = null
    private var tempFile: File? = null
    private val videoBufferInfo = MediaCodec.BufferInfo()
    private var encodeW = 0
    private var encodeH = 0
    private var startNs = 0L
    private var startUs = 0L
    private var submittedFrames = 0

    // --- Muxer (shared) ---
    private val muxerLock = Any()
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false

    // --- Audio ---
    private var audioOn = false
    private var audioRecord: AudioRecord? = null
    private var audioCodec: MediaCodec? = null
    private var audioThread: Thread? = null
    private val audioBufferInfo = MediaCodec.BufferInfo()
    @Volatile private var audioActive = false
    private var audioBufSize = 0
    private var audioSamples = 0L

    private var failed = false

    /** True once [start] has successfully set up the video codec + muxer. */
    var started = false
        private set

    /** Encoder output dimensions — valid once [start] has returned true. */
    val encodeWidth: Int get() = encodeW
    val encodeHeight: Int get() = encodeH

    /**
     * Prepare the encoder for a [captureWidth] x [captureHeight] source.
     * Returns false if the device could not give us a video encoder; if only
     * the microphone fails, recording continues video-only.
     */
    fun start(captureWidth: Int, captureHeight: Int): Boolean {
        if (captureWidth <= 0 || captureHeight <= 0) return false
        return try {
            val enc = MediaCodec.createEncoderByType(VIDEO_MIME)
            codec = enc
            val hardware = runCatching { enc.codecInfo.isHardwareAccelerated }
                .getOrDefault(true)
            Log.i(TAG, "Video codec ${enc.codecInfo.name}, hw=$hardware")
            // A hardware encoder sails through ~1080p; a software one (emulator,
            // some budget devices) cannot sustain 60 fps at that size, so cap it
            // far lower. Scaling the pixel count keeps the encoder off the
            // critical path.
            val maxLongEdge = if (hardware) HW_LONG_EDGE else SW_LONG_EDGE
            val longEdge = maxOf(captureWidth, captureHeight)
            val scale = if (longEdge > maxLongEdge) maxLongEdge.toFloat() / longEdge else 1f
            encodeW = align16((captureWidth * scale).toInt())
            encodeH = align16((captureHeight * scale).toInt())
            if (encodeW < 16 || encodeH < 16) {
                cleanup()
                return false
            }
            runCatching {
                val vc = enc.codecInfo.getCapabilitiesForType(VIDEO_MIME).videoCapabilities
                val wAlign = maxOf(2, vc.widthAlignment)
                val hAlign = maxOf(2, vc.heightAlignment)
                encodeW = (encodeW.coerceIn(
                    vc.supportedWidths.lower, vc.supportedWidths.upper
                ) / wAlign) * wAlign
                val heights = vc.getSupportedHeightsFor(encodeW)
                encodeH = (encodeH.coerceIn(
                    heights.lower, heights.upper
                ) / hAlign) * hAlign
            }
            // Higher bitrate (x9) keeps fast on-screen motion sharp at 60 fps.
            val bitRate = (encodeW.toLong() * encodeH * 9)
                .coerceIn(6_000_000L, 32_000_000L).toInt()
            val format = MediaFormat.createVideoFormat(VIDEO_MIME, encodeW, encodeH).apply {
                // Surface input — the encoder consumes GPU buffers directly.
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, 60)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = enc.createInputSurface()
            enc.start()
            val tmp = File(context.cacheDir, "studio_rec_${System.nanoTime()}.mp4")
            tempFile = tmp
            muxer = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            startNs = System.nanoTime()
            startUs = startNs / 1000
            if (withAudio) startAudio()
            started = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "Encoder start failed", e)
            failed = true
            cleanup()
            runCatching { tempFile?.delete() }
            tempFile = null
            false
        }
    }

    // --- Video --------------------------------------------------------------

    /**
     * Encode one studio frame. [draw] renders straight onto the encoder's input
     * surface — a hardware [Canvas] — so the studio's GraphicsLayer can be
     * replayed GPU-to-GPU with no intermediate bitmap and no read-back. Returns
     * false once the encoder has failed and recording should stop.
     */
    fun submitFrame(draw: (Canvas) -> Unit): Boolean {
        val surface = inputSurface ?: return false
        if (failed || !started) return false
        return try {
            drainVideo(false)
            // The posted buffer's timestamp is the wall clock; drainVideo
            // rebases it to zero so audio and video stay aligned.
            val canvas = surface.lockHardwareCanvas()
            try {
                draw(canvas)
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
            submittedFrames++
            true
        } catch (e: Exception) {
            Log.e(TAG, "submitFrame failed", e)
            failed = true
            false
        }
    }

    private fun drainVideo(endOfStream: Boolean) {
        val c = codec ?: return
        val m = muxer ?: return
        var guard = 0
        while (guard++ < 600) {
            val outIndex = c.dequeueOutputBuffer(videoBufferInfo, if (endOfStream) 10_000 else 0)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(muxerLock) {
                    if (videoTrack < 0) {
                        videoTrack = m.addTrack(c.outputFormat)
                        maybeStartMuxer()
                    }
                }
                outIndex >= 0 -> {
                    val buf = c.getOutputBuffer(outIndex)
                    val isConfig =
                        videoBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buf != null && !isConfig && videoBufferInfo.size > 0) {
                        // The input surface stamps frames with the wall clock —
                        // rebase to the recording start so the muxed video
                        // track begins at zero, like the audio track.
                        videoBufferInfo.presentationTimeUs =
                            (videoBufferInfo.presentationTimeUs - startUs).coerceAtLeast(0)
                        synchronized(muxerLock) {
                            if (muxerStarted) {
                                buf.position(videoBufferInfo.offset)
                                buf.limit(videoBufferInfo.offset + videoBufferInfo.size)
                                m.writeSampleData(videoTrack, buf, videoBufferInfo)
                            }
                        }
                    }
                    c.releaseOutputBuffer(outIndex, false)
                    if (videoBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        return
                    }
                }
            }
        }
    }

    // --- Audio --------------------------------------------------------------

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked before withAudio is set
    private fun startAudio() {
        try {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) return
            audioBufSize = maxOf(minBuf * 2, 8192)
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                audioBufSize
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return
            }
            val format = MediaFormat.createAudioFormat(AUDIO_MIME, SAMPLE_RATE, 1).apply {
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC
                )
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, audioBufSize)
            }
            val codec = MediaCodec.createEncoderByType(AUDIO_MIME)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            audioRecord = record
            audioCodec = codec
            audioOn = true
            audioActive = true
            audioThread = Thread { audioLoop() }.also { it.start() }
        } catch (e: Exception) {
            Log.w(TAG, "Audio unavailable, recording video-only", e)
            runCatching { audioRecord?.release() }
            runCatching { audioCodec?.release() }
            audioRecord = null
            audioCodec = null
            audioOn = false
        }
    }

    private fun audioLoop() {
        val record = audioRecord ?: return
        val buffer = ByteArray(audioBufSize)
        try {
            record.startRecording()
            while (audioActive) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    feedAudio(buffer, read, endOfStream = false)
                    drainAudio(false)
                }
            }
            feedAudio(buffer, 0, endOfStream = true)
            drainAudio(true)
        } catch (e: Exception) {
            Log.w(TAG, "Audio loop ended", e)
        }
    }

    private fun feedAudio(buffer: ByteArray, length: Int, endOfStream: Boolean) {
        val c = audioCodec ?: return
        val index = c.dequeueInputBuffer(10_000)
        if (index < 0) return
        val input = c.getInputBuffer(index) ?: return
        input.clear()
        if (length > 0) input.put(buffer, 0, length)
        val pts = audioSamples * 1_000_000L / SAMPLE_RATE
        audioSamples += length / 2 // 16-bit mono => 2 bytes per sample
        c.queueInputBuffer(
            index, 0, length, pts,
            if (endOfStream) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
        )
    }

    private fun drainAudio(endOfStream: Boolean) {
        val c = audioCodec ?: return
        val m = muxer ?: return
        var guard = 0
        while (guard++ < 600) {
            val outIndex = c.dequeueOutputBuffer(audioBufferInfo, if (endOfStream) 10_000 else 0)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(muxerLock) {
                    if (audioTrack < 0) {
                        audioTrack = m.addTrack(c.outputFormat)
                        maybeStartMuxer()
                    }
                }
                outIndex >= 0 -> {
                    val buf = c.getOutputBuffer(outIndex)
                    val isConfig =
                        audioBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buf != null && !isConfig && audioBufferInfo.size > 0) {
                        synchronized(muxerLock) {
                            if (muxerStarted) {
                                buf.position(audioBufferInfo.offset)
                                buf.limit(audioBufferInfo.offset + audioBufferInfo.size)
                                m.writeSampleData(audioTrack, buf, audioBufferInfo)
                            }
                        }
                    }
                    c.releaseOutputBuffer(outIndex, false)
                    if (audioBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        return
                    }
                }
            }
        }
    }

    /** Caller must hold [muxerLock]. Starts the muxer once every track is ready. */
    private fun maybeStartMuxer() {
        if (muxerStarted) return
        if (videoTrack < 0) return
        if (audioOn && audioTrack < 0) return
        muxer?.start()
        muxerStarted = true
    }

    // --- Finish -------------------------------------------------------------

    /**
     * Flush everything, finalise the local MP4 and publish it to the gallery.
     * Returns the saved video's URI, or null if the recording failed.
     */
    fun finish(): Uri? {
        // Stop the microphone first so its end-of-stream is fully muxed before
        // the video stream is closed.
        if (audioOn) {
            audioActive = false
            runCatching { audioThread?.join(2500) }
            runCatching { audioRecord?.stop() }
            runCatching { audioRecord?.release() }
            audioRecord = null
            runCatching { audioCodec?.stop() }
            runCatching { audioCodec?.release() }
            audioCodec = null
        }
        val c = codec
        if (c != null && started && !failed) {
            try {
                c.signalEndOfInputStream()
                drainVideo(true)
            } catch (e: Exception) {
                Log.e(TAG, "finish drain failed", e)
                failed = true
            }
        }
        if (!muxerStarted) failed = true
        val durMs = (System.nanoTime() - startNs) / 1_000_000
        if (durMs > 0) {
            Log.i(
                TAG,
                "Encoded $submittedFrames frames in ${durMs}ms " +
                    "(${"%.1f".format(submittedFrames * 1000.0 / durMs)} fps), " +
                    "${encodeW}x$encodeH"
            )
        }
        cleanup()
        val tmp = tempFile
        tempFile = null
        if (tmp == null) return null
        if (failed || !tmp.exists() || tmp.length() == 0L) {
            runCatching { tmp.delete() }
            return null
        }
        val uri = publishToGallery(tmp)
        runCatching { tmp.delete() }
        return uri
    }

    /** Copy the finished MP4 into the gallery — one sequential write, not 60/s. */
    private fun publishToGallery(file: File): Uri? {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "EUC_$stamp.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/EUC Planet")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out, 1 shl 16) }
            } ?: throw IllegalStateException("no output stream")
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                null, null
            )
            uri
        } catch (e: Exception) {
            Log.e(TAG, "Publish to gallery failed", e)
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    private fun cleanup() {
        audioActive = false
        runCatching { audioThread?.join(500) }
        runCatching { audioRecord?.release() }
        audioRecord = null
        runCatching { audioCodec?.stop() }
        runCatching { audioCodec?.release() }
        audioCodec = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { inputSurface?.release() }
        inputSurface = null
        runCatching { if (muxerStarted) muxer?.stop() }
        runCatching { muxer?.release() }
        muxer = null
    }

    private fun align16(value: Int): Int = (value / 16) * 16

    companion object {
        private const val TAG = "StudioVideoEncoder"
        private const val VIDEO_MIME = "video/avc"
        private const val AUDIO_MIME = "audio/mp4a-latm"
        private const val SAMPLE_RATE = 44_100

        /** Longest encoded edge on a hardware encoder. */
        private const val HW_LONG_EDGE = 1920

        /** Longest encoded edge on a software encoder — small enough for 60 fps. */
        private const val SW_LONG_EDGE = 1080
    }
}
