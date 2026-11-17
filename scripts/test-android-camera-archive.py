#!/usr/bin/env python3
"""Exercise the archive API and Android MP4 paths on two disposable emulators with local AWS and Play fixtures.

Install the app and androidTest APKs on both emulators first. Requires adb and Python with moto[server], boto3,
cryptography and the backend requirements. No external credentials or services are used.
"""
import argparse
import base64
import hashlib
import ipaddress
import json
import logging
import os
import re
import subprocess
import sys
import tempfile
import threading
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch

import boto3
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


def exercise(args, root, database_endpoint):
    cert_paths, certificate = tls_certificate(root)
    os.environ.update(AWS_DEFAULT_REGION='us-east-1', AWS_ACCESS_KEY_ID='fixture', AWS_SECRET_ACCESS_KEY='fixture',
                      AWS_EC2_METADATA_DISABLED='true', AWS_ENDPOINT_URL=database_endpoint, AWS_CA_BUNDLE=cert_paths[0],
                      TABLE_NAME='camera-smoke', CAMERA_ARCHIVE_BUCKET='archive-smoke', CAMERA_ARCHIVE_SALES_MODE='LicenseTest',
                      PLAY_TEST_ACCOUNT_IDS='fixture-account', GOOGLE_PLAY_CREDENTIALS_SECRET_ID='fixture')
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
    parser.add_argument('--server', required=True)
    parser.add_argument('--viewer', required=True)
    parser.add_argument('--adb', default='adb')
    args = parser.parse_args()
    if args.server == args.viewer:
        parser.error('Choose two separate emulators')
    logging.getLogger('werkzeug').setLevel(logging.ERROR)
    database = ThreadedMotoServer(ip_address='127.0.0.1', port=0, verbose=False)
    database.start()
    try:
        with tempfile.TemporaryDirectory() as root:
            exercise(args, root, 'http://127.0.0.1:' + str(database.get_host_and_port()[1]))
    finally:
        database.stop()
