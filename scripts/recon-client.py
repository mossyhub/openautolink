#!/usr/bin/env python3
"""
CarPlay Recon Client — retrieves reports and APKs from the head unit.

Usage:
    python recon-client.py <car-ip> report              # Download text report
    python recon-client.py <car-ip> list                 # List all APKs
    python recon-client.py <car-ip> apk <package>        # Grab one APK + native libs
    python recon-client.py <car-ip> all                  # Grab ALL readable APKs

Files are saved to a 'recon-output/' directory.
"""

import os
import socket
import sys
import time

PORT = 5289
OUTPUT_DIR = "recon-output"
RECV_SIZE = 65536
TIMEOUT = 30


def recv_all(sock: socket.socket) -> bytes:
    """Receive until the remote side closes the connection."""
    chunks = []
    while True:
        try:
            chunk = sock.recv(RECV_SIZE)
            if not chunk:
                break
            chunks.append(chunk)
        except socket.timeout:
            break
    return b"".join(chunks)


def cmd_report(ip: str):
    """Download the full recon text report."""
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    print(f"Connecting to {ip}:{PORT}...")
    with socket.create_connection((ip, PORT), timeout=TIMEOUT) as s:
        s.settimeout(TIMEOUT)
        # Send nothing — server sends report by default
        data = recv_all(s)

    path = os.path.join(OUTPUT_DIR, "recon-report.txt")
    with open(path, "wb") as f:
        f.write(data)
    print(f"Report saved: {path} ({len(data):,} bytes)")


def cmd_list(ip: str):
    """List all APKs on the device."""
    print(f"Connecting to {ip}:{PORT}...")
    with socket.create_connection((ip, PORT), timeout=TIMEOUT) as s:
        s.settimeout(TIMEOUT)
        s.sendall(b"LIST_APKS\n")
        data = recv_all(s)

    text = data.decode("utf-8", errors="replace")
    print(text)

    os.makedirs(OUTPUT_DIR, exist_ok=True)
    path = os.path.join(OUTPUT_DIR, "apk-list.txt")
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)
    print(f"List saved: {path}")


def cmd_apk(ip: str, package: str):
    """Grab a single APK + its native libs."""
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    print(f"Requesting APK: {package}")
    with socket.create_connection((ip, PORT), timeout=TIMEOUT) as s:
        s.settimeout(TIMEOUT)
        s.sendall(f"GET_APK:{package}\n".encode())
        data = recv_all(s)

    if data.startswith(b"ERROR:"):
        print(f"Server error: {data.decode('utf-8', errors='replace')}")
        return

    # Parse the framed response: APK:<name>:<size>\n<bytes> [LIB:<name>:<size>\n<bytes>]* DONE\n
    _parse_framed_files(data, package)


def cmd_all(ip: str):
    """Grab ALL readable APKs from the device."""
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    print(f"Requesting ALL APKs from {ip}:{PORT}...")
    print("This may take a while depending on how many packages are installed.")

    with socket.create_connection((ip, PORT), timeout=300) as s:
        s.settimeout(300)
        s.sendall(b"GET_ALL_APKS\n")

        # Read the manifest first
        buf = b""
        while b"END_MANIFEST\n" not in buf:
            chunk = s.recv(RECV_SIZE)
            if not chunk:
                print("Connection closed before manifest received")
                return
            buf += chunk

        manifest_end = buf.index(b"END_MANIFEST\n") + len(b"END_MANIFEST\n")
        manifest_text = buf[:manifest_end].decode("utf-8", errors="replace")
        remainder = buf[manifest_end:]

        # Parse manifest
        lines = manifest_text.strip().split("\n")
        count_line = lines[0]  # MANIFEST:<count>
        total = int(count_line.split(":")[1])
        print(f"Manifest: {total} APKs to transfer")
        for line in lines[1:-1]:  # skip MANIFEST and END_MANIFEST
            print(f"  {line}")

        # Now read framed APK data
        data = remainder
        while True:
            chunk = s.recv(RECV_SIZE)
            if not chunk:
                break
            data += chunk

    # Parse all framed APKs
    _parse_bulk_framed(data)


def _parse_framed_files(data: bytes, label: str):
    """Parse APK:<name>:<size>\\n<bytes> frames from a single-APK response."""
    pkg_dir = os.path.join(OUTPUT_DIR, label.replace(".", "_"))
    os.makedirs(pkg_dir, exist_ok=True)

    pos = 0
    saved = 0
    while pos < len(data):
        # Find the next header line
        nl = data.index(b"\n", pos)
        header = data[pos:nl].decode("utf-8", errors="replace")

        if header == "DONE":
            break
        if header.startswith("ERROR:"):
            print(f"  Error: {header}")
            break

        parts = header.split(":")
        if len(parts) < 3:
            break

        file_type = parts[0]  # APK or LIB
        file_name = parts[1]
        file_size = int(parts[2])

        file_data = data[nl + 1: nl + 1 + file_size]
        out_path = os.path.join(pkg_dir, file_name)
        with open(out_path, "wb") as f:
            f.write(file_data)
        print(f"  [{file_type}] {file_name} ({len(file_data):,} bytes) -> {out_path}")
        saved += 1
        pos = nl + 1 + file_size

    print(f"Saved {saved} file(s) to {pkg_dir}/")


def _parse_bulk_framed(data: bytes):
    """Parse APK:<package>:<size>\\n<bytes> frames from a bulk transfer."""
    pos = 0
    saved = 0
    errors = 0

    while pos < len(data):
        # Find header line
        try:
            nl = data.index(b"\n", pos)
        except ValueError:
            break
        header = data[pos:nl].decode("utf-8", errors="replace")

        if header.startswith("ALL_DONE:"):
            count = header.split(":")[1]
            print(f"\nTransfer complete: {count} APKs sent by server, {saved} saved locally")
            break
        if header.startswith("ERROR:"):
            print(f"  Error: {header}")
            errors += 1
            pos = nl + 1
            continue

        parts = header.split(":")
        if len(parts) < 3 or parts[0] != "APK":
            pos = nl + 1
            continue

        package = parts[1]
        file_size = int(parts[2])

        pkg_dir = os.path.join(OUTPUT_DIR, package.replace(".", "_"))
        os.makedirs(pkg_dir, exist_ok=True)

        file_data = data[nl + 1: nl + 1 + file_size]
        out_path = os.path.join(pkg_dir, "base.apk")
        with open(out_path, "wb") as f:
            f.write(file_data)
        saved += 1
        size_mb = len(file_data) / (1024 * 1024)
        print(f"  [{saved}] {package} ({size_mb:.1f} MB) -> {out_path}")
        pos = nl + 1 + file_size

    if errors:
        print(f"  {errors} error(s) during transfer")
    print(f"\nAll files saved under {OUTPUT_DIR}/")


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)

    ip = sys.argv[1]
    command = sys.argv[2].lower()

    if command == "report":
        cmd_report(ip)
    elif command == "list":
        cmd_list(ip)
    elif command == "apk":
        if len(sys.argv) < 4:
            print("Usage: recon-client.py <ip> apk <package.name>")
            sys.exit(1)
        cmd_apk(ip, sys.argv[3])
    elif command == "all":
        cmd_all(ip)
    else:
        print(f"Unknown command: {command}")
        print(__doc__)
        sys.exit(1)


if __name__ == "__main__":
    main()
