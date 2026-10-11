// Exercise the actual fixture helpers with simulated browser responses, without ADB/network.
import assert from "node:assert/strict";
import fs from "node:fs";
import {webcrypto} from "node:crypto";
import test from "node:test";
import vm from "node:vm";

const html = fs.readFileSync(new URL("./fixture/fixture.html", import.meta.url), "utf8");
const pageScript = html.match(/<script>([\s\S]*?)<\/script>/)[1];
const startOfAutomaticTest = pageScript.indexOf("\ntest().catch(");
assert.ok(startOfAutomaticTest > 0, "Fixture bootstrap must be recognizable");
const helpers = pageScript.slice(0, startOfAutomaticTest)
  .replace("__SELFTEST_RUN_JSON__", '"host_test"')
  .replace("__SELFTEST_PHASE_JSON__", '"online"')
  .replace("__SELFTEST_BASE_JSON__", '"http://127.0.0.1:8765"');

function page(fetch, snapshots = []) {
  const context = vm.createContext({
    fetch, AbortController, TypeError, Uint8Array, setTimeout, clearTimeout,
    crypto: webcrypto,
    MusicCacheSelfTest: {snapshot: () => JSON.stringify(snapshots.shift())},
    document: {
      getElementById: () => ({textContent: "", append() {}}),
      createElement: () => ({className: "", textContent: ""})
    }
  });
  vm.runInContext(helpers + `\nglobalThis.api = {
    getBytes, hasExactLength, assertMissing, assertTruncated, assertIgnoredRangeRejected,
    verifySignedReuse, verifyChangedSignature, verifyDeniedSignature,
    checks: () => result.checks
  };`, context);
  return context.api;
}

function response(status, bytes, length = String(bytes.length), read = () => {}) {
  return {status, ok: status >= 200 && status <= 299,
    headers: {get: name => name === "Content-Length" ? length : "bytes 100-399/64044"},
    arrayBuffer: async () => {read(); return new Uint8Array(bytes).buffer;}};
}

test("Content-Length must be a single numeric value equal to the consumed body", () => {
  const api = page(async () => {throw Error("No request expected");});
  for (const length of ["300, 300", "0, 300", "", null, "299", "301", "+300"])
    assert.equal(api.hasExactLength({contentLength: length, bytes: new Uint8Array(300)}, 300), false);
  assert.equal(api.hasExactLength({contentLength: "300", bytes: new Uint8Array(300)}, 300), true);
  assert.equal(api.hasExactLength({contentLength: "0", bytes: new Uint8Array(0)}, 0), true);
});

const initialCounters = {networkBytes: 64044, hitBytes: 0, diskBytes: 64044,
  validationRequests: 0, validationBytes: 0, reusedSignedLinks: 0};

function tone(frequency = 440) {
  const value = Buffer.alloc(64044);
  Buffer.from("5249464624fa000057415645666d74201000000001000100803e0000007d0000020010006461746100fa0000", "hex").copy(value);
  for (let index = 0; index < 32000; index++)
    value.writeInt16LE(Math.round(6000 * Math.sin(2 * Math.PI * frequency * index / 16000)), 44 + index * 2);
  return value;
}

test("new signature proves one-byte validation and a whole-song disk hit without duplicate storage", async () => {
  const after = {...initialCounters, hitBytes: 64044, validationRequests: 1,
    validationBytes: 1, reusedSignedLinks: 1};
  const api = page(async () => response(200, tone()), [initialCounters, after]);
  await api.verifySignedReuse();
  assert.equal(api.checks().length, 7);
  assert.equal(api.checks().every(check => check.ok), true);
});

test("duplicate download or storage cannot pass signature reuse checks", async () => {
  const after = {...initialCounters, hitBytes: 64044, validationRequests: 1,
    validationBytes: 1, reusedSignedLinks: 1, networkBytes: 128088, diskBytes: 128088};
  const api = page(async () => response(200, tone()), [initialCounters, after]);
  await api.verifySignedReuse();
  assert.equal(api.checks().find(check => check.name === "新签名不重复保存音频").ok, false);
  assert.equal(api.checks().find(check => check.name === "新签名整首来自磁盘").ok, false);
});

test("changed ETag requires the new audio bytes and a new download", async () => {
  const after = {...initialCounters, networkBytes: 128088, diskBytes: 128088,
    validationRequests: 1, validationBytes: 1};
  const api = page(async () => response(200, tone(660)), [initialCounters, after]);
  await api.verifyChangedSignature();
  assert.equal(api.checks().length, 6);
  assert.equal(api.checks().every(check => check.ok), true);
});

test("returning old bytes under a changed ETag is rejected", async () => {
  const after = {...initialCounters, networkBytes: 128088, diskBytes: 128088,
    validationRequests: 1, validationBytes: 1};
  const api = page(async () => response(200, tone()), [initialCounters, after]);
  await api.verifyChangedSignature();
  assert.equal(api.checks().find(check => check.name === "不同ETag新内容SHA-256一致").ok, false);
});

test("authorization rejection never counts a cached song or a second download", async () => {
  const after = {...initialCounters, validationRequests: 1};
  const api = page(async () => response(403, []), [initialCounters, after]);
  await api.verifyDeniedSignature();
  assert.equal(api.checks().length, 3);
  assert.equal(api.checks().every(check => check.ok), true);
  const invalid = page(async () => response(200, tone()), [initialCounters, {...after, hitBytes: 64044}]);
  await invalid.verifyDeniedSignature();
  assert.equal(invalid.checks().every(check => check.ok), false);
});

test("getBytes consumes the response and exposes Content-Length", async () => {
  let consumed = false;
  const api = page(async () => response(206, [1, 2, 3], "3", () => {consumed = true;}));
  const value = await api.getBytes("full.wav", "bytes=0-2");
  assert.equal(consumed, true);
  assert.equal(value.contentLength, "3");
  assert.deepEqual(Array.from(value.bytes), [1, 2, 3]);
});

test("offline miss succeeds only after reading an HTTP 504 empty body", async () => {
  let consumed = false;
  const api = page(async () => response(504, [], "0", () => {consumed = true;}));
  await api.assertMissing("partial.wav", "bytes=4096-4196", "missing");
  assert.equal(consumed, true);
  assert.equal(api.checks()[0].ok, true);
});

test("offline TypeError is a failure rather than proof of a cache miss", async () => {
  const api = page(async () => {throw new TypeError("Failed to fetch");});
  await api.assertMissing("partial.wav", "bytes=4096-4196", "missing");
  assert.equal(api.checks()[0].ok, false);
});

test("offline status, body and duplicate length failures cannot be accepted", async () => {
  for (const value of [response(502, []), response(504, [1]), response(504, [], "0, 0")]) {
    const api = page(async () => value);
    await api.assertMissing("partial.wav", "bytes=4096-4196", "missing");
    assert.equal(api.checks()[0].ok, false);
  }
});

test("non-Range HTTP 504 permits a readable error body with exact single length", async () => {
  for (const [length, expected] of [["3", true], ["0", false], ["3, 3", false]]) {
    const api = page(async () => response(504, [1, 2, 3], length));
    await api.assertMissing("partial.wav", null, "missing");
    assert.equal(api.checks()[0].ok, expected);
  }
});

test("an unreadable HTTP 504 body fails", async () => {
  const value = response(504, []);
  value.arrayBuffer = async () => {throw new TypeError("Invalid response framing");};
  const api = page(async () => value);
  await api.assertMissing("partial.wav", null, "missing");
  assert.equal(api.checks()[0].ok, false);
});

test("truncated body expects TypeError and never accepts a timeout", async () => {
  const api = page(async () => {throw new TypeError("Body terminated");});
  await api.assertTruncated();
  assert.equal(api.checks()[0].ok, true);
  const timeout = new Error("Timed out"); timeout.name = "AbortError";
  const aborted = page(async () => {throw timeout;});
  await aborted.assertTruncated();
  assert.equal(aborted.checks()[0].ok, false);
});

test("ignored upstream Range requires readable HTTP 502 with an empty body", async () => {
  for (const [value, expected] of [[response(502, []), true], [response(200, []), false],
                                  [response(502, [], "0, 0"), false]]) {
    const api = page(async () => value);
    await api.assertIgnoredRangeRejected();
    assert.equal(api.checks()[0].ok, expected);
  }
});
