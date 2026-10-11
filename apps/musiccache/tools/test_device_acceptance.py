"""Safety-boundary tests for the device harness; these never contact ADB or a phone."""
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from device_acceptance import (ACTIVITY, TEST_PROCESS, capture_selftest_screenshot,
                               process_ids, wait_for_test_exit)


class FakeDevice:
    def __init__(self, process_results=(), foreground_results=()):
        self.process_results = iter(process_results)
        self.foreground_results = iter(foreground_results)
        self.actions = []

    def run(self, *arguments, **options):
        self.actions.append(arguments)
        if arguments == ("shell", "pidof", TEST_PROCESS):
            code, output = next(self.process_results)
            return subprocess.CompletedProcess(arguments, code, output, "")
        if arguments == ("exec-out", "screencap", "-p"):
            return subprocess.CompletedProcess(arguments, 0, b"test-png", b"")
        raise AssertionError(f"Unexpected device action: {arguments}")

    def text(self, *arguments, **options):
        self.actions.append(arguments)
        if arguments == ("shell", "dumpsys", "activity", "activities"):
            return next(self.foreground_results)
        raise AssertionError(f"Unexpected device action: {arguments}")


class HarnessSafetyTests(unittest.TestCase):
    def test_entry_is_only_dedicated_activity(self):
        self.assertEqual(ACTIVITY, "com.zhou.musiccache/.SelfTestActivity")

    def test_process_exit_wait_only_observes_and_never_terminates(self):
        device = FakeDevice(process_results=((0, "456\n"), (1, "")))
        with patch("device_acceptance.time.sleep"):
            self.assertTrue(wait_for_test_exit(device))
        self.assertEqual(device.actions, [("shell", "pidof", TEST_PROCESS)] * 2)

    def test_process_exit_timeout_has_no_fallback_action(self):
        device = FakeDevice(process_results=((0, "456"), (0, "456")))
        tick = iter((0, .25, .5, .75, 1, 1.25))
        with patch("device_acceptance.time.monotonic", side_effect=lambda: next(tick)), \
                patch("device_acceptance.time.sleep"):
            self.assertFalse(wait_for_test_exit(device, timeout=.9))
        self.assertEqual(device.actions, [("shell", "pidof", TEST_PROCESS)] * 2)

    def test_adb_error_is_not_mistaken_for_process_exit(self):
        device = FakeDevice(process_results=((2, ""),))
        with self.assertRaises(RuntimeError):
            process_ids(device, TEST_PROCESS)

    def test_screenshot_is_not_requested_on_normal_page(self):
        device = FakeDevice(foreground_results=(
            "topResumedActivity=ActivityRecord{1 com.zhou.musiccache/.MainActivity t2}",))
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "evidence.png"
            self.assertFalse(capture_selftest_screenshot(device, destination))
            self.assertFalse(destination.exists())
        self.assertEqual(device.actions, [("shell", "dumpsys", "activity", "activities")])

    def test_screenshot_discarded_if_test_activity_exits_during_capture(self):
        device = FakeDevice(foreground_results=(
            "topResumedActivity=ActivityRecord{1 com.zhou.musiccache/.SelfTestActivity t2}",
            "topResumedActivity=ActivityRecord{1 com.zhou.musiccache/.MainActivity t2}"))
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "evidence.png"
            self.assertFalse(capture_selftest_screenshot(device, destination))
            self.assertFalse(destination.exists())

    def test_screenshot_saved_only_when_test_activity_remains_foreground(self):
        activity = "topResumedActivity=ActivityRecord{1 com.zhou.musiccache/.SelfTestActivity t2}"
        device = FakeDevice(foreground_results=(activity, activity))
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "evidence.png"
            self.assertTrue(capture_selftest_screenshot(device, destination))
            self.assertEqual(destination.read_bytes(), b"test-png")


if __name__ == "__main__":
    unittest.main(verbosity=2)
