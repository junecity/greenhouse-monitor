package studio.junecity.greenhouse.streaming

import org.junit.Assert.*
import org.junit.Test
import studio.junecity.greenhouse.streaming.LiveViewWindow.Phase

class LiveViewWindowTest {
    @Test fun expiresFifteenSecondsAfterFirstFrameAndRequiresAnotherTap() {
        val waiting = LiveViewWindow().start(1000)
        val showing = waiting.frame(6000)
        assertEquals(15L, showing.secondsRemaining(6000))
        assertEquals(showing, showing.frame(12000))
        assertTrue(showing.tick(20999).active)
        val expired = showing.tick(21000)
        assertEquals(Phase.Expired, expired.phase)
        assertFalse(expired.tick(30000).active)
        assertEquals(Phase.Connecting, expired.start(40000).phase)
    }

    @Test fun unsuccessfulConnectionStopsAndALateFrameCannotRestartIt() {
        val waiting = LiveViewWindow().start(0)
        assertEquals(Phase.Failed, waiting.frame(30000).phase)
        assertFalse(waiting.tick(30000).frame(31000).active)
        assertFalse(LiveViewWindow().frame(31000).active)
    }
}
