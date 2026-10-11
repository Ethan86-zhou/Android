package com.zhou.musiccache.player;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** 只呈现界面并发出用户操作；账号、队列与播放生命周期由 Activity/Service 管理。公开方法在主线程调用。 */
public final class PlayerScreen extends LinearLayout {
    public interface Listener {
        void onSearch(String query);
        void onFavorites();
        void onPlaylists();
        void onPlaylist(Playlist playlist);
        void onLogin();
        void onSelect(Track track, List<Track> tracks);
        void onQueue();
        void onPlayPause();
        void onPrevious();
        void onNext();
        void onSeek(long positionMs);
        void onCache();
        void onRemoveFromQueue(int index);
    }

    private static final int PAPER = Color.rgb(247, 248, 241);
    private static final int INK = Color.rgb(33, 55, 44);
    private static final int MUTED = Color.rgb(105, 120, 109);
    private static final int GREEN = Color.rgb(30, 104, 75);
    private static final int PALE = Color.rgb(231, 239, 225);
    private static final int LINE = Color.rgb(221, 230, 217);
    private static final int SEARCH = 1, FAVORITES = 2, QUEUE = 3, PLAYLISTS = 4, PLAYLIST = 5;

    private final Listener listener;
    private final EditText searchInput;
    private final Button searchButton, favoritesButton, playlistsButton, queueButton, playButton, previousButton, nextButton;
    private final TextView sourceStatus, sectionTitle, sectionCount, busyLabel;
    private final TextView playingTitle, playingArtist, elapsed, duration, playerQueue;
    private final LinearLayout busyRow, seekSection;
    private final SeekBar seekBar;
    private final ListView list;
    private final ScrollView emptyScroll;
    private final TextView emptyTitle, emptyMessage;
    private final Button retryButton;
    private final TrackAdapter adapter = new TrackAdapter();
    private final PlaylistAdapter playlistAdapter = new PlaylistAdapter();
    private List<Track> tracks = Collections.emptyList();
    private List<Playlist> playlists = Collections.emptyList();
    private Playlist selectedPlaylist;
    private boolean showingQueue, dragging;
    private int lastAction, currentQueueIndex = -1;
    private long playbackDuration, playbackPosition, seekStartDuration;
    private String lastSearch = "", currentTitle = "", currentArtist = "", seekStartTitle = "";

    public PlayerScreen(Context context, Listener listener) {
        super(context);
        this.listener = Objects.requireNonNull(listener, "listener");
        setOrientation(VERTICAL);
        setBackgroundColor(PAPER);
        setFocusableInTouchMode(true);
        setClipToPadding(true);

        LinearLayout header = horizontal();
        header.setPadding(dp(20), dp(12), dp(16), dp(6));
        LinearLayout brand = vertical();
        TextView brandName = label("轻听", 27, INK, true);
        brand.addView(brandName);
        brand.addView(label("把喜欢，留在耳边", 12, MUTED, false));
        header.addView(brand, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        Button more = button("更多", false, view -> showMore(view));
        more.setContentDescription("更多：登录与缓存管理");
        header.addView(more, new LayoutParams(dp(64), dp(48)));
        addView(header);

        sourceStatus = label("登录 QQ 音乐，听你喜欢的歌", 12, MUTED, false);
        sourceStatus.setSingleLine(true);
        sourceStatus.setEllipsize(TextUtils.TruncateAt.END);
        sourceStatus.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        addView(sourceStatus, marginParams(LayoutParams.MATCH_PARENT, dp(28), 20, 0, 20, 8));

        LinearLayout search = horizontal();
        searchInput = new EditText(context);
        searchInput.setTextSize(15);
        searchInput.setTextColor(INK);
        searchInput.setHintTextColor(MUTED);
        searchInput.setHint("搜索歌曲、歌手");
        searchInput.setContentDescription("搜索歌曲或歌手");
        searchInput.setSingleLine(true);
        searchInput.setInputType(InputType.TYPE_CLASS_TEXT);
        searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchInput.setPadding(dp(15), 0, dp(10), 0);
        searchInput.setBackground(shape(Color.WHITE, 16, LINE));
        searchInput.setOnEditorActionListener((view, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH || (event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_UP)) {
                submitSearch();
                return true;
            }
            return false;
        });
        search.addView(searchInput, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1));
        searchButton = button("搜索", true, view -> submitSearch());
        search.addView(searchButton, marginParams(dp(72), LayoutParams.MATCH_PARENT, 8, 0, 0, 0));
        addView(search, marginParams(LayoutParams.MATCH_PARENT, dp(54), 18, 0, 18, 0));

        LinearLayout navigation = horizontal();
        favoritesButton = button("我的喜欢", false, view -> {
            lastAction = FAVORITES; showingQueue = false; updateNavigation(); dismissKeyboard(); listener.onFavorites();
        });
        playlistsButton = button("我的歌单", false, view -> {
            lastAction = PLAYLISTS; showingQueue = false; updateNavigation(); dismissKeyboard(); listener.onPlaylists();
        });
        queueButton = button("播放队列", false, view -> {
            lastAction = QUEUE; showingQueue = true; updateNavigation(); dismissKeyboard(); listener.onQueue();
        });
        LayoutParams favoriteParams = new LayoutParams(0, dp(48), 1);
        favoriteParams.rightMargin = dp(6);
        navigation.addView(favoritesButton, favoriteParams);
        LayoutParams playlistParams = new LayoutParams(0, dp(48), 1);
        playlistParams.rightMargin = dp(6);
        navigation.addView(playlistsButton, playlistParams);
        navigation.addView(queueButton, new LayoutParams(0, dp(48), 1));
        for (Button entry : new Button[]{favoritesButton, playlistsButton, queueButton}) {
            entry.setTextSize(13); entry.setPadding(dp(4), 0, dp(4), 0);
            entry.setSingleLine(true);
            entry.setAutoSizeTextTypeUniformWithConfiguration(11, 13, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        }
        addView(navigation, marginParams(LayoutParams.MATCH_PARENT, dp(48), 18, 12, 18, 6));

        LinearLayout section = horizontal();
        sectionTitle = label("现在，想听什么", 18, INK, true);
        sectionTitle.setSingleLine(true);
        sectionTitle.setEllipsize(TextUtils.TruncateAt.END);
        section.addView(sectionTitle, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        sectionCount = label("", 12, MUTED, false);
        section.addView(sectionCount);
        addView(section, marginParams(LayoutParams.MATCH_PARENT, dp(42), 20, 0, 20, 0));

        busyRow = horizontal();
        ProgressBar busyIndicator = new ProgressBar(context);
        busyIndicator.setIndeterminateTintList(ColorStateList.valueOf(GREEN));
        busyRow.addView(busyIndicator, new LayoutParams(dp(22), dp(22)));
        busyLabel = label("正在加载…", 13, MUTED, false);
        busyLabel.setSingleLine(true);
        busyLabel.setEllipsize(TextUtils.TruncateAt.END);
        busyLabel.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        busyRow.addView(busyLabel, marginParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, 10, 0, 0, 0));
        busyRow.setVisibility(GONE);
        addView(busyRow, marginParams(LayoutParams.MATCH_PARENT, dp(36), 22, 0, 22, 0));

        FrameLayout content = new FrameLayout(context);
        list = new ListView(context);
        list.setAdapter(adapter);
        list.setDivider(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        list.setDividerHeight(dp(5));
        list.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        list.setPadding(dp(14), 0, dp(14), dp(8));
        list.setClipToPadding(false);
        list.setScrollBarStyle(SCROLLBARS_OUTSIDE_OVERLAY);
        content.addView(list, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        emptyScroll = new ScrollView(context);
        emptyScroll.setFillViewport(true);
        LinearLayout empty = vertical();
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(28), dp(16), dp(28), dp(16));
        TextView note = label("♫", 34, GREEN, false);
        note.setGravity(Gravity.CENTER);
        note.setBackground(shape(PALE, 24, Color.TRANSPARENT));
        note.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        empty.addView(note, new LayoutParams(dp(64), dp(64)));
        emptyTitle = label("选一首，慢慢听", 18, INK, true);
        emptyTitle.setGravity(Gravity.CENTER);
        emptyTitle.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        empty.addView(emptyTitle, marginParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, 0, 16, 0, 8));
        emptyMessage = label("搜一首熟悉的歌，或打开「我的喜欢」。", 14, MUTED, false);
        emptyMessage.setGravity(Gravity.CENTER);
        emptyMessage.setLineSpacing(dp(3), 1);
        empty.addView(emptyMessage, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        retryButton = button("搜索歌曲", false, view -> focusSearch());
        empty.addView(retryButton, marginParams(dp(132), dp(48), 0, 18, 0, 0));
        emptyScroll.addView(empty, new ScrollView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        content.addView(emptyScroll, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(content, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));
        list.setVisibility(GONE);

        LinearLayout player = vertical();
        player.setPadding(dp(16), dp(12), dp(16), dp(10));
        player.setBackground(shape(Color.WHITE, 22, LINE));
        player.setElevation(dp(2));
        LinearLayout now = horizontal();
        LinearLayout song = vertical();
        playingTitle = label("还没有选择歌曲", 16, INK, true);
        playingTitle.setSingleLine(true);
        playingTitle.setEllipsize(TextUtils.TruncateAt.END);
        playingArtist = label("从搜索或我的喜欢开始", 12, MUTED, false);
        playingArtist.setSingleLine(true);
        playingArtist.setEllipsize(TextUtils.TruncateAt.END);
        song.addView(playingTitle);
        song.addView(playingArtist, marginParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, 0, 3, 0, 0));
        now.addView(song, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        playerQueue = label("队列", 12, GREEN, true);
        playerQueue.setGravity(Gravity.CENTER);
        playerQueue.setMinWidth(dp(56));
        playerQueue.setMinHeight(dp(48));
        playerQueue.setBackground(ripple(PALE, 14));
        playerQueue.setContentDescription("打开播放队列");
        playerQueue.setOnClickListener(view -> { lastAction = QUEUE; showingQueue = true; updateNavigation(); listener.onQueue(); });
        now.addView(playerQueue, marginParams(dp(60), dp(48), 10, 0, 0, 0));
        player.addView(now);

        seekSection = vertical();
        seekBar = new SeekBar(context);
        seekBar.setMax(1000);
        seekBar.setProgressTintList(ColorStateList.valueOf(GREEN));
        seekBar.setProgressBackgroundTintList(ColorStateList.valueOf(LINE));
        seekBar.setThumbTintList(ColorStateList.valueOf(GREEN));
        seekBar.setContentDescription("播放进度");
        seekBar.setPadding(dp(8), 0, dp(8), 0);
        seekSection.addView(seekBar, new LayoutParams(LayoutParams.MATCH_PARENT, dp(48)));
        LinearLayout times = horizontal();
        elapsed = label("0:00", 11, MUTED, false);
        duration = label("0:00", 11, MUTED, false);
        times.addView(elapsed, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        times.addView(duration);
        seekSection.addView(times, marginParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, 6, -6, 6, 3));
        player.addView(seekSection);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) {
                dragging = true; seekStartDuration = playbackDuration; seekStartTitle = currentTitle;
            }
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) elapsed.setText(formatTime(Math.round(playbackDuration * (progress / 1000.0))));
            }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                if (seekStartDuration > 0 && seekStartTitle.equals(currentTitle))
                    listener.onSeek(Math.min(playbackDuration, Math.round(playbackDuration * (bar.getProgress() / 1000.0))));
            }
        });

        LinearLayout controls = horizontal();
        previousButton = button("上一首", false, view -> listener.onPrevious());
        playButton = button("播放", true, view -> listener.onPlayPause());
        nextButton = button("下一首", false, view -> listener.onNext());
        controls.addView(previousButton, new LayoutParams(0, dp(52), 1));
        LayoutParams playParams = new LayoutParams(0, dp(52), 1.2f);
        playParams.setMargins(dp(10), 0, dp(10), 0);
        controls.addView(playButton, playParams);
        controls.addView(nextButton, new LayoutParams(0, dp(52), 1));
        player.addView(controls, marginParams(LayoutParams.MATCH_PARENT, dp(52), 0, 6, 0, 0));
        addView(player, marginParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, 14, 5, 14, 10));
        updatePlayback("", "", 0, 0, false, -1, 0);

        setOnApplyWindowInsetsListener((view, insets) -> {
            boolean keyboard;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
                keyboard = insets.isVisible(WindowInsets.Type.ime());
            } else {
                setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
                keyboard = insets.getSystemWindowInsetBottom() > dp(160);
            }
            seekSection.setVisibility(keyboard ? GONE : VISIBLE);
            return insets;
        });
    }

    /** 调用方可以直接传递可变列表；界面立即复制，点击回调也只暴露只读快照。 */
    public void showTracks(String title, List<Track> values, boolean queue) {
        setBusy(false, "");
        List<Track> copy = new ArrayList<>();
        if (values != null) for (Track track : values) if (track != null) copy.add(track);
        tracks = Collections.unmodifiableList(copy);
        showingQueue = queue;
        if (queue) lastAction = QUEUE;
        sectionTitle.setText(nonempty(title, queue ? "播放队列" : "歌曲"));
        sectionCount.setText(String.format(Locale.CHINA, "%d 首", tracks.size()));
        updateNavigation();
        if (list.getAdapter() != adapter) list.setAdapter(adapter);
        adapter.notifyDataSetChanged();
        list.setSelection(0);
        list.setVisibility(tracks.isEmpty() ? GONE : VISIBLE);
        emptyScroll.setVisibility(tracks.isEmpty() ? VISIBLE : GONE);
        if (tracks.isEmpty()) {
            emptyTitle.setText(queue ? "队列里还没有歌曲" : "暂时没有找到歌曲");
            emptyMessage.setText(queue ? "去搜索一首喜欢的歌，点一下就能开始听。" : "可以换个名字搜索，或稍后再试一次。");
            retryButton.setText(queue ? "找首歌" : "重新试试");
            retryButton.setOnClickListener(view -> { if (queue) focusSearch(); else retry(); });
        }
    }

    public void showPlaylists(List<Playlist> values) {
        setBusy(false, "");
        List<Playlist> copy = new ArrayList<>();
        if (values != null) for (Playlist playlist : values) if (playlist != null) copy.add(playlist);
        playlists = Collections.unmodifiableList(copy);
        showingQueue = false;
        lastAction = PLAYLISTS;
        selectedPlaylist = null;
        sectionTitle.setText("我的歌单");
        sectionCount.setText(String.format(Locale.CHINA, "%d 个", playlists.size()));
        updateNavigation();
        if (list.getAdapter() != playlistAdapter) list.setAdapter(playlistAdapter);
        playlistAdapter.notifyDataSetChanged();
        list.setSelection(0);
        list.setVisibility(playlists.isEmpty() ? GONE : VISIBLE);
        emptyScroll.setVisibility(playlists.isEmpty() ? VISIBLE : GONE);
        if (playlists.isEmpty()) {
            emptyTitle.setText("暂时没有找到歌单");
            emptyMessage.setText("确认已登录 QQ 音乐，再重新获取你的歌单。");
            retryButton.setText("重新获取");
            retryButton.setOnClickListener(view -> listener.onPlaylists());
        }
    }

    public void showMessage(String title, String message) {
        setBusy(false, "");
        sectionTitle.setText(nonempty(title, "轻听"));
        sectionCount.setText("");
        emptyTitle.setText(nonempty(title, "稍后再试试"));
        emptyMessage.setText(nonempty(message, "可以重新尝试，或从上方搜索一首歌。"));
        retryButton.setText(lastAction == 0 ? "搜索歌曲" : "重新试试");
        retryButton.setOnClickListener(view -> retry());
        list.setVisibility(GONE);
        emptyScroll.setVisibility(VISIBLE);
        updateNavigation();
    }

    public void setBusy(boolean busy, String message) {
        busyRow.setVisibility(busy ? VISIBLE : GONE);
        busyLabel.setText(nonempty(message, "正在加载…"));
        searchButton.setEnabled(!busy);
        searchButton.setAlpha(busy ? .55f : 1f);
        retryButton.setEnabled(!busy);
    }

    public void setSourceStatus(String status) {
        sourceStatus.setText(nonempty(status, "登录 QQ 音乐，听你喜欢的歌"));
    }

    public void updatePlayback(String title, String artist, long positionMs, long durationMs,
                               boolean playing, int queueIndex, int queueSize) {
        boolean changed = !currentTitle.equals(nonempty(title, "")) || !currentArtist.equals(nonempty(artist, ""))
                || currentQueueIndex != queueIndex;
        currentTitle = nonempty(title, ""); currentArtist = nonempty(artist, "");
        currentQueueIndex = queueIndex;
        playbackDuration = Math.max(0, durationMs);
        playbackPosition = Math.max(0, positionMs);
        if (playbackDuration > 0) playbackPosition = Math.min(playbackPosition, playbackDuration);
        playingTitle.setText(nonempty(currentTitle, "还没有选择歌曲"));
        playingArtist.setText(nonempty(currentArtist, currentTitle.isEmpty() ? "从搜索或我的喜欢开始" : "正在准备播放"));
        playButton.setText(playing ? "暂停" : "播放");
        playButton.setContentDescription(playing ? "暂停播放" : "开始播放");
        boolean hasSong = queueSize > 0 || !currentTitle.isEmpty();
        enable(playButton, hasSong);
        enable(previousButton, queueSize > 1);
        enable(nextButton, queueSize > 1);
        seekBar.setEnabled(hasSong && playbackDuration > 0);
        duration.setText(formatTime(playbackDuration));
        if (!dragging) {
            elapsed.setText(formatTime(playbackPosition));
            seekBar.setProgress(playbackDuration <= 0 ? 0 : (int) Math.round(1000.0 * playbackPosition / playbackDuration));
        }
        String count = queueSize > 0 && queueIndex >= 0 ? String.format(Locale.CHINA, "%d / %d", queueIndex + 1, queueSize) : "队列";
        playerQueue.setText(count);
        playerQueue.setContentDescription(queueSize > 0 ? "打开播放队列，共 " + queueSize + " 首" : "打开播放队列");
        if (changed) adapter.notifyDataSetChanged();
    }

    private void submitSearch() {
        if (!searchButton.isEnabled()) return;
        String query = searchInput.getText().toString().trim();
        if (query.isEmpty()) { searchInput.setError("先输入歌曲或歌手名字"); searchInput.requestFocus(); return; }
        lastSearch = query; lastAction = SEARCH; showingQueue = false;
        searchInput.setError(null);
        dismissKeyboard(); updateNavigation(); listener.onSearch(query);
    }

    private void retry() {
        if (lastAction == SEARCH && !lastSearch.isEmpty()) listener.onSearch(lastSearch);
        else if (lastAction == FAVORITES) listener.onFavorites();
        else if (lastAction == QUEUE) listener.onQueue();
        else if (lastAction == PLAYLISTS) listener.onPlaylists();
        else if (lastAction == PLAYLIST && selectedPlaylist != null) listener.onPlaylist(selectedPlaylist);
        else focusSearch();
    }

    private void focusSearch() {
        searchInput.requestFocus();
        InputMethodManager keyboard = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT);
    }

    private void dismissKeyboard() {
        InputMethodManager keyboard = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(getWindowToken(), 0);
        searchInput.clearFocus(); requestFocus();
    }

    private void showMore(View anchor) {
        PopupMenu menu = new PopupMenu(getContext(), anchor);
        menu.getMenu().add("登录 QQ 音乐").setOnMenuItemClickListener(item -> { dismissKeyboard(); listener.onLogin(); return true; });
        menu.getMenu().add("缓存管理").setOnMenuItemClickListener(item -> { dismissKeyboard(); listener.onCache(); return true; });
        menu.show();
    }

    private void updateNavigation() {
        favoritesButton.setBackground(ripple(lastAction == FAVORITES && !showingQueue ? PALE : Color.WHITE, 14));
        playlistsButton.setBackground(ripple((lastAction == PLAYLISTS || lastAction == PLAYLIST) && !showingQueue ? PALE : Color.WHITE, 14));
        queueButton.setBackground(ripple(showingQueue ? PALE : Color.WHITE, 14));
    }

    private final class PlaylistAdapter extends BaseAdapter {
        @Override public int getCount() { return playlists.size(); }
        @Override public Playlist getItem(int position) { return playlists.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            TrackRow row = recycled instanceof TrackRow ? (TrackRow) recycled : new TrackRow();
            Playlist playlist = getItem(position);
            String title = nonempty(playlist.title, "未命名歌单");
            row.setBackground(ripple(Color.WHITE, 16));
            row.number.setText("♫"); row.number.setTextColor(GREEN);
            row.name.setText(title); row.name.setTextColor(INK);
            row.artist.setVisibility(playlist.count >= 0 ? VISIBLE : GONE);
            row.artist.setText(playlist.count >= 0 ? String.format(Locale.CHINA, "%d 首歌曲", playlist.count) : "");
            row.length.setText("查看");
            row.remove.setVisibility(GONE);
            row.body.setContentDescription("打开歌单 " + title
                    + (playlist.count >= 0 ? "，共 " + playlist.count + " 首歌曲" : ""));
            row.body.setOnClickListener(view -> {
                selectedPlaylist = playlist; lastAction = PLAYLIST; showingQueue = false;
                dismissKeyboard(); updateNavigation(); listener.onPlaylist(playlist);
            });
            return row;
        }
    }

    private final class TrackAdapter extends BaseAdapter {
        @Override public int getCount() { return tracks.size(); }
        @Override public Track getItem(int position) { return tracks.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            TrackRow row = recycled instanceof TrackRow ? (TrackRow) recycled : new TrackRow();
            List<Track> selection = tracks;
            Track track = getItem(position);
            String name = nonempty(track.title, "未命名歌曲");
            String artist = nonempty(track.artist, "未知歌手");
            boolean current = showingQueue ? position == currentQueueIndex
                    : !currentTitle.isEmpty() && name.equals(currentTitle) && artist.equals(currentArtist);
            row.setBackground(ripple(current ? PALE : Color.WHITE, 16));
            row.number.setText(current ? "♫" : String.valueOf(position + 1));
            row.number.setTextColor(current ? GREEN : MUTED);
            row.name.setText(name); row.name.setTextColor(current ? GREEN : INK);
            row.artist.setVisibility(VISIBLE);
            row.artist.setText(artist + (track.album.isEmpty() ? "" : " · " + track.album));
            row.length.setText(track.durationMs > 0 ? formatTime(track.durationMs) : "—:—");
            row.remove.setVisibility(showingQueue ? VISIBLE : GONE);
            row.remove.setContentDescription("从队列移除：" + name);
            row.remove.setOnClickListener(view -> listener.onRemoveFromQueue(position));
            row.body.setContentDescription("播放 " + name + "，" + artist
                    + (track.durationMs > 0 ? "，" + formatTime(track.durationMs) : ""));
            row.body.setOnClickListener(view -> { dismissKeyboard(); listener.onSelect(track, selection); });
            return row;
        }
    }

    private final class TrackRow extends LinearLayout {
        final LinearLayout body;
        final TextView number, name, artist, length;
        final Button remove;
        TrackRow() {
            super(PlayerScreen.this.getContext());
            setOrientation(HORIZONTAL); setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(7), dp(7), dp(7), dp(7));
            setMinimumHeight(dp(76));
            body = horizontal(); body.setMinimumHeight(dp(62)); body.setFocusable(true);
            number = label("", 12, MUTED, true); number.setGravity(Gravity.CENTER);
            body.addView(number, new LayoutParams(dp(32), dp(48)));
            LinearLayout words = vertical();
            name = label("", 15, INK, true); name.setSingleLine(true); name.setEllipsize(TextUtils.TruncateAt.END);
            artist = label("", 12, MUTED, false); artist.setSingleLine(true); artist.setEllipsize(TextUtils.TruncateAt.END);
            words.addView(name);
            words.addView(artist, marginParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, 0, 5, 0, 0));
            body.addView(words, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
            length = label("", 11, MUTED, false); length.setGravity(Gravity.END);
            body.addView(length, marginParams(dp(44), LayoutParams.WRAP_CONTENT, 6, 0, 4, 0));
            for (TextView text : new TextView[]{number, name, artist, length}) text.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(body, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
            remove = button("移除", false, view -> {}); remove.setTextSize(12);
            addView(remove, marginParams(dp(50), dp(48), 4, 0, 0, 0));
        }
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); requestApplyInsets(); }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout vertical() { LinearLayout view = new LinearLayout(getContext()); view.setOrientation(VERTICAL); return view; }
    private LinearLayout horizontal() { LinearLayout view = new LinearLayout(getContext()); view.setOrientation(HORIZONTAL); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private TextView label(String text, int sp, int color, boolean medium) {
        TextView view = new TextView(getContext()); view.setText(text); view.setTextSize(sp); view.setTextColor(color);
        if (medium) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }
    private Button button(String title, boolean primary, View.OnClickListener action) {
        Button view = new Button(getContext()); view.setText(title); view.setTextSize(14); view.setAllCaps(false);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setTextColor(primary ? Color.WHITE : GREEN); view.setMinHeight(dp(48)); view.setMinimumHeight(dp(48));
        view.setMinWidth(dp(48)); view.setMinimumWidth(dp(48)); view.setPadding(dp(8), 0, dp(8), 0);
        view.setBackground(ripple(primary ? GREEN : Color.WHITE, 14)); view.setStateListAnimator(null);
        view.setOnClickListener(action); return view;
    }
    private GradientDrawable shape(int color, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius));
        if (stroke != Color.TRANSPARENT) drawable.setStroke(dp(1), stroke);
        return drawable;
    }
    private RippleDrawable ripple(int color, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(Color.argb(28, 30, 104, 75)), shape(color, radius, Color.TRANSPARENT), shape(Color.WHITE, radius, Color.TRANSPARENT));
    }
    private LayoutParams marginParams(int width, int height, int left, int top, int right, int bottom) {
        LayoutParams params = new LayoutParams(width, height); params.setMargins(dp(left), dp(top), dp(right), dp(bottom)); return params;
    }
    private static String nonempty(String value, String fallback) { return value == null || value.trim().isEmpty() ? fallback : value.trim(); }
    private static void enable(View view, boolean enabled) { view.setEnabled(enabled); view.setAlpha(enabled ? 1 : .38f); }
    private static String formatTime(long milliseconds) {
        long seconds = Math.max(0, milliseconds) / 1000;
        if (seconds >= 3600) return String.format(Locale.CHINA, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
        return String.format(Locale.CHINA, "%d:%02d", seconds / 60, seconds % 60);
    }
}
