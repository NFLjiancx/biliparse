package com.biliparse.download;

import com.biliparse.api.CookieManager;
import com.biliparse.api.WebClient;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * 视频流下载器：支持 Range 分段多线程下载（对应文档第十八节下载要求）
 */
public class StreamDownloader {

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int SEGMENT_RETRY = 3;

    /**
     * 下载单个流文件
     *
     * @param url        流地址
     * @param target     目标文件
     * @param segments   分段线程数
     * @param downloaded 全局已下载字节计数（用于进度统计）
     * @param cancelled  取消检查
     */
    public static void download(String url, File target, int segments,
                                AtomicLong downloaded, BooleanSupplier cancelled) throws IOException {
        target.getParentFile().mkdirs();

        // 探测文件大小与 Range 支持
        long totalSize = -1;
        HttpURLConnection probe = openStreamConnection(url, "bytes=0-0");
        try {
            int code = probe.getResponseCode();
            if (code == 206) {
                String range = probe.getHeaderField("Content-Range");
                if (range != null && range.contains("/")) {
                    try {
                        totalSize = Long.parseLong(range.substring(range.lastIndexOf('/') + 1).trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
            } else if (code == 200) {
                long cl = probe.getContentLengthLong();
                // 200 响应也可能携带完整 Content-Length（服务器不支持 Range）
                if (cl > 0) {
                    totalSize = cl;
                }
            }
        } finally {
            probe.disconnect();
        }

        if (totalSize > 1024 * 1024 && segments > 1) {
            downloadSegmented(url, target, totalSize, segments, downloaded, cancelled);
        } else {
            downloadSingle(url, target, downloaded, cancelled);
        }
    }

    /** 分段并行下载，写入同一文件的不同区间 */
    private static void downloadSegmented(String url, File target, long totalSize, int segments,
                                          AtomicLong downloaded, BooleanSupplier cancelled) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(target, "rw")) {
            raf.setLength(totalSize);
        }
        long segSize = (totalSize + segments - 1) / segments;
        ExecutorService pool = Executors.newFixedThreadPool(segments);
        List<Segment> taskList = new ArrayList<>();
        for (int i = 0; i < segments; i++) {
            long start = i * segSize;
            long end = Math.min(start + segSize - 1, totalSize - 1);
            if (start > end) {
                break;
            }
            Segment seg = new Segment(url, target, start, end, downloaded, cancelled);
            taskList.add(seg);
            pool.submit(seg);
        }
        pool.shutdown();
        try {
            pool.awaitTermination(24, TimeUnit.HOURS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("下载被中断");
        }
        for (Segment seg : taskList) {
            if (seg.error != null) {
                throw seg.error;
            }
        }
    }

    /** 单连接完整下载（服务器不支持 Range 时的回退） */
    private static void downloadSingle(String url, File target,
                                       AtomicLong downloaded, BooleanSupplier cancelled) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= SEGMENT_RETRY; attempt++) {
            if (cancelled.getAsBoolean()) {
                throw new IOException("下载已取消");
            }
            HttpURLConnection conn = openStreamConnection(url, null);
            try {
                if (conn.getResponseCode() != 200) {
                    throw new IOException("HTTP " + conn.getResponseCode());
                }
                try (InputStream in = conn.getInputStream();
                     RandomAccessFile raf = new RandomAccessFile(target, "rw")) {
                    byte[] buf = new byte[BUFFER_SIZE];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        if (cancelled.getAsBoolean()) {
                            throw new IOException("下载已取消");
                        }
                        raf.write(buf, 0, n);
                        downloaded.addAndGet(n);
                    }
                }
                return;
            } catch (IOException e) {
                last = e;
                if ("下载已取消".equals(e.getMessage())) {
                    throw e;
                }
            } finally {
                conn.disconnect();
            }
            sleepQuietly(500L * attempt);
        }
        throw last != null ? last : new IOException("下载失败");
    }

    /** 单个下载分段 */
    private static class Segment implements Runnable {
        final String url;
        final File target;
        final long start;
        final long end;
        final AtomicLong downloaded;
        final BooleanSupplier cancelled;
        volatile IOException error;
        long written;

        Segment(String url, File target, long start, long end,
                AtomicLong downloaded, BooleanSupplier cancelled) {
            this.url = url;
            this.target = target;
            this.start = start;
            this.end = end;
            this.downloaded = downloaded;
            this.cancelled = cancelled;
        }

        @Override
        public void run() {
            for (int attempt = 1; attempt <= SEGMENT_RETRY; attempt++) {
                if (cancelled.getAsBoolean()) {
                    error = new IOException("下载已取消");
                    return;
                }
                long from = start + written;
                if (from > end) {
                    return;
                }
                HttpURLConnection conn = openStreamConnection(url, "bytes=" + from + "-" + end);
                try {
                    if (conn.getResponseCode() != 206) {
                        error = new IOException("服务器不支持 Range 请求(HTTP " + conn.getResponseCode() + ")");
                        return;
                    }
                    try (InputStream in = conn.getInputStream();
                         RandomAccessFile raf = new RandomAccessFile(target, "rw")) {
                        raf.seek(from);
                        byte[] buf = new byte[BUFFER_SIZE];
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            if (cancelled.getAsBoolean()) {
                                error = new IOException("下载已取消");
                                return;
                            }
                            raf.write(buf, 0, n);
                            written += n;
                            downloaded.addAndGet(n);
                        }
                    }
                    return; // 成功
                } catch (IOException e) {
                    error = e;
                } finally {
                    conn.disconnect();
                }
                sleepQuietly(500L * attempt);
            }
        }
    }

    /** 打开流下载连接，携带文档要求的 Origin/Referer/UA/Cookie 头 */
    private static HttpURLConnection openStreamConnection(String url, String range) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(60_000);
            conn.setRequestProperty("User-Agent", WebClient.UA);
            conn.setRequestProperty("Origin", WebClient.REFERER_MAIN);
            conn.setRequestProperty("Referer", WebClient.REFERER_MAIN);
            String cookie = CookieManager.cookieHeader();
            if (cookie != null) {
                conn.setRequestProperty("Cookie", cookie);
            }
            if (range != null) {
                conn.setRequestProperty("Range", range);
            }
            return conn;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
