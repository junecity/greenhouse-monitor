package studio.junecity.greenhouse.streaming

import org.webrtc.EglBase
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

/** Surfaces may detach while the camera keeps capturing for an authorized viewer. */
class VideoFrameOutput : VideoSink {
    private val sinks = mutableSetOf<VideoSink>()

    @Synchronized fun attach(sink: VideoSink) { sinks.add(sink) }
    @Synchronized fun detach(sink: VideoSink) { sinks.remove(sink) }
    @Synchronized override fun onFrame(frame: VideoFrame) { sinks.forEach { it.onFrame(frame) } }

    companion object {
        // The camera texture, decoder, and visible surfaces must share an EGL group.
        private val root by lazy { EglBase.create() }
        val renderContext: EglBase.Context get() = root.eglBaseContext
    }
}
