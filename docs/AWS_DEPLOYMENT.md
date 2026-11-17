# AWS deployment handoff

The dedicated `greenhouse-monitor-dev` stack is deployed in `us-east-1`; it has no dependency on junecity.studio. Production and staging stacks have not been created.

The separate `greenhouse-monitor-deployment-bootstrap` stack owns a private encrypted artifact bucket, a restricted CloudFormation service role, and a restricted deployment-caller role. The application stack was created through the service role. The temporary root browser session used only to bootstrap the roles must be logged out after handoff and is never embedded in the app or CI.

## Prepared resources

- Cognito Plus user pool with verified email, optional authenticator-app MFA, threat protection, token revocation, short access/ID tokens, and deletion protection.
- JWT-authorized API Gateway HTTP API with access logging.
- Three reserved-concurrency Lambda functions for tenant APIs, scheduled heartbeat monitoring, and IAM-only entitlement administration.
- Encrypted, point-in-time-recoverable, on-demand DynamoDB table with TTL and a sharded heartbeat index.
- Encrypted SNS internal critical-alert and operations topics, one-minute EventBridge schedule, bounded retries, and an encrypted dead-letter queue.
- Thirty-day API/Lambda log retention, API throttling, and CloudWatch alarms for API errors/throttling/5xx responses, heartbeat-monitor errors, and dead-lettered checks.
- A non-HTTP entitlement administrator with least-privilege DynamoDB access, 90-day audit logs, request-ID stamping, and an error alarm. It grants complimentary remote video, revokes it, or resets an account to Free without hardcoding an email address.

The Android WebRTC peer, signaling client, HTTPS cloud gateway, encrypted token/config store, Cognito onboarding/token refresh, account-scoped QR pairing, device inventory/revocation gateway, AWS alert polling, local high-priority notification, and alert acknowledgement paths are present. The backend contains authorized Kinesis channel creation, short-lived signed WSS endpoints, ICE/TURN retrieval, server polling, session termination, device revocation, alert persistence, and acknowledgement. The archive has server-side Google Play subscription verification. Live merchant configuration and physical two-phone release testing remain open. Keep the legacy local camera path available until the AWS path passes physical-device tests.

## Completed development deployment

- Stack status: `CREATE_COMPLETE`; Cognito Plus deletion protection is `ACTIVE`; both stacks report `IN_SYNC` drift status.
- Live checks passed for the JWT rejection path, DynamoDB encryption/PITR/TTL, Python 3.13 arm64 Lambdas, the one-minute Scheduler rule, and a direct heartbeat-monitor invocation.
- The configured APK passed signature, checksum, stack-output presence, long-lived access-key-pattern, unit/lint, and emulator cold-launch checks.
- The operations SNS topic has no human subscription. Add an approved AWS-native destination before relying on operational alarms.
- No Kinesis signaling channel is created until an authorized viewer requests an on-demand session.

## Remaining production checklist

1. Create separate staging and production stacks only after the development device matrix passes.
2. Review Cognito Plus pricing. Threat protection requires Plus; change this only through a documented security decision.
3. Establish separate `dev`, `staging`, and `production` stacks, budgets, cost-anomaly alerts, and resource tags.
4. Replace root-assisted bootstrap access with an owner-controlled IAM/Identity Center deployer that can assume `greenhouse-monitor-dev-deployer`; do not use root, administrator credentials, or access keys in CI or Android.
5. Continue running both CloudFormation lints and account-aware SAM validation before every update.
6. Add backend integration tests against an isolated local/emulated or dev table, including conditional writes, pagination, throttling, and tenant denial.
7. Validate server-side Kinesis allocation, endpoint signing, ICE retrieval, and termination; then add production quotas, metrics, and audit retention.
8. Validate the implemented single-use hashed pairing and device inventory/revocation flows. Decide where account administration appears without changing the approved monitoring screens.
9. Configure Google Play products only after approval. Verify purchase tokens through the Play Developer API and process Real-time Developer Notifications; never accept a client entitlement flag.
10. Subscribe an approved AWS-native operations destination to the operations topic. If SMS/email/voice escalation is approved, route it through dedicated tenant-aware AWS notification workers; do not expose the internal topics directly to arbitrary customers. Android currently polls its Cognito-authorized alert inbox about once per minute and does not use Firebase.
11. Review the Android special-use foreground-service declaration against current Play policy and run the physical test matrix on the Galaxy A71 and Pixel 10 across same-LAN, separate Wi-Fi, cellular, TURN-only, public-IP change, lock/background, battery optimization, server restart, and AWS failure conditions.
12. Confirm rollback: leave the encrypted JPEG/local path enabled until WebRTC passes; keep UPnP disabled unless explicitly needed for the legacy test path.

## Complimentary owner activation

The owner entitlement is administered through a Lambda function that has no API Gateway event or public function URL. Grant `lambda:InvokeFunction` on only this function to a tightly controlled operations role and rely on CloudTrail plus the function's 90-day log group for invocation audit.

After the owner has registered in Cognito, obtain the account's opaque Cognito `sub` using an authorized Cognito administrator. Then invoke the function with one of these payloads:

```json
{"action":"GRANT_COMPLIMENTARY","subject":"<cognito-sub>"}
{"action":"REVOKE","subject":"<cognito-sub>"}
{"action":"RESET_FREE","subject":"<cognito-sub>"}
```

Example invocation after deployment:

```text
aws lambda invoke \
  --function-name <EntitlementAdminFunctionName-output> \
  --cli-binary-format raw-in-base64-out \
  --payload '{"action":"GRANT_COMPLIMENTARY","subject":"<cognito-sub>"}' \
  entitlement-response.json
```

Never expose this invocation permission to the Android application, and never use an email address as the grant key. The next remote-stream request or one-minute renewable lease check reads the authoritative entitlement from DynamoDB.

## Reproducible deployment/update commands

Bootstrap the restricted roles once, review the application change set, and deploy through the emitted CloudFormation service-role ARN. Keep `UserPoolDeletionProtection=INACTIVE` only while creating a new development stack, then immediately update it to `ACTIVE` after `CREATE_COMPLETE`.

```text
cfn-lint -i W3037 -- infrastructure/deployment-bootstrap.yaml
cfn-lint infrastructure/template.yaml
sam validate --lint -t infrastructure/template.yaml --region us-east-1 --profile <profile>
sam build -t infrastructure/template.yaml
sam deploy --template-file .aws-sam/build/template.yaml \
  --stack-name greenhouse-monitor-dev \
  --s3-bucket <ArtifactBucketName-output> \
  --capabilities CAPABILITY_IAM \
  --role-arn <CloudFormationServiceRoleArn-output> \
  --region us-east-1 --profile <profile> \
  --parameter-overrides EnvironmentName=dev OfflineTimeoutSeconds=180 UserPoolDeletionProtection=ACTIVE
```

After deployment, record stack outputs in an encrypted CI secret/configuration system. The Android build may receive non-secret identifiers such as Region, API base URL, user-pool ID, and app-client ID. It must never receive AWS access keys, Play service-account credentials, or customer tokens at build time.

For a local debug handoff after the stack exists and the operator has read-only CloudFormation access, use:

```text
scripts/build-aws-debug-apk.sh <stack-name> <aws-region> [aws-profile]
```

The script reads only `ApiUrl`, `UserPoolId`, and `AndroidClientId`, supplies the selected Region to Gradle, and writes the APK plus SHA-256 checksum under ignored `build-out/`. It does not read, print, or embed AWS credentials. A Play-distributed release still requires the owner-controlled release keystore and Play configuration.

## Cost model (US East reference, verify for the deployment Region)

The design is usage-based, but it is not zero-cost:

- Kinesis WebRTC is charged by active signaling channel, signaling messages, and TURN relay minutes. AWS's current US-East example uses **$0.03 per active channel-month**, **$2.25 per million signaling messages**, and **$0.12 per 1,000 TURN minutes**, plus applicable internet data transfer. Direct peer-to-peer media avoids TURN-minute charges.
- One server heartbeat per minute is about 43,200 API calls, Lambda invocations, and DynamoDB writes per 30-day month. A one-minute scheduled monitor adds another 43,200 Lambda invocations and about 691,200 sharded index queries before retries/pagination.
- Cognito Plus has no MAU free tier; the template deliberately selects Plus because enforced threat protection requires it.
- API Gateway, Lambda, DynamoDB on-demand, CloudWatch Logs/X-Ray, SNS, KMS requests, and data transfer add usage-based charges. Email/SMS/voice escalation is not included and would add provider charges.
- The operations alarms, SQS dead-letter queue, and 30-day log retention add small usage-based charges; subscribe an operations destination after deployment or alarm state changes will have no human recipient.

For planning, calculate at least three scenarios (pilot, 1,000 customers, peak incident) in the AWS Pricing Calculator using measured TURN percentage, session duration, heartbeat volume, authentication MAUs, log retention, and data transfer. Set budgets before creating the stack. Prices can change; the AWS pricing pages are authoritative.

## Rollback and teardown

- Android: disable the cloud feature flag and continue local Govee/history/camera operation.
- Streaming: deny new session creation, terminate active leases, and retain the legacy local camera path.
- Backend: deploy the preceding SAM revision. DynamoDB and identity data require an explicit, reviewed migration rather than automatic deletion.
- Stack teardown will not automatically remove the retained DynamoDB table, and Cognito deletion protection must be deliberately disabled first. Export or securely delete customer data according to the approved retention policy.

## Internal Google Play camera checkout test (Android 2.16.0)

1. In Play Console, use the existing package `studio.junecity.greenhouse`. Configure an internal test release, a subscription with ID `premium_monthly` and an auto-renewing monthly base plan with ID `monthly`. Choose the test country's price in Play Console. The Android sheet displays the localized recurring price returned by Play.
2. Add the purchasing Google account to **license testing** as well as the internal test track. Opt into the test track and select the tester account in Play Store. An internal track invitation by itself does not guarantee free transactions. At checkout, verify that the Play sheet offers **Test instrument, always approves** or **always declines**. Cancel if it shows an ordinary payment method. [Google Play billing test instructions](https://developer.android.com/google/play/billing/test)
3. Configure a Google Play Developer API service account for this app with subscription lookup and order management permissions. Place its JSON in Secrets Manager. Supply the secret ARN to the dedicated development stack's existing `GooglePlayCredentialsSecretArn` parameter, and the test customer's opaque Greenhouse account ID to `PlayTestAccountIds`. Deploy the updated API code and template. Keep the approved app package name. The default empty tester list disables checkout. Annual plans, trials and introductory offers are outside this monthly test.
4. Use a paired Free customer with **Subscription Controlled** camera policy. Open Live View, confirm the price and recurrence, and try Maybe later. Reopen, try a declined test purchase, then buy with the approving test instrument. The backend checks Google’s product, base plan, test flag, account binding and expiration, acknowledges the purchase, then records the entitlement. Close the sheet and explicitly start a 15-second view. Confirm a second authorized phone on the account can stream.
5. Interrupt a test checkout and reopen Live View. The sheet queries owned subscriptions and also offers Restore purchase. Check pending, renewal, cancellation through the paid period, expiry, and admin disable. For this internal test, reopen the checkout on expiry to reverify a renewal, or use the existing owner reverify action. Automatic Play lifecycle notifications and production billing are separate work.

### Paid camera recording and playback

Customers open **Cameras → Playback → Cloud library → View subscription** on their paired viewer. Google Play supplies the local price and handles recurring monthly payment. One subscription covers the customer's paired greenhouses. The greenhouse phone records a one-minute silent clip on demand while the camera is open. Either paired phone can play the saved clip. Local short clips and timelapses remain available on the phone where they were saved. Remote Live View uses its own entitlement.

**Configure a test purchase**

1. Use the Play developer account that owns `studio.junecity.greenhouse`. Set up its payments profile and create the `camera_storage_monthly` subscription with the auto-renewing `monthly` base plan. Activate the plan in the test region at the owner's approved price. Use an ordinary monthly price for this plan. The Android checkout selects the base plan directly. [Play setup](https://developer.android.com/google/play/billing/getting-ready)
2. Enable the Android Publisher API in the Google project. Give a dedicated service account permission to read and manage subscriptions for this package. Store its JSON in the environment's AWS Secrets Manager. Set the stack parameter `GooglePlayCredentialsSecretArn` to the secret ARN. The app receives only the account binding and catalog metadata. [Publisher API access](https://developers.google.com/android-publisher/getting_started)
3. In Google Cloud, create a Pub/Sub topic and grant `roles/pubsub.publisher` on that topic to `google-play-developer-notifications@system.gserviceaccount.com`. Create a dedicated push identity. The Pub/Sub service agent needs permission to mint its OIDC token. Set `GooglePlayPushServiceAccount` to the push identity’s email. Configure a push subscription with endpoint `<ApiUrl>/v1/billing/google-play/notifications` and the exact custom audience from the `GooglePlayPushAudience` stack output. In Play Console's monetization settings, select the topic and send a test notification. Check the delivery response and monitor the subscription’s oldest unacknowledged message. If Play uses one topic for the package, give staging and production separate push subscriptions, identities and audiences. [Play notification setup](https://developer.android.com/google/play/billing/getting-ready) and [authenticated push](https://cloud.google.com/pubsub/docs/authenticate-push-subscriptions)
4. For the dedicated development stack, apply the bootstrap update for the scoped storage permission. Deploy the API with `EnableCameraArchive=true`, `CameraArchiveSalesMode=LicenseTest`, and `PlayTestAccountIds` set to the test customer's opaque Greenhouse account ID. Set `CameraArchiveBucketName` to `greenhouse-monitor-dev-clips-<account>-<region>`. Its data is private, encrypted, and accessible over HTTPS. Review the CloudFormation change set against the intended stack.
5. Install the internal Play build on the viewer and pair a server in the same environment. Add the buyer's Google account to both the internal track and license testing. Test a declined payment, an approved test payment, and a delayed approval after closing the app. Verify that the buyer can restore access and manage the subscription. Use Play’s test instrument. [Billing test guide](https://developer.android.com/google/play/billing/test)
6. Record a minute from the greenhouse phone. Interrupt the upload, retry the local copy, then play and delete the single resulting clip from the viewer. Verify another customer cannot see it. Exercise renewal, cancellation through the paid period, expiration and revocation with the viewer closed. Check that a live-view-only account can still use its existing features.

**Archive limits and operations**

The account may request 24 new clips per UTC day, up to 8 MiB each. Current app versions reuse a recording ID on retries. Clients predating 2.43 count each upload authorization separately. The API enforces seven-day retention immediately, independently of S3’s asynchronous lifecycle expiration. A signed upload URL lasts at most two minutes and a playback URL at most three. Both also expire at the paid-through time or clip deadline, whichever comes first.

The service rechecks payment during archive access and on Play subscription notifications. The stored paid-through time bounds a short verification cache and a maximum five-minute allowance during a temporary Play outage. Monitor HTTP 5xx, Lambda errors and Google Pub/Sub delivery failures, and subscribe the operations notification destination for the deployment. Budget for S3, transfers and requests plus API/Lambda/DynamoDB usage.

**Release and rollback**

Use the customer environment’s own stack, Cognito user pool, recording bucket, push identity and billing credential. For example, provision a bootstrap with ApplicationStackName `greenhouse-monitor-prd` and AdminStackName `ghs-prd-admin`, then deploy that application stack with EnvironmentName `prd` and explicit bucket `greenhouse-monitor-prd-clips-<account>-<region>`. A separate staging deployment can use `stg` in place of `prd`. Dedicated AWS accounts also isolate service-level provisioning permissions. Review the change set, use the bootstrap’s execution role, and confirm operations alarms have a recipient. Set `CameraArchiveSalesMode=Production` only for the customer environment after the license test. The development template defaults to license testing. When preparing the Play bundle, supply the stack's Region, API URL, Cognito user-pool ID and Android client ID to the existing Gradle build. Use the command below with those four environment variables loaded.

```sh
./gradlew :app:bundleRelease -PGREENHOUSE_DIRECT_SERVER_UPDATES=false -PGREENHOUSE_ARM_ONLY=false
jarsigner -keystore /path/to/play-upload-keystore -signedjar greenhouse-play.aab \
  app/build/outputs/bundle/release/app-release.aab YOUR_UPLOAD_KEY_ALIAS
jarsigner -verify greenhouse-play.aab
```

Use the registered upload key for the existing Play listing. Increment the version code for a new bundle if a development-configured build has already used the candidate’s code on any Play track. Internal testing against the actual customer backend before production rollout can use the same bundle when promoted. The owner should review the store listing’s video storage, retention and subscription disclosures. Record the observed purchase, renewal, cancellation and playback results for the release.

To pause recording, set `EnableCameraArchive=false` and **keep the existing explicit bucket name**. The Lambda loses archive access while the private bucket, TLS policy and expiration continue. Turning off the AWS feature does not cancel Google Play renewals. Coordinate any permanent shutdown with the affected subscribers and their Play billing status.

For a repeatable local Android check, install the debug and androidTest APKs on two disposable Android emulators. Run `scripts/test-android-camera-archive.py --server <serial> --viewer <serial>` in a Python environment with the backend dependencies and `moto[server]`. The script encodes a synthetic one-minute MP4, uploads it twice, decodes it on the other device and verifies deletion. Google purchases and AWS storage are simulated in this check.

### Remote updates for a dedicated greenhouse phone

**Customer setup on the phones.** For the directly distributed Greenhouse Studio APK, pair the greenhouse phone normally. On a server running 2.37 or older, install the new same-signer APK over the existing app once, keeping its data and pairing. Use 2.38 or later on the client as well.

1. On the greenhouse phone, open **Settings → Connectivity → Allow remote updates**.
2. On Android’s permission screen, enable **Allow from this source** for Greenhouse Studio and return to the app. Leave monitoring running for its next heartbeat.
3. When a newer release is available, open **Settings → Connectivity** on the client, select the greenhouse and tap **Update server**. The phones can use different internet connections. Repeat setup on each greenhouse phone that should accept update requests.

On ordinary Android 10–11 phones (including the Moto G7 on Android 10), the server offers the system installer for each update. Tap the update notification on that phone and confirm **Install**. On eligible Android 12+ phones the app can request unattended self-update. If Android asks for confirmation, tap its notification or use **Settings → Connectivity → Approve update** on the server. Declining ends that request. A paired client can tap **Update server** again to start a new one. An already fully managed greenhouse phone keeps its existing unattended path. Turning remote updates off on the server withdraws its permission for future requests. Heartbeat and alert monitoring resume after a successful update. On an ordinary phone, tap the monitoring notification to reopen the dashboard and camera if Android keeps the app in the background. [Android PackageInstaller](https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams#setRequireUserAction(int))

This option belongs to the direct APK distribution. Build it with `-PGREENHOUSE_DIRECT_SERVER_UPDATES=true` or the dedicated development APK build script. Standard builds omit the install permissions and leave customer updates to their distribution channel. [Google Play install-permission policy](https://support.google.com/googleplay/android-developer/answer/12085295)

The client compares the server’s reported build with the current private release. Each request targets one server and lasts seven days. The server picks it up on its next successful monitoring heartbeat, normally within a minute. Legacy ordinary phones can still receive an install reminder. A reminder cannot authorize an install after a later opt-in.

**Publishing a version for client discovery.** Deploy the application stack with the private artifact bucket in `ServerReleaseBucketName`. Use the previously enrolled signing certificate's SHA-256 fingerprint and put Android build-tools (`aapt`, `apksigner`), Java and AWS CLI on the publisher's PATH. Supply the profile and region for the same isolated stack:

```sh
AWS_PROFILE=greenhouse-dev AWS_REGION=us-east-1 python3 scripts/publish-server-release.py \
  'build-out/Greenhouse Studio 2.38.0.apk' --stack greenhouse-monitor-dev \
  --signer-sha256 "$TRUSTED_SIGNER_SHA256"
```

The publisher checks the APK signature, package and build number, creates an immutable `releases/<environment>/android/<code>.apk`, checks the stored length, checksum and metadata, then conditionally updates `latest.json`. Re-running the command with the same file is safe. Existing installed clients that predate catalog support keep their original version-based behavior. New clients refresh device status from the API. Each running Lambda can cache discovery for up to 30 seconds. An installation request revalidates the available release before queueing.

Publish a higher signed build for corrections. The device checks file length, SHA-256, package identity, signing certificate and version, and rechecks authorization after download. No arbitrary URL, shell command or downgrade is accepted. To withdraw a pending release, remove that version from the private distribution prefix. An install already committed to Android can still finish. Existing development APKs use the enrolled development signing identity. Use a maintained production signing and distribution policy before customer rollout. Test a full update on an enrolled physical server before updating multiple phones.

## Additional greenhouses and account camera recording

Each account includes its first monitored greenhouse. Each additional active greenhouse uses one paid slot, whether it collects on a standalone phone or uses a paired server. Sensors are included. Monthly monitoring capacity and cloud recording are separate Google Play subscriptions. A change in greenhouse count replaces the monitoring subscription. Local short clips remain available independently.

### Suggested US monthly prices

| Purchase | Suggested US price | Example |
| --- | ---: | --- |
| First greenhouse | Free | One standalone or server greenhouse |
| Each additional active greenhouse | $6.99 | Three greenhouses cost $13.98 per month |
| Account cloud recording | $7.99 | Covers all camera-equipped greenhouses on the account |

Three greenhouses with cloud recording total $21.97 per month before applicable tax. Use Play Console to create the ten subscription products `greenhouse_extra_1` through `greenhouse_extra_10` with an auto-renewing monthly base plan named `monthly`. The suggested US prices in tier order are $6.99, $13.98, $20.97, $27.96, $34.95, $41.94, $48.93, $55.92, $62.91 and $69.90. Configure the separate `camera_storage_monthly` product at $7.99. Set prices and availability for every supported market so each larger capacity tier costs more per month. The checkout shows the current localized Play price and renewal period.

At a combined US Google Play subscription and billing fee of 15%, these prices yield about $5.94 per paid additional greenhouse and $6.79 per paying video account each month before operating costs, taxes and refunds. For a high-use video example, 24 uploads per day for 30 days at the maximum 8 MiB per upload produce 5.625 GiB of new clips. A seven-day retention window holds about 1.31 GiB. Three full plays per uploaded clip transfer 16.875 GiB. At illustrative US East rates of $0.023 per GB-month stored, $0.09 per GB served, $0.005 per thousand PUTs and $0.0004 per thousand GETs, storage, transfer and these object requests come to approximately $1.55–$1.67. This leaves about $5.12–$5.24 of the video subscription for remaining costs. Pricing units, taxes, regional prices, playback volume, and free transfer allocations affect the result. Measure actual API Gateway, Cognito, Lambda, DynamoDB, telemetry, support, and free-house costs in addition to S3 before declaring a profit. In particular, repeated playback increases transfer costs and a continuously running server incurs polling and snapshot costs even with zero clips. [Google Play fees](https://support.google.com/googleplay/android-developer/answer/112622) · [Amazon S3 prices](https://aws.amazon.com/s3/pricing/) · [Amazon DynamoDB prices](https://aws.amazon.com/dynamodb/pricing/)

### Enabling subscriptions

Use the existing Google Play Publisher credential and authenticated Pub/Sub push endpoint. Set `GreenhousePlanSalesMode=LicenseTest`, allow-list the test account in `PlayTestAccountIds`, and set `GreenhousePlanMaxExtras` to the highest configured tier. The checkout defaults to Off on a fresh stack. Leave each product unavailable for production sale until its base plan is active in the customer's market and a Play-distributed signed build exercises checkout, restore, renewal, cancellation, and a replacement. Production mode requires both the Publisher credential and the verified Play push-service account. The customer-facing build needs the production Cognito/API configuration and the registered Play upload key.

Use a fresh test account to create a standalone greenhouse, attach a server to that same greenhouse, then add another greenhouse. Verify that attaching the server consumes zero additional slots. Purchase the second greenhouse and check its dashboard and sensor alerts. Pause it, verify the paid-through date in Play, let the test subscription expire, and confirm that monitoring pauses while saved history and floor plans remain accessible. Resume from the same account and verify the sensor assignments. Confirm the first greenhouse continues collecting throughout. Existing pre-upgrade installations retain their established greenhouses.

For remote camera coverage, open Cameras → Playback → Cloud library on the client. Record one minute on a named server, then use **Record all ready cameras** with at least two online camera phones. An offline or outdated server should show its own status. Clips share the existing account-wide limit of 24 per UTC day and seven days of playback. A server must have the app open at least once after a reboot to establish Android camera foreground-service capability and needs camera permission. Renewing monitoring alone does not activate cloud storage.

Server backup snapshots of at least 1 KiB use gzip when it reduces the DynamoDB item size. The new reader accepts both compressed and legacy JSON records. Keep the dual-format reader in any backend rollback once compressed records have been written. Phone exports and restores still use their existing formats.

The two-greenhouse HTTP test uses local DynamoDB and S3, actual one-minute MP4s, scoped device credentials, and a simulated Play verifier. Run `scripts/test-android-camera-archive.py --orders-only --ffmpeg /path/to/ffmpeg`. For Android-to-Android remote recording, provide disposable server and viewer emulator serials with the installed debug and androidTest packages and add `--remote-order`; the server emulator needs a front camera. The harness uses adb reverse and an ephemeral HTTPS certificate.

CloudWatch API error and throttle alarms use the existing operations topic. Compare actual net Play receipts with tagged AWS cost totals in the administrator dashboard each billing cycle, including usage from free accounts. Console access on a phone is sufficient for the operator to inspect CloudFormation and those alarms. To grant a separate CLI session for deployment using an existing IAM Identity Center assignment, obtain its access portal URL and home Region, then use the AWS CLI device authorization flow. The operator completes approval in the phone browser. [AWS CLI cross-device authorization](https://docs.aws.amazon.com/cli/latest/userguide/cli-configure-sso.html)

The Android 2.44 app checks the account when a new standalone installation claims its first free greenhouse. Deploy and verify the updated API before distributing the app to new customers. An already verified free greenhouse can collect offline. When a customer reduces capacity, Settings and the Dashboard show the saved pause date. The phone can use the last verified schedule for its final-day notification while offline. Paused greenhouses keep their floor plans and saved readings. On either phone, History → time range → **Export saved readings** writes the selected greenhouse’s locally available observations to CSV.
