#!/usr/bin/env python3
"""Exercise the archive API and Android MP4 paths on two disposable emulators with local AWS and Play fixtures.

Install the app and androidTest APKs on both emulators first. Requires adb and Python with moto[server], boto3,
cryptography and the backend requirements. No external credentials or services are used.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import base64
import hashlib
import ipaddress
import json
import logging
import os
import re
import uuid
import subprocess
import sys
import tempfile
import threading
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch

import boto3
import requests
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID
from flask import Flask, request
from moto.server import ThreadedMotoServer, create_backend_app
from werkzeug.serving import make_server


def tls_certificate(directory):
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'localhost')])
    now = datetime.now(timezone.utc)
    cert = (x509.CertificateBuilder().issuer_name(name).subject_name(name).public_key(key.public_key())
            .serial_number(x509.random_serial_number()).not_valid_before(now - timedelta(minutes=5))
            .not_valid_after(now + timedelta(hours=2))
            .add_extension(x509.SubjectAlternativeName([x509.DNSName('localhost'),
                           x509.IPAddress(ipaddress.ip_address('127.0.0.1'))]), critical=False)
            .sign(key, hashes.SHA256()))
    cert_path, key_path = Path(directory, 'cert.pem'), Path(directory, 'key.pem')
    cert_path.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    key_path.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                          serialization.NoEncryption()))
    return (str(cert_path), str(key_path)), base64.b64encode(cert.public_bytes(serialization.Encoding.DER)).decode()


def seed():
    table = boto3.resource('dynamodb').create_table(
        TableName='camera-smoke', BillingMode='PAY_PER_REQUEST',
        KeySchema=[{'AttributeName': 'pk', 'KeyType': 'HASH'}, {'AttributeName': 'sk', 'KeyType': 'RANGE'}],
        AttributeDefinitions=[{'AttributeName': k, 'AttributeType': 'S'} for k in ['pk', 'sk']])
    owner = {'tenantId': 'fixture-tenant', 'accountId': 'fixture-account'}
    for key, sk, attributes in [
        ('USER#local-viewer', 'ACCOUNT', {}),
        ('ACCOUNT#fixture-account', 'METADATA', {'state': 'ACTIVE'}),
        ('GREENHOUSE#archive-qa-house', 'METADATA', {'serverDeviceId': 'fixture-server'}),
        ('DEVICE#fixture-viewer', 'METADATA', {'enabled': True, 'role': 'VIEWER_CLIENT'}),
        ('DEVICE#fixture-server', 'METADATA', {'enabled': True, 'role': 'GREENHOUSE_SERVER',
         'greenhouseId': 'archive-qa-house',
         'deviceSecretHash': hashlib.sha256(b'local-fixture-server-credential-for-qa').hexdigest()})]:
        table.put_item(Item={**owner, **attributes, 'pk': key, 'sk': sk})


def exercise_orders(port, certificate_path, root, ffmpeg):
    """Real HTTP API, S3 PUT/GET and MP4 decode. Only identities and the Play receipt are fixtures."""
    viewer = {"authorization": "Bearer local-viewer", "x-greenhouse-device-id": "fixture-viewer"}
    house_a, house_b = "archive-qa-house", "second-qa-house"
    device_a, device_b = "fixture-server", "second-server"
    secret_a, secret_b = "local-fixture-server-credential-for-qa", "second-fixture-server-credential-for-qa"
    servers = {house_a: (device_a, secret_a), house_b: (device_b, secret_b)}
    session = requests.Session()
    session.verify = certificate_path
    session.trust_env = False

    def call(path, body=None, *, headers=viewer, expected=200):
        reply = session.request("GET" if body is None else "POST", f"https://localhost:{port}/v1/"+path,
                                json=body, headers=headers, timeout=15)
        assert reply.status_code == expected, (path, reply.status_code, reply.text)
        return reply.json() if reply.content else {}

    def dh(house):
        device, secret = servers[house]
        return {"x-greenhouse-device-id": device, "x-greenhouse-device-secret": secret}

    def archive(house, action, body=None, *, collector=None, expected=200):
        prefix = "device/" if collector else ""
        return call(prefix+f"greenhouses/{house}/recordings/{action}", body,
                    headers=dh(collector) if collector else viewer, expected=expected)

    def heartbeat(house, *, ready=True, hinted=False):
        path = f"device/devices/{servers[house][0]}/heartbeat"
        payload = {"appVersionCode": 92, "remoteRecordingReady": ready, "sensorConnectionHealthy": True}
        result = call(path, payload, headers=dh(house), expected=200 if hinted else 204)
        assert bool(result.get("remoteRecordingPending")) == hinted

    def receipt(product, token, *, account_binding=None, require_test_purchase=True):
        binding = hashlib.sha256(b'greenhouse-play:fixture-account').hexdigest()
        assert require_test_purchase and account_binding in (None, binding)
        if token == "fixture-paid-extra-greenhouse":
            assert product == "greenhouse_extra"
            actual = "greenhouse_extra_1"
        else:
            assert token == "local-play-test-purchase" and product == "camera_storage_monthly"
            actual = product
        return {"active": True, "accountBinding": binding, "productId": actual, "testPurchase": True,
                "startedAt": int(time.time())-300, "expiresAt": int(time.time())+3600, "renews": True}

    try:
        # Stage one legacy greenhouse in the real account directory. Enroll the second
        # server with the current public challenge and viewer approval after purchase.
        db = boto3.resource("dynamodb").Table("camera-smoke")
        db.put_item(Item={"pk": "ACCOUNT#fixture-account", "sk": "DEVICE#"+device_a,
                          "enabled": True, "accountId":"fixture-account", "tenantId":"fixture-tenant",
                          "greenhouseId": house_a, "deviceId": device_a, "role":"GREENHOUSE_SERVER", "createdAt": 1})
        with patch("greenhouse_plan.verify_google_play_purchase", side_effect=receipt), \
             patch("camera_archive.verify_google_play_purchase", side_effect=receipt):
            first = call("greenhouse-plan")
            assert len(first["houses"]) == 1 and first["houses"][0]["free"] and first["extraSlots"] == 0
            invite = {"greenhouseId":house_b, "greenhouseName":"West"}
            assert call("server-pairing/invitations", invite, expected=409)["error"] == "greenhouse_plan_required"
            plan = call("greenhouse-plan/verify", {"productId":"greenhouse_extra_1", "purchaseToken":"fixture-paid-extra-greenhouse"})
            assert plan["extraSlots"] == 1
            code = call("server-pairing/invitations", invite, expected=201)["displayCode"]
            request = call("public-server-pairing/requests", {"serverDeviceId":device_b, "deviceSecret":secret_b,
                                                              "pairingCode":code}, headers={}, expected=202)
            call(f"server-pairing/requests/{request['requestId']}/approve", {})
            assert {r["serverGreenhouseId"] for r in call("greenhouse-plan")["houses"] if r["active"]} == set(servers)
            print("One free server and one paid server enrolled; additional access required at pairing.", flush=True)
            heartbeat(house_a)
            assert not archive(house_a, "access")["active"]
            archive(house_a, "record", {"requestId":str(uuid.uuid4())}, expected=403)
            archive(house_a, "verify", {"purchaseToken":"local-play-test-purchase"})
            assert not archive(house_b, "access")["remoteRecordingAvailable"]
            archive(house_b, "record", {"requestId":str(uuid.uuid4())}, expected=409)
            heartbeat(house_b)
            # The same UUID in two houses must produce house-scoped jobs and objects.
            request_id = str(uuid.uuid4())
            cmd = {"requestId":request_id}
            for house in servers:
                assert archive(house, "access")["remoteRecordingAvailable"]
                for _ in range(2):
                    assert archive(house,"record",cmd)["status"] == "queued"
                heartbeat(house, hinted=True)
            archive(house_a, "poll-record", {}, collector=house_b, expected=403)
            print("Separate storage purchase, unavailable-camera state, retries and server isolation verified.", flush=True)
            for house, color in ((house_a,"green"),(house_b,"blue")):
                assert archive(house, "poll-record", {}, collector=house) == cmd
                assert archive(house, "poll-record", {}, collector=house) == {}
                assert archive(house, "record-status", cmd)["status"] == "recording"
                filename = Path(root, house+".mp4")
                subprocess.run([ffmpeg, "-loglevel","error","-f","lavfi","-i",f"color=c={color}:s=96x64:r=5",
                                "-t","60","-an","-c:v","mpeg4","-movflags","+faststart",str(filename)], check=True, timeout=45)
                data = filename.read_bytes()
                md5 = base64.b64encode(hashlib.md5(data).digest()).decode()
                metadata = dict(cmd, bytes=len(data), md5=md5)
                url = archive(house,"upload",metadata,collector=house)["url"]
                put = session.put(url, data=data, headers={"Content-Type":"video/mp4","Content-MD5":md5}, timeout=20)
                put.raise_for_status()
                assert archive(house,"upload",metadata,collector=house) == {"alreadyUploaded":True}
                assert archive(house,"finish-record",dict(cmd,status="uploaded"),collector=house)["status"] == "uploaded"
                assert archive(house,"record-status",cmd)["status"] == "uploaded"
                clip, = archive(house,"list")["clips"]
                url = archive(house,"play",{"id":clip["id"]})["url"]
                download = session.get(url, timeout=20)
                download.raise_for_status()
                assert download.content == data
                subprocess.run([ffmpeg,"-v","error","-xerror","-i","pipe:0","-f","null","-"],
                               input=download.content, check=True, timeout=30)
                assert archive(house,"delete",{"id":clip["id"]})["deleted"]
                assert archive(house,"list")["clips"] == []
            quota = db.get_item(Key={"pk":"ACCOUNT#fixture-account","sk":f"CAMERA_UPLOADS#{int(time.time())//86400}"})["Item"]
            assert quota["requests"] == 2
            assert boto3.client("s3").list_objects_v2(Bucket="archive-smoke")["KeyCount"] == 0
            print("Two distinct one-minute clips uploaded, retried, downloaded, decoded and deleted; two quota reservations.", flush=True)
    finally:
        session.close()


def exercise(args, root, database_endpoint):
    cert_paths, certificate = tls_certificate(root)
    os.environ.update(AWS_DEFAULT_REGION='us-east-1', AWS_ACCESS_KEY_ID='fixture', AWS_SECRET_ACCESS_KEY='fixture',
                      AWS_EC2_METADATA_DISABLED='true', AWS_ENDPOINT_URL=database_endpoint, AWS_CA_BUNDLE=cert_paths[0],
                      TABLE_NAME='camera-smoke', CAMERA_ARCHIVE_BUCKET='archive-smoke', CAMERA_ARCHIVE_SALES_MODE='LicenseTest',
                      PLAY_TEST_ACCOUNT_IDS='fixture-account', GOOGLE_PLAY_CREDENTIALS_SECRET_ID='fixture',
                      GREENHOUSE_PLAN_SALES_MODE='LicenseTest', GOOGLE_PLAY_PACKAGE_NAME='studio.junecity.greenhouse')
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'backend' / 'src'))
    import app
    api = Flask(__name__)

    @api.route('/v1/<path:route>', methods=['GET', 'POST'])
    def call_api(route):
        claims = {'sub': 'local-viewer'} if request.headers.get('Authorization') == 'Bearer local-viewer' else {}
        event = {'rawPath': '/v1/' + route, 'headers': {k.lower(): v for k, v in request.headers.items()},
                 'body': request.get_data(as_text=True),
                 'requestContext': {'http': {'method': request.method}, 'authorizer': {'jwt': {'claims': claims}}}}
        reply = app.handler(event, None)
        return reply.get('body', ''), reply['statusCode'], {'Content-Type': 'application/json'}

    s3 = create_backend_app('s3')

    def route(environ, start_response):
        handler = api if environ['PATH_INFO'].startswith('/v1/') else s3
        return handler(environ, start_response)

    def play(product, token, *, account_binding, require_test_purchase):
        assert product == 'camera_storage_monthly' and token == 'local-play-test-purchase' and require_test_purchase
        return {'active': True, 'accountBinding': account_binding, 'testPurchase': True, 'expiresAt': int(time.time()) + 3600}

    with make_server('127.0.0.1', 0, route, threaded=True, ssl_context=cert_paths) as gateway:
        port = str(gateway.server_port)
        os.environ['AWS_ENDPOINT_URL_S3'] = f'https://localhost:{port}'
        thread = threading.Thread(target=gateway.serve_forever, daemon=True)
        thread.start()
        forwards = []
        try:
            seed()
            boto3.client('s3').create_bucket(Bucket='archive-smoke')

            if args.orders_only:
                exercise_orders(port, cert_paths[0], root, args.ffmpeg)
                return

            def adb(device, *options):
                return subprocess.check_output([args.adb, '-s', device, *options], text=True, timeout=180).strip()

            for device in (args.server, args.viewer):
                if adb(device, 'shell', 'getprop', 'ro.kernel.qemu') != '1':
                    raise ValueError('Use disposable emulators for the local fixture')
                adb(device, 'reverse', f'tcp:{port}', f'tcp:{port}')
                forwards.append(device)

            def run(device, method, **extras):
                instrument = ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                              'studio.junecity.greenhouse.ui.CloudArchiveDeviceTest#' + method]
                for key, value in dict(archiveFixturePort=port, archiveFixtureCert=certificate, **extras).items():
                    instrument.extend(['-e', key, value])
                output = adb(device, *instrument, 'studio.junecity.greenhouse.test/androidx.test.runner.AndroidJUnitRunner')
                if 'OK (1 test)' not in output:
                    raise RuntimeError(output)
                print(method, 'passed', flush=True)
                return output

            with patch('camera_archive.verify_google_play_purchase', side_effect=play):
                run(args.viewer, 'authorizeViewerWithFixturePurchase')
                if args.remote_order:
                    db = boto3.resource('dynamodb').Table('camera-smoke')
                    db.put_item(Item={'pk':'ACCOUNT#fixture-account', 'sk':'DEVICE#fixture-server',
                                      'accountId':'fixture-account', 'tenantId':'fixture-tenant', 'enabled':True,
                                      'greenhouseId':'archive-qa-house', 'deviceId':'fixture-server', 'role':'GREENHOUSE_SERVER'})
                    package = 'studio.junecity.greenhouse'
                    adb(args.server, 'shell', 'pm', 'grant', package, 'android.permission.CAMERA')
                    adb(args.server, 'shell', 'pm', 'grant', package, 'android.permission.POST_NOTIFICATIONS')
                    airplane = adb(args.server, 'shell', 'settings', 'get', 'global', 'airplane_mode_on')
                    wifi = adb(args.server, 'shell', 'settings', 'get', 'global', 'wifi_on')
                    adb(args.server, 'shell', 'cmd', 'connectivity', 'airplane-mode', 'enable')
                    adb(args.server, 'shell', 'svc', 'wifi', 'disable')
                    try:
                        with ThreadPoolExecutor(max_workers=1) as pool:
                            server = pool.submit(run, args.server, 'captureRemoteOrderOnServer')
                            ordered = run(args.viewer, 'requestRemoteClipOnViewer')
                            saved = server.result(timeout=150)
                    finally:
                        if airplane != '1':
                            adb(args.server, 'shell', 'cmd', 'connectivity', 'airplane-mode', 'disable')
                        if wifi in {'1', '2'}:
                            adb(args.server, 'shell', 'svc', 'wifi', 'enable')
                    order = re.search(r'INSTRUMENTATION_STATUS: remoteOrder=(\S+)', ordered).group(1)
                    clip = re.search(r'INSTRUMENTATION_STATUS: archiveClip=(\S+)', saved).group(1)
                    storage = boto3.client('s3')
                    stored, = storage.list_objects_v2(Bucket='archive-smoke')['Contents']
                    assert uuid.UUID(order).hex in stored['Key'] and clip in stored['Key']
                    content = storage.get_object(Bucket='archive-smoke', Key=stored['Key'])['Body'].read()
                    recorded = {'archiveClip': clip, 'archiveSha': hashlib.sha256(content).hexdigest(), 'remoteOrder': order}
                else:
                    saved = run(args.server, 'uploadOneMinuteOnServer')
                    recorded = dict(re.findall(r'INSTRUMENTATION_STATUS: (archiveClip|archiveSha)=(\S+)', saved))
                    assert set(recorded) == {'archiveClip', 'archiveSha'}, saved
                run(args.viewer, 'playAndDeleteOnViewer', **recorded)
            count = boto3.client('s3').list_objects_v2(Bucket='archive-smoke')['KeyCount']
            assert count == 0, f'{count} clips left after deletion'
        finally:
            for device in forwards:
                subprocess.run([args.adb, '-s', device, 'reverse', '--remove', f'tcp:{port}'], check=False)
            gateway.shutdown()
            thread.join()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--remote-order', action='store_true', help='Use a viewer recording order and a real front lens on two Android emulators')
    parser.add_argument('--orders-only', action='store_true', help='Run the two-house HTTP ordering and paid membership fixture')
    parser.add_argument('--ffmpeg', default='ffmpeg', help='Executable used to generate and fully decode real MP4s in --orders-only mode')
    parser.add_argument('--server')
    parser.add_argument('--viewer')
    parser.add_argument('--adb', default='adb')
    args = parser.parse_args()
    if not args.orders_only and (not args.server or not args.viewer or args.server == args.viewer):
        parser.error('Choose two separate emulators')
    logging.getLogger('werkzeug').setLevel(logging.ERROR)
    database = ThreadedMotoServer(ip_address='127.0.0.1', port=0, verbose=False)
    database.start()
    try:
        with tempfile.TemporaryDirectory() as root:
            exercise(args, root, 'http://127.0.0.1:' + str(database.get_host_and_port()[1]))
    finally:
        database.stop()
