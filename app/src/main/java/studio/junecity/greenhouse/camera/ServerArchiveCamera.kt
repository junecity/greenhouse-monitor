package studio.junecity.greenhouse.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.CapturerObserver
import org.webrtc.PeerConnectionFactory
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame
import org.webrtc.YuvHelper
import studio.junecity.greenhouse.streaming.CloudCameraLease
import studio.junecity.greenhouse.streaming.VideoFrameOutput

/** A silent front-camera take owned by the camera foreground service, including at Android Home. */
internal suspend fun recordServerArchiveClip(
    context: Context,
    movies: CameraMovies,
    id: UUID,
    checkPermission: suspend () -> Unit,
) = withContext(Dispatchers.IO) {
    checkPermission()
    PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
        .createInitializationOptions())
    val enumerator: CameraEnumerator = if (Camera2Enumerator.isSupported(context)) Camera2Enumerator(context) else Camera1Enumerator(false)
    val front = enumerator.deviceNames.firstOrNull(enumerator::isFrontFacing) ?: error("Front camera unavailable")
    val camera = checkNotNull(enumerator.createCapturer(front, null))
    val samples = CameraSamples()
    val converter = ArchiveFrameConverter()
    val ready = CompletableDeferred<Boolean>()
    val helper = checkNotNull(SurfaceTextureHelper.create("GreenhouseArchiveCamera", VideoFrameOutput.renderContext))
    try {
        camera.initialize(helper, context.applicationContext, object : CapturerObserver {
            override fun onCapturerStarted(success: Boolean) { ready.complete(success) }
            override fun onCapturerStopped() = Unit
            override fun onFrameCaptured(frame: VideoFrame) {
                CloudCameraLease.preview.onFrame(frame)
                samples.offer { converter.bitmap(frame) }
            }
        })
        camera.startCapture(480, 272, 10)
        check(withTimeout(12_000) { ready.await() }) { "Front camera did not start" }
        movies.record(MovieKind.Cloud, frame = {
            checkPermission()
            checkNotNull(samples.next()) { "No camera frame arrived" }
        }, progress = {}, stop = { false }, recordingId = id, recycleFrames = true)
    } finally {
        try { camera.stopCapture() } finally {
            try { camera.dispose() } finally { helper.dispose() }
        }
    }
}

/** Convert an occasional sample to the encoder's Bitmap input and apply the sensor's orientation. */
private class ArchiveFrameConverter {
    private var bytes = ByteArray(0)
    private var nv21 = ByteBuffer.allocateDirect(0)
    private val jpeg = ByteArrayOutputStream()

    fun bitmap(frame: VideoFrame): Bitmap {
        val pixels = checkNotNull(frame.buffer.toI420())
        try {
            val w = pixels.width
            val h = pixels.height
            require(w % 2 == 0 && h % 2 == 0)
            val size = w * h * 3 / 2
            if (bytes.size != size) {
                bytes = ByteArray(size)
                nv21 = ByteBuffer.allocateDirect(size)
            }
            nv21.clear()
            // NV21 stores V before U. Swap the source planes passed to the NV12 interleaver.
            YuvHelper.I420ToNV12(pixels.dataY, pixels.strideY, pixels.dataV, pixels.strideV,
                pixels.dataU, pixels.strideU, nv21, w, h)
            nv21.rewind()
            nv21.get(bytes)
            jpeg.reset()
            check(YuvImage(bytes, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 85, jpeg))
            val raw = checkNotNull(BitmapFactory.decodeByteArray(jpeg.toByteArray(), 0, jpeg.size()))
            if (frame.rotation == 0) return raw
            val rotate = Matrix().apply { postRotate(frame.rotation.toFloat()) }
            return Bitmap.createBitmap(raw, 0, 0, w, h, rotate, true).also { if (it !== raw) raw.recycle() }
        } finally { pixels.release() }
    }
}
