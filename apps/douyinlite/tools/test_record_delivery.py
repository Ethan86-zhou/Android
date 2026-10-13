"""交付源码哈希与真正 Git blob 一致，且不改变索引或丢弃未暂存输入。"""
import hashlib
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('record_delivery', Path(__file__).with_name('record_delivery.py'))
recorder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(recorder)


class SourceHashTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.original_root = recorder.ROOT
        self.original_app = recorder.APP
        recorder.ROOT = self.root
        recorder.APP = self.root / 'apps/douyinlite'
        self.git('init', '-q')
        (self.root / '.gitattributes').write_bytes(b'* text=auto eol=lf\n*.bat text eol=crlf\n*.bin binary\n')

    def tearDown(self):
        recorder.ROOT = self.original_root
        recorder.APP = self.original_app
        self.directory.cleanup()

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.root), *args], stderr=subprocess.PIPE)

    def compare_with_staging(self, name, content):
        path = self.root / name
        path.write_bytes(content)
        before = self.git('ls-files', '--stage')
        actual = recorder.normalized_source_digest(path, content)
        self.assertEqual(before, self.git('ls-files', '--stage'))
        self.assertEqual(content, path.read_bytes())
        self.git('add', '--', name)
        expected = hashlib.sha256(self.git('show', ':' + name)).hexdigest()
        self.assertEqual(expected, actual)
        return actual

    def test_new_crlf_source_matches_committed_lf(self):
        content = b'class Example {\r\n}\r\n'
        value = self.compare_with_staging('Example.java', content)
        self.assertNotEqual(hashlib.sha256(content).hexdigest(), value)

    def test_windows_wrapper_uses_git_blob_bytes(self):
        self.compare_with_staging('gradlew.bat', b'@echo off\r\nexit /b 0\r\n')

    def test_binary_is_not_newline_normalized(self):
        content = b'\0binary\r\n\xff\r\n'
        self.assertEqual(hashlib.sha256(content).hexdigest(), self.compare_with_staging('asset.bin', content))

    def test_unstaged_change_is_hashed_instead_of_stale_index(self):
        path = self.root / 'Example.java'
        path.write_bytes(b'old\n')
        self.git('add', 'Example.java')
        new = b'new\r\n'
        path.write_bytes(new)
        actual = recorder.normalized_source_digest(path, new)
        self.assertEqual(hashlib.sha256(b'new\n').hexdigest(), actual)
        self.assertEqual(b'old\n', self.git('show', ':Example.java'))

    def test_repository_evidence_outside_app_has_portable_reference(self):
        evidence = self.root / 'verification/result.json'
        evidence.parent.mkdir()
        evidence.write_bytes(b'{}\n')
        reference = recorder.reference(evidence)
        self.assertEqual('../../verification/result.json', reference['path'])
        self.assertEqual(hashlib.sha256(b'{}\n').hexdigest(), reference['sha256'])


if __name__ == '__main__':
    unittest.main()
