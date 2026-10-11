"""Loopback-only, generated-tone HTTP fixture; never fetches QQ Music or logs credentials."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import io
import json
import math
from pathlib import Path
import re
import socket
import struct
import threading
from urllib.parse import parse_qs, urlsplit
import wave

HOST = "127.0.0.1"
PORT = 8765
RUN_PATTERN = re.compile(r"[A-Za-z0-9_-]{1,64}\Z")


def make_tone(frequency: int = 440) -> bytes:
    """Two seconds of our own PCM, no third-party copyrighted media."""
    output = io.BytesIO()
    with wave.open(output, "wb") as stream:
        stream.setparams((1, 2, 16000, 0, "NONE", "not compressed"))
        stream.writeframes(b"".join(struct.pack("<h", round(6000 * math.sin(2 * math.pi * frequency * index / 16000)))
                                    for index in range(32000)))
    return output.getvalue()


TONE = make_tone()
TONE_SHA256 = hashlib.sha256(TONE).hexdigest()
ETAG = '"tone-' + TONE_SHA256 + '"'
CHANGED_TONE = make_tone(660)
CHANGED_SHA256 = hashlib.sha256(CHANGED_TONE).hexdigest()
CHANGED_ETAG = '"tone-' + CHANGED_SHA256 + '"'


def parse_range(value: str | None, size: int) -> tuple[int, int, int]:
    """Return status, inclusive start/end. Intentionally rejects multipart ranges."""
    if not value:
        return 200, 0, size - 1
    match = re.fullmatch(r"bytes=(\d*)-(\d*)", value.strip())
    if not match or not any(match.groups()):
        raise ValueError("invalid or multiple range")
    first, last = match.groups()
    if not first:
        suffix = int(last)
        if suffix <= 0:
            raise ValueError("zero suffix")
        return 206, max(0, size - suffix), size - 1
    start, end = int(first), int(last) if last else size - 1
    if start >= size or end < start:
        raise ValueError("range outside resource")
    return 206, start, min(end, size - 1)


class FixtureServer:
    def __init__(self, run_id: str, log_path: Path | None = None, port: int = PORT):
        if not RUN_PATTERN.fullmatch(run_id):
            raise ValueError("invalid selftest run ID")
        self.run_id = run_id
        self.log_path = log_path
        self.events: list[dict] = []
        self.lock = threading.Lock()
        self.server = ThreadingHTTPServer((HOST, port), self._handler())
        self.server.daemon_threads = True
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.started = False
        self.closed = False

    def _record(self, event: dict):
        event["at"] = datetime.now(timezone.utc).isoformat()
        with self.lock:
            self.events.append(event)
            if self.log_path is not None:
                with self.log_path.open("a", encoding="utf-8") as log:
                    log.write(json.dumps(event, ensure_ascii=False) + "\n")

    def _handler(self):
        owner = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *_):
                pass

            def do_HEAD(self):
                self._serve(send_body=False)

            def do_GET(self):
                self._serve(send_body=True)

            def _serve(self, send_body: bool):
                location = urlsplit(self.path)
                query = parse_qs(location.query, keep_blank_values=True)
                filenames = {"full.wav", "partial.wav", "no-store.wav", "truncated.wav", "ignore-range.wav"}
                filename = location.path.removeprefix("/media/")
                if (not location.path.startswith("/media/") or filename not in filenames
                        or query.get("run") != [owner.run_id]
                        or set(query) - {"run", "variant"}
                        or query.get("variant", ["one"]) not in (["one"], ["two"], ["changed"], ["denied"], ["untouched"])):
                    self.send_error(404)
                    return
                request_range = self.headers.get("Range")
                variant = query.get("variant", ["one"])[0]
                if variant == "denied":
                    self.send_response(403)
                    self.send_header("Content-Type", "text/plain")
                    self.send_header("Content-Length", "0")
                    self.send_header("Cache-Control", "no-store")
                    self.end_headers()
                    owner._record({"file": filename, "status": 403, "range": request_range,
                                   "declaredBytes": 0, "bodyBytes": 0, "variant": variant})
                    return
                resource = CHANGED_TONE if variant == "changed" else TONE
                validator = CHANGED_ETAG if variant == "changed" else ETAG
                effective_range = request_range
                if filename == "ignore-range.wav" or self.headers.get("If-Range") not in (None, validator):
                    effective_range = None
                try:
                    status, start, end = parse_range(effective_range, len(resource))
                except ValueError:
                    self.send_response(416)
                    self.send_header("Content-Range", f"bytes */{len(resource)}")
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    owner._record({"file": filename, "status": 416, "range": request_range,
                                   "bodyBytes": 0, "variant": query.get("variant", ["one"])[0]})
                    return
                body = resource[start:end + 1]
                actual_body = body[:max(1, len(body) // 2)] if filename == "truncated.wav" else body
                self.send_response(status)
                self.send_header("Content-Type", "audio/wav")
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Accept-Ranges", "bytes")
                self.send_header("ETag", validator)
                self.send_header("Cache-Control", "no-store" if filename == "no-store.wav" else "public, max-age=3600")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.send_header("Access-Control-Expose-Headers", "Content-Range,Content-Length,ETag")
                if status == 206:
                    self.send_header("Content-Range", f"bytes {start}-{end}/{len(resource)}")
                if filename == "truncated.wav":
                    self.send_header("Connection", "close")
                self.end_headers()
                sent = 0
                try:
                    if send_body:
                        self.wfile.write(actual_body)
                        self.wfile.flush()
                        sent = len(actual_body)
                except (BrokenPipeError, ConnectionResetError):
                    pass
                finally:
                    owner._record({"file": filename, "status": status, "range": request_range,
                                   "start": start, "end": end, "declaredBytes": len(body),
                                   "bodyBytes": sent, "truncated": filename == "truncated.wav",
                                   "ignoredRange": filename == "ignore-range.wav" and request_range is not None,
                                   "etag": validator, "variant": variant})
                    if filename == "truncated.wav":
                        self.close_connection = True
                        try:
                            self.connection.shutdown(socket.SHUT_WR)
                        except OSError:
                            pass

        return Handler

    def start(self):
        self.thread.start()
        self.started = True
        return self

    def close(self):
        if self.closed:
            return
        if self.started:
            self.server.shutdown()
        self.server.server_close()
        if self.started:
            self.thread.join(timeout=3)
        self.closed = True

    def __enter__(self):
        return self.start()

    def __exit__(self, *_):
        self.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", required=True)
    parser.add_argument("--log", type=Path)
    args = parser.parse_args()
    with FixtureServer(args.run, args.log) as server:
        print(f"Selftest only: http://{HOST}:{server.port}/media/full.wav?run={args.run}&variant=one", flush=True)
        print(f"Generated tone: {len(TONE)} bytes; SHA-256 {TONE_SHA256}", flush=True)
        try:
            threading.Event().wait()
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()
