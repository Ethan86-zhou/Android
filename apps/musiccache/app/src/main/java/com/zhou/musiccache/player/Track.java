package com.zhou.musiccache.player;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Objects;

/** 可保存的歌曲信息；播放地址和登录信息不属于歌曲模型。 */
public final class Track {
    public final String id;
    public final String title;
    public final String artist;
    public final String album;
    public final long durationMs;

    public Track(String id, String title, String artist, String album, long durationMs) {
        this.id = clean(id);
        this.title = clean(title);
        this.artist = clean(artist);
        this.album = clean(album);
        this.durationMs = Math.max(0, durationMs);
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }

    public JSONObject toJson() throws JSONException {
        return new JSONObject().put("id", id).put("title", title).put("artist", artist)
                .put("album", album).put("durationMs", durationMs);
    }

    public static Track fromJson(JSONObject value) {
        return new Track(value.optString("id", ""), value.optString("title", ""),
                value.optString("artist", ""), value.optString("album", ""),
                value.optLong("durationMs", 0));
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Track)) return false;
        Track track = (Track) other;
        return durationMs == track.durationMs && id.equals(track.id) && title.equals(track.title)
                && artist.equals(track.artist) && album.equals(track.album);
    }

    @Override public int hashCode() { return Objects.hash(id, title, artist, album, durationMs); }
}
