package studio.junecity.greenhouse.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

enum class MovieKind(val label: String, val durationMs: Long, val stepMs: Long) {
    Clip("Short clip", 15_000, 200),
    Timelapse("Timelapse", 300_000, 5_000),
    Cloud("Cloud clip", 60_000, 200),
}

class CameraMovies(root: File, enrollment: String, house: String) {
    init {
        require(enrollment.isNotBlank() && house.isNotBlank())
    }

    private val directory =
        File(
            root,
            MessageDigest.getInstance("SHA-256")
                .digest("$enrollment\u0000$house".toByteArray())
                .joinToString("") { "%02x".format(it) },
        )

    fun library(): List<File> =
        directory
            .listFiles()
            .orEmpty()
            .filter { it.extension == "mp4" }
            .sortedByDescending { it.name }

    fun delete(file: File): Boolean =
        file.parentFile == directory && file.extension == "mp4" && file.delete()

    suspend fun record(
        kind: MovieKind,
        frame: suspend () -> Bitmap?,
        progress: (Int) -> Unit,
        stop: () -> Boolean,
        recordingId: UUID = UUID.randomUUID(),
        recycleFrames: Boolean = false,
    ): File =
        withContext(Dispatchers.IO) {
            check(directory.isDirectory || directory.mkdirs()) { "Recording storage unavailable" }
            directory
                .listFiles()
                .orEmpty()
                .filter {
                    it.extension == "part" &&
                        System.currentTimeMillis() - it.lastModified() > 3_600_000
                }
                .forEach { it.delete() }
            check(directory.usableSpace > 24L * 1024 * 1024) { "Free up space on this phone" }
            val name = "${System.currentTimeMillis()}-${kind.name}-$recordingId"
            val stage = File(directory, "$name.part")
            try {
                var count = 0
                val start = SystemClock.elapsedRealtime()
                Mp4Frames(stage).use { encoder ->
                    var next = start
                    while (!stop() && SystemClock.elapsedRealtime() - start < kind.durationMs) {
                        val image = frame() ?: break
                        val elapsed = SystemClock.elapsedRealtime() - start
                        try {
                            encoder.write(image, if (kind == MovieKind.Timelapse) count * 200_000L else elapsed * 1000)
                        } finally {
                            if (recycleFrames) image.recycle()
                        }
                        count++
                        withContext(Dispatchers.Main) {
                            progress(((kind.durationMs - elapsed).coerceAtLeast(0) / 1000).toInt())
                        }
                        next = maxOf(next + kind.stepMs, SystemClock.elapsedRealtime())
                        delay(minOf(next, start + kind.durationMs) - SystemClock.elapsedRealtime())
                    }
                    check(count >= 2) { "Too few camera frames. Start the camera and try again." }
                    encoder.finish()
                }
                val saved = File(directory, "$name.mp4")
                check(stage.renameTo(saved)) { "Could not save recording" }
                saved
            } finally {
                stage.delete()
            }
        }
}

/** Encode sampled images with explicit presentation timestamps so a timelapse plays at 5 fps. */
internal class Mp4Frames(file: File) : AutoCloseable {
    private val width = 480
    private val height = 272
    private val codec = MediaCodec.createEncoderByType("video/avc")
    private val muxer =
        try {
            MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: Exception) {
            codec.release()
            throw e
        }
    private val info = MediaCodec.BufferInfo()
    private val canvasImage = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(canvasImage)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pixels = IntArray(width * height)
    private var track = -1
    private var lastPts = 0L

    init {
        try {
            val format =
                MediaFormat.createVideoFormat("video/avc", width, height).apply {
                    setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
                    )
                    setInteger(MediaFormat.KEY_FRAME_RATE, 5)
                    setInteger(MediaFormat.KEY_BIT_RATE, 550_000)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    fun write(bitmap: Bitmap, pts: Long) {
        val scale = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val x = (width - bitmap.width * scale) / 2
        val y = (height - bitmap.height * scale) / 2
        canvas.drawColor(android.graphics.Color.BLACK)
        canvas.drawBitmap(bitmap, null, RectF(x, y, width - x, height - y), paint)
        canvasImage.getPixels(pixels, 0, width, 0, 0, width, height)
        val index = input()
        val image = checkNotNull(codec.getInputImage(index))
        fillYuv(image)
        lastPts = maxOf(lastPts + 1, pts)
        codec.queueInputBuffer(index, 0, width * height * 3 / 2, lastPts, 0)
        drain(false)
    }

    private fun fillYuv(image: Image) {
        for ((planeIndex, plane) in image.planes.withIndex()) {
            val bytes = plane.buffer
            val base = bytes.position()
            val step = if (planeIndex == 0) 1 else 2
            for (y in 0 until height step step) for (x in 0 until width step step) {
                val pixel = pixels[y * width + x]
                val r = pixel ushr 16 and 255
                val g = pixel ushr 8 and 255
                val b = pixel and 255
                val value =
                    when (planeIndex) {
                        0 -> 16 + ((66 * r + 129 * g + 25 * b + 128) shr 8)
                        1 -> 128 + ((-38 * r - 74 * g + 112 * b + 128) shr 8)
                        else -> 128 + ((112 * r - 94 * g - 18 * b + 128) shr 8)
                    }
                bytes.put(
                    base + (y / step) * plane.rowStride + (x / step) * plane.pixelStride,
                    value.toByte(),
                )
            }
        }
    }

    private fun input(): Int {
        repeat(100) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index >= 0) return index
            drain(false)
        }
        error("Video encoder timed out")
    }

    fun finish() {
        codec.queueInputBuffer(
            input(),
            0,
            0,
            lastPts + 200_000,
            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
        )
        drain(true)
        muxer.stop()
    }

    private fun drain(final: Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 3_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val index = codec.dequeueOutputBuffer(info, if (final) 10_000 else 0)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                }
                index == MediaCodec.INFO_TRY_AGAIN_LATER && !final -> return
                index >= 0 -> {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0)
                        muxer.writeSampleData(
                            track,
                            checkNotNull(codec.getOutputBuffer(index)),
                            info,
                        )
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
        check(!final) { "Could not finish video" }
    }

    override fun close() {
        try {
            codec.release()
        } finally {
            muxer.release()
            canvasImage.recycle()
        }
    }
}
