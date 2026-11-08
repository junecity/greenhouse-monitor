# Greenhouse Monitor private admin portal

## Status

The development portal, administrator API, identity stack, and private static hosting are deployed in `us-east-1`. Google Play credentials, production billing, and a custom private DNS name are not configured.

- Portal: `https://d3a7uf1u0z93pt.cloudfront.net`
- Admin API: `https://oks4jq5hk3.execute-api.us-east-1.amazonaws.com/dev`
- Stack: `greenhouse-monitor-admin-dev`

The first Owner is enabled and has received Cognito's temporary-password email. The Owner must set a permanent password and enroll a TOTP authenticator during the first sign-in; no password is stored in source or deployment documentation.

The approved Android Dashboard, Zones, History, Cameras, Settings, and bottom navigation were not changed.

## Architecture

```text
Administrator browser
  -> private CloudFront/S3 Next.js static portal
  -> dedicated Cognito admin pool (authorization-code + PKCE, MFA required)
  -> dedicated HTTP API JWT authorizer
  -> admin Lambda (server-side Owner/Support/ReadOnly enforcement)
  -> existing Greenhouse Monitor DynamoDB records and customer Cognito pool
  -> CloudWatch alarm state / tag-filtered Cost Explorer data
```

The browser receives short-lived Cognito tokens but never AWS credentials. DynamoDB, customer Cognito administration, Cost Explorer, CloudWatch, Secrets Manager, and Google Play verification are available only to the admin Lambda's least-privilege execution role.

The admin portal has no customer registration path. The admin user pool disables public sign-up, requires a 14-character password and software-token MFA, and records successful authentication through a Cognito PostAuthentication trigger. API Gateway validates the dedicated admin issuer and audience before Lambda code runs. Lambda then requires a recognized `cognito:groups` role and rejects expired sessions.

## Implemented capabilities

- Real-data overview metrics for customers, entitlements, server heartbeat state, sensor counts, and active camera-session metadata.
- Real-data trend series with explicit empty states instead of fabricated values.
- Searchable customer directory and detail view for greenhouses, devices, entitlement, and recent alerts.
- Owner-only account suspension/reactivation, secure recovery initiation, complimentary Premium grants with optional expiry, revocation, and Google Play reverification.
- Owner/Support device-session revocation. Read-only users cannot mutate state.
- Subscription source, verification, renewal/expiry, complimentary, and error visibility.
- Device heartbeat, app version, sensor provider/count, camera status, and recent error visibility when clients report those fields.
- Streaming operations metadata without video access or recording.
- CloudWatch alarm state and tag-filtered actual Cost Explorer totals; month-end estimates are labeled daily-run-rate estimates.
- Audit events for administrator login, state changes, Premium changes, recovery, device revocation, and denied in-Lambda authorization attempts.
- Responsive desktop/mobile layouts, reduced-motion support, loading/empty/error states, HTTPS-only hosting, strict response headers, API throttling, and no public administrator registration.

## Roles

| Capability | Owner | Support | Read-only |
|---|---:|---:|---:|
| View operational/customer/device/subscription data | Yes | Yes | Yes |
| Revoke a compromised device session | Yes | Yes | No |
| Grant/revoke complimentary Premium | Yes | No | No |
| Suspend/reactivate a customer | Yes | No | No |
| Initiate account recovery | Yes | No | No |
| Reverify a stored Google Play purchase | Yes | No | No |

The interface hides unavailable controls, but the Lambda independently enforces every permission. Interface filtering is not a security boundary.

## Data model and migration

New account, entitlement, greenhouse, device, and stream-session records include an `adminEntity`/`adminSort` projection for the `admin` DynamoDB GSI. Existing records must be backfilled only after the main application stack adds that index:

```bash
python scripts/backfill-admin-index.py \
  --table-name <application-table> \
  --region <region>

# Review the eligible count, then explicitly apply:
python scripts/backfill-admin-index.py \
  --table-name <application-table> \
  --region <region> \
  --apply
```

Dry-run is the default. Pairing challenges, alert messages, tokens, and secrets are not copied into the admin index. Existing accounts receive their subject/profile projection on their next authenticated app bootstrap; the portal never exposes authentication tokens, purchase tokens, camera keys, or passwords.

## Local verification

```bash
python -m pip install -r backend/requirements-dev.txt -r backend/src/requirements.txt
python -m unittest discover -s backend/tests -v
python -m py_compile backend/src/*.py scripts/backfill-admin-index.py
cfn-lint infrastructure/template.yaml infrastructure/admin-portal.yaml

cd admin-portal
npm ci
npm test
npm run lint
npm run build
npm audit --audit-level=moderate
```

The portal builds without deployment values and shows a configuration-required screen. Configure only the non-secret `NEXT_PUBLIC_*` values from `.env.example` for a deployed build. Never add AWS credentials, customer tokens, or Google service-account JSON to frontend environment variables.

`NEXT_PUBLIC_COGNITO_HOSTED_UI_BASE_URL` is required in addition to the issuer. The portal supplies Cognito's stack-owned OAuth endpoints directly because the Cognito issuer discovery response is not browser-CORS enabled; the flow remains authorization-code + PKCE.

## Deployment and promotion checklist

1. Create a change set for `infrastructure/template.yaml` to add the admin GSI and product/environment cost-allocation tags. Review it; do not deploy blindly.
2. After the index is `ACTIVE`, run the backfill script in dry-run mode, review counts, then run it once with `--apply`.
3. Choose separate development, staging, and production admin stack names, hosted-login domain prefixes, callback/logout URLs, and portal origins.
4. Request/validate an ACM certificate in `us-east-1` for the private admin hostname if using a custom CloudFront alias.
5. Run `sam validate`, `sam build`, `cfn-lint`, and create a change set from `infrastructure/admin-portal.yaml`.
6. Review IAM, Cognito Plus, CloudFront, S3, API Gateway, Lambda, and CloudWatch resources and estimated charges before executing the change set.
7. Create the first owner with Cognito `AdminCreateUser`; do not enable self-registration. Add only that account to the `Owner` group. Do not hardcode an email in source or infrastructure. **Completed for development.**
8. Complete the temporary-password and TOTP enrollment flow. Verify that a token without the Owner/Support/ReadOnly group is denied. **Owner action still required.**
9. Build `admin-portal/` with the stack's API, authority, client, callback, and logout outputs. Upload `admin-portal/out/` to the emitted private S3 bucket and invalidate the CloudFront distribution.
10. Point the approved private DNS hostname to CloudFront. Test HTTPS, CSP, sign-in, MFA, sign-out, mobile navigation, each role, destructive confirmations, audit records, rate limits, and expired sessions.
11. Keep `GooglePlayCredentialsSecretArn` blank until production Play Billing and a least-privilege Play service account are separately approved. When approved, store the JSON only in Secrets Manager and test with a Play license-test purchase.
12. Activate the `Product=GreenhouseMonitor` cost-allocation tag, set an AWS Budget and cost-anomaly monitor, and verify the dashboard distinguishes actual billing from estimates.
13. Validate rollback: retain the admin user pool/bucket, disable the CloudFront distribution/API stage, and revert the application-stack change set without deleting customer records.

Example owner creation after an approved deployment:

```bash
aws cognito-idp admin-create-user \
  --user-pool-id <admin-user-pool-id> \
  --username <authorized-owner-email> \
  --user-attributes Name=email,Value=<authorized-owner-email> Name=email_verified,Value=true

aws cognito-idp admin-add-user-to-group \
  --user-pool-id <admin-user-pool-id> \
  --username <authorized-owner-email> \
  --group-name Owner
```

## Cost planning

Prices change and the AWS Pricing Calculator should be used before deployment. For one owner with light usage, a reasonable planning range for the **admin portal only** is approximately **$0.05–$1.00 per month**, excluding the customer Android backend, Kinesis/TURN streaming, domain registration, taxes, and traffic that consumes shared account free-tier allowances.

Assumptions:

- One Cognito Plus administrator MAU: about $0.02/month; Plus has no MAU free tier.
- Fewer than 10,000 admin API calls/month: roughly one cent at the published HTTP API per-million request scale, before shared free tiers.
- Lambda, DynamoDB on-demand, S3 static files, CloudFront traffic/functions, and logs remain very small; they are often within shared account free tiers but must not be assumed free.
- CloudFront's published free tier is shared across the account/organization. A custom domain/hosted zone and optional WAF add separate charges.
- The portal's Cost Explorer query filters the `Product=GreenhouseMonitor` tag and labels the projected month-end amount as an estimate. Cost-allocation tags can take time to appear and must be activated in Billing.

Authoritative pricing references:

- https://aws.amazon.com/cognito/pricing/
- https://aws.amazon.com/api-gateway/pricing/
- https://aws.amazon.com/cloudfront/pricing/
- https://aws.amazon.com/lambda/pricing/
- https://aws.amazon.com/dynamodb/pricing/

## Live verification and remaining external integrations

- CloudFront/S3 delivery, security headers, Cognito hosted-login redirect with PKCE, unauthenticated API denial, admin Lambda dashboard execution, Cost Explorer access, and application Lambda import/runtime were live-tested in the development stack.
- A full authenticated JWT request, permanent-password change, TOTP enrollment, and portal mutations remain pending the Owner's first sign-in.
- Google Play verification code is unit-tested against response contracts but has not used a real service account or purchase token.
- The admin GSI is active; the backfill dry run found zero eligible records, so no migration writes were required.
- Physical mobile-browser validation remains pending.
- Camera video is intentionally inaccessible; only session metadata is exposed.
