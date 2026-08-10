package com.biliparse;

import com.biliparse.api.BiliApi;
import com.biliparse.api.CookieManager;
import com.biliparse.download.DownloadJob;
import com.biliparse.model.Episode;
import com.biliparse.model.PlayUrlData;
import com.biliparse.util.InputParser;
import com.biliparse.util.StringUtils;

import java.io.File;

/**
 * 端到端自检（临时验证用，不影响正式功能）
 */
public class SelfTest {

    public static void main(String[] args) throws Exception {
        CookieManager.load();

        // 1. MD5 基准验证
        String md5 = com.biliparse.api.WbiSign.md5("abc");
        check("900150983cd24fb0d6963f7d28e17f72".equals(md5), "MD5 算法");

        // 2. 输入解析
        check(InputParser.parse("BV17x411w7KC").type == InputParser.Type.VIDEO_BV, "BV 号解析");
        check(InputParser.parse("https://www.bilibili.com/video/av170001").type == InputParser.Type.VIDEO_AV, "av URL 解析");
        check(InputParser.parse("ss32982").type == InputParser.Type.BANGUMI_SEASON, "ss 解析");
        check(InputParser.parse("https://www.bilibili.com/bangumi/play/ep317925").type == InputParser.Type.BANGUMI_EP, "ep URL 解析");
        check(InputParser.parse("md28228367").type == InputParser.Type.BANGUMI_MEDIA, "md 解析");

        if (args.length > 0 && args[0].equals("--offline")) {
            System.out.println("离线自检全部通过");
            return;
        }

        // 3. 在线：Wbi 密钥刷新 + 视频信息
        String bvid = args.length > 0 ? args[0] : "BV17x411w7KC";
        var view = BiliApi.videoView(bvid, null);
        String title = view.get("title").getAsString();
        check(title != null && !title.isEmpty(), "视频信息(wbi/view)");
        System.out.println("视频标题: " + title);

        // 4. playurl（取第一个分P，低画质以加快下载）
        long cid = view.getAsJsonArray("pages").get(0).getAsJsonObject().get("cid").getAsLong();
        Episode ep = new Episode();
        ep.bvid = bvid;
        ep.cid = cid;
        PlayUrlData data = BiliApi.playUrl(ep, 16);
        check(data.dash && data.videoUrl != null, "playurl DASH 流");
        System.out.println("画质列表: " + data.qualities.size() + " 项, 实际画质 qn=" + data.selectedQn);

        // 5. 真实下载 + FFmpeg 混流
        File outDir = new File("/tmp/biliparse-selftest");
        outDir.mkdirs();
        File output = new File(outDir, "selftest.mp4");
        if (output.exists()) {
            output.delete();
        }
        DownloadJob job = new DownloadJob(ep, "selftest", output, 16);
        com.biliparse.download.DownloadManager.get().submit(job);
        // 等待完成
        long deadline = System.currentTimeMillis() + 300_000;
        while (System.currentTimeMillis() < deadline) {
            if (job.state == DownloadJob.State.DONE || job.state == DownloadJob.State.FAILED
                    || job.state == DownloadJob.State.CANCELLED) {
                break;
            }
            Thread.sleep(1000);
            System.out.printf("\r下载中: %.1f%%  速度 %s/s",
                    job.progress() * 100, StringUtils.formatBytes(job.speed));
        }
        System.out.println();
        check(job.state == DownloadJob.State.DONE && output.exists() && output.length() > 10_000,
                "下载+混流");
        System.out.println("输出文件: " + output + " (" + StringUtils.formatBytes(output.length()) + ")");
        System.out.println("自检全部通过！");
        System.exit(0);
    }

    private static void check(boolean ok, String name) {
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + name);
        if (!ok) {
            System.exit(1);
        }
    }
}
