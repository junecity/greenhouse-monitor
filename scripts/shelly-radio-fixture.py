"""Broadcast both Shelly test frames into a local Android emulator's netsim HCI port.

Run with the hci.port from the emulator's netsim.ini, then run ShellyInventoryTest
with the instrumentation argument "-e shellyRadio true". For H5179 battery tests,
use --govee and run GoveeBatteryRadioTest with "-e goveeRadio true".
"""
import argparse
import socket
import struct
import time
from contextlib import ExitStack


def read_exact(controller, size):
    data = bytearray()
    while len(data) < size:
        piece = controller.recv(size - len(data))
        if not piece:
            raise EOFError("Virtual controller disconnected")
        data.extend(piece)
    return data


def command(controller, opcode, parameters=b""):
    controller.sendall(b"\x01" + struct.pack("<HB", opcode, len(parameters)) + parameters)
    while True:
        if read_exact(controller, 1) != b"\x04":
            raise ValueError("Expected an HCI event")
        event, length = read_exact(controller, 2)
        payload = read_exact(controller, length)
        if event == 14 and payload[1:3] == struct.pack("<H", opcode):
            if payload[3]:
                raise RuntimeError(f"HCI command {opcode:#x} failed ({payload[3]})")
            return


def advertise(controller, model, govee=False):
    command(controller, 0x0C03)  # Reset controller
    command(controller, 0x2005, bytes([model, 0, 0, 0, 0, 0xC1]))  # Static random address
    command(controller, 0x2006, struct.pack("<HHBBB6sBB", 160, 160, 0, 1, 0, bytes(6), 7, 0))
    if govee:
        name = b"Govee_H5179_0001" if model == 1 else b"GV5179_0002"
        payload = bytes.fromhex("01 88 ec 00 01 01 0a 0a a4 06 54" if model == 1 else "01 00 01 01 03 b3 14 11")
        advertisement = bytes.fromhex("02 01 06") + bytes([len(payload) + 1, 0xff]) + payload
        response = bytes([len(name) + 1, 9]) + name
    else:
        sample = bytes.fromhex("40 00 01 01 5a 2e 32 45 fa 00")  # BTHome 25 °C, 50% RH
        if model == 0x0C:
            sample += bytes.fromhex("64 02")  # Display model reports Bright
        advertisement = bytes.fromhex("02 01 06") + bytes([len(sample) + 3, 0x16, 0xD2, 0xFC]) + sample
        response = bytes([6, 0xFF, 0xA9, 0x0B, 0x0B, model, 0])
    for opcode, packet in ((0x2008, advertisement), (0x2009, response)):
        command(controller, opcode, bytes([len(packet)]) + packet.ljust(31, b"\x00"))
    command(controller, 0x200A, b"\x01")  # Enable advertising


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("port", type=int)
    parser.add_argument("--seconds", type=int, default=120)
    parser.add_argument("--govee", action="store_true")
    args = parser.parse_args()
    with ExitStack() as devices:
        for model in ((1, 2) if args.govee else (0x0C, 0x11)):
            controller = devices.enter_context(socket.create_connection(("127.0.0.1", args.port), timeout=3))
            advertise(controller, model, args.govee)
        print("Two virtual sensors are advertising.", flush=True)
        time.sleep(args.seconds)
