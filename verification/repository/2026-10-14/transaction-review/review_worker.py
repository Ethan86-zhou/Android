"""Independent transaction review: real host Bash, generated files only, no ADB.

Run from any directory with Python 3. This does not replace Android/ADB evidence.
Each scenario uses a fresh OS temporary directory and retains no user data.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile
import types

EXPECTED_HELPER = "cb0d6339fdd92991aa8ddc98925aa52e02ac0698a5c8f29492bce0fcac539877"
NAMES = {"a.db", "b.db", "c.db-wal"}
ORIGINAL = {"a.db": b"old A\r\n\x1a\xff", "c.db-wal": b"old sidecar\x00", "unrelated": b"keep"}
TARGET = {"a.db": b"restore A\n\x1a", "b.db": b"restore B\x00\xfe"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bash", default=shutil.which("bash") or "C:/Program Files/Git/bin/bash.exe")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise SystemExit("Use a new result path; prior evidence is immutable")
    repository = Path(__file__).resolve().parents[4]
    helper = repository / "scripts/android_data_transaction.py"
    source = helper.read_bytes()
    actual_sha = hashlib.sha256(source).hexdigest()
    if actual_sha != EXPECTED_HELPER:
        raise SystemExit("This review is pinned to a different helper SHA; do not reuse its claims")
    # Pin the imported implementation to exactly the bytes hashed above.
    module = types.ModuleType("reviewed_transaction")
    exec(compile(source, str(helper), "exec"), module.__dict__)
    hashes = {name: hashlib.sha256(data).hexdigest() for name, data in TARGET.items()}
    checks = []

    def initialize(root):
        (root / "live").mkdir()
        (root / "tx/staged").mkdir(parents=True)
        for name, data in ORIGINAL.items():
            (root / "live" / name).write_bytes(data)
        for name, data in TARGET.items():
            (root / "tx/staged" / name).write_bytes(data)
        (root / "tx/state").write_text("prepared", encoding="ascii")

    def execute(root, script, *options):
        return subprocess.run([args.bash, "-c", script, "review-worker", *options],
                              cwd=root, capture_output=True, timeout=15)

    def state(root):
        return (root / "tx/state").read_text(encoding="ascii").strip()

    def assert_live(root, expected):
        actual = {p.name: p.read_bytes() for p in (root / "live").iterdir() if p.is_file()}
        assert actual == expected, "Mixed or unexpected live file set"

    def retain_copies(root):
        assert all((root / "tx/staged" / name).is_file() for name in TARGET)
        assert (root / "tx/rollback/a.db").read_bytes() == ORIGINAL["a.db"]
        assert (root / "tx/rollback/c.db-wal").read_bytes() == ORIGINAL["c.db-wal"]

    for scenario, fail_after in (("commit_exact_set", 0), ("failure_after_first_publish", 1),
                                  ("failure_after_second_publish", 2), ("corrupt_second_stage", 0),
                                  ("term_after_committed_marker", 0)):
        with tempfile.TemporaryDirectory(prefix="android-transaction-review-") as temporary:
            root = Path(temporary).resolve()
            initialize(root)
            if scenario == "corrupt_second_stage":
                (root / "tx/staged/b.db").write_bytes(b"corrupted generated stage")
            script = module.worker_script("live", "tx", hashes, NAMES, fail_after_publish=fail_after).decode()
            if scenario == "term_after_committed_marker":
                script = script.replace("set_state committed\nphase=committed",
                                        "set_state committed\nkill -TERM $$\nphase=committed")
            completed = execute(root, script)
            expected_state = ("failed_preparation" if scenario == "corrupt_second_stage" else
                              "rolled_back" if fail_after else "committed")
            assert state(root) == expected_state
            assert_live(root, {**TARGET, "unrelated": ORIGINAL["unrelated"]}
                        if expected_state == "committed" else ORIGINAL)
            retain_copies(root)
            assert not (root / "live.restore-lock").exists()
            checks.append({"scenario": scenario, "passed": True, "exitCode": completed.returncode,
                           "state": state(root), "exactFileSet": True, "copiesRetained": True})

    with tempfile.TemporaryDirectory(prefix="android-transaction-review-") as temporary:
        root = Path(temporary).resolve()
        initialize(root)
        clean = module.worker_script("live", "tx", hashes, NAMES, fail_after_publish=1).decode()
        # Simulate both rollback I/O failure and inability to write its failure marker.
        # This previously left a mixed set and incorrectly released the global lock.
        fault = clean.replace("rollback() {\n", "rollback() {\n    return 1\n")
        fault = fault.replace("set_state() { printf",
                              'set_state() { [ "$1" != rollback_failed ] || return 1; printf')
        execute(root, fault)
        assert state(root) == "rolling_back"
        assert (root / "live.restore-lock").is_dir()
        assert (root / "live/a.db").read_bytes() == TARGET["a.db"]
        assert (root / "live/c.db-wal").read_bytes() == ORIGINAL["c.db-wal"]
        retain_copies(root)
        execute(root, clean, "rollback", "independent-review-attempt")
        assert state(root) == "rolled_back"
        assert_live(root, ORIGINAL)
        assert not (root / "live.restore-lock").exists()
        retain_copies(root)
        checks.append({"scenario": "rollback_and_marker_failure_then_local_recovery", "passed": True,
                       "lockRetainedDuringFailure": True, "state": state(root),
                       "exactFileSet": True, "copiesRetained": True})

    with tempfile.TemporaryDirectory(prefix="android-transaction-review-") as temporary:
        root = Path(temporary).resolve()
        initialize(root)
        (root / "tx/state").write_text("preparing", encoding="ascii")
        (root / "tx/rollback.ready").touch()
        (root / "live.restore-lock").mkdir()
        (root / "live.restore-lock/owner").write_text("tx", encoding="ascii")

        def local_shell(*parts, data=None):
            assert parts[:6] == ("shell", "-T", "run-as", "review.test", "sh", "-c")
            result = execute(root, shlex.split(parts[6])[0])
            if result.returncode:
                raise RuntimeError("Generated shell probe failed")
            return result.stdout

        mode = module._recovery_mode(local_shell, "review.test", "live", "tx", "preparing")
        assert mode == "abort"
        execute(root, module.worker_script("live", "tx", hashes, NAMES).decode(), mode, "review-attempt")
        assert state(root) == "failed_preparation"
        assert_live(root, ORIGINAL)
        assert not (root / "live.restore-lock").exists()
        checks.append({"scenario": "preparing_with_ready_marker_aborts_safely", "passed": True,
                       "mode": mode, "state": state(root), "exactFileSet": True})

    assert hashlib.sha256(helper.read_bytes()).hexdigest() == actual_sha, "Helper changed during review"
    report = {"verifiedAtUtc": datetime.now(timezone.utc).isoformat(), "passed": True,
              "helperSha256": actual_sha, "testScriptSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              "generatedHostFilesOnly": True, "adbInvoked": False, "deviceTouched": False,
              "scope": "Real host Bash worker behavior; Android detached-client scenarios have separate device evidence.",
              "checkCount": len(checks), "checks": checks}
    with args.output.open("x", encoding="utf-8") as stream:
        json.dump(report, stream, indent=2)
        stream.write("\n")
    print("PASS", len(checks), "independent real-Bash scenarios; helper", actual_sha)


if __name__ == "__main__":
    main()
