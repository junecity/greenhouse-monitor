package studio.junecity.greenhouse.streaming

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Base64

class KinesisSignalingCodecTest {
    @Test fun singleMasterViewerInitiatesOffer() {
        assertEquals(true, WebRtcRole.Viewer.initiatesOffer())
        assertEquals(false, WebRtcRole.Master.initiatesOffer())
    }
    @Test fun offerUsesKinesisEnvelopeAndRoundTrips() {
        val description = SignalingDescription("offer", "v=0\r\n")
        val outgoing = JSONObject(KinesisSignalingCodec.outgoingDescription(description, "viewer-1"))
        assertEquals("SDP_OFFER", outgoing.getString("action"))
        assertEquals("viewer-1", outgoing.getString("recipientClientId"))
        val incoming = JSONObject()
            .put("messageType", "SDP_OFFER")
            .put("messagePayload", outgoing.getString("messagePayload"))
            .toString()
        assertEquals(description, KinesisSignalingCodec.incoming(incoming).description)
    }

    @Test fun iceCandidateRoundTripsFromAwsEnvelope() {
        val candidate = SignalingIceCandidate("0", 0, "candidate:1 1 UDP 1 192.0.2.1 5000 typ host")
        val outgoing = JSONObject(KinesisSignalingCodec.outgoingCandidate(candidate, null))
        assertFalse(outgoing.has("recipientClientId"))
        val awsMessage = JSONObject()
            .put("messageType", "ICE_CANDIDATE")
            .put("messagePayload", outgoing.getString("messagePayload"))
            .toString()
        assertEquals(candidate, KinesisSignalingCodec.incoming(awsMessage).candidate)
    }

    @Test fun viewerOfferOmitsRecipientInsteadOfSendingInvalidEmptyId() {
        val outgoing = JSONObject(KinesisSignalingCodec.outgoingDescription(SignalingDescription("offer", "v=0"), null))
        assertFalse(outgoing.has("recipientClientId"))
    }

    @Test fun signalingStatusFailureIsDecodedWithoutRequiringPayload() {
        val incoming = KinesisSignalingCodec.incoming(
            JSONObject().put("messageType", "STATUS_RESPONSE").put(
                "statusResponse",
                JSONObject().put("success", false).put("statusCode", "400")
            ).toString()
        )
        assertEquals(false, incoming.statusSuccess)
        assertEquals("400", incoming.statusCode)
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedPayloadIsRejected() {
        KinesisSignalingCodec.incoming(JSONObject().put("messageType", "SDP_OFFER").put("messagePayload", Base64.getEncoder().encodeToString("bad".toByteArray())).toString())
    }

    @Test fun incomingOfferPreservesSenderForMasterReply() {
        val payload = Base64.getEncoder().encodeToString("{\"type\":\"offer\",\"sdp\":\"v=0\"}".toByteArray())
        val incoming = KinesisSignalingCodec.incoming(JSONObject().put("messageType", "SDP_OFFER").put("senderClientId", "viewer-12345678").put("messagePayload", payload).toString())
        assertEquals("viewer-12345678", incoming.senderClientId)
        assertEquals("offer", incoming.description?.type)
    }
}

