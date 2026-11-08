import os
from urllib.parse import urlencode

import boto3
from botocore.auth import SigV4QueryAuth
from botocore.awsrequest import AWSRequest
from botocore.exceptions import ClientError

from domain import signaling_channel_name


REGION = os.environ.get("AWS_REGION", "us-east-1")
ENVIRONMENT = os.environ.get("ENVIRONMENT_NAME", "dev")
kinesis = boto3.client("kinesisvideo", region_name=REGION)


def ensure_channel(greenhouse_id: str) -> str:
    name = signaling_channel_name(ENVIRONMENT, greenhouse_id)
    try:
        return kinesis.describe_signaling_channel(ChannelName=name)["ChannelInfo"]["ChannelARN"]
    except ClientError as error:
        if error.response.get("Error", {}).get("Code") != "ResourceNotFoundException":
            raise
    try:
        return kinesis.create_signaling_channel(ChannelName=name, ChannelType="SINGLE_MASTER")["ChannelARN"]
    except ClientError as error:
        if error.response.get("Error", {}).get("Code") != "ResourceInUseException":
            raise
        return kinesis.describe_signaling_channel(ChannelName=name)["ChannelInfo"]["ChannelARN"]


def connection_config(channel_arn: str, role: str, client_id: str | None) -> dict:
    endpoints = kinesis.get_signaling_channel_endpoint(
        ChannelARN=channel_arn,
        SingleMasterChannelEndpointConfiguration={"Protocols": ["WSS", "HTTPS"], "Role": role},
    )["ResourceEndpointList"]
    by_protocol = {item["Protocol"]: item["ResourceEndpoint"] for item in endpoints}
    signaling = boto3.client("kinesis-video-signaling", region_name=REGION, endpoint_url=by_protocol["HTTPS"])
    ice_request = {"ChannelARN": channel_arn}
    if client_id:
        ice_request["ClientId"] = client_id
    ice_response = signaling.get_ice_server_config(**ice_request)
    ice_servers = [{"urls": [f"stun:stun.kinesisvideo.{REGION}.amazonaws.com:443"]}]
    ice_servers.extend({"urls": item["Uris"], "username": item["Username"], "credential": item["Password"]} for item in ice_response.get("IceServerList", []))
    return {
        "signalingWebSocketUrl": presigned_websocket_url(by_protocol["WSS"], channel_arn, client_id),
        "iceServers": ice_servers,
    }


def presigned_websocket_url(endpoint: str, channel_arn: str, client_id: str | None, expires_seconds: int = 299) -> str:
    query = {"X-Amz-ChannelARN": channel_arn}
    if client_id:
        query["X-Amz-ClientId"] = client_id
    request = AWSRequest(method="GET", url=f"{endpoint}?{urlencode(query)}")
    credentials = boto3.Session().get_credentials()
    if credentials is None:
        raise RuntimeError("AWS execution credentials unavailable")
    SigV4QueryAuth(credentials.get_frozen_credentials(), "kinesisvideo", REGION, expires_seconds).add_auth(request)
    return request.url
