package com.zhou.musiccache.player;

/** 歌单摘要；只包含可展示的标识、标题与歌曲数量。 */
public final class Playlist {
    public final String id;
    public final String title;
    /** -1 表示来源没有提供歌曲数量。 */
    public final int count;

    public Playlist(String id, String title, int count) {
        this.id = id == null ? "" : id.trim();
        this.title = title == null ? "" : title.trim();
        this.count = Math.max(-1, count);
    }
}
