package com.ethan.lexi;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public final class WordbookExportTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void recordLimitNeverOpensOrTruncatesExistingBackup() throws Exception {
        assertRejectedWithoutOpening(Collections.nCopies(WordbookExport.MAX_IMPORT_RECORDS + 1, word()), "50000");
    }

    @Test public void utf8ByteLimitNeverOpensOrTruncatesExistingBackup() throws Exception {
        WordbookStore.SavedWord saved = word();
        // Each note is legal, but their combined UTF-8 size exceeds a single JSON backup.
        saved.note = String.join("", Collections.nCopies(64_000, "词"));
        assertRejectedWithoutOpening(Collections.nCopies(180, saved), "32 MiB");
    }

    @Test public void allFormatsReplaceDestinationOnlyAfterPreparation() throws Exception {
        for (String format : new String[]{"json", "csv", "md"}) {
            File cache = temporary.newFolder("cache-" + format);
            File target = temporary.newFile("backup." + format);
            Files.write(target.toPath(), "previous backup".getBytes(StandardCharsets.UTF_8));
            AtomicInteger opened = new AtomicInteger();
            WordbookExport.export(cache, format, List.of(word()), () -> {
                File[] staged = cache.listFiles();
                assertNotNull(staged);
                assertEquals(1, staged.length);
                assertTrue(staged[0].length() > 0);
                assertEquals("previous backup", new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
                opened.incrementAndGet();
                return new FileOutputStream(target);
            });
            assertEquals(1, opened.get());
            String result = new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8);
            assertTrue(result.contains("苹果"));
            if (format.equals("json")) {
                JSONObject root = new JSONObject(result);
                assertEquals("lexi-wordbook", root.getString("format"));
                assertEquals(1, root.getInt("version"));
                assertEquals("笔记\n\"中文\"", root.getJSONArray("entries").getJSONObject(0).getString("note"));
            }
            assertEmpty(cache);
        }
    }

    @Test public void destinationOpenFailureCleansStagingFile() throws Exception {
        File cache = temporary.newFolder();
        try {
            WordbookExport.export(cache, "json", List.of(word()), () -> { throw new IOException("provider refused"); });
            fail("expected provider failure");
        } catch (IOException expected) { assertEquals("provider refused", expected.getMessage()); }
        assertEmpty(cache);
    }

    @Test public void destinationWriteFailureClosesStreamAndCleansStagingFile() throws Exception {
        File cache = temporary.newFolder();
        AtomicInteger closed = new AtomicInteger();
        try {
            WordbookExport.export(cache, "json", List.of(word()), () -> new OutputStream() {
                @Override public void write(int value) throws IOException { throw new IOException("storage full"); }
                @Override public void close() { closed.incrementAndGet(); }
            });
            fail("expected storage failure");
        } catch (IOException expected) { assertEquals("storage full", expected.getMessage()); }
        assertEquals(1, closed.get());
        assertEmpty(cache);
    }

    @Test public void nullDestinationCleansStagingFile() throws Exception {
        File cache = temporary.newFolder();
        try {
            WordbookExport.export(cache, "json", List.of(word()), () -> null);
            fail("expected missing destination failure");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("无法写入")); }
        assertEmpty(cache);
    }

    private void assertRejectedWithoutOpening(List<WordbookStore.SavedWord> words, String message) throws Exception {
        File cache = temporary.newFolder();
        File target = temporary.newFile();
        byte[] original = "previous backup 苹果".getBytes(StandardCharsets.UTF_8);
        Files.write(target.toPath(), original);
        AtomicInteger opened = new AtomicInteger();
        try {
            WordbookExport.export(cache, "json", words, () -> {
                opened.incrementAndGet();
                return new FileOutputStream(target);
            });
            fail("expected export limit failure");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains(message)); }
        assertEquals(0, opened.get());
        assertArrayEquals(original, Files.readAllBytes(target.toPath()));
        assertEmpty(cache);
    }

    private static WordbookStore.SavedWord word() {
        WordbookStore.SavedWord saved = new WordbookStore.SavedWord();
        saved.entry.word = "apple";
        saved.entry.translation = "苹果";
        saved.note = "笔记\n\"中文\"";
        return saved;
    }

    private static void assertEmpty(File directory) {
        File[] remaining = directory.listFiles();
        assertNotNull(remaining);
        assertEquals(0, remaining.length);
    }
}
