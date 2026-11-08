# AWS deployment handoff

No AWS resources have been deployed from this repository. The SAM template is a dedicated Greenhouse Monitor foundation and has no dependency on junecity.studio.

## Prepared resources

- Cognito Plus user pool with verified email, optional authenticator-app MFA, threat protection, token revocation, short access/ID tokens, and deletion protection.
- JWT-authorized API Gateway HTTP API with access logging.
- Two reserved-concurrency Lambda functions for tenant APIs and scheduled heartbeat monitoring.
- Encrypted, point-in-time-recoverable, on-demand DynamoDB table with TTL and a sharded heartbeat index.
- Encrypted SNS internal critical-alert topic and one-minute EventBridge schedule.

The Android WebRTC peer, signaling client, HTTPS cloud gateway, and encrypted token/config store are present. The backend contains account-scoped pairing plus authorized Kinesis channel creation, short-lived signed WSS endpoints, ICE/TURN retrieval, server polling, and session termination, with the corresponding undeployed IAM actions. Cognito onboarding/token refresh, subscription verification, end-user push delivery, and alert acknowledgement remain incomplete. Do not connect the approved camera screen to this backend until the stack is validated in development and physical-device tests pass.

## Approval checklist before deployment

1. Select an AWS account and a Kinesis WebRTC-supported Region dedicated to Greenhouse Monitor.
2. Review Cognito Plus pricing. Threat protection requires Plus; change this only through a documented security decision.
3. Establish separate `dev`, `staging`, and `production` stacks, budgets, cost-anomaly alerts, and resource tags.
4. Create a least-privilege deployment role; do not use an administrator credential in CI or the Android app.
5. Install AWS SAM CLI and run `sam validate --lint -t infrastructure/template.yaml` with the intended account and Region. SAM validation was not available in the current environment.
6. Add backend integration tests against an isolated local/emulated or dev table, including conditional writes, pagination, throttling, and tenant denial.
7. Validate server-side Kinesis allocation, endpoint signing, ICE retrieval, and termination; then add production quotas, metrics, and audit retention.
8. Validate the implemented single-use hashed pairing flow and add device-list/revocation endpoints.
9. Configure Google Play products only after approval. Verify purchase tokens through the Play Developer API and process Real-time Developer Notifications; never accept a client entitlement flag.
10. Configure FCM/APNs-style customer delivery separately from the internal SNS topic, with device-token rotation and tenant-scoped acknowledgement.
11. Run the physical test matrix on the Galaxy A71 and Pixel 10 across same-LAN, separate Wi-Fi, cellular, TURN-only, public-IP change, lock/background, server restart, and AWS failure conditions.
12. Confirm rollback: leave the encrypted JPEG/local path enabled until WebRTC passes; keep UPnP disabled unless explicitly needed for the legacy test path.

## Deployment commands after approval

Copy `infrastructure/samconfig.example.toml` to an environment-specific file and review every value. Then:

```text
sam validate --lint -t infrastructure/template.yaml
sam build -t infrastructure/template.yaml
sam deploy --guided --config-file infrastructure/samconfig.<environment>.toml
```

After deployment, record stack outputs in an encrypted CI secret/configuration system. The Android build may receive non-secret identifiers such as Region, API base URL, user-pool ID, and app-client ID. It must never receive AWS access keys, Play service-account credentials, or customer tokens at build time.

## Cost model (US East reference, verify for the deployment Region)

The design is usage-based, but it is not zero-cost:

- Kinesis WebRTC is charged by active signaling channel, signaling messages, and TURN relay minutes. AWS's current US-East example uses **$0.03 per active channel-month**, **$2.25 per million signaling messages**, and **$0.12 per 1,000 TURN minutes**, plus applicable internet data transfer. Direct peer-to-peer media avoids TURN-minute charges.
- One server heartbeat per minute is about 43,200 API calls, Lambda invocations, and DynamoDB writes per 30-day month. A one-minute scheduled monitor adds another 43,200 Lambda invocations and about 691,200 sharded index queries before retries/pagination.
- Cognito Plus has no MAU free tier; the template deliberately selects Plus because enforced threat protection requires it.
- API Gateway, Lambda, DynamoDB on-demand, CloudWatch Logs/X-Ray, SNS, KMS requests, and data transfer add usage-based charges. Email/SMS/voice escalation is not included and would add provider charges.

For planning, calculate at least three scenarios (pilot, 1,000 customers, peak incident) in the AWS Pricing Calculator using measured TURN percentage, session duration, heartbeat volume, authentication MAUs, log retention, and data transfer. Set budgets before creating the stack. Prices can change; the AWS pricing pages are authoritative.

## Rollback and teardown

- Android: disable the cloud feature flag and continue local Govee/history/camera operation.
- Streaming: deny new session creation, terminate active leases, and retain the legacy local camera path.
- Backend: deploy the preceding SAM revision. DynamoDB and identity data require an explicit, reviewed migration rather than automatic deletion.
- Stack teardown will not automatically remove the retained DynamoDB table, and Cognito deletion protection must be deliberately disabled first. Export or securely delete customer data according to the approved retention policy.
