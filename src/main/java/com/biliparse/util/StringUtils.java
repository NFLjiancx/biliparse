package com.biliparse.util;

/**
 * 通用工具：文件名清洗、时长与字节格式化
 */
public class StringUtils {

    /** 清洗文件名中的非法字符 */
    public static String sanitizeFileName(String name) {
        if (name == null || name.isEmpty()) {
            return "untitled";
        }
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_").trim();
        if (cleaned.length() > 150) {
            cleaned = cleaned.substring(0, 150);
        }
        return cleaned.isEmpty() ? "untitled" : cleaned;
    }

    /** 秒 → mm:ss 或 HH:mm:ss */
    public static String formatDuration(long seconds) {
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        if (h > 0) {
            return String.format("%d:%02d:%02d", h, m, s);
        }
        return String.format("%d:%02d", m, s);
    }

    /** 字节数 → 人类可读 */
    public static String formatBytes(long bytes) {
        if (bytes < 0) {
            return "--";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format("%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format("%.1f MB", mb);
        }
        return String.format("%.2f GB", mb / 1024.0);
    }

    /** 播放数 → 万/亿 */
    public static String formatCount(long count) {
        if (count >= 100_000_000) {
            return String.format("%.1f亿", count / 100_000_000.0);
        }
        if (count >= 10_000) {
            return String.format("%.1f万", count / 10_000.0);
        }
        return String.valueOf(count);
    }
}
