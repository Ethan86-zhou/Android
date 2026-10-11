// Run: node tools/test_qq_catalog.mjs
// Executes the actual embedded JavaScript extracted from QQWebSource.java.
// QQ SDK replies and the DOM are simulated. No network, device, credentials, or app build.
import assert from "node:assert/strict";
import {createHash} from "node:crypto";
import fs from "node:fs";
import vm from "node:vm";

const sourceUrl = new URL("../app/src/main/java/com/zhou/musiccache/player/QQWebSource.java", import.meta.url);
const source = fs.readFileSync(sourceUrl, "utf8");
const sha256 = text => createHash("sha256").update(text).digest("hex");
const sourceHash = sha256(source);

function template(name) {
  const match = source.match(new RegExp(
    "private static final String " + name + ' = """\\r?\\n([\\s\\S]*?)\\r?\\n[ \\t]*""";'));
  assert.ok(match, "Missing Java text block: " + name);
  // Java text blocks still process Java escapes. The current scripts use \\ for JS regexes.
  // Decode standard escapes explicitly; reject unsupported ones rather than silently testing
  // a script different from what Java would give WebView.
  const escapes = {"\\": "\\", '"': '"', "'": "'", b: "\b", t: "\t", n: "\n",
    f: "\f", r: "\r", s: " "};
  return match[1].replace(/\r\n/g, "\n").replace(/\\([\s\S])/g, (_, char) => {
    if (char === "\n") return "";
    assert.ok(Object.hasOwn(escapes, char), "Unsupported Java escape in " + name + ": " + char);
    return escapes[char];
  });
}

const playlistTemplate = template("READ_PLAYLIST_PAGE");
const favoriteTemplate = template("READ_FAVORITE_ID");
const listsTemplate = template("READ_PLAYLISTS");
const searchTemplate = template("READ_SEARCH_PAGE");
let passed = 0;
function check(condition, description) {
  assert.ok(condition, description);
  console.log("ok " + ++passed + " - " + description);
}

function tracks(count, offset = 0) {
  return Array.from({length: count}, (_, i) => ({
    mid: "S" + String(i + offset).padStart(7, "0"), title: "曲目" + (i + offset),
    singer: [{name: "歌手"}], album: {name: "专辑"}, interval: 225
  }));
}

function environment(reply, playlistId = "1234567890") {
  const calls = [];
  const Music = {uajaxAsync(config, params) {
    calls.push([config, params]);
    return typeof reply === "function" ? reply(config, params) : Promise.resolve(reply);
  }};
  const location = {
    origin: "https://i2.y.qq.com", pathname: "/n3/other/pages/details/playlist.html",
    href: "https://i2.y.qq.com/n3/other/pages/details/playlist.html" +
      (playlistId === null ? "" : "?id=" + playlistId)
  };
  const window = {Music};
  return {context: vm.createContext({URL, location, window, Music}), calls, window, location};
}

async function evaluateResult(env, script) {
  new vm.Script(script);
  vm.runInContext(script, env.context);
  await new Promise(setImmediate);
  return vm.runInContext(script, env.context);
}

function playlistScript(id = "1234567890", offset = 0, token = "1_" + offset) {
  return playlistTemplate.replaceAll("__ID__", id)
    .replaceAll("__OFFSET__", String(offset)).replaceAll("__TOKEN__", token);
}
function playlistResponse(total, extra = {}) {
  return {res: {code: 0, req_0: {code: 0, data: {
    code: 0, dirinfo: {songnum: total}, songlist: tracks(total),
    invalid_song: [], filtered_song: [], ...extra
  }}}};
}

async function playlistChecks() {
  const before = passed;
  let env = environment(playlistResponse(69));
  let result = await evaluateResult(env, playlistScript());
  check(result.state === "ready" && result.total === 69 && result.rows.length === 69,
    "playlist: all 69 rows survive the real extraction script");
  check(env.calls.length === 1, "playlist: polling starts only one request");
  check(env.calls[0][0].comm && env.calls[0][1][0].param.song_num === 100,
    "playlist: official config-first/array-second SDK shape");
  check(env.calls[0][1][0].param.disstid === 1234567890, "playlist: dynamic playlist ID");
  check(result.rows[0].durationMs === 225000, "playlist: duration metadata mapping");
  check(Object.keys(result).sort().join(",") === "omitted,rawCount,rows,state,token,total",
    "playlist: only minimal metadata and counts retained");
  await evaluateResult(env, playlistScript());
  check(env.calls.length === 1, "playlist: completed polling does not refetch");

  env = environment((config, params) => Promise.resolve(playlistResponse(101, {
    songlist: tracks(101).slice(params[0].param.song_begin, params[0].param.song_begin + 100)
  })));
  result = await evaluateResult(env, playlistScript());
  check(result.rows.length === 100 && result.total === 101, "playlist: first page has 100 rows");
  result = await evaluateResult(env, playlistScript("1234567890", 100));
  check(result.rows.length === 1 && env.calls[1][1][0].param.song_begin === 100,
    "playlist: second page uses offset 100");

  env = environment(playlistResponse(0));
  result = await evaluateResult(env, playlistScript());
  check(result.state === "ready" && result.rows.length === 0 && result.total === 0,
    "playlist: explicit zero-song result");
  env = environment(playlistResponse(1, {
    invalid_song: [{}], filtered_song: [{}], songlist: [{mid: "bad"}]
  }));
  result = await evaluateResult(env, playlistScript());
  check(result.omitted === 3 && result.rows.length === 0,
    "playlist: unavailable, filtered, and invalid identifiers counted");
  env = environment({res: {code: 0, req_0: {code: 403, data: {}}}});
  result = await evaluateResult(env, playlistScript());
  check(result.state === "error", "playlist: permission rejection fails closed");
  env = environment(playlistResponse(101));
  result = await evaluateResult(env, playlistScript());
  check(result.state === "error", "playlist: oversized server page rejected");
  env = environment(playlistResponse(1, {dirinfo: {}}));
  result = await evaluateResult(env, playlistScript());
  check(result.state === "error", "playlist: missing total rejected");
  env = environment(() => Promise.reject(new Error("secret-url")));
  result = await evaluateResult(env, playlistScript());
  check(result.state === "error" && !JSON.stringify(result).includes("secret"),
    "playlist: exception text not returned");
  env = environment(playlistResponse(1), "8888888888");
  result = await evaluateResult(env, playlistScript());
  check(result.state === "waiting" && env.calls.length === 0,
    "playlist: wrong page playlist ID blocks request");
  env = environment(playlistResponse(1));
  env.location.origin = "https://evil.example";
  result = await evaluateResult(env, playlistScript());
  check(result.state === "waiting" && env.calls.length === 0,
    "playlist: wrong origin blocks request");

  let release;
  env = environment(() => new Promise(resolve => { release = resolve; }));
  vm.runInContext(playlistScript(), env.context);
  await new Promise(setImmediate);
  env.window.__musicCachePlaylistResult = {token: "newer", state: "pending"};
  release(playlistResponse(1));
  await new Promise(setImmediate);
  check(env.window.__musicCachePlaylistResult.token === "newer" &&
    env.window.__musicCachePlaylistResult.state === "pending",
    "playlist: stale promise cannot overwrite newer request");

  const userdata = {
    isStateHost: true, myFavDissId: "9876543210",
    get credentials() { throw new Error("Must not inspect unrelated account properties"); }
  };
  const element = {__reactFiber$test: {return: {memoizedProps: {userdata}}}};
  const document = {querySelector: () => element, body: {innerText: ""}};
  const location = {origin: "https://y.qq.com", pathname: "/n/ryqq_v2/profile/like/song"};
  let favorite = vm.runInNewContext(favoriteTemplate, {location, document});
  check(favorite.id === "9876543210", "favorites: read only own favorite ID");
  userdata.isStateHost = false;
  favorite = vm.runInNewContext(favoriteTemplate, {location, document});
  check(!favorite.id, "favorites: another profile is not accepted as own favorites");

  const box = {textContent: "播放量 12345", querySelector: () => null};
  const anchor = {
    href: "https://y.qq.com/n/ryqq_v2/playlist/1234567890", title: "我的歌单",
    textContent: "我的歌单", closest: () => box
  };
  const listLocation = {origin: "https://y.qq.com", pathname: "/n/ryqq_v2/profile/create",
    href: "https://y.qq.com/n/ryqq_v2/profile/create"};
  let lists = vm.runInNewContext(listsTemplate, {URL, location: listLocation,
    document: {querySelectorAll: () => [anchor, anchor], body: {innerText: ""}}});
  check(lists.rows.length === 1 && lists.rows[0].count === -1,
    "playlist directory: duplicate anchors removed and play count not treated as song count");
  box.textContent = "69 首歌曲";
  lists = vm.runInNewContext(listsTemplate, {URL, location: listLocation,
    document: {querySelectorAll: () => [anchor], body: {innerText: ""}}});
  check(lists.rows[0].count === 69, "playlist directory: explicit song count recognized");
  assert.equal(passed - before, 22, "Playlist check count");
  console.log("PASS 22 playlist/catalog JavaScript checks");
}

function searchResponse(total = 1035, count = 30, offset = 0) {
  return {res: {code: 0, req_0: {code: 0, data: {
    meta: {sum: total}, body: {song: {list: tracks(count, offset).map(track => ({
      ...track, interval: 125
    }))}}
  }}}};
}
function searchScript(query = "周杰伦", page = 1, token = "3_" + page) {
  // Match production replacement order: quoted user text is always substituted last.
  return searchTemplate.replaceAll("__PAGE__", String(page))
    .replaceAll("__TOKEN__", token).replaceAll("__QUERY__", JSON.stringify(query));
}

async function searchChecks() {
  const before = passed;
  let env = environment(searchResponse(), null);
  let result = await evaluateResult(env, searchScript());
  check(result.state === "ready" && result.rows.length === 30 && result.total === 1035,
    "search: real SDK response shape yields 30 rows and reported total");
  check(env.calls.length === 1, "search: one SDK request");
  const request = env.calls[0][1][0];
  check(request.module === "music.search.SearchCgiService" &&
    request.method === "DoSearchForQQMusicDesktop", "search: official module and method");
  check(request.param.num_per_page === 30 && request.param.page_num === 1 &&
    request.param.search_type === 0, "search: validated page size and page number");
  check(request.param.query === "周杰伦" && request.param.remoteplace === "yqq.yqq.yqq",
    "search: query and source parameter");
  check(Object.keys(env.calls[0][0]).join(",") === "comm", "search: caller provides no credentials");
  check(result.rows[0].durationMs === 125000 && result.rows[0].artist === "歌手",
    "search: minimal metadata mapping");
  check(Object.keys(result).sort().join(",") === "omitted,rawCount,rows,state,token,total",
    "search: no raw response retained");
  await evaluateResult(env, searchScript());
  check(env.calls.length === 1, "search: polling does not repeat a completed request");

  env = environment((config, params) =>
    Promise.resolve(searchResponse(1035, 30, (params[0].param.page_num - 1) * 30)), null);
  const ids = new Set();
  for (let page = 1; page <= 3; page++) {
    result = await evaluateResult(env, searchScript("hello", page));
    result.rows.forEach(track => ids.add(track.id));
  }
  check(env.calls.length === 3 && ids.size === 90, "search: three pages supply 90 distinct identifiers");
  check(env.calls.map(call => call[1][0].param.page_num).join(",") === "1,2,3",
    "search: page numbers increment");
  check(source.includes("SEARCH_PAGE_SIZE = 30, SEARCH_MAX_PAGES = 3"),
    "search: Java source declares finite three-page bound");
  env = environment(searchResponse(0, 0), null);
  result = await evaluateResult(env, searchScript());
  check(result.total === 0 && result.rows.length === 0 && result.state === "ready",
    "search: valid zero-match result");
  env = environment(searchResponse(35, 5, 30), null);
  result = await evaluateResult(env, searchScript("hello", 2));
  check(result.total === 35 && result.rawCount === 5, "search: short final page metadata");

  const invalidReplies = [
    ["top-level rejection", {res: {code: 403}}],
    ["request rejection", {res: {code: 0, req_0: {code: 403}}}],
    ["oversized page", searchResponse(10, 31)],
    ["missing total", {res: {code: 0, req_0: {code: 0, data: {body: {song: {list: []}}}}}}]
  ];
  for (const [name, reply] of invalidReplies) {
    env = environment(reply, null);
    result = await evaluateResult(env, searchScript());
    check(result.state === "error", "search: " + name + " fails closed");
  }
  env = environment(() => Promise.reject(new Error("https://secret/?token=abc")), null);
  result = await evaluateResult(env, searchScript());
  check(result.state === "error" && !JSON.stringify(result).includes("secret"),
    "search: exception detail not exposed");
  env = environment(searchResponse(), null);
  env.location.origin = "https://wrong.example";
  result = await evaluateResult(env, searchScript());
  check(result.state === "waiting" && env.calls.length === 0, "search: origin guard");
  env = environment(searchResponse(), null);
  env.location.pathname = "/wrong";
  result = await evaluateResult(env, searchScript());
  check(result.state === "waiting" && env.calls.length === 0, "search: path guard");
  env = environment(searchResponse(), null);
  delete env.window.Music;
  result = await evaluateResult(env, searchScript());
  check(result.state === "waiting" && env.calls.length === 0,
    "search: unavailable SDK does not silently fall back to DOM rows");
  const query = '中文 " ; window.pwned=true; // __PAGE__ __TOKEN__ __QUERY__\n';
  env = environment(searchResponse(), null);
  await evaluateResult(env, searchScript(query));
  check(env.calls[0][1][0].param.query === query && !env.window.pwned,
    "search: query quoting and placeholder order prevent injection/corruption");

  let release;
  env = environment(() => new Promise(resolve => { release = resolve; }), null);
  vm.runInContext(searchScript(), env.context);
  await new Promise(setImmediate);
  env.window.__musicCacheSearchResult = {token: "4_1", state: "pending"};
  release(searchResponse());
  await new Promise(setImmediate);
  check(env.window.__musicCacheSearchResult.token === "4_1",
    "search: stale reply cannot replace a newer token");
  const invalidId = searchResponse(1, 1);
  invalidId.res.req_0.data.body.song.list[0].mid = "invalid";
  env = environment(invalidId, null);
  result = await evaluateResult(env, searchScript());
  check(result.rows.length === 0 && result.omitted === 1, "search: invalid song identifier counted");
  new vm.Script(playlistScript());
  new vm.Script(favoriteTemplate);
  check(source.includes("public void playlist(String id, CatalogCallback callback)") &&
    source.includes("myFavDissId"), "search change retains playlist/favorite entry points");
  assert.equal(passed - before, 26, "Search check count");
  console.log("PASS 26 search/catalog JavaScript checks");
}

console.log("QQ catalog script regression");
console.log("RecordedAt: " + new Date().toISOString());
console.log("Source: app/src/main/java/com/zhou/musiccache/player/QQWebSource.java");
console.log("SourceSHA256: " + sourceHash);
console.log("Scope: extracted JavaScript with simulated QQ SDK/DOM; not live QQ, Android lifecycle, or Java pagination-loop validation.");
await playlistChecks();
await searchChecks();
assert.equal(passed, 48);
assert.equal(sha256(fs.readFileSync(sourceUrl, "utf8")), sourceHash, "Source changed during tests");
console.log("PASS 48 checks total; production source unchanged; no network or device access.");
