package com.ethan.lexi;

/** 一条完整的离线词典记录，也用于生词本保存独立快照。 */
public final class Entry {
    public long id;
    public String word = "";
    public String phonetic = "";
    public String translation = "";
    public String definition = "";
    public String tags = "";
    public String exchange = "";
    /** ECDICT 语料词频排名，越小越常用；无排名时为 1,000,000,000。 */
    public int frequency = 1_000_000_000;

    public Entry() { }

    public Entry(long id, String word, String phonetic, String translation,
                 String definition, String tags, String exchange, int frequency) {
        this.id = id;
        this.word = word;
        this.phonetic = phonetic;
        this.translation = translation;
        this.definition = definition;
        this.tags = tags;
        this.exchange = exchange;
        this.frequency = frequency;
    }
}
