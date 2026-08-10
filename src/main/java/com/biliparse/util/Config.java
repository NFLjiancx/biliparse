package com.biliparse.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * 应用配置：下载目录、FFmpeg 路径、并发任务数、单文件线程数
 * 持久化到 ~/.biliparse/config.properties
 */
public class Config {

    private static final Path CONFIG_FILE =
            Paths.get(System.getProperty("user.home"), ".biliparse", "config.properties");

    public String downloadDir = Paths.get(System.getProperty("user.home"), "Downloads", "bilibili").toString();
    public String ffmpegPath = "ffmpeg";
    /** 同时进行下载的任务数 */
    public int maxConcurrentJobs = 2;
    /** 单个文件的分段下载线程数 */
    public int segmentsPerFile = 4;

    private static Config instance;

    public static synchronized Config get() {
        if (instance == null) {
            instance = new Config();
            instance.load();
        }
        return instance;
    }

    private void load() {
        if (!Files.exists(CONFIG_FILE)) {
            return;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(CONFIG_FILE)) {
            props.load(in);
            downloadDir = props.getProperty("downloadDir", downloadDir);
            ffmpegPath = props.getProperty("ffmpegPath", ffmpegPath);
            maxConcurrentJobs = Integer.parseInt(props.getProperty("maxConcurrentJobs", "2"));
            segmentsPerFile = Integer.parseInt(props.getProperty("segmentsPerFile", "4"));
        } catch (Exception ignored) {
        }
    }

    public synchronized void save() {
        Properties props = new Properties();
        props.setProperty("downloadDir", downloadDir);
        props.setProperty("ffmpegPath", ffmpegPath);
        props.setProperty("maxConcurrentJobs", String.valueOf(maxConcurrentJobs));
        props.setProperty("segmentsPerFile", String.valueOf(segmentsPerFile));
        try {
            Files.createDirectories(CONFIG_FILE.getParent());
            try (OutputStream out = Files.newOutputStream(CONFIG_FILE)) {
                props.store(out, "BiliParse config");
            }
        } catch (IOException ignored) {
        }
    }
}
