package com.ethan.lexi;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Native device checks: touch real views, persist on-device, and restore the original wordbook. */
public final class DictionaryInstrumentation extends Instrumentation {
    private MainActivity activity;
    private File evidence;
    private String evidenceDirectory;
    private int checks;
    private long initialOpenMs;
    private final JSONArray results = new JSONArray();
    private final JSONArray screenshots = new JSONArray();
    private final JSONArray exportFiles = new JSONArray();
    private final Map<String, byte[]> originalDatabase = new LinkedHashMap<>();
    private final JSONObject timing = new JSONObject();
    private boolean snapshotTaken;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        evidenceDirectory = arguments == null ? null : arguments.getString("evidence_dir");
        if (evidenceDirectory == null || !evidenceDirectory.matches("device-tests/[a-zA-Z0-9-]+")) {
            evidenceDirectory = "device-tests/run-" + System.currentTimeMillis();
        }
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            evidence = new File(getTargetContext().getFilesDir(), evidenceDirectory);
            if (evidence.exists() || !evidence.mkdirs()) throw new AssertionError("Evidence directory must be new");
            snapshotWordbook();
            int storageChecks = WordbookChecks.run(getTargetContext());
            checks += storageChecks;
            results.put("Isolated wordbook, history, escaping, backup validation and roundtrip: " + storageChecks + " checks");
            long began = SystemClock.elapsedRealtime();
            launch(new Intent(getTargetContext(), MainActivity.class));
            initialOpenMs = SystemClock.elapsedRealtime() - began;
            timing.put("firstActivityReadyMs", initialOpenMs);
            expect(read(() -> activity.ready), "Offline dictionary becomes ready on the Samsung phone");
            screenshot("01-search-home.png");
            dictionaryChecks();
            wordbookUiChecks();
            shortcutChecks();
            tapText("设置");
            await(() -> !imeVisible(), 3000, "Leaving quick search hides the on-screen keyboard");
            screenshot("07-offline-settings.png");
            expect(read(() -> "settings".equals(activity.screen)), "Offline library and export settings open");
            restoreWordbook();
            expect(snapshotMatches(), "Original user wordbook and history restored byte-for-byte after acceptance");
            JSONObject report = new JSONObject().put("passed", true).put("checks", checks)
                    .put("results", results).put("screenshots", screenshots).put("exportFiles", exportFiles)
                    .put("timing", timing).put("userDataRestored", true)
                    .put("method", "Native instrumented Activity, real touchscreen taps, actual offline SQLite queries and exports. Backend edge cases run in isolated databases. Production wordbook files are snapshotted and restored exactly.");
            write("device-checks.json", report.toString(2));
            result.putString("stream", "PASS: " + checks + " device checks\n");
            result.putInt("checks", checks);
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            String details = android.util.Log.getStackTraceString(failure);
            try { restoreWordbook(); } catch (Throwable restoreFailure) {
                details += "\nRESTORE FAILED:\n" + android.util.Log.getStackTraceString(restoreFailure);
            }
            try { write("device-failure.txt", details); } catch (Exception ignored) { }
            result.putString("stream", "FAIL: " + details);
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void dictionaryChecks() throws Exception {
        android.content.pm.PackageInfo installed = getTargetContext().getPackageManager().getPackageInfo(
                getTargetContext().getPackageName(), android.content.pm.PackageManager.GET_PERMISSIONS);
        List<String> permissions = installed.requestedPermissions == null ? java.util.Collections.emptyList()
                : Arrays.asList(installed.requestedPermissions);
        expect(!permissions.contains("android.permission.INTERNET"), "Installed app requests no Internet permission");
        expect(!permissions.contains("android.permission.MANAGE_EXTERNAL_STORAGE")
                && !permissions.contains("android.permission.WRITE_EXTERNAL_STORAGE"), "Installed app requests no broad storage permission");
        DictionaryRepository dictionary = read(() -> activity.dictionary);
        expect(dictionary.count() == 770_611, "Complete bundled ECDICT library contains 770,611 entries");
        Entry apple = dictionary.exact("apple");
        expect(apple != null && apple.translation.contains("苹果"), "English apple resolves to its Chinese meaning offline");
        expect(dictionary.exact("  APPLE  ") != null, "Exact lookup trims whitespace and ignores letter case");
        expect(dictionary.get(apple.id) != null && dictionary.get(apple.id).word.equals(apple.word), "Details reload by stable SQLite entry ID");
        expect(!dictionary.search("appl", 30).isEmpty(), "Prefix lookup returns offline suggestions");
        expect(dictionary.search("zzzznotarealwordxyz", 30).isEmpty(), "Unknown word returns an empty result without a network request");
        expect(dictionary.search("   ", 30).isEmpty(), "Blank lookup is empty and bounded");
        expect(dictionary.search("%", 30).size() <= 30, "Wildcard-like input remains literal and results stay bounded");
        List<Entry> inflected = dictionary.search("running", 30);
        expect(!inflected.isEmpty(), "Inflected running has an offline result");
        boolean rootFound = false;
        for (Entry entry : inflected) if (entry.word.equalsIgnoreCase("run")) rootFound = true;
        expect(rootFound, "Inflected lookup includes the base word run");
        long[] samples = new long[30];
        String[] queries = {"apple", "book", "dict", "software", "running", "a"};
        for (int i = 0; i < samples.length; i++) {
            long began = SystemClock.elapsedRealtimeNanos();
            List<Entry> found = dictionary.search(queries[i % queries.length], 30);
            samples[i] = (SystemClock.elapsedRealtimeNanos() - began) / 1_000_000;
            expect(!found.isEmpty() && found.size() <= 30, "Indexed query " + queries[i % queries.length] + " returns bounded results");
        }
        Arrays.sort(samples);
        timing.put("searchSamples", samples.length).put("searchP50Ms", samples[15])
                .put("searchP95Ms", samples[28]).put("searchMaxMs", samples[29])
                .put("dictionaryCount", dictionary.count());
        expect(samples[28] < 500, "On-device indexed search p95 is below 500 ms");
    }

    private void wordbookUiChecks() throws Exception {
        DictionaryRepository dictionary = read(() -> activity.dictionary);
        String candidate = null;
        for (String word : new String[]{"apple", "serendipity", "dictionary", "engineer", "banana"}) {
            if (dictionary.exact(word) != null && !read(() -> activity.wordbook.contains(word))) {
                candidate = word; break;
            }
        }
        if (candidate == null) throw new AssertionError("No unused acceptance word available");
        final String word = candidate;
        runOnMainSync(() -> { activity.showSearch(); activity.searchInput.setText("  " + word.toUpperCase(java.util.Locale.ROOT) + "  "); });
        await(() -> containsText(activity.results, word), 5000, "Typed query produces its result card");
        recreateActivity();
        expect(read(() -> activity.searchInput.getText().toString().trim().equalsIgnoreCase(word)), "Typed query survives Activity recreation");
        await(() -> containsText(activity.results, word), 5000, "Restored query rebuilds its offline results");
        screenshot("02-search-results.png");
        tapText(word);
        await(() -> "detail".equals(activity.screen) && activity.currentEntry != null
                && activity.currentEntry.word.equalsIgnoreCase(word), 3000, "Tapping actual result opens matching details");
        expect(read(() -> activity.currentEntry.translation.length() > 0), "Detail renders the dictionary translation");
        tapView(read(() -> activity.saveButton));
        await(() -> activity.wordbook.contains(word), 2000, "Touching save adds the word to the local wordbook");
        screenshot("03-saved-detail.png");
        launch(new Intent(getTargetContext(), MainActivity.class));
        expect(read(() -> activity.wordbook.contains(word)), "Saved word persists after Activity restart");
        runOnMainSync(() -> activity.openEntry(activity.dictionary.exact(word)));
        editNote();
        expect(read(() -> activity.wordbook.get(word).note.equals("验收笔记：offline, \"词屿\"\n第二行")), "Edited Unicode and multiline note persists in SQLite");
        tapText("○ 待学习 · 点击标记已掌握");
        expect(read(() -> activity.wordbook.get(word).learned), "Touching learned toggle persists the mastered state");
        recreateActivity();
        await(() -> "detail".equals(activity.screen) && activity.currentEntry != null
                && activity.currentEntry.word.equalsIgnoreCase(word), 5000, "Detail screen and entry survive Activity recreation");
        expect(read(() -> activity.wordbook.get(word).learned && activity.wordbook.get(word).note.contains("验收笔记")),
                "Recreated detail retains the saved note and mastered state");
        tapText("单词本");
        SystemClock.sleep(200);
        runOnMainSync(() -> activity.searchInput.setText("验收笔记"));
        await(() -> containsText(activity.results, word), 3000, "Wordbook filter finds saved words by note text");
        tapText("待学习");
        await(() -> containsText(activity.results, "这里暂时没有单词"), 3000, "Pending-learning filter excludes the mastered test word");
        tapText("已掌握");
        await(() -> containsText(activity.results, word), 3000, "Mastered filter includes the mastered test word");
        screenshot("04-wordbook.png");
        exportChecks(word);
        tapText(word);
        tapView(read(() -> activity.saveButton));
        accessibilityClick("移出");
        await(() -> !activity.wordbook.contains(word), 2000, "Touching saved button removes the wordbook record");
        runOnMainSync(() -> { activity.showBook(); activity.searchInput.setText(word); });
        await(() -> containsText(activity.results, activity.wordbook.count() == 0 ? "还没有收藏的单词" : "这里暂时没有单词"),
                3000, "Removed word disappears from its completed wordbook filter");
    }

    private void editNote() throws Exception {
        tapText("＋ 添加记忆笔记");
        SystemClock.sleep(200);
        AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
        AccessibilityNodeInfo input = findEditable(root);
        if (input == null) throw new AssertionError("Note dialog has no editable field");
        Bundle value = new Bundle();
        value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "验收笔记：offline, \"词屿\"\n第二行");
        expect(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, value), "Actual note dialog accepts editable text");
        accessibilityClick("保存");
        SystemClock.sleep(150);
    }

    private void exportChecks(String word) throws Exception {
        List<WordbookStore.SavedWord> rows = read(() -> activity.wordbook.list("验收笔记"));
        expect(rows.size() == 1 && rows.get(0).entry.word.equalsIgnoreCase(word), "Export snapshot includes the filtered acceptance word");
        try (FileOutputStream stream = new FileOutputStream(new File(evidence, "wordbook-test.csv"))) { WordbookExport.writeCsv(stream, rows); }
        try (FileOutputStream stream = new FileOutputStream(new File(evidence, "wordbook-test.md"))) { WordbookExport.writeMarkdown(stream, rows); }
        try (FileOutputStream stream = new FileOutputStream(new File(evidence, "wordbook-test.json"))) { WordbookExport.writeJson(stream, rows); }
        for (String extension : new String[]{"csv", "md", "json"}) {
            String name = "wordbook-test." + extension;
            expect(new File(evidence, name).length() > 50, "Offline " + extension + " export produces a nonempty file");
            exportFiles.put(name);
        }
        String name = "lexi-roundtrip-" + System.nanoTime() + ".db";
        try (WordbookStore restored = new WordbookStore(getTargetContext(), name)) {
            byte[] json = Files.readAllBytes(new File(evidence, "wordbook-test.json").toPath());
            expect(WordbookExport.importJson(new ByteArrayInputStream(json), restored) == 1, "JSON export restores into an independent device database");
            WordbookStore.SavedWord copy = restored.get(word);
            expect(copy != null && copy.learned && copy.note.equals(rows.get(0).note)
                    && copy.entry.translation.equals(rows.get(0).entry.translation)
                    && copy.savedAt == rows.get(0).savedAt, "Export/import roundtrip retains meaning, note, learning state and save time");
        } finally { getTargetContext().deleteDatabase(name); }
    }

    private void shortcutChecks() throws Exception {
        expect(resolvesToApp(new Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")), "Installed manifest exposes the Android selected-text action");
        expect(resolvesToApp(new Intent(Intent.ACTION_SEND).setType("text/plain")), "Installed manifest exposes the Android share-text action");
        android.content.pm.ShortcutManager shortcuts = getTargetContext().getSystemService(android.content.pm.ShortcutManager.class);
        expect(shortcuts != null && !shortcuts.getManifestShortcuts().isEmpty(), "Installed app exposes a launcher quick-search shortcut");
        launch(new Intent(getTargetContext(), MainActivity.class).setAction(Intent.ACTION_PROCESS_TEXT)
                .putExtra(Intent.EXTRA_PROCESS_TEXT, "software").putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true).setType("text/plain"));
        await(() -> "detail".equals(activity.screen) && activity.currentEntry != null && activity.currentEntry.word.equalsIgnoreCase("software"),
                5000, "Android selected-text shortcut receives software directly");
        screenshot("05-selected-text.png");
        launch(new Intent(getTargetContext(), MainActivity.class).setAction(Intent.ACTION_SEND)
                .putExtra(Intent.EXTRA_TEXT, "dictionary").setType("text/plain"));
        await(() -> "detail".equals(activity.screen) && activity.currentEntry != null && activity.currentEntry.word.equalsIgnoreCase("dictionary"),
                5000, "Android share-text shortcut receives dictionary directly");
        screenshot("06-shared-text.png");
        launch(new Intent(getTargetContext(), MainActivity.class).setAction("com.ethan.lexi.SEARCH"));
        await(() -> "search".equals(activity.screen) && activity.searchInput != null && activity.searchInput.hasFocus(),
                3000, "Launcher shortcut focuses the real lookup input");
        await(this::imeVisible, 3000, "Launcher shortcut opens the Samsung on-screen keyboard");
        screenshot("08-quick-search.png");
    }

    private boolean imeVisible() {
        android.view.WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
        return insets != null && insets.isVisible(android.view.WindowInsets.Type.ime());
    }

    private boolean resolvesToApp(Intent intent) {
        for (android.content.pm.ResolveInfo resolved : getTargetContext().getPackageManager().queryIntentActivities(intent, 0))
            if (getTargetContext().getPackageName().equals(resolved.activityInfo.packageName)) return true;
        return false;
    }

    private void launch(Intent intent) {
        if (activity != null) {
            MainActivity previous = activity;
            runOnMainSync(previous::finish);
            waitForIdleSync();
        }
        activity = (MainActivity) startActivitySync(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        await(() -> activity.ready, 90_000, "Dictionary startup completes");
        waitForIdleSync();
    }

    private void recreateActivity() {
        ActivityMonitor monitor = addMonitor(MainActivity.class.getName(), null, false);
        MainActivity previous = activity;
        runOnMainSync(previous::recreate);
        Activity replacement = monitor.waitForActivityWithTimeout(10_000);
        removeMonitor(monitor);
        if (!(replacement instanceof MainActivity)) throw new AssertionError("Activity recreation did not produce its replacement");
        activity = (MainActivity) replacement;
        await(() -> activity.ready, 30_000, "Recreated Activity opens its dictionary");
        waitForIdleSync();
    }

    private void snapshotWordbook() throws Exception {
        File main = getTargetContext().getDatabasePath(WordbookStore.DATABASE_NAME);
        for (String suffix : new String[]{"", "-journal", "-wal", "-shm"}) {
            File file = new File(main.getPath() + suffix);
            if (file.exists()) originalDatabase.put(suffix, Files.readAllBytes(file.toPath()));
        }
        snapshotTaken = true;
    }

    private void restoreWordbook() throws Exception {
        if (!snapshotTaken) return;
        if (activity != null) {
            MainActivity previous = activity;
            runOnMainSync(() -> { if (previous.wordbook != null) previous.wordbook.close(); previous.finish(); });
            waitForIdleSync();
            activity = null;
        }
        getTargetContext().deleteDatabase(WordbookStore.DATABASE_NAME);
        File main = getTargetContext().getDatabasePath(WordbookStore.DATABASE_NAME);
        File parent = main.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new AssertionError("Cannot restore database directory");
        for (Map.Entry<String, byte[]> item : originalDatabase.entrySet()) Files.write(new File(main.getPath() + item.getKey()).toPath(), item.getValue());
    }

    private boolean snapshotMatches() throws Exception {
        File main = getTargetContext().getDatabasePath(WordbookStore.DATABASE_NAME);
        for (String suffix : new String[]{"", "-journal", "-wal", "-shm"}) {
            File file = new File(main.getPath() + suffix);
            byte[] expected = originalDatabase.get(suffix);
            if (expected == null ? file.exists() : !file.exists() || !Arrays.equals(expected, Files.readAllBytes(file.toPath()))) return false;
        }
        return true;
    }

    private interface Read<T> { T get(); }
    private <T> T read(Read<T> source) {
        AtomicReference<T> value = new AtomicReference<>();
        runOnMainSync(() -> value.set(source.get()));
        return value.get();
    }
    private void await(Read<Boolean> condition, long timeout, String label) {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (read(condition)) { expect(true, label); return; }
            SystemClock.sleep(40);
        }
        throw new AssertionError(label);
    }
    private void expect(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++; results.put(label);
    }
    private boolean containsText(View root, String text) { return findText(root, text) != null; }
    private View findText(View root, String text) {
        if (root instanceof TextView && ((TextView) root).getText().toString().equalsIgnoreCase(text)) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findText(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }
    private void tapText(String text) {
        View view = read(() -> findClickableText(activity.getWindow().getDecorView(), text));
        if (view == null) throw new AssertionError("Visible control missing: " + text);
        tapView(view);
    }
    private View findClickableText(View root, String text) {
        if (root instanceof TextView && ((TextView) root).getText().toString().equalsIgnoreCase(text)) {
            View node = root;
            while (node != null) {
                if (node.isClickable()) return node;
                node = node.getParent() instanceof View ? (View) node.getParent() : null;
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findClickableText(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }
    private void tapView(View view) {
        runOnMainSync(() -> view.requestRectangleOnScreen(new Rect(0, 0, view.getWidth(), view.getHeight()), true));
        SystemClock.sleep(120);
        Rect bounds = read(() -> { Rect r = new Rect(); view.getGlobalVisibleRect(r); return r; });
        if (bounds.isEmpty()) throw new AssertionError("Touch target is not visible");
        long down = SystemClock.uptimeMillis();
        pointer(down, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY());
        SystemClock.sleep(40);
        pointer(down, MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY());
        waitForIdleSync();
    }
    private void pointer(long down, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        sendPointerSync(event); event.recycle();
    }
    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo root) {
        if (root == null) return null;
        if (root.isEditable()) return root;
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo found = findEditable(root.getChild(i));
            if (found != null) return found;
        }
        return null;
    }
    private void accessibilityClick(String text) {
        long deadline = SystemClock.elapsedRealtime() + 2000;
        while (SystemClock.elapsedRealtime() < deadline) {
            AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            if (root != null) for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(text)) {
                if (text.equals(String.valueOf(node.getText())) && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
            }
            SystemClock.sleep(40);
        }
        throw new AssertionError("Dialog control missing: " + text);
    }
    private void screenshot(String name) throws Exception {
        // View callbacks can finish before SurfaceFlinger presents the new frame.
        // Let two rendered frames and the keyboard transition settle before taking evidence.
        waitForIdleSync();
        SystemClock.sleep(350);
        waitForIdleSync();
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        if (bitmap == null) throw new AssertionError("Screenshot unavailable");
        try (FileOutputStream stream = new FileOutputStream(new File(evidence, name))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); }
        bitmap.recycle(); screenshots.put(name);
    }
    private void write(String name, String text) throws Exception {
        try (FileOutputStream stream = new FileOutputStream(new File(evidence, name))) { stream.write(text.getBytes(StandardCharsets.UTF_8)); }
    }
}
