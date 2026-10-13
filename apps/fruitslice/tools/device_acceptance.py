"""Install this build, run device checks, and collect immutable evidence (Python stdlib)."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import uuid
from contextlib import contextmanager
from datetime import datetime, timezone, timedelta


PACKAGE = "com.ethan.fruitslice"
HISTORY_FILES = ("fruit_slice_history.xml", "fruit_slice_history.xml.bak")
BACKUP_TRANSPORT = "base64-shell-v2-exec-out-device-sha256-v3"


def device_digest(run, path):
    # SHA output is plain ASCII, so the shell protocol is appropriate here and
    # importantly propagates run-as/file errors. Never use shell for raw payloads:
    # Windows ADB can translate LF on output or treat 0x1A as EOF on stdin.
    response = run("shell", "-T", "run-as", PACKAGE, "sha256sum", path).strip()
    match = re.fullmatch(r"([0-9a-fA-F]{64})[ \t]+\*?" + re.escape(path), response)
    if not match:
        raise RuntimeError("Could not verify device file hash: " + path)
    return match.group(1).lower()


def read_verified(run, path):
    before = device_digest(run, path)
    data = run("exec-out", "run-as", PACKAGE, "cat", path, binary=True)
    if hashlib.sha256(data).hexdigest() != before or device_digest(run, path) != before:
        raise RuntimeError("History read transport checksum mismatch: " + path)
    return data


def stage_verified(run, path, data):
    # Windows shell stdin is unsuitable for binary data. exec-in also does not
    # wait reliably for dd to finish. Send only ASCII base64 over shell-v2, whose
    # exit status confirms completion, then decode inside the app's private dir.
    encoded = path + ".b64"
    try:
        run("shell", "-T", "run-as", PACKAGE, "dd", "of=" + encoded,
            binary=True, input_data=base64.b64encode(data))
        script = "base64 -d " + shlex.quote(encoded) + " > " + shlex.quote(path)
        run("shell", "-T", "run-as", PACKAGE, "sh", "-c", shlex.quote(script))
    finally:
        run("shell", "-T", "run-as", PACKAGE, "rm", "-f", encoded)
    if read_verified(run, path) != data:
        raise RuntimeError("History write transport checksum mismatch: " + path)


def read_history_file(run, name):
    """A missing preferences file is distinct from an empty file or an ADB failure."""
    path = "shared_prefs/" + name
    # Use shell only for directory/status text. exec-out preserves binary bytes,
    # but lacks a remote exit status, so validate its output with a device hash.
    directories = run("shell", "-T", "run-as", PACKAGE, "ls", "-1").splitlines()
    if "shared_prefs" not in directories:
        return None
    files = run("shell", "-T", "run-as", PACKAGE, "ls", "-1", "shared_prefs").splitlines()
    if name not in files:
        return None
    return read_verified(run, path)


def backup_history(run, app):
    """Keep raw user data only under ignored build/, never in publishable evidence."""
    run("shell", "am", "force-stop", PACKAGE)
    backup = app / "build" / "device-data-backups" / uuid.uuid4().hex
    backup.mkdir(parents=True, exist_ok=False)
    metadata = {"package": PACKAGE, "serial": run("get-serialno").strip(),
                "transport": BACKUP_TRANSPORT, "files": {}}
    for name in HISTORY_FILES:
        data = read_history_file(run, name)
        metadata["files"][name] = None if data is None else hashlib.sha256(data).hexdigest()
        if data is not None:
            with (backup / name).open("xb") as stream:
                stream.write(data)
                stream.flush()
                os.fsync(stream.fileno())
    with (backup / "backup.json").open("x", encoding="utf-8") as stream:
        json.dump(metadata, stream, indent=2)
        stream.flush()
        os.fsync(stream.fileno())
    return backup


def restore_history(run, backup):
    """Also usable after a disconnected device or killed host process via --restore-history."""
    metadata = json.loads((backup / "backup.json").read_text(encoding="utf-8"))
    if metadata.get("package") != PACKAGE or set(metadata.get("files", {})) != set(HISTORY_FILES):
        raise RuntimeError("Unrecognized history backup")
    if metadata.get("transport") != BACKUP_TRANSPORT:
        raise RuntimeError("Legacy backup lacks device-verified raw bytes; review it before any manual recovery")
    if metadata["serial"] != run("get-serialno").strip():
        raise RuntimeError("History backup belongs to a different device")
    original = {}
    for name, digest in metadata["files"].items():
        data = None if digest is None else (backup / name).read_bytes()
        if data is not None and hashlib.sha256(data).hexdigest() != digest:
            raise RuntimeError("History backup checksum mismatch: " + name)
        original[name] = data
    run("shell", "am", "force-stop", PACKAGE)
    # Prepare and verify every original file before publishing any of them.
    # A transfer failure must leave the current official history untouched.
    staged = {}
    try:
        for name, data in original.items():
            if data is not None:
                temporary = "shared_prefs/" + name + ".acceptance-restore-" + uuid.uuid4().hex
                staged[name] = temporary
                run("shell", "-T", "run-as", PACKAGE, "mkdir", "-p", "shared_prefs")
                stage_verified(run, temporary, data)
        for name, data in original.items():
            path = "shared_prefs/" + name
            if data is None:
                run("shell", "-T", "run-as", PACKAGE, "rm", "-f", path)
            else:
                run("shell", "-T", "run-as", PACKAGE, "mv", staged[name], path)
    finally:
        for temporary in staged.values():
            run("shell", "-T", "run-as", PACKAGE, "rm", "-f", temporary)
    for name, expected in original.items():
        if read_history_file(run, name) != expected:
            raise RuntimeError("History restore did not preserve the original bytes: " + name)
    return {"restored": True, "byteIdentical": True, "backupId": backup.name,
            "transport": BACKUP_TRANSPORT, "deviceHashesVerified": True,
            "files": {name: {"existed": digest is not None, "sha256": digest}
                      for name, digest in metadata["files"].items()}}


@contextmanager
def preserve_history(run, app, output):
    backup = backup_history(run, app)
    print("Private history recovery backup: " + str(backup), flush=True)
    try:
        yield backup
    finally:
        restored = restore_history(run, backup)
        (output / "history-preservation.json").write_text(json.dumps(restored, indent=2), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser()
    default_sdk = Path(os.environ.get("ANDROID_HOME", Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk"))
    parser.add_argument("--adb", default=str(default_sdk / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")))
    parser.add_argument("--serial")
    parser.add_argument("--history-only", action="store_true", help="Validate history migration without injecting gameplay scores")
    parser.add_argument("--output", type=Path, help="A new evidence directory; existing contents are never overwritten")
    parser.add_argument("--restore-history", type=Path, help="Recover a saved build/device-data-backups/<id> after interruption; runs no tests")
    args = parser.parse_args()
    app = Path(__file__).resolve().parents[1]
    adb = [args.adb] + (["-s", args.serial] if args.serial else [])

    def run(*command, binary=False, input_data=None):
        return subprocess.check_output([*adb, *command], input=input_data, text=not binary,
                                       encoding=None if binary else "utf-8", errors=None if binary else "replace",
                                       stderr=subprocess.PIPE)

    if args.restore_history:
        print(json.dumps(restore_history(run, args.restore_history.resolve()), indent=2))
        return
    if args.output is None:
        parser.error("--output is required when running acceptance")
    output = args.output.resolve()
    if output.exists() and any(output.iterdir()):
        raise SystemExit("Evidence directory must be new or empty")
    output.mkdir(parents=True, exist_ok=True)

    apk = app / "app/build/outputs/apk/debug/app-debug.apk"
    test_apk = app / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    if not apk.is_file() or not test_apk.is_file():
        raise SystemExit("Build assembleDebug and assembleDebugAndroidTest first")
    started = run("shell", "date", "+%m-%dT%H:%M:%S.000").strip().replace("T", " ")
    installation = run("install", "-r", str(apk)) + run("install", "-r", str(test_apk))
    (output / "install.txt").write_text(installation, encoding="utf-8")
    with preserve_history(run, app, output):
        collect_acceptance(run, args, app, output, apk, test_apk, started)


def collect_acceptance(run, args, app, output, apk, test_apk, started):
    arguments = ["-e", "history_only", "true"] if args.history_only else []
    transcript = run("shell", "am", "instrument", "-w", *arguments, "com.ethan.fruitslice.test/com.ethan.fruitslice.GameInstrumentation")
    (output / "instrumentation.txt").write_text(transcript, encoding="utf-8")
    if not re.search(r"PASS: \d+ device checks", transcript):
        failure = run("exec-out", "run-as", "com.ethan.fruitslice", "cat", "files/device-tests/device-failure.txt")
        (output / "failure.txt").write_text(failure, encoding="utf-8")
        raise SystemExit(transcript)
    checks_json = run("exec-out", "run-as", "com.ethan.fruitslice", "cat", "files/device-tests/device-checks.json")
    (output / "device-checks.json").write_text(checks_json, encoding="utf-8")
    device_checks = json.loads(checks_json)
    names = device_checks.get("screenshots")
    if names is None:
        names = run("shell", "run-as", "com.ethan.fruitslice", "ls", "files/device-tests").splitlines()
    for name in names:
        if re.fullmatch(r"\d\d-[a-z0-9-]+\.png", name):
            data = run("exec-out", "run-as", "com.ethan.fruitslice", "cat", "files/device-tests/" + name, binary=True)
            (output / name).write_bytes(data)
    runtime = run("logcat", "-d", "-T", started, "-v", "threadtime", "-s", "AndroidRuntime:E", "*:S")
    (output / "runtime.txt").write_text(runtime, encoding="utf-8")
    source_files = sorted(p for root in ("app/src", "gradle", "tools") for p in (app / root).rglob("*")
                          if p.is_file() and "classes" not in p.parts and "__pycache__" not in p.parts)
    source_files += [app / name for name in ("build.gradle", "settings.gradle", "gradle.properties", "app/build.gradle", "gradlew", "gradlew.bat")]
    manifest = {p.relative_to(app).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in source_files}
    (output / "source-sha256.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False), encoding="utf-8")
    source_commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=app, text=True).strip()
    report = {
        "verifiedAt": datetime.now(timezone(timedelta(hours=8))).isoformat(),
        "device": run("shell", "getprop", "ro.product.model").strip(),
        "android": run("shell", "getprop", "ro.build.version.release").strip(),
        "api": int(run("shell", "getprop", "ro.build.version.sdk").strip()),
        "apkSha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        "testApkSha256": hashlib.sha256(test_apk.read_bytes()).hexdigest(),
        "sourceCommit": source_commit,
        "sourceContent": "source-sha256.json is authoritative if working tree contains changes",
        "deviceChecks": device_checks,
        "runtimeAppCrash": "com.ethan.fruitslice" in runtime and "FATAL EXCEPTION" in runtime,
        "fixtureHistory": ("Migration examined, original preference files restored byte-for-byte in finally" if args.history_only
                           else "All synthetic gameplay uses isolated preferences; original preference files restored byte-for-byte in finally"),
    }
    (output / "verification.json").write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    print(transcript.strip())
    print("Evidence: " + str(output))


if __name__ == "__main__":
    main()
