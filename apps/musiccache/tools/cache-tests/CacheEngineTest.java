package com.zhou.musiccache.cache;

import android.webkit.WebResourceResponse;
import android.webkit.WebResourceRequest;
import android.net.Uri;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 无第三方依赖；真实磁盘、真实本机HTTP，Android仅使用响应容器适配器。 */
public final class CacheEngineTest {
    private static final byte[] AUDIO = new byte[8192];
    private static int checks;
    private static final String URL = "https://dl.stream.qqmusic.qq.com/private.m4a?vkey=DO_NOT_STORE_THIS_SECRET&token=PRIVATE_QUERY";
    private static final Map<String, String> FRESH = Map.of("Cache-Control", "max-age=3600", "Content-Type", "audio/mp4", "ETag", "\"v1\"", "Access-Control-Allow-Origin", "https://y.qq.com");

    public static void main(String[] args) throws Exception {
        for (int i = 0; i < AUDIO.length; i++) AUDIO[i] = (byte) (i * 17);
        Path root = Files.createTempDirectory("musiccache-tests-");
        try {
            rangeTests(root.resolve("ranges"));
            policyTests(root.resolve("policy"));
            transactionTests(root.resolve("transaction"));
            quotaTests(root.resolve("quota"));
            signedIdentityTests(root.resolve("signed-identity"));
            webViewAdapterTests();
            proxyTests(root.resolve("proxy"));
            System.out.println("PASS " + checks + " cache checks (real disk + loopback HTTP; no QQ login/playback claim)");
        } finally { deleteTree(root); }
    }
    private static void rangeTests(Path root) throws Exception {
        RangeCache cache = new RangeCache(root.toFile());
        check(put(cache, URL, 100, 0, 30, FRESH), "first range commits");
        check(!cache.entries().get(0).complete, "partial is incomplete");
        check(cache.open(URL, null) == null, "whole resource miss");
        check(cache.open(URL, "bytes=20-39") == null, "overlapping request with hole misses");
        check(put(cache, URL, 100, 20, 40, FRESH), "overlap commits");
        check(cache.entries().get(0).cachedBytes == 60, "overlap union, no double count");
        check(cache.sizeBytes() == 70, "disk reports actual duplicate bytes");
        equal(read(cache, URL, "bytes=15-59"), Arrays.copyOfRange(AUDIO, 15, 60), "cross-chunk merged stream");
        check(put(cache, URL, 100, 70, 30, FRESH), "tail commits");
        check(cache.entries().get(0).spans.size() == 2, "hole retained");
        check(cache.open(URL, "bytes=55-75") == null, "hole never fabricated");
        equal(read(cache, URL, "bytes=-10"), Arrays.copyOfRange(AUDIO, 90, 100), "suffix range");
        equal(read(cache, URL, "bytes=80-"), Arrays.copyOfRange(AUDIO, 80, 100), "open-ended range");
        check(put(cache, URL, 100, 60, 10, FRESH), "gap commits");
        check(cache.entries().get(0).complete, "complete only full coverage");
        equal(read(cache, URL, null), Arrays.copyOf(AUDIO, 100), "complete body");
        try (RangeCache.CachedResponse response = cache.open(URL, "bytes=95-999")) {
            check(response.statusCode == 206 && response.length == 5, "clamped end response");
            check("bytes 95-99/100".equals(response.headers.get("Content-Range")), "content-range header");
            check("5".equals(response.headers.get("Content-Length")), "content-length header");
            check("https://y.qq.com".equals(response.headers.get("Access-Control-Allow-Origin")), "CORS preserved");
        }
        String[] invalid = {"bytes=100-", "bytes=-0", "bytes=40-20", "bytes=0-1,5-6", "bytes=+1-2", "items=0-2", "bytes=9223372036854775808-", "bytes=--"};
        for (String value : invalid) check(cache.open(URL, value) == null, "reject invalid range " + value);
        RangeCache reboot = new RangeCache(root.toFile());
        equal(read(reboot, URL, null), Arrays.copyOf(AUDIO, 100), "restart reads same bytes");
        check(reboot.entries().get(0).complete, "restart preserves completeness");
        check(RangeCache.keyForUrl(URL).matches("[0-9a-f]{64}"), "opaque key");
        check(!RangeCache.keyForUrl(URL).equals(RangeCache.keyForUrl(URL + "x")), "query participates in key");
        try (java.util.stream.Stream<Path> paths = Files.list(root)) {
            for (Path path : paths.toList()) {
                String data = new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1);
                check(!data.contains("DO_NOT_STORE_THIS_SECRET") && !data.contains("PRIVATE_QUERY") && !data.contains("vkey="), "disk has no URL secret");
            }
        }
    }

    private static void policyTests(Path root) throws Exception {
        RangeCache cache = new RangeCache(root.toFile());
        check(put(cache, URL, 100, 0, 100, FRESH), "initial policy cache");
        check(!put(cache, URL, 100, 0, 100, Map.of("Cache-Control", "private, no-store")), "no-store not persisted");
        check(cache.open(URL, null) == null && cache.entries().isEmpty(), "no-store invalidates older cached response");
        String[] denied = {"no-cache", "max-age=0", "max-age=100, no-cache", "max-age=\"0\""};
        for (String policy : denied) check(!put(cache, URL, 100, 0, 100, Map.of("Cache-Control", policy)), "deny " + policy);
        check(!put(cache, URL, 100, 0, 100, Map.of("Cache-Control", "max-age=60", "Age", "61")), "Age expires response");
        check(!put(cache, URL, 100, 0, 100, Map.of("Cache-Control", "max-age=100", "Vary", "*")), "Vary star rejected");
        check(!put(cache, URL, 100, 0, 100, Map.of("Cache-Control", "max-age=100", "Vary", "Origin")), "vary origin cannot share URL-only key");
        check(!put(cache, URL, 100, 0, 100, Map.of("Cache-Control", "max-age=100", "Content-Encoding", "gzip")), "encoded ranges not stored");
        check(put(cache, URL, 100, 0, 100, Map.of()), "absent cache policy uses explicit experiment default");
        check(cache.entries().get(0).heuristic, "heuristic marked in snapshot");
        check(new RangeCache(root.toFile()).entries().get(0).heuristic, "heuristic persisted");
        long now = System.currentTimeMillis();
        check(RangeCache.expiryFor(Map.of(), now) == now + 86400000L, "24h experiment default");
        check(RangeCache.expiryFor(Map.of("Expires", "0"), now) == 0, "invalid explicit expiry is not heuristic");
        check(RangeCache.expiryFor(Map.of("Cache-Control", "max-age=60", "Date", "Mon, 01 Jan 2001 00:00:00 GMT"), now) == 0, "old date has aged out");
        check(!put(cache, URL, 100, 0, 100, Map.of("Pragma", "no-cache")), "legacy pragma denied");
    }

    private static void transactionTests(Path root) throws Exception {
        RangeCache cache = new RangeCache(root.toFile());
        check(!cache.put(URL, "audio/mp4", 100, 0, 100, new ByteArrayInputStream(new byte[99]), FRESH), "truncated put not committed");
        check(cache.entries().isEmpty() && cache.sizeBytes() == 0, "truncation has no evidence");
        try {
            cache.put(URL, "audio/mp4", 100, 0, 100, new ByteArrayInputStream(new byte[101]), FRESH);
            throw new AssertionError("overlong response accepted");
        } catch (IOException expected) { checks++; }
        RangeCache.WriteSession abandoned = cache.begin(URL, "audio/mp4", 100, 0, 100, FRESH);
        abandoned.append(AUDIO, 0, 60); abandoned.close();
        check(cache.entries().isEmpty(), "close before complete aborts");
        check(put(cache, URL, 100, 0, 50, FRESH), "old length half");
        check(put(cache, URL, 120, 50, 70, FRESH), "new total begins separate representation");
        check(cache.entries().get(0).totalBytes == 120 && cache.entries().get(0).cachedBytes == 70, "total change drops incompatible bytes");
        check(cache.open(URL, null) == null, "length change not completed by old bytes");
        RangeCache.WriteSession beforeClear = cache.begin(URL, "audio/mp4", 120, 0, 50, FRESH);
        beforeClear.append(AUDIO, 0, 50); cache.clear();
        check(!beforeClear.finish() && cache.entries().isEmpty(), "in-flight response cannot resurrect cleared cache");
        RangeCache.WriteSession beforeNoStore = cache.begin(URL, "audio/mp4", 100, 0, 100, FRESH);
        beforeNoStore.append(AUDIO, 0, 100);
        check(cache.begin(URL, "audio/mp4", 100, 0, 100, Map.of("Cache-Control", "no-store")) == null, "concurrent no-store response");
        check(!beforeNoStore.finish(), "no-store invalidates pending older write");
        check(put(cache, URL, 100, 0, 50, FRESH), "before validator change");
        Map<String, String> changed = new HashMap<>(FRESH); changed.put("ETag", "\"v2\"");
        check(put(cache, URL, 100, 50, 50, changed), "new ETag");
        check(cache.entries().get(0).cachedBytes == 50 && !cache.entries().get(0).complete, "ETag change cannot mix versions");
        for (Map<String, String> unverified : List.of(Map.of("Cache-Control", "max-age=3600"),
                Map.of("Cache-Control", "max-age=3600", "ETag", "W/\"weak\""))) {
            cache.clear();
            check(put(cache, URL, 100, 0, 50, unverified), "unverified first response can be cached alone");
            byte[] differentRepresentation = new byte[50]; Arrays.fill(differentRepresentation, (byte) 99);
            check(cache.put(URL, "audio/mp4", 100, 50, 50, new ByteArrayInputStream(differentRepresentation), unverified), "changed unverified second response saved independently");
            check(!cache.entries().get(0).complete && !cache.entries().get(0).canCombineRanges, "unverified responses never claim full file");
            check(cache.open(URL, null) == null && cache.open(URL, "bytes=20-80") == null, "no cross-version byte splice");
            equal(read(cache, URL, "bytes=50-99"), differentRepresentation, "latest unverified range exact bytes");
            check(put(cache, URL, 100, 0, 100, unverified), "single whole unverified response accepted");
            check(cache.entries().get(0).complete, "single response whole file can be complete");
        }
        cache.clear();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                int start = i * 25;
                tasks.add(pool.submit(() -> put(cache, URL, 100, start, 25, FRESH)));
            }
            for (Future<Boolean> task : tasks) check(task.get(), "concurrent segment commits");
        } finally { pool.shutdownNow(); }
        equal(read(cache, URL, null), Arrays.copyOf(AUDIO, 100), "concurrent exact full body");
        Files.writeString(root.resolve("capture-abandoned.part"), "partial");
        Files.writeString(root.resolve("orphan.bin"), "orphan");
        new RangeCache(root.toFile());
        check(!Files.exists(root.resolve("capture-abandoned.part")) && !Files.exists(root.resolve("orphan.bin")), "restart removes uncommitted orphan data");
    }

    private static void quotaTests(Path root) throws Exception {
        RangeCache cache = new RangeCache(root.toFile(), 150, 100);
        check(cache.begin(URL, "audio/mp4", 101, 0, 100, FRESH) == null, "resource quota");
        check(put(cache, URL, 100, 0, 100, FRESH), "within resource quota");
        check(!put(cache, URL + "2", 100, 0, 100, FRESH), "global quota");
        cache.clear();
        RangeCache.WriteSession one = cache.begin(URL, "audio/mp4", 100, 0, 100, FRESH);
        check(cache.begin(URL + "2", "audio/mp4", 100, 0, 100, FRESH) == null, "concurrent reservations count toward quota");
        one.close();
        check(put(cache, URL + "2", 100, 0, 100, FRESH), "aborted reservation reclaimed");
    }

    private static void signedIdentityTests(Path root) throws Exception {
        String one = "https://ws6.stream.qqmusic.qq.com/C400song.m4a?guid=device&vkey=OLD_SECRET&uin=person&fromtag=66&quality=high";
        String two = "https://ws6.stream.qqmusic.qq.com/C400song.m4a?guid=device2&vkey=NEW_SECRET&uin=person2&fromtag=77&quality=high";
        String resource = RangeCache.resourceKeyForUrl(one, false);
        check(resource.matches("[0-9a-f]{64}"), "canonical identity is opaque hash");
        check(resource.equals(RangeCache.resourceKeyForUrl(two, false)), "only known signature parameters excluded");
        check(!resource.equals(RangeCache.resourceKeyForUrl(two.replace("quality=high", "quality=low"), false)), "unknown content query preserved");
        check(!resource.equals(RangeCache.resourceKeyForUrl(two.replace("ws6.", "ws7."), false)), "strong ETag not assumed global across origins");
        check(!resource.equals(RangeCache.resourceKeyForUrl(two.replace("C400", "M500"), false)), "quality path prefix is part of identity");
        check(!resource.equals(RangeCache.resourceKeyForUrl(two + "&variant=changed", false)), "production variant is not treated as a signature");
        check(RangeCache.resourceKeyForUrl("https://example.org/C400song.m4a?vkey=1", false).isEmpty(), "canonical identity restricted to QQ allowlist");
        String loopOne = "http://127.0.0.1:8765/media/full.wav?run=one&variant=one";
        String loopTwo = "http://127.0.0.1:8765/media/full.wav?run=one&variant=two";
        check(RangeCache.resourceKeyForUrl(loopOne, false).isEmpty(), "loopback never canonical in production");
        check(RangeCache.resourceKeyForUrl(loopOne, true).equals(RangeCache.resourceKeyForUrl(loopTwo, true)), "debug variant may represent rotating signature");
        check(!RangeCache.resourceKeyForUrl(loopOne, true).equals(RangeCache.resourceKeyForUrl(loopTwo.replace("run=one", "run=two"), true)), "debug runs remain distinct");

        RangeCache cache = new RangeCache(root.toFile());
        check(put(cache, one, 100, 0, 100, FRESH), "signed source complete");
        long disk = cache.sizeBytes();
        check(cache.hasReusableResource(resource), "complete strong source eligible for probe");
        check(cache.open(two, null) == null, "new signature never used before validation");
        check(cache.reuseValidated(two, resource, "audio/mp4", 100, FRESH), "validated signature alias established");
        equal(read(cache, two, null), Arrays.copyOf(AUDIO, 100), "alias serves exact source bytes");
        check(cache.isAlias(two) && cache.sizeBytes() == disk && cache.entries().size() == 1, "alias creates neither duplicate bytes nor duplicate audio entry");
        RangeCache restarted = new RangeCache(root.toFile());
        equal(read(restarted, two, "bytes=50-99"), Arrays.copyOfRange(AUDIO, 50, 100), "alias survives restart with version binding");
        Map<String, String> changed = new HashMap<>(FRESH); changed.put("ETag", "\"different-version\"");
        check(!cache.reuseValidated(two + "&vkey=other", resource, "audio/mp4", 100, changed), "different strong version cannot alias");
        check(!cache.reuseValidated(two, resource, "audio/aac", 100, FRESH), "different MIME cannot alias");
        check(!cache.reuseValidated(two, resource, "audio/mp4", 101, FRESH), "different total cannot alias");
        check(!cache.reuseValidated(two, resource, "audio/mp4", 100, Map.of("ETag", "W/\"v1\"", "Cache-Control", "max-age=100")), "weak validator cannot authorize alias");
        check(!cache.reuseValidated(two, resource, "audio/mp4", 100, Map.of("ETag", "\"v1\"", "Cache-Control", "no-store")), "no-store probe cannot authorize alias");
        check(!cache.reuseValidated(two, resource, "audio/mp4", 100, Map.of("ETag", "\"v1\"", "Cache-Control", "no-cache")), "no-cache probe cannot authorize alias");
        cache.rejectAuthorization(two);
        check(cache.open(two, null) == null && cache.sizeBytes() == disk, "authorization rejection removes alias but retains source audio");
        check(cache.reuseValidated(two, resource, "audio/mp4", 100, FRESH), "fresh successful validation can restore alias");
        cache.rejectAuthorization(one);
        check(cache.open(one, null) == null && cache.sizeBytes() == disk, "source authorization failure retains expired audio bytes");
        equal(read(cache, two, null), Arrays.copyOf(AUDIO, 100), "independently validated alias still bound to same source version");
        check(cache.reuseValidated(one, resource, "audio/mp4", 100, FRESH), "same URL strong revalidation refreshes expiry without download");
        equal(read(cache, one, null), Arrays.copyOf(AUDIO, 100), "refreshed exact URL readable");
        check(put(cache, one, 100, 0, 100, changed), "source receives new version under same original URL");
        check(cache.open(two, null) == null && !cache.isAlias(two), "source version rewrite invalidates prior aliases");
        check(new RangeCache(root.toFile()).open(two, null) == null, "invalidated alias does not resurrect after restart");
        cache.clear();
        Map<String, String> noValidator = Map.of("Cache-Control", "max-age=100");
        check(put(cache, one, 100, 0, 100, noValidator), "weakly identified full source can still play itself");
        check(!cache.hasReusableResource(resource), "source without strong validator never triggers cross-link probe");
        for (String invalidTag : List.of("\"one\", \"two\"", "\"one\"\r\n", "\"bad tag\"")) {
            cache.clear();
            check(put(cache, one, 100, 0, 100, Map.of("Cache-Control", "max-age=100", "ETag", invalidTag)), "invalid ETag still permits single-response playback");
            check(!cache.hasReusableResource(resource), "malformed ETag is never a strong cross-link validator");
        }
        cache.clear();

        // 构造真实MAGIC2元数据，证明升级不删除旧记录且不按版本猜测其资源路径。
        check(put(cache, one, 100, 0, 100, FRESH), "legacy setup source");
        Path metadata = root.resolve(RangeCache.keyForUrl(one) + ".meta");
        byte[] modern = Files.readAllBytes(metadata);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(modern));
             ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream old = new DataOutputStream(bytes)) {
            input.readInt(); old.writeInt(0x4d434132);
            for (int i = 0; i < 4; i++) old.writeUTF(input.readUTF());
            old.writeLong(input.readLong()); old.writeLong(input.readLong()); old.writeBoolean(input.readBoolean());
            input.readUTF(); // MAGIC3新增resourceKey在旧格式不存在。
            old.write(input.readAllBytes()); old.flush(); Files.write(metadata, bytes.toByteArray());
        }
        Map<Path, byte[]> audioBefore = new HashMap<>();
        try (java.util.stream.Stream<Path> paths = Files.list(root)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".bin")).toList()) audioBefore.put(path, Files.readAllBytes(path));
        }
        RangeCache legacy = new RangeCache(root.toFile());
        check(legacy.entries().size() == 1 && legacy.entries().get(0).complete && legacy.entries().get(0).resourceKey.isEmpty(), "MAGIC2 retained without guessed path");
        check(!legacy.hasReusableResource(resource) && legacy.open(two, null) == null, "different signature cannot migrate legacy identity");
        equal(read(legacy, one, "bytes=0-0"), Arrays.copyOf(AUDIO, 1), "exact original URL safely performs lazy migration");
        check(resource.equals(legacy.entries().get(0).resourceKey), "exact URL assigns canonical hash");
        check(new RangeCache(root.toFile()).hasReusableResource(resource), "lazy migration persisted as current format");
        for (Map.Entry<Path, byte[]> audio : audioBefore.entrySet()) equal(Files.readAllBytes(audio.getKey()), audio.getValue(), "migration leaves existing audio file byte-for-byte unchanged");
        check(legacy.reuseValidated(two, resource, "audio/mp4", 100, FRESH), "migrated old bytes may be reused only after current link validation");
        try (java.util.stream.Stream<Path> paths = Files.list(root)) {
            for (Path path : paths.toList()) {
                String raw = new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1);
                check(!raw.contains("OLD_SECRET") && !raw.contains("NEW_SECRET") && !raw.contains("C400song") && !raw.contains("quality="), "new metadata and aliases contain no raw URL/path/query");
            }
        }
    }

    private static void proxyTests(Path root) throws Exception {
        RangeCache cache = new RangeCache(root.toFile());
        CacheProxy production = new CacheProxy(cache);
        check(production.accepts(URL), "production accepts QQ signed m4a");
        String[] denied = {"https://stream.qqmusic.qq.com/a.m4a", "https://evilstream.qqmusic.qq.com/a.m4a", "https://dl.stream.qqmusic.qq.com.evil.test/a.m4a", "http://dl.stream.qqmusic.qq.com/a.m4a", "https://dl.stream.qqmusic.qq.com/a.m3u8", "https://dl.stream.qqmusic.qq.com/a.qmc0", "https://dl.stream.qqmusic.qq.com:8443/a.mp3", "https://user@dl.stream.qqmusic.qq.com/a.mp3", "http://127.0.0.1:8765/media/run/full.wav"};
        for (String url : denied) check(!production.accepts(url), "production rejects unsafe target");
        check(production.intercept("https://y.qq.com/", "GET", Map.of()) == null, "official page remains WebView native");
        CacheProxy proxy = new CacheProxy(cache, true);
        String base = "http://127.0.0.1:8765/media/";
        check(proxy.accepts(base + "full.wav"), "strict selftest route");
        check(proxy.accepts(base + "full.wav?run=device_1&variant=one"), "test signed-URL variants kept distinct");
        String[] testDenied = {"http://localhost:8765/media/run/a.wav", "http://127.0.0.1:8766/media/run/a.wav", base + "full.wav?token=1", "http://127.0.0.1:8765/other.wav", "http://127.0.0.1:8765/media/run/../a.wav"};
        for (String value : testDenied) check(!proxy.accepts(value), "test target scope");
        AtomicInteger requests = new AtomicInteger();
        Map<String, List<String>> observedRanges = new ConcurrentHashMap<>();
        CountDownLatch slowStarted = new CountDownLatch(1), releaseSlow = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 8765), 0);
        ExecutorService workers = Executors.newCachedThreadPool(); server.setExecutor(workers);
        server.createContext("/media/", exchange -> {
            requests.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getRawQuery();
            String variant = "one";
            if (query != null) for (String component : query.split("&")) if (component.startsWith("variant=")) variant = component.substring(8);
            String requestedRange = exchange.getRequestHeaders().getFirst("Range");
            observedRanges.computeIfAbsent(path + ":" + variant, unused -> Collections.synchronizedList(new ArrayList<>()))
                    .add(requestedRange == null ? "FULL" : requestedRange);
            if (variant.equals("denied")) { exchange.sendResponseHeaders(403, -1); exchange.close(); return; }
            byte[] payload = AUDIO;
            if (variant.equals("changed")) {
                payload = AUDIO.clone(); for (int i = 0; i < payload.length; i++) payload[i] ^= 0x55;
            }
            if (path.endsWith("redirect.wav")) {
                exchange.getResponseHeaders().set("Location", "https://example.invalid/stolen.mp3");
                exchange.sendResponseHeaders(302, -1); exchange.close(); return;
            }
            exchange.getResponseHeaders().set("Content-Type", "audio/wav");
            exchange.getResponseHeaders().set("ETag", variant.equals("changed") ? "\"fixture-v2\"" : variant.equals("weak") ? "W/\"fixture-v1\"" : "\"fixture-v1\"");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Cache-Control", path.endsWith("nostore.wav") || variant.equals("nostore") ? "no-store" : path.endsWith("nocache.wav") ? "no-cache" : "max-age=3600");
            String range = exchange.getRequestHeaders().getFirst("Range");
            if (path.endsWith("ignore.wav")) range = null;
            long[] bytes = RangeCache.parseRange(range, AUDIO.length);
            if (bytes == null) { exchange.sendResponseHeaders(416, -1); exchange.close(); return; }
            int start = (int) bytes[0], count = (int) (bytes[1] - bytes[0] + 1);
            if (range != null) exchange.getResponseHeaders().set("Content-Range", variant.equals("badrange") ? "bytes 1-1/" + AUDIO.length : "bytes " + bytes[0] + "-" + bytes[1] + "/" + AUDIO.length);
            exchange.sendResponseHeaders(range == null ? 200 : 206, count);
            try (OutputStream out = exchange.getResponseBody()) {
                if (path.endsWith("slow.wav")) {
                    out.write(AUDIO, 0, 256); out.flush(); slowStarted.countDown();
                    try { releaseSlow.await(10, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    out.write(AUDIO, 256, AUDIO.length - 256);
                } else out.write(payload, start, path.endsWith("truncated.wav") ? count / 2 : count);
            }
            catch (IOException ignored) { } finally { exchange.close(); }
        });
        server.start();
        try {
            WebResourceResponse response = proxy.intercept(base + "full.wav", "GET", Map.of());
            check(response.getStatusCode() == 200, "network 200");
            equal(consume(response), AUDIO, "proxy forwards exact audio");
            check(proxy.snapshot().networkBytes == AUDIO.length && proxy.snapshot().hitBytes == 0, "network accounting");
            check(proxy.snapshot().entries.get(0).complete, "full proxy response complete");
            int before = requests.get(); proxy.setOffline(true);
            equal(consume(proxy.intercept(base + "full.wav", "GET", Map.of("Range", "bytes=-256"))), Arrays.copyOfRange(AUDIO, AUDIO.length - 256, AUDIO.length), "offline suffix hit");
            check(requests.get() == before && proxy.snapshot().hitBytes == 256, "offline hit never goes to server");
            for (String header : List.of("If-Range", "If-None-Match", "If-Modified-Since", "If-Match", "If-Unmodified-Since")) {
                response = proxy.intercept(base + "full.wav", "GET", Map.of("Range", "bytes=0-99", header, "\"different-validator\""));
                check(response.getStatusCode() == 504 && requests.get() == before, "offline conditional request fails closed: " + header); consume(response);
            }
            response = proxy.intercept(base + "unknown.wav", "GET", Map.of());
            check(response.getStatusCode() == 504 && requests.get() == before, "offline miss 504 without request"); consume(response);
            proxy.setOffline(false);
            before = requests.get();
            equal(consume(proxy.intercept(base + "full.wav", "GET", Map.of("Range", "bytes=0-99", "If-Range", "\"different-validator\""))), Arrays.copyOf(AUDIO, 100), "online If-Range reaches origin");
            check(requests.get() == before + 1, "conditional request does not use cache");
            equal(consume(proxy.intercept(base + "partial.wav", "GET", Map.of("Range", "bytes=0-99"))), Arrays.copyOf(AUDIO, 100), "online segment");
            proxy.setOffline(true); before = requests.get();
            response = proxy.intercept(base + "partial.wav", "GET", Map.of("Range", "bytes=80-120"));
            check(response.getStatusCode() == 504 && requests.get() == before, "offline hole never fetched"); consume(response);
            equal(consume(proxy.intercept(base + "partial.wav", "GET", Map.of("Range", "bytes=20-40"))), Arrays.copyOfRange(AUDIO, 20, 41), "offline partial covered request");
            proxy.setOffline(false);
            // 确认后续完整资源命中基线。
            consume(proxy.intercept(base + "full.wav", "GET", Map.of()));
            equal(consume(proxy.intercept(base + "nostore.wav", "GET", Map.of())), AUDIO, "no-store still plays");
            equal(consume(proxy.intercept(base + "nocache.wav", "GET", Map.of())), AUDIO, "no-cache still plays");
            proxy.setOffline(true);
            check(proxy.intercept(base + "nostore.wav", "GET", Map.of()).getStatusCode() == 504, "no-store not offline");
            check(proxy.intercept(base + "nocache.wav", "GET", Map.of()).getStatusCode() == 504, "no-cache not offline");
            proxy.setOffline(false);
            response = proxy.intercept(base + "redirect.wav", "GET", Map.of());
            check(response.getStatusCode() == 502, "redirect rejected"); consume(response);
            response = proxy.intercept(base + "truncated.wav", "GET", Map.of());
            try { consume(response); } catch (IOException expected) { checks++; }
            check(cache.open(base + "truncated.wav", null) == null, "truncated network response absent");
            before = requests.get();
            equal(consume(proxy.intercept(base + "full.wav", "GET", Map.of())), AUDIO, "online prefers compliant existing data");
            check(requests.get() == before, "online hit avoids download");
            check(proxy.snapshot().events.stream().noneMatch(event -> event.contains("http:") || event.contains("token=")), "events redact URL");
            long bytesBefore = proxy.snapshot().networkBytes;
            String webUrl = base + "webview.wav";
            WebResourceResponse webFirst = proxy.intercept(request(webUrl, "bytes=0-2999"));
            equal(consumeLikeChromium(webFirst, "bytes=0-2999"), Arrays.copyOf(AUDIO, 3000), "WebView first range");
            WebResourceResponse webSecond = proxy.intercept(request(webUrl, "bytes=3000-8191"));
            equal(consumeLikeChromium(webSecond, "bytes=3000-8191"), Arrays.copyOfRange(AUDIO, 3000, 8192), "WebView second range does not seek body twice");
            check(proxy.snapshot().networkBytes - bytesBefore == AUDIO.length, "WebView initialization skip counts no fictitious network bytes");
            try (RangeCache.CachedResponse completeWeb = cache.open(webUrl, null)) {
                check(completeWeb != null, "WebView actual second body completes strong-validator representation");
            }
            proxy.setOffline(true); before = requests.get();
            long hitBefore = proxy.snapshot().hitBytes;
            equal(consumeLikeChromium(proxy.intercept(request(webUrl, "bytes=3000-8191")), "bytes=3000-8191"), Arrays.copyOfRange(AUDIO, 3000, 8192), "WebView disk second range");
            equal(consumeLikeChromium(proxy.intercept(request(webUrl, "bytes=-257")), "bytes=-257"), Arrays.copyOfRange(AUDIO, 7935, 8192), "WebView disk suffix range");
            equal(consumeLikeChromium(proxy.intercept(request(webUrl, "bytes=4096-")), "bytes=4096-"), Arrays.copyOfRange(AUDIO, 4096, 8192), "WebView disk open-ended range");
            check(proxy.snapshot().hitBytes - hitBefore == 5192 + 257 + 4096, "WebView disk skip counts only delivered bytes");
            check(requests.get() == before, "WebView adapted offline requests have no HTTP traffic");
            WebResourceResponse missing = proxy.intercept(request(base + "missing-webview.wav", "bytes=32768-64043"));
            check(missing.getStatusCode() == 504 && consumeLikeChromium(missing, "bytes=32768-64043").length == 0, "WebView nonzero Range preserves 504 with readable empty body");
            WebResourceResponse suffixMissing = proxy.intercept(request(base + "missing-webview.wav", "bytes=-200"));
            check(suffixMissing.getStatusCode() == 504 && consumeLikeChromium(suffixMissing, "bytes=-200").length == 0, "WebView suffix Range preserves 504 with readable empty body");
            proxy.setOffline(false);
            WebResourceResponse ignoredRange = proxy.intercept(request(base + "ignore.wav", "bytes=3000-8191"));
            check(ignoredRange.getStatusCode() == 502 && consumeLikeChromium(ignoredRange, "bytes=3000-8191").length == 0,
                    "WebView origin 200 ignoring Range rejected as 502 with empty body");
            check(proxy.snapshot().events.stream().anyMatch(event -> event.contains("原状态 200")), "ignored Range leaves an explanatory local event");
            WebResourceResponse stringIgnoredRange = proxy.intercept(base + "ignore.wav", "GET", Map.of("Range", "bytes=3000-8191"));
            check(stringIgnoredRange.getStatusCode() == 200, "string HTTP overload keeps original 200 status");
            equal(consume(stringIgnoredRange), AUDIO, "string HTTP overload keeps original full response body");
            equal(consumeLikeChromium(proxy.intercept(request(base + "full-no-range.wav", null)), null), AUDIO,
                    "WebView full GET reports whole length before first socket read");
            String signedOne = base + "signed.wav?run=unit&variant=one";
            String signedTwo = base + "signed.wav?run=unit&variant=two";
            equal(consumeLikeChromium(proxy.intercept(request(signedOne, null)), null), AUDIO, "signed-link source loaded once");
            CacheProxy.Snapshot signedBefore = proxy.snapshot();
            equal(consumeLikeChromium(proxy.intercept(request(signedTwo, null)), null), AUDIO, "new signed URL reuses authorized original bytes");
            CacheProxy.Snapshot signedAfter = proxy.snapshot();
            check(signedAfter.validationRequests - signedBefore.validationRequests == 1 && signedAfter.validationBytes - signedBefore.validationBytes == 1,
                    "new signature performs exactly one real one-byte validation");
            check(signedAfter.networkBytes == signedBefore.networkBytes && signedAfter.hitBytes - signedBefore.hitBytes == AUDIO.length,
                    "probe traffic separated from zero-redownload audio reuse");
            check(signedAfter.reusedSignedLinks - signedBefore.reusedSignedLinks == 1 && signedAfter.diskBytes == signedBefore.diskBytes
                    && signedAfter.entries.size() == signedBefore.entries.size(), "alias increments reuse count without new audio record or file");
            check(observedRanges.get("/media/signed.wav:two").equals(List.of("bytes=0-0")), "server sees only one-byte request for new signature");
            equal(consumeLikeChromium(proxy.intercept(request(signedTwo, "bytes=500-999")), "bytes=500-999"), Arrays.copyOfRange(AUDIO, 500, 1000), "validated alias supports subsequent partial reads");
            check(proxy.snapshot().validationRequests == signedAfter.validationRequests, "same validated signature does not probe repeatedly");
            String signedChanged = base + "signed.wav?run=unit&variant=changed";
            byte[] changedAudio = AUDIO.clone(); for (int i = 0; i < changedAudio.length; i++) changedAudio[i] ^= 0x55;
            equal(consumeLikeChromium(proxy.intercept(request(signedChanged, null)), null), changedAudio, "different strong ETag downloads actual new representation");
            check(observedRanges.get("/media/signed.wav:changed").equals(List.of("bytes=0-0", "FULL")), "changed variant is verified then fetched normally");
            equal(consumeLikeChromium(proxy.intercept(request(signedOne, null)), null), AUDIO, "changed variant does not overwrite old signature content");
            CacheProxy.Snapshot deniedBefore = proxy.snapshot();
            String deniedUrl = base + "signed.wav?run=unit&variant=denied";
            WebResourceResponse deniedResponse = proxy.intercept(request(deniedUrl, null));
            check(deniedResponse.getStatusCode() == 403, "new signed URL denial remains HTTP403"); consumeLikeChromium(deniedResponse, null);
            CacheProxy.Snapshot deniedAfter = proxy.snapshot();
            check(deniedAfter.diskBytes == deniedBefore.diskBytes && deniedAfter.hitBytes == deniedBefore.hitBytes
                    && deniedAfter.networkBytes == deniedBefore.networkBytes && deniedAfter.reusedSignedLinks == deniedBefore.reusedSignedLinks,
                    "denied signature cannot read cached audio or add a false alias");
            check(observedRanges.get("/media/signed.wav:denied").equals(List.of("bytes=0-0")), "403 probe is not retried as full song");
            proxy.setOffline(true); before = requests.get();
            equal(consumeLikeChromium(proxy.intercept(request(signedTwo, null)), null), AUDIO, "validated alias usable strictly offline");
            WebResourceResponse untouched = proxy.intercept(request(base + "signed.wav?run=unit&variant=untouched", null));
            check(untouched.getStatusCode() == 504, "unvalidated new signature cannot borrow same-path audio offline"); consumeLikeChromium(untouched, null);
            check(requests.get() == before && proxy.snapshot().validationRequests == deniedAfter.validationRequests, "strict offline never sends a validation probe");
            proxy.setOffline(false);
            long probeBefore = proxy.snapshot().validationRequests;
            equal(consume(proxy.intercept(base + "signed.wav?run=unit&variant=forced", "GET", Map.of("Cache-Control", "no-cache"))), AUDIO,
                    "forced network request keeps ordinary HTTP semantics");
            equal(consume(proxy.intercept(base + "signed.wav?run=unit&variant=conditional", "GET", Map.of("If-None-Match", "\"other\""))), AUDIO,
                    "conditional request is not replaced by permission probe");
            equal(consume(proxy.intercept(base + "signed.wav?run=unit&variant=pragma", "GET", Map.of("Pragma", "no-cache"))), AUDIO,
                    "Pragma no-cache bypasses alias optimization");
            check(proxy.snapshot().validationRequests == probeBefore, "force and condition headers do not run hidden one-byte validations");
            equal(consume(proxy.intercept(base + "signed.wav?run=unit&variant=weak", "GET", Map.of())), AUDIO,
                    "weak probe validator falls back to authorized real response");
            check(observedRanges.get("/media/signed.wav:weak").equals(List.of("bytes=0-0", "FULL")), "weak validator never aliases old bytes");
            equal(consume(proxy.intercept(base + "signed.wav?run=unit&variant=nostore", "GET", Map.of())), AUDIO,
                    "no-store probe never authorizes cached reuse");
            check(observedRanges.get("/media/signed.wav:nostore").equals(List.of("bytes=0-0", "FULL")), "no-store uses real network body");
            WebResourceResponse badProbe = proxy.intercept(request(base + "signed.wav?run=unit&variant=badrange", null));
            check(badProbe.getStatusCode() == 502, "probe with wrong Content-Range fails closed"); consumeLikeChromium(badProbe, null);
            check(observedRanges.get("/media/signed.wav:badrange").equals(List.of("bytes=0-0")), "invalid probe never falls back to old song");
            WebResourceResponse slowResponse = proxy.intercept(base + "slow.wav", "GET", Map.of());
            check(slowStarted.await(2, TimeUnit.SECONDS), "slow response began");
            ExecutorService reader = Executors.newSingleThreadExecutor();
            CountDownLatch reading = new CountDownLatch(1);
            Future<Boolean> cancelled = reader.submit(() -> {
                try {
                    byte[] prefix = slowResponse.getData().readNBytes(256);
                    if (prefix.length != 256) return false;
                    reading.countDown(); consume(slowResponse); return false;
                } catch (IOException expected) { return true; }
            });
            check(reading.await(2, TimeUnit.SECONDS), "concurrent network reader began");
            Thread.sleep(100); // 让第二次read真实进入等待服务端数据的状态，而非仅测试尚未开始的read。
            long cancelStart = System.nanoTime();
            proxy.setOffline(true);
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cancelStart) < 500, "offline toggle never waits on blocked socket read");
            releaseSlow.countDown();
            try { check(cancelled.get(3, TimeUnit.SECONDS), "offline concurrent cancellation is clean IOException"); }
            finally { reader.shutdownNow(); }
            check(cache.open(base + "slow.wav", null) == null, "cancelled response is never committed");
            proxy.clearAudioCache(); check(cache.sizeBytes() == 0 && cache.entries().isEmpty(), "clear cache only");
        } finally { releaseSlow.countDown(); proxy.cancelActive(); server.stop(0); workers.shutdownNow(); }
    }
    private static void webViewAdapterTests() throws Exception {
        byte[] segment = Arrays.copyOfRange(AUDIO, 7000, 8192);
        WebResourceResponse original = new WebResourceResponse("audio/wav", null, 206, "Partial Content",
                Map.of("Content-Length", "1192", "Content-Range", "bytes 7000-8191/8192"), new ByteArrayInputStream(segment));
        check(RangeCache.parseRange("bytes=7000-8191", original.getData().available()) == null,
                "regression reproduces Chromium rejecting segment length as whole size");
        WebResourceResponse adapted = CacheProxy.adaptForWebView(original, "bytes=7000-8191");
        check(adapted.getData().available() == 8192, "206 initialization reports representation size");
        equal(consumeLikeChromium(adapted, "bytes=7000-8191"), segment, "206 Chromium seek reads exact segment");
        check("bytes 7000-8191/8192".equals(adapted.getResponseHeaders().get("Content-Range")), "adapter preserves actual Content-Range");
        WebViewRangeInputStream stepped = new WebViewRangeInputStream(new ByteArrayInputStream(segment), 8192, 7000, segment.length);
        check(stepped.skip(3000) == 3000 && stepped.skip(4000) == 4000, "initialization skip may occur in multiple steps");
        equal(stepped.readAllBytes(), segment, "stepped initialization leaves body intact");
        WebViewRangeInputStream direct = new WebViewRangeInputStream(new ByteArrayInputStream(segment), 8192, 7000, segment.length);
        equal(direct.readAllBytes(), segment, "direct body read never fabricates a prefix");
        byte[] two = {42, 43};
        WebResourceResponse huge = new WebResourceResponse("audio/wav", null, 206, "Partial Content",
                Map.of("Content-Length", "2", "Content-Range", "bytes 3000000000-3000000001/3000000002"), new ByteArrayInputStream(two));
        WebResourceResponse hugeRejected = CacheProxy.adaptForWebView(huge, "bytes=3000000000-");
        check(hugeRejected.getStatusCode() == 502 && consumeLikeChromium(hugeRejected, "bytes=3000000000-").length == 0,
                "representation beyond native available int bound fails closed");
        WebResourceResponse hugeSuffix = new WebResourceResponse("audio/wav", null, 206, "Partial Content",
                Map.of("Content-Length", "2", "Content-Range", "bytes 3000000000-3000000001/3000000002"), new ByteArrayInputStream(two));
        WebResourceResponse hugeSuffixRejected = CacheProxy.adaptForWebView(hugeSuffix, "bytes=-2");
        check(hugeSuffixRejected.getStatusCode() == 502 && consumeLikeChromium(hugeSuffixRejected, "bytes=-2").length == 0,
                "huge suffix cannot claim an unverifiable content length");
        WebResourceResponse inconsistent = new WebResourceResponse("audio/wav", null, 206, "Partial Content",
                Map.of("Content-Length", "2", "Content-Range", "bytes 1-2/8192"), new ByteArrayInputStream(two));
        WebResourceResponse rejected = CacheProxy.adaptForWebView(inconsistent, "bytes=7000-8191");
        check(rejected.getStatusCode() == 502 && consumeLikeChromium(rejected, "bytes=7000-8191").length == 0,
                "mismatched upstream range fails closed with readable error");
        WebResourceResponse full = new WebResourceResponse("audio/wav", null, 200, "OK",
                Map.of("content-length", "2", "CONTENT-TYPE", "audio/wav", "Access-Control-Allow-Origin", "*"),
                new ByteArrayInputStream(two) { @Override public int available() { return 0; } });
        WebResourceResponse fullAdapted = CacheProxy.adaptForWebView(full, null);
        equal(consumeLikeChromium(fullAdapted, null), two, "full GET known length is independent of socket currently available bytes");
        check("*".equals(fullAdapted.getResponseHeaders().get("Access-Control-Allow-Origin")), "header adaptation preserves CORS");
        WebResourceResponse unknown = new WebResourceResponse("audio/wav", null, 200, "OK", Map.of(), new ByteArrayInputStream(two));
        WebResourceResponse unknownRejected = CacheProxy.adaptForWebView(unknown, null);
        check(unknownRejected.getStatusCode() == 502 && consumeLikeChromium(unknownRejected, null).length == 0,
                "unknown representation length is explicitly unsupported in WebView");
        WebResourceResponse encodedError = new WebResourceResponse("text/plain", null, 403, "Forbidden",
                Map.of("Content-Length", "2", "Content-Encoding", "gzip"), new ByteArrayInputStream(two));
        WebResourceResponse emptyError = CacheProxy.adaptForWebView(encodedError, "bytes=1-1");
        check(emptyError.getStatusCode() == 403 && consumeLikeChromium(emptyError, "bytes=1-1").length == 0,
                "403 is preserved with empty body even for one-byte Range");
        check(RangeCache.header(emptyError.getResponseHeaders(), "Content-Encoding").isEmpty(), "empty error body has no stale compression header");
        WebResourceResponse wrongLength = new WebResourceResponse("audio/wav", null, 206, "Partial Content",
                Map.of("Content-Length", "1", "Content-Range", "bytes 0-1/2"), new ByteArrayInputStream(two));
        WebResourceResponse wrongLengthRejected = CacheProxy.adaptForWebView(wrongLength, "bytes=0-1");
        check(wrongLengthRejected.getStatusCode() == 502 && consumeLikeChromium(wrongLengthRejected, "bytes=0-1").length == 0,
                "Content-Length mismatch cannot become a valid-looking 206");
    }
    private static WebResourceRequest request(String url, String range) {
        return new WebResourceRequest() {
            @Override public Uri getUrl() { return Uri.parse(url); }
            @Override public String getMethod() { return "GET"; }
            @Override public Map<String, String> getRequestHeaders() { return range == null ? Map.of() : Map.of("Range", range); }
        };
    }
    /** 精确模拟Chromium InputStreamReader::VerifyRequestedRange + SkipToRequestedRange的关键序列。 */
    private static byte[] consumeLikeChromium(WebResourceResponse response, String range) throws IOException {
        InputStream body = response.getData();
        int available = body.available();
        long skip = 0, generatedContentLength = 0;
        if (available > 0) {
            long[] computed = RangeCache.parseRange(range, available);
            if (computed == null) { body.close(); throw new IOException("Chromium ERR_REQUEST_RANGE_NOT_SATISFIABLE"); }
            skip = computed[0];
            generatedContentLength = computed[1] - computed[0] + 1;
        } else if (range != null && !range.isEmpty()) {
            int dash = range.indexOf('-', 6);
            if (dash > 6) skip = Long.parseLong(range.substring(6, dash));
        }
        while (skip > 0) {
            long consumed = body.skip(skip);
            if (consumed <= 0) { body.close(); throw new IOException("Chromium initialization skip failed"); }
            skip -= consumed;
        }
        // HeadersComplete先SetHeader长度/MIME，WebResourceResponse后AddHeader应用头；模拟重复头风险。
        check(RangeCache.header(response.getResponseHeaders(), "Content-Length").isEmpty(),
                "Chromium generated Content-Length is the only emitted length");
        check(RangeCache.header(response.getResponseHeaders(), "Content-Type").isEmpty(),
                "Chromium generated MIME is not appended twice");
        byte[] received = consume(response);
        check(generatedContentLength == received.length, "single generated Content-Length equals actual body bytes");
        return received;
    }
    private static boolean put(RangeCache cache, String url, long total, int start, int length, Map<String, String> headers) throws IOException {
        return cache.put(url, "audio/mp4", total, start, length, new ByteArrayInputStream(Arrays.copyOfRange(AUDIO, start, start + length)), headers);
    }
    private static byte[] read(RangeCache cache, String url, String range) throws IOException {
        try (RangeCache.CachedResponse response = cache.open(url, range)) {
            if (response == null) throw new AssertionError("Unexpected cache miss"); return response.body.readAllBytes();
        }
    }
    private static byte[] consume(WebResourceResponse response) throws IOException {
        try (InputStream body = response.getData()) { return body.readAllBytes(); }
    }
    private static void equal(byte[] actual, byte[] expected, String label) { check(Arrays.equals(actual, expected), label); }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); checks++; }
    private static void deleteTree(Path root) throws IOException {
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
