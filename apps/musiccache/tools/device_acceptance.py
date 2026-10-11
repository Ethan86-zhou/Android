"""Validate the installed WebView cache with generated media, preserving account data.

This proves the cache engine works through real WebView requests. It does NOT prove
QQ Music accepts embedded login, grants access to a song, or supplies cacheable media.
The device's Wi-Fi/mobile-data settings and the user's Chrome are never changed.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import sys
import time
import uuid

from fixture_server import CHANGED_SHA256, FixtureServer, PORT, TONE, TONE_SHA256

PACKAGE = "com.zhou.musiccache"
ACTIVITY = PACKAGE + "/.SelfTestActivity"
TEST_PROCESS = PACKAGE + ":cachetest"
PHASES = ("online", "offline", "partial_offline")
MIN_CHECKS = {"online": 27, "offline": 7, "partial_offline": 8}
LOCAL_TZ = timezone(timedelta(hours=8))


def digest(path: Path) -> str:
    with path.open("rb") as file:
        return hashlib.file_digest(file, "sha256").hexdigest()


def write_json(path: Path, value):
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def default_adb() -> str:
    found = shutil.which("adb")
    if found:
        return found
    sdk = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
               or str(Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk"))
    return str(sdk / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb"))


def source_manifest(app: Path, output: Path) -> dict:
    roots = ("app/src", "tools", "gradle")
    excluded = {"__pycache__", "classes", ".gradle", "build"}
    sources = [path for root in roots for path in (app / root).rglob("*")
               if path.is_file() and not set(path.relative_to(app).parts) & excluded]
    for name in ("build.gradle", "settings.gradle", "gradle.properties", "app/build.gradle", "gradlew", "gradlew.bat"):
        if (app / name).is_file():
            sources.append(app / name)
    manifest = {path.relative_to(app).as_posix(): digest(path) for path in sorted(set(sources))}
    write_json(output / "source-sha256.json", manifest)
    commit = subprocess.run(["git", "rev-parse", "HEAD"], cwd=app, capture_output=True, text=True, check=True).stdout.strip()
    status = subprocess.run(["git", "status", "--porcelain", "--", "."], cwd=app,
                            capture_output=True, text=True, check=True).stdout.strip()
    return {"baseCommit": commit, "appWorkingTreeDirty": bool(status),
            "sourceManifest": "source-sha256.json",
            "sourceManifestSha256": digest(output / "source-sha256.json"),
            "sourceNote": "Manifest hashes identify the tested working-tree source; baseCommit alone does not identify uncommitted code."}


class Device:
    def __init__(self, adb: str, serial: str | None):
        found = subprocess.run([adb, "devices"], capture_output=True, text=True, check=True).stdout
        available = [line.split()[0] for line in found.splitlines()[1:]
                     if len(line.split()) == 2 and line.split()[1] == "device"]
        if serial is None:
            if len(available) != 1:
                raise RuntimeError("Specify --serial when zero or multiple authorized devices are connected")
            serial = available[0]
        elif serial not in available:
            raise RuntimeError("The selected device is unavailable or USB debugging is not authorized")
        self.command = [adb, "-s", serial]

    def run(self, *arguments: str, check=True, timeout=35, binary=False):
        value = subprocess.run([*self.command, *arguments], capture_output=True,
                               text=not binary, encoding=None if binary else "utf-8",
                               errors=None if binary else "replace", timeout=timeout)
        if check and value.returncode:
            # Serial numbers and complete process output are deliberately absent.
            problem = value.stderr if not binary else value.stderr.decode("utf-8", "replace")
            raise RuntimeError(f"ADB action {arguments[0]} failed: {problem.strip()[:500]}")
        return value

    def text(self, *arguments: str, **options) -> str:
        return self.run(*arguments, **options).stdout.strip()

    def report(self, run_id: str, phase: str, timeout: int) -> dict:
        location = f"files/selftest/{run_id}/{phase}.json"
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            value = self.run("exec-out", "run-as", PACKAGE, "cat", location, check=False, timeout=8)
            if value.returncode == 0:
                try:
                    return json.loads(value.stdout)
                except json.JSONDecodeError:
                    pass  # The bridge may still be replacing its report atomically.
            time.sleep(0.5)
        raise RuntimeError(f"No selftest report arrived for {phase} within {timeout}s")


def process_ids(device: Device, name: str, timeout: float = 3) -> list[int]:
    value = device.run("shell", "pidof", name, check=False, timeout=timeout)
    if value.returncode not in (0, 1):
        raise RuntimeError(f"Cannot inspect the {name} process")
    if value.returncode == 1:
        return []
    tokens = value.stdout.split()
    if not tokens or any(not token.isdecimal() for token in tokens):
        raise RuntimeError(f"Unexpected process list for {name}")
    return sorted(int(token) for token in tokens)


def wait_for_test_exit(device: Device, timeout: float = 15) -> bool:
    """Observe only: the isolated Activity exits itself; this helper never terminates it."""
    deadline = time.monotonic() + timeout
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return False
        if not process_ids(device, TEST_PROCESS, timeout=min(3, remaining)):
            return True
        time.sleep(min(0.4, max(0, deadline - time.monotonic())))


def is_selftest_foreground(device: Device) -> bool:
    # Inspect Activity identifiers only. Do not save the full activity dump.
    state = device.text("shell", "dumpsys", "activity", "activities", timeout=3)
    component = re.compile(r"com\.zhou\.musiccache/(?:\.SelfTestActivity|com\.zhou\.musiccache\.SelfTestActivity)(?:\s|\})")
    return any(component.search(line) for line in state.splitlines()
               if "topResumedActivity=" in line or "mResumedActivity:" in line
               or "mResumedActivity=" in line)


def capture_selftest_screenshot(device: Device, destination: Path) -> bool:
    if not is_selftest_foreground(device):
        return False
    pixels = device.run("exec-out", "screencap", "-p", binary=True, timeout=3).stdout
    # The Activity auto-finishes shortly after its report. Discard a racing capture.
    if not is_selftest_foreground(device):
        return False
    destination.write_bytes(pixels)
    return True


def check_phase(report: dict, run_id: str, phase: str) -> list[dict]:
    checks = []

    def add(name, ok, detail):
        checks.append({"name": name, "ok": bool(ok), "detail": detail})

    add("report identity", report.get("schema") == 1 and report.get("run") == run_id
        and report.get("phase") == phase, {"run": report.get("run"), "phase": report.get("phase")})
    page_checks = report.get("checks", [])
    valid = (isinstance(page_checks, list) and len(page_checks) >= MIN_CHECKS[phase]
             and all(isinstance(item, dict) and item.get("ok") is True for item in page_checks))
    add("browser checks", report.get("passed") is True and valid, f"{len(page_checks)} checks")
    engine = report.get("engine", {})
    if not isinstance(engine, dict):
        engine = {}
    add("native cache counters", all(isinstance(engine.get(key), (int, float))
        for key in ("diskBytes", "networkBytes", "hitBytes", "blockedRequests",
                    "validationRequests", "validationBytes", "reusedSignedLinks")), engine)
    entries = engine.get("entries", [])
    by_key = {entry.get("key"): entry for entry in entries if isinstance(entry, dict)} if isinstance(entries, list) else {}
    def media_key(name, variant="one"):
        url = f"http://127.0.0.1:{PORT}/media/{name}?run={run_id}&variant={variant}"
        return hashlib.sha256(url.encode("utf-8")).hexdigest()
    complete = by_key.get(media_key("full.wav"), {})
    partial = by_key.get(media_key("partial.wav"), {})
    changed = by_key.get(media_key("full.wav", "changed"), {})
    add("complete resource coverage reported accurately", complete.get("complete") is True
        and complete.get("cachedBytes") == len(TONE) and complete.get("totalBytes") == len(TONE), complete)
    add("partial resource coverage reported accurately", partial.get("complete") is False
        and partial.get("cachedBytes") == 4096 and partial.get("totalBytes") == len(TONE), partial)
    add("changed representation stored separately", changed.get("complete") is True
        and changed.get("cachedBytes") == len(TONE) and changed.get("totalBytes") == len(TONE), changed)
    forbidden = (media_key("no-store.wav"), media_key("truncated.wav"), media_key("ignore-range.wav"),
                 media_key("full.wav", "two"), media_key("full.wav", "untouched"), media_key("full.wav", "denied"))
    add("uncacheable and unrequested resources absent", all(key not in by_key for key in forbidden),
        "No uncacheable, unauthorized or untouched entry; validated variant two is an alias without a second audio record")
    if phase == "online":
        add("native network actually used", engine.get("networkBytes", 0) >= len(TONE), engine.get("networkBytes"))
        add("cache persisted bytes", engine.get("diskBytes", 0) >= len(TONE) + 4096, engine.get("diskBytes"))
        reuse = report.get("signedReuse", {}).get("delta", {})
        add("validated signature reused without duplicate transfer or storage",
            reuse.get("validationRequests") == 1 and reuse.get("validationBytes") == 1
            and reuse.get("networkBytes") == 0 and reuse.get("diskBytes") == 0
            and reuse.get("hitBytes") == len(TONE) and reuse.get("reusedSignedLinks") == 1, reuse)
    else:
        add("native offline guard", report.get("nativeOffline") is True, report.get("nativeOffline"))
        add("zero native network bytes after restart", engine.get("networkBytes") == 0, engine.get("networkBytes"))
        add("no signature validation network requests while offline", engine.get("validationRequests") == 0
            and engine.get("validationBytes") == 0,
            {"requests": engine.get("validationRequests"), "bytes": engine.get("validationBytes")})
        add("native cache actually used", engine.get("hitBytes", 0) >= (len(TONE) if phase == "offline" else 4096),
            engine.get("hitBytes"))
        if phase == "partial_offline":
            add("missing media requests blocked", engine.get("blockedRequests", 0) >= 7, engine.get("blockedRequests"))
    return checks


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default=default_adb())
    parser.add_argument("--serial")
    parser.add_argument("--apk", required=True, type=Path, help="The exact debug APK to verify")
    parser.add_argument("--install", action="store_true", help="Update-install APK with -r, preserving app data; never uninstall")
    parser.add_argument("--output", required=True, type=Path, help="A new or empty evidence directory")
    parser.add_argument("--timeout", type=int, default=100, help="Maximum seconds per browser phase")
    args = parser.parse_args()
    app = Path(__file__).resolve().parents[1]
    apk, output = args.apk.resolve(), args.output.resolve()
    if not apk.is_file():
        parser.error("--apk does not exist")
    if output.exists() and (not output.is_dir() or any(output.iterdir())):
        parser.error("Evidence output must be new or empty; existing records are never overwritten")
    output.mkdir(parents=True, exist_ok=True)
    run_id = "test_" + datetime.now(LOCAL_TZ).strftime("%Y%m%d_%H%M%S_") + uuid.uuid4().hex[:8]
    report = {"schema": 1, "run": run_id, "startedAt": datetime.now(LOCAL_TZ).isoformat(),
              "scope": "Generated-tone WebView/cache selftest, not QQ Music account or real-song acceptance",
              "apkSha256": digest(apk), "fixtureSha256": TONE_SHA256, "fixtureBytes": len(TONE),
              "fixtureChangedSha256": CHANGED_SHA256,
              "accountData": "No Chrome access, no cookies read/exported, no app-data reset; isolated debug Activity, process, WebView profile and selftest cache.",
              "offlineMethod": "Stop the loopback media server; allow the isolated selftest process to exit itself, then launch its next phase in native offline mode. Normal app process and phone radios stay unchanged.",
              "installationRequested": args.install,
              "processBaselineNote": "Normal-process PID baseline is recorded after any explicitly requested APK update, since Android may stop the old package during installation.",
              "checks": [], "phases": {}}
    device = None
    server = None
    reverse_owned = False
    test_started = False
    test_exit_observed = False
    test_exit_wait_attempted = False
    normal_before = None

    def add(name, ok, detail):
        report["checks"].append({"name": name, "ok": bool(ok), "detail": detail})

    try:
        report.update(source_manifest(app, output))
        device = Device(args.adb, args.serial)
        report["device"] = {"model": device.text("shell", "getprop", "ro.product.model"),
                            "android": device.text("shell", "getprop", "ro.build.version.release"),
                            "api": device.text("shell", "getprop", "ro.build.version.sdk")}
        if args.install:
            installation = device.text("install", "-r", str(apk), timeout=120)
            (output / "install.txt").write_text(installation + "\n", encoding="utf-8")
            if "Success" not in installation:
                raise RuntimeError("Update installation did not report success; no uninstall or data reset attempted")
        installed_paths = device.text("shell", "pm", "path", PACKAGE).splitlines()
        if len(installed_paths) != 1 or not installed_paths[0].startswith("package:/"):
            raise RuntimeError("Expected a single installed debug APK for this package")
        installed_path = installed_paths[0].removeprefix("package:")
        installed_hash = device.text("shell", "sha256sum", installed_path).split()[0].lower()
        add("installed APK matches input", installed_hash == report["apkSha256"], installed_hash)
        if installed_hash != report["apkSha256"]:
            raise RuntimeError("Installed APK differs; use --install or install this exact APK first")
        device.text("exec-out", "run-as", PACKAGE, "pwd")  # Fail before launching if not debuggable.
        normal_before = process_ids(device, PACKAGE)
        report["normalPidsBefore"] = normal_before
        if not wait_for_test_exit(device):
            raise RuntimeError("An existing selftest process did not exit itself within 15s; no process was stopped")
        reverse_list = device.text("reverse", "--list")
        if any(f"tcp:{PORT}" in line.split()[1:2] for line in reverse_list.splitlines()):
            raise RuntimeError(f"Device reverse port {PORT} is already in use; preserving the existing mapping")
        server = FixtureServer(run_id, output / "fixture-requests.jsonl").start()
        device.text("reverse", f"tcp:{PORT}", f"tcp:{PORT}")
        reverse_owned = True
        pids = []
        for phase in PHASES:
            if phase == "offline":
                server.close()
                with socket.socket() as probe:
                    absent = probe.connect_ex(("127.0.0.1", PORT)) != 0
                add("media origin stopped before offline", absent, "Loopback listener is unreachable")
                if not absent:
                    raise RuntimeError("Loopback fixture port is still serving; offline evidence would be ambiguous")
            print(f"WebView selftest: {phase}", flush=True)
            launch = device.text("shell", "am", "start", "-W", "-n", ACTIVITY,
                                 "--ez", "selftest", "true", "--es", "selftest_run", run_id,
                                 "--es", "selftest_phase", phase, "--es", "selftest_url", f"http://127.0.0.1:{PORT}/")
            test_started = True
            test_exit_observed = False
            test_exit_wait_attempted = False
            (output / f"launch-{phase}.txt").write_text(launch + "\n", encoding="utf-8")
            native_report = device.report(run_id, phase, args.timeout)
            write_json(output / f"{phase}.json", native_report)
            phase_checks = check_phase(native_report, run_id, phase)
            report["checks"].extend(dict(item, phase=phase) for item in phase_checks)
            report["phases"][phase] = {"report": f"{phase}.json", "passed": all(item["ok"] for item in phase_checks)}
            pids.append(native_report.get("pid"))
            # Screenshots are confined to the selftest UI; never capture the normal login page.
            if native_report.get("run") == run_id and native_report.get("phase") == phase:
                captured = capture_selftest_screenshot(device, output / f"{phase}.png")
                report["phases"][phase]["screenshot"] = f"{phase}.png" if captured else None
                report["phases"][phase]["screenshotNote"] = (
                    "SelfTestActivity confirmed in foreground before and after capture" if captured
                    else "Omitted because SelfTestActivity was no longer confirmed in foreground")
            test_exit_wait_attempted = True
            test_exit_observed = wait_for_test_exit(device)
            add("isolated selftest process exited itself", test_exit_observed, phase)
            if not test_exit_observed:
                raise RuntimeError(f"Selftest process for {phase} did not exit itself within 15s; no process was stopped")
            normal_after_phase = process_ids(device, PACKAGE)
            report["phases"][phase]["normalPidsAfter"] = normal_after_phase
            add("normal app process unchanged", normal_after_phase == normal_before,
                {"phase": phase, "before": normal_before, "after": normal_after_phase})
            if normal_after_phase != normal_before:
                raise RuntimeError("Normal app process changed during selftest; no restart or recovery action attempted")
            if not report["phases"][phase]["passed"]:
                raise RuntimeError(f"Phase {phase} failed; inspect {phase}.json and verification.json")
        add("distinct isolated selftest processes", all(isinstance(pid, int) and pid > 0 for pid in pids)
            and len(set(pids)) == len(PHASES), pids)
        with server.lock:
            events = list(server.events)
        add("origin saw actual two Range requests", all(any(event.get("file") == "full.wav"
            and event.get("status") == 206 and event.get("range") == expected for event in events)
            for expected in ("bytes=0-32767", "bytes=32768-64043")), f"{len(events)} origin requests")
        alias_requests = [event for event in events if event.get("file") == "full.wav" and event.get("variant") == "two"]
        add("new signature used exactly one one-byte origin validation", len(alias_requests) == 1
            and alias_requests[0].get("status") == 206 and alias_requests[0].get("range") == "bytes=0-0"
            and alias_requests[0].get("declaredBytes") == 1 and alias_requests[0].get("bodyBytes") == 1,
            alias_requests)
        changed_requests = [event for event in events if event.get("file") == "full.wav" and event.get("variant") == "changed"]
        add("changed representation was validated then downloaded from origin", len(changed_requests) == 2
            and any(event.get("status") == 206 and event.get("range") == "bytes=0-0" and event.get("bodyBytes") == 1
                    for event in changed_requests)
            and any(event.get("status") == 200 and event.get("range") is None and event.get("bodyBytes") == len(TONE)
                    for event in changed_requests), changed_requests)
        denied_requests = [event for event in events if event.get("file") == "full.wav" and event.get("variant") == "denied"]
        add("authorization rejection stopped further audio requests", len(denied_requests) == 1
            and denied_requests[0].get("status") == 403 and denied_requests[0].get("range") == "bytes=0-0",
            denied_requests)
        add("untouched signature never reached origin", not any(event.get("variant") == "untouched" for event in events),
            "The untouched signature is tested only after the media server is stopped")
        add("origin actually ignored a Range request", any(event.get("file") == "ignore-range.wav"
            and event.get("status") == 200 and event.get("range") == "bytes=100-399"
            and event.get("declaredBytes") == len(TONE) for event in events),
            "Source fixture sent a full 200 response to bytes=100-399")
    except Exception as error:
        report["error"] = f"{type(error).__name__}: {error}"
        add("test workflow completed", False, report["error"])
    finally:
        if server is not None:
            server.close()
        if device is not None and reverse_owned:
            try:
                device.text("reverse", "--remove", f"tcp:{PORT}")
                add("owned reverse mapping removed", True, PORT)
            except Exception as error:
                add("owned reverse mapping removed", False, str(error))
        if device is not None and test_started and not test_exit_wait_attempted:
            try:
                exited = wait_for_test_exit(device)
                add("isolated selftest exited after incomplete workflow", exited,
                    "Only observed its own exit; no process termination or normal Activity restart requested")
            except Exception as error:
                add("isolated selftest exit observed", False, str(error))
        if device is not None and normal_before is not None:
            try:
                normal_after = process_ids(device, PACKAGE)
                report["normalPidsAfter"] = normal_after
                add("normal app process preserved throughout", normal_before == normal_after,
                    {"before": normal_before, "after": normal_after})
            except Exception as error:
                add("normal app process preservation verified", False, str(error))
        report["finishedAt"] = datetime.now(LOCAL_TZ).isoformat()
        report["passed"] = bool(report["checks"]) and all(item["ok"] for item in report["checks"])
        write_json(output / "verification.json", report)
        summary = ["网页缓存验证器：合成测试音设备验收", "",
                   "范围：真实 Android WebView + App 缓存引擎；不代表 QQ 音乐登录后歌曲验收。",
                   "方法：在线请求 → 持久缓存 → 独立自测进程自行退出 → 关闭测试音服务器 → 新自测进程离线重播。",
                   "安全隔离：专用 SelfTestActivity 与 :cachetest 进程、独立目录和 WebView profile；正常 App 不重启、不清空数据。",
                   "前台确认是自测页才保存截图；正常进程 PID 前后对照写入 verification.json。",
                   "手机 Wi-Fi 和移动数据设置未改动。", "",
                   f"结果：{'通过' if report['passed'] else '失败'}", f"APK SHA-256：{report['apkSha256']}",
                   f"源码基线：{report.get('baseCommit', '未读取')}（准确源码见 source-sha256.json）", ""]
        summary.extend(f"{'通过' if item['ok'] else '失败'}：{item.get('phase', '')} {item['name']}"
                       for item in report["checks"])
        if report.get("error"):
            summary.extend(["", report["error"]])
        (output / "验证结果.txt").write_text("\n".join(summary) + "\n", encoding="utf-8")
    print(f"{'PASS' if report['passed'] else 'FAIL'} — evidence: {output}")
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
