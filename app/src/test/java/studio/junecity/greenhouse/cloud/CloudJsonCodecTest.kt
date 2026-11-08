package studio.junecity.greenhouse.cloud

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CloudJsonCodecTest {
    @Test fun premiumEntitlementIsDecodedFromBackendVocabulary() {
        val value = CloudJsonCodec.entitlement(JSONObject()
            .put("accountId", "account-1").put("plan", "PREMIUM").put("state", "ACTIVE")
            .put("source", "GOOGLE_PLAY_VERIFIED").put("features", JSONArray().put("REMOTE_LIVE_VIDEO"))
            .put("expiresAt", 100).put("verifiedAt", 50))
        assertEquals(AccountPlan.Premium, value.plan)
        assertEquals(100_000L, value.expiresAtEpochMs)
        assertEquals(setOf(EntitlementFeature.RemoteLiveVideo), value.features)
    }

    @Test fun streamingSessionDecodesStunAndTurn() {
        val json = JSONObject().put("id", "session-1").put("greenhouseId", "greenhouse-1")
            .put("serverDeviceId", "server-1").put("viewerDeviceId", "viewer-1")
            .put("signalingChannelArn", "arn:test").put("region", "us-east-1").put("expiresAtEpochMs", 5000)
            .put("signalingWebSocketUrl", "wss://example.test").put("peerClientId", JSONObject.NULL)
            .put("iceServers", JSONArray().put(JSONObject().put("urls", JSONArray().put("stun:test"))))
        val value = CloudJsonCodec.streamingSession(json)
        assertEquals(listOf("stun:test"), value.iceServers.single().urls)
        assertNull(value.peerClientId)
    }
}
