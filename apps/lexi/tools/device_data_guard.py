"""Keep a durable, private host copy of the user's wordbook during device checks."""
from contextlib import contextmanager
import base64
import hashlib
import json
from pathlib import Path
import subprocess
import shlex
from uuid import uuid4

PACKAGE = "com.ethan.lexi"
DATABASE_FILES = {"lexi-wordbook.db", "lexi-wordbook.db-wal", "lexi-wordbook.db-shm", "lexi-wordbook.db-journal"}


def _command(adb, *args, data=None):
    result = subprocess.run([*adb, *args], input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        raise RuntimeError((result.stdout + result.stderr).decode("utf-8", "replace"))
    return result.stdout


def _names(command):
    try:
        result = command("shell", "-T", "run-as", PACKAGE, "ls", "databases")
    except RuntimeError as failure:
        if str(failure).strip() == "ls: databases: No such file or directory":
            return set()
        raise
    return set(result.decode("utf-8").splitlines()) & DATABASE_FILES


def _installed(command):
    packages = command("shell", "pm", "list", "packages", PACKAGE).splitlines()
    return ("package:" + PACKAGE).encode("utf-8") in packages


def _serial(command):
    serial = command("get-serialno").decode("utf-8").strip()
    if not serial or serial == "unknown":
        raise RuntimeError("Cannot identify the device for private wordbook backup")
    return serial


def _digest(command, path):
    # Control commands use shell v2 for reliable status; their output is ASCII only.
    result = command("shell", "-T", "run-as", PACKAGE, "sha256sum", path).decode("ascii").split()
    if len(result) != 2 or result[1] != path or len(result[0]) != 64 or any(c not in "0123456789abcdef" for c in result[0]):
        raise RuntimeError("Device checksum command returned an invalid result")
    return result[0]


def _read_verified(command, path):
    # Windows adb shell can expand LF on stdout. exec-out preserves bytes, but does
    # not propagate remote status, so verify against independent device checksums.
    before = _digest(command, path)
    data = command("exec-out", "run-as", PACKAGE, "cat", path)
    if hashlib.sha256(data).hexdigest() != before or _digest(command, path) != before:
        raise RuntimeError("Binary download does not match the device checksum")
    return data


def _stage_verified(command, path, data):
    # On Windows neither shell's binary stdin nor exec-in's completion can be
    # trusted. Upload only ASCII base64 via shell v2, then decode entirely inside
    # the app sandbox. Each shell call waits for remote completion and status.
    encoded = path + ".b64"
    try:
        command("shell", "-T", "run-as", PACKAGE, "dd", "of=" + encoded,
                data=base64.b64encode(data))
        script = "base64 -d " + shlex.quote(encoded) + " > " + shlex.quote(path)
        # adb joins command arguments before the device shell parses them: quote
        # the complete sh -c payload once so redirection runs under the app UID.
        command("shell", "-T", "run-as", PACKAGE, "sh", "-c", shlex.quote(script))
    finally:
        command("shell", "-T", "run-as", PACKAGE, "rm", "-f", encoded)
    if _read_verified(command, path) != data:
        raise RuntimeError("Binary upload does not match the private host backup")


def restore_wordbook(adb, backup: Path, report: Path, command=None):
    """Recover a completed host snapshot, including after the host process was killed."""
    if command is None:
        command = lambda *args, data=None: _command(adb, *args, data=data)
    manifest = json.loads((backup / "manifest.json").read_text(encoding="utf-8"))
    hashes = manifest.get("sha256", {})
    if manifest.get("backupFormat") != 2:
        raise RuntimeError("Legacy backup transport was not verified; do not restore it automatically")
    if manifest.get("package") != PACKAGE or not isinstance(hashes, dict) or not set(hashes) <= DATABASE_FILES:
        raise RuntimeError("Invalid private wordbook backup manifest")
    status = {"hostSnapshotCreated": True, "originalFiles": len(hashes), "userDataRestored": False}
    try:
        if manifest.get("deviceSerial") != _serial(command):
            raise RuntimeError("Connected device differs from the private backup device; refusing restore")
        # Verify the durable snapshot before touching any device data.
        for name, digest in hashes.items():
            if hashlib.sha256((backup / name).read_bytes()).hexdigest() != digest:
                raise RuntimeError("Private host wordbook backup failed integrity check")
        command("shell", "am", "force-stop", PACKAGE)
        if _installed(command):
            command("shell", "-T", "run-as", PACKAGE, "mkdir", "-p", "databases")
            staged = {}
            try:
                for name in sorted(hashes):
                    temporary = "databases/" + name + ".acceptance-restore-" + uuid4().hex
                    staged[name] = temporary
                    _stage_verified(command, temporary, (backup / name).read_bytes())
                for name, temporary in staged.items():
                    command("shell", "-T", "run-as", PACKAGE, "mv", temporary, "databases/" + name)
            finally:
                for temporary in staged.values():
                    command("shell", "-T", "run-as", PACKAGE, "rm", "-f", temporary)
            for name in sorted(_names(command) - set(hashes)):
                command("shell", "-T", "run-as", PACKAGE, "rm", "databases/" + name)
            restored = {name: hashlib.sha256(_read_verified(command,
                        "databases/" + name)).hexdigest() for name in _names(command)}
            if restored != hashes:
                raise RuntimeError("Device wordbook does not match private host backup after restore")
        elif manifest.get("installedBefore"):
            raise RuntimeError("Lexi disappeared during acceptance; private host backup retained")
        status["userDataRestored"] = True
        manifest["restored"] = True
        (backup / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    finally:
        report.write_text(json.dumps(status, indent=2), encoding="utf-8")


@contextmanager
def preserve_wordbook(adb, backup: Path, report: Path, command=None):
    """Restore even when instrumentation fails; keep host copies after interrupted runs.

    backup must be in the ignored app/build directory, never in public evidence.
    A killed host process cannot run finally; its backup and manifest remain available.
    """
    if command is None:
        command = lambda *args, data=None: _command(adb, *args, data=data)
    serial = _serial(command)
    command("shell", "am", "force-stop", PACKAGE)
    installed = _installed(command)
    backup.mkdir(parents=True, exist_ok=False)
    names = _names(command) if installed else set()
    hashes = {}
    for name in sorted(names):
        data = _read_verified(command, "databases/" + name)
        (backup / name).write_bytes(data)
        hashes[name] = hashlib.sha256(data).hexdigest()
    manifest = {"backupFormat": 2, "package": PACKAGE, "deviceSerial": serial, "installedBefore": installed,
                "sha256": hashes, "restored": False}
    (backup / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    try:
        yield
    finally:
        restore_wordbook(adb, backup, report, command)
