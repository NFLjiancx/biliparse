package com.biliparse.ui;

import com.biliparse.api.BiliApi;
import com.biliparse.api.CookieManager;
import com.biliparse.api.WbiSign;
import com.biliparse.util.QrUtil;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.io.ByteArrayInputStream;
import java.util.Objects;

/**
 * 扫码登录对话框（文档第四节：新版二维码登录）
 */
public class LoginDialog {

    public interface LoginCallback {
        void onLogin(String uname);
    }

    private final Stage stage = new Stage();
    private final ImageView qrView = new ImageView();
    private final Label hintLabel = new Label("正在获取二维码…");
    private final Label statusLabel = new Label(" ");

    private volatile String qrcodeKey;
    private volatile boolean polling;
    private volatile boolean closed;
    private final LoginCallback callback;

    public LoginDialog(Window owner, LoginCallback callback) {
        this.callback = callback;

        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.initStyle(StageStyle.UTILITY);
        stage.setTitle("登录 bilibili");
        stage.setResizable(false);

        qrView.setFitWidth(240);
        qrView.setFitHeight(240);
        hintLabel.getStyleClass().add("qr-hint");
        statusLabel.getStyleClass().add("status-text");

        Button refreshButton = new Button("刷新二维码");
        refreshButton.getStyleClass().add("primary");
        refreshButton.setOnAction(e -> startLogin());
        Button closeButton = new Button("关闭");
        closeButton.setOnAction(e -> stage.close());
        HBox buttons = new HBox(10, refreshButton, closeButton);
        buttons.setAlignment(Pos.CENTER);

        VBox content = new VBox(10, hintLabel, qrView, statusLabel, buttons);
        content.setAlignment(Pos.CENTER);
        content.setPadding(new Insets(18, 30, 18, 30));

        Scene scene = new Scene(content, 340, 420);
        scene.getStylesheets().add(Objects.requireNonNull(
                LoginDialog.class.getResource("app.css")).toExternalForm());
        stage.setScene(scene);
        stage.setOnHidden(e -> closed = true);

        startLogin();
    }

    /** 模态显示，阻塞直到关闭 */
    public void show() {
        stage.showAndWait();
    }

    private void startLogin() {
        polling = false;
        hintLabel.setText("正在获取二维码…");
        qrView.setImage(null);
        statusLabel.setText(" ");
        new Thread(() -> {
            try {
                String[] gen = BiliApi.qrGenerate();
                String url = gen[0];
                qrcodeKey = gen[1];
                if (closed) {
                    return;
                }
                byte[] png = QrUtil.generate(url, 240);
                Image image = new Image(new ByteArrayInputStream(png));
                Platform.runLater(() -> {
                    hintLabel.setText("请使用 bilibili 客户端扫码");
                    qrView.setImage(image);
                });
                pollLoop(qrcodeKey);
            } catch (Exception e) {
                if (!closed) {
                    Platform.runLater(() ->
                            statusLabel.setText("获取二维码失败: " + e.getMessage()));
                }
            }
        }, "qr-login").start();
    }

    private void pollLoop(String key) {
        polling = true;
        while (polling && !closed && key.equals(qrcodeKey)) {
            try {
                Thread.sleep(2000);
                int code = BiliApi.qrPoll(key);
                if (closed || !key.equals(qrcodeKey)) {
                    return;
                }
                switch (code) {
                    case 0 -> {
                        polling = false;
                        // 登录成功：Cookie 已由 WebClient 合并保存
                        CookieManager.save();
                        String uname = fetchUname();
                        try {
                            WbiSign.refreshKeys();
                        } catch (Exception ignored) {
                        }
                        Platform.runLater(() -> {
                            statusLabel.setText("登录成功：" + uname);
                            if (callback != null) {
                                callback.onLogin(uname);
                            }
                            stage.close();
                        });
                        return;
                    }
                    case 86038 -> {
                        Platform.runLater(() -> statusLabel.setText("二维码已失效，请刷新"));
                        polling = false;
                        return;
                    }
                    case 86090 -> Platform.runLater(() ->
                            statusLabel.setText("已扫码，请在手机上确认"));
                    case 86101 -> { /* 未扫码，继续等待 */ }
                    default -> { /* 网络抖动，继续轮询 */ }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ignored) {
                // 轮询失败时继续重试
            }
        }
    }

    private String fetchUname() {
        try {
            JsonObject nav = BiliApi.nav();
            if (nav.get("code").getAsInt() == 0) {
                return nav.getAsJsonObject("data").get("uname").getAsString();
            }
        } catch (Exception ignored) {
        }
        return "已登录";
    }
}
