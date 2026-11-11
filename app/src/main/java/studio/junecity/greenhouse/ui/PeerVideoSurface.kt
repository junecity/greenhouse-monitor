package studio.junecity.greenhouse.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import studio.junecity.greenhouse.streaming.VideoFrameOutput

@Composable
internal fun PeerVideoSurface(output: VideoFrameOutput, modifier: Modifier, mirror: Boolean = false) {
    key(output) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                SurfaceViewRenderer(context).apply {
                    init(VideoFrameOutput.renderContext, null)
                    setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                    setMirror(mirror)
                    output.attach(this)
                }
            },
            onRelease = { renderer ->
                output.detach(renderer)
                renderer.release()
            }
        )
    }
}
