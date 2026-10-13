"""Verify the actual ADB binary paths using generated data in Lexi's private cache.

This never reads or writes the wordbook. Run only while this device is reserved
for this task; the installed Lexi must be debuggable.
"""
import argparse
import hashlib
import json
from pathlib import Path
from uuid import uuid4

from device_data_guard import PACKAGE, _command, _read_verified, _stage_verified


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    adb = [args.adb, "-s", args.serial]
    command = lambda *parts, data=None: _command(adb, *parts, data=data)
    command("shell", "-T", "run-as", PACKAGE, "mkdir", "-p", "cache")
    pairs = bytes(value for first in range(256) for second in range(256) for value in (first, second))
    fixtures = {"all_byte_pairs_1_mib": pairs * 8, "empty": b"", "controls": b"\x00\x1a\r\n\r\r\n\n\xff" * 1000}
    checks = []
    for name, data in fixtures.items():
        path = "cache/lexi-binary-probe-" + uuid4().hex + ".bin"
        try:
            _stage_verified(command, path, data)
            back = _read_verified(command, path)
            if back != data:
                raise RuntimeError("Generated binary fixture did not roundtrip")
            checks.append({"name": name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest(), "passed": True})
        finally:
            command("shell", "-T", "run-as", PACKAGE, "rm", "-f", path)
    # A zero adb exit after truncation must be detected before any caller can rename.
    path = "cache/lexi-binary-probe-" + uuid4().hex + ".bin"
    data = fixtures["all_byte_pairs_1_mib"]

    def truncated(*parts, data=None):
        return command(*parts, data=data[:16_384] if "dd" in parts else data)

    try:
        rejected = False
        try:
            _stage_verified(truncated, path, data)
        except RuntimeError as failure:
            rejected = "Binary upload" in str(failure)
        if not rejected:
            raise RuntimeError("Truncated upload was accepted")
        checks.append({"name": "successful_exit_truncated_upload_rejected", "passed": True})
    finally:
        command("shell", "-T", "run-as", PACKAGE, "rm", "-f", path)
    report = {"passed": True, "generatedDataOnly": True, "userDatabaseTouched": False,
              "transport": "ASCII base64 upload over shell-v2; private device decode; exec-out download; device SHA-256 verification",
              "checks": checks}
    (args.output / "verification.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print("PASS: " + str(len(checks)) + " real-device binary transport checks")


if __name__ == "__main__":
    main()
