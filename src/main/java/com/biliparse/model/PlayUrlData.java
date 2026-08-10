package com.biliparse.model;

import java.util.ArrayList;
import java.util.List;

/**
 * playurl 接口解析结果
 */
public class PlayUrlData {

    /** 是否为 DASH 音视频分流 */
    public boolean dash;

    /** DASH 视频流地址 */
    public String videoUrl;
    /** DASH 音频流地址（可能为 null） */
    public String audioUrl;

    /** 传统 durl 分段地址（FLV/MP4） */
    public List<String> durlSegments = new ArrayList<>();

    /** 本接口返回的可用画质列表 */
    public List<Quality> qualities = new ArrayList<>();

    /** 实际选中的视频画质 id */
    public int selectedQn;
}
