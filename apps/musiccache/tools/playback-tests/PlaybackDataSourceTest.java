package com.zhou.musiccache.playback;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.datasource.DataSourceException;
import androidx.media3.datasource.DataSpec;
import com.sun.net.httpserver.HttpServer;
import com.zhou.musiccache.cache.CacheProxy;
import com.zhou.musiccache.cache.RangeCache;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** 实际DataSource+缓存+本机HTTP。Media3容器用小型API替身；不声称验证了Android音频输出。 */
public final class PlaybackDataSourceTest {
    private static final int CHUNK = 256 * 1024;
    private static final byte[] AUDIO = new byte[CHUNK * 2 + 4321];
    private static final Uri SONG = Uri.parse("qqmusic://song/TestSong001");
    private static final List<String> REQUESTS = Collections.synchronizedList(new ArrayList<>());
    private static int checks;

    public static void main(String[] args) throws Exception {
        for (int i = 0; i < AUDIO.length; i++) AUDIO[i] = (byte) (i * 71 + i / 13);
        Path directory = Files.createTempDirectory("musiccache-native-tests-");
        ExecutorService workers = Executors.newCachedThreadPool();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 8765), 0);
        server.setExecutor(workers);
        server.createContext("/media/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String range = exchange.getRequestHeaders().getFirst("Range");
            REQUESTS.add(path + ":" + range);
            exchange.getResponseHeaders().set("Content-Type", "audio/wav");
            exchange.getResponseHeaders().set("Cache-Control", "max-age=3600");
            exchange.getResponseHeaders().set("ETag", "\"one\"");
            if (path.endsWith("denied.wav")) { exchange.sendResponseHeaders(403, -1); exchange.close(); return; }
            if (path.endsWith("redirect.wav")) {
                exchange.getResponseHeaders().set("Location", "https://example.invalid/SECRET_URL");
                exchange.sendResponseHeaders(302, -1); exchange.close(); return;
            }
            if (path.endsWith("unknown.wav")) {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(AUDIO, 0, 4321); exchange.close(); return;
            }
            if (path.endsWith("ignore.wav")) {
                exchange.sendResponseHeaders(200, AUDIO.length);
                try { exchange.getResponseBody().write(AUDIO); } catch (IOException ignored) { }
                exchange.close(); return;
            }
            if (path.endsWith("notaudio.wav")) exchange.getResponseHeaders().set("Content-Type", "text/html");
            long[] bounds = RangeCache.parseRange(range, AUDIO.length);
            if (bounds == null) {
                exchange.getResponseHeaders().set("Content-Range", "bytes */" + AUDIO.length);
                exchange.sendResponseHeaders(416, -1); exchange.close(); return;
            }
            int start = (int) bounds[0], end = (int) bounds[1];
            if (path.endsWith("version.wav") && start >= CHUNK) exchange.getResponseHeaders().set("ETag", "\"two\"");
            if (path.endsWith("weak-switch.wav") && start >= CHUNK) exchange.getResponseHeaders().set("ETag", "W/\"one\"");
            if (path.endsWith("missing-switch.wav") && start >= CHUNK) exchange.getResponseHeaders().remove("ETag");
            if (path.endsWith("new-validator.wav") && start < CHUNK) exchange.getResponseHeaders().remove("ETag");
            if (path.endsWith("total.wav") && start >= CHUNK) {
                exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + (AUDIO.length + 1));
            } else if (path.endsWith("bad.wav")) {
                exchange.getResponseHeaders().set("Content-Range", "bytes " + (start + 1) + "-" + (end + 1) + "/" + AUDIO.length);
            } else exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + AUDIO.length);
            if (path.endsWith("nostore.wav")) exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(206, end - start + 1);
            try {
                exchange.getResponseBody().write(AUDIO, start,
                        path.endsWith("truncate.wav") ? Math.min(97, end - start + 1) : end - start + 1);
            } catch (IOException ignored) { }
            try { exchange.close(); } catch (RuntimeException ignored) { }
        });
        server.start();
        try {
            fullAndOffline(directory.resolve("full"));
            mixedCachePlayback(directory.resolve("mixed"));
            positions(directory.resolve("positions"));
            earlyClose(directory.resolve("early"));
            streamFallbacks(directory.resolve("fallback"));
            invalidResponses(directory.resolve("failures"));
            authorizationAndPrivacy(directory.resolve("privacy"));
            System.out.println("PASS " + checks + " native DataSource checks (real HTTP/disk; Media3 API stubs, no device playback claim)");
        } finally {
            server.stop(0); workers.shutdownNow();
            try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.delete(path); } catch (IOException ignored) { } });
            }
        }
    }

    private static void mixedCachePlayback(Path root) throws Exception {
        // Seek may have cached a later range before sequential playback starts. Reopen the
        // actual metadata so these cases also cover caches written by an earlier process.
        for (int cachedStart : new int[] {0, CHUNK}) {
            Path directory = root.resolve("same-" + cachedStart);
            seed(directory, "mixed", cachedStart, CHUNK, "\"one\"");
            CacheProxy proxy = proxy(directory); CacheDataSource source = source(proxy, "mixed");
            source.open(spec(0, -1));
            equal(readAll(source), AUDIO, "same version crosses cache/network boundary at " + cachedStart);
            source.close();
            check(proxy.snapshot().hitBytes == CHUNK, "existing partial range actually reused at " + cachedStart);
            check(proxy.snapshot().networkBytes == AUDIO.length - CHUNK, "only missing bytes downloaded at " + cachedStart);
        }
        Path changed = root.resolve("changed");
        seed(changed, "version", 0, CHUNK, "\"one\"");
        expectBoundaryFailure(source(proxy(changed), "version"), "cached old version rejects network new version");

        for (String tag : new String[] {"", "W/\"one\""}) {
            Path directory = root.resolve(tag.isEmpty() ? "missing" : "weak");
            seed(directory, "mixed", 0, CHUNK, tag);
            expectBoundaryFailure(source(proxy(directory), "mixed"), "unverified cached prefix cannot mix with network " + tag);
        }
        for (String name : new String[] {"weak-switch", "missing-switch", "new-validator"}) {
            expectBoundaryFailure(source(proxy(root.resolve(name)), name), "validator availability change rejects unsafe continuation " + name);
        }

        Path full = root.resolve("unverified-full");
        seed(full, "mixed", 0, AUDIO.length, "");
        CacheProxy offline = proxy(full); offline.setOffline(true);
        CacheDataSource fullSource = source(offline, "mixed"); fullSource.open(spec(0, -1));
        equal(readAll(fullSource), AUDIO, "one complete cached response without ETag remains playable across read chunks");
        fullSource.close();
        check(offline.snapshot().networkBytes == 0, "unverified full response stays entirely local");

        // No-validator records cannot be trusted after another response replaces their bytes.
        Path replaced = root.resolve("replaced");
        seed(replaced, "mixed", 0, AUDIO.length, "");
        RangeCache cache = new RangeCache(replaced.toFile());
        CacheProxy proxy = new CacheProxy(cache, true); CacheDataSource changing = source(proxy, "mixed");
        changing.open(spec(0, -1));
        byte[] first = new byte[CHUNK]; int read = 0;
        while (read < CHUNK) read += changing.read(first, read, CHUNK - read);
        check(cache.put(url("mixed"), "audio/wav", AUDIO.length, 0, AUDIO.length,
                new ByteArrayInputStream(AUDIO), Map.of("Cache-Control", "max-age=3600")), "replace no-validator response");
        boolean failed = false;
        try { changing.read(new byte[1], 0, 1); } catch (IOException expected) { failed = true; }
        finally { changing.close(); }
        check(failed, "replacement of unidentified cached response stops before new bytes");

        Path legacy = root.resolve("legacy");
        seed(legacy, "mixed", 0, CHUNK, "\"one\"");
        Path metadata = legacy.resolve(RangeCache.keyForUrl(url("mixed")) + ".meta");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(Files.readAllBytes(metadata)));
             ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream output = new DataOutputStream(bytes)) {
            input.readInt(); output.writeInt(0x4d434132);
            for (int i = 0; i < 4; i++) output.writeUTF(input.readUTF());
            output.writeLong(input.readLong()); output.writeLong(input.readLong()); output.writeBoolean(input.readBoolean());
            input.readUTF(); output.write(input.readAllBytes()); output.flush(); Files.write(metadata, bytes.toByteArray());
        }
        CacheDataSource oldSource = source(proxy(legacy), "mixed"); oldSource.open(spec(0, -1));
        equal(readAll(oldSource), AUDIO, "legacy metadata's hashed ETag still matches current network response"); oldSource.close();

        Path privacy = root.resolve("private-etag");
        seed(privacy, "mixed", 0, AUDIO.length, "\"PRIVATE_ETAG_DO_NOT_PERSIST\"");
        try (java.util.stream.Stream<Path> files = Files.list(privacy)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".meta")).toList()) {
                String raw = new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.ISO_8859_1);
                check(!raw.contains("PRIVATE_ETAG_DO_NOT_PERSIST"), "raw ETag stays out of metadata");
            }
        }
    }

    private static void seed(Path directory, String name, int start, int count, String etag) throws Exception {
        check(new RangeCache(directory.toFile()).put(url(name), "audio/wav", AUDIO.length, start, count,
                new ByteArrayInputStream(AUDIO, start, count),
                Map.of("ETag", etag, "Cache-Control", "max-age=3600", "Content-Type", "audio/wav")), "prepare partial-cache scenario");
    }

    private static void expectBoundaryFailure(CacheDataSource source, String label) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] bytes = new byte[8192]; boolean failed = false;
        try {
            source.open(spec(0, -1));
            for (int count; (count = source.read(bytes, 0, bytes.length)) >= 0;) output.write(bytes, 0, count);
        } catch (IOException expected) {
            failed = expected instanceof DataSourceException && "读取期间歌曲版本发生变化".equals(expected.getMessage());
        }
        finally { source.close(); }
        check(failed && output.size() == CHUNK, label);
    }

    private static void fullAndOffline(Path path) throws Exception {
        CacheProxy proxy = proxy(path); CacheDataSource source = source(proxy, "full");
        REQUESTS.clear();
        check(source.open(spec(0, -1)) == AUDIO.length, "open resolves entire entity length, not first chunk length");
        equal(readAll(source), AUDIO, "three chunks reproduce original bytes");
        check(REQUESTS.equals(Arrays.asList("/media/full.wav:bytes=0-262143", "/media/full.wav:bytes=262144-524287",
                "/media/full.wav:bytes=524288-528608")), "bounded sequential request offsets and final clamp");
        check(proxy.snapshot().networkBytes == AUDIO.length, "network byte counter equals actual song");
        check(proxy.snapshot().entries.size() == 1 && proxy.snapshot().entries.get(0).complete, "all committed chunks form complete cache");
        check(source.getUri().toString().equals(SONG.toString()), "public URI remains opaque song URI");
        check(source.getResponseHeaders().containsKey("content-range"), "headers case insensitive");
        source.close();
        check(source.transferStarts == 1 && source.transferEnds == 1 && source.transferred == AUDIO.length, "transfer lifecycle balanced");
        check(source.getUri() == null && source.getResponseHeaders().isEmpty(), "close drops URI and headers");
        long network = proxy.snapshot().networkBytes; REQUESTS.clear(); proxy.setOffline(true);
        check(source.open(spec(0, -1)) == AUDIO.length, "same instance reopens cached resource");
        equal(readAll(source), AUDIO, "cached full audio offline"); source.close();
        check(REQUESTS.isEmpty() && proxy.snapshot().networkBytes == network, "offline cached playback sends no HTTP");
        check(proxy.snapshot().hitBytes == AUDIO.length, "cache hits count actual bytes");
    }

    private static void positions(Path path) throws Exception {
        CacheProxy proxy = proxy(path); CacheDataSource source = source(proxy, "seek");
        check(source.open(spec(CHUNK - 30, CHUNK + 80)) == CHUNK + 80, "bounded request returns requested length");
        equal(readAll(source), Arrays.copyOfRange(AUDIO, CHUNK - 30, CHUNK * 2 + 50), "nonzero seek across bounded chunks"); source.close();
        check(source.open(spec(AUDIO.length - 100, 1000)) == 1000, "length beyond EOF preserves Media3 open contract");
        equal(readAll(source), Arrays.copyOfRange(AUDIO, AUDIO.length - 100, AUDIO.length), "bounded read stops at real EOF"); source.close();
        check(source.open(spec(AUDIO.length, -1)) == 0, "416 at exact EOF opens empty");
        check(source.read(new byte[8], 0, 8) == C.RESULT_END_OF_INPUT, "EOF read returns END_OF_INPUT"); source.close();
        expectOpenReason(source, spec(AUDIO.length + 1L, -1), PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, "416 past EOF typed error");
        expectOpenReason(source, spec(Long.MAX_VALUE, -1), PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, "large position avoids Range overflow");
    }

    private static void earlyClose(Path path) throws Exception {
        CacheProxy proxy = proxy(path); CacheDataSource source = source(proxy, "early");
        source.open(spec(0, -1));
        byte[] first = new byte[CHUNK]; int total = 0;
        while (total < first.length) total += source.read(first, total, first.length - total);
        check(source.read(new byte[29], 0, 29) == 29, "second chunk starts lazily"); source.close();
        check(proxy.snapshot().entries.size() == 1 && proxy.snapshot().entries.get(0).cachedBytes == CHUNK,
                "early close retains complete first chunk and discards partial second chunk");
        proxy.setOffline(true); source.open(spec(0, 100));
        equal(readAll(source), Arrays.copyOf(AUDIO, 100), "early seek back reuses retained first chunk"); source.close();
        expectOpenReason(source, spec(CHUNK, 100), PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, "offline incomplete second chunk fails");
    }

    private static void streamFallbacks(Path path) throws Exception {
        CacheProxy proxy = proxy(path); CacheDataSource ignore = source(proxy, "ignore");
        check(ignore.open(spec(100, 300)) == 300, "200 ignored Range opens bounded request");
        equal(readAll(ignore), Arrays.copyOfRange(AUDIO, 100, 400), "200 reads and discards real prefix"); ignore.close();
        check(proxy.snapshot().networkBytes == 400, "ignored Range prefix counted as real network");
        check(proxy.snapshot().entries.isEmpty(), "partially consumed full 200 is not committed");
        CacheDataSource unknown = source(proxy, "unknown");
        check(unknown.open(spec(0, -1)) == -1, "chunked 200 keeps unknown length");
        equal(readAll(unknown), Arrays.copyOf(AUDIO, 4321), "unknown length streams until actual EOF"); unknown.close();
        check(proxy.snapshot().entries.isEmpty(), "unknown length does not invent complete cache");
        CacheDataSource noStore = source(proxy, "nostore"); noStore.open(spec(0, 100));
        equal(readAll(noStore), Arrays.copyOf(AUDIO, 100), "no-store remains playable"); noStore.close();
        check(proxy.snapshot().entries.isEmpty(), "no-store does not commit");
    }

    private static void invalidResponses(Path path) throws Exception {
        CacheProxy proxy = proxy(path);
        expectOpenReason(source(proxy, "denied"), spec(0, -1), PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, "403 remains failure");
        expectOpenReason(source(proxy, "redirect"), spec(0, -1), PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, "redirect cannot escape allowlist");
        expectOpenReason(source(proxy, "bad"), spec(0, 100), PlaybackException.ERROR_CODE_IO_UNSPECIFIED, "mismatched Content-Range rejected");
        expectOpenReason(source(proxy, "notaudio"), spec(0, 100), PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE, "HTML never presented as audio");
        for (String name : new String[]{"version", "total"}) {
            CacheDataSource source = source(proxy, name); source.open(spec(0, -1));
            ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] bytes = new byte[8192]; boolean failed = false;
            try { for (int count; (count = source.read(bytes, 0, bytes.length)) >= 0;) output.write(bytes, 0, count); }
            catch (IOException error) { failed = true; }
            finally { source.close(); }
            check(failed && output.size() == CHUNK, name + " changes stop before emitting second chunk");
        }
        CacheDataSource truncated = source(proxy, "truncate"); truncated.open(spec(0, 500));
        boolean failed = false;
        try { readAll(truncated); } catch (IOException error) { failed = true; } finally { truncated.close(); }
        check(failed, "truncated finite response is not clean EOF");
        check(proxy.snapshot().entries.stream().noneMatch(entry -> entry.cachedBytes == 500), "truncated response never commits declared length");
    }

    private static void authorizationAndPrivacy(Path path) throws Exception {
        CacheProxy proxy = proxy(path); AtomicInteger resolves = new AtomicInteger();
        CacheDataSource source = new CacheDataSource(proxy, song -> {
            resolves.incrementAndGet(); return new PlayerRuntime.ResolvedAudio("https://example.invalid/secret.mp3?token=DO_NOT_LOG", Map.of());
        });
        for (String uri : new String[]{"https://example.invalid/song.mp3", "qqmusic://song/a?token=DO_NOT_LOG", "qqmusic://song/a/b", "qqmusic://song/%61", "qqmusic://user@song/a"}) {
            try { source.open(new DataSpec(Uri.parse(uri), 0, -1)); throw new AssertionError("accepted arbitrary URI"); }
            catch (IOException expected) { check(!expected.toString().contains("DO_NOT_LOG"), "invalid URI error sanitized"); }
            finally { source.close(); }
        }
        check(resolves.get() == 0, "invalid media URI never invokes resolver");
        try { source.open(spec(0, -1)); throw new AssertionError("accepted external resolver URL"); }
        catch (IOException expected) { check(!expected.toString().contains("DO_NOT_LOG") && expected.getCause() == null, "resolver URL not leaked through exception"); }
        finally { source.close(); }
        CacheDataSource throwing = new CacheDataSource(proxy, song -> { throw new IOException("https://host/SECRET_URL"); });
        try { throwing.open(spec(0, -1)); throw new AssertionError("resolver exception lost"); }
        catch (IOException expected) { check(!expected.toString().contains("SECRET_URL") && expected.getCause() == null, "resolver exception cleaned"); }
        finally { throwing.close(); }
        CacheDataSource valid = source(proxy, "private"); valid.open(spec(0, 200)); readAll(valid); valid.close();
        try (java.util.stream.Stream<Path> files = Files.walk(path)) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                if (file.getFileName().toString().endsWith(".meta")) {
                    String metadata = new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.ISO_8859_1);
                    check(!metadata.contains("/media/") && !metadata.contains("variant=") && !metadata.contains("qqmusic://"), "metadata contains no playback URL");
                }
            }
        }
    }

    private static CacheProxy proxy(Path path) throws IOException { return new CacheProxy(new RangeCache(path.toFile()), true); }
    private static CacheDataSource source(CacheProxy proxy, String name) {
        return new CacheDataSource(proxy, song -> new PlayerRuntime.ResolvedAudio(url(name), Map.of()));
    }
    private static String url(String name) { return "http://127.0.0.1:8765/media/" + name + ".wav?run=native&variant=one"; }
    private static DataSpec spec(long start, long length) { return new DataSpec(SONG, start, length); }
    private static byte[] readAll(CacheDataSource source) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] bytes = new byte[8192];
        for (int count; (count = source.read(bytes, 0, bytes.length)) >= 0;) output.write(bytes, 0, count);
        return output.toByteArray();
    }
    private static void expectOpenReason(CacheDataSource source, DataSpec spec, int reason, String label) throws Exception {
        try { source.open(spec); throw new AssertionError(label + ": opened"); }
        catch (DataSourceException expected) { check(expected.reason == reason, label); }
        finally { source.close(); }
    }
    private static void equal(byte[] actual, byte[] expected, String label) { check(Arrays.equals(actual, expected), label); }
    private static void check(boolean condition, String label) { if (!condition) throw new AssertionError(label); checks++; }
}
