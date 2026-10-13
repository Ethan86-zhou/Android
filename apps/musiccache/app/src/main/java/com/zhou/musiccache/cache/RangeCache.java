package com.zhou.musiccache.cache;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 按 HTTP 字节区间保存音频。磁盘只记录 URL 的 SHA-256，不写签名 URL、Cookie 或令牌。
 * 每个响应先写临时文件，收到承诺的全部字节后才原子发布元数据；失败的响应不能补齐歌曲。
 */
public final class RangeCache {
    public static final long MAX_TOTAL_BYTES = 256L * 1024 * 1024;
    public static final long MAX_RESOURCE_BYTES = 64L * 1024 * 1024;
    private static final int LEGACY_MAGIC = 0x4d434132;
    private static final int MAGIC = 0x4d434133;
    private static final int ALIAS_MAGIC = 0x4d434c31;
    private final File directory;
    private final long maxTotal, maxResource;
    private final Map<String, Record> records = new LinkedHashMap<>();
    private final Map<String, Alias> aliases = new LinkedHashMap<>();
    private final Map<String, Long> revisions = new HashMap<>();
    private long reserved;
    private long generation;

    public RangeCache(File directory) throws IOException {
        this(directory, MAX_TOTAL_BYTES, MAX_RESOURCE_BYTES);
    }

    public RangeCache(File directory, long maxTotal, long maxResource) throws IOException {
        this.directory = directory;
        this.maxTotal = maxTotal;
        this.maxResource = maxResource;
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create audio cache");
        load();
    }

    public static final class Span {
        public final long start, endInclusive;
        public Span(long start, long endInclusive) { this.start = start; this.endInclusive = endInclusive; }
    }

    public static final class Entry {
        public final String key, label, mimeType, resourceKey;
        public final long totalBytes, cachedBytes, expiresAtMillis;
        public final boolean complete, fresh, heuristic, canCombineRanges;
        public final List<Span> spans;
        private Entry(Record record) {
            key = record.key; label = record.label; mimeType = record.mime;
            resourceKey = record.resourceKey;
            totalBytes = record.total; expiresAtMillis = record.expiry;
            spans = Collections.unmodifiableList(merged(record.chunks));
            long sum = 0;
            for (Span span : spans) sum += span.endInclusive - span.start + 1;
            cachedBytes = sum;
            complete = spans.size() == 1 && spans.get(0).start == 0 && spans.get(0).endInclusive == totalBytes - 1;
            fresh = expiryIsFresh(record.expiry);
            heuristic = record.heuristic;
            canCombineRanges = !record.validator.isEmpty();
        }
    }

    public static final class CachedResponse implements Closeable {
        public final InputStream body;
        public final String mimeType;
        public final int statusCode;
        public final long start, endInclusive, totalBytes, length;
        public final Map<String, String> headers;
        private CachedResponse(InputStream body, Record record, long[] range, boolean partial) {
            this.body = body; mimeType = record.mime; statusCode = partial ? 206 : 200;
            start = range[0]; endInclusive = range[1]; totalBytes = record.total; length = endInclusive - start + 1;
            Map<String, String> values = new LinkedHashMap<>(record.headers);
            values.put("Content-Length", Long.toString(length));
            values.put("Accept-Ranges", "bytes");
            // WebView 自身不能再次保存，否则会绕过本实验的命中字节统计和离线开关。
            values.put("Cache-Control", "no-store");
            if (partial) values.put("Content-Range", "bytes " + start + "-" + endInclusive + "/" + totalBytes);
            headers = Collections.unmodifiableMap(values);
        }
        @Override public void close() throws IOException { body.close(); }
    }

    private static final class Chunk {
        final long start, length;
        final String file;
        Chunk(long start, long length, String file) { this.start = start; this.length = length; this.file = file; }
    }

    private static final class Record {
        String key, label, mime, validator;
        String resourceKey = "";
        long total, expiry;
        boolean heuristic;
        Map<String, String> headers = new LinkedHashMap<>();
        List<Chunk> chunks = new ArrayList<>();
        Record copy() {
            Record copy = new Record();
            copy.key = key; copy.label = label; copy.mime = mime; copy.validator = validator;
            copy.total = total; copy.expiry = expiry; copy.headers.putAll(headers); copy.chunks.addAll(chunks);
            copy.heuristic = heuristic;
            copy.resourceKey = resourceKey;
            return copy;
        }
    }

    private static final class Alias {
        String key, sourceKey, resourceKey, validator, mime;
        long total, expiry;
        Map<String, String> headers = new LinkedHashMap<>();
    }

    public static String keyForUrl(String url) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte value : hash) result.append(String.format(Locale.ROOT, "%02x", value & 255));
            return result.toString();
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    /** 同origin+raw path+内容query，仅剔除已知签名参数；磁盘只保存最终哈希。 */
    public static String resourceKeyForUrl(String url, boolean allowTestLoopback) {
        try {
            URI uri = new URI(url);
            if (uri.getUserInfo() != null || uri.getFragment() != null || uri.getHost() == null) return "";
            String host = uri.getHost().toLowerCase(Locale.ROOT), path = uri.getRawPath();
            if (path == null) return "";
            if ("https".equalsIgnoreCase(uri.getScheme()) && (uri.getPort() == -1 || uri.getPort() == 443)
                    && host.endsWith(".stream.qqmusic.qq.com") && host.length() > ".stream.qqmusic.qq.com".length()
                    && path.toLowerCase(Locale.ROOT).matches(".*\\.(m4a|mp3|aac|flac|ogg)")) {
                return keyForUrl("qqmusic-origin-v1\nhttps://" + host + ":443" + path + retainedQuery(uri.getRawQuery(), false));
            }
            if (allowTestLoopback && "http".equalsIgnoreCase(uri.getScheme()) && host.equals("127.0.0.1")
                    && uri.getPort() == 8765 && path.matches("/media/[A-Za-z0-9_-]+\\.wav")
                    && (uri.getRawQuery() == null || uri.getRawQuery().matches("(?:run|variant)=[A-Za-z0-9_-]{1,96}(?:&(?:run|variant)=[A-Za-z0-9_-]{1,96})*"))) {
                return keyForUrl("loopback-selftest-v1\nhttp://127.0.0.1:8765" + path + retainedQuery(uri.getRawQuery(), true));
            }
        } catch (Exception ignored) { }
        return "";
    }

    private static String retainedQuery(String query, boolean test) {
        if (query == null || query.isEmpty()) return "";
        List<String> retained = new ArrayList<>();
        for (String component : query.split("&", -1)) {
            int equals = component.indexOf('=');
            String name = equals < 0 ? component : component.substring(0, equals);
            boolean signature = test ? name.equals("variant") : name.equals("guid") || name.equals("vkey")
                    || name.equals("uin") || name.equals("fromtag");
            if (!signature) retained.add(component);
        }
        return retained.isEmpty() ? "" : "?" + String.join("&", retained);
    }

    public synchronized boolean hasReusableResource(String resourceKey) {
        if (!validHash(resourceKey)) return false;
        for (Record record : records.values()) {
            if (resourceKey.equals(record.resourceKey) && !record.validator.isEmpty() && new Entry(record).complete) return true;
        }
        return false;
    }

    /** 只能在当前完整URL真实授权请求成功之后调用；alias绑定具体源版本，不占第二份音频空间。 */
    public synchronized boolean reuseValidated(String url, String resourceKey, String mime, long total,
                                               Map<String, String> headers) throws IOException {
        String version = validator(headers);
        long expiry = expiryFor(headers, System.currentTimeMillis());
        if (!validHash(resourceKey) || version.isEmpty() || !expiryIsFresh(expiry)) return false;
        for (Record source : records.values()) {
            if (!resourceKey.equals(source.resourceKey) || !version.equals(source.validator) || source.total != total
                    || !source.mime.equalsIgnoreCase(mime) || !new Entry(source).complete) continue;
            Alias alias = new Alias();
            alias.key = keyForUrl(url); alias.sourceKey = source.key; alias.resourceKey = resourceKey;
            alias.validator = version; alias.mime = source.mime; alias.total = total; alias.expiry = expiry;
            alias.headers = storedHeaders(headers);
            if (alias.key.equals(source.key)) {
                Record renewed = source.copy(); renewed.expiry = expiry; renewed.headers = storedHeaders(headers);
                save(renewed); records.put(renewed.key, renewed); return true;
            }
            Record previous = records.get(alias.key);
            if (previous != null && (!previous.validator.equals(version) || previous.total != total || !previous.mime.equalsIgnoreCase(mime))) {
                invalidateKey(alias.key);
            }
            saveAlias(alias); aliases.put(alias.key, alias);
            while (aliases.size() > 512) removeAlias(aliases.keySet().iterator().next());
            return true;
        }
        return false;
    }

    public synchronized boolean isAlias(String url) { return aliases.containsKey(keyForUrl(url)); }

    /** 当前URL明确授权失败时禁用其命中，但保留音频字节供未来另一个实际获授权URL重新验证。 */
    public synchronized void rejectAuthorization(String url) throws IOException {
        String key = keyForUrl(url);
        removeAlias(key);
        Record record = records.get(key);
        if (record != null) {
            Record rejected = record.copy(); rejected.expiry = 0;
            save(rejected); records.put(key, rejected);
        }
    }

    private static boolean validHash(String hash) { return hash != null && hash.matches("[0-9a-f]{64}"); }

    private boolean aliasMatches(Alias alias, Record source) {
        return source != null && !alias.validator.isEmpty() && alias.sourceKey.equals(source.key)
                && alias.resourceKey.equals(source.resourceKey) && alias.validator.equals(source.validator)
                && alias.total == source.total && alias.mime.equalsIgnoreCase(source.mime) && new Entry(source).complete;
    }

    private void removeAlias(String key) throws IOException {
        aliases.remove(key); Files.deleteIfExists(new File(directory, key + ".alias").toPath());
    }

    public synchronized List<Entry> entries() {
        List<Entry> result = new ArrayList<>();
        for (Record record : records.values()) result.add(new Entry(record));
        return Collections.unmodifiableList(result);
    }

    /** 实际占用，包括重叠响应的文件字节；单个条目的 cachedBytes 为去重后的覆盖字节。 */
    public synchronized long sizeBytes() {
        long total = 0;
        for (Record record : records.values()) for (Chunk chunk : record.chunks) total += chunk.length;
        return total;
    }

    public synchronized void clear() throws IOException {
        generation++;
        for (String key : new ArrayList<>(records.keySet())) invalidateKey(key);
        for (String key : new ArrayList<>(aliases.keySet())) removeAlias(key);
    }

    public synchronized void invalidate(String url) throws IOException { invalidateKey(keyForUrl(url)); }

    private void invalidateKey(String key) throws IOException {
        revisions.put(key, revisions.getOrDefault(key, 0L) + 1);
        Record record = records.remove(key);
        for (Alias alias : new ArrayList<>(aliases.values())) {
            if (alias.key.equals(key) || alias.sourceKey.equals(key)) removeAlias(alias.key);
        }
        Files.deleteIfExists(new File(directory, key + ".meta").toPath());
        if (record != null) for (Chunk chunk : record.chunks) Files.deleteIfExists(new File(directory, chunk.file).toPath());
    }

    /** 只接受单区间；缺段、过期、无效区间一律返回 null，绝不把部分文件当完整歌曲。 */
    public synchronized CachedResponse open(String url, String rangeHeader) throws IOException {
        return open(url, rangeHeader, resourceKeyForUrl(url, false));
    }

    public synchronized CachedResponse open(String url, String rangeHeader, String resourceKey) throws IOException {
        resourceKey = validHash(resourceKey) ? resourceKey : "";
        String key = keyForUrl(url);
        Record record = records.get(key);
        // 旧格式没有路径身份：只有完整URL哈希精确命中才允许回填，禁止按ETag或文件名猜测。
        if (record != null && record.resourceKey.isEmpty() && validHash(resourceKey)) {
            Record migrated = record.copy(); migrated.resourceKey = resourceKey;
            save(migrated); records.put(key, migrated); record = migrated;
        }
        Alias alias = aliases.get(key);
        if (alias != null && expiryIsFresh(alias.expiry) && resourceKey.equals(alias.resourceKey)) {
            Record source = records.get(alias.sourceKey);
            if (aliasMatches(alias, source)) {
                record = source.copy(); record.expiry = alias.expiry; record.headers = new LinkedHashMap<>(alias.headers);
            } else removeAlias(key);
        }
        if (record == null || !expiryIsFresh(record.expiry)) return null;
        long[] range = parseRange(rangeHeader, record.total);
        if (range == null) return null;
        List<Chunk> selected = new ArrayList<>();
        long position = range[0];
        while (position <= range[1]) {
            Chunk best = null;
            for (Chunk chunk : record.chunks) {
                if (chunk.start <= position && chunk.start + chunk.length > position &&
                    (best == null || chunk.start + chunk.length > best.start + best.length)) best = chunk;
            }
            if (best == null) return null;
            selected.add(best);
            position = Math.min(range[1] + 1, best.start + best.length);
        }
        // 先打开所有文件句柄：随后清空缓存不会令已返回的流读到另一份同名文件。
        List<InputStream> streams = new ArrayList<>();
        try {
            position = range[0];
            for (Chunk chunk : selected) {
                FileInputStream input = new FileInputStream(new File(directory, chunk.file));
                long offset = position - chunk.start;
                input.getChannel().position(offset);
                long length = Math.min(range[1] + 1 - position, chunk.length - offset);
                streams.add(new LimitedInputStream(input, length));
                position += length;
            }
        } catch (IOException error) {
            for (InputStream input : streams) try { input.close(); } catch (IOException ignored) { }
            return null;
        }
        return new CachedResponse(new SequenceInputStream(Collections.enumeration(streams)), record, range, rangeHeader != null && !rangeHeader.trim().isEmpty());
    }

    public static long[] parseRange(String header, long total) {
        if (total <= 0) return null;
        if (header == null || header.trim().isEmpty()) return new long[] {0, total - 1};
        String value = header.trim();
        if (!value.startsWith("bytes=") || value.contains(",")) return null;
        String[] parts = value.substring(6).split("-", -1);
        if (parts.length != 2) return null;
        try {
            long start, end;
            if (parts[0].isEmpty()) {
                if (!parts[1].matches("[0-9]+")) return null;
                long suffix = Long.parseLong(parts[1]);
                if (suffix <= 0) return null;
                start = Math.max(0, total - suffix); end = total - 1;
            } else {
                if (!parts[0].matches("[0-9]+") || (!parts[1].isEmpty() && !parts[1].matches("[0-9]+"))) return null;
                start = Long.parseLong(parts[0]);
                end = parts[1].isEmpty() ? total - 1 : Math.min(total - 1, Long.parseLong(parts[1]));
                if (start >= total || end < start) return null;
            }
            return new long[] {start, end};
        } catch (NumberFormatException error) { return null; }
    }

    /** 测试和内部资源的便利接口。流的所有字节必须与 expectedLength 完全相同。 */
    public boolean put(String url, String mime, long total, long start, long expectedLength,
                       InputStream input, Map<String, String> headers) throws IOException {
        WriteSession session = begin(url, mime, total, start, expectedLength, headers);
        if (session == null) return false;
        try (WriteSession target = session) {
            byte[] buffer = new byte[16384];
            int count;
            while ((count = input.read(buffer)) != -1) if (count > 0) target.append(buffer, 0, count);
            return target.finish();
        }
    }

    public synchronized WriteSession begin(String url, String mime, long total, long start, long expectedLength,
                                            Map<String, String> headers) throws IOException {
        return begin(url, mime, total, start, expectedLength, headers, resourceKeyForUrl(url, false));
    }

    public synchronized WriteSession begin(String url, String mime, long total, long start, long expectedLength,
                                            Map<String, String> headers, String resourceKey) throws IOException {
        String key = keyForUrl(url);
        long expiry = expiryFor(headers, System.currentTimeMillis());
        if (expiry <= System.currentTimeMillis()) { invalidateKey(key); return null; }
        if (total <= 0 || total > maxResource || start < 0 || expectedLength <= 0 ||
            start >= total || expectedLength > total - start || expectedLength > maxTotal) return null;
        // 真实重新获取当前URL时，其先前验证别名不再参与该响应的版本判断。
        if (aliases.containsKey(key)) removeAlias(key);
        String validator = validator(headers);
        Record old = records.get(key);
        // 无强ETag时无法证明两次206属于同一份文件。只保留最新完整响应，不跨响应拼接。
        if (old != null && (validator.isEmpty() || old.total != total || !old.validator.equals(validator) || !old.mime.equals(mime) || !expiryIsFresh(old.expiry))) {
            invalidateKey(key);
        }
        if (records.containsKey(key) && records.get(key).chunks.size() >= 9999) return null;
        if (!makeRoom(key, expectedLength)) return null;
        reserved += expectedLength;
        try {
            return new WriteSession(key, label(url, key), mime, total, start, expectedLength,
                expiry, validator, storedHeaders(headers), generation, revisions.getOrDefault(key, 0L), isHeuristic(headers),
                validHash(resourceKey) ? resourceKey : "");
        } catch (IOException error) { reserved -= expectedLength; throw error; }
    }

    /** 优先淘汰过期音频，其次淘汰最久未保存的音频；保留正在追加的资源和所有写入预留量。 */
    private boolean makeRoom(String protectedKey, long expectedLength) throws IOException {
        if (reserved > maxTotal - expectedLength) return false;
        long available = maxTotal - reserved - expectedLength;
        Record current = records.get(protectedKey);
        long protectedBytes = 0;
        if (current != null) for (Chunk chunk : current.chunks) protectedBytes += chunk.length;
        // 即使删掉其它所有歌曲也放不下时，不能无意义地清空其它缓存。
        if (protectedBytes > available) return false;
        if (sizeBytes() <= available) return true;
        long now = System.currentTimeMillis();
        List<Record> candidates = new ArrayList<>(records.values());
        candidates.removeIf(record -> record.key.equals(protectedKey));
        // 元数据更新时间跨进程保留，无需更改已有缓存文件格式；读取不会改变淘汰顺序。
        candidates.sort(Comparator.comparing((Record record) -> record.expiry > now)
                .thenComparingLong(record -> new File(directory, record.key + ".meta").lastModified())
                .thenComparing(record -> record.key));
        for (Record record : candidates) {
            // 同时删除引用别名、提升版本修订，防止尚未结束的旧响应重新写回被淘汰资源。
            invalidateKey(record.key);
            if (sizeBytes() <= available) return true;
        }
        return false;
    }

    public final class WriteSession implements Closeable {
        private final String key, label, mime, validator, resourceKey;
        private final long total, start, expected, expiry, sessionGeneration, revision;
        private final boolean heuristic;
        private final Map<String, String> headers;
        private final File temporary;
        private final FileOutputStream output;
        private long received;
        private boolean ended;
        private WriteSession(String key, String label, String mime, long total, long start, long expected,
                             long expiry, String validator, Map<String, String> headers, long generation, long revision, boolean heuristic, String resourceKey) throws IOException {
            this.key = key; this.label = label; this.mime = mime; this.total = total; this.start = start;
            this.expected = expected; this.expiry = expiry; this.validator = validator; this.headers = headers;
            sessionGeneration = generation;
            this.revision = revision; this.heuristic = heuristic;
            this.resourceKey = resourceKey;
            temporary = File.createTempFile("capture-", ".part", directory);
            output = new FileOutputStream(temporary);
        }
        public synchronized void append(byte[] bytes, int offset, int count) throws IOException {
            if (ended || count < 0 || count > expected - received) throw new IOException("Invalid audio response length");
            output.write(bytes, offset, count); received += count;
        }
        public synchronized long receivedBytes() { return received; }
        public synchronized boolean finish() throws IOException {
            if (ended) return false;
            if (received != expected) { close(); return false; }
            output.getFD().sync(); output.close();
            synchronized (RangeCache.this) {
                if (generation != sessionGeneration || revisions.getOrDefault(key, 0L) != revision || !expiryIsFresh(expiry)) { close(); return false; }
                Record current = records.get(key);
                if (current != null && (current.total != total || !current.validator.equals(validator))) {
                    close(); return false;
                }
                if (current != null && validator.isEmpty()) {
                    // 两个同时开始的无验证器响应也不能在提交阶段意外合并。
                    close(); return false;
                }
                Record next = current == null ? new Record() : current.copy();
                next.key = key; next.label = label; next.mime = mime; next.total = total; next.validator = validator;
                next.resourceKey = resourceKey;
                next.expiry = current == null ? expiry : Math.min(expiry, current.expiry);
                next.heuristic = heuristic || (current != null && current.heuristic);
                next.headers = new LinkedHashMap<>(headers);
                String name = key + "-" + UUID.randomUUID() + ".bin";
                File destination = new File(directory, name);
                move(temporary, destination);
                next.chunks.add(new Chunk(start, expected, name));
                try { save(next); } catch (IOException error) {
                    Files.deleteIfExists(destination.toPath()); close(); throw error;
                }
                records.put(key, next);
                ended = true; reserved -= expected;
                return true;
            }
        }
        @Override public synchronized void close() throws IOException {
            if (ended) return;
            ended = true;
            try { output.close(); } finally {
                synchronized (RangeCache.this) { reserved -= expected; }
                Files.deleteIfExists(temporary.toPath());
            }
        }
    }

    /** 无明确新鲜期时采用实验默认24小时；no-cache/no-store和不支持的Vary绝不离线复用。 */
    public static long expiryFor(Map<String, String> headers, long now) {
        String control = header(headers, "Cache-Control").toLowerCase(Locale.ROOT);
        String pragma = header(headers, "Pragma").toLowerCase(Locale.ROOT);
        String vary = header(headers, "Vary").toLowerCase(Locale.ROOT).trim();
        String encoding = header(headers, "Content-Encoding").toLowerCase(Locale.ROOT).trim();
        if (control.contains("no-store") || control.contains("no-cache") || pragma.contains("no-cache") ||
            (!vary.isEmpty() && !vary.equals("accept-encoding")) || (!encoding.isEmpty() && !encoding.equals("identity"))) return 0;
        long age = parseNonNegative(header(headers, "Age"), 0);
        long date = parseHttpDate(header(headers, "Date"));
        long currentAge = Math.max(age * 1000L, date > 0 ? Math.max(0, now - date) : 0);
        for (String directive : control.split(",")) {
            String item = directive.trim();
            if (item.startsWith("max-age=")) {
                long seconds = parseNonNegative(item.substring(8).replace("\"", ""), -1);
                if (seconds <= 0) return 0;
                long remaining = Math.min(seconds, 365L * 24 * 3600) * 1000 - currentAge;
                return remaining > 0 ? now + remaining : 0;
            }
        }
        long expires = parseHttpDate(header(headers, "Expires"));
        if (expires <= 0) return header(headers, "Expires").isEmpty() ? now + 24L * 60 * 60 * 1000 : 0;
        return date > 0 ? now + Math.max(0, expires - date - currentAge) : expires;
    }

    public static boolean isHeuristic(Map<String, String> headers) {
        return !header(headers, "Cache-Control").toLowerCase(Locale.ROOT).contains("max-age=") && header(headers, "Expires").isEmpty();
    }

    private static boolean expiryIsFresh(long expiry) { return expiry > System.currentTimeMillis(); }
    private static long parseNonNegative(String text, long fallback) {
        try { long value = Long.parseLong(text.trim()); return value >= 0 && value < Long.MAX_VALUE / 1000 ? value : fallback; }
        catch (RuntimeException error) { return fallback; }
    }
    private static long parseHttpDate(String value) {
        try {
            SimpleDateFormat parser = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
            parser.setLenient(false); return Objects.requireNonNull(parser.parse(value)).getTime();
        } catch (Exception error) { return 0; }
    }
    public static String header(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> item : headers.entrySet()) if (item.getKey() != null && item.getKey().equalsIgnoreCase(name)) return item.getValue() == null ? "" : item.getValue();
        return "";
    }
    private static String validator(Map<String, String> headers) {
        String raw = header(headers, "ETag");
        if (raw.indexOf('\r') >= 0 || raw.indexOf('\n') >= 0) return "";
        String etag = raw.trim();
        if (etag.length() < 2 || etag.charAt(0) != '"' || etag.charAt(etag.length() - 1) != '"') return "";
        for (int i = 1; i < etag.length() - 1; i++) {
            char value = etag.charAt(i);
            if (value == '"' || value <= 0x20 || value == 0x7f || value > 0xff) return "";
        }
        // 弱ETag和仅Last-Modified不能安全用于跨HTTP响应合并Range。
        return keyForUrl(etag);
    }
    private static String label(String url, String key) {
        String extension = "audio";
        try {
            String path = new URI(url).getPath().toLowerCase(Locale.ROOT);
            for (String candidate : new String[] {"m4a", "mp3", "aac", "flac", "ogg", "wav"}) if (path.endsWith("." + candidate)) extension = candidate;
        } catch (Exception ignored) { }
        return extension.toUpperCase(Locale.ROOT) + " · " + key.substring(0, 10);
    }
    private static Map<String, String> storedHeaders(Map<String, String> headers) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String name : new String[] {"Content-Type", "Access-Control-Allow-Origin", "Access-Control-Allow-Credentials", "Access-Control-Expose-Headers", "Cross-Origin-Resource-Policy"}) {
            String value = header(headers, name);
            if (!value.isEmpty() && value.length() < 2048 && value.indexOf('\r') < 0 && value.indexOf('\n') < 0) result.put(name, value);
        }
        return result;
    }
    private static List<Span> merged(List<Chunk> chunks) {
        List<Chunk> sorted = new ArrayList<>(chunks);
        sorted.sort(Comparator.comparingLong(chunk -> chunk.start));
        List<Span> result = new ArrayList<>();
        long start = -1, end = -1;
        for (Chunk chunk : sorted) {
            if (start < 0) { start = chunk.start; end = chunk.start + chunk.length - 1; }
            else if (chunk.start <= end + 1) end = Math.max(end, chunk.start + chunk.length - 1);
            else { result.add(new Span(start, end)); start = chunk.start; end = chunk.start + chunk.length - 1; }
        }
        if (start >= 0) result.add(new Span(start, end));
        return result;
    }
    private void save(Record record) throws IOException {
        File temp = File.createTempFile("metadata-", ".part", directory);
        try {
            try (FileOutputStream file = new FileOutputStream(temp); DataOutputStream out = new DataOutputStream(file)) {
                out.writeInt(MAGIC); out.writeUTF(record.key); out.writeUTF(record.label); out.writeUTF(record.mime);
                out.writeUTF(record.validator); out.writeLong(record.total); out.writeLong(record.expiry); out.writeBoolean(record.heuristic);
                out.writeUTF(record.resourceKey);
                out.writeInt(record.headers.size());
                for (Map.Entry<String, String> header : record.headers.entrySet()) { out.writeUTF(header.getKey()); out.writeUTF(header.getValue()); }
                out.writeInt(record.chunks.size());
                for (Chunk chunk : record.chunks) { out.writeLong(chunk.start); out.writeLong(chunk.length); out.writeUTF(chunk.file); }
                out.flush(); file.getFD().sync();
            }
            move(temp, new File(directory, record.key + ".meta"));
        } finally { Files.deleteIfExists(temp.toPath()); }
    }
    private void saveAlias(Alias alias) throws IOException {
        File temp = File.createTempFile("alias-", ".part", directory);
        try {
            try (FileOutputStream file = new FileOutputStream(temp); DataOutputStream out = new DataOutputStream(file)) {
                out.writeInt(ALIAS_MAGIC); out.writeUTF(alias.key); out.writeUTF(alias.sourceKey);
                out.writeUTF(alias.resourceKey); out.writeUTF(alias.validator); out.writeUTF(alias.mime);
                out.writeLong(alias.total); out.writeLong(alias.expiry); out.writeInt(alias.headers.size());
                for (Map.Entry<String, String> header : alias.headers.entrySet()) { out.writeUTF(header.getKey()); out.writeUTF(header.getValue()); }
                out.flush(); file.getFD().sync();
            }
            move(temp, new File(directory, alias.key + ".alias"));
        } finally { Files.deleteIfExists(temp.toPath()); }
    }
    private static void move(File from, File to) throws IOException {
        try { Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException unsupported) { Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING); }
    }
    private void load() throws IOException {
        Set<String> referenced = new HashSet<>();
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("Cannot list audio cache");
        for (File file : files) {
            if (!file.getName().matches("[0-9a-f]{64}\\.meta")) continue;
            try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
                int format = in.readInt();
                if (format != MAGIC && format != LEGACY_MAGIC) throw new IOException("Metadata version");
                Record record = new Record();
                record.key = in.readUTF(); record.label = in.readUTF(); record.mime = in.readUTF(); record.validator = in.readUTF();
                record.total = in.readLong(); record.expiry = in.readLong(); record.heuristic = in.readBoolean();
                if (format == MAGIC) record.resourceKey = in.readUTF();
                if (!record.resourceKey.isEmpty() && !validHash(record.resourceKey)) throw new IOException("Resource identity");
                if (!file.getName().equals(record.key + ".meta") || record.total <= 0 || record.total > maxResource) throw new IOException("Metadata bounds");
                int count = in.readInt();
                if (count < 0 || count > 10) throw new IOException("Header count");
                for (int i = 0; i < count; i++) record.headers.put(in.readUTF(), in.readUTF());
                count = in.readInt();
                if (count <= 0 || count > 10000) throw new IOException("Chunk count");
                for (int i = 0; i < count; i++) {
                    long start = in.readLong(), length = in.readLong(); String name = in.readUTF();
                    if (!name.matches(record.key + "-[0-9a-f-]{36}\\.bin") || start < 0 || start >= record.total || length <= 0 || length > record.total - start || new File(directory, name).length() != length) throw new IOException("Chunk bounds");
                    record.chunks.add(new Chunk(start, length, name));
                }
                records.put(record.key, record);
                if (sizeBytes() > maxTotal) { records.remove(record.key); throw new IOException("Total cache limit"); }
                referenced.add(file.getName());
                for (Chunk chunk : record.chunks) referenced.add(chunk.file);
            } catch (IOException | RuntimeException corrupt) { Files.deleteIfExists(file.toPath()); }
        }
        for (File file : files) {
            if (!file.getName().matches("[0-9a-f]{64}\\.alias")) continue;
            try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
                if (in.readInt() != ALIAS_MAGIC) throw new IOException("Alias format");
                Alias alias = new Alias();
                alias.key = in.readUTF(); alias.sourceKey = in.readUTF(); alias.resourceKey = in.readUTF();
                alias.validator = in.readUTF(); alias.mime = in.readUTF(); alias.total = in.readLong(); alias.expiry = in.readLong();
                if (!file.getName().equals(alias.key + ".alias") || !validHash(alias.sourceKey) || !validHash(alias.resourceKey)
                        || !validHash(alias.validator) || aliases.size() >= 512) throw new IOException("Alias identity");
                int count = in.readInt();
                if (count < 0 || count > 10) throw new IOException("Alias headers");
                for (int i = 0; i < count; i++) alias.headers.put(in.readUTF(), in.readUTF());
                if (!aliasMatches(alias, records.get(alias.sourceKey))) throw new IOException("Alias source version changed");
                aliases.put(alias.key, alias);
            } catch (IOException | RuntimeException corrupt) { Files.deleteIfExists(file.toPath()); }
        }
        for (File file : files) if ((file.getName().endsWith(".part") || file.getName().endsWith(".bin")) && !referenced.contains(file.getName())) Files.deleteIfExists(file.toPath());
    }
    private static final class LimitedInputStream extends FilterInputStream {
        private long remaining;
        LimitedInputStream(InputStream input, long length) { super(input); remaining = length; }
        @Override public int read() throws IOException {
            if (remaining == 0) return -1;
            int value = super.read(); if (value == -1) throw new EOFException("Missing cache bytes"); remaining--; return value;
        }
        @Override public int read(byte[] buffer, int offset, int count) throws IOException {
            if (count == 0) return 0;
            if (remaining == 0) return -1;
            int read = in.read(buffer, offset, (int) Math.min(count, remaining));
            if (read == -1) throw new EOFException("Missing cache bytes"); remaining -= read; return read;
        }
    }
}
