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

## Phase 1 delivered
- `RemoteCameraAccessPolicy.kt`: pure entitlement decision logic for future UI/service integration.
- `RemoteCameraAccessPolicyTest.kt`: local/free/premium/complimentary/expired/unverified cases.
- **Not delivered:** authenticated AWS backend, Play Billing integration, signaling/TURN credentials, WebRTC sender/receiver, UI integration, or deployed infrastructure.
