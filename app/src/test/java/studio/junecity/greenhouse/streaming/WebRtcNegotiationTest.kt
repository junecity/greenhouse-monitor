package studio.junecity.greenhouse.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebRtcNegotiationTest {
    @Test fun viewerOffersAndRequestsRemoteVideo() {
        assertTrue(WebRtcRole.Viewer.initiatesOffer())
        assertTrue(WebRtcRole.Viewer.receivesRemoteVideo())
        assertFalse(WebRtcRole.Master.initiatesOffer())
        assertFalse(WebRtcRole.Master.receivesRemoteVideo())
    }

    @Test fun candidatesAreHeldUntilRemoteDescriptionIsApplied() {
        val buffer = RemoteIceCandidateBuffer<String>()
        assertEquals(emptyList<String>(), buffer.receive("candidate-one"))
        assertEquals(emptyList<String>(), buffer.receive("candidate-two"))
        assertEquals(listOf("candidate-one", "candidate-two"), buffer.remoteDescriptionApplied())
        assertEquals(listOf("candidate-three"), buffer.receive("candidate-three"))
        assertEquals(emptyList<String>(), buffer.remoteDescriptionApplied())
    }
}
