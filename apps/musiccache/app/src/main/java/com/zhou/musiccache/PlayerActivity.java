package com.zhou.musiccache;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.common.util.concurrent.ListenableFuture;
import com.zhou.musiccache.cache.CacheProxy;
import com.zhou.musiccache.cache.RangeCache;
import com.zhou.musiccache.player.PlayerScreen;
import com.zhou.musiccache.player.Playlist;
import com.zhou.musiccache.player.QQWebSource;
import com.zhou.musiccache.player.Track;
import com.zhou.musiccache.playback.PlaybackService;
import com.zhou.musiccache.playback.PlayerRuntime;

import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 原生界面仅通过媒体控制器操作服务，关闭界面不会关闭音乐。 */
@UnstableApi
public final class PlayerActivity extends Activity implements PlayerScreen.Listener {
    private final Handler main = new Handler(Looper.getMainLooper());
    private PlayerScreen screen;
    private QQWebSource source;
    private MediaController controller;
    private ListenableFuture<MediaController> connection;
    private boolean queueView, destroyed, playbackActive;
    private int catalogRequest;
    private final List<Track> queue = new ArrayList<>();
    private SharedPreferences preferences;
    private final Runnable tick = new Runnable() {
        @Override public void run() { if (!destroyed) { updatePlayback(); main.postDelayed(this, 700); } }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("light-player", MODE_PRIVATE);
        source = ((MusicApplication)getApplication()).source();
        screen = new PlayerScreen(this, this); setContentView(screen);
        screen.setSourceStatus("QQ 音乐网页源 · 自动缓存");
        connection = new MediaController.Builder(this, new SessionToken(this, new ComponentName(this, PlaybackService.class))).buildAsync();
        connection.addListener(() -> {
            if (destroyed) return;
            try {
                controller = connection.get();
                controller.addListener(new Player.Listener() {
                    @Override public void onEvents(Player player, Player.Events events) {
                        syncQueue(); updatePlayback();
                        if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) || events.contains(Player.EVENT_TIMELINE_CHANGED)
                                || events.contains(Player.EVENT_IS_PLAYING_CHANGED)) saveQueue();
                    }
                    @Override public void onPlayerError(PlaybackException error) {
                        screen.setBusy(false, "");
                        screen.setSourceStatus("暂时无法播放，可重新登录后重试或换一首");
                        Toast.makeText(PlayerActivity.this, "QQ 网页暂未提供可播放音频，请确认登录和歌曲权限", Toast.LENGTH_LONG).show();
                    }
                });
                if (controller.getMediaItemCount() == 0) restoreQueue(); else syncQueue();
                updatePlayback();
            } catch (Exception error) { screen.showMessage("播放器暂未连接", "请重新打开轻听后重试"); }
        }, main::post);
        main.post(tick); onFavorites();
    }
    private MediaItem mediaItem(Track track) {
        return new MediaItem.Builder().setMediaId(track.id).setUri("qqmusic://song/" + track.id)
                .setMediaMetadata(new MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist)
                        .setAlbumTitle(track.album).build()).build();
    }
    private void restoreQueue() {
        try {
            JSONArray saved = new JSONArray(preferences.getString("queue", "[]"));
            List<MediaItem> items = new ArrayList<>(); queue.clear();
            for (int i = 0; i < saved.length() && i < 1000; i++) {
                Track t = Track.fromJson(saved.getJSONObject(i));
                if (t.id.matches("[A-Za-z0-9]{8,32}")) { queue.add(t); items.add(mediaItem(t)); }
            }
            if (!items.isEmpty()) controller.setMediaItems(items, Math.max(0, Math.min(items.size()-1, preferences.getInt("index",0))), Math.max(0, preferences.getLong("position",0)));
        } catch (Exception ignored) { queue.clear(); }
    }
    private void syncQueue() {
        if (controller == null) return;
        List<Track> previous = new ArrayList<>(queue); queue.clear();
        for (int i = 0; i < controller.getMediaItemCount(); i++) {
            MediaItem item = controller.getMediaItemAt(i); Track found = null;
            for (Track t : previous) if (t.id.equals(item.mediaId)) { found = t; break; }
            queue.add(found != null ? found : new Track(item.mediaId, text(item.mediaMetadata.title), text(item.mediaMetadata.artist), text(item.mediaMetadata.albumTitle), 0));
        }
    }
    private void saveQueue() {
        if (controller == null) return;
        try {
            JSONArray saved = new JSONArray(); for (Track track : queue) saved.put(track.toJson());
            preferences.edit().putString("queue", saved.toString()).putInt("index", controller.getCurrentMediaItemIndex())
                    .putLong("position", Math.max(0,controller.getCurrentPosition())).apply();
        } catch (Exception ignored) { }
    }
    private static String text(CharSequence value) { return value == null ? "" : value.toString(); }
    private void updatePlayback() {
        if (controller == null) return;
        MediaItem item = controller.getCurrentMediaItem(); long duration = controller.getDuration();
        if (duration == C.TIME_UNSET || duration < 0) {
            int i=controller.getCurrentMediaItemIndex(); duration=i>=0&&i<queue.size()?queue.get(i).durationMs:0;
        }
        screen.updatePlayback(item==null?"选一首喜欢的歌":text(item.mediaMetadata.title), item==null?"轻听 · QQ 音乐网页源":text(item.mediaMetadata.artist),
                controller.getCurrentPosition(), duration, controller.getPlayWhenReady() && controller.getPlayerError()==null
                        && controller.getPlaybackState()!=Player.STATE_ENDED && controller.getPlaybackState()!=Player.STATE_IDLE,
                controller.getCurrentMediaItemIndex(), controller.getMediaItemCount());
        if (controller.getPlaybackState()==Player.STATE_BUFFERING) {
            playbackActive=true; screen.setSourceStatus("正在准备歌曲…");
        } else if (controller.isPlaying()) {
            playbackActive=true; screen.setSourceStatus("正在播放 · 已缓存音频优先本地读取");
        } else if (playbackActive) {
            playbackActive=false;
            screen.setSourceStatus(controller.getPlayerError()!=null ? "暂时无法播放，可重新登录后重试或换一首"
                    : controller.getPlaybackState()==Player.STATE_ENDED ? "已播放完毕 · 自动缓存已保留"
                    : "播放已暂停 · 自动缓存已保留");
        }
    }
    @Override public void onSearch(String query) {
        queueView=false; final int request=++catalogRequest;
        screen.setBusy(true,"正在搜索…");
        source.search(query, catalogCallback(request, "搜索 · " + query));
    }
    @Override public void onFavorites() {
        queueView=false; final int request=++catalogRequest;
        screen.setBusy(true,"正在读取我的喜欢…");
        source.favorites(catalogCallback(request, "我的喜欢"));
    }
    @Override public void onPlaylists() {
        queueView=false; final int request=++catalogRequest; screen.setBusy(true,"正在读取我的歌单…");
        source.playlists(new QQWebSource.PlaylistCallback() {
            @Override public void loaded(List<Playlist> lists) {
                if(destroyed||request!=catalogRequest)return;
                screen.setBusy(false,"");screen.showPlaylists(lists);screen.setSourceStatus("选择一个歌单，读取完整歌曲目录");
            }
            @Override public void failed(String message) {
                if(destroyed||request!=catalogRequest)return;
                screen.setBusy(false,"");screen.showMessage("我的歌单",message);
            }
        });
    }
    @Override public void onPlaylist(Playlist playlist) {
        queueView=false; final int request=++catalogRequest;screen.setBusy(true,"正在读取完整歌单…");
        source.playlist(playlist.id,catalogCallback(request,playlist.title));
    }
    private QQWebSource.CatalogCallback catalogCallback(int request,String title) {
        return new QQWebSource.CatalogCallback() {
            @Override public void loaded(List<Track> tracks,String note) {
                if(destroyed||request!=catalogRequest)return;
                screen.setBusy(false,""); screen.showTracks(title,tracks,false); screen.setSourceStatus(note);
            }
            @Override public void failed(String message) {
                if(destroyed||request!=catalogRequest)return;
                screen.setBusy(false,"");screen.showMessage(title,message);screen.setSourceStatus(message);
            }
        };
    }
    @Override public void onLogin() { startActivityForResult(new Intent(this, LoginActivity.class), 11); }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==11){source.invalidate();onFavorites();}
    }
    @Override public void onSelect(Track track,List<Track> tracks) {
        if(controller==null){Toast.makeText(this,"播放器正在连接，请稍候",Toast.LENGTH_SHORT).show();return;}
        int index=0;for(int i=0;i<tracks.size();i++)if(tracks.get(i).id.equals(track.id)){index=i;break;}
        if(queueView){controller.seekTo(index,0);}
        else{
            queue.clear();queue.addAll(tracks);List<MediaItem> items=new ArrayList<>();for(Track t:tracks)items.add(mediaItem(t));
            controller.setMediaItems(items,index,0);
        }
        controller.prepare();controller.play();saveQueue();
    }
    @Override public void onQueue(){++catalogRequest;queueView=true;syncQueue();screen.setBusy(false,"");screen.showTracks("播放队列",new ArrayList<>(queue),true);}
    @Override public void onPlayPause(){
        if(controller==null)return;
        if(controller.getPlayerError()==null && controller.getPlayWhenReady()
                && (controller.getPlaybackState()==Player.STATE_READY||controller.getPlaybackState()==Player.STATE_BUFFERING))controller.pause();
        else if(controller.getMediaItemCount()>0){
            if(controller.getPlayerError()!=null)source.invalidate();
            if(controller.getPlaybackState()==Player.STATE_ENDED)controller.seekTo(0);
            controller.prepare();controller.play();
        }else Toast.makeText(this,"先从搜索或我的喜欢选择歌曲",Toast.LENGTH_SHORT).show();
    }
    @Override public void onPrevious(){if(controller!=null){boolean failed=controller.getPlayerError()!=null;controller.seekToPreviousMediaItem();if(failed){source.invalidate();controller.prepare();controller.play();}}}
    @Override public void onNext(){if(controller!=null){boolean failed=controller.getPlayerError()!=null;controller.seekToNextMediaItem();if(failed){source.invalidate();controller.prepare();controller.play();}}}
    @Override public void onSeek(long positionMs){if(controller!=null)controller.seekTo(positionMs);}
    @Override public void onRemoveFromQueue(int index){
        if(controller==null||index<0||index>=controller.getMediaItemCount())return;
        controller.removeMediaItem(index);syncQueue();saveQueue();if(queueView)onQueue();
    }
    @Override public void onCache(){
        CacheProxy.Snapshot s=PlayerRuntime.get(this).proxy.snapshot();int complete=0;for(RangeCache.Entry e:s.entries)if(e.complete)complete++;
        String message="已保存  "+size(s.diskBytes)+" / 256 MB\n完整音频资源  "+complete+" 个\n\n本次歌曲下载  "+size(s.networkBytes)
                +"\n本次缓存读取  "+size(s.hitBytes)+"\n版本校验  "+s.validationRequests+" 次 / "+size(s.validationBytes)
                +"\n\n正常播放时自动缓存，空间不足会淘汰旧音频。网页认证继续联网，歌曲版本一致时优先复用完整缓存。\n\n这些数字只统计音频及版本校验正文，网页和认证仍有网络流量。";
        TextView text=new TextView(this);text.setText(message);text.setTextSize(15);int p=(int)(22*getResources().getDisplayMetrics().density);text.setPadding(p,p,p,p);ScrollView scroll=new ScrollView(this);scroll.addView(text);
        new AlertDialog.Builder(this).setTitle("自动缓存").setView(scroll).setPositiveButton("完成",null)
                .setNeutralButton("清理音频",(d,w)->new AlertDialog.Builder(this).setTitle("清理已缓存的音频？")
                        .setMessage("会停止当前播放并删除音频缓存。登录和播放队列保留。")
                        .setNegativeButton("取消",null).setPositiveButton("清理",(a,b)->{
                            if(controller!=null)controller.stop();
                            new Thread(()->{try{PlayerRuntime.get(this).proxy.clearAudioCache();main.post(()->Toast.makeText(this,"音频缓存已清理",Toast.LENGTH_SHORT).show());}catch(Exception e){main.post(()->Toast.makeText(this,"清理失败，请稍后重试",Toast.LENGTH_SHORT).show());}},"clear-audio").start();
                        }).show()).show();
    }
    private static String size(long n){return n<1024?n+" B":n<1048576?String.format(Locale.CHINA,"%.1f KB",n/1024.0):String.format(Locale.CHINA,"%.2f MB",n/1048576.0);}
    @Override protected void onStop(){saveQueue();super.onStop();}
    @Override protected void onDestroy(){destroyed=true;++catalogRequest;main.removeCallbacksAndMessages(null);if(connection!=null)MediaController.releaseFuture(connection);super.onDestroy();}
}
