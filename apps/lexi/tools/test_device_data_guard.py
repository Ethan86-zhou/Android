"""Exercise interruption recovery without a phone or real user data."""
import json
import hashlib
import base64
import shlex
from pathlib import Path
import tempfile
import unittest
from device_data_guard import preserve_wordbook, restore_wordbook


class DeviceDataGuardTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.files = {"lexi-wordbook.db": b"original database\r\n\x00\x1a\xff", "lexi-wordbook.db-wal": b"original wal"}
        self.installed = True
        self.serial = b"test-device-1"

    def command(self, *args, data=None):
        if args == ("get-serialno",):
            return self.serial
        if args[:3] == ("shell", "am", "force-stop"):
            return b""
        if args[:3] == ("shell", "pm", "path"):
            if not self.installed:
                raise RuntimeError("pm path exits 1 when the package is absent")
            return b"package:/data/app/lexi.apk\n"
        if args[:4] == ("shell", "pm", "list", "packages"):
            return b"package:com.ethan.lexi\npackage:com.ethan.lexi.test\n" if self.installed else b"package:com.ethan.lexi.test\n"
        if args[0] == "exec-out":
            return self.files[args[-1].split("/")[-1]]
        if args[0:2] != ("shell", "-T"):
            raise AssertionError("Data guard must use shell -T to preserve remote status: " + repr(args))
        operation = args[4]
        if operation == "dd":
            self.files[args[-1].split("/")[-1]] = data
            return b""
        if operation == "sh":
            script = shlex.split(shlex.split(args[-1])[0])
            self.files[script[4].split("/")[-1]] = base64.b64decode(self.files[script[2].split("/")[-1]])
            return b""
        if operation == "ls":
            return "\n".join(self.files).encode()
        if operation == "sha256sum":
            return (hashlib.sha256(self.files[args[-1].split("/")[-1]]).hexdigest() + "  " + args[-1]).encode()
        if operation == "mv":
            self.files[args[-1].split("/")[-1]] = self.files.pop(args[-2].split("/")[-1])
        if operation == "rm":
            self.files.pop(args[-1].split("/")[-1], None)
        return b""

    def guard(self):
        return preserve_wordbook([], self.root / "private-backup", self.root / "report.json", self.command)

    def test_process_failure_restores_original_and_removes_new_sidecars(self):
        original = dict(self.files)
        with self.assertRaisesRegex(RuntimeError, "instrumentation died"):
            with self.guard():
                self.assertEqual((self.root / "private-backup/lexi-wordbook.db").read_bytes(), original["lexi-wordbook.db"])
                self.files = {"lexi-wordbook.db": b"test data", "lexi-wordbook.db-shm": b"new sidecar"}
                raise RuntimeError("instrumentation died")
        self.assertEqual(self.files, original)
        self.assertTrue(json.loads((self.root / "report.json").read_text())["userDataRestored"])

    def test_keyboard_interrupt_also_restores(self):
        original = dict(self.files)
        with self.assertRaises(KeyboardInterrupt):
            with self.guard():
                self.files.clear()
                raise KeyboardInterrupt()
        self.assertEqual(self.files, original)

    def test_first_install_removes_only_new_wordbook_files(self):
        self.installed = False
        self.files.clear()
        with self.guard():
            self.installed = True
            self.files = {"lexi-wordbook.db": b"new test database", "other.db": b"unrelated"}
        self.assertEqual(self.files, {"other.db": b"unrelated"})

    def test_corrupt_host_backup_aborts_before_overwriting_device(self):
        with self.assertRaisesRegex(RuntimeError, "integrity check"):
            with self.guard():
                (self.root / "private-backup/lexi-wordbook.db").write_bytes(b"corruption")
                self.files["lexi-wordbook.db"] = b"latest device state"
        self.assertEqual(self.files["lexi-wordbook.db"], b"latest device state")
        self.assertFalse(json.loads((self.root / "report.json").read_text())["userDataRestored"])

    def test_switched_device_is_never_overwritten(self):
        with self.assertRaisesRegex(RuntimeError, "differs"):
            with self.guard():
                self.serial = b"other-phone"
                self.files = {"lexi-wordbook.db": b"other user data"}
        self.assertEqual(self.files, {"lexi-wordbook.db": b"other user data"})

    def test_standalone_restore_uses_saved_manifest_and_files(self):
        original = dict(self.files)
        with self.guard():
            pass
        self.files = {"lexi-wordbook.db": b"interrupted test state"}
        restore_wordbook([], self.root / "private-backup", self.root / "recovery.json", self.command)
        self.assertEqual(self.files, original)
        self.assertTrue(json.loads((self.root / "recovery.json").read_text())["userDataRestored"])

    def test_run_as_failure_never_becomes_empty_backup(self):
        original = dict(self.files)

        def denied(*args, data=None):
            if args == ("shell", "-T", "run-as", "com.ethan.lexi", "ls", "databases"):
                raise RuntimeError("run-as: package not debuggable")
            return self.command(*args, data=data)

        with self.assertRaisesRegex(RuntimeError, "not debuggable"):
            with preserve_wordbook([], self.root / "private-backup", self.root / "report.json", denied):
                self.fail("Tests must not start without a verified snapshot")
        self.assertEqual(self.files, original)
        self.assertFalse((self.root / "private-backup/manifest.json").exists())

    def test_converted_download_is_rejected_before_tests_start(self):
        def converted(*args, data=None):
            result = self.command(*args, data=data)
            return result.replace(b"\n", b"\r\n") if args[0] == "exec-out" else result

        with self.assertRaisesRegex(RuntimeError, "Binary download"):
            with preserve_wordbook([], self.root / "private-backup", self.root / "report.json", converted):
                self.fail("Corrupt download must not become a trusted backup")
        self.assertFalse((self.root / "private-backup/manifest.json").exists())

    def test_success_exit_with_truncated_upload_never_replaces_live_database(self):
        def truncated(*args, data=None):
            return self.command(*args, data=data[:4] if "dd" in args else data)

        with self.assertRaisesRegex(RuntimeError, "Binary upload"):
            with preserve_wordbook([], self.root / "private-backup", self.root / "report.json", truncated):
                self.files["lexi-wordbook.db"] = b"state before failed restore"
        self.assertEqual(self.files["lexi-wordbook.db"], b"state before failed restore")
        self.assertFalse(any("acceptance-restore" in name for name in self.files))
        self.assertFalse(json.loads((self.root / "report.json").read_text())["userDataRestored"])

    def test_unverified_legacy_manifest_is_not_restored(self):
        with self.guard():
            pass
        manifest = self.root / "private-backup/manifest.json"
        data = json.loads(manifest.read_text()); data.pop("backupFormat")
        manifest.write_text(json.dumps(data))
        with self.assertRaisesRegex(RuntimeError, "Legacy backup"):
            restore_wordbook([], manifest.parent, self.root / "recovery.json", self.command)


if __name__ == "__main__":
    unittest.main()
