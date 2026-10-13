"""Exercise device-local transactions only on generated files in Lexi's cache."""
import argparse
import hashlib
import json
from pathlib import Path
import shlex
import subprocess
import time
from uuid import uuid4

from device_data_guard import PACKAGE, _command
from android_data_transaction import (TransactionError, read_verified, restore_transaction,
                                      stage_verified, _state, _shell)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    helper = Path(__file__).resolve().parents[3] / "scripts/android_data_transaction.py"
    source_hash = hashlib.sha256(helper.read_bytes()).hexdigest()
    adb = [args.adb, "-s", args.serial]
    command = lambda *parts, data=None: _command(adb, *parts, data=data)
    root = "cache/lexi-transaction-probe-" + uuid4().hex
    names = {"book.db", "book.db-journal", "book.db-shm", "book.db-wal"}
    original = {"book.db": b"original\x00\x1a\r\n" * 8192, "book.db-wal": b"original WAL" * 500}
    desired = {"book.db": bytes(range(256)) * 512, "book.db-journal": b"", "book.db-shm": b"new SHM"}
    checks = []

    def setup(label):
        live = root + "/" + label + "/live"
        tx = root + "/" + label + "/transaction"
        command("shell", "-T", "run-as", PACKAGE, "mkdir", "-p", live)
        for name, data in original.items():
            stage_verified(command, PACKAGE, live + "/" + name, data)
        return live, tx

    def verify(live, expected):
        for name in names:
            if name in expected:
                assert read_verified(command, PACKAGE, live + "/" + name) == expected[name]
            else:
                _shell(command, PACKAGE, f"[ ! -e {live}/{name} ]")

    def retain_script(label, tx):
        (args.output / (label + "-worker.sh")).write_bytes(read_verified(command, PACKAGE, tx + "/worker.sh"))
        (args.output / (label + "-plan.json")).write_bytes(read_verified(command, PACKAGE, tx + "/plan.json"))

    live, tx = setup("commit")
    restore_transaction(command, PACKAGE, live, desired, names, tx)
    verify(live, desired); retain_script("commit", tx)
    checks.append({"name": "complete_set_commit_and_sidecar_absence", "passed": True})

    for fault in (1, 2):
        label = "failure-" + str(fault)
        live, tx = setup(label)
        try:
            restore_transaction(command, PACKAGE, live, desired, names, tx, _fail_after_publish=fault)
            raise AssertionError("Fault injection did not fail")
        except TransactionError as failure:
            assert failure.state == "rolled_back"
        verify(live, original); retain_script(label, tx)
        checks.append({"name": "failure_after_publish_" + str(fault) + "_restores_all_old_files", "passed": True})
        if fault == 1:
            command("shell", "-T", "run-as", PACKAGE, "touch", live + "/test-fault-released")
            receipt = restore_transaction(command, PACKAGE, live, desired, names, tx, _fail_after_publish=fault)
            assert receipt["transactionDir"] == tx + "/retry"
            verify(live, desired)
            assert _state(command, PACKAGE, tx) == "rolled_back"
            checks.append({"name": "reconnect_retries_in_child_preserving_prior_rollback", "passed": True})

    for fault in (0, 1):
        label = "disconnect-" + str(fault)
        live, tx = setup(label)
        terminated = False

        def disconnect_launcher(*parts, data=None):
            nonlocal terminated
            if parts[:5] == ("shell", "-T", "run-as", PACKAGE, "sh") and "nohup setsid" in parts[-1]:
                script = shlex.split(parts[-1])[0] + "\nsleep 30"
                launch = [*parts[:-1], shlex.quote(script)]
                process = subprocess.Popen([*adb, *launch], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                try:
                    deadline = time.monotonic() + 10
                    while _state(command, PACKAGE, tx) != "publishing":
                        if time.monotonic() > deadline:
                            raise AssertionError("Worker never reached the publication window")
                        time.sleep(0.05)
                    process.terminate()  # only this probe's own ADB client
                    process.communicate(timeout=5)
                    terminated = True
                finally:
                    if process.poll() is None:
                        process.terminate(); process.communicate(timeout=5)
                raise RuntimeError("Injected ADB client interruption")
            return command(*parts, data=data)

        try:
            restore_transaction(disconnect_launcher, PACKAGE, live, desired, names, tx,
                                _fail_after_publish=fault, _pause_after_publish=3)
            raise AssertionError("Launcher interruption was not injected")
        except RuntimeError as failure:
            assert "Injected ADB client interruption" in str(failure)
        assert terminated
        deadline = time.monotonic() + 15
        expected_state = "committed" if fault == 0 else "rolled_back"
        while _state(command, PACKAGE, tx) != expected_state:
            if time.monotonic() > deadline:
                raise AssertionError("Detached worker did not finish after ADB client interruption")
            time.sleep(0.1)
        verify(live, desired if fault == 0 else original)
        if fault == 0:
            restore_transaction(command, PACKAGE, live, desired, names, tx, _pause_after_publish=3)
        retain_script(label, tx)
        checks.append({"name": "terminated_adb_client_device_" + expected_state, "passed": True})

    assert hashlib.sha256(helper.read_bytes()).hexdigest() == source_hash, "Helper changed during probe"
    report = {"passed": True, "userDatabaseTouched": False, "generatedCacheOnly": True,
              "helperSha256": source_hash, "deviceFixtureRoot": root, "checks": checks}
    (args.output / "verification.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print("PASS: " + str(len(checks)) + " isolated device transaction checks")


if __name__ == "__main__":
    main()
