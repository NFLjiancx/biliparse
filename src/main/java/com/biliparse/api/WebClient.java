package com.biliparse.api;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * 统一 HTTP 请求封装（对应文档 WebClient.RequestWeb）
 */
public class WebClient {

    public static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    public static final String REFERER_MAIN = "https://www.bilibili.com";
    public static final String REFERER_PASSPORT = "https://passport.bilibili.com/login";

    private static final int TIMEOUT_MS = 30_000;
    private static final int MAX_RETRY = 3;

    public static class HttpResult {
        public int statusCode;
        public String body;
        public Map<String, List<String>> headers;

        public List<String> setCookies() {
            if (headers == null) {
                return List.of();
            }
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase("Set-Cookie")) {
                    return e.getValue();
                }
            }
            return List.of();
        }
    }

    /** GET 请求，自动附带 Cookie（除非 withCookie=false） */
    public static HttpResult get(String url, String referer, boolean withCookie) {
        return request("GET", url, null, referer, withCookie, true);
    }

    /** POST 表单请求（用于旧版扫码轮询等） */
    public static HttpResult postForm(String url, String formBody, String referer, boolean withCookie) {
        return request("POST", url, formBody, referer, withCookie, true);
    }

    /** 不自动跟随重定向的 GET，用于解析短链跳转 */
    public static String resolveRedirect(String url) {
        HttpURLConnection conn = null;
        try {
            conn = open(url, "GET", REFERER_MAIN, true);
            conn.setInstanceFollowRedirects(false);
            conn.connect();
            String location = conn.getHeaderField("Location");
            return location != null ? location : url;
        } catch (IOException e) {
            throw new ApiException("解析短链失败: " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static HttpResult request(String method, String url, String formBody,
                                      String referer, boolean withCookie, boolean followRedirect) {
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            HttpURLConnection conn = null;
            try {
                conn = open(url, method, referer, withCookie);
                conn.setInstanceFollowRedirects(followRedirect);
                if ("POST".equals(method)) {
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                    conn.getOutputStream().write(formBody.getBytes(StandardCharsets.UTF_8));
                }
                conn.connect();

                HttpResult result = new HttpResult();
                result.statusCode = conn.getResponseCode();
                result.headers = conn.getHeaderFields();
                // 登录成功后保存 Cookie
                CookieManager.mergeSetCookies(result.setCookies());
                result.body = readBody(conn);
                return result;
            } catch (IOException e) {
                last = e;
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
            try {
                Thread.sleep(500L * attempt);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new ApiException("网络请求失败: " + (last == null ? "未知错误" : last.getMessage()), last);
    }

    private static HttpURLConnection open(String url, String method, String referer,
                                          boolean withCookie) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7");
        conn.setRequestProperty("Accept-Encoding", "gzip, deflate");
        if (referer != null) {
            conn.setRequestProperty("Referer", referer);
        }
        // 文档：URL 含 getLogin 的请求不附加 Origin 与本地 Cookie
        if (!url.contains("getLogin")) {
            if (!url.contains("passport.bilibili.com")) {
                conn.setRequestProperty("Origin", REFERER_MAIN);
            }
            if (withCookie) {
                String cookie = CookieManager.cookieHeader();
                if (cookie != null) {
                    conn.setRequestProperty("Cookie", cookie);
                }
            }
        }
        return conn;
    }

    private static String readBody(HttpURLConnection conn) throws IOException {
        InputStream raw;
        try {
            raw = conn.getInputStream();
        } catch (IOException e) {
            raw = conn.getErrorStream();
            if (raw == null) {
                throw e;
            }
        }
        String encoding = conn.getContentEncoding();
        InputStream in;
        if ("gzip".equalsIgnoreCase(encoding)) {
            in = new GZIPInputStream(raw);
        } else if ("deflate".equalsIgnoreCase(encoding)) {
            in = new InflaterInputStream(raw);
        } else {
            in = raw;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        in.close();
        return out.toString(StandardCharsets.UTF_8);
    }
}
