package com.biliparse;

import com.biliparse.api.CookieManager;
import com.biliparse.ui.MainApp;
import com.biliparse.util.Config;
import javafx.application.Application;

/**
 * BiliParse 程序入口（普通启动器，避免 shade jar 直接启动 JavaFX 的模块化检查）
 */
public class Main {

    public static void main(String[] args) {
        // 加载本地登录态与配置
        CookieManager.load();
        Config.get();
        Application.launch(MainApp.class, args);
    }
}
