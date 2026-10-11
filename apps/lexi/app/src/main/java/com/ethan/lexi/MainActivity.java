package com.ethan.lexi;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 拾词：所有词条、查询记录和单词本均在手机本地。系统文件选择器负责导出位置。 */
public final class MainActivity extends Activity {
    static final int BG = 0xfff7f7f2, INK = 0xff20362f, MUTED = 0xff738078;
    static final int GREEN = 0xff316458, PALE = 0xffe5ede5, LINE = 0xffdce2d9;
    DictionaryRepository dictionary;
    WordbookStore wordbook;
    EditText searchInput;
    LinearLayout results;
    Entry currentEntry;
    TextView saveButton;
    volatile boolean ready;
    String screen = "search";
    private LinearLayout root, page, nav;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private int generation, total, bookLimit = 100;
    private String query = "", bookQuery = "", bookFilter = "全部", detailOrigin = "search";
    private String pendingFormat = "csv";
    private boolean destroyed;
    private boolean pendingIntent;
    private Bundle restored;
    private TextView status;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        restored = state;
        if (state != null) {
            query = state.getString("query", "");
            bookQuery = state.getString("bookQuery", "");
            bookFilter = state.getString("bookFilter", "全部");
            detailOrigin = state.getString("origin", "search");
            pendingFormat = state.getString("format", "csv");
        }
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        root = column(); root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            int bottom = Math.max(bars.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom);
            root.setPadding(bars.left, bars.top, bars.right, bottom);
            return WindowInsets.CONSUMED;
        });
        page = column(); root.addView(page, new LinearLayout.LayoutParams(-1, 0, 1));
        nav = row(); root.addView(nav);
        setContentView(root);
        if (android.os.Build.VERSION.SDK_INT >= 33)
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0, this::goBack);
        wordbook = new WordbookStore(this);
        dictionary = new DictionaryRepository(this);
        loading();
        worker.execute(() -> {
            try {
                dictionary.prepare(); total = dictionary.count();
                main.post(() -> {
                    if (destroyed) return;
                    ready = true;
                    String previous = restored == null ? "search" : restored.getString("screen", "search");
                    long id = restored == null ? -1 : restored.getLong("entry", -1);
                    if ("detail".equals(previous) && id >= 0) { screen = "detail"; loadEntry(id); }
                    else if ("book".equals(previous)) showBook();
                    else if ("settings".equals(previous)) showSettings();
                    else showSearch();
                    if (state == null || pendingIntent) { pendingIntent = false; handleIntent(getIntent()); }
                });
            } catch (Exception ex) {
                main.post(() -> { if (!destroyed) { page.removeAllViews(); header("词库未能打开", "请重新打开应用再试"); text(page, "本机存储空间不足或词库文件损坏。\n" + ex.getMessage(), 15, MUTED); button(page, "重新打开", () -> recreate(), true); } });
            }
        });
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        if (ready) handleIntent(intent); else pendingIntent = true;
    }

    private void handleIntent(Intent intent) {
        String action = intent.getAction();
        CharSequence incoming = Intent.ACTION_PROCESS_TEXT.equals(action)
                ? intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
                : Intent.ACTION_SEND.equals(action) ? intent.getCharSequenceExtra(Intent.EXTRA_TEXT) : null;
        if (incoming != null) {
            query = incoming.toString().trim();
            if (query.length() > 256) query = query.substring(0, 256);
            showSearch();
            final String term = query;
            int token = generation;
            queryTask(() -> {
                Entry entry = dictionary.exact(term);
                main.post(() -> { if (!destroyed && token == generation && screen.equals("search") && query.equals(term) && entry != null) openEntry(entry); });
            });
        } else if ("com.ethan.lexi.SEARCH".equals(action)) {
            showSearch(); focusSearch();
        }
    }

    @Override public void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("screen", screen); out.putString("query", query);
        out.putString("bookQuery", bookQuery); out.putString("bookFilter", bookFilter);
        out.putString("origin", detailOrigin); out.putString("format", pendingFormat);
        if (currentEntry != null) { out.putLong("entry", currentEntry.id); out.putString("entryWord", currentEntry.word); }
    }

    @Override public void onBackPressed() { goBack(); }
    private void goBack() {
        if ("detail".equals(screen)) { if ("book".equals(detailOrigin)) showBook(); else showSearch(); }
        else if (!"search".equals(screen)) showSearch();
        else if (!query.isEmpty()) { query = ""; showSearch(); }
        else finish();
    }
    @Override public void onDestroy() {
        destroyed = true; main.removeCallbacksAndMessages(null);
        worker.execute(() -> { dictionary.close(); wordbook.close(); }); worker.shutdown();
        super.onDestroy();
    }

    private void loading() {
        page.setPadding(dp(24), dp(34), dp(24), 0);
        header("拾词", "让每一个新词，都有归处。");
        gap(page, 52); text(page, "正在准备离线词库…", 21, INK);
        text(page, "首次打开需要几秒钟\n完成后，无需联网即可查词", 15, MUTED);
    }

    private void reset(String next) {
        generation++; screen = next; page.removeAllViews(); page.setPadding(dp(22), dp(16), dp(22), 0);
        searchInput = null; saveButton = null;
        navigation();
    }
    private void navigation() {
        nav.removeAllViews(); nav.setPadding(dp(16), dp(8), dp(16), dp(8));
        nav.setBackgroundColor(BG);
        String selected = screen.equals("detail") ? detailOrigin : screen;
        String[] names = {"查词", "单词本", "设置"}; String[] keys = {"search", "book", "settings"};
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            TextView t = label(names[i], 15, selected.equals(keys[i]) ? GREEN : MUTED);
            t.setTypeface(null, selected.equals(keys[i]) ? Typeface.BOLD : Typeface.NORMAL);
            t.setGravity(Gravity.CENTER); t.setMinHeight(dp(48)); t.setBackground(background(selected.equals(keys[i]) ? PALE : BG, 18));
            t.setOnClickListener(v -> { hideKeyboard(); if (index == 0) showSearch(); else if (index == 1) showBook(); else showSettings(); });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(48), 1); p.setMargins(dp(4), 0, dp(4), 0); nav.addView(t, p);
        }
    }

    void showSearch() {
        if (!ready) return;
        reset("search"); header("拾词", "OFFLINE DICTIONARY");
        LinearLayout searchRow = row(); searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setPadding(dp(14), 0, dp(5), 0); searchRow.setBackground(outline(Color.WHITE, 18));
        text(searchRow, "⌕", 27, GREEN).setPadding(0, 0, dp(10), 0);
        searchInput = input("输入英文单词", query); searchInput.setContentDescription("搜索英文单词");
        searchInput.setBackgroundColor(Color.TRANSPARENT); searchInput.setSingleLine(true);
        searchInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchRow.addView(searchInput, new LinearLayout.LayoutParams(0, dp(56), 1));
        TextView clear = label("×", 24, MUTED); clear.setGravity(Gravity.CENTER); clear.setContentDescription("清空搜索");
        searchRow.addView(clear, new LinearLayout.LayoutParams(dp(44), dp(48)));
        clear.setOnClickListener(v -> searchInput.setText("")); page.addView(searchRow);
        LinearLayout helper = row(); helper.setGravity(Gravity.CENTER_VERTICAL);
        TextView offline = label(String.format(Locale.CHINA, "● 离线可用 · %,d 词条", total), 12, GREEN);
        helper.addView(offline, new LinearLayout.LayoutParams(0, dp(43), 1));
        TextView paste = label("粘贴查词", 13, GREEN); paste.setGravity(Gravity.CENTER); helper.addView(paste, new LinearLayout.LayoutParams(dp(76), dp(43)));
        paste.setOnClickListener(v -> pasteQuery()); page.addView(helper);
        ScrollView scroll = scroller(page); results = column(); scroll.addView(results);
        searchInput.addTextChangedListener(watcher(() -> { query = searchInput.getText().toString(); scheduleSearch(); }));
        searchInput.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard(); lookupFirst(); return true;
            } return false;
        });
        scheduleSearch();
    }

    private void focusSearch() {
        searchInput.requestFocus(); searchInput.postDelayed(() -> {
            if (searchInput != null && !destroyed) ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT);
        }, 200);
    }
    private void pasteQuery() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (!clipboard.hasPrimaryClip() || clipboard.getPrimaryClip() == null || clipboard.getPrimaryClip().getItemCount() == 0) { toast("剪贴板暂无文字"); return; }
        CharSequence content = clipboard.getPrimaryClip().getItemAt(0).coerceToText(this);
        String value = content == null ? "" : content.toString().trim();
        if (value.length() > 256) value = value.substring(0, 256);
        searchInput.setText(value); searchInput.setSelection(value.length()); hideKeyboard();
    }
    private void scheduleSearch() {
        int token = ++generation; String term = query.trim();
        if (term.isEmpty()) { home(); return; }
        results.removeAllViews(); status = text(results, "查找中…", 13, MUTED);
        main.postDelayed(() -> {
            if (destroyed || token != generation || !screen.equals("search")) return;
            queryTask(() -> {
                long start = android.os.SystemClock.elapsedRealtime();
                List<Entry> found = dictionary.search(term, 40);
                long elapsed = android.os.SystemClock.elapsedRealtime() - start;
                main.post(() -> {
                    if (destroyed || token != generation || !screen.equals("search")) return;
                    results.removeAllViews();
                    if (found.isEmpty()) {
                        gap(results, 28); text(results, "没有找到这个词", 23, INK);
                        text(results, "检查拼写，或试试单词原形。\n当前词库以英汉查词为主。", 15, MUTED);
                    } else {
                        text(results, "搜索结果 · " + found.size() + (found.size() == 40 ? "+" : "") + "  /  " + elapsed + " ms", 12, MUTED);
                        gap(results, 8); for (Entry entry : found) entryRow(results, entry, null);
                    }
                });
            });
        }, 140);
    }
    private void lookupFirst() {
        final String term = query.trim(); final int token = generation;
        if (term.isEmpty()) return;
        queryTask(() -> {
            List<Entry> found = dictionary.search(term, 1);
            main.post(() -> { if (!destroyed && token == generation && screen.equals("search") && !found.isEmpty()) openEntry(found.get(0)); });
        });
    }
    private void home() {
        results.removeAllViews();
        LinearLayout hero = card(PALE); text(hero, "A WORD TO KEEP", 11, GREEN);
        TextView word = text(hero, "serendipity", 33, INK); word.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        text(hero, "不期而遇的美好", 16, GREEN); gap(hero, 16);
        text(hero, "查一个词，收获一种表达。     ↗", 13, GREEN);
        hero.setContentDescription("查看 serendipity"); hero.setOnClickListener(v -> lookupWord("serendipity")); results.addView(hero);
        gap(results, 24); text(results, "最近查词", 16, INK); gap(results, 8);
        List<String> history = wordbook.recentHistory(8);
        if (history.isEmpty()) {
            text(results, "查过的单词会出现在这里", 13, MUTED);
            gap(results, 24); text(results, "试着搜一搜", 16, INK); gap(results, 8);
            for (String s : new String[]{"hello", "resilient", "sustainable"}) {
                TextView t = button(results, s + "  ↗", () -> lookupWord(s), false); t.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            }
        } else for (String s : history) {
            TextView t = button(results, s + "  ↗", () -> lookupWord(s), false); t.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        }
        gap(results, 20); text(results, "长按桌面图标 → 快捷查词\n在支持的应用中选中文字 → 拾词", 12, MUTED); gap(results, 20);
    }
    private void lookupWord(String term) {
        int token = generation;
        queryTask(() -> {
            Entry entry = dictionary.exact(term);
            main.post(() -> { if (!destroyed && token == generation && entry != null) openEntry(entry); });
        });
    }
    private void loadEntry(long id) {
        int token = generation;
        String word = restored == null ? "" : restored.getString("entryWord", "");
        queryTask(() -> {
            WordbookStore.SavedWord saved = detailOrigin.equals("book") ? wordbook.get(word) : null;
            Entry e = saved != null ? saved.entry : !word.isEmpty() ? dictionary.exact(word) : dictionary.get(id);
            main.post(() -> { if (destroyed || token != generation) return; if (e == null) showSearch(); else openEntry(e); });
        });
    }
    private void queryTask(Runnable task) {
        int token = generation;
        worker.execute(() -> {
            try { task.run(); }
            catch (Exception e) { main.post(() -> { if (!destroyed && token == generation) error("读取失败，请重新打开应用再试", e); }); }
        });
    }

    void openEntry(Entry entry) {
        if (!screen.equals("detail")) detailOrigin = screen.equals("book") ? "book" : "search";
        hideKeyboard(); currentEntry = entry; wordbook.recordLookup(entry.word);
        reset("detail");
        LinearLayout top = row();
        TextView back = label("‹  返回", 15, GREEN); back.setMinHeight(dp(48)); back.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(back, new LinearLayout.LayoutParams(0, dp(48), 1)); back.setOnClickListener(v -> goBack());
        TextView copy = label("复制释义", 13, GREEN); copy.setGravity(Gravity.CENTER); top.addView(copy, new LinearLayout.LayoutParams(dp(80), dp(48)));
        copy.setOnClickListener(v -> { ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText(entry.word, entry.word + "\n" + entry.translation)); toast("已复制"); });
        page.addView(top);
        ScrollView scroll = scroller(page); LinearLayout detail = column(); scroll.addView(detail);
        gap(detail, 15); TextView word = text(detail, entry.word, 38, INK); word.setTypeface(Typeface.create("serif", Typeface.NORMAL)); word.setTextIsSelectable(true);
        if (!entry.phonetic.isEmpty()) { gap(detail, 8); text(detail, "/ " + entry.phonetic + " /", 17, MUTED).setTextIsSelectable(true); }
        if (!entry.tags.isEmpty()) { gap(detail, 14); text(detail, translateTags(entry.tags), 12, GREEN); }
        gap(detail, 24);
        LinearLayout meaning = card(Color.WHITE); text(meaning, "中文释义", 12, MUTED); gap(meaning, 10);
        text(meaning, entry.translation.isEmpty() ? "词库暂无中文释义" : entry.translation, 20, INK).setTextIsSelectable(true); detail.addView(meaning);
        if (!entry.definition.isEmpty()) {
            gap(detail, 18); text(detail, "ENGLISH DEFINITION", 11, GREEN); gap(detail, 10);
            text(detail, entry.definition, 16, INK).setTextIsSelectable(true);
        }
        if (!entry.exchange.isEmpty()) {
            gap(detail, 24); text(detail, "词形变化", 13, MUTED); gap(detail, 8);
            for (String item : entry.exchange.split("/")) {
                int colon = item.indexOf(':'); if (colon < 0) continue;
                String key = item.substring(0, colon), value = item.substring(colon + 1);
                String title = switch (key) { case "p" -> "过去式"; case "d" -> "过去分词"; case "i" -> "现在分词"; case "3" -> "第三人称单数"; case "s" -> "复数"; case "r" -> "比较级"; case "t" -> "最高级"; case "0" -> "原形"; default -> ""; };
                if (title.isEmpty()) continue;
                TextView t = button(detail, title + "   " + value, () -> lookupWord(value), false); t.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            }
        }
        WordbookStore.SavedWord saved = wordbook.get(entry.word);
        if (saved != null) {
            gap(detail, 22); text(detail, "我的笔记", 13, MUTED);
            button(detail, saved.note.isEmpty() ? "＋ 添加记忆笔记" : saved.note, () -> editNote(entry.word), false);
            button(detail, saved.learned ? "✓ 已掌握 · 点击改为待学习" : "○ 待学习 · 点击标记已掌握", () -> { wordbook.setLearned(entry.word, !saved.learned); openEntry(entry); }, false);
        }
        gap(detail, 25); text(detail, detailOrigin.equals("book") ? "释义来源 · 本机单词本快照" : "释义来源 · ECDICT 开源词库", 11, MUTED); gap(detail, 20);
        saveButton = button(page, saved == null ? "＋ 加入单词本" : "✓ 已加入单词本", () -> {
            if (wordbook.contains(entry.word)) {
                new AlertDialog.Builder(this).setTitle("移出单词本？").setMessage("“" + entry.word + "”的笔记和学习状态也会一并移除。")
                    .setNegativeButton("保留", null).setPositiveButton("移出", (d, w) -> { wordbook.remove(entry.word); openEntry(entry); }).show();
            } else { wordbook.save(entry); toast("已加入单词本"); openEntry(entry); }
        }, true);
    }
    private void editNote(String word) {
        WordbookStore.SavedWord saved = wordbook.get(word); if (saved == null) return;
        EditText note = input("写下你的例句或记忆方法", saved.note); note.setMinLines(3); note.setMaxLines(8); note.setGravity(Gravity.TOP);
        note.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(WordbookExport.MAX_FIELD_CHARS)});
        LinearLayout content = column(); content.setPadding(dp(24), dp(12), dp(24), dp(12)); content.addView(note);
        new AlertDialog.Builder(this).setTitle("记忆笔记").setView(content).setNegativeButton("取消", null)
            .setPositiveButton("保存", (d, w) -> { wordbook.setNote(word, note.getText().toString()); openEntry(currentEntry); }).show();
    }

    void showBook() {
        if (!ready) return;
        reset("book"); hideKeyboard(); header("单词本", "把遇见的词，变成自己的词。");
        LinearLayout actions = row();
        TextView summary = label("共 " + wordbook.count() + " 个单词", 13, MUTED); actions.addView(summary, new LinearLayout.LayoutParams(0, dp(48), 1)); summary.setGravity(Gravity.CENTER_VERTICAL);
        TextView export = label("导出全部 ↗", 14, GREEN); export.setGravity(Gravity.CENTER); actions.addView(export, new LinearLayout.LayoutParams(dp(100), dp(48))); export.setOnClickListener(v -> chooseExport()); page.addView(actions);
        EditText filter = input("搜索单词、释义或笔记", bookQuery); searchInput = filter; filter.setSingleLine(true); filter.setBackground(outline(Color.WHITE, 14)); filter.setPadding(dp(14), dp(10), dp(14), dp(10)); page.addView(filter, new LinearLayout.LayoutParams(-1, dp(50)));
        LinearLayout chips = row(); chips.setPadding(0, dp(12), 0, dp(8)); page.addView(chips);
        for (String name : new String[]{"全部", "待学习", "已掌握"}) {
            TextView t = label(name, 13, bookFilter.equals(name) ? Color.WHITE : GREEN); t.setGravity(Gravity.CENTER); t.setBackground(background(bookFilter.equals(name) ? GREEN : PALE, 14));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(40), 1); p.setMargins(dp(3), 0, dp(3), 0); chips.addView(t, p);
            t.setOnClickListener(v -> { bookFilter = name; bookLimit = 100; showBook(); });
        }
        ScrollView scroll = scroller(page); results = column(); scroll.addView(results);
        filter.addTextChangedListener(watcher(() -> { bookQuery = filter.getText().toString(); bookLimit = 100; scheduleBook(); })); scheduleBook();
    }
    private void scheduleBook() {
        int token = ++generation; String term = bookQuery, selection = bookFilter;
        queryTask(() -> {
            List<WordbookStore.SavedWord> all = wordbook.list(term), selected = new ArrayList<>();
            for (WordbookStore.SavedWord w : all) if (selection.equals("全部") || selection.equals(w.learned ? "已掌握" : "待学习")) selected.add(w);
            main.post(() -> {
                if (destroyed || token != generation || !screen.equals("book")) return;
                results.removeAllViews();
                if (selected.isEmpty()) { gap(results, 30); text(results, wordbook.count() == 0 ? "还没有收藏的单词" : "这里暂时没有单词", 21, INK); text(results, "查词时点击“加入单词本”，\n随时记笔记、标记掌握和离线导出。", 14, MUTED); }
                else {
                    text(results, selected.size() + " 个单词 · 最近收藏优先", 12, MUTED); gap(results, 8);
                    for (int i = 0; i < Math.min(bookLimit, selected.size()); i++) { WordbookStore.SavedWord w = selected.get(i); entryRow(results, w.entry, w.learned ? "已掌握" : "待学习"); }
                    if (selected.size() > bookLimit) button(results, "显示更多", () -> { bookLimit += 100; scheduleBook(); }, false);
                }
                gap(results, 20);
            });
        });
    }
    private void entryRow(LinearLayout parent, Entry entry, String badge) {
        LinearLayout box = card(Color.WHITE); box.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout title = row(); TextView word = label(entry.word, 21, INK); word.setTypeface(null, Typeface.BOLD);
        title.addView(word, new LinearLayout.LayoutParams(0, -2, 1));
        if (badge != null) text(title, badge, 11, GREEN); else text(title, "↗", 20, MUTED);
        box.addView(title); gap(box, 5);
        TextView trans = text(box, entry.translation.isEmpty() ? entry.definition : entry.translation, 14, MUTED); trans.setMaxLines(2); trans.setEllipsize(android.text.TextUtils.TruncateAt.END);
        box.setOnClickListener(v -> openEntry(entry)); box.setContentDescription("查看 " + entry.word);
        parent.addView(box, new LinearLayout.LayoutParams(-1, -2)); gap(parent, 9);
    }

    void showSettings() {
        if (!ready) return;
        reset("settings"); hideKeyboard(); header("离线，也自在", "设置与数据");
        ScrollView scroll = scroller(page); LinearLayout content = column(); scroll.addView(content);
        LinearLayout intro = card(PALE); text(intro, "词库已在这台手机", 22, INK); gap(intro, 10);
        text(intro, String.format(Locale.CHINA, "ECDICT · %,d 个英汉词条\n查词、单词本、导出，全程离线。", total), 14, GREEN); content.addView(intro); gap(content, 20);
        text(content, "我的数据", 14, MUTED); gap(content, 6);
        button(content, "导出单词本", this::chooseExport, false);
        button(content, "从 JSON 备份恢复", () -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(i, 502);
        }, false);
        button(content, "清空最近查词", () -> new AlertDialog.Builder(this).setTitle("清空最近查词？").setMessage("单词本和笔记会保留。")
                .setNegativeButton("取消", null).setPositiveButton("清空", (d, w) -> { wordbook.clearHistory(); toast("最近查词已清空"); }).show(), false);
        gap(content, 22); text(content, "快捷查词", 14, MUTED); gap(content, 10);
        text(content, "1. 长按拾词桌面图标，选择“快捷查词”。\n2. 复制单词后，点击首页“粘贴查词”。\n3. 在支持文字选择的应用中选中单词，选择“拾词”；也可用系统分享发送到拾词。", 14, INK);
        gap(content, 24); text(content, "关于拾词  1.0.2", 14, MUTED);
        text(content, "词库按上游原文呈现，部分专业词和短语可能没有中文释义。应用无需网络、联系人或存储权限。卸载会移除本机单词本，换机前请先导出 JSON 备份。", 13, MUTED);
        button(content, "开源词库与许可证", this::showLicense, false); gap(content, 24);
    }
    private void showLicense() {
        worker.execute(() -> {
            String license;
            try (InputStream in = getAssets().open("licenses/ECDICT-MIT.txt"); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                license = out.toString(StandardCharsets.UTF_8.name());
            }
            catch (Exception e) { license = "MIT License · Copyright (c) 2025 Linwei"; }
            String body = "ECDICT\nhttps://github.com/skywind3000/ECDICT\n\n本应用使用 ECDICT 的基础 CSV 词库。\n\n" + license;
            main.post(() -> { if (!destroyed) new AlertDialog.Builder(this).setTitle("开源词库").setMessage(body).setPositiveButton("完成", null).show(); });
        });
    }
    private void chooseExport() {
        if (wordbook.count() == 0) { toast("先收藏一些单词，再导出吧"); return; }
        new AlertDialog.Builder(this).setTitle("导出全部 " + wordbook.count() + " 个单词")
            .setItems(new String[]{"CSV · 用 Excel 或表格打开", "Markdown · 阅读和打印", "JSON · 完整备份，可恢复"}, (d, n) -> exportBook(new String[]{"csv", "md", "json"}[n])).show();
    }
    void exportBook(String format) {
        pendingFormat = format;
        String mime = format.equals("csv") ? "text/csv" : format.equals("md") ? "text/markdown" : "application/json";
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime);
        intent.putExtra(Intent.EXTRA_TITLE, "拾词单词本-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + "." + format);
        startActivityForResult(intent, 501);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        android.net.Uri uri = data.getData();
        if (request == 501) {
            String format = pendingFormat; toast("正在导出…");
            worker.execute(() -> {
                int exported;
                try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                    if (out == null) throw new java.io.IOException("无法写入所选位置");
                    List<WordbookStore.SavedWord> words = wordbook.list("");
                    if (format.equals("csv")) WordbookExport.writeCsv(out, words);
                    else if (format.equals("md")) WordbookExport.writeMarkdown(out, words);
                    else WordbookExport.writeJson(out, words);
                    exported = words.size();
                } catch (Exception e) { main.post(() -> { if (!destroyed) error("导出失败", e); }); return; }
                main.post(() -> { if (!destroyed) toast("已导出 " + exported + " 个单词"); });
            });
        } else if (request == 502) new AlertDialog.Builder(this).setTitle("恢复单词本备份？")
            .setMessage("将合并 JSON 备份中的单词。重复单词的笔记和学习状态将使用备份中的内容。")
            .setNegativeButton("取消", null).setPositiveButton("恢复", (d, w) -> worker.execute(() -> {
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new java.io.IOException("无法读取所选文件");
                    int added = WordbookExport.importJson(in, wordbook);
                    main.post(() -> { if (!destroyed) { showBook(); toast("恢复完成，新增 " + added + " 个单词"); } });
                } catch (Exception e) { main.post(() -> { if (!destroyed) error("恢复失败", e); }); }
            })).show();
    }
    private void error(String title, Exception e) { new AlertDialog.Builder(this).setTitle(title).setMessage(e.getMessage()).setPositiveButton("知道了", null).show(); }

    private static String translateTags(String tags) {
        List<String> labels = new ArrayList<>();
        for (String tag : tags.split(" ")) labels.add(switch (tag) { case "zk" -> "中考"; case "gk" -> "高考"; case "cet4" -> "四级"; case "cet6" -> "六级"; case "ky" -> "考研"; case "toefl" -> "托福"; case "ielts" -> "雅思"; case "gre" -> "GRE"; default -> tag; });
        return String.join("  ·  ", labels);
    }
    private void hideKeyboard() { View v = getCurrentFocus(); if (v != null) { ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(v.getWindowToken(), 0); v.clearFocus(); } }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    private TextView label(String value, int sp, int color) { TextView t = new TextView(this); t.setText(value); t.setTextSize(sp); t.setTextColor(color); t.setLineSpacing(dp(3), 1); return t; }
    private TextView text(LinearLayout parent, String value, int sp, int color) { TextView t = label(value, sp, color); parent.addView(t); return t; }
    private void gap(LinearLayout parent, int height) { View v = new View(this); parent.addView(v, new LinearLayout.LayoutParams(1, dp(height))); }
    private GradientDrawable background(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private GradientDrawable outline(int color, int radius) { GradientDrawable d = background(color, radius); d.setStroke(dp(1), LINE); return d; }
    private LinearLayout card(int color) { LinearLayout l = column(); l.setBackground(background(color, 18)); l.setPadding(dp(20), dp(20), dp(20), dp(20)); return l; }
    private ScrollView scroller(LinearLayout parent) { ScrollView s = new ScrollView(this); s.setFillViewport(false); s.setClipToPadding(false); s.setVerticalScrollBarEnabled(false); parent.addView(s, new LinearLayout.LayoutParams(-1, 0, 1)); return s; }
    private EditText input(String hint, String value) { EditText t = new EditText(this); t.setTextSize(17); t.setTextColor(INK); t.setHintTextColor(MUTED); t.setHint(hint); t.setText(value); t.setSelectAllOnFocus(false); t.setPadding(0, dp(8), 0, dp(8)); t.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(256)}); return t; }
    private TextView button(LinearLayout parent, String title, Runnable action, boolean primary) { TextView t = label(title, 15, primary ? Color.WHITE : GREEN); t.setGravity(Gravity.CENTER); t.setMinHeight(dp(50)); t.setPadding(dp(16), dp(12), dp(16), dp(12)); t.setBackground(background(primary ? GREEN : PALE, 14)); t.setFocusable(true); t.setOnClickListener(v -> action.run()); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, dp(5), 0, dp(5)); parent.addView(t, p); return t; }
    private void header(String title, String subtitle) { text(page, title, 30, INK).setTypeface(null, Typeface.BOLD); gap(page, 5); text(page, subtitle, 12, MUTED); gap(page, 22); }
    private TextWatcher watcher(Runnable action) { return new TextWatcher() { public void beforeTextChanged(CharSequence s, int start, int count, int after) {} public void onTextChanged(CharSequence s, int start, int before, int count) {} public void afterTextChanged(Editable e) { action.run(); } }; }
}
