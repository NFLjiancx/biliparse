package com.biliparse.service;

import com.biliparse.api.ApiException;
import com.biliparse.api.BiliApi;
import com.biliparse.model.Episode;
import com.biliparse.model.ParseResult;
import com.biliparse.util.InputParser;
import com.biliparse.util.InputParser.ParsedInput;
import com.biliparse.util.StringUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * 解析服务：输入文本 → 视频/番剧解析结果
 */
public class ParseService {

    public static ParseResult parse(String input) {
        ParsedInput parsed = InputParser.parse(input);
        return switch (parsed.type) {
            case VIDEO_BV -> parseVideo(parsed.id, null);
            case VIDEO_AV -> parseVideo(null, Long.parseLong(parsed.id));
            case BANGUMI_SEASON -> parseBangumi(parsed.id, null, null);
            case BANGUMI_EP -> parseBangumi(null, parsed.id, null);
            case BANGUMI_MEDIA -> parseBangumi(null, null, parsed.id);
            case UNSUPPORTED -> throw new ApiException(
                    "无法识别的输入，支持：BV号、av号、番剧 ss/ep/md 及对应链接");
        };
    }

    // ==================== 普通视频 ====================

    private static ParseResult parseVideo(String bvid, Long aid) {
        JsonObject view = BiliApi.videoView(bvid, aid);

        // 番剧类稿件：redirect_url 跳转到 bangumi 页
        if (view.has("redirect_url") && !view.get("redirect_url").isJsonNull()) {
            String redirect = view.get("redirect_url").getAsString();
            ParsedInput re = InputParser.parse(redirect);
            if (re.type == InputParser.Type.BANGUMI_SEASON) {
                return parseBangumi(re.id, null, null);
            }
            if (re.type == InputParser.Type.BANGUMI_EP) {
                return parseBangumi(null, re.id, null);
            }
        }

        ParseResult result = new ParseResult();
        result.kind = ParseResult.Kind.VIDEO;
        result.videoTitle = view.get("title").getAsString();
        result.coverUrl = view.get("pic").getAsString();
        if (view.has("owner") && view.get("owner").isJsonObject()) {
            result.uploader = view.getAsJsonObject("owner").get("name").getAsString();
        }

        StringBuilder desc = new StringBuilder();
        if (view.has("stat") && view.get("stat").isJsonObject()) {
            JsonObject stat = view.getAsJsonObject("stat");
            desc.append("播放 ").append(StringUtils.formatCount(stat.get("view").getAsLong()))
                .append("  弹幕 ").append(StringUtils.formatCount(stat.get("danmaku").getAsLong()))
                .append("  点赞 ").append(StringUtils.formatCount(stat.get("like").getAsLong()));
        }
        if (result.uploader != null) {
            desc.append("  UP主: ").append(result.uploader);
        }
        result.description = desc.toString();

        String rootBvid = view.get("bvid").getAsString();
        long rootAid = view.get("aid").getAsLong();
        JsonArray pages = view.getAsJsonArray("pages");
        boolean single = pages.size() <= 1;
        for (JsonElement el : pages) {
            JsonObject page = el.getAsJsonObject();
            Episode ep = new Episode();
            ep.streamType = Episode.StreamType.VIDEO;
            ep.bvid = rootBvid;
            ep.aid = rootAid;
            ep.cid = page.get("cid").getAsLong();
            ep.duration = page.get("duration").getAsLong();
            ep.index = String.valueOf(page.get("page").getAsInt());
            String part = page.has("part") ? page.get("part").getAsString() : "";
            ep.title = single ? result.videoTitle : (part.isEmpty() ? "P" + ep.index : part);
            result.episodes.add(ep);
        }

        // 获取可用画质（以第一个分P探测，失败不阻塞）
        fillQualities(result);
        return result;
    }

    // ==================== 番剧（PGC） ====================

    private static ParseResult parseBangumi(String seasonId, String epId, String mediaId) {
        if (mediaId != null) {
            seasonId = BiliApi.mediaToSeasonId(mediaId);
        }
        JsonObject season = BiliApi.bangumiSeason(seasonId, epId);

        ParseResult result = new ParseResult();
        result.kind = ParseResult.Kind.BANGUMI;
        result.seasonTitle = season.get("title").getAsString();
        result.coverUrl = season.has("cover") ? season.get("cover").getAsString() : null;
        result.uploader = "番剧";

        StringBuilder desc = new StringBuilder();
        if (season.has("new_ep") && season.get("new_ep").isJsonObject()
                && season.getAsJsonObject("new_ep").has("desc")
                && !season.getAsJsonObject("new_ep").get("desc").isJsonNull()) {
            desc.append(season.getAsJsonObject("new_ep").get("desc").getAsString());
        }
        if (season.has("stat") && season.get("stat").isJsonObject()) {
            JsonObject stat = season.getAsJsonObject("stat");
            if (stat.has("view")) {
                if (desc.length() > 0) {
                    desc.append("  ");
                }
                desc.append("播放 ").append(StringUtils.formatCount(stat.get("view").getAsLong()));
            }
        }
        result.description = desc.toString();

        // 正片
        if (season.has("episodes")) {
            for (JsonElement el : season.getAsJsonArray("episodes")) {
                result.episodes.add(toEpisode(el.getAsJsonObject()));
            }
        }
        // 花絮/预告等分段
        if (season.has("section") && season.get("section").isJsonArray()) {
            for (JsonElement secEl : season.getAsJsonArray("section")) {
                JsonObject sec = secEl.getAsJsonObject();
                String secTitle = sec.has("title") ? sec.get("title").getAsString() : "";
                if (sec.has("episodes")) {
                    for (JsonElement el : sec.getAsJsonArray("episodes")) {
                        Episode ep = toEpisode(el.getAsJsonObject());
                        ep.title = "[" + secTitle + "] " + ep.title;
                        result.episodes.add(ep);
                    }
                }
            }
        }

        fillQualities(result);
        return result;
    }

    private static Episode toEpisode(JsonObject json) {
        Episode ep = new Episode();
        ep.streamType = Episode.StreamType.BANGUMI;
        ep.epId = json.get("id").getAsString();
        ep.aid = json.get("aid").getAsLong();
        if (json.has("bvid") && !json.get("bvid").isJsonNull()) {
            ep.bvid = json.get("bvid").getAsString();
        }
        ep.cid = json.get("cid").getAsLong();
        ep.title = json.has("title") ? json.get("title").getAsString() : "";
        if (json.has("long_title") && !json.get("long_title").isJsonNull()) {
            ep.longTitle = json.get("long_title").getAsString();
        }
        if (json.has("duration")) {
            ep.duration = json.get("duration").getAsLong() / 1000;
        }
        // status=2 通常为大会员专享
        if (json.has("status")) {
            ep.vipOnly = json.get("status").getAsInt() == 2;
        }
        ep.index = ep.title;
        return ep;
    }

    /** 以第一个条目探测可用画质，失败时保留空列表（UI 使用默认画质表） */
    private static void fillQualities(ParseResult result) {
        if (result.episodes.isEmpty()) {
            return;
        }
        try {
            Episode first = result.episodes.stream()
                    .filter(e -> !e.vipOnly)
                    .findFirst()
                    .orElse(result.episodes.get(0));
            result.qualities = BiliApi.playUrl(first, 127).qualities;
        } catch (Exception ignored) {
        }
    }
}
