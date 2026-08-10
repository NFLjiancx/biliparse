package com.biliparse.download;

import com.biliparse.model.Episode;

import java.io.File;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单个下载任务（一集/一分P）：获取流地址 → 下载音视频 → FFmpeg 混流
 */
public class DownloadJob {

    public enum State { QUEUED, FETCHING, DOWNLOADING, MERGING, DONE, FAILED, CANCELLED }

    /** 所属剧集/分P */
    public final Episode episode;
    /** 展示标题 */
    public final String title;
    /** 最终输出 mp4 文件 */
    public final File outputFile;
    /** 期望画质 */
    public final int qn;

    public volatile State state = State.QUEUED;
    public volatile String errorMessage;

    /** 已下载字节数 */
    public final AtomicLong downloaded = new AtomicLong();
    /** 总字节数（探测后得知） */
    public volatile long totalBytes = -1;
    /** 下载速度（字节/秒），由管理器周期计算 */
    public volatile long speed;

    private volatile boolean cancelRequested;

    public DownloadJob(Episode episode, String title, File outputFile, int qn) {
        this.episode = episode;
        this.title = title;
        this.outputFile = outputFile;
        this.qn = qn;
    }

    public void requestCancel() {
        cancelRequested = true;
    }

    public boolean isCancelRequested() {
        return cancelRequested;
    }

    /** 下载进度 0~1 */
    public double progress() {
        if (state == State.DONE) {
            return 1.0;
        }
        if (totalBytes <= 0) {
            return 0.0;
        }
        return Math.min(1.0, downloaded.get() * 1.0 / totalBytes);
    }
}
