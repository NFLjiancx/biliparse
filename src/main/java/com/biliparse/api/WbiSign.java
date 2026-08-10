package com.biliparse.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

/**
 * Wbi 签名机制（对应文档第三节 WbiSign）
 */
public class WbiSign {

    private static final int[] MIXIN_KEY_ENC_TAB = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
            37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
            22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
    };

    private static String imgKey;
    private static String subKey;

    /** 从 nav 接口刷新 Wbi 密钥 */
    public static synchronized void refreshKeys() {
        WebClient.HttpResult res = WebClient.get(
                "https://api.bilibili.com/x/web-interface/nav", WebClient.REFERER_MAIN, true);
        JsonObject root = JsonParser.parseString(res.body).getAsJsonObject();
        JsonObject wbiImg = root.getAsJsonObject("data").getAsJsonObject("wbi_img");
        imgKey = fileNameWithoutExt(wbiImg.get("img_url").getAsString());
        subKey = fileNameWithoutExt(wbiImg.get("sub_url").getAsString());
    }

    private static String fileNameWithoutExt(String url) {
        String name = url.substring(url.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String mixinKey() {
        String origin = imgKey + subKey;
        StringBuilder sb = new StringBuilder(32);
        for (int idx : MIXIN_KEY_ENC_TAB) {
            if (sb.length() >= 32) {
                break;
            }
            if (idx < origin.length()) {
                sb.append(origin.charAt(idx));
            }
        }
        return sb.toString();
    }

    /**
     * 对参数进行 Wbi 签名，返回追加了 wts 与 w_rid 的完整 query 字符串
     */
    public static synchronized String sign(Map<String, String> params) {
        if (imgKey == null || subKey == null) {
            refreshKeys();
        }
        TreeMap<String, String> sorted = new TreeMap<>(params);
        sorted.put("wts", String.valueOf(System.currentTimeMillis() / 1000));
        // 过滤参数值中的 !'()* 字符
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            String value = e.getValue().replaceAll("[!'()*]", "");
            if (query.length() > 0) {
                query.append('&');
            }
            query.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                  .append('=')
                  .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        }
        String wRid = md5(query + mixinKey());
        return query + "&w_rid=" + wRid;
    }

    public static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
