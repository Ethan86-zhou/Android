"""Install and verify one Lexi build on a connected device; retain immutable evidence."""
import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time
import zipfile
from uuid import uuid4
from device_data_guard import preserve_wordbook, restore_wordbook


PACKAGE = "com.ethan.lexi"


def main():
    parser = argparse.ArgumentParser()
    sdk = Path(os.environ.get("ANDROID_HOME", Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk"))
    parser.add_argument("--adb", default=str(sdk / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")))
    parser.add_argument("--serial")
    parser.add_argument("--restore-backup", type=Path, help="Restore one private host snapshot after interrupted acceptance; skips install and testing")
    parser.add_argument("--airplane-mode", action="store_true", help="Temporarily disable radios for offline checks, then restore airplane/Wi-Fi state")
    parser.add_argument("--output", required=True, type=Path, help="New or empty evidence directory")
    args = parser.parse_args()
    app = Path(__file__).resolve().parents[1]
    output = args.output.resolve()
    if output.exists() and any(output.iterdir()):
        raise SystemExit("Evidence directory is not empty; use a new directory")
    output.mkdir(parents=True, exist_ok=True)
    adb = [args.adb] + (["-s", args.serial] if args.serial else [])
    if not args.serial:
        selected = subprocess.check_output([*adb, "get-serialno"], text=True).strip()
        if not selected or selected == "unknown":
            raise SystemExit("Cannot identify the device; supply --serial")
        adb.extend(["-s", selected])
    if args.restore_backup:
        restore_wordbook(adb, args.restore_backup.resolve(), output / "data-preservation.json")
        print("Original wordbook restored and verified; evidence: " + str(output))
        return

    def run(*command, binary=False):
        result = subprocess.run([*adb, *command], stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
        if result.returncode:
            message = result.stdout.decode("utf-8", "replace") + result.stderr.decode("utf-8", "replace")
            raise RuntimeError(message)
        return result.stdout if binary else result.stdout.decode("utf-8", "replace")

    apk = app / "app/build/outputs/apk/debug/app-debug.apk"
    test_apk = app / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    if not apk.is_file() or not test_apk.is_file():
        raise SystemExit("Build assembleDebug and assembleDebugAndroidTest first")
    apk_hash = hashlib.sha256(apk.read_bytes()).hexdigest()
    test_apk_hash = hashlib.sha256(test_apk.read_bytes()).hexdigest()
    packaged_assets = {}
    with zipfile.ZipFile(apk) as archive:
        source_assets = {"assets/" + p.relative_to(app / "app/src/main/assets").as_posix(): p
                         for p in (app / "app/src/main/assets").rglob("*") if p.is_file()}
        if set(source_assets) != {name for name in archive.namelist() if name.startswith("assets/")}:
            raise SystemExit("APK assets differ from source; rebuild cleanly before acceptance")
        for name, path in source_assets.items():
            packaged_hash = hashlib.sha256(archive.read(name)).hexdigest()
            if packaged_hash != hashlib.sha256(path.read_bytes()).hexdigest():
                raise SystemExit("APK contains a stale asset: " + name)
            packaged_assets[name] = packaged_hash
    # Capture the source state before installation: later edits must never change this record.
    source_paths = set()
    for name in ("app/src", "gradle", "tools"):
        for path in (app / name).rglob("*"):
            if path.is_file() and "__pycache__" not in path.parts and "classes" not in path.parts:
                source_paths.add(path)
    for name in ("build.gradle", "settings.gradle", "gradle.properties", "app/build.gradle", "gradlew", "gradlew.bat", "README.md"):
        path = app / name
        if path.is_file():
            source_paths.add(path)
    source_manifest = {p.relative_to(app).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(source_paths)}
    source_manifest["../../scripts/android_data_transaction.py"] = hashlib.sha256(
        (app.parents[1] / "scripts/android_data_transaction.py").read_bytes()).hexdigest()
    (output / "source-sha256.json").write_text(json.dumps(source_manifest, indent=2, ensure_ascii=False), encoding="utf-8")
    source_commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=app, text=True).strip()
    source_scope = ["app/src", "gradle", "tools", "build.gradle", "settings.gradle", "gradle.properties", "app/build.gradle", "gradlew", "gradlew.bat", "README.md", "../../scripts/android_data_transaction.py"]
    source_status = subprocess.check_output(["git", "status", "--porcelain", "--untracked-files=all", "--", *source_scope], cwd=app, text=True)
    (output / "source-status.txt").write_text(source_status, encoding="utf-8")
    at = datetime.now(timezone(timedelta(hours=8)))
    device_evidence = "device-tests/" + at.strftime("%Y%m%dT%H%M%S-%f")
    private_backup = app / "build/device-backups" / uuid4().hex
    with preserve_wordbook(adb, private_backup, output / "data-preservation.json"):
        started = run("shell", "date", "+%m-%dT%H:%M:%S.000").strip().replace("T", " ")
        (output / "install.txt").write_text(run("install", "-r", str(apk)) + run("install", "-r", str(test_apk)), encoding="utf-8")
        run("shell", "am", "force-stop", PACKAGE)
        run("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        run("shell", "wm", "dismiss-keyguard")
        connectivity = {"airplaneModeBefore": run("shell", "settings", "get", "global", "airplane_mode_on").strip(),
                        "wifiBefore": run("shell", "settings", "get", "global", "wifi_on").strip(),
                        "bluetoothBefore": run("shell", "settings", "get", "global", "bluetooth_on").strip()}
        try:
            if args.airplane_mode:
                run("shell", "cmd", "connectivity", "airplane-mode", "enable")
                run("shell", "svc", "wifi", "disable")
                for _ in range(20):
                    if run("shell", "settings", "get", "global", "wifi_on").strip() != "1":
                        break
                    time.sleep(0.2)
                connectivity["airplaneModeDuringChecks"] = run("shell", "settings", "get", "global", "airplane_mode_on").strip()
                connectivity["wifiDuringChecks"] = run("shell", "settings", "get", "global", "wifi_on").strip()
                if connectivity["airplaneModeDuringChecks"] != "1" or connectivity["wifiDuringChecks"] == "1":
                    raise RuntimeError("Could not verify airplane mode with Wi-Fi disabled")
            transcript = run("shell", "am", "instrument", "-w", "-e", "evidence_dir", device_evidence,
                             PACKAGE + ".test/" + PACKAGE + ".DictionaryInstrumentation")
        finally:
            if args.airplane_mode:
                run("shell", "cmd", "connectivity", "airplane-mode", "enable" if connectivity["airplaneModeBefore"] == "1" else "disable")
                if connectivity["wifiBefore"] in {"0", "1"}:
                    run("shell", "svc", "wifi", "enable" if connectivity["wifiBefore"] == "1" else "disable")
                bluetooth_after = run("shell", "settings", "get", "global", "bluetooth_on").strip()
                if connectivity["bluetoothBefore"] in {"0", "1"} and bluetooth_after != connectivity["bluetoothBefore"]:
                    run("shell", "svc", "bluetooth", "enable" if connectivity["bluetoothBefore"] == "1" else "disable")
            connectivity["airplaneModeAfter"] = run("shell", "settings", "get", "global", "airplane_mode_on").strip()
            connectivity["wifiAfter"] = run("shell", "settings", "get", "global", "wifi_on").strip()
            connectivity["bluetoothAfter"] = run("shell", "settings", "get", "global", "bluetooth_on").strip()
            (output / "connectivity.json").write_text(json.dumps(connectivity, indent=2), encoding="utf-8")
        (output / "instrumentation.txt").write_text(transcript, encoding="utf-8")
        runtime = run("logcat", "-d", "-T", started, "-v", "threadtime", "-s", "AndroidRuntime:E", "*:S")
        (output / "runtime.txt").write_text(runtime, encoding="utf-8")
        passed = bool(re.search(r"PASS: \d+ device checks", transcript))
        record_name = "device-checks.json" if passed else "device-failure.txt"
        try:
            record = run("exec-out", "run-as", PACKAGE, "cat", "files/" + device_evidence + "/" + record_name)
            (output / record_name).write_text(record, encoding="utf-8")
        except RuntimeError as failure:
            (output / "evidence-pull-error.txt").write_text(str(failure), encoding="utf-8")
            record = "{}"
        checks = json.loads(record) if passed else {"passed": False}
        if passed:
            for name in checks.get("screenshots", []):
                if re.fullmatch(r"\d\d-[a-z0-9-]+\.png", name):
                    (output / name).write_bytes(run("exec-out", "run-as", PACKAGE, "cat", "files/" + device_evidence + "/" + name, binary=True))
            for name in checks.get("exportFiles", []):
                if re.fullmatch(r"wordbook-test\.(csv|md|json)", name):
                    (output / name).write_bytes(run("exec-out", "run-as", PACKAGE, "cat", "files/" + device_evidence + "/" + name, binary=True))
        report = {
            "verifiedAt": at.isoformat(),
            "device": run("shell", "getprop", "ro.product.model").strip(),
            "android": run("shell", "getprop", "ro.build.version.release").strip(),
            "api": int(run("shell", "getprop", "ro.build.version.sdk").strip()),
            "apkSha256": apk_hash,
            "testApkSha256": test_apk_hash,
            "packagedAssetSha256": packaged_assets,
            "sourceCommit": source_commit,
            "sourceDirty": bool(source_status.strip()),
            "sourceContent": "source-sha256.json records the exact source contents when this run began",
            "deviceEvidenceDirectory": device_evidence,
            "connectivity": connectivity,
            "deviceChecks": checks,
            "runtimeAppCrash": PACKAGE in runtime and "FATAL EXCEPTION" in runtime,
        }
        (output / "verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        if not passed or report["runtimeAppCrash"]:
            raise SystemExit(1)
    # Report success only after the host guard has restored and verified user data.
    print(transcript.strip())
    print("Host backup verified and original wordbook restored.")
    print("Evidence: " + str(output))


if __name__ == "__main__":
    main()
