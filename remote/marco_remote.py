# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Windows CLI for the Marco TV card surface. Paste the optically scanned TV QR locally."""
import argparse
import getpass
import json
import re
import ssl
import sys
import threading
import time

from tls_link import connect, receive, send

DIRECTIONS = {"UP", "DOWN", "LEFT", "RIGHT"}


class Session:
    def __init__(self, sock, sid):
        self.sock = sock
        self.sid = sid
        self.seq = 0
        self.lock = threading.Lock()
        self.done = threading.Event()
        self.last = time.monotonic()

    def request(self, command):
        self.seq += 1
        send(self.sock, f"{self.sid} {self.seq} {command}")
        response = receive(self.sock, timeout=5)
        self.last = time.monotonic()
        return response

    def state(self):
        parts = self.request("STATE").split()
        if (len(parts) != 7 or parts[0] != "STATE"
                or not re.fullmatch("[a-f0-9]{64}", parts[1])
                or not all(item.isdecimal() for item in parts[2:])):
            raise ValueError("Card surface unavailable")
        return parts[1], " ".join(parts[2:])

    def run(self, command):
        with self.lock:
            if self.done.is_set():
                raise EOFError("Ended")
            if command == "CLOSE":
                self.done.set()
                return self.request("CLOSE")
            nonce, position = self.state()
            if command == "STATE":
                return "STATE " + position
            if command not in DIRECTIONS:
                raise ValueError("Unknown direction")
            result = self.request(f"STEP {nonce} {command}")
            # Only print defined result types and numeric positions. Never echo server secrets.
            if not re.fullmatch(r"[A-Z_]+(?: -?[0-9]+){0,5}", result):
                raise ValueError("Unexpected movement response")
            return result

    def keep_alive(self):
        try:
            while not self.done.wait(2):
                with self.lock:
                    if not self.done.is_set() and time.monotonic() - self.last >= 4:
                        if self.request("PING") != "PONG":
                            raise EOFError("Ended")
        except (OSError, EOFError, ValueError):
            self.done.set()
            print("Session ended. Press Enter to exit.", flush=True)


def pair(text):
    if len(text) > 8192:
        raise ValueError("Invalid QR")
    data = json.loads(text)
    if data["version"] != 2 or not re.fullmatch("[a-f0-9]{64}", data["ticket"]):
        raise ValueError("Invalid TV pairing QR")
    sock = connect(data)
    try:
        print("TV certificate verified: " + sock.version(), flush=True)
        send(sock, "PAIR " + data["ticket"])
        if receive(sock) != "PENDING":
            raise ValueError("Pairing window unavailable")
        print("Approve on the television.", flush=True)
        result = re.fullmatch(r"GRANTED ([a-f0-9]{64})", receive(sock))
        if not result:
            raise ValueError("Authorization denied or expired")
        return Session(sock, result.group(1))
    except BaseException:
        sock.close()
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("commands", nargs="*", metavar="STATE|RIGHT|LEFT|UP|DOWN|CLOSE")
    args = parser.parse_args()
    commands = [c.upper() for c in args.commands]
    if any(c not in DIRECTIONS | {"STATE", "CLOSE"} for c in commands):
        parser.error("Unknown card command")
    session = pair(getpass.getpass("Paste TV QR text here (hidden): ").strip())
    try:
        print("Connected. Position fields: epoch layoutRevision focusRevision row column.", flush=True)
        threading.Thread(target=session.keep_alive, daemon=True).start()
        if commands:
            for command in commands:
                print(command, session.run(command), flush=True)
                if command == "CLOSE":
                    break
        else:
            while not session.done.is_set():
                command = input("STATE / UP / DOWN / LEFT / RIGHT / CLOSE > ").strip().upper()
                if command in DIRECTIONS | {"STATE", "CLOSE"}:
                    print(session.run(command), flush=True)
    finally:
        try:
            if not session.done.is_set():
                session.run("CLOSE")
        finally:
            session.done.set()
            session.sock.close()


if __name__ == "__main__":
    try:
        main()
    except (EOFError, OSError, ValueError, KeyError, TypeError, KeyboardInterrupt):
        sys.exit("Session closed or authorization failed. Start a fresh window on the TV.")
