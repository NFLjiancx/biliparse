package com.biliparse.util;

import com.biliparse.api.WebClient;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 输入解析（对应文档第十七节 ParseEntrance）
 * 支持 av/BV/ss/ep/md 及各类 URL、b23.tv 短链
 */
public class InputParser {

    public enum Type { VIDEO_BV, VIDEO_AV, BANGUMI_SEASON, BANGUMI_EP, BANGUMI_MEDIA, UNSUPPORTED }

    public static class ParsedInput {
        public Type type;
        public String id;

        ParsedInput(Type type, String id) {
            this.type = type;
            this.id = id;
        }
    }

    private static final Pattern BV_PATTERN = Pattern.compile("BV[0-9A-Za-z]{10}");

    public static ParsedInput parse(String raw) {
        String input = raw == null ? "" : raw.trim();
        if (input.isEmpty()) {
            return new ParsedInput(Type.UNSUPPORTED, null);
        }

        // URL 输入
        if (input.startsWith("http://") || input.startsWith("https://")) {
            String url = input.replaceFirst("^http://", "https://");

            // b23.tv 短链：解析重定向后再判断
            if (url.contains("b23.tv/")) {
                url = WebClient.resolveRedirect(url.split("[?#]")[0]);
                url = url.replaceFirst("^http://", "https://");
            }

            Matcher bv = BV_PATTERN.matcher(url);
            String path = url.replaceFirst("\\?.*$", "").replaceFirst("#.*$", "");

            if (path.contains("/bangumi/play/")) {
                String id = tailId(path);
                if (id.startsWith("ss")) {
                    return new ParsedInput(Type.BANGUMI_SEASON, id.substring(2));
                }
                if (id.startsWith("ep")) {
                    return new ParsedInput(Type.BANGUMI_EP, id.substring(2));
                }
            }
            if (path.contains("/bangumi/media/")) {
                String id = tailId(path);
                if (id.startsWith("md")) {
                    return new ParsedInput(Type.BANGUMI_MEDIA, id.substring(2));
                }
            }
            if (path.contains("/cheese/play/")) {
                return new ParsedInput(Type.UNSUPPORTED, null);
            }
            // 视频页 / 短链 / 分享页中的 BV 号
            if (bv.find()) {
                return new ParsedInput(Type.VIDEO_BV, bv.group());
            }
            Matcher av = Pattern.compile("av(\\d+)").matcher(url);
            if (av.find()) {
                return new ParsedInput(Type.VIDEO_AV, av.group(1));
            }
            return new ParsedInput(Type.UNSUPPORTED, null);
        }

        // 纯文本输入
        Matcher bv = BV_PATTERN.matcher(input);
        if (bv.find()) {
            return new ParsedInput(Type.VIDEO_BV, bv.group());
        }
        String lower = input.toLowerCase();
        if (lower.startsWith("av")) {
            return new ParsedInput(Type.VIDEO_AV, input.substring(2));
        }
        if (lower.startsWith("ss")) {
            return new ParsedInput(Type.BANGUMI_SEASON, input.substring(2));
        }
        if (lower.startsWith("ep")) {
            return new ParsedInput(Type.BANGUMI_EP, input.substring(2));
        }
        if (lower.startsWith("md")) {
            return new ParsedInput(Type.BANGUMI_MEDIA, input.substring(2));
        }
        return new ParsedInput(Type.UNSUPPORTED, null);
    }

    private static String tailId(String path) {
        String[] parts = path.split("/");
        return parts[parts.length - 1];
    }
}
