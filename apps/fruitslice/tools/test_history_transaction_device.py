"""Probe the real device transaction worker using generated cache files only.

No game Activity is started and no real shared_prefs file is read or changed.
Disconnect probes terminate only their own ADB launcher process, never the ADB
server, another process, USB connection, network, or the detached device worker.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shlex
import subprocess
import sys
import time
from uuid import uuid4

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "scripts"))
from android_data_transaction import TransactionError, read_verified, restore_transaction, stage_verified

PACKAGE = "com.ethan.fruitslice"
NAMES = {"history.xml", "history.xml.bak"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    adb = [args.adb, "-s", args.serial]
    helper_path = Path(__file__).resolve().parents[3] / "scripts/android_data_transaction.py"
    helper_hash_before = hashlib.sha256(helper_path.read_bytes()).hexdigest()

    def command(*parts, data=None):
        result = subprocess.run([*adb, *parts], input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if result.returncode:
            raise RuntimeError((result.stdout + result.stderr).decode("utf-8", "replace"))
        return result.stdout

    prefix = "cache/fruitslice-transaction-probe-" + uuid4().hex
    old = {"history.xml": b"generated OLD XML\r\n\x1a\xff\x00", "history.xml.bak": b"generated OLD BAK\n\x00"}
    desired = {"history.xml": b"generated NEW XML\n\x1a\xfe\x00", "history.xml.bak": b"generated NEW BAK\r\n"}
    unrelated = b"unrelated generated file must be unchanged"
    scenarios = [("commit", 0, False), ("rollback_after_first_publish", 1, False),
                 ("disconnect_then_commit", 0, True), ("disconnect_then_rollback", 1, True),
                 ("rollback_after_removing_bak", 2, False)]
    checks = []
    for name, fail_after, disconnect in scenarios:
        live = prefix + "/" + name + "/live"
        tx = prefix + "/" + name + "/transaction"
        expected_desired = {"history.xml": desired["history.xml"]} if name.endswith("removing_bak") else desired
        command("shell", "-T", "run-as", PACKAGE, "mkdir", "-p", live)
        for filename, data in {**old, "unrelated.txt": unrelated}.items():
            stage_verified(command, PACKAGE, live + "/" + filename, data)
        # Durable probe identity is recorded before the remote worker is launched.
        identity = {"scenario": name, "live": live, "transaction": tx, "faultAfterPublication": fail_after}
        with (args.output / (name + "-transaction.json")).open("x", encoding="utf-8") as stream:
            json.dump(identity, stream, indent=2)
        interrupted = False
        publication_observed = False
        pause = 2 if disconnect else 0

        def disconnecting(*parts, data=None):
            nonlocal interrupted, publication_observed
            if parts[:6] != ("shell", "-T", "run-as", PACKAGE, "sh", "-c"):
                return command(*parts, data=data)
            script = shlex.split(parts[6])[0]
            if not script.startswith("nohup setsid ") or interrupted:
                return command(*parts, data=data)
            # The production launch normally returns immediately; hold ONLY this
            # outer launcher alive to terminate it while the detached worker sleeps.
            held_script = script + " sleep 10"
            process = subprocess.Popen([*adb, *parts[:6], shlex.quote(held_script)],
                                       stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                       creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
            try:
                deadline = time.monotonic() + 5
                while time.monotonic() < deadline:
                    state = command("shell", "-T", "run-as", PACKAGE, "cat", tx + "/state").strip()
                    if state == b"publishing":
                        first = read_verified(command, PACKAGE, live + "/history.xml")
                        if first == desired["history.xml"]:
                            publication_observed = True
                            break
                    time.sleep(.02)
                if not publication_observed:
                    raise RuntimeError("Did not observe the first publication before disconnect")
                process.terminate()
                process.communicate(timeout=5)
                interrupted = True
                raise ConnectionError("Probe terminated only its ADB launcher after first publication")
            finally:
                if process.poll() is None:
                    process.terminate()
                    process.communicate(timeout=5)

        def restore(callback):
            return restore_transaction(callback, PACKAGE, live, expected_desired, NAMES, tx,
                                       _fail_after_publish=fail_after, _pause_after_publish=pause)

        state = None
        try:
            try:
                receipt = restore(disconnecting if disconnect else command)
            except ConnectionError:
                # No second tx or old-file snapshot is created after disconnection.
                receipt = restore(command)
            state = receipt["state"]
        except TransactionError as failure:
            state = failure.state
        expected_state = "rolled_back" if fail_after else "committed"
        if state != expected_state:
            raise RuntimeError(f"{name}: expected {expected_state}, got {state}")
        expected_files = old if fail_after else expected_desired
        actual_names = set(command("shell", "-T", "run-as", PACKAGE, "ls", live).decode().splitlines())
        for filename in NAMES:
            if filename in expected_files:
                if read_verified(command, PACKAGE, live + "/" + filename) != expected_files[filename]:
                    raise RuntimeError(f"{name}: mixed or damaged live set at {filename}")
            elif filename in actual_names:
                raise RuntimeError(f"{name}: originally absent file was not removed")
        if read_verified(command, PACKAGE, live + "/unrelated.txt") != unrelated:
            raise RuntimeError(f"{name}: unrelated file changed")
        if disconnect and not (interrupted and publication_observed):
            raise RuntimeError("Disconnect injection was not exercised")
        # Keep the journal and both file generations for independent inspection.
        retained = set(command("shell", "-T", "run-as", PACKAGE, "ls", tx).decode().splitlines())
        if not {"staged", "rollback", "state", "plan.json", "worker.sh"} <= retained:
            raise RuntimeError("Transaction evidence was removed")
        plan = read_verified(command, PACKAGE, tx + "/plan.json")
        worker = read_verified(command, PACKAGE, tx + "/worker.sh")
        (args.output / (name + "-device-plan.json")).write_bytes(plan)
        (args.output / (name + "-device-worker.sh")).write_bytes(worker)
        checks.append({**identity, "passed": True, "state": state,
                       "ownLauncherTerminated": interrupted, "publicationObservedBeforeDisconnect": publication_observed,
                       "liveHashes": {key: hashlib.sha256(value).hexdigest() for key, value in expected_files.items()},
                       "workerSha256": hashlib.sha256(worker).hexdigest(),
                       "rollbackAndStagingRetained": True, "unrelatedFileUnchanged": True})
    helper_hash_after = hashlib.sha256(helper_path.read_bytes()).hexdigest()
    source_stable = helper_hash_before == helper_hash_after
    report = {"passed": source_stable, "functionalChecksPassed": True, "sourceStable": source_stable,
              "generatedCacheDataOnly": True, "realHistoryTouched": False,
              "activityLaunched": False, "deviceCacheRoot": prefix,
              "sharedHelperSha256Before": helper_hash_before,
              "sharedHelperSha256After": helper_hash_after,
              "checks": checks}
    with (args.output / "verification.json").open("x", encoding="utf-8") as stream:
        json.dump(report, stream, indent=2)
    if not source_stable:
        raise SystemExit("Device scenarios passed, but shared helper changed during the run; validate the final source again")
    print("PASS: " + str(len(checks)) + " real-device transaction scenarios; generated cache files only")


if __name__ == "__main__":
    main()
