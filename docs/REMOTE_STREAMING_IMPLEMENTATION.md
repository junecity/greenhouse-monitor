# Remote camera streaming — implementation contract

Status: **Phase 1 scaffold only**. No AWS services deployed; no WebRTC media transport connected.

## Product rules
- Galaxy A71 or customer's old Android phone remains the server, sensor processor, display and camera.
- Pixel 10 or customer's everyday Android phone is the viewer.
- The existing five-screen visual design is frozen.
- Remote live camera is **Premium**; owner/developer complimentary access is an explicit server-side grant.
- Local greenhouse monitoring, sensor history, and local camera access must remain available without Premium.
- Stream starts only after an intentional viewer action and stops on leaving the view, closing it or locking the client (subject to verified Android lifecycle handling).
- No continuous cloud video recording or uploading. One active viewing session should not create continuous relay usage.
- No required external registration website. All customer onboarding is in-app.
- Dedicated AWS resources and identity boundaries, separate from junecity.studio.

## Proposed implementation sequence
1. Add Cognito-based customer authentication and backend device identity. Pair server and client with expiring one-use codes; never expose credentials or camera keys in logs.
2. Add server-verified entitlement checks before issuing AWS Kinesis Video Streams WebRTC signaling permissions and TURN/ICE credentials. Validate Google Play subscriptions server-side; never rely on the Kotlin policy alone.
3. Implement WebRTC sender on greenhouse phone and receiver on client using tested Android SDK/library versions. Keep existing local stream working until migration is verified. Use short-lived credentials, encrypted media, and authorization-scoped signaling channels.
4. Use ICE/STUN to connect peer-to-peer when possible, and AWS-managed TURN relay when necessary. Detect Wi-Fi/cellular transitions and ICE disconnects, reconnect with bounded exponential backoff, and expose clear failure states.
5. Test camera lifecycle: only capture/transmit while viewing, terminate sessions on viewer departure/lock, handle app process death, Android camera/foreground-service restrictions, server restart, and multiple client requests.
6. Remove public UPnP exposure after remote WebRTC is fully tested. Do not expose an HTTP endpoint containing a pairing token over the public internet.
7. Add independent AWS heartbeat monitoring and offline alerting; keep this distinct from paid remote camera access and ordinary website notifications.
8. Add metrics and budgets for TURN relay minutes, data egress, signaling, concurrency, and per-customer limits. Evaluate AWS pricing and quotas for expected concurrency before deployment.

## Security and commercial acceptance
- Backend denies free, expired, revoked and cross-customer access to signaling/TURN credentials.
- No private IP/public IP registry is treated as a NAT traversal solution.
- Customer cannot impersonate another greenhouse device or view another customer's stream.
- Temporary cloud outage does not stop available local sensor monitoring.
- Validate direct LAN, cellular, CGNAT, changing public IP, relay-only, network drop/reconnect, revoked premium, subscription expiry, and owner complimentary access.
- Do not deploy billable AWS services or alter live routing until cost/security review and deployment approval.

## Current source facts
- `CameraStreamServer.kt` serves encrypted JPEG frames on TCP 45891.
- `RemoteCameraFeed.kt` tries saved local then public address.
- `UpnpPortMapper.kt` configures router UPnP mapping.
- `CameraPairing.kt` holds local/public hosts and pairing credentials.

This implementation is retained as a migration/local fallback. It is not commercially scalable because pairing embeds network addresses, public IP changes break remote access, UPnP exposes a device port, and there is no customer identity or server-side entitlement check.

## Target architecture

1. Cognito user-pool identity authenticates customers in the Android application.
2. A Greenhouse API authorizer derives the tenant from validated JWT claims. Clients never submit an authoritative tenant ID.
3. The backend stores accounts, devices, greenhouse membership, pairing challenges, entitlements, heartbeats, and short-lived streaming sessions in dedicated DynamoDB tables.
4. An authorized Premium client requests a viewing session. The backend verifies tenant/device access and entitlement before returning short-lived Kinesis signaling configuration.
5. The greenhouse server receives an on-demand request, starts CameraX/WebRTC capture, and joins as master. The viewer joins with a unique client ID.
6. ICE prefers direct connectivity; AWS-managed STUN/TURN data is returned by Kinesis. No router forwarding is required.
7. Closing/backgrounding the viewer ends the session. The server stops capture when no authorized viewer remains or the lease expires.
8. Local same-network viewing remains available independently of AWS and does not require Premium.

## Verified SDK facts

- AWS's Android repository release v1.3.0 targets API 29+ and demonstrates Kinesis signaling with AWS Android SDK 2.81.1.
- The AWS repository does not publish a complete drop-in media artifact. Its documented setup requires importing a compatible `libwebrtc` AAR.
- Physical Android devices must verify H.264 encode/decode support; emulator success is insufficient.
- AWS temporary credentials or backend-minted signed signaling data replace embedded long-lived keys.

## Migration gates

Do not retire the existing remote path until all of these pass on the Galaxy A71 and Pixel 10: authenticated session creation, direct ICE, TURN fallback, network handoff, reconnect, viewer close/background termination, server restart, expired/revoked entitlement denial, cross-tenant denial, and AWS outage fallback to local monitoring.

UPnP must be disabled by default after WebRTC verification and removed only after a rollback release is available.

## Current status

- Existing local/encrypted-JPEG path: implemented; previously device-rendered, not revalidated in this stage.
- Identity, tenant/device authorization, backend entitlement, pairing, heartbeat, and WebRTC session contracts: implemented as credential-free foundations.
- Backend authorization denies Free, expired, revoked, disabled-device, wrong-role, unverified-source, and cross-tenant requests. Live AWS integration is not yet verified.
- AWS Cognito/API Gateway/DynamoDB/Lambda/SNS/scheduled-monitor resources: defined in SAM but not created or deployed.
- Kinesis-compatible WebRTC media peer, SDP/ICE signaling codec, secure WebSocket transport, and on-demand lifecycle: implemented and build-tested behind interfaces.
- Activation remains intentionally disabled: the backend does not yet issue signed WSS endpoints/ICE payloads, and no AWS or physical-device validation has occurred.

## Streaming lifecycle invariants

- No camera peer is created until the backend grants a streaming session.
- Only the master creates a local camera track; viewers receive remote video only.
- Reconnect uses bounded exponential backoff and waits for network availability after handoff.
- Viewer close/background/lock, lease expiry, and retry exhaustion close signaling, peer connection, camera capturer, video source, texture thread, factory, and EGL resources, then end the backend session.
- The server sensor-monitoring path does not depend on a viewing session.

## Phase 1 delivered
- `RemoteCameraAccessPolicy.kt`: pure entitlement decision logic for future UI/service integration.
- `RemoteCameraAccessPolicyTest.kt`: local/free/premium/complimentary/expired/unverified cases.
- **Not delivered:** authenticated AWS backend, Play Billing integration, signaling/TURN credentials, WebRTC sender/receiver, UI integration, or deployed infrastructure.
