# Changelog — Greenhouse Monitor

All meaningful changes must be recorded here, newest first. Each entry should include date (YYYY-MM-DD), branch/commit where known, Added/Changed/Fixed, validation performed, and limitations. Do not claim untested work is working.

## 2026-11-12 — Delete greenhouse floor plans from General settings (2.10.3)
- Added a Delete action per greenhouse, named confirmation, cancellation and protection for the last plan. Deleting the displayed plan selects a remaining one. Affected sensors can be reassigned in Devices and retain their recorded history. Generated floor-plan names skip names already in use.
- Made explicit and cached assignments consistent across collection, preferences, local sensor storage and backup restoration. No database migration or backend deployment.
- A first compile flagged a missing history import, which was restored. Final configured ARM build passed 103 JVM tests, Android lint (0 errors, 40 warnings, 1 hint), signature verification and emulator upgrade-install. One targeted API 35 instrumentation test exercised the actual Settings action, cancel, confirm, fallback house, last-house protection, retained sensor/history and local server snapshot round-trip. No physical device or live cloud deletion test.
- Plain 83,208,450-byte APK in build-out and the existing Drive download. Verified Drive metadata. SHA-256 `247ffd2c8745e427470ff478fa3fd123b136754824245195ae08e8b4e062b5dd`.

## 2026-11-12 — Sensor recovery and full-width server dashboard (2.10.2)
- Both collectors isolate individual H5179 read failures, retain measurement timestamps and honor saved greenhouse assignments. Foreground entry triggers collection on the server. The viewer retries a missing server backup. Persisted reassignment and first available readings can reach the next heartbeat without waiting for the longer backup interval. Oversized backups retry with recent history, preserving local records.
- Dashboard distinguishes registered, live, and stale sensors. An empty server dashboard opens sensor management, including explicit assignment to the selected house. SpatialInterpolator supports readings before map placement. MainActivity restores immersive mode after rotation or focus changes. Compact weather cards allow long text to wrap.
- Android tests: 100 JVM tests passed. Lint reported 0 errors, 40 warnings and 1 hint. API 35 ARM suite passed 14 cases with one opt-in live AWS skip. After the weather adjustment, all three targeted layout and sensor cases passed, including both landscape rotations. Android 10 ARM tests passed both server and viewer cases at 2270 × 1080 with three-button navigation. Screenshots under build-out/2.10.2-qa. Backend tests passed 74/74 with the project Python environment.
- Initial validation encountered a screenshot masked by the Android 10 first-use immersive tutorial, an unreliable pre-API-30 navigation inset assertion and a notification-delivery test race. Rechecked with the tutorial acknowledged, effective legacy flags, full content dimensions, screenshot inspection and a bounded wait for notification delivery. Physical Moto and live Govee/cloud delivery remain unverified.
- Signed dual-ARM APK upgrade-installed and replaced on Drive. Verified file name, MIME and 83,136,678 bytes. SHA-256 `a3ce252e40dbbd10297d6fe1113573751d2ef7da0f68314e651c435b5c03e514`. No backend deployment or migration.

## 2026-11-12 — Restore full-width dashboard card positions (2.10.1)
- Restored the original Outdoor, VPD, gauge, camera, sensor and navigation grid positions. The cutout shapes only the intersecting component, with space for the complete temperature label and value. Other monitoring screens retain their existing inset handling.
- Updated DashboardCutout.kt, DashboardScreen.kt, StandaloneEditorTest.kt and release metadata. JVM suite: 98 passed. Android lint: 0 errors, 40 warnings and one hint. The first build attempt caught an incidental dependency-version edit; the final APK uses the original dependency versions.
- API 35 ARM emulator: centered-notch and corner-hole instrumentation both passed in landscape and reverse landscape. The centered-notch test verified identical coordinates for the unaffected landmarks and the original left edge of the navigation bar. Screenshots are in build-out/2.10.1-qa. Physical Galaxy/Pixel validation remains open.
- The signed dual-ARM APK upgrade-installed as version 31. Existing Drive file replaced and verified by name, MIME and byte count (83,106,146). Local SHA-256: `e7baa357a14f3964763e15de86f0cf7e446351e2fb99383275db6abcddb89873`.

## 2026-11-12 — Camera cutout, launcher artwork and overnight zone tools (2.10.0)
- The landscape dashboard follows the physical lens position with a charcoal curve edged in amber. The adjoining reading tile wraps around it. Insets protect readings and controls when rotated. Updated the adaptive and legacy launcher resources.
- Zones offers current and overnight temperature/humidity comparisons and an overnight map slider. History summarizes the coldest measured sensor and estimated minutes below the configured threshold, with observed and missing minutes per sensor. Overnight uses device-local time and supports DST.
- Temperature notification taps open incident details after the existing authentication gate. Sensor-scoped alerts show the current mapped spot and incident-hour trend. Eligible viewers can start the existing 15-second live view. Cloud alerts without a sensor ID remain greenhouse-wide.
- Local SQLite reads include the available night as well as the existing chart window. Remote playback still depends on available snapshot history and the currently saved sensor map.
- Verified 98 JVM tests, Android lint (0 errors, 40 warnings and one hint), 74 backend tests and 14 portal tests. Twelve distinct Android instrumentation cases passed across the suite and the launcher-mask check. The opt-in live AWS camera case was skipped. Centered-notch and corner-hole overlays on API 35 both passed the reading-bounds check in landscape and reverse landscape. Emulated 69°F screenshots and the rendered launcher are under build-out/2.10.0-qa. No physical phone validation of this release.
- Verified install-in-place with the existing signer, both ARM ABIs, and version 30. The plain 83,067,626-byte APK is under build-out and replaced the stable Google Drive file. Local SHA-256 is `57398d31f017b79a303cde16fea182fee6d5f965f3abc8b8a5a1283b3efc0043`. Drive name, MIME and size were read back.

## 2026-11-12 — Drag greenhouse corners and save the measured footprint (2.9.1)
- Drag a corner or pull a midpoint to add one. Toggle grid snapping, watch the square footage and dimensions update, and save the footprint to Zones. The heat map clips its colors and grid along the saved walls.
- Verified 94 JVM tests, lint, ARM upgrade installation, and a portrait Settings → save → reopen → landscape Zones touchscreen test on Android API 35. A 128 sq ft rectangle became a 96 sq ft snapped polygon and then an 83.15 sq ft free-positioned polygon. Both screenshots show the same final footprint. No physical-device validation.
- Updated the plain APK in build-out and the existing Drive download. Camera, backend, and account services required no release changes.

## 2026-11-11 — Admin portal publication and live camera verification
- Published the 2.9.0 customer management redesign to the existing admin portal. The CloudFront invalidation completed, 30 live routes and assets matched the export, Chrome rendered sign-in, and the protected customer directory API required authentication.
- Two live Android emulator media tests passed using normal ICE and forced TURN. Both tested successive viewing sessions on the same signaling channel. No physical phone or carrier-network validation in this pass.

## 2026-11-10 — Single-phone monitoring, drawn floor plans, simpler device management (2.9.0)
- Add Govee H5179 sensors on an authenticated phone without pairing a server. Standalone storage and provider credentials are independent of paired-server data. Returning to paired mode restores server sync and on-demand video.
- Draw and resize a greenhouse, including triangles and concave outlines. Straighten near-square corners, calculate area and drag sensors into the footprint. Placement by name and tap is also supported. Preserve existing T and rectangle geometry.
- Reduce Settings categories and repeated actions. Admin Customers opens a focused mobile profile, shows named greenhouses, and keeps technical IDs, account recovery, and optional camera policy fields in details.
- Tests passed: 92 Android JVM, seven emulator cases (one additional opt-in live AWS case skipped), 74 backend, 14 portal. Android lint, configured ARM assembly, upgrade-install/signature, portal lint/configured build and mobile/desktop overflow checks passed. Initial gesture test caught scroll interception, fixed and rerun. Physical Govee hardware and carrier streaming were not tested.
- Signed plain APK uploaded in place to Drive. Portal publication awaits renewed AWS credentials; the existing deployed portal has not been updated.

## 2026-11-10 — Reopen a paired camera client (2.8.4)
- Preserve the selected greenhouse across account-device validation. Recover saved literal-null greenhouse IDs using the customer's eligible servers. Sensor sync and camera startup share the restored selection.
- Tag signaling media with the viewing session. Ignore stale offers, answers and ICE from earlier views. Release the viewer lock before the old session DELETE and check for superseded server leases during viewing.
- Confirmed Android's null conversion. An Android negative control without session filtering reproduced loss of the reopened stream. With the fix, both launches decoded frames using real WebRTC and simulated API/signaling, despite an outstanding DELETE and injected stale answer. Timer and preview regressions passed. Android JVM 89/89, lint, install, APK signature, backend 74/74 and portal 14/14 passed.
- Refreshed the signed build-out and existing Drive APK. Physical Wi-Fi and cellular relaunch remain unverified. Live AWS verification needs renewed CLI authentication.

## 2026-11-10 — Client reopen, shared server preview and timed Live View (2.8.3)
- Client refreshes the authorized server snapshot immediately on foreground entry, retries transient errors, then returns to the greenhouse interval. Server validation retains credentials on recoverable failures.
- Tap Live View on Dashboard or Cameras to start. Each request allows 15 seconds from the first decoded frame. Stop, leaving the feed or pausing the app closes the viewer. Continue requests another session. Camera service bounds connection setup and abandoned capture, and the local preview receives the same WebRTC capture while streaming.
- Tested 86 Android JVM cases, lint, configured ARM build, signing and emulator installation. API 35 instrumented tests passed for consecutive local WebRTC streams with preview/decoded frames and for timed controls, background and reopen. The opt-in live AWS test was skipped with the CLI login expired. Physical Galaxy/Pixel reopen, real sensor latency and cellular viewing require verification.
- Updated the existing plain APK on Drive and checked filename, MIME type and size against build-out. No database migration or backend deployment.

## 2026-11-10 — Live AWS signaling repair and deployed administration (2.8.2)
- Reproduced empty Kinesis text frames triggering Android signaling disconnects. Ignore empty frames without weakening malformed-SDP validation; process service control messages without parsing nonexistent media payloads; redact parser exception payloads from logs.
- Restart credential-bound camera, heartbeat, and alert workers after re-pairing changes enrollment, waiting for prior camera cleanup. Added a regression for credential rotation without duplicate workers.
- Added an opt-in disposable AWS/Android media test and relay-only test configuration. The stricter test first failed with repeated `invalid_signaling_message`; it passed after the repair. Both normal ICE and forced AWS TURN delivered decoded emulated-front-camera frames with no disconnect events. Local loopback media also passed. This is not a physical two-phone or carrier-handoff test.
- Renewed deployment access, deployed the session-supersession backend and safe denial diagnostics, and published the modern administrator site with actual deployed configuration. CloudFront invalidation completed; both existing development stacks reached UPDATE_COMPLETE. Administrator version marker is 2.8.2. No unrelated infrastructure or customer data was changed.
- Validation passed: 82 Android JVM tests, Android lint/configured ARM assembly, 74 backend tests, 14 portal tests and lint/live-configured build, CloudFormation lint, stable-signature/package checks, and install over the previous emulator APK. Pre-existing nonfatal D8 metadata warnings remain.
- Delivered plain APK `Greenhouse Monitor 2.8.2.apk` (81,513,583 bytes) to the stable Drive file and verified metadata. SHA-256: `bf6f0b7495273c69c9d2ea781c9e60e4b727700867d3b9b6c7cf7435b5f1aa17`.
- Remaining: install on both physical phones, verify current server authorization/heartbeat, same-Wi-Fi video, cellular/TURN video, handoff, and background lifecycle. Live rejected device credentials and stale server heartbeat were observed; the deployed code and emulator results do not prove the physical server recovered.

## 2026-11-10 — Late-server video recovery and simplified administration (2.8.1)
- Added periodic Viewer offer retransmission with gathered candidates until the Server answers, and blocked disposed CameraX callbacks from reopening local capture during remote handoff.
- Strengthened the Android media test: Server joins 5.5 seconds late and Viewer must decode three nonempty frames. Passed on API 35 ARM emulator; this does not validate AWS signaling or physical-phone networks.
- Simplified administration with a mobile top menu, customer cards, focused overview metrics, expandable account/security/diagnostic details, and clear Enable free access / Disable access controls using the existing authenticated backend.
- Validation: Android unit tests, lint, configured ARM assembly, 74 backend tests, 14 portal tests, ESLint, TypeScript/static build, signature/package checks, and diff checks passed. Rendered synthetic customer layout in Chrome at 390px and confirmed no horizontal overflow. Existing nonfatal D8 metadata warnings remain.
- Delivered the plain signed 2.8.1/code-24 APK to the existing Drive file and verified 81,497,199 bytes. SHA-256: `448f96025b259d11006205d2e19fe332637824d3427c6c3e981bc4d02042ff84`.
- Blocker: AWS credentials remain expired. The prior backend fix and new portal are not live; owner sign-in, deployment, live Kinesis diagnosis, and physical LAN/cellular tests remain outstanding. No claim of restored customer streaming is made.

## 2026-11-09 — Stream-session supersession and automatic Server camera (Android 2.8.0)

### Fixed
- A new Viewer request now ends older abandoned stream sessions for the same greenhouse before it is published. The greenhouse Server validates pending-session records, chooses the newest request by creation time, and removes stale queue pointers instead of answering an unrelated UUID while the current Client reconnects forever.
- The paired backend device role is now authoritative for starting the greenhouse heartbeat and on-demand camera services. A greenhouse Server no longer depends on a separate local Camera Mode preference after QR pairing.
- The Server checks its renewable authorization lease every ten seconds, so a superseded or revoked session releases the camera promptly. WebRTC now uses the AWS sample's bundle, ECDSA, RTCP-mux, and TCP-candidate policies and emits sanitized signaling/ICE diagnostics.

### Validation
- Passed 79 Android JVM tests, Android lint, debug assembly, and a new Android instrumentation test on the API 35 ARM emulator that opened the emulated front camera, connected Master and Viewer peers, and delivered a remote `VideoTrack`.
- Passed all 74 backend tests, including new regressions for superseding an abandoned session and selecting the newest pending session. CloudFormation lint, account-aware SAM validation, and SAM build passed.
- GitHub Actions run `42751121672` passed both the full verification and configured ARM APK jobs for implementation commit `60eb363`.
- Built and verified `build-out/Greenhouse Monitor 2.8.0.apk` as an API 29+ ARM32/ARM64 v2-signed APK (81,497,199 bytes; SHA-256 `20b695e8b41886187f7c40d7f231c91a217507ba290afb68768835620d9ec819`; certificate SHA-256 `b94e4b6a71111e3e0f79c53d2a027baeabcfe148444f752ade85e71afa0f92eb`). Drive file `19NBhnl5JRIscX6MaR5q7nFPM_a2plNXR` was replaced in place with the plain APK, and metadata readback matched the filename, Android-package MIME type, and byte size.

### Remaining deployment and physical validation
- The development backend update and cloud-configured APK are pending a renewed owner AWS session. The saved IAM credentials were rejected; the AWS root-user password must be entered by the owner in the open sign-in tab.
- Real Galaxy front-camera frames, same-Wi-Fi direct ICE, cellular/TURN relay, and Wi-Fi/cellular handoff remain physical two-phone tests and are not claimed by the emulator test.

## 2026-11-09 — WebRTC media negotiation repair (Android 2.7.9)

### Fixed
- Corrected Kinesis single-master signaling so Viewer offers and ICE messages omit `recipientClientId`; the previous empty string was invalid for the Viewer role and could prevent signaling from reaching the greenhouse Server.
- Added the required receive-only video transceiver before the Viewer creates its offer. The offer now contains the video media section that the Server needs to return its front-camera track.
- Buffered remote trickle-ICE candidates until remote SDP is applied instead of passing early candidates to libwebrtc before a usable transport exists.
- Kept the greenhouse camera service registered as a camera foreground service from its visible app startup, allowing an authorized on-demand session to open the camera later under modern Android background restrictions. The camera hardware remains closed while no one is watching.
- Added a 30-second connection-attempt timeout, bounded reconnect behavior, signaling-status decoding, and sanitized Server log diagnostics instead of allowing a failed attempt to wait indefinitely.

### Validation
- Passed 79 Android JVM tests, including new regressions for Viewer recipient omission, receive-only video negotiation, status responses, and early ICE buffering; Android lint and debug assembly also passed.
- Passed all 72 backend tests. Installed and cold-launched the cloud-configured universal build on the Android emulator with no app fatal exception.
- Built Android 2.7.9/code 22 as an API 29+ ARM32/ARM64 v2-signed APK: 81,497,199 bytes, SHA-256 `fb9f4fec1591a9419f25c5a871f39b16d7e44e133206e2f092e51d67d551a3e7`, certificate SHA-256 `b94e4b6a71111e3e0f79c53d2a027baeabcfe148444f752ade85e71afa0f92eb`.
- Implementation commit `6dc3075` is pushed; GitHub Actions run `42745606532` passed the full verification and configured ARM APK jobs. Replaced Drive file `19NBhnl5JRIscX6MaR5q7nFPM_a2plNXR` in place and verified the 2.7.9 filename, Android-package MIME type, and 81,497,199-byte remote size.

### Remaining physical validation
- The corrected APK must be installed on both the greenhouse Server and Client phones. Actual Galaxy front-camera frames, same-Wi-Fi direct ICE, cellular/TURN relay, and Wi-Fi/cellular handoff cannot be claimed until the two physical phones run this build.

## 2026-11-09 — Portrait Settings hamburger and unclipped controls (Android 2.7.8)

### Changed
- Replaced the portrait Settings category strip with a fixed top hamburger bar and an eight-item menu. The selected section remains identified in the bar, and every category is reachable without horizontal scrolling.
- Narrow Settings cards now wrap general row content instead of hiding it beyond a horizontal viewport. Active Greenhouse and Greenhouse Layout use vertically stacked, full-width actions; Outdoor Weather uses a full-width ZIP field plus equal-width Save ZIP and Refresh actions.
- Bumped Android to 2.7.8/code 21 and updated the administrator current-version marker.

### Validation
- Passed all 75 Android JVM tests with zero failures/errors/skips, Android lint, configured ARM32/ARM64 assembly, APK package/version inspection, v2 signature verification, and `git diff --check`.
- Installed the development build on the 1080 × 2270 Android emulator. Verified the portrait hamburger and all eight menu entries, switched to Connectivity through the menu, scrolled General Settings, and confirmed UI bounds for Edit Layout, the four interval actions, Save ZIP, and Refresh remain inside the visible content area. Screenshots are under `deliverables/settings-hamburger-v2.7.8/` and are not committed.
- Configured ARM APK: 81,480,815 bytes; SHA-256 `aff53ee7ac9b803f2c826150c7c46010177a46fe9e10bc53cb926024245132a6`; signing certificate SHA-256 `b94e4b6a71111e3e0f79c53d2a027baeabcfe148444f752ade85e71afa0f92eb`.
- Implementation commit `994c8f2` is pushed. Replaced Drive file `19NBhnl5JRIscX6MaR5q7nFPM_a2plNXR` in place and verified the remote 2.7.8 filename, Android-package MIME type, and 81,480,815-byte size.

### Remaining physical validation
- Actual Galaxy A71 and Moto G7 font scaling/touch ergonomics remain a physical-device check; this entry claims emulator layout validation only.

## 2026-11-09 — Full-system verification and Android 2.7.7

### Fixed
- Added the missing Kinesis signaling-channel tag permission so live WebRTC authorization can create an account-scoped channel and return signed signaling plus managed ICE/TURN endpoints.
- Made the portrait Settings refresh selector and device actions fully readable. All four refresh intervals remain visible; narrow device cards stack complete action labels; a disconnected greenhouse Server can be revoked directly.
- Filled missing administrator customer presentation fields from the matching Cognito identity so completed customer accounts show their email instead of `Unnamed account` while retaining canonical account and Server relationships.

### Deployment and validation
- Deployed the application and administrator stacks successfully. Live pairing, approval, disconnect/reconnect, revoke/re-pair, rotated-credential enforcement, heartbeat, stream authorization/pickup/end, and customer hierarchy checks passed. Disposable test records and the test signaling channel were removed; live administration again returns only `notify@junecity.com`, with one greenhouse and its preserved revoked Server.
- Passed 75 Android JVM tests plus lint/configured ARM assembly, 72 backend tests, 12 portal tests plus ESLint/TypeScript/production build, zero npm audit findings, both CloudFormation lints, live 401 authorization-boundary checks, portal HTTP checks, and a CloudWatch error scan.
- Installed and relaunched the final APK on two ARM Android emulators. Verified new-account Primary Client behavior, additional-client sign-in, persisted authentication, pre-auth built-in scanner, all five app screens, every Settings category, server lifecycle controls, empty sensor/history states, and the proportional 256 sq ft T-shaped editor. No fatal Android exception was observed.
- Bumped Android to 2.7.7/code 20. The configured API 29+ ARM32/ARM64 APK is 81,448,043 bytes with SHA-256 `5564d7858a2ae1ecc8c1e23512e04d8ba011c9c8f145fd6d693cb3adb9af854c` and v2 signing certificate SHA-256 `b94e4b6a71111e3e0f79c53d2a027baeabcfe148444f752ade85e71afa0f92eb`.
- Implementation commit `4e39d71` is pushed; GitHub Actions run `42616731802` passed both jobs. Replaced Drive file `19NBhnl5JRIscX6MaR5q7nFPM_a2plNXR` in place with the plain APK and verified the remote filename, MIME type, and 81,448,043-byte size.

### Remaining physical validation
- Actual Govee H5179 readings require the owner's Govee credential and sensors. Real front-camera frames, direct same-Wi-Fi media, and off-Wi-Fi cellular/TURN handoff require the Galaxy A71 and client phone on their target networks. Those hardware results are not claimed by the emulator/backend tests.

## 2026-11-09 — Canonical customer ownership and admin directory repair

### Fixed
- Customer lists and dashboard totals now require a current Cognito identity linked to a completed backend account. Stale deleted-identity account shells and incomplete registrations are excluded instead of appearing as separate customers.
- Existing greenhouse ownership was atomically reconciled to `notify@junecity.com`; the account, primary Client, Greenhouse 1, revoked Server, configuration, history, and alerts were preserved. Bootstrap now rejects a stale user mapping when the authoritative account belongs to a different Cognito subject.
- Customer rows and profiles fall back to the customer email rather than `Unnamed account` when no display name is available.

### Deployment and validation
- Deployed both updated Lambdas and the static portal. Live smoke verification returned one customer, one greenhouse, and one nested revoked Server; dashboard `totalCustomers` is 1. No records were deleted.
- Passed 71 backend tests and compilation, 12 portal tests, ESLint, production build, and `git diff --check`. Physical device behavior remains unverified.
- Implementation commit `030bfa0` is pushed; GitHub Actions run `42602543507` passed both jobs. Delivered stable-signed ARM APK `Greenhouse Monitor 2.7.6.apk` (81,479,396 bytes; SHA-256 `97eba0e36aa636b5bc7cc1f40a03bdbfaba6928f28282ec8c6f2d9fbc1310433`; certificate SHA-256 `b94e4b6a71111e3e0f79c53d2a027baeabcfe148444f752ade85e71afa0f92eb`) by replacing Drive file `19NBhnl5JRIscX6MaR5q7nFPM_a2plNXR` in place; metadata readback matched.

## 2026-11-09 — Revoked-server re-pairing, portrait Settings, and customer reconciliation

### Fixed
- Fixed the revoke → fresh QR → approve workflow for the same greenhouse phone. Approval now verifies that an existing device belongs to the same customer, rotates its device secret, explicitly reauthorizes it, clears revocation/replacement markers, and preserves the greenhouse, configuration, sensor assignments, and history.
- The administrator customer directory and dashboard no longer count anonymous legacy setup shells as customers. Only Cognito-linked customer accounts and confirmed pending identities appear; greenhouse servers remain inventory beneath their owning customer.

### Changed
- Settings now rotates to portrait independently while Dashboard, Zones, History, and Cameras stay landscape. Portrait Settings uses a single-column scrollable layout, horizontal category selector, stacked Connectivity/server controls, responsive greenhouse designer, larger typography, and compact icon-over-label bottom navigation.
- Bumped Android to 2.7.6 (version code 19) and the configured ARM artifact name/version marker.

### Testing
- Passed 75 Android JVM tests with zero failures/errors, Android lint, debug assembly, 69 Python backend tests, Python compilation, 12 administrator portal tests, ESLint, and production build. `git diff --check` passed. The pre-existing non-fatal D8 Kotlin metadata warning remains.
- Added regression coverage for same-device server reauthorization, portrait Settings orientation, anonymous legacy account exclusion, and customer metric reconciliation.
- Physical portrait review on the Galaxy A71/Moto G7, same-Wi-Fi direct WebRTC, off-Wi-Fi TURN, and two-phone revoke/re-pair remain target-device tests and are not claimed as verified here.

## 2026-11-09 — Readable server controls and automatic camera authorization

### Fixed
- Replaced the compressed, unlabeled Connectivity action row with full-width 54-dp controls for Replace Server, Disconnect Server, and an always-visible red Revoke Server action. Disconnect and revoke confirmations now use readable full-width buttons.
- Increased the Connectivity heading, synchronization copy, server status, descriptive copy, action labels, and result-message typography for landscape phones.
- Removed the obsolete second camera QR workflow from cloud-enrolled Client and Server phones. The initial approved greenhouse-server pairing now establishes the device relationship used by authenticated on-demand WebRTC camera sessions; the Camera settings screen shows the assigned role and automatic connection state instead of a camera QR.
- Cloud-enrolled Server phones no longer start the legacy JPEG/QR camera server. They keep the camera closed until the authorized cloud camera service receives a Client Live View request. The legacy local camera QR remains available only in deliberately unconfigured/local builds.

### Changed
- Bumped Android to 2.7.5 (version code 18) and updated the configured ARM artifact name.

### Testing
- Passed 74 Android JVM tests with zero failures/errors, Android lint, debug assembly, and `git diff --check`. The pre-existing non-fatal D8 Kotlin metadata warning remains.
- Built `build-out/Greenhouse Monitor 2.7.5.apk`: 81,382,511 bytes, SHA-256 `23133f7acac351e252270ac400425e476cdebc5558e7eae35338c3aa73f61f71`, API 29 minimum, ARM32/ARM64 only, and v2 signed with the persisted certificate SHA-256 `b94e4b6a71111e3e0f79c53d2a027baeabcfe148444f752ade85e71afa0f92eb`. The deployed non-secret API, customer-pool, and app-client identifiers were present in the APK.
- Implementation commit `de9c34d` is pushed. GitHub Actions run `42595970553` passed the full verification and configured ARM APK jobs. Google Drive file `19NBhnl5JRIscX6MaR5q7nFPM_a2plNXR` was replaced in place; metadata readback confirmed the 2.7.5 filename, Android package MIME type, and 81,382,511-byte size.
- Same-Wi-Fi peer-to-peer WebRTC and off-Wi-Fi TURN behavior remain implemented but are not claimed as physically verified on the Galaxy A71/Moto G7/second phone in this checkpoint.

## 2026-11-08 — Server reconnect, adjustable synchronization, and customer grouping

### Added
- Added Connectivity controls on authenticated Clients to disconnect the current greenhouse Server, preserve its greenhouse data, reconnect it with a fresh approved QR code, or replace it without deleting the greenhouse.
- Added Server-managed sensor/data intervals of 1, 2, 5, 10, or 30 minutes. The selected interval controls Govee reads and greenhouse backup publication; Clients perform lightweight 30-second checks, while the safety heartbeat remains fixed at one minute.

### Changed
- The administrator customer detail now presents one primary customer and groups every greenhouse Server beneath that account. General device rows are limited to authorized Client phones, and a revoked Server is reported as disconnected even when its greenhouse record remains preserved.
- Remote-camera denial copy now says access is not enabled for the account. The existing Owner-only per-customer test switch remains the backend-authoritative no-payment camera control; verified paid subscriptions remain authoritative when present.
- Bumped Android to 2.7.3 (version code 16). The approved monitoring design remains intact except for the explicitly requested interval and connection controls.

### Deployment and testing
- Deployed the updated administrator Lambda and version marker to `greenhouse-monitor-admin-dev`, published the live-configured portal, and started CloudFront invalidation `I6HPXCRXXC00T31LK83ZREOJMA`.
- Passed 72 Android JVM tests with zero failures/errors, Android lint and debug assembly, 61 Python backend tests and compilation, 12 administrator portal tests, ESLint, production build, and zero-vulnerability npm audit.
- Built `build-out/Greenhouse Monitor 2.7.3.apk`: 81,333,355 bytes, SHA-256 `7b21107844e09e595035659031be1947b36263f6a6d03c256775b51aaefb9b8a`, API 29 minimum, ARM32/ARM64 only, v2 signed with the persisted certificate SHA-256 `b94e4b6a71111e3e0f79c53d2a027baeabcfe148444f752ade85e71afa0f92eb`, and containing only the deployed non-secret cloud configuration. Physical disconnect/reconnect, selected-interval timing, Govee reads, and remote WebRTC video remain unverified on the target phones.
- GitHub Actions run `42552326235` passed the complete verification and configured ARM jobs for implementation commit `7042978`. Google Drive file `19NBhnl5JRIscX6MaR5q7nFPM_a2plNXR` was replaced in place; metadata readback confirmed `Greenhouse Monitor 2.7.3.apk`, the Android package MIME type, and 81,333,355 bytes.

## 2026-11-08 — Server-authoritative monitoring sync and camera test control

### Added
- Added a persisted current-sensor snapshot to the greenhouse backup contract and automatic client synchronization of server preferences, greenhouse geometry, sensor identities/readings/status, zone assignments, alert preferences, and history.
- Added server/replacement authorization tests for schema-2 backups plus client read-only enforcement.

### Changed
- New Viewer Client enrollments now begin with no Govee credentials, sample sensors, local history, zone assignments, or inherited greenhouse configuration. Clients wait for an approved greenhouse server and render its synchronized data; Govee setup and zone assignment remain available only on the Server phone.
- Replacement Servers can restore the stable greenhouse backup while preserving their local Server camera role. Repeated client synchronization replaces imported history instead of duplicating rows.
- The administrator customer detail screen now labels the existing backend-authoritative complimentary entitlement as a per-customer remote-camera test control, with explicit on/off actions and optional expiration. Verified Google Play entitlement remains authoritative for paid access.
- Bumped Android to 2.7.2 (version code 15) and the configured ARM artifact name accordingly. The approved five monitoring screens were not redesigned.

### Deployment and testing
- Deployed the reviewed `greenhouse-monitor-dev` change set in `us-east-1`; CloudFormation completed `UPDATE_COMPLETE` without replacements. Published the live-configured administrator static build and completed its CloudFront invalidation. The public application API still returns 401 without authorization and the portal returns 200 with its configured CSP.
- Passed 70 Android JVM tests, Android lint, configured ARM debug assembly, 60 Python backend tests and Python 3.13 compilation, 12 administrator portal tests, ESLint, static production build, zero-vulnerability npm audit, all CloudFormation lints, SAM validation, and SAM build.
- Local APK verification passed for package `studio.junecity.greenhouse`, version 2.7.2/code 15, API 29 minimum, `armeabi-v7a` plus `arm64-v8a`, v2 signature, and the persisted debug certificate SHA-256 `b94e4b6a71111e3e0f79c53d2a027baeabcfe148444f752ade85e71afa0f92eb`. Physical client/server synchronization, live Govee readings, QR approval, and remote WebRTC remain unverified on the target phones.
- GitHub Actions run `42548402194` passed both the full verification job and configured ARM artifact job for implementation commit `b7a71f1`. The installable APK is 81,300,591 bytes with SHA-256 `bd87738960b92457a804c158848fa63e3e9eb34ca5ca77dd1b1b463454e85150`; the existing Google Drive file was replaced in place and metadata readback confirmed the 2.7.2 filename, MIME type, and exact byte count.

## 2026-11-08 — Client-displayed QR server pairing

### Changed
- The authenticated client now displays a temporary server-pairing QR automatically in Settings → Connectivity instead of requiring the customer to discover Devices → Add Server first.
- The Connectivity panel shows the five-minute expiry, refreshes unused expired codes, and changes to an explicit approval action after the greenhouse phone scans it.
- Fresh-install server onboarding now directs the customer back to Settings → Connectivity for approval.
- Bumped the Android application to 2.7.1 (version code 14).

### Fixed
- Reopening Connectivity restores an existing pending server request before generating another invitation, so navigating away cannot strand the scanning server phone.
- Kept first-login automatic Client registration, passwordless Server scanning, encrypted role persistence, the full Devices management screen, and all existing Govee/camera/history behavior intact.

### Testing
- `git diff --check` passed. GitHub Actions run `42543388030` passed Android JVM tests, lint, universal debug assembly, 57 backend tests, Python compilation, 12 admin-portal tests, portal lint/production build/dependency audit, AWS template lint, checksum generation, and configured ARM assembly/ABI verification.
- The final ARM APK is version 2.7.1/code 14, contains `armeabi-v7a` and `arm64-v8a`, and is signed by the same persisted project certificate as prior installable builds. `apksigner` verified the v3 signature and certificate SHA-256 `b94e4b6a71111e3e0f79c53d2a027baeabcfe148444f752ade85e71afa0f92eb`. Final size: 81,348,324 bytes; SHA-256: `f07211a0c2cdc41940682ad70c547f4f8a6c48557f16e51af51186a9c332dbad`.
- The existing Google Drive APK was replaced in place and metadata readback confirmed its 2.7.1 filename and exact size. Physical two-phone scanning, client approval, reboot persistence, and Govee regression remain unverified on the Galaxy A71/Moto G7/Pixel hardware.

## 2026-11-08 — Direct-install ARM APK build option

- Added an opt-in `GREENHOUSE_ARM_ONLY` Android build property that packages both 32-bit and 64-bit ARM native libraries while omitting unused Intel ABIs. The application code, frozen interface, cloud configuration, and Android API support are unchanged.
- Added a manual GitHub Actions input plus a separately verified configured ARM artifact on upgrade-branch pushes. Normal push and pull-request verification remains a universal build; cloud identifiers are supplied through non-secret repository variables rather than committed values.
- Intended delivery target: a direct `.apk` under Google Drive's connector-size limit for the ARM-based Galaxy A71, Pixel 10, and Moto G7 families.
- Fixed delivery signing after the first ARM artifact used GitHub's ephemeral debug certificate, which Android could not install over prior builds. Re-signed the unchanged ARM application with the persisted project debug keystore used by 2.6.3, 2.6.4, and the universal 2.7.0 APK, then replaced the existing Drive APK and checksum in place.
- Validation: GitHub Actions run `42539646383` built the configured artifact, verified `armeabi-v7a` and `arm64-v8a`, rejected Intel libraries, and passed the full verification job. Local `apksigner` verification passed with v3 signature and certificate SHA-256 `b94e4b6a71111e3e0f79c53d2a027baeabcfe148444f752ade85e71afa0f92eb`, matching the prior deliverables. Package `studio.junecity.greenhouse`, version 2.7.0/code 13, API 29 minimum, both ARM ABIs, all four deployed non-secret cloud values, checksum, and Drive size/readback were verified. Final raw APK: 81,299,172 bytes; SHA-256 `76031b14732cd085d3ce9c70375795e0e6b88b16d9367373a082cc0dada56e3d`. Physical installation remains unverified until the owner retries it.

## 2026-11-08 — Automatic clients and approved QR server pairing (Android 2.7.0)

- Removed post-login Client/Server selection. A newly authenticated phone registers as a Viewer Client automatically, later signed-in phones join the same account as clients, and securely persisted enrollments are validated before monitoring opens.
- Added passwordless greenhouse-server enrollment from the fresh-install screen: the server scans a client-created five-minute QR invitation, generates a device-bound secret locally, waits for explicit client approval, and stores the approved role in Android Keystore-backed encrypted storage. Device-secret routes are isolated from the Cognito-authorized client routes and enforce account/device revocation server-side.
- Added Settings → Devices management for registered clients and greenhouse servers, live/offline state, pending approval, Add Server, Replace Server, Remove Server, and greenhouse selection. Multiple greenhouse servers are account-scoped and tenant-isolated.
- Added replacement continuity backup for greenhouse geometry/preferences, notification settings, sensor-to-greenhouse and zone assignments, and the latest 1,500 history readings. Backups remain attached to the stable greenhouse ID; Govee credentials are intentionally not copied.
- Deployed the reviewed application change set to `greenhouse-monitor-dev`; it completed without resource replacement and drift detection reports `IN_SYNC`. The admin stack's current Android version marker is 2.7.0. A disposable live SRP customer verified automatic client creation, two independent greenhouse servers, three approval cycles, server replacement, device-secret restoration, and backup restoration; every disposable Cognito and DynamoDB record was deleted afterward.
- Validation: 68 Android JVM tests passed with zero failures/errors; Android lint, local/configured assembly, APK checksum and v2 signature verification passed; a clean emulator cold-launched the portrait onboarding and opened the built-in QR scanner. 57 backend tests, Python 3.13 compilation, CloudFormation lint, SAM validation/build, deployment smoke checks, and authorization-boundary checks passed. Configured APK SHA-256: `38c4d7148509acc1626776f8ab5dea74a8ed94decf2c9e522b73f1a5be5b7961`.
- Limitations: QR capture/approval, persistence after reboot, Govee regression, replacement restore, and layout behavior still need final verification on two physical phones. Expiration is automated-test verified rather than delayed five minutes in the live stack. The APK is debug-signed; Play/release signing remains external.

## 2026-11-08 — Administrator pending-customer visibility

- Fixed the administrator dashboard and customer directory so confirmed customer registrations are visible before Android onboarding creates their account, tenant, and Free-entitlement records. Pending registrations are labeled `PENDING_SETUP` and are not misreported as active or subscribed customers.
- Added a read-only pending-customer detail state and hid account/entitlement mutations until setup completes. Existing DynamoDB account records remain authoritative after bootstrap.
- Granted the administrator API least-privilege `cognito-idp:ListUsers` access to the customer pool and deployed the updated Lambda, IAM policy, static portal, and CloudFront invalidation to the development environment.
- Validation: 50 Python tests and Python compilation passed; 12 portal tests, ESLint, and the production static build passed; SAM validation and CloudFormation lint passed. Live Lambda checks returned two registered customers, both pending setup, with Total customers 2 and Active customers 0. The deployed portal asset contains the pending-setup state and the CloudFront invalidation completed.
- Limitation: a pending customer becomes Active/Free only after a successful app sign-in and role-selection bootstrap; no customer credentials were accessed during validation.

## 2026-11-08 — Customer stale-session recovery and Android 2.6.4

- Fixed explicit customer sign-in so it clears any persisted or partial Amplify session before starting a fresh SRP authentication for the entered email. This repairs the state left when identity authentication succeeded before the original account-bootstrap outage was corrected.
- Expanded customer-safe authentication error handling to inspect nested causes and recovery suggestions and give actionable guidance for stale sessions, unconfirmed accounts, credential failures, throttling, and connectivity failures instead of hiding them behind a generic message.
- Released the cloud-configured Android debug build as 2.6.4 (version code 12), SHA-256 `9c67c24daf8041d480877758f0d036738617bc336d0ad41fd0641f7f6afee0ae`, and updated the deployed admin stack's current-version marker to 2.6.4.
- Live validation: the real customer's password-reset event is confirmed successful while no sign-in event reached the identity service from the affected build, matching a local stale-session failure. A clean Android emulator installed 2.6.4, performed a real SRP sign-in with a disposable confirmed customer, received a live account/tenant bootstrap, and advanced to phone-role selection. The disposable identity and database records were deleted after verification.
- Automated validation: 63 JVM tests passed with zero failures/errors; Android lint and cloud-configured debug assembly passed; package/version inspection passed. A non-fatal pre-existing D8 Kotlin metadata warning remains. The owner's physical-phone sign-in is not claimed because private credentials were not accessed.

## 2026-11-08 — Live customer bootstrap and admin data loading repair

- Fixed deployed customer and administrator route matching by normalizing the API Gateway stage prefix before dispatch. The prior `/dev` prefix caused valid customer bootstrap and administrator data routes to fall through.
- Fixed all DynamoDB resource-client transactions to use native Python values instead of double-serialized AttributeValue maps, and added explicit `dynamodb:TransactWriteItems` permissions to the application and heartbeat roles. This restores atomic customer account/tenant/Free-entitlement creation and the related device, pairing, heartbeat, admin-revocation, and offline-monitor transactions.
- Fixed the administrator portal's browser preflight by allowing Chromium `cache-control`/`pragma` request headers and adding an unauthenticated 204 OPTIONS route while retaining JWT authorization on every data route.
- Added safe dependency-error diagnostics containing only route/AWS error codes and transaction cancellation reasons; no customer data, credentials, or tokens are logged.
- Deployed both repaired stacks to the existing development environment. Live verification passed: both stacks are `UPDATE_COMPLETE`; a disposable Cognito SRP customer received HTTP 201 from `/dev/v1/bootstrap` and all test records were cleaned up; admin CORS preflight returned 204 with the expected origin/method/header policy; and the deployed `/dev/admin/v1/session` route returned 200 in a role-claim Lambda smoke test.
- Validation: 48 Python tests passed; both infrastructure templates passed `cfn-lint`; SAM validation/build passed for the updated admin template. A full administrator browser retry and the existing customer's authenticated phone retry still require the owner's private credentials/session.

## 2026-11-08 — Administrator callback and customer password recovery

- Fixed a live administrator-login race: Cognito successfully authenticated the Owner, but the callback component could consume the same one-time authorization code twice when context initialization rerendered. The callback now depends only on the stable completion function, with a rerender regression test, and the corrected static portal is deployed through CloudFront.
- Added an in-app customer “Forgot password?” flow that requests an emailed code, validates a new password against the deployed policy, confirms the reset, and returns to sign-in. Existing-account and invalid-credential errors now point customers to recovery instead of account recreation.
- Released Android 2.6.3 (version code 11) and updated the live admin backend's current-version marker. Cloud customer and admin identities remain intentionally isolated; matching email addresses do not share passwords.
- Validation: 61 Android JVM tests passed with zero failures/errors; Android lint and debug assembly passed; 12 portal tests, ESLint, and the live-configured Next.js build passed. The cloud-configured APK is v2 signed and has SHA-256 `17fb83725808c858d9833021648086c058bd45721827c184d02534121cecfc88`.
- Live checks confirmed the prior administrator authentication succeeded and the failure occurred before any authenticated API request. The physical Android reset-code round trip and the corrected portal's authenticated dashboard still require the account owner to retry with private credentials.

## 2026-11-08 — Private administration portal development deployment

- Deployed the reviewed application-table update, restricted bootstrap-role update, and dedicated `greenhouse-monitor-admin-dev` stack in `us-east-1`; all stacks completed successfully without replacing application data resources.
- Published the static portal through private encrypted S3 and CloudFront, configured the live API/Cognito callback origins, and created one enabled Owner account with mandatory first-password change and TOTP MFA. No password or personal address was committed.
- Fixed the managed CloudFront cache-policy identifier, least-privilege deployment permissions, Next.js static hydration CSP, browser-storage fallback, bounded session initialization, and Cognito discovery CORS incompatibility using explicit authorization-code + PKCE endpoints.
- Normalized the API Gateway access-log destination ARN so CloudFormation drift detection compares the exact stored ARN instead of the log-group wildcard form.
- The admin GSI is active. Its migration dry run reported zero eligible records, so no data backfill write was needed. Failed pre-creation stack attempts were rolled back only after retained resources were verified empty; no customer records were removed.
- Live validation: portal 200 over HTTPS with HSTS/CSP/frame/nosniff/no-store headers and AES256 origin storage; sign-in redirected to the dedicated Cognito hosted login with PKCE; anonymous API access returned 401; direct Owner-role Lambda dashboard smoke returned 200 with actual empty-state metrics, Cost Explorer status `AVAILABLE`, and healthy alarm data; the existing application Lambda rejected an unauthenticated smoke event with 403 and no import error.
- Automated validation: 60 Android JVM tests with zero failures/errors, Android lint/debug assembly, 45 Python tests, Python 3.13 compilation, 11 portal tests, ESLint, production static build, zero-vulnerability npm audit, all three CloudFormation lints, and SAM account-aware validation/build passed. Final drift checks report all three deployed stacks `IN_SYNC`; the pushed CI result is recorded with the deployment commit after completion.
- Remaining external gates: the Owner must use the emailed temporary password to set a permanent password and enroll TOTP before authenticated browser actions can be tested; Google Play credentials/billing and physical A71/Pixel streaming/background/network tests remain unconfigured or unverified.

## 2026-11-08 — Private administration portal

- Added a separate responsive Next.js/TypeScript administrator portal with real-data overview charts, customer search/detail, device health, subscription management, streaming operations metadata, cost visibility, audit history, support tools, and explicit loading/empty/error states. The approved Android monitoring screens were not modified.
- Added a dedicated Cognito administrator architecture with admin-created users only, mandatory TOTP MFA, short sessions, Owner/Support/ReadOnly roles, backend permission enforcement, API throttling, secure headers, retained encrypted private hosting, login/mutation audit events, and no administrator access to customer video or secrets.
- Added backend customer suspension/reactivation, account recovery, complimentary Premium grant/revoke with optional expiry, device-session revocation, and backend-only Google Play purchase verification. Google Play remains authoritative for Play purchases, and purchase tokens are never returned to clients.
- Added a DynamoDB admin GSI projection, cost-allocation tags, a dry-run-first migration script, dedicated approval-gated admin infrastructure, deployment/cost/rollback documentation, and CI coverage for portal, backend, dependency audit, and both infrastructure templates.
- Validation: 45 Python tests passed; Python 3.13 compilation passed; 11 portal tests, ESLint, Next.js production static build, and `npm audit --audit-level=moderate` passed with zero vulnerabilities; `cfn-lint` passed both templates; the Android regression suite passed 60 JVM tests with zero failures/errors plus lint and debug assembly. The Android build emitted a non-fatal D8 Kotlin-metadata warning. `sam validate`/`sam build` were not run because SAM CLI is unavailable.
- Limitations: no admin resources, DNS, production billing, Play credential, or data migration were deployed. Cognito/TOTP, CloudFront/S3, live JWT authorization, Cost Explorer, Google Play, responsive physical-browser, and existing-record migration remain unverified against live services.

## 2026-11-08 — junecity.studio portrait account onboarding

- Changed account creation, email verification, role selection, and pairing to a portrait-first responsive flow; the five approved monitoring screens still switch to and remain in sensor landscape.
- Replaced customer-facing AWS/Cognito references with junecity.studio product language across onboarding, camera status, live view, foreground alert text, and the launcher label. Internal service identifiers and architecture remain unchanged.
- Added local validation for the deployed 12-character password policy and translated raw identity-provider failures into actionable account, password, verification-code, and network guidance.
- Bumped the Android test build to version 2.6.2 (version code 10).
- Validation: 60 JVM tests passed with zero failures/errors; Android lint and debug assembly passed; the configured build cold-launched on the emulator at 1080×2270 portrait; the local monitoring build switched to 2270×1080 landscape. Configured APK SHA-256: `e775b98fb0ff377e85c859593132bfb85e32a79d185226d2c4dbf7d95dfd53ba`. Physical Galaxy/Moto/Pixel typography, account creation, email delivery, and post-confirmation rotation still require device testing.

## 2026-11-08 — AWS development deployment

- Deployed the dedicated `greenhouse-monitor-deployment-bootstrap` and `greenhouse-monitor-dev` CloudFormation stacks in `us-east-1`; no junecity.studio resources were touched.
- Added a private encrypted artifact bucket, restricted deployment caller and CloudFormation service roles, Cognito Plus with active deletion protection, API Gateway, Lambda, DynamoDB, Scheduler, SNS, SQS, logs, and alarms.
- Added configurable Cognito deletion protection so failed first-time development deployments can roll back safely before protection is enabled, plus the live-required Cognito, API Gateway tag, and CloudWatch log-delivery permissions.
- Extended the AWS APK helper with an optional profile argument and added bootstrap-template linting to CI.
- Recovered four pre-activation least-privilege failures without customer data: missing SAM transform, Cognito MFA, API Gateway tagging, and log-delivery permissions were corrected; partial pools/tables/log groups were verified empty and removed before clean redeployment.
- Live AWS validation: stack `CREATE_COMPLETE`; Cognito Plus deletion protection active; unauthenticated API returned 401; DynamoDB encryption/PITR/TTL enabled; Lambdas active on Python 3.13 arm64; one-minute schedule enabled; heartbeat monitor invoked successfully with zero Lambda errors; both stacks report `IN_SYNC` drift status.
- Android/backend validation: 57 JVM tests, lint, configured assembly, 29 Python tests, Python 3.13 compilation, SAM validate/build, both CloudFormation lints, APK checksum/signature/config/key scan, and emulator cold launch passed. Configured APK SHA-256: `58544165e4fb0c4c389c72e6bc6e2ef389a78d369a9fecf6a0e2fcb9e675c585`.
- Remaining: create/confirm the owner Cognito account before complimentary entitlement can be granted; add an approved operations-topic destination; verify onboarding, pairing, direct/TURN video, handoff, alerts, reboot, and network transitions on physical phones; configure release signing and Play subscriptions if commercial billing is enabled.

## 2026-11-08 — AWS-configured APK build handoff

- Added a credential-safe build helper that reads the deployed stack's non-secret API/Cognito outputs through the operator's AWS CLI session and supplies all four required Android build values together.
- Added an AWS Region stack output and an ignored `build-out/` destination containing the configured debug APK and SHA-256 checksum.
- Documented that release signing still requires the owner-controlled keystore and Play configuration.
- Validation: shell syntax and fail-closed argument/dependency handling passed; `cfn-lint` passed. Live output retrieval and a configured build remain deployment-gated because no AWS stack or AWS CLI identity is available.

## 2026-11-08 — Current AWS architecture documentation

- Replaced the README's obsolete public-IP/UPnP remote-camera instructions with the implemented Cognito, one-use cloud pairing, Premium authorization, Kinesis WebRTC, direct ICE/TURN fallback, and on-demand lifecycle.
- Documented all-or-nothing AWS build outputs, the AWS-only alert inbox/polling path, complimentary IAM administration, and the distinction between the LAN rollback build and the undeployed AWS configuration.
- Updated the remote-streaming contract from “scaffold only” to the accurate connected/build-tested state while retaining explicit deployment, Play Billing, release-signing, and physical-device limitations.
- Validation: documentation was reconciled against current Android, backend, infrastructure, and CI behavior; no runtime code changed.

## 2026-11-08 — Current GitHub Actions runtime

- Verified pushed commit `1c68f87` in GitHub Actions run `42488729170`: Android tests/lint/APK, 29 backend tests, Python compilation, AWS template lint, APK checksum, and artifact upload all passed.
- Updated checkout, Java, Python, Gradle, and artifact actions to their official Node 24-compatible major releases after GitHub flagged the prior runtimes as deprecated. The artifact action was corrected from v5 to Node 24-based v7 after run `42489260140` exposed the remaining warning.
- Added per-ref concurrency cancellation so superseded pushes do not consume overlapping CI time.
- Validation: each referenced official major tag was resolved from its upstream Git repository before editing. The updated workflow requires a fresh pushed run.

## 2026-11-08 — AWS-only complimentary Premium administration

- Added an IAM-invoked entitlement administration Lambda with no HTTP route or function URL.
- Added fail-closed account selection by opaque Cognito subject or account ID and actions to grant complimentary remote video, revoke access, or reset an account to Free.
- Added entitlement revisioning, verification timestamps, Lambda request-ID audit stamps, 90-day logs, least-privilege DynamoDB permissions, and a CloudWatch error alarm.
- Added deployment instructions that do not hardcode or use a personal email as an authorization bypass.
- Tests: 29 Python tests, Python byte-compilation, and `cfn-lint` 1.46.0 passed. Live IAM invocation, DynamoDB updates, CloudTrail audit, and renewable-lease revocation remain unverified until an approved AWS development deployment.

## 2026-11-08 — AWS account device inventory and revocation

- Added transactionally maintained account-device index records for Server and paired Viewer phones.
- Added Cognito-authenticated, tenant/account-conditioned device listing and enable/revoke endpoints with revocation time and requesting-device audit fields.
- Added Android gateway contracts for device inventory and state changes without modifying the approved monitoring screens.
- Revoked devices are denied alert access and pairing, and remote streaming loses authorization when the current renewable lease expires.
- Tests: 57 Android JVM tests, Android lint, debug assembly, 26 Python tests, and Python byte-compilation passed. Live DynamoDB transactions, Cognito authorization, revocation propagation timing, and account-administration UI remain unverified or intentionally outside the frozen five-screen interface.

## 2026-11-08 — AWS operational safeguards and template validation

- Added HTTP API throttling, explicit 30-day Lambda log retention, an encrypted operations SNS topic, and CloudWatch alarms for API errors/throttling/5xx responses and heartbeat-monitor errors.
- Added bounded EventBridge retries, an encrypted heartbeat dead-letter queue, and an alarm for exhausted scheduled checks.
- Added `cfn-lint` to backend development dependencies and CI so the transformed SAM template is checked without AWS credentials.
- Updated deployment guidance to describe the implemented Cognito onboarding, WebRTC, AWS alert inbox/acknowledgement, AWS-only Android polling, operations-topic subscription, and remaining deployment gates accurately.
- Tests: `cfn-lint` 1.46.0 passed for `infrastructure/template.yaml`; 25 Python tests and Python byte-compilation passed. AWS SAM CLI/account-aware validation and live alarm delivery remain unverified because no AWS stack is deployed.

## 2026-11-08 — AWS-only alert delivery and reboot recovery

- Added account/tenant-isolated DynamoDB alerts for server-offline, sensor-communication, and critical-temperature incidents with 90-day TTL retention.
- Added Cognito-authorized alert retrieval and acknowledgement APIs; disabled or cross-account devices cannot retrieve or acknowledge another account's alerts.
- Added Android AWS alert polling, high-priority local notifications, and an authenticated Acknowledge action without Firebase/FCM or another cloud platform.
- Added opt-in boot recovery for cloud heartbeat and alert services. Camera capture intentionally waits for an app launch because Android restricts starting camera foreground services from boot receivers.
- Extended the heartbeat GSI projection with account identity and kept SNS as an internal event destination for future AWS-native escalation.
- Tests: 56 Android JVM tests, Android lint, debug assembly, 25 Python tests, and Python byte-compilation passed. Live notification timing, reboot/OEM restrictions, Do Not Disturb behavior, Cognito refresh after long idle, and SNS escalation remain unverified until AWS deployment and physical-device testing.

## 2026-11-08 — Renewable, revocation-aware streaming sessions

- Extended authorized viewing sessions to a four-hour absolute cap while adding a two-minute backend authorization lease.
- Viewer phones revalidate tenant/device/Premium authorization every minute; Server phones independently enforce but cannot extend the lease, limiting abandoned or modified-client streaming.
- Added participant-only connection refresh endpoints that issue fresh role-scoped SigV4 WebSocket and AWS ICE/TURN data before bounded reconnect attempts.
- Kept immediate non-cancellable session termination on viewer exit/background and preserved the on-demand camera lifecycle.
- Tests: 54 Android JVM tests, Android lint, debug assembly, 20 Python tests, and Python byte-compilation passed. Live lease timing, revocation, long-duration streaming, and reconnects remain unverified until deployment and device testing.

## 2026-11-08 — On-demand AWS WebRTC camera activation

- Connected enrolled Viewer camera panels to backend-authorized Kinesis WebRTC while retaining the existing local encrypted-JPEG fallback.
- Added a non-exported Server foreground service that waits without opening the camera, captures only for an authorized live session, applies the selected stream resolution/rate, performs bounded reconnects, and releases camera/network resources afterward.
- Added lifecycle-scoped WebRTC rendering with the existing pinch-zoom/pan interaction and non-cancellable backend session termination when the viewer leaves or locks the phone.
- Added a camera lease so CameraX and libwebrtc cannot contend for the front camera.
- Disabled UPnP/public router mapping in AWS-configured builds; the legacy server remains LAN-only for rollback.
- Tests: 54 Android JVM tests, Android lint, and debug assembly passed. Live Cognito/Kinesis signaling, direct ICE, TURN fallback, camera handoff, Wi-Fi/cellular transitions, and physical video remain unverified without AWS deployment and devices. Initial backend session lifetime is still finite and renewable connection leases are the next reliability task.

## 2026-11-08 — In-app AWS onboarding and QR device pairing

- Added a separate responsive landscape onboarding flow for Cognito sign-up, email confirmation, sign-in, restored-session validation, and Server/Client selection without modifying the approved five monitoring screens.
- Added atomic account bootstrap and Server registration calls, five-minute one-use QR generation, client QR scanning/manual entry, same-account claim, and encrypted role/resource enrollment.
- Existing enrollments now require a valid automatically refreshed Cognito session before the monitoring interface opens.
- Standardized onboarding controls on the existing charcoal/amber/white/green palette and retained large landscape typography.
- Tests: 53 Android JVM tests, Android lint, and debug assembly passed; configured BuildConfig generation was checked with placeholder AWS outputs and then restored to a blank local build. Live Cognito, QR camera scanning, backend pairing, and physical layout remain unverified until AWS deployment and device access.

## 2026-11-08 — AWS runtime configuration and durable device enrollment

- Added all-or-nothing build-time injection for the AWS region, Cognito user pool/client, and API Gateway URL while keeping unconfigured local builds operational.
- Added encrypted stable device/greenhouse enrollment identifiers and backend role; no AWS credentials are stored.
- Added bootstrap/server-registration gateway calls and greenhouse identity propagation through server and one-use viewer pairing responses.
- Changed cloud heartbeat reporting to obtain automatically refreshed Cognito tokens at runtime and report the backend greenhouse ID instead of a copied token/local UI ID.
- Tests: 49 Android JVM tests, Android lint, debug assembly, 18 Python tests, and Python byte-compilation passed. Live Cognito/API behavior remains unverified without deployment; the onboarding UI is the next task.

## 2026-11-08 — Atomic AWS account and server-device bootstrap

- Added an idempotent Cognito-subject bootstrap endpoint that atomically creates an isolated account, tenant, and active Free entitlement without storing email or trusting client tenant identifiers.
- Added atomic authenticated greenhouse-server and initial-greenhouse registration; viewer creation remains restricted to the same-account single-use pairing claim.
- Added API/domain tests for first-run bootstrap, retry idempotency, tenant binding, device mismatch rejection, resource validation, and Free defaults.
- Added the AWS SDK as a CI-only backend test dependency; Lambda continues to use its managed runtime SDK.
- Tests: 18 Python tests and Python byte-compilation passed. DynamoDB transaction behavior and JWT authorizer integration remain unverified until deployment.

## 2026-11-08 — AWS Cognito Android authentication foundation

- Added programmatic AWS Amplify/Cognito configuration with SRP sign-up, confirmation, sign-in, sign-out, automatically refreshed access-token retrieval, and authenticated cloud-gateway creation.
- Added strict AWS region, user-pool, app-client, and HTTPS API configuration validation; no AWS credentials or Firebase services are embedded.
- Enabled Android core-library desugaring required by the pinned Amplify Auth dependency.
- Tests: 46 Android JVM tests, Android lint, and debug assembly passed. Live Cognito registration and token refresh remain unverified until a development stack is deployed; the onboarding UI and backend account bootstrap are separate next tasks.

## 2026-11-08 — Reproducible CI verification

- Added a least-permission GitHub Actions workflow for Android unit tests, lint, debug assembly, backend tests, Python compilation, APK checksumming, and short-lived artifact upload.
- No credentials or deployment actions are present in the workflow.
- Local validation remains the latest executed result until GitHub completes the first workflow run.

## 2026-11-08 — Final credential-free checkpoint

- Completed every safe offline component through the cloud activation boundary without changing the approved five-screen design or deploying billable services.
- Final tests: 43 Android JVM tests, Android lint, debug assembly, 12 Python policy tests, Python byte-compilation, and diff checks passed.
- Debug APK SHA-256: `6eabba9fd09d20da0c4389fe1f65329b35cfce82879049137ce7a732e1b05fc1`.
- External gates remain: approved AWS dev deployment, Cognito and Play/FCM configuration, release signing, and Galaxy A71/Pixel 10 network/background/camera validation.

## 2026-11-08 — Correct Kinesis single-master negotiation

- Corrected WebRTC negotiation so the viewer creates the offer and the greenhouse master answers it.
- Preserved Kinesis `senderClientId` and assigned role-specific peer IDs so master SDP/ICE responses target the authorized viewer.
- Tests: 43 Android JVM tests and 12 Python tests passed; Python compilation passed. Live Kinesis interoperability remains a physical/deployed test requirement.

## 2026-11-08 — Foreground server heartbeat runtime

- Added a non-exported, user-visible Android special-use foreground service that reports server/sensor/critical-temperature health approximately every minute only in configured greenhouse-server mode.
- Added bounded retry cadence, encrypted-token expiry shutdown, viewer-mode shutdown, and a local health snapshot updated by Govee refreshes.
- Declared the required foreground-service permissions/subtype without changing the approved interface.
- Tests: Android unit tests, lint, and debug assembly passed with 41 JVM tests. Physical A71 background, battery, reboot, OEM task-killer, notification-permission, and Play policy behavior remain unverified.

## 2026-11-08 — Authenticated Android cloud gateway

- Added an HTTPS-only bearer-authenticated Android gateway for entitlement, pairing, heartbeats, viewer session requests, server polling, and session termination.
- Added Android Keystore-encrypted cloud API, device, access-token, expiry, and optional refresh-token storage; no AWS access keys are stored.
- Added the tenant-derived backend entitlement endpoint and JSON contract tests.
- Tests: Android unit tests, lint, and debug assembly passed with 39 JVM tests; 12 Python tests and Python byte-compilation passed. Cognito token acquisition and live API calls remain unverified.

## 2026-11-08 — Backend-issued Kinesis sessions

- Added deterministic per-greenhouse Kinesis signaling-channel allocation after tenant and Premium authorization.
- Added short-lived SigV4 WebSocket URLs, AWS ICE/TURN configuration, authenticated server polling, and participant-only session termination.
- Added least-privilege-intended Kinesis actions to the undeployed API role and deterministic channel-name tests.
- Tests: 12 Python policy tests and Python byte-compilation passed. Live Kinesis, IAM, TURN, and device interoperability remain unverified without an approved development deployment.

## 2026-11-08 — Account-scoped cloud pairing

- Added five-minute, cryptographically random pairing challenges; only a SHA-256 digest is persisted.
- Added authenticated same-account/same-tenant viewer claims with one-use conditional writes, expiry enforcement, device-ID validation, and safe conflict responses.
- Kept the existing local QR pairing active until Cognito onboarding and a deployed backend are available.
- Tests: 11 Python policy tests and Python byte-compilation passed. DynamoDB integration remains unverified without a deployed development stack.

## 2026-11-08 — Deployment and cost handoff

- Added an approval-gated AWS deployment, rollback, cost, and physical-device validation checklist.
- Made the Cognito Plus cost/security choice explicit and enabled user-pool deletion protection.
- No infrastructure was deployed. SAM validation remains pending because the SAM CLI and AWS configuration are not available in the current environment.
- Final post-rebase verification: Android `testDebugUnitTest`, `lintDebug`, and `assembleDebug` passed with 37 JVM tests; 8 Python tests and Python compilation passed. The debug APK SHA-256 is `acbecc053421b70607946ee206c46e6ec081bd69fe29fbab6c819ef797884ae7`.

## 2026-11-08 — Android 2.7.4 server lifecycle and streaming authorization

### Added
- Client controls for temporary server disconnect/reconnect and permanent server revoke/fresh QR re-pair.
- Explicit administrator Revoke Server action under each customer's greenhouse-server hierarchy.
- Backend-enforced Subscription Controlled, Complimentary Access, and Disabled video modes, with audited changes and optional complimentary expiry.
- Per-greenhouse 1, 5, 10, and 15 minute dashboard refresh settings and expanded server health reporting.
- Regression coverage for stale greenhouse selection, disconnected/revoked devices, fresh pairing credentials, streaming override enforcement, and nested server administration.

### Changed
- Pairing invitations now expire after two minutes and can reauthorize a previously revoked physical server without deleting the stable greenhouse record.
- Disconnect no longer masquerades as revocation: it suspends operational traffic while retaining a resumable credential; revoked credentials cannot be re-enabled without a fresh pairing.
- Camera authorization and lease renewal deny disconnected/revoked servers and honor the separate customer video entitlement.
- History imports/inserts deduplicate by sensor and original timestamp.

### Validation
- Backend: 66 unit tests passed; Python compilation passed.
- Admin portal: 12 tests, ESLint, and production build passed.
- Android: 74 JVM tests, lint, and debug APK assembly passed.
- CloudFormation lint and `git diff --check` passed.
- GitHub Actions run `42557749771` passed full verification and the configured ARM APK job for commit `5a441a6`.
- Plain APK `Greenhouse Monitor 2.7.4.apk` is 81,366,127 bytes with SHA-256 `0e48659cb52342ac24494d03bd0075beba2fd2c06c3750b0a7cbf260374574c5`; the stable Drive file was updated and read back successfully.
- Physical same-Wi-Fi direct video and carrier-network TURN fallback remain unverified until exercised on the target phones.
- Live AWS route/admin deployment is pending owner completion of the reopened AWS CLI browser authorization.

## 2026-11-08 — Stage 6 heartbeat and alert foundations

- Added Android-domain heartbeat reporting and explicit critical/high/normal alert policy without modifying the approved monitoring UI.
- Added paginated, sharded offline detection with atomic incident deduplication; a subsequent authenticated heartbeat clears the incident marker.
- Expanded backend heartbeat policy coverage and corrected the monitor Lambda's DynamoDB write permission.
- Tests: 8 Python unit tests and Python compilation passed. The final combined-history Android checkpoint passed unit tests, lint, and assembly with 37 JVM tests.

## 2026-11-08 — Stage 7 sensor-provider extensibility

- Added a vendor-neutral sensor device/measurement/provider boundary with optional battery, connectivity, identity, and timestamp fields.
- Adapted the existing Govee H5179 implementation, foreground refresh, and WorkManager sync to the provider boundary without changing UI, history, or Govee credentials.
- Added mapping tests that preserve full temperature/humidity precision and missing values.
- Tests: Android `testDebugUnitTest`, `lintDebug`, and `assembleDebug` passed with 30 JVM tests. Live Govee regression remains unverified without hardware/API credentials.

## 2026-11-08 — Stages 3–4 WebRTC media and lifecycle foundations

- Pinned a current precompiled Android `libwebrtc` artifact and added a real peer engine with front-camera master capture, remote-track delivery, continual ICE gathering, and deterministic cleanup.
- Added Kinesis WebRTC signaling SDP/ICE envelope handling over backend-issued secure WebSockets; no AWS keys are embedded.
- Added an on-demand streaming lifecycle that authorizes before connection, handles network loss and bounded reconnection, and ends sessions when the viewer closes, backgrounds, locks, expires, or exhausts retries.
- Preserved the existing encrypted JPEG/local camera implementation; WebRTC is not activated in the approved UI without deployed backend configuration.
- Tests: Android `testDebugUnitTest`, `lintDebug`, and `assembleDebug` passed with 28 JVM tests. Live AWS, TURN, H.264, Galaxy A71, Pixel 10, and network-handoff tests remain unverified.

## 2026-11-08 — Stage 2 security and backend foundations

- Added Android domain contracts for accounts, tenants, devices, one-time pairing, heartbeats, streaming sessions, and backend-authoritative entitlements.
- Added deny-by-default remote camera authorization covering Free, Premium, complimentary backend grants, expiration, revocation, disabled devices, device roles, and tenant isolation.
- Added an isolated AWS SAM template for Cognito, API Gateway, DynamoDB, Lambda, SNS, and scheduled heartbeat checks. Nothing was deployed.
- Added backend authorization, stream-session request, heartbeat foundations, and matching Python policy tests.
- Tests: Android `testDebugUnitTest`, `lintDebug`, and `assembleDebug` passed with 19 JVM tests; 6 Python tests and Python byte-compilation passed. SAM CLI was unavailable, so template lint is pending.

## 2026-11-08 — Stage 1 repository audit

- Read the project memory, camera implementation, sensor/history paths, build configuration, current branch, and worktree state.
- Confirmed the legacy encrypted JPEG/QR/UPnP path and frozen five-screen UI.
- Baseline Android unit tests, lint, and debug assembly passed with 12 JVM tests.

## 2026-11-08 — Premium remote camera implementation scaffold
### Added
- `RemoteCameraAccessPolicy.kt`: fail-closed entitlement policy for remote camera; local camera remains free.
- `RemoteCameraAccessPolicyTest.kt`: premium, complimentary, free, expired and unverified test cases.
- `docs/REMOTE_STREAMING_IMPLEMENTATION.md`: implementation and security acceptance plan for AWS Kinesis Video Streams WebRTC with TURN fallback.
### Changed
- Project memory updated with premium-only, on-demand remote camera streaming decision.
- No existing camera code, five-screen UI, AWS infrastructure, or billing changed.
### Validation
- Source and documentation reviewed; new tests added but not executed in an Android build environment.
### Remaining
- Actual WebRTC transport, Cognito auth, Google Play entitlement verification, backend signaling/TURN authorization, session lifecycle, and AWS deployment remain unimplemented.

## 2026-11-08 — Codex workflow and AWS remote-access upgrade specification
### Added
- Root `AGENTS.md` with mandatory read-before-edit, update-after-task project memory and design-freeze rules.
- AWS authenticated remote camera discovery and encrypted relay fallback requirements for future Codex implementation.
- Persistent changelog policy for all subsequent tasks.
### Changed
- No application source code, UI, camera transport, or AWS resources changed in this documentation update.
### Validation
- Read existing `CameraStreamServer.kt`, `UpnpPortMapper.kt`, `RemoteCameraFeed.kt`, `CameraPairing.kt` and README from `codex/greenhouse-complete-upgrade`.
- No build, emulator, physical-device, or AWS integration tests run.
### Remaining
- AWS discovery, endpoint refresh, and NAT-resistant relay are specifications only; implementation and testing remain outstanding.

## 2026-11-12 — Recovered offline notifications and floor-plan presets (Android 2.10.4)
- Filter retired outages from the pending inbox and guard the scheduled incident write against a simultaneous disconnect, revocation or reassignment. The Android app removes old offline notifications after a successful complete poll and leaves temperature notifications in place. Backup requests no longer hold up heartbeat reporting.
- Settings → General → Edit layout → Shape presets offers L, T, circle and rectangle. Save carries the physical outline to Zones. Round plans support diameter entry and rim dragging. Both two-section 8 × 16 ft layouts cover 256 sq ft. Existing plans remain editable.
- 106 Android JVM tests and 74 Python tests passed. Android lint, SAM build, CloudFormation lint and signed ARM upgrade installation passed. API 35 tests exercised preset selection, backup round-trip, Zones, active/recovered Android notifications, custom wall editing, triangle tracing, and confirmed deletion. Early runs exposed intermittent Settings entry and gesture-test failures; the presets control was moved alongside the editor actions and the deletion test entered via the portrait menu. The preset, notification, triangle and corner reruns passed. The pre-existing greenhouse-deletion test passed once after entering through the portrait menu but intermittently missed its Manage action on another emulator display configuration. Two unrelated emulator cases skipped due to cutout and isolated-install prerequisites.
- Plain APK in build-out and stable Drive file verified, 83,234,350 bytes, local SHA-256 488c694231e34ec601864375b4702b8b4e41d132cc166dc587921e4d38a342d7. Backend changes built locally; CLI sessions expired. Actual customer heartbeat timing, deployed incident filtering and physical two-phone behavior remain unverified.

## 2026-11-12 — Zones crash with invalid saved geometry (Android 2.10.5)

- **Fixed:** Loading a zero or otherwise invalid floor-plan dimension no longer passes a bad aspect ratio to the heat map. Only the damaged dimension falls back to its default. Invalid sensor coordinates are skipped individually; a missing height defaults to zero, so a valid two-dimensional placement survives backup export/import. IDs, assignments and readings are unchanged.
- **Testing:** The 2.10.4 server dashboard fixture closed with `IllegalArgumentException: aspectRatio 0.0 must be > 0` when opening Zones. On 2.10.5, two Dashboard → Zones → Dashboard visits passed with the camera active and a sensor visible. Preset/footprint and compare/replay/overnight regressions also passed (3/3 API 35 tests). 106 JVM tests passed; lint and configured ARM assembly passed. An intermediate test compilation failed due to an assertion inserted in an unrelated fixture; removed it and rebuilt. No physical server log or hardware reproduction was available.
- **Delivery:** Verified upgrade installation and existing signing certificate. Build-out APK and stable Drive file: 82,114,592 bytes; local SHA-256 `39dc89d231fe7eb1388e7233a34c8e4e1a7b8da564e0aab2ec98334a646f8dbd`. Drive name, size and APK MIME verified. No AWS deployment.

## 2026-11-12 — Moto G7 freezes when entering Zones (Android 2.10.6)

- Physical Android 10 reports on 2.10.5 showed the UI thread blocked detaching the dashboard camera texture while the Adreno render thread waited in the driver. Selected CameraX performance preview on river/API 29. A preview-only trial still crashed in the Adreno indexed mesh draw on the second Zones visit. Render heat-map cells as a cached bitmap, retaining the floor-plan mask, grid, color scale, and sensor overlays.
- Verified an in-place signed upgrade on the enrolled Moto, eight Dashboard → Zones → Dashboard round trips, same process, no new crash or ANR, and the live preview restored. 106 JVM tests passed; lint had zero errors (40 warnings, one hint). The API 35 emulator initially found no live-preview label. After waking and unlocking it, the server navigation/legacy footprint test and shape-preset regression both passed. No off-Wi-Fi camera or physical Galaxy regression test in this release.
- Updated FrontCameraFeed.kt, StageScreens.kt, Gradle version, CI artifact label and admin template version default. No schema change or AWS deployment. Signed APK in build-out and on the stable Drive link, 82,114,588 bytes. Local SHA-256 `f68837a70d2e6a0c76e398f586a96771adf6b4482c7293052dafe6e63728ca6c`. Drive name, MIME and size verified.

## 2026-11-12 — Seven-day outdoor forecast and phone alerts (Android 2.11.0)

- Dashboard Outdoor opens a scrollable seven-day forecast showing daily conditions, high, low, precipitation probability and local update time. The existing ZIP, card position and monitoring screens are reused. Missing values remain unavailable.
- Added optional inclusive low/high thresholds for the next two calendar days and a daily reminder at a selectable local time. Ordinary Android notifications open the forecast after authentication. Forecast alerts are device-local, disabled initially, checked on refresh and by network-constrained periodic work. Deduplication is by ZIP, weather date and condition. The inexact daily alarm uses a recent cached daily summary when available.
- Changed weather provider/model, view model, dashboard and activity; added ForecastDialog, ForecastAlerts including scheduler/receiver, manifest registration and tests. Updated Android version, CI artifact name and admin template default. No deployed infrastructure or database changes.
- Signed upgrade installed on physical Moto G7. Verified live data for seven dates, scrolling both ends, Set alerts/Back/Close, and two camera-to-Zones round trips with the same process. Screenshots saved in build-out/2.11.0-qa. 109 JVM tests passed, lint 0 errors (42 warnings and one hint). Initial JVM fixture used raw collections instead of decoded provider JSON; corrected and passed. Initial Android notification assertion sampled before asynchronous posting; verified posted low alert, deduplication and reminder body after polling. An optional legacy camera fixture twice timed out waiting for preview on API 35. Exact OS wake timing, reboot and overnight delivery untested. No opt-ins were enabled on the owner's phone.
- Signed APK 83,392,558 bytes, SHA-256 `c7d6be0d8c4370e62a6083c652fef41c19848e0d294b39015b342a42cfa44dcd`. Plain APK placed in build-out and replaced on stable Drive link; confirmed remote name, MIME and size.

## 2026-11-12 — General Settings version label (Android 2.11.1)
- Added a small muted version label at the bottom-right of General Settings, sourced from the installed build. Updated StageScreens.kt, app version, CI artifact name and admin template version default.
- Verified signed in-place Moto G7 install and the actual version label above bottom navigation. Screenshot in build-out/2.11.1-qa. 109 JVM tests passed; lint had zero errors, 42 warnings and one hint. No physical Pixel check or cloud deployment.
- Updated the stable Drive APK. Size 82,196,656 bytes, local SHA-256 `0811c277f20db3a47eec7e6cc2a03142322461c68645c5f6ea7007da5550d612`.

## 2026-11-12 — Dashboard forecast affordance and weekly outlook (Android 2.11.2)

- Added a visible amber Forecast label and calendar to the existing Outdoor tile and a button role to the tile. Updated ForecastDialog with aligned daily cards, today highlight, precipitation and weather icons, persistent Refresh and Set alerts actions. Width and font scale govern wrapping.
- Initial landscape grid wrapped after four days on Moto. Adjusted breakpoint and verified all seven days, update time, and alert access together in the final installed signed build. Tapped Forecast, Refresh, Set alerts, Back and Close. Physical screenshot in build-out/2.11.2-qa. No physical Pixel or enlarged-font test. JVM 109 passed, lint zero errors (42 warnings and one hint), emulator forecast notification regression passed before the final layout breakpoint adjustment.
- Changed DashboardScreen.kt, ForecastDialog.kt, Gradle version, CI artifact name and admin version default. Stable Drive file replaced with signed APK, 83,427,034 bytes, local SHA-256 `b206514a45f6c522ef8983a829f79193bb27260d61584f8bd27503c1d4e807e3`. No backend deployment.

## 2026-11-12 — Customer backup and recovery (Android 2.12.0)

- Added Settings → Backup with Save backup and Restore from file. A new backup password protects each portable document using PBKDF2 and Tink streaming AES-GCM. Enrolled phones require a connection to verify device authorization. Use the same account and mode on the destination, and pair a replacement server to its original greenhouse first.
- Collector backups include local plans, assignments, current sensor inventory, full retained reading history, weather and display settings, indoor notification rules and forecast preferences. Paired viewer exports contain its local forecast rules. Account records remain on the backend. Set up Govee separately on a new collector.
- Changed BackupPanel, CustomerBackup, HistoryStore, GreenhouseBackupStore, the settings stores, ForecastAlerts, MainActivity, GreenhouseViewModel, StageScreens, version labels and Gradle dependency declaration. Restore merges history in one transaction, journals settings for interrupted applies and preserves destination camera role. The journal uses a local SQLite table created on demand. No cloud migration or deployment.
- 109 JVM tests passed, lint had 0 errors (46 warnings, one hint), and 74 backend tests passed using mocks. Seven Android API 35 tests passed covering replacement, repeat restore, newer readings, wrong password, tampering/truncation, tenant/house/mode mismatch, viewer rules, interrupted settings recovery, malformed cloud snapshots, bounded upload retry, and paired snapshot rendering. The recovery fixture contained 2,200 saved readings and one newer destination reading. Instrumentation used fixture registrations and streams, without a live cloud-to-cloud restore or two physical phones.
- Signed in-place update on Moto G7. Opened Settings → Backup → Restore from file and canceled the system picker without changing customer data. Screenshot in build-out/2.12.0-qa. An actual provider upload/restore through Google Drive was not exercised. Plain signed APK published in build-out and to the stable Drive file, 83,557,654 bytes, local SHA-256 `9594821e15454c552fccec649fe2ba520d6a56da447d6478fb8e31e937333378`. Remote name, MIME and size confirmed.

## 2026-11-12 — Sensor placement, zone insights and local backup (Android 2.13.0)

- Fixed repeated Zones drags, overlapping sensor selection in Floor plan, and server publication after a placement edit. Paired viewers display the collecting phone’s positions. Updated StageScreens, FloorPlanEditor, GreenhouseViewModel and CloudHeartbeatService.
- Updated ZoneInsights with sensor selectors, a single active comparison metric, overnight difference chart, and a replay map with optional reading details. Physical screenshots are in build-out/2.13.0-qa.
- CustomerBackup, BackupPanel, LocalBackupWorker, SecureSettingsStore, CloudOnboardingScreen and MainActivity support password-free exports, local automatic backups and confirmed restore. One daily periodic job snapshots the current validated scope; keep three completed copies. Android may defer the job. Private copies disappear on uninstall. Older encrypted files still require their original password. Existing account/house/role checks, credential exclusions and history merge remain. Paired viewers save phone-local forecast preferences; collectors save greenhouse data and settings. No backend or schema deployment.
- 109 JVM tests passed. Android lint reported zero errors, 46 warnings and one hint. Final API 35 instrumentation passed all ten tests across CustomerBackupTest and StandaloneEditorTest. Coverage includes two successive map drags through the view model, reopening and reading the coordinates, floor-plan placement, both dialogs, three-copy rotation, merged history, corrupt files, wrong scope, interrupted restore and encrypted-file compatibility. The first repeated-drag test read stale Android accessibility bounds before the rendered update; waiting for the new bounds passed independently and in the complete suite.
- Signed upgrade installed on the physical Moto G7. Opened both dialogs, returned to Dashboard, observed a dated restore action and validated the first automatic local archive. Restore used isolated emulator data. Two-phone live synchronization and overnight scheduling were not retested. Updated build.gradle.kts, CI artifact and infrastructure version defaults. Published the plain APK in build-out and on the existing Drive link. Verified Drive name, MIME and size (82,327,728 bytes). Local SHA-256: `4db074bd4790b90102811758bca34846a7954e1b92012639f44726c19ccfbad6`.

## 2026-11-13 · Android 2.14.0 (code 42) — Interactive greenhouse charts

- Added SensorTrendChart with native animated traces, labeled axes, separate sensor colors, missing-data gaps, touch inspection and an accessible time-range action. StageScreens uses the same chart for 6-hour, 24-hour and 7-day History. The collecting phone queries retained SQLite readings for the requested week independently of the dashboard row limit. A paired viewer plots available synced history.
- ZoneInsights adds a shared time cursor and measured differences to the two-sensor temperature/humidity comparison. Overnight Replay shows the proportional floor plan and chart side by side. Play advances overnight readings in about 24 seconds. Pause, drag-to-seek and background handling control playback. Raw sensor details are collapsed.
- Verification: 109 JVM tests passed. Lint finished with zero errors, 46 warnings and one hint. Three API 35 instrumented tests passed covering the chart interactions, gaps, querying older SQLite samples and sensor positioning after reopen. Physical Moto G7 upgrade, visual inspection and interaction checks covered History range selection, Compare scrubbing and Replay playback. No new Pixel or paired-cloud end-to-end test. No database migration or cloud deployment.
- Updated Android version, CI artifact name and admin template version default. Signed plain APK in build-out and the existing Drive file verified by name, MIME type and size (83,824,466 bytes). Local SHA-256: `169593aa62348a304c90a3c62968e47a25a2378a7d2208afdf9e795b8b759499`. Physical screenshots are under build-out/2.14.0-qa.

## 2026-11-13 · Android 2.15.0 (code 43) — Interactive 3D Zones

- Added a native rotatable greenhouse with two-finger zoom and pan, sensor selection, a fixed color legend for each metric and motion-aware transitions. Uses the selected floor plan and real sensor heights. Collecting phones edit mounting and greenhouse heights. Unknown legacy heights remain unset. A local estimate fades toward unmeasured areas. Visible markers show measurements.
- Added low-temperature, relative cold-spot and coverage suggestions. A recent outdoor forecast can flag a cooling risk. An indoor range requires three prior days with sufficiently spaced readings of the same sensor under similar outdoor conditions. Treat this as an observation from past conditions; moving a sensor can reduce comparability. No change to alarms, database schema or cloud services.
- Changed ZoneVolumeScreen, ZoneClimate, StageScreens, MainActivity, GreenhouseViewModel, CloudOnboardingScreen, Models, AppPreferencesStore and LiveWeatherProvider. Added climate and weather tests and exercised the native controls in StandaloneEditorTest. Updated Android and CI release metadata and the admin template version default.
- Verification: 114 JVM tests passed. Lint reported zero errors, 46 warnings and one hint. The Moto initially returned to Zones immediately because drawing a path invoked the screen dismissal callback. Corrected the call and added a regression assertion. Initial instrumented height/zoom checks needed to wait for accessibility updates. One earlier Compare scrub assertion also timed out. All three final API 35 tests passed together, covering rotation gestures, two-finger zoom, Fit, editing and saving height, legacy zero, portrait, repeated zone drags, Compare and Replay. Tested live readings, switching to humidity, Fit, opening and canceling height editing, closing and returning to Dashboard on the enrolled Moto. No actual mounting heights were assigned on that phone. Cross-device height sync, physical Pixel rendering, forecast performance across real nights and screen reader use on hardware remain unverified.
- Installable signed APK in build-out, 84,084,262 bytes. SHA-256 `f18e4a02de93c61ddb4c80ad879d7a7b21904a3b45818d202915e3261a14a899`. Existing Google Drive download replaced and verified by metadata and size. Screenshots under build-out/2.15.0-qa include the physical Moto and emulator fixture in both orientations.

## 2026-11-13 · Android 2.15.1 (code 44) — Visible heat map with unknown sensor heights

- Fixed an empty-looking 3D view when mapped sensors reported readings but lacked mounting heights. ZoneVolumeScreen uses SpatialInterpolator for a bounded, cached, footprint-clipped floor texture. Unset heights remain unknown. Known heights keep their existing air volumes, including in mixed installations. Both metrics color the markers.
- Changed ZoneVolumeScreen, StandaloneEditorTest, Android version metadata, CI artifact name and admin version default. No schema change or backend deployment.
- Tested 114 JVM cases, lint (0 errors, 46 warnings, one hint), configured APK assembly and signature verification. Both API 35 instrumentation tests initially encountered the Android notification permission prompt before reaching the fixture. After granting permission in the test emulator both passed, including pixel assertions for temperature and humidity with three fresh, height-unset viewer sensors and the mounted-sensor gesture/height regression. On the enrolled Moto G7 the upgrade displayed the projected heat map with live temperature and humidity and returned to Dashboard. The physical client phone was not tested. Screenshots are in build-out/2.15.1-qa.
- Plain signed APK in build-out and the stable Google Drive file. Drive name, MIME and size verified after upload (82,557,104 bytes). Local SHA-256 `68df2c94f5e54775305c32d17481f03b69a1f2052199eaa3783d3504986ab468`.
