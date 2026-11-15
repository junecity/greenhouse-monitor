package studio.junecity.greenhouse.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import studio.junecity.greenhouse.streaming.VideoFrameOutput

@Composable
internal fun PeerVideoSurface(
    output: VideoFrameOutput,
    modifier: Modifier,
    mirror: Boolean = false,
) {
    val samples = LocalCameraSamples.current
    key(output) {
        val captureQueued =
            androidx.compose.runtime.remember { java.util.concurrent.atomic.AtomicBoolean(false) }
        val captureHolder =
            androidx.compose.runtime.remember { arrayOfNulls<org.webrtc.VideoSink>(1) }
        AndroidView(
            modifier = modifier,
            factory = { context ->
                SurfaceViewRenderer(context).apply {
                    init(VideoFrameOutput.renderContext, null)
                    setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                    setMirror(mirror)
                    output.attach(this)
                    if (samples != null) {
                        // EglRenderer creates a bitmap only when a sample is requested.
                        val renderer = this
                        captureHolder[0] =
                            org.webrtc.VideoSink { frame ->
                                if (
                                    samples.requested() && captureQueued.compareAndSet(false, true)
                                ) {
                                    renderer.addFrameListener(
                                        { bitmap ->
                                            captureQueued.set(false)
                                            samples.offer { bitmap }
                                        },
                                        0.5f,
                                    )
                                }
                            }
                        output.attach(checkNotNull(captureHolder[0]))
                    }
                }
            },
            onRelease = { renderer ->
                captureHolder[0]?.let(output::detach)
                output.detach(renderer)
                renderer.release()
            },
        )
    }
}
