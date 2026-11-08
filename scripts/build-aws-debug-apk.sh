#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 || $# -gt 3 ]]; then
  echo "Usage: $0 <cloudformation-stack-name> <aws-region> [aws-profile]" >&2
  exit 2
fi

command -v aws >/dev/null || { echo "AWS CLI is required" >&2; exit 2; }
command -v java >/dev/null || { echo "JDK 17 is required" >&2; exit 2; }

stack_name="$1"
aws_region="$2"
aws_profile="${3:-${AWS_PROFILE:-}}"
project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

aws_args=(--region "$aws_region")
if [[ -n "$aws_profile" ]]; then
  aws_args+=(--profile "$aws_profile")
fi

stack_output() {
  local output_key="$1"
  aws cloudformation describe-stacks \
    --stack-name "$stack_name" \
    "${aws_args[@]}" \
    --query "Stacks[0].Outputs[?OutputKey=='${output_key}'].OutputValue | [0]" \
    --output text
}

api_url="$(stack_output ApiUrl)"
user_pool_id="$(stack_output UserPoolId)"
android_client_id="$(stack_output AndroidClientId)"

for value_name in api_url user_pool_id android_client_id; do
  value="${!value_name}"
  if [[ -z "$value" || "$value" == "None" ]]; then
    echo "Required CloudFormation output is missing: $value_name" >&2
    exit 1
  fi
done

cd "$project_root"
./gradlew --no-daemon assembleDebug \
  -PGREENHOUSE_AWS_REGION="$aws_region" \
  -PGREENHOUSE_COGNITO_USER_POOL_ID="$user_pool_id" \
  -PGREENHOUSE_COGNITO_APP_CLIENT_ID="$android_client_id" \
  -PGREENHOUSE_AWS_API_BASE_URL="$api_url"

source_apk="app/build/outputs/apk/debug/app-debug.apk"
output_dir="build-out"
output_apk="$output_dir/Greenhouse-Monitor-AWS-debug.apk"
mkdir -p "$output_dir"
cp "$source_apk" "$output_apk"

if command -v sha256sum >/dev/null; then
  sha256sum "$output_apk" > "$output_apk.sha256"
else
  shasum -a 256 "$output_apk" > "$output_apk.sha256"
fi

echo "AWS-configured debug APK: $project_root/$output_apk"
echo "Checksum: $project_root/$output_apk.sha256"
