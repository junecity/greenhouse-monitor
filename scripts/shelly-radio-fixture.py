"""Broadcast both Shelly test frames into a local Android emulator's netsim HCI port.

Run with the hci.port from the emulator's netsim.ini, then run ShellyInventoryTest
with the instrumentation argument "-e shellyRadio true". No physical radios are used.
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


def advertise(controller, model):
    command(controller, 0x0C03)  # Reset controller
    command(controller, 0x2005, bytes([model, 0, 0, 0, 0, 0xC1]))  # Static random address
    command(controller, 0x2006, struct.pack("<HHBBB6sBB", 160, 160, 0, 1, 0, bytes(6), 7, 0))
    sample = bytes.fromhex("40 00 01 01 5a 2e 32 45 fa 00")  # BTHome 25 °C, 50% RH
    if model == 0x0C:
        sample += bytes.fromhex("64 02")  # Display model reports Bright
    service = bytes.fromhex("02 01 06") + bytes([len(sample) + 3, 0x16, 0xD2, 0xFC]) + sample
    manufacturer = bytes([6, 0xFF, 0xA9, 0x0B, 0x0B, model, 0])
    for opcode, packet in ((0x2008, service), (0x2009, manufacturer)):
        command(controller, opcode, bytes([len(packet)]) + packet.ljust(31, b"\x00"))
    command(controller, 0x200A, b"\x01")  # Enable advertising


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("port", type=int)
    parser.add_argument("--seconds", type=int, default=120)
    args = parser.parse_args()
    with ExitStack() as devices:
        for model in (0x0C, 0x11):
            controller = devices.enter_context(socket.create_connection(("127.0.0.1", args.port), timeout=3))
            advertise(controller, model)
        print("Two virtual Shelly sensors are advertising.", flush=True)
        time.sleep(args.seconds)
