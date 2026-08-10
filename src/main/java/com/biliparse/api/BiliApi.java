package com.biliparse.api;

import com.biliparse.model.Episode;
import com.biliparse.model.PlayUrlData;
import com.biliparse.model.Quality;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bilibili API 门面（对应文档第四~十一节）
 */
public class BiliApi {

    private static final String API = "https://api.bilibili.com";
    private static final String PASSPORT = "https://passport.bilibili.com";

    /** playurl 的 fnval 位掩码：DASH+HDR+4K+杜比+HiRes+8K+AV1 */
    private static final String FNVAL = "4048";

    // ==================== 登录 ====================

    /** 新版扫码登录：生成二维码。返回 [url, qrcode_key] */
    public static String[] qrGenerate() {
        WebClient.HttpResult res = WebClient.get(
                PASSPORT + "/x/passport-login/web/qrcode/generate", WebClient.REFERER_PASSPORT, false);
        JsonObject data = requireData(res.body);
        return new String[]{
                data.get("url").getAsString(),
                data.get("qrcode_key").getAsString()
        };
    }

    /**
     * 新版扫码登录：轮询状态
     *
     * @return 状态码（0 成功 / 86038 二维码失效 / 86090 已扫码 / 86101 未扫码）
     */
    public static int qrPoll(String qrcodeKey) {
        WebClient.HttpResult res = WebClient.get(
                PASSPORT + "/x/passport-login/web/qrcode/poll?qrcode_key=" + qrcodeKey,
                WebClient.REFERER_PASSPORT, false);
        JsonObject root = JsonParser.parseString(res.body).getAsJsonObject();
        JsonObject data = root.has("data") && root.get("data").isJsonObject()
                ? root.getAsJsonObject("data") : new JsonObject();
        return data.has("code") ? data.get("code").getAsInt() : -1;
    }

    // ==================== 用户信息 ====================

    /** 导航栏用户信息，未登录也会返回（code=-101） */
    public static JsonObject nav() {
        WebClient.HttpResult res = WebClient.get(
                API + "/x/web-interface/nav", WebClient.REFERER_MAIN, true);
        return JsonParser.parseString(res.body).getAsJsonObject();
    }

    // ==================== 视频信息 ====================

    /** 视频详细信息（wbi/view） */
    public static JsonObject videoView(String bvid, Long aid) {
        Map<String, String> params = new LinkedHashMap<>();
        if (bvid != null) {
            params.put("bvid", bvid);
        } else {
            params.put("aid", String.valueOf(aid));
        }
        String query = WbiSign.sign(params);
        WebClient.HttpResult res = WebClient.get(
                API + "/x/web-interface/wbi/view?" + query, WebClient.REFERER_MAIN, true);
        return requireData(res.body);
    }

    // ==================== 番剧（PGC） ====================

    /** mediaId → seasonId（pgc/review/user） */
    public static String mediaToSeasonId(String mediaId) {
        WebClient.HttpResult res = WebClient.get(
                API + "/pgc/review/user?media_id=" + mediaId, WebClient.REFERER_MAIN, true);
        JsonObject root = JsonParser.parseString(res.body).getAsJsonObject();
        checkCode(root);
        return root.getAsJsonObject("result").getAsJsonObject("media")
                .get("season_id").getAsString();
    }

    /** 剧集明细（pgc/view/web/season） */
    public static JsonObject bangumiSeason(String seasonId, String epId) {
        String url = API + "/pgc/view/web/season?";
        if (seasonId != null) {
            url += "season_id=" + seasonId;
        } else {
            url += "ep_id=" + epId;
        }
        WebClient.HttpResult res = WebClient.get(url, WebClient.REFERER_MAIN, true);
        JsonObject root = JsonParser.parseString(res.body).getAsJsonObject();
        checkCode(root);
        return root.getAsJsonObject("result");
    }

    // ==================== 播放地址 ====================

    /**
     * 获取视频流地址
     *
     * @param episode 剧集/分P信息
     * @param qn      期望画质
     */
    public static PlayUrlData playUrl(Episode episode, int qn) {
        String url;
        if (episode.streamType == Episode.StreamType.BANGUMI) {
            url = API + "/pgc/player/web/playurl?ep_id=" + episode.epId
                    + "&cid=" + episode.cid + buildPlayUrlParams(qn);
        } else {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("cid", String.valueOf(episode.cid));
            if (episode.bvid != null) {
                params.put("bvid", episode.bvid);
            } else {
                params.put("aid", String.valueOf(episode.aid));
            }
            params.put("qn", String.valueOf(qn));
            params.put("fnver", "0");
            params.put("fnval", FNVAL);
            params.put("fourk", "1");
            params.put("from_client", "BROWSER");
            url = API + "/x/player/wbi/playurl?" + WbiSign.sign(params);
        }
        WebClient.HttpResult res = WebClient.get(url, WebClient.REFERER_MAIN, true);
        JsonObject root = JsonParser.parseString(res.body).getAsJsonObject();
        checkCode(root);
        // 普通视频用 data，番剧用 result，优先 data
        JsonObject data = root.has("data") && root.get("data").isJsonObject()
                ? root.getAsJsonObject("data") : root.getAsJsonObject("result");
        return parsePlayUrl(data, qn);
    }

    private static String buildPlayUrlParams(int qn) {
        return "&qn=" + qn + "&fnver=0&fnval=" + FNVAL + "&fourk=1";
    }

    private static PlayUrlData parsePlayUrl(JsonObject data, int requestedQn) {
        PlayUrlData result = new PlayUrlData();

        // 可用画质列表
        if (data.has("accept_quality")) {
            JsonArray qns = data.getAsJsonArray("accept_quality");
            JsonArray descs = data.has("accept_description")
                    ? data.getAsJsonArray("accept_description") : new JsonArray();
            for (int i = 0; i < qns.size(); i++) {
                String desc = i < descs.size() ? descs.get(i).getAsString() : ("画质 " + qns.get(i).getAsInt());
                result.qualities.add(new Quality(qns.get(i).getAsInt(), desc));
            }
        }

        if (data.has("dash") && data.get("dash").isJsonObject()) {
            result.dash = true;
            JsonObject dash = data.getAsJsonObject("dash");

            // 选择视频流：id <= 请求画质 中最大的，优先 H.264(codecid=7)
            JsonObject bestVideo = null;
            if (dash.has("video") && dash.get("video").isJsonArray()) {
                for (JsonElement el : dash.getAsJsonArray("video")) {
                    JsonObject v = el.getAsJsonObject();
                    int id = v.get("id").getAsInt();
                    if (id > requestedQn) {
                        continue;
                    }
                    if (bestVideo == null) {
                        bestVideo = v;
                        continue;
                    }
                    int bestId = bestVideo.get("id").getAsInt();
                    boolean bestAvc = bestVideo.get("codecid").getAsInt() == 7;
                    boolean curAvc = v.get("codecid").getAsInt() == 7;
                    if (id > bestId || (id == bestId && curAvc && !bestAvc)) {
                        bestVideo = v;
                    }
                }
            }
            if (bestVideo == null) {
                throw new ApiException("未获取到可用的视频流（可能需要登录或大会员）");
            }
            result.videoUrl = streamUrl(bestVideo);
            result.selectedQn = bestVideo.get("id").getAsInt();

            // 选择音频流：带宽最高者
            JsonObject bestAudio = null;
            if (dash.has("audio") && dash.get("audio").isJsonArray()) {
                for (JsonElement el : dash.getAsJsonArray("audio")) {
                    JsonObject a = el.getAsJsonObject();
                    if (bestAudio == null
                            || a.get("bandwidth").getAsLong() > bestAudio.get("bandwidth").getAsLong()) {
                        bestAudio = a;
                    }
                }
            }
            if (bestAudio != null) {
                result.audioUrl = streamUrl(bestAudio);
            }
        } else if (data.has("durl") && data.get("durl").isJsonArray()) {
            // 传统 FLV/MP4 分段
            for (JsonElement el : data.getAsJsonArray("durl")) {
                result.durlSegments.add(el.getAsJsonObject().get("url").getAsString());
            }
            if (result.durlSegments.isEmpty()) {
                throw new ApiException("未获取到可用的视频流");
            }
        } else {
            throw new ApiException("未获取到可用的视频流（可能需要登录或大会员）");
        }
        return result;
    }

    private static String streamUrl(JsonObject stream) {
        if (stream.has("baseUrl") && !stream.get("baseUrl").isJsonNull()) {
            return stream.get("baseUrl").getAsString();
        }
        return stream.get("base_url").getAsString();
    }

    // ==================== 工具 ====================

    private static JsonObject requireData(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        checkCode(root);
        return root.getAsJsonObject("data");
    }

    private static void checkCode(JsonObject root) {
        int code = root.has("code") ? root.get("code").getAsInt() : -1;
        if (code != 0) {
            String message = root.has("message") ? root.get("message").getAsString() : "";
            throw new ApiException("接口返回错误(code=" + code + "): " + message);
        }
    }
}
