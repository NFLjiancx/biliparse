package com.biliparse.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cookie 管理：全局维护 name=value，持久化到 ~/.biliparse/cookies.txt
 */
public class CookieManager {

    private static final Path COOKIE_FILE =
            Paths.get(System.getProperty("user.home"), ".biliparse", "cookies.txt");

    private static final Map<String, String> COOKIES = new ConcurrentHashMap<>();

    public static synchronized void load() {
        COOKIES.clear();
        if (!Files.exists(COOKIE_FILE)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(COOKIE_FILE, StandardCharsets.UTF_8)) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                int idx = line.indexOf('=');
                if (idx > 0) {
                    COOKIES.put(line.substring(0, idx), line.substring(idx + 1));
                }
            }
        } catch (IOException ignored) {
        }
    }

    public static synchronized void save() {
        try {
            Files.createDirectories(COOKIE_FILE.getParent());
            StringBuilder sb = new StringBuilder();
            // 使用 TreeMap 保证顺序稳定
            for (Map.Entry<String, String> e : new TreeMap<>(COOKIES).entrySet()) {
                sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
            Files.writeString(COOKIE_FILE, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    /** 合并响应中的 Set-Cookie 头 */
    public static void mergeSetCookies(List<String> setCookieHeaders) {
        if (setCookieHeaders == null || setCookieHeaders.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (String header : setCookieHeaders) {
            if (header == null) {
                continue;
            }
            String pair = header.split(";", 2)[0].trim();
            int idx = pair.indexOf('=');
            if (idx > 0) {
                String name = pair.substring(0, idx).trim();
                String value = pair.substring(idx + 1).trim();
                if (!name.isEmpty()) {
                    COOKIES.put(name, value);
                    changed = true;
                }
            }
        }
        if (changed) {
            save();
        }
    }

    public static String cookieHeader() {
        if (COOKIES.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : COOKIES.entrySet()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    public static boolean hasLoginCookie() {
        return COOKIES.containsKey("SESSDATA");
    }

    public static synchronized void clear() {
        COOKIES.clear();
        save();
    }
}
