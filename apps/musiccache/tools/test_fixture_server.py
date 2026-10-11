"""Host-side checks for the HTTP evidence fixture, without touching any phone."""
import hashlib
import http.client
import unittest

from fixture_server import (CHANGED_ETAG, CHANGED_SHA256, CHANGED_TONE,
                            ETAG, FixtureServer, TONE, TONE_SHA256, parse_range)


class FixtureTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = FixtureServer("host_test", port=0).start()

    @classmethod
    def tearDownClass(cls):
        cls.server.close()

    def request(self, filename="full.wav", headers=None, query="run=host_test&variant=one"):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.port, timeout=3)
        self.addCleanup(connection.close)
        connection.request("GET", f"/media/{filename}?{query}", headers=headers or {})
        return connection.getresponse()

    def test_generated_tone_identity(self):
        self.assertEqual(len(TONE), 64044)
        self.assertEqual(TONE[:4], b"RIFF")
        self.assertEqual(TONE_SHA256, "9a444b5973a1c31fc641521b32e9eda0318e45c2f7b9c7979d438fde47ad60c5")

    def test_complete_response(self):
        response = self.request()
        self.assertEqual(response.status, 200)
        self.assertEqual(response.getheader("ETag"), ETAG)
        self.assertEqual(hashlib.sha256(response.read()).hexdigest(), TONE_SHA256)

    def test_two_ranges_reconstruct_exact_bytes(self):
        chunks = []
        for start, end in ((0, 32767), (32768, 64043)):
            response = self.request(headers={"Range": f"bytes={start}-{end}"})
            self.assertEqual(response.status, 206)
            self.assertEqual(response.getheader("Content-Range"), f"bytes {start}-{end}/64044")
            self.assertEqual(response.getheader("Content-Length"), str(end - start + 1))
            self.assertEqual(sum(name.lower() == "content-length" for name, _ in response.getheaders()), 1)
            chunks.append(response.read())
        self.assertEqual(b"".join(chunks), TONE)

    def test_suffix_open_clipped_ranges(self):
        for request, expected in (("bytes=-10", (206, 64034, 64043)),
                                  ("bytes=64000-", (206, 64000, 64043)),
                                  ("bytes=64000-90000", (206, 64000, 64043))):
            with self.subTest(request=request):
                self.assertEqual(parse_range(request, 64044), expected)

    def test_invalid_ranges(self):
        for value in ("bytes=64044-", "bytes=20-10", "bytes=-0", "bytes=-", "bytes=0-1,3-4"):
            with self.subTest(value=value):
                response = self.request(headers={"Range": value})
                self.assertEqual(response.status, 416)
                self.assertEqual(response.getheader("Content-Range"), "bytes */64044")
                self.assertEqual(response.read(), b"")

    def test_changed_if_range_returns_full_representation(self):
        response = self.request(headers={"Range": "bytes=10-20", "If-Range": '"old"'})
        self.assertEqual(response.status, 200)
        self.assertEqual(response.read(), TONE)

    def test_no_store_header(self):
        response = self.request("no-store.wav")
        self.assertEqual(response.getheader("Cache-Control"), "no-store")
        self.assertEqual(response.read(), TONE)

    def test_ignore_range_returns_whole_200_response(self):
        response = self.request("ignore-range.wav", {"Range": "bytes=100-399"})
        self.assertEqual(response.status, 200)
        self.assertIsNone(response.getheader("Content-Range"))
        self.assertEqual(response.getheader("Content-Length"), str(len(TONE)))
        self.assertEqual(response.read(), TONE)

    def test_new_signature_has_same_strong_validator_and_one_byte_probe(self):
        response = self.request(headers={"Range": "bytes=0-0"}, query="run=host_test&variant=two")
        self.assertEqual(response.status, 206)
        self.assertEqual(response.getheader("ETag"), ETAG)
        self.assertEqual(response.getheader("Content-Range"), f"bytes 0-0/{len(TONE)}")
        self.assertEqual(response.read(), TONE[:1])

    def test_changed_signature_has_new_validator_and_actual_different_audio(self):
        response = self.request(headers={"Range": "bytes=0-0"}, query="run=host_test&variant=changed")
        self.assertEqual(response.status, 206)
        self.assertEqual(response.getheader("ETag"), CHANGED_ETAG)
        self.assertNotEqual(CHANGED_ETAG, ETAG)
        self.assertEqual(response.read(), CHANGED_TONE[:1])
        full = self.request(query="run=host_test&variant=changed")
        self.assertEqual(full.status, 200)
        self.assertEqual(hashlib.sha256(full.read()).hexdigest(), CHANGED_SHA256)
        self.assertNotEqual(CHANGED_SHA256, TONE_SHA256)

    def test_denied_signature_returns_403_even_for_one_byte_probe(self):
        response = self.request(headers={"Range": "bytes=0-0"}, query="run=host_test&variant=denied")
        self.assertEqual(response.status, 403)
        self.assertEqual(response.getheader("Content-Length"), "0")
        self.assertEqual(response.read(), b"")

    def test_truncated_body_is_observable(self):
        response = self.request("truncated.wav", {"Range": "bytes=0-8191"})
        self.assertEqual(response.status, 206)
        self.assertEqual(response.getheader("Content-Length"), "8192")
        with self.assertRaises(http.client.IncompleteRead) as failure:
            response.read()
        self.assertEqual(len(failure.exception.partial), 4096)

    def test_wrong_namespace_and_unknown_query_rejected(self):
        for query in ("run=another", "run=host_test&secret=never-log"):
            with self.subTest(query=query):
                response = self.request(query=query)
                self.assertEqual(response.status, 404)
                response.read()


if __name__ == "__main__":
    unittest.main(verbosity=2)
