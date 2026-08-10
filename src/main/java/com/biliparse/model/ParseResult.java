package com.biliparse.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次解析的结果：视频/番剧元信息 + 剧集列表 + 可用画质
 */
public class ParseResult {

    public enum Kind { VIDEO, BANGUMI }

    public Kind kind = Kind.VIDEO;

    /** 合集/剧集标题（作为下载子目录名） */
    public String seasonTitle;
    /** 单个视频标题 */
    public String videoTitle;
    public String coverUrl;
    public String uploader;
    /** 描述信息（播放量、简介等，用于界面展示） */
    public String description;

    public List<Episode> episodes = new ArrayList<>();
    public List<Quality> qualities = new ArrayList<>();

    public String displayName() {
        return kind == Kind.BANGUMI ? seasonTitle : videoTitle;
    }
}
