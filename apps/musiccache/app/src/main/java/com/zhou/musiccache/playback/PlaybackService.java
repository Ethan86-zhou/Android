package com.zhou.musiccache.playback;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Process;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.zhou.musiccache.BuildConfig;
import com.zhou.musiccache.cache.CacheProxy;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONArray;

import java.io.IOException;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 播放器属于前台媒体服务，不属于Activity；退出界面不释放正在播放的队列。 */
@UnstableApi
public final class PlaybackService extends MediaSessionService {
    private ExoPlayer player;
    private MediaSession session;
    private final Handler diagnosticsHandler = new Handler(Looper.getMainLooper());
    private ExecutorService diagnosticsWriter;
    private Runnable diagnosticsTick;
    private SharedPreferences queuePreferences;
    private boolean hasObservedQueue;
    private final Handler queueHandler = new Handler(Looper.getMainLooper());
    private final Runnable saveQueueSoon = this::saveQueue;
    private final Runnable queueTick = new Runnable() {
        @Override public void run() {
            if (player == null) return;
            saveQueue(); queueHandler.postDelayed(this, 10000);
        }
    };
    private final Player.Listener queueListener = new Player.Listener() {
        @Override public void onEvents(Player current, Player.Events events) {
            if (current.getMediaItemCount() > 0) hasObservedQueue = true;
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)
                    || events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
                    || events.contains(Player.EVENT_POSITION_DISCONTINUITY)
                    || events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED)
                    || events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)) {
                // 合并同一组setMediaItems/seek/prepare回调，避免写入过渡状态。
                queueHandler.removeCallbacks(saveQueueSoon);
                queueHandler.postDelayed(saveQueueSoon, 250);
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        PlayerRuntime runtime = PlayerRuntime.get(this);
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new ProgressiveMediaSource.Factory(new CacheDataSource.Factory(runtime)))
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .build();
        queuePreferences = getSharedPreferences("light-player", MODE_PRIVATE);
        player.addListener(queueListener);
        queueHandler.postDelayed(queueTick, 10000);
        MediaSession.Builder builder = new MediaSession.Builder(this, player).setCallback(new SessionCallback());
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch != null) {
            builder.setSessionActivity(PendingIntent.getActivity(this, 0, launch,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        }
        session = builder.build();
        if (BuildConfig.DEBUG) startDiagnostics(runtime);
    }

    @Override public MediaSession onGetSession(MediaSession.ControllerInfo controller) {
        return allowed(controller) ? session : null;
    }

    private boolean allowed(MediaSession.ControllerInfo controller) {
        if (controller.getUid() == Process.myUid()) return true;
        if (!controller.isTrusted()) return false;
        if (controller.getUid() == Process.SYSTEM_UID) return true;
        try {
            ApplicationInfo application = getPackageManager().getApplicationInfo(controller.getPackageName(), 0);
            return application.uid == controller.getUid()
                    && (application.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
        } catch (PackageManager.NameNotFoundException error) { return false; }
    }

    private final class SessionCallback implements MediaSession.Callback {
        @Override public ListenableFuture<MediaSession.ConnectionResult> onConnectAsync(
                MediaSession mediaSession, MediaSession.ControllerInfo controller) {
            if (!allowed(controller)) return Futures.immediateFuture(MediaSession.ConnectionResult.reject());
            Player.Commands commands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS;
            if (controller.getUid() != Process.myUid()) {
                // 系统通知、耳机和锁屏可以控制当前队列；只有本应用可提交新歌曲。
                commands = commands.buildUpon().remove(Player.COMMAND_SET_MEDIA_ITEM)
                        .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS).build();
            }
            return Futures.immediateFuture(new MediaSession.ConnectionResult.AcceptedResultBuilder(mediaSession, controller)
                    .setAvailablePlayerCommands(commands).build());
        }

        @Override public ListenableFuture<List<MediaItem>> onAddMediaItems(MediaSession mediaSession,
                MediaSession.ControllerInfo controller, List<MediaItem> items) {
            if (controller.getUid() != Process.myUid()) {
                return Futures.immediateFailedFuture(new SecurityException("仅应用界面可添加歌曲"));
            }
            List<MediaItem> safe = new ArrayList<>();
            try {
                if (items.size() > 1000) throw new IOException("队列最多1000首歌曲");
                for (MediaItem item : items) {
                    String song = item.mediaId;
                    if (!CacheDataSource.isSongId(song)) throw new IOException("歌曲编号无效");
                    if (item.localConfiguration != null
                            && !song.equals(CacheDataSource.songId(item.localConfiguration.uri))) {
                        throw new IOException("歌曲来源不一致");
                    }
                    // 重建最小播放项目，不传播调用方传入的任意URI、DRM配置、广告或签名数据。
                    safe.add(new MediaItem.Builder().setMediaId(song).setUri("qqmusic://song/" + song)
                            .setMediaMetadata(new MediaMetadata.Builder()
                                    .setTitle(shortText(item.mediaMetadata.title))
                                    .setArtist(shortText(item.mediaMetadata.artist))
                                    .setAlbumTitle(shortText(item.mediaMetadata.albumTitle)).build()).build());
                }
            } catch (IOException error) {
                return Futures.immediateFailedFuture(new IOException("仅支持QQ音乐歌曲队列"));
            }
            return Futures.immediateFuture(safe);
        }
    }

    private static String shortText(CharSequence text) {
        if (text == null) return "";
        String value = text.toString(); return value.substring(0, Math.min(240, value.length()));
    }

    /** Activity不在时仍保存同一套队列字段；只构造安全元数据，不序列化MediaItem或播放URI。 */
    private void saveQueue() {
        if (player == null || queuePreferences == null) return;
        int count = player.getMediaItemCount();
        if (count > 0) hasObservedQueue = true;
        // MediaController在连接后才异步恢复队列，服务的初始空timeline不是用户清空操作。
        if (!hasObservedQueue) return;
        try {
            Map<String, Long> previousDurations = new HashMap<>();
            try {
                JSONArray previous = new JSONArray(queuePreferences.getString("queue", "[]"));
                for (int i = 0; i < previous.length(); i++) {
                    JSONObject value = previous.optJSONObject(i);
                    if (value != null) previousDurations.put(value.optString("id", ""), Math.max(0, value.optLong("durationMs", 0)));
                }
            } catch (JSONException ignored) { }
            JSONArray saved = new JSONArray();
            Timeline timeline = player.getCurrentTimeline();
            Timeline.Window window = new Timeline.Window();
            for (int i = 0; i < count; i++) {
                MediaItem item = player.getMediaItemAt(i);
                if (!CacheDataSource.isSongId(item.mediaId)) return;
                long duration = i < timeline.getWindowCount() ? timeline.getWindow(i, window).getDurationMs() : C.TIME_UNSET;
                if (duration < 0) duration = previousDurations.containsKey(item.mediaId) ? previousDurations.get(item.mediaId) : 0;
                saved.put(new JSONObject().put("id", item.mediaId)
                        .put("title", shortText(item.mediaMetadata.title))
                        .put("artist", shortText(item.mediaMetadata.artist))
                        .put("album", shortText(item.mediaMetadata.albumTitle))
                        .put("durationMs", Math.max(0, duration)));
            }
            String encoded = saved.toString();
            int index = count == 0 ? 0 : Math.max(0, Math.min(count - 1, player.getCurrentMediaItemIndex()));
            long position = count == 0 ? 0 : Math.max(0, player.getCurrentPosition());
            // 暂停后状态没变化时不重复写磁盘；也比较当前SharedPreferences以兼容Activity的保存。
            if (encoded.equals(queuePreferences.getString("queue", "[]"))
                    && index == queuePreferences.getInt("index", 0)
                    && position == queuePreferences.getLong("position", 0)) return;
            queuePreferences.edit().putString("queue", encoded).putInt("index", index)
                    .putLong("position", position).apply();
        } catch (JSONException ignored) { }
    }

    /** 调试验收只记录编号、播放状态及累计字节，不记录名称、签名地址、Cookie或错误消息。 */
    private void startDiagnostics(PlayerRuntime runtime) {
        diagnosticsWriter = Executors.newSingleThreadExecutor();
        AtomicFile file = new AtomicFile(new File(getFilesDir(), "player-status.json"));
        diagnosticsTick = () -> {
            if (player == null || diagnosticsWriter == null) return;
            try {
                JSONObject state = new JSONObject();
                state.put("appVersion", BuildConfig.VERSION_NAME);
                state.put("state", player.getPlaybackState());
                state.put("isPlaying", player.isPlaying());
                state.put("playWhenReady", player.getPlayWhenReady());
                state.put("positionMs", player.getCurrentPosition());
                state.put("durationMs", player.getDuration());
                state.put("currentMediaId", player.getCurrentMediaItem() == null ? "" : player.getCurrentMediaItem().mediaId);
                state.put("queueIndex", player.getCurrentMediaItemIndex());
                state.put("queueSize", player.getMediaItemCount());
                state.put("errorCode", player.getPlayerError() == null ? 0 : player.getPlayerError().errorCode);
                CacheProxy.Snapshot cache = runtime.proxy.snapshot();
                state.put("diskBytes", cache.diskBytes); state.put("networkBytes", cache.networkBytes);
                state.put("hitBytes", cache.hitBytes); state.put("validationRequests", cache.validationRequests);
                state.put("validationBytes", cache.validationBytes); state.put("reusedSignedLinks", cache.reusedSignedLinks);
                byte[] encoded = state.toString().getBytes(StandardCharsets.UTF_8);
                diagnosticsWriter.execute(() -> {
                    FileOutputStream output = null;
                    try {
                        output = file.startWrite(); output.write(encoded); file.finishWrite(output);
                    } catch (IOException ignored) { if (output != null) file.failWrite(output); }
                });
            } catch (JSONException ignored) { }
            diagnosticsHandler.postDelayed(diagnosticsTick, 1000);
        };
        diagnosticsHandler.post(diagnosticsTick);
    }

    @Override public void onDestroy() {
        queueHandler.removeCallbacksAndMessages(null);
        saveQueue();
        if (player != null) player.removeListener(queueListener);
        if (diagnosticsTick != null) diagnosticsHandler.removeCallbacks(diagnosticsTick);
        if (diagnosticsWriter != null) { diagnosticsWriter.shutdownNow(); diagnosticsWriter = null; }
        if (session != null) { session.release(); session = null; }
        if (player != null) { player.release(); player = null; }
        // 不取消共享proxy，也不清理音频；仍然打开的官方登录页可能正在使用它。
        super.onDestroy();
    }
}
