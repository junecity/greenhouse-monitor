"""Serve an Environmental Sensing GATT peripheral to a local Android emulator.

Requires the Bumble Python package. Supply netsim's hci.port, then run
EnvironmentalRadioTest with the instrumentation argument "-e essRadio true".
Stop with Ctrl+C.
"""
import argparse
import asyncio
import struct

from bumble.core import UUID
from bumble.device import Device
from bumble.gatt import Characteristic, Service
from bumble.hci import Address
from bumble.transport import open_transport


async def serve(port):
    async with await open_transport(f"tcp-client:127.0.0.1:{port}") as (source, sink):
        device = Device.with_hci("Test ESS", Address("C1:21:00:00:00:24"), source, sink)

        def readable(uuid, value):
            return Characteristic(UUID.from_16_bits(uuid), Characteristic.Properties.READ,
                                  Characteristic.Permissions.READABLE, value)

        device.add_service(Service(UUID.from_16_bits(0x181A), [
            readable(0x2A6E, struct.pack("<h", -250)),  # -2.50 °C
            readable(0x2A6F, struct.pack("<H", 4825)),  # 48.25% RH
        ]))
        await device.power_on()
        name = b"Test ESS"
        data = bytes.fromhex("02 01 06 03 03 1a 18") + bytes([1 + len(name), 9]) + name
        await device.start_advertising(advertising_data=data, auto_restart=True)
        print("Environmental Sensing fixture ready", flush=True)
        await asyncio.Event().wait()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("port", type=int)
    asyncio.run(serve(parser.parse_args().port))
