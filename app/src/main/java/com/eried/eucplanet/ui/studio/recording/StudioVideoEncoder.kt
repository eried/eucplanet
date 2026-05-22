package com.eried.eucplanet.ui.studio.recording

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records the Overlay Studio to an MP4 **without** MediaProjection — so there
 * is no "share your screen" consent dialog.
 *
 * Video: the studio (cameras + overlays) is captured each frame into a [Bitmap]
 * by the caller, converted to YUV and fed to an H.264 [MediaCodec].
 * Audio (optional): the device microphone is read on a background thread and
 * fed to an AAC [MediaCodec]. Both streams are interleaved into one MP4 by a
 * shared [MediaMuxer].
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
    private var muxer: MediaMuxer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var outputUri: Uri? = null
    private val videoBufferInfo = MediaCodec.BufferInfo()
    private var encodeW = 0
    private var encodeH = 0
    private var startNs = 0L
    private var lastPtsUs = -1L
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

    /** Reused per-frame scratch so we are not allocating bitmaps a second. */
    private var scaled: Bitmap? = null
    private var pixels: IntArray? = null

    /** Worker pool that splits the per-frame ARGB->YUV conversion across cores. */
    private val yuvThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
    private var yuvExecutor: java.util.concurrent.ExecutorService? = null

    /** True once [start] has successfully set up the video codec + muxer. */
    var started = false
        private set

    /**
     * Prepare the encoder for a [captureWidth] x [captureHeight] source.
     * Returns false if the device could not give us a video encoder or output
     * file; if only the microphone fails, recording continues video-only.
     */
    fun start(captureWidth: Int, captureHeight: Int): Boolean {
        if (captureWidth <= 0 || captureHeight <= 0) return false
        encodeW = align16(captureWidth.coerceAtMost(1080))
        encodeH = align16((encodeW.toLong() * captureHeight / captureWidth).toInt())
        if (encodeW < 16 || encodeH < 16) return false
        return try {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "EUC_$stamp.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/EUC Planet")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            outputUri = context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return false
            pfd = context.contentResolver.openFileDescriptor(outputUri!!, "rw")
                ?: return false

            val enc = MediaCodec.createEncoderByType(VIDEO_MIME)
            codec = enc
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
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, 60)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            muxer = MediaMuxer(
                pfd!!.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
            startNs = System.nanoTime()
            if (withAudio) startAudio()
            started = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "Encoder start failed", e)
            failed = true
            cleanup()
            false
        }
    }

    // --- Video --------------------------------------------------------------

    /** Encode one studio frame. The bitmap may be any size; it is scaled to fit. */
    fun submitFrame(frame: Bitmap) {
        val c = codec ?: return
        if (failed || !started) return
        try {
            drainVideo(false)
            val index = c.dequeueInputBuffer(10_000)
            if (index < 0) return
            val image = c.getInputImage(index)
            if (image == null) {
                failed = true
                return
            }
            fillImage(frame, image)
            c.queueInputBuffer(index, 0, encodeW * encodeH * 3 / 2, nextPtsUs(), 0)
            submittedFrames++
        } catch (e: Exception) {
            Log.e(TAG, "submitFrame failed", e)
            failed = true
        }
    }

    private fun nextPtsUs(): Long {
        val raw = (System.nanoTime() - startNs) / 1000
        val pts = if (raw <= lastPtsUs) lastPtsUs + 1 else raw
        lastPtsUs = pts
        return pts
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
     * Flush everything, finalise the MP4 and publish it to the gallery.
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
                val index = c.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    c.queueInputBuffer(
                        index, 0, 0, nextPtsUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                }
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
        val uri = outputUri
        outputUri = null
        if (uri == null) return null
        return if (failed) {
            runCatching { context.contentResolver.delete(uri, null, null) }
            null
        } else {
            runCatching {
                context.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                    null, null
                )
            }
            uri
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
        runCatching { if (muxerStarted) muxer?.stop() }
        runCatching { muxer?.release() }
        muxer = null
        runCatching { pfd?.close() }
        pfd = null
        scaled?.recycle()
        scaled = null
        pixels = null
        runCatching { yuvExecutor?.shutdown() }
        yuvExecutor = null
    }

    // --- Frame conversion ---------------------------------------------------

    /** Scale [frame] into the encoder size and convert ARGB -> YUV420 in [image]. */
    private fun fillImage(frame: Bitmap, image: Image) {
        val w = image.width
        val h = image.height
        // GraphicsLayer.toImageBitmap() can hand back a HARDWARE bitmap, which a
        // software Canvas can neither draw nor getPixels — copy it first.
        val source = if (frame.config == Bitmap.Config.HARDWARE) {
            frame.copy(Bitmap.Config.ARGB_8888, false) ?: return
        } else {
            frame
        }
        val target = scaled ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            .also { scaled = it }
        Canvas(target).drawBitmap(
            source,
            Rect(0, 0, source.width, source.height),
            Rect(0, 0, w, h),
            null
        )
        if (source !== frame) source.recycle()
        val argb = pixels ?: IntArray(w * h).also { pixels = it }
        target.getPixels(argb, 0, w, 0, 0, w, h)

        val yP = image.planes[0]
        val uP = image.planes[1]
        val vP = image.planes[2]
        val yBuf = yP.buffer
        val uBuf = uP.buffer
        val vBuf = vP.buffer
        // ARGB -> YUV420 is the per-frame hot loop; split the rows across a
        // worker pool so a 1080p frame converts fast enough to stay smooth.
        // Absolute ByteBuffer puts to distinct indices are race-free.
        val pool = yuvExecutor ?: java.util.concurrent.Executors
            .newFixedThreadPool(yuvThreads).also { yuvExecutor = it }
        val rowsPerBand = (h + yuvThreads - 1) / yuvThreads
        val tasks = (0 until yuvThreads).mapNotNull { band ->
            val jStart = band * rowsPerBand
            if (jStart >= h) return@mapNotNull null
            val jEnd = minOf(jStart + rowsPerBand, h)
            java.util.concurrent.Callable {
                for (j in jStart until jEnd) {
                    val rowYuv = j shr 1
                    val rowArgb = j * w
                    for (i in 0 until w) {
                        val c = argb[rowArgb + i]
                        val r = (c shr 16) and 0xFF
                        val g = (c shr 8) and 0xFF
                        val b = c and 0xFF
                        val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                        yBuf.put(j * yP.rowStride + i * yP.pixelStride, clamp(y))
                        if (j and 1 == 0 && i and 1 == 0) {
                            val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                            val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                            val col = i shr 1
                            uBuf.put(rowYuv * uP.rowStride + col * uP.pixelStride, clamp(u))
                            vBuf.put(rowYuv * vP.rowStride + col * vP.pixelStride, clamp(v))
                        }
                    }
                }
            }
        }
        pool.invokeAll(tasks)
    }

    private fun clamp(v: Int): Byte = v.coerceIn(0, 255).toByte()

    private fun align16(value: Int): Int = (value / 16) * 16

    companion object {
        private const val TAG = "StudioVideoEncoder"
        private const val VIDEO_MIME = "video/avc"
        private const val AUDIO_MIME = "audio/mp4a-latm"
        private const val SAMPLE_RATE = 44_100
    }
}
