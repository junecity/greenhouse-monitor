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

The Android WebRTC peer, signaling client, HTTPS cloud gateway, encrypted token/config store, Cognito onboarding/token refresh, account-scoped QR pairing, device inventory/revocation gateway, AWS alert polling, local high-priority notification, and alert acknowledgement paths are present. The backend contains authorized Kinesis channel creation, short-lived signed WSS endpoints, ICE/TURN retrieval, server polling, session termination, device revocation, alert persistence, and acknowledgement. Google Play subscription verification, placement of account-administration controls outside the frozen monitoring screens, and live two-phone WebRTC validation remain incomplete. Keep the legacy local camera path available until the AWS path passes physical-device tests.

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
