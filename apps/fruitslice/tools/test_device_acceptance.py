"""Host regression checks for history backup/recovery; no phone or user data is accessed."""
import json
import base64
import hashlib
from pathlib import Path
import subprocess
import shlex
import tempfile
import unittest

from device_acceptance import HISTORY_FILES, backup_history, preserve_history, restore_history


class FakeDevice:
    def __init__(self, files):
        self.files = dict(files)
        self.serial = "isolated-device"
        self.online = True
        self.run_as_allowed = True
        self.writes = 0
        self.read_transform = lambda data: data
        self.write_transform = lambda data: data

    def run(self, *command, binary=False, input_data=None):
        if not self.online:
            raise ConnectionError("device disconnected")
        if command == ("get-serialno",):
            return self.serial
        if command[:3] == ("shell", "am", "force-stop"):
            return ""
        if command[:3] == ("exec-out", "run-as", "com.ethan.fruitslice") and command[3] == "cat":
            if not binary:
                raise AssertionError("Raw backups require binary stdout")
            return self.read_transform(self.files[command[4]])
        if command[:4] != ("shell", "-T", "run-as", "com.ethan.fruitslice"):
            raise AssertionError(command)
        if not self.run_as_allowed:
            raise subprocess.CalledProcessError(1, command, b"", b"run-as: package not debuggable")
        if command[4] == "ls":
            if len(command) == 6:
                return "shared_prefs\n" if self.files else ""
            return "\n".join(name.removeprefix("shared_prefs/") for name in self.files)
        if command[4] == "sha256sum":
            return hashlib.sha256(self.files[command[5]]).hexdigest() + "  " + command[5] + "\r\n"
        if command[4:6] == ("rm", "-f"):
            self.files.pop(command[6], None)
            if command[6] in {"shared_prefs/" + name for name in HISTORY_FILES}:
                self.writes += 1
            return ""
        if command[4] == "mkdir":
            return ""
        if command[4] == "dd":
            if not binary or any(value not in b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=" for value in input_data):
                raise AssertionError("Only newline-free base64 may use shell stdin")
            self.files[command[5].removeprefix("of=")] = self.write_transform(input_data)
            return b""
        if command[4:6] == ("sh", "-c"):
            script = shlex.split(command[6])[0]
            tokens = shlex.split(script)
            if tokens[:2] != ["base64", "-d"] or tokens[3] != ">":
                raise AssertionError(command)
            self.files[tokens[4]] = base64.b64decode(self.files[tokens[2]], validate=True)
            return ""
        if command[4] == "mv":
            self.files[command[6]] = self.files.pop(command[5])
            self.writes += 1
            return ""
        raise AssertionError(command)


class HistoryPreservationTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.app = Path(self.directory.name)
        self.output = self.app / "evidence"
        self.output.mkdir()
        self.original = {"shared_prefs/fruit_slice_history.xml": b"original \xe4\xb8\xad\xe6\x96\x87\r\n",
                         "shared_prefs/fruit_slice_history.xml.bak": b"backup-before-atomic-write"}

    def tearDown(self):
        self.directory.cleanup()

    def test_finally_restores_both_files_after_failed_instrumentation(self):
        device = FakeDevice(self.original)
        with self.assertRaisesRegex(RuntimeError, "fixture failure"):
            with preserve_history(device.run, self.app, self.output) as backup:
                self.assertTrue((backup / "backup.json").is_file())
                device.files["shared_prefs/fruit_slice_history.xml"] = b"synthetic best + 321"
                device.files.pop("shared_prefs/fruit_slice_history.xml.bak")
                raise RuntimeError("fixture failure")
        self.assertEqual(device.files, self.original)
        self.assertTrue(json.loads((self.output / "history-preservation.json").read_text())["byteIdentical"])

    def test_empty_installation_restores_absence_without_clearing_other_preferences(self):
        device = FakeDevice({"shared_prefs/unrelated.xml": b"keep"})
        with preserve_history(device.run, self.app, self.output):
            for name in HISTORY_FILES:
                device.files["shared_prefs/" + name] = b"acceptance-created"
        self.assertEqual(device.files, {"shared_prefs/unrelated.xml": b"keep"})

    def test_empty_file_is_distinct_from_absence(self):
        device = FakeDevice({"shared_prefs/fruit_slice_history.xml": b""})
        backup = backup_history(device.run, self.app)
        device.files.clear()
        restore_history(device.run, backup)
        self.assertEqual(device.files, {"shared_prefs/fruit_slice_history.xml": b""})

    def test_disk_backup_recovers_after_disconnection_and_new_host_invocation(self):
        device = FakeDevice(self.original)
        backup = backup_history(device.run, self.app)
        device.files.clear()
        device.online = False
        with self.assertRaises(ConnectionError):
            restore_history(device.run, backup)
        device.online = True
        report = restore_history(device.run, Path(str(backup)))
        self.assertTrue(report["restored"])
        self.assertEqual(device.files, self.original)

    def test_wrong_device_is_rejected_before_any_writes(self):
        device = FakeDevice(self.original)
        backup = backup_history(device.run, self.app)
        device.serial = "a-different-device"
        with self.assertRaisesRegex(RuntimeError, "different device"):
            restore_history(device.run, backup)
        self.assertEqual(device.writes, 0)

    def test_corrupt_backup_is_rejected_before_any_writes(self):
        device = FakeDevice(self.original)
        backup = backup_history(device.run, self.app)
        (backup / HISTORY_FILES[1]).write_bytes(b"damaged")
        with self.assertRaisesRegex(RuntimeError, "checksum mismatch"):
            restore_history(device.run, backup)
        self.assertEqual(device.writes, 0)

    def test_success_still_preserves_exact_bytes(self):
        device = FakeDevice(self.original)
        with preserve_history(device.run, self.app, self.output):
            device.files["shared_prefs/fruit_slice_history.xml"] = b"normalized JSON"
        self.assertEqual(device.files, self.original)

    def test_run_as_denial_aborts_before_injection_instead_of_backing_up_empty_history(self):
        device = FakeDevice(self.original)
        device.run_as_allowed = False
        with self.assertRaises(subprocess.CalledProcessError):
            with preserve_history(device.run, self.app, self.output):
                self.fail("Never inject without a readable original history")
        self.assertEqual(device.files, self.original)
        self.assertFalse((self.output / "history-preservation.json").exists())

    def test_transformed_read_is_rejected_before_injection(self):
        device = FakeDevice(self.original)
        device.read_transform = lambda data: data.replace(b"\n", b"\r\n")
        with self.assertRaisesRegex(RuntimeError, "read transport checksum mismatch"):
            with preserve_history(device.run, self.app, self.output):
                self.fail("A text-translated backup must never permit tests to start")
        self.assertEqual(device.files, self.original)
        self.assertEqual(device.writes, 0)

    def test_truncated_ascii_upload_is_rejected_before_any_official_file_is_replaced(self):
        original = {"shared_prefs/fruit_slice_history.xml": b"first original\n",
                    "shared_prefs/fruit_slice_history.xml.bak": b"second original\x1aTAIL"}
        device = FakeDevice(original)
        backup = backup_history(device.run, self.app)
        current = {name: b"current test state" for name in original}
        device.files = dict(current)
        encoded_second = base64.b64encode(original["shared_prefs/fruit_slice_history.xml.bak"])
        device.write_transform = lambda data: data[:-4] if data == encoded_second else data
        with self.assertRaisesRegex(RuntimeError, "write transport checksum mismatch"):
            restore_history(device.run, backup)
        self.assertEqual({name: device.files[name] for name in original}, current)
        self.assertEqual(device.writes, 0)
        self.assertEqual(device.files, current, "Failed staging cleans only its private temporary files")

    def test_all_byte_values_round_trip_without_newline_or_ctrl_z_conversion(self):
        original = {"shared_prefs/fruit_slice_history.xml": bytes(range(256)) * 4096}
        device = FakeDevice(original)
        with preserve_history(device.run, self.app, self.output):
            device.files["shared_prefs/fruit_slice_history.xml"] = b"temporary fixture"
        self.assertEqual(device.files, original)
        report = json.loads((self.output / "history-preservation.json").read_text())
        self.assertTrue(report["deviceHashesVerified"])

    def test_legacy_unverified_backup_is_never_written_automatically(self):
        device = FakeDevice(self.original)
        backup = backup_history(device.run, self.app)
        manifest = json.loads((backup / "backup.json").read_text())
        manifest.pop("transport")
        (backup / "backup.json").write_text(json.dumps(manifest))
        with self.assertRaisesRegex(RuntimeError, "Legacy backup lacks device-verified raw bytes"):
            restore_history(device.run, backup)
        self.assertEqual(device.files, self.original)
        self.assertEqual(device.writes, 0)


if __name__ == "__main__":
    unittest.main()
