package com.biliparse.model;

/**
 * 可下载的单集/分P条目
 */
public class Episode {

    /** 流类型：普通视频或番剧（PGC） */
    public enum StreamType { VIDEO, BANGUMI }

    public StreamType streamType = StreamType.VIDEO;

    /** 展示用序号，如 "1" / "第1话" */
    public String index;
    /** 主标题（分P名 / 剧集标题） */
    public String title;
    /** 副标题（番剧 long_title） */
    public String longTitle;
    /** 时长（秒） */
    public long duration;

    public String bvid;
    public long aid;
    public long cid;
    /** 番剧单集 ID */
    public String epId;

    /** 会员限定标记（番剧 status=2 等） */
    public boolean vipOnly;

    public String displayTitle() {
        if (longTitle != null && !longTitle.isEmpty()) {
            return index + ". " + title + "  " + longTitle;
        }
        return index + ". " + title;
    }
}
