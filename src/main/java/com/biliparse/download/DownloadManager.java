package com.biliparse.download;

import com.biliparse.api.BiliApi;
import com.biliparse.model.PlayUrlData;
import com.biliparse.util.Config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 下载调度器：任务排队、并发控制、速度统计、FFmpeg 混流
 */
public class DownloadManager {

    public interface Listener {
        void onJobChanged(DownloadJob job);
    }

    private static DownloadManager instance;

    private final List<DownloadJob> jobs = new CopyOnWriteArrayList<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Map<DownloadJob, Long> lastBytes = new ConcurrentHashMap<>();
    private final Map<DownloadJob, Long> lastTime = new ConcurrentHashMap<>();

    private ExecutorService pool;

    public static synchronized DownloadManager get() {
        if (instance == null) {
            instance = new DownloadManager();
        }
        return instance;
    }

    private DownloadManager() {
        rebuildPool();
    }

    private void rebuildPool() {
        if (pool != null) {
            pool.shutdown();
        }
        pool = Executors.newFixedThreadPool(Math.max(1, Config.get().maxConcurrentJobs));
    }

    /** 无活动任务时应用新的并发数 */
    public synchronized void applyConcurrency() {
        boolean active = jobs.stream().anyMatch(j ->
                j.state == DownloadJob.State.DOWNLOADING || j.state == DownloadJob.State.MERGING);
        if (!active) {
            rebuildPool();
        }
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public List<DownloadJob> jobs() {
        return jobs;
    }

    public void submit(DownloadJob job) {
        jobs.add(job);
        notifyChange(job);
        pool.submit(() -> runJob(job));
    }

    public void removeFinished() {
        jobs.removeIf(j -> j.state == DownloadJob.State.DONE
                || j.state == DownloadJob.State.FAILED
                || j.state == DownloadJob.State.CANCELLED);
    }

    /** UI 定时调用：刷新下载速度 */
    public void tick() {
        long now = System.currentTimeMillis();
        for (DownloadJob job : jobs) {
            if (job.state != DownloadJob.State.DOWNLOADING) {
                continue;
            }
            Long prevBytes = lastBytes.get(job);
            Long prevTime = lastTime.get(job);
            long cur = job.downloaded.get();
            if (prevBytes != null && prevTime != null && now > prevTime) {
                job.speed = (cur - prevBytes) * 1000 / (now - prevTime);
            }
            lastBytes.put(job, cur);
            lastTime.put(job, now);
        }
    }

    /** 通知监听器任务有变化（外部直接增删任务后也需手动触发） */
    public void notifyChange(DownloadJob job) {
        for (Listener l : listeners) {
            l.onJobChanged(job);
        }
    }

    // ==================== 任务执行 ====================

    private void runJob(DownloadJob job) {
        File dir = job.outputFile.getParentFile();
        List<File> tempFiles = new ArrayList<>();
        try {
            if (job.isCancelRequested()) {
                job.state = DownloadJob.State.CANCELLED;
                notifyChange(job);
                return;
            }

            // 1. 获取流地址
            job.state = DownloadJob.State.FETCHING;
            notifyChange(job);
            PlayUrlData data = BiliApi.playUrl(job.episode, job.qn);

            // 2. 下载
            job.state = DownloadJob.State.DOWNLOADING;
            notifyChange(job);
            Files.createDirectories(dir.toPath());

            if (data.dash) {
                downloadDash(job, data, dir, tempFiles);
            } else {
                downloadDurl(job, data, dir, tempFiles);
            }

            if (job.isCancelRequested()) {
                job.state = DownloadJob.State.CANCELLED;
                notifyChange(job);
                return;
            }

            // 3. FFmpeg 混流
            job.state = DownloadJob.State.MERGING;
            notifyChange(job);
            job.speed = 0;
            mux(job, data, tempFiles);

            job.state = DownloadJob.State.DONE;
            notifyChange(job);
        } catch (Exception e) {
            if (job.isCancelRequested()) {
                job.state = DownloadJob.State.CANCELLED;
            } else {
                job.state = DownloadJob.State.FAILED;
                job.errorMessage = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            notifyChange(job);
        } finally {
            for (File f : tempFiles) {
                if (f.exists()) {
                    f.delete();
                }
            }
            lastBytes.remove(job);
            lastTime.remove(job);
        }
    }

    /** DASH：音视频并行下载后混流 */
    private void downloadDash(DownloadJob job, PlayUrlData data, File dir, List<File> tempFiles)
            throws Exception {
        File videoTmp = new File(dir, job.outputFile.getName() + ".video.m4s");
        tempFiles.add(videoTmp);
        File audioTmp = null;
        if (data.audioUrl != null) {
            audioTmp = new File(dir, job.outputFile.getName() + ".audio.m4s");
            tempFiles.add(audioTmp);
        }

        long videoSize = probeSize(data.videoUrl);
        long audioSize = data.audioUrl == null ? 0 : probeSize(data.audioUrl);
        job.totalBytes = (videoSize > 0 ? videoSize : 0) + (audioSize > 0 ? audioSize : 0);

        int segments = Math.max(1, Config.get().segmentsPerFile);
        ExecutorService io = Executors.newFixedThreadPool(audioTmp != null ? 2 : 1);
        List<Future<?>> futures = new ArrayList<>();
        File finalAudioTmp = audioTmp;
        futures.add(io.submit(() -> {
            try {
                StreamDownloader.download(data.videoUrl, videoTmp, segments,
                        job.downloaded, job::isCancelRequested);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }));
        if (finalAudioTmp != null) {
            futures.add(io.submit(() -> {
                try {
                    StreamDownloader.download(data.audioUrl, finalAudioTmp, segments,
                            job.downloaded, job::isCancelRequested);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        io.shutdown();
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                throw new IOException(cause.getMessage(), cause);
            }
        }
    }

    /** durl：分段顺序下载（FLV/MP4 旧格式） */
    private void downloadDurl(DownloadJob job, PlayUrlData data, File dir, List<File> tempFiles)
            throws IOException {
        int segments = Math.max(1, Config.get().segmentsPerFile);
        for (int i = 0; i < data.durlSegments.size(); i++) {
            File seg = new File(dir, job.outputFile.getName() + ".seg" + i + ".flv");
            tempFiles.add(seg);
            StreamDownloader.download(data.durlSegments.get(i), seg, segments,
                    job.downloaded, job::isCancelRequested);
        }
    }

    /** FFmpeg 混流/合并 */
    private void mux(DownloadJob job, PlayUrlData data, List<File> tempFiles) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(Config.get().ffmpegPath, "-y", "-loglevel", "error"));
        if (data.dash) {
            File videoTmp = tempFiles.get(0);
            cmd.addAll(List.of("-i", videoTmp.getAbsolutePath()));
            if (tempFiles.size() > 1) {
                cmd.addAll(List.of("-i", tempFiles.get(1).getAbsolutePath()));
            }
            cmd.addAll(List.of("-c", "copy"));
        } else if (tempFiles.size() == 1) {
            cmd.addAll(List.of("-i", tempFiles.get(0).getAbsolutePath(), "-c", "copy"));
        } else {
            // concat demuxer 合并多段
            File listFile = new File(job.outputFile.getParentFile(),
                    job.outputFile.getName() + ".concat.txt");
            StringBuilder sb = new StringBuilder();
            for (File seg : tempFiles) {
                sb.append("file '").append(seg.getAbsolutePath().replace("'", "'\\''")).append("'\n");
            }
            Files.writeString(listFile.toPath(), sb.toString());
            tempFiles.add(listFile);
            cmd.addAll(List.of("-f", "concat", "-safe", "0", "-i", listFile.getAbsolutePath(), "-c", "copy"));
        }
        cmd.add(job.outputFile.getAbsolutePath());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes());
        }
        int exit = process.waitFor();
        if (exit != 0) {
            String hint = output.contains("No such file") || output.contains("not found")
                    ? "（请检查 FFmpeg 是否已安装并在 设置→FFmpeg 路径 中配置）" : "";
            throw new IOException("FFmpeg 混流失败(exit=" + exit + ") " + hint + " "
                    + output.substring(0, Math.min(200, output.length())));
        }
    }

    /** 探测流文件大小（Range 请求） */
    private static long probeSize(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(30_000);
            conn.setRequestProperty("User-Agent", com.biliparse.api.WebClient.UA);
            conn.setRequestProperty("Referer", "https://www.bilibili.com");
            conn.setRequestProperty("Range", "bytes=0-0");
            int code = conn.getResponseCode();
            if (code == 206) {
                String range = conn.getHeaderField("Content-Range");
                if (range != null && range.contains("/")) {
                    return Long.parseLong(range.substring(range.lastIndexOf('/') + 1).trim());
                }
            }
            return conn.getContentLengthLong();
        } catch (Exception e) {
            return -1;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
