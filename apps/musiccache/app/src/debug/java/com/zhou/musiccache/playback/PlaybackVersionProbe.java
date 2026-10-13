package com.zhou.musiccache.playback;

import android.net.Uri;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DataSourceException;
import com.zhou.musiccache.BuildConfig;
import com.zhou.musiccache.cache.CacheProxy;
import com.zhou.musiccache.cache.RangeCache;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

/** Debug-only: actual Media3 DataSource and loopback HTTP; no accounts, songs, or audio output. */
public final class PlaybackVersionProbe {
    private static final int CHUNK = 256 * 1024;
    private PlaybackVersionProbe() { }

    public static void run(File files, String run) {
        if (!BuildConfig.DEBUG || run == null || !run.matches("[A-Za-z0-9_-]{1,64}")) return;
        File directory = new File(files, "selftest/" + run + "/native-version");
        JSONArray checks = new JSONArray(), failed = new JSONArray();
        long started = System.currentTimeMillis();
        try {
            if (directory.exists() || !directory.mkdirs()) throw new IOException("New probe directory required");
            String url = "http://127.0.0.1:8765/media/native.wav?run=" + run + "&variant=one";
            byte[] expected = generated();
            String etag;
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(5000); connection.setReadTimeout(5000);
            connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
            try {
                if (connection.getResponseCode() != 200) throw new IOException("Fixture unavailable");
                etag = connection.getHeaderField("ETag");
                byte[] fixture;
                try (InputStream body = connection.getInputStream()) { fixture = read(body); }
                check(checks, failed, "fixture_generated_bytes", Arrays.equals(expected, fixture));
            } finally { connection.disconnect(); }
            if (etag == null || !etag.startsWith("\"native-")) throw new IOException("Fixture version missing");
            Map<String, String> headers = Map.of("ETag", etag, "Cache-Control", "max-age=3600");
            for (int start : new int[] {0, CHUNK}) {
                File cacheDirectory = new File(directory, "same-" + start);
                RangeCache cache = new RangeCache(cacheDirectory);
                check(checks, failed, "seed_same_" + start, cache.put(url, "audio/wav", expected.length,
                        start, CHUNK, new ByteArrayInputStream(expected, start, CHUNK), headers));
                CacheProxy proxy = new CacheProxy(new RangeCache(cacheDirectory), true);
                Outcome result = consume(proxy, url);
                check(checks, failed, "same_version_bytes_" + start, !result.failed && Arrays.equals(result.bytes, expected));
                check(checks, failed, "same_version_cache_hit_" + start, proxy.snapshot().hitBytes == CHUNK);
                check(checks, failed, "same_version_network_missing_only_" + start,
                        proxy.snapshot().networkBytes == expected.length - CHUNK);
            }
            String changed = url.replace("variant=one", "variant=changed");
            RangeCache old = new RangeCache(new File(directory, "changed"));
            check(checks, failed, "seed_changed_version", old.put(changed, "audio/wav", expected.length,
                    0, CHUNK, new ByteArrayInputStream(expected, 0, CHUNK), headers));
            Outcome rejected = consume(new CacheProxy(old, true), changed);
            check(checks, failed, "changed_version_stops_at_boundary", rejected.versionRejected && rejected.bytes.length == CHUNK
                    && Arrays.equals(rejected.bytes, Arrays.copyOf(expected, CHUNK)));

            RangeCache unidentified = new RangeCache(new File(directory, "unknown"));
            Map<String, String> noVersion = Map.of("Cache-Control", "max-age=3600");
            check(checks, failed, "seed_unidentified_prefix", unidentified.put(url, "audio/wav", expected.length,
                    0, CHUNK, new ByteArrayInputStream(expected, 0, CHUNK), noVersion));
            Outcome unknown = consume(new CacheProxy(unidentified, true), url);
            check(checks, failed, "unknown_prefix_does_not_mix", unknown.versionRejected && unknown.bytes.length == CHUNK);

            RangeCache full = new RangeCache(new File(directory, "unknown-full"));
            check(checks, failed, "seed_unidentified_full", full.put(url, "audio/wav", expected.length,
                    0, expected.length, new ByteArrayInputStream(expected), noVersion));
            CacheProxy offline = new CacheProxy(full, true); offline.setOffline(true);
            Outcome whole = consume(offline, url);
            check(checks, failed, "unidentified_full_offline", !whole.failed && Arrays.equals(whole.bytes, expected)
                    && offline.snapshot().networkBytes == 0);
        } catch (Exception error) {
            try { check(checks, failed, "probe_exception_" + error.getClass().getSimpleName(), false); }
            catch (Exception ignored) { failed.put("probe_exception"); }
        }
        try {
            JSONObject report = new JSONObject().put("passed", failed.length() == 0).put("checks", checks)
                    .put("failed", failed).put("checkCount", checks.length()).put("run", run)
                    .put("pid", android.os.Process.myPid()).put("appVersion", BuildConfig.VERSION_NAME)
                    .put("generatedOnly", true).put("actualMedia3DataSource", true).put("audioOutputTested", false)
                    .put("elapsedMillis", System.currentTimeMillis() - started);
            try (FileOutputStream output = new FileOutputStream(new File(directory.getParentFile(), "native-version.json"))) {
                output.write(report.toString(2).getBytes(StandardCharsets.UTF_8)); output.getFD().sync();
            }
        } catch (Exception ignored) { /* Harness treats absent report as a failure. */ }
    }

    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    private static Outcome consume(CacheProxy proxy, String url) throws IOException {
        CacheDataSource source = new CacheDataSource(proxy, song -> new PlayerRuntime.ResolvedAudio(url, Map.of()));
        ByteArrayOutputStream output = new ByteArrayOutputStream(); boolean failed = false, versionRejected = false;
        try {
            source.open(new DataSpec.Builder().setUri(Uri.parse("qqmusic://song/NativeVersionProbe")).build());
            byte[] buffer = new byte[8192];
            for (int count; (count = source.read(buffer, 0, buffer.length)) >= 0;) output.write(buffer, 0, count);
        } catch (IOException expected) {
            failed = true;
            versionRejected = expected instanceof DataSourceException && "读取期间歌曲版本发生变化".equals(expected.getMessage());
        }
        finally { source.close(); }
        return new Outcome(output.toByteArray(), failed, versionRejected);
    }
    private static byte[] read(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192];
        for (int count; (count = input.read(buffer)) >= 0;) {
            if (output.size() + count > CHUNK * 3) throw new IOException("Fixture too large");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
    private static byte[] generated() {
        byte[] bytes = new byte[CHUNK * 2 + 4321];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i * 71 + i / 13);
        return bytes;
    }
    private static void check(JSONArray checks, JSONArray failed, String name, boolean ok) throws Exception {
        checks.put(new JSONObject().put("name", name).put("ok", ok)); if (!ok) failed.put(name);
    }
    private static final class Outcome {
        final byte[] bytes; final boolean failed, versionRejected;
        Outcome(byte[] bytes, boolean failed, boolean versionRejected) {
            this.bytes = bytes; this.failed = failed; this.versionRejected = versionRejected;
        }
    }
}
