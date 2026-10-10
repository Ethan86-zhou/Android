"""Install this build, run device checks, and collect immutable evidence (Python stdlib)."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
from datetime import datetime, timezone, timedelta


def main():
    parser = argparse.ArgumentParser()
    default_sdk = Path(os.environ.get("ANDROID_HOME", Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk"))
    parser.add_argument("--adb", default=str(default_sdk / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")))
    parser.add_argument("--serial")
    parser.add_argument("--output", required=True, type=Path, help="A new evidence directory; existing contents are never overwritten")
    args = parser.parse_args()
    app = Path(__file__).resolve().parents[1]
    output = args.output.resolve()
    if output.exists() and any(output.iterdir()):
        raise SystemExit("Evidence directory must be new or empty")
    output.mkdir(parents=True, exist_ok=True)
    adb = [args.adb] + (["-s", args.serial] if args.serial else [])

    def run(*command, binary=False):
        return subprocess.check_output([*adb, *command], text=not binary, encoding=None if binary else "utf-8", errors=None if binary else "replace")

    apk = app / "app/build/outputs/apk/debug/app-debug.apk"
    test_apk = app / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    if not apk.is_file() or not test_apk.is_file():
        raise SystemExit("Build assembleDebug and assembleDebugAndroidTest first")
    started = run("shell", "date", "+%m-%dT%H:%M:%S.000").strip().replace("T", " ")
    installation = run("install", "-r", str(apk)) + run("install", "-r", str(test_apk))
    (output / "install.txt").write_text(installation, encoding="utf-8")
    transcript = run("shell", "am", "instrument", "-w", "com.ethan.fruitslice.test/com.ethan.fruitslice.GameInstrumentation")
    (output / "instrumentation.txt").write_text(transcript, encoding="utf-8")
    if not re.search(r"PASS: \d+ device checks", transcript):
        failure = run("exec-out", "run-as", "com.ethan.fruitslice", "cat", "files/device-tests/device-failure.txt")
        (output / "failure.txt").write_text(failure, encoding="utf-8")
        raise SystemExit(transcript)
    names = run("shell", "run-as", "com.ethan.fruitslice", "ls", "files/device-tests").splitlines()
    for name in names:
        if re.fullmatch(r"\d\d-[a-z0-9-]+\.png", name) or name == "device-checks.json":
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
        "deviceChecks": json.loads((output / "device-checks.json").read_text(encoding="utf-8")),
        "runtimeAppCrash": "com.ethan.fruitslice" in runtime and "FATAL EXCEPTION" in runtime,
        "fixtureHistory": "Device runner restores the original history and lifetime best after test scenes",
    }
    (output / "verification.json").write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    print(transcript.strip())
    print("Evidence: " + str(output))


if __name__ == "__main__":
    main()
