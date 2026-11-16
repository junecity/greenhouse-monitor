#!/usr/bin/env python3
"""Publish a verified Greenhouse Studio APK, then advertise its version to clients."""
import argparse
import base64
import hashlib
import json
import re
import subprocess
import tempfile
from pathlib import Path


def aws(*args):
    result = subprocess.run(["aws", *args, "--output", "json"], text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError(result.stderr.strip())
    return json.loads(result.stdout or "{}")


def get_latest(bucket, key, dest):
    try:
        result = aws("s3api", "get-object", "--bucket", bucket, "--key", key, str(dest))
    except RuntimeError as error:
        if "(NoSuchKey)" in str(error):
            return None, None
        raise
    return json.loads(dest.read_bytes()), result["ETag"]


def publish(apk, stack_name, signer):
    manifest = subprocess.check_output(["aapt", "dump", "badging", str(apk)], text=True)
    pkg = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", manifest, re.M)
    if not pkg or pkg[1] != "studio.junecity.greenhouse" or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9.+_-]{0,39}", pkg[3]):
        raise ValueError("APK package or version is invalid")
    cert = subprocess.check_output(["apksigner", "verify", "--print-certs", str(apk)], text=True)
    certs = re.findall(r"^Signer #\d+ certificate SHA-256 digest: (\w+)$", cert, re.M)
    if certs != [signer.lower()]:
        raise ValueError("APK signing certificate differs from the supplied trusted fingerprint")
    size = apk.stat().st_size
    if not 0 < size <= 180 * 1024 * 1024:
        raise ValueError("APK exceeds remote installer size limit")
    digest = hashlib.sha256()
    with apk.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    checksum = base64.b64encode(digest.digest()).decode()
    release = {"versionCode": int(pkg[2]), "versionName": pkg[3]}
    stack = aws("cloudformation", "describe-stacks", "--stack-name", stack_name)["Stacks"][0]
    params = {p["ParameterKey"]: p["ParameterValue"] for p in stack["Parameters"]}
    bucket = params["ServerReleaseBucketName"]
    env = params["EnvironmentName"]
    if not bucket or stack["StackName"] != f"greenhouse-monitor-{env}":
        raise ValueError("Select a Greenhouse Monitor application stack configured for remote releases")
    prefix = f"releases/{env}/android/"
    key = f"{prefix}{release['versionCode']}.apk"
    with tempfile.TemporaryDirectory() as tmp:
        pointer = Path(tmp, "latest.json")
        current, etag = get_latest(bucket, prefix + "latest.json", pointer)
        if current and current["versionCode"] >= release["versionCode"] and current != release:
            raise ValueError("The catalog already advertises an equal or higher version code")
        try:
            aws("s3api", "put-object", "--bucket", bucket, "--key", key, "--body", str(apk),
                "--content-type", "application/vnd.android.package-archive", "--if-none-match", "*",
                "--checksum-algorithm", "SHA256", "--checksum-sha256", checksum,
                "--metadata", "sha256=" + digest.hexdigest())
        except RuntimeError as error:
            if "(PreconditionFailed)" not in str(error):
                raise
        obj = aws("s3api", "head-object", "--bucket", bucket, "--key", key, "--checksum-mode", "ENABLED")
        if (obj.get("ChecksumSHA256") != checksum or obj["ContentLength"] != size
                or obj["Metadata"].get("sha256") != digest.hexdigest()):
            raise ValueError("Published object differs from this APK; use a new build number")
        if current != release:
            pointer.write_text(json.dumps(release))
            guard = ["--if-match", etag] if etag else ["--if-none-match", "*"]
            aws("s3api", "put-object", "--bucket", bucket, "--key", prefix + "latest.json",
                "--body", str(pointer), "--content-type", "application/json", "--cache-control", "no-store", *guard)
        print(f"{env} release {pkg[3]} (build {pkg[2]}) verified and published; SHA-256 {digest.hexdigest()}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--stack", required=True)
    parser.add_argument("--signer-sha256", required=True, help="trusted installed app signing certificate fingerprint")
    args = parser.parse_args()
    publish(args.apk, args.stack, args.signer_sha256)
