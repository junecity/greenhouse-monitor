"""Opt-in AWS media check; creates/deletes one dedicated diagnostic signaling channel.

Requires an authenticated AWS CLI session, boto3, and the debug app + androidTest APK
installed on a camera-capable emulator/device. It incurs brief signaling/TURN usage.
No customer data is used. Signed URLs and TURN credentials are never printed.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--region", default="us-east-1")
    parser.add_argument("--relay-only", action="store_true")
    args = parser.parse_args()
    credentials = json.loads(subprocess.check_output([
        "aws", "configure", "export-credentials", "--format", "process"
    ]))
    os.environ.update(
        AWS_ACCESS_KEY_ID=credentials["AccessKeyId"],
        AWS_SECRET_ACCESS_KEY=credentials["SecretAccessKey"],
        AWS_SESSION_TOKEN=credentials.get("SessionToken", ""),
        AWS_REGION=args.region, AWS_DEFAULT_REGION=args.region,
    )
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "backend" / "src"))
    import kinesis_session as ks

    package = "studio.junecity.greenhouse"
    channel = None
    try:
        channel = ks.kinesis.create_signaling_channel(
            ChannelName="greenhouse-dev-diagnostic-" + uuid.uuid4().hex[:12],
            ChannelType="SINGLE_MASTER",
            Tags=[{"Key": "Product", "Value": "GreenhouseMonitor"}, {"Key": "Environment", "Value": "dev"}],
        )["ChannelARN"]
        common = dict(
            id="diagnostic-session", greenhouseId="diagnostic-greenhouse",
            serverDeviceId="diagnostic-server", viewerDeviceId="diagnostic-viewer",
            signalingChannelArn=channel, region=args.region,
            expiresAtEpochMs=int(time.time() * 1000) + 240000,
        )
        fixture = {
            "viewer": dict(common, **ks.connection_config(channel, "VIEWER", "diagnostic-viewer"), peerClientId=None),
            "master": dict(common, **ks.connection_config(channel, "MASTER", None), peerClientId="diagnostic-viewer"),
        }
        subprocess.run([args.adb, "shell", "run-as", package, "mkdir", "-p", "files"], check=True)
        subprocess.run([
            args.adb, "shell", f"run-as {package} sh -c 'cat > files/live-stream-test.json'"
        ], input=json.dumps(fixture).encode(), check=True)
        result = subprocess.run([
            args.adb, "shell", "am", "instrument", "-w", "-e", "class",
            package + ".streaming.LibWebRtcPeerInstrumentedTest#liveKinesisDeliversDecodedCameraFrames",
            "-e", "liveCloudMedia", "true", "-e", "relayOnly", str(args.relay_only).lower(),
            package + ".test/androidx.test.runner.AndroidJUnitRunner",
        ], capture_output=True, text=True, timeout=130)
        print(result.stdout)
        if result.returncode or "OK (1 test)" not in result.stdout:
            raise RuntimeError("Live media check failed")
    finally:
        subprocess.run([args.adb, "shell", "run-as", package, "rm", "-f", "files/live-stream-test.json"], capture_output=True)
        if channel:
            ks.kinesis.delete_signaling_channel(ChannelARN=channel)
            print("Disposable signaling channel removed")


if __name__ == "__main__":
    main()
