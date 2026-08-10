package com.biliparse.ui;

import com.biliparse.api.BiliApi;
import com.biliparse.api.CookieManager;
import com.biliparse.api.WebClient;
import com.biliparse.download.DownloadJob;
import com.biliparse.download.DownloadManager;
import com.biliparse.model.Episode;
import com.biliparse.model.ParseResult;
import com.biliparse.model.Quality;
import com.biliparse.service.ParseService;
import com.biliparse.util.Config;
import com.biliparse.util.StringUtils;
import com.google.gson.JsonObject;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Objects;

/**
 * JavaFX 主窗口：输入解析、剧集选择、画质选择、下载提交
 */
public class MainApp extends Application {

    /** 解析失败/接口未返回时使用的默认画质表（文档 9.4 节） */
    private static final Quality[] DEFAULT_QUALITIES = {
            new Quality(127, "超高清 8K"), new Quality(125, "HDR 真彩"),
            new Quality(120, "4K 超清"), new Quality(116, "1080P 60帧"),
            new Quality(112, "1080P 高码率"), new Quality(80, "1080P 高清"),
            new Quality(74, "720P 60帧"), new Quality(64, "720P 高清"),
            new Quality(32, "480P 清晰"), new Quality(16, "360P 流畅")
    };

    private final TextField inputField = new TextField();
    private final Button parseButton = new Button("解析");
    private final Button loginButton = new Button("登录");
    private final Label statusLabel = new Label("就绪");

    // 解析结果页
    private final ImageView coverView = new ImageView();
    private final Label titleLabel = new Label();
    private final Label descLabel = new Label();
    private final ObservableList<EpisodeRow> episodeRows = FXCollections.observableArrayList();
    private final TableView<EpisodeRow> episodeTable = new TableView<>(episodeRows);
    private final ComboBox<Quality> qualityCombo = new ComboBox<>();

    private ParseResult currentResult;
    private TabPane tabs;
    private MenuBar menuBar;
    private BorderPane root;
    private Stage stage;

    @Override
    public void stop() {
        // 下载线程池为非守护线程，窗口关闭后需显式退出，否则进程残留
        System.exit(0);
    }

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        stage.setTitle("BiliParse - bilibili 视频下载器");

        root = new BorderPane();
        root.setTop(buildHeader());
        tabs = new TabPane();
        tabs.getTabs().add(buildParseTab());
        tabs.getTabs().add(buildDownloadTab());
        root.setCenter(tabs);
        root.setBottom(buildStatusBar());

        Scene scene = new Scene(root, 1000, 680);
        scene.getStylesheets().add(Objects.requireNonNull(
                MainApp.class.getResource("app.css")).toExternalForm());
        stage.setScene(scene);
        stage.setMinWidth(860);
        stage.setMinHeight(560);
        stage.show();

        refreshLoginState();
        checkFfmpeg();

        // 自检模式：截图后退出（用于自动化验证界面渲染）
        List<String> params = getParameters().getRaw();
        if (params.contains("--screenshot")) {
            new Thread(() -> {
                try {
                    Thread.sleep(3000);
                    Platform.runLater(() -> {
                        try {
                            javafx.scene.image.WritableImage img =
                                    root.snapshot(new javafx.scene.SnapshotParameters(), null);
                            ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png",
                                    new File("/tmp/biliparse-ui.png"));
                            // 切到下载列表页再截一张
                            tabs.getSelectionModel().select(1);
                            javafx.scene.image.WritableImage img2 =
                                    root.snapshot(new javafx.scene.SnapshotParameters(), null);
                            ImageIO.write(SwingFXUtils.fromFXImage(img2, null), "png",
                                    new File("/tmp/biliparse-ui-2.png"));
                            System.out.println("截图已保存: /tmp/biliparse-ui.png");
                            Platform.exit();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
                } catch (InterruptedException ignored) {
                }
            }, "screenshot").start();
        }

        // 样式自检：滚动条、菜单弹层、下拉弹层、设置对话框（截图后退出）
        if (params.contains("--demo-style")) {
            new File("/tmp/biliparse-demo").mkdirs();
            // 填充足够多的任务，让下载列表出现滚动条
            for (int i = 1; i <= 20; i++) {
                DownloadJob job = new DownloadJob(new Episode(), "样式自检任务 " + i,
                        new File("/tmp/biliparse-demo/style" + i + ".mp4"), 80);
                DownloadManager.get().jobs().add(job);
                DownloadManager.get().notifyChange(job);
            }
            tabs.getSelectionModel().select(1);
            new Thread(() -> {
                try {
                    Thread.sleep(2000);
                    Platform.runLater(() -> shot("/tmp/biliparse-demo/s1-scrollbar.png"));
                    Thread.sleep(600);
                    java.util.concurrent.atomic.AtomicReference<javafx.scene.control.ContextMenu> menuRef =
                            new java.util.concurrent.atomic.AtomicReference<>();
                    Platform.runLater(() -> {
                        // 直接弹出与菜单栏同套样式的上下文菜单（关闭自动隐藏，保证截图时可见）
                        javafx.scene.control.ContextMenu cm = new javafx.scene.control.ContextMenu(
                                new MenuItem("偏好设置…"),
                                new MenuItem("退出登录"));
                        cm.setAutoHide(false);
                        javafx.geometry.Point2D pos = menuBar.localToScreen(6, menuBar.getHeight());
                        cm.show(menuBar, pos.getX(), pos.getY());
                        menuRef.set(cm);
                    });
                    Thread.sleep(600);
                    Platform.runLater(() -> {
                        shotPopup("/tmp/biliparse-demo/s2-menu.png");
                        javafx.scene.control.ContextMenu cm = menuRef.get();
                        if (cm != null) {
                            cm.hide();
                        }
                    });
                    Thread.sleep(600);
                    Platform.runLater(() -> {
                        tabs.getSelectionModel().select(0);
                        qualityCombo.show();
                    });
                    Thread.sleep(600);
                    Platform.runLater(() -> {
                        shotPopup("/tmp/biliparse-demo/s3-combo.png");
                        qualityCombo.hide();
                    });
                    Thread.sleep(600);
                    Platform.runLater(() -> {
                        javafx.scene.control.Dialog<javafx.scene.control.ButtonType> d =
                                SettingsDialog.build(stage);
                        d.show();
                        Platform.runLater(() -> {
                            try {
                                javafx.scene.image.WritableImage img = d.getDialogPane()
                                        .snapshot(new javafx.scene.SnapshotParameters(), null);
                                ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png",
                                        new File("/tmp/biliparse-demo/s4-settings.png"));
                            } catch (Exception ignored) {
                            }
                            d.close();
                            Platform.exit();
                        });
                    });
                } catch (InterruptedException ignored) {
                }
            }, "style-shot").start();
        }

        // 演示模式：真实解析并提交一个小画质下载，连续截图验证进度动画（自检用）
        if (params.contains("--demo-download")) {
            new Thread(() -> {
                try {
                    new File("/tmp/biliparse-demo").mkdirs();
                    ParseResult result = ParseService.parse("BV17x411w7KC");
                    Platform.runLater(() -> showResult(result));
                    Thread.sleep(1000);
                    Episode ep = result.episodes.get(0);
                    File out = new File("/tmp/biliparse-demo/demo.mp4");
                    DownloadManager.get().submit(new DownloadJob(ep,
                            result.displayName() + " - " + ep.displayTitle(), out, 16));
                    Platform.runLater(() -> tabs.getSelectionModel().select(1));
                    for (int i = 1; i <= 5; i++) {
                        Thread.sleep(3000);
                        int idx = i;
                        Platform.runLater(() -> {
                            try {
                                javafx.scene.image.WritableImage img =
                                        root.snapshot(new javafx.scene.SnapshotParameters(), null);
                                ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png",
                                        new File("/tmp/biliparse-demo/shot" + idx + ".png"));
                            } catch (Exception ignored) {
                            }
                        });
                    }
                    Thread.sleep(500);
                    Platform.exit();
                } catch (Exception e) {
                    e.printStackTrace();
                    Platform.exit();
                }
            }, "demo-download").start();
        }

        // 模拟模式：伪造一个慢速下载任务，连续抓帧观察进度条动画（自检用）
        if (params.contains("--demo-progress")) {
            new File("/tmp/biliparse-demo").mkdirs();
            DownloadJob fake = new DownloadJob(new Episode(), "模拟下载任务（动画观察）",
                    new File("/tmp/biliparse-demo/fake.mp4"), 80);
            fake.state = DownloadJob.State.FETCHING;
            fake.totalBytes = 100_000_000L;
            DownloadManager.get().jobs().add(fake);
            DownloadManager.get().notifyChange(fake);
            // 再加一个排队中任务，验证静态空条不会抖动
            DownloadJob queued = new DownloadJob(new Episode(), "排队任务（静态空条）",
                    new File("/tmp/biliparse-demo/queued.mp4"), 80);
            DownloadManager.get().jobs().add(queued);
            DownloadManager.get().notifyChange(queued);
            tabs.getSelectionModel().select(1);
            new Thread(() -> {
                try {
                    // 按 100ms 粒度推进，模拟真实下载的连续字节累加；前 2 秒为获取流地址阶段
                    for (int t = 1; t <= 120; t++) {
                        Thread.sleep(100);
                        if (t == 20) {
                            fake.state = DownloadJob.State.DOWNLOADING;
                        }
                        if (t > 20) {
                            fake.downloaded.set(fake.totalBytes * (t - 20) / 100);
                        }
                    }
                    fake.speed = 0;
                    Thread.sleep(3000);
                    Platform.exit();
                } catch (InterruptedException ignored) {
                }
            }, "sim-progress").start();
            new Thread(() -> {
                try {
                    Thread.sleep(1500);
                    for (int i = 1; i <= 12; i++) {
                        Thread.sleep(1000);
                        int idx = i;
                        Platform.runLater(() -> {
                            try {
                                javafx.scene.image.WritableImage img =
                                        root.snapshot(new javafx.scene.SnapshotParameters(), null);
                                ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png",
                                        new File("/tmp/biliparse-demo/p" + idx + ".png"));
                            } catch (Exception ignored) {
                            }
                        });
                    }
                } catch (InterruptedException ignored) {
                }
            }, "sim-shot").start();
        }
    }

    // ==================== 界面构建 ====================

    /** 主窗口截图（自检用） */
    private void shot(String path) {
        try {
            javafx.scene.image.WritableImage img =
                    root.snapshot(new javafx.scene.SnapshotParameters(), null);
            ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", new File(path));
        } catch (Exception ignored) {
        }
    }

    /** 当前弹出窗口（菜单/下拉列表）截图（自检用） */
    private void shotPopup(String path) {
        javafx.stage.Window.getWindows().stream()
                .filter(w -> w instanceof javafx.stage.PopupWindow && w.isShowing())
                .findFirst()
                .ifPresent(w -> {
                    try {
                        javafx.scene.Node popupRoot =
                                ((javafx.stage.PopupWindow) w).getScene().getRoot();
                        javafx.scene.image.WritableImage img =
                                popupRoot.snapshot(new javafx.scene.SnapshotParameters(), null);
                        ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", new File(path));
                    } catch (Exception ignored) {
                    }
                });
    }

    private VBox buildHeader() {
        menuBar = new MenuBar();

        Menu settingsMenu = new Menu("设置");
        MenuItem settingsItem = new MenuItem("偏好设置…");
        settingsItem.setOnAction(e -> SettingsDialog.show(stage));
        settingsMenu.getItems().add(settingsItem);

        Menu accountMenu = new Menu("账号");
        MenuItem logoutItem = new MenuItem("退出登录");
        logoutItem.setOnAction(e -> {
            CookieManager.clear();
            refreshLoginState();
            statusLabel.setText("已退出登录");
        });
        accountMenu.getItems().add(logoutItem);

        Menu helpMenu = new Menu("帮助");
        MenuItem aboutItem = new MenuItem("关于");
        aboutItem.setOnAction(e -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION,
                    "BiliParse - bilibili 视频下载器\n基于 DownKyi 接口文档实现\n仅供学习研究使用",
                    ButtonType.OK);
            alert.setTitle("关于");
            alert.setHeaderText(null);
            alert.initOwner(stage);
            alert.showAndWait();
        });
        helpMenu.getItems().add(aboutItem);

        menuBar.getMenus().addAll(settingsMenu, accountMenu, helpMenu);

        inputField.setPromptText("输入 BV号 / av号 / 番剧 ss/ep/md / 视频或番剧链接 / b23.tv 短链");
        inputField.setPrefColumnCount(36);
        inputField.setOnAction(e -> startParse());
        parseButton.getStyleClass().add("primary");
        parseButton.setOnAction(e -> startParse());
        loginButton.setOnAction(e -> showLogin());

        HBox inputRow = new HBox(8, inputField, parseButton, loginButton);
        inputRow.setAlignment(Pos.CENTER);
        HBox.setHgrow(inputField, Priority.ALWAYS);
        inputRow.getStyleClass().add("top-bar");

        return new VBox(menuBar, inputRow);
    }

    private Tab buildParseTab() {
        Tab tab = new Tab("解析结果");
        tab.setClosable(false);

        titleLabel.getStyleClass().add("video-title");
        descLabel.getStyleClass().add("video-desc");
        descLabel.setWrapText(true);
        descLabel.setMaxWidth(640);

        VBox infoBox = new VBox(8, titleLabel, descLabel);
        infoBox.setPadding(new Insets(6, 12, 6, 4));

        Region coverPlaceholder = new Region();
        coverPlaceholder.getStyleClass().add("cover-placeholder");
        // 16:9 封面区域，与视频封面比例一致
        coverPlaceholder.setPrefSize(320, 180);
        coverView.setFitWidth(320);
        coverView.setFitHeight(180);
        coverView.setPreserveRatio(true);
        StackPane coverPane = new StackPane(coverPlaceholder, coverView);
        coverPane.getStyleClass().add("cover-pane");

        HBox infoCard = new HBox(12, coverPane, infoBox);
        infoCard.getStyleClass().add("card");
        infoCard.setAlignment(Pos.CENTER_LEFT);

        buildEpisodeTable();

        Label qualityLabel = new Label("画质：");
        qualityCombo.setPrefWidth(180);
        qualityCombo.getItems().addAll(DEFAULT_QUALITIES);
        qualityCombo.getSelectionModel().select(5); // 默认 1080P 高清

        Button selectAllButton = new Button("全选");
        selectAllButton.setOnAction(e -> setAllSelected(true));
        Button invertButton = new Button("反选");
        invertButton.setOnAction(e -> episodeRows.forEach(r -> r.selected.set(!r.selected.get())));
        Button downloadButton = new Button("下载所选");
        downloadButton.getStyleClass().add("primary");
        downloadButton.setOnAction(e -> startDownload());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actionBar = new HBox(8, qualityLabel, qualityCombo,
                selectAllButton, invertButton, spacer, downloadButton);
        actionBar.setAlignment(Pos.CENTER_LEFT);
        actionBar.getStyleClass().add("action-bar");

        VBox content = new VBox(12, infoCard, episodeTable, actionBar);
        content.setPadding(new Insets(12));
        VBox.setVgrow(episodeTable, Priority.ALWAYS);
        tab.setContent(content);
        return tab;
    }

    private void buildEpisodeTable() {
        episodeTable.setEditable(true);
        episodeTable.setPlaceholder(new Label("在此输入视频或番剧链接，点击「解析」查看剧集列表"));
        episodeTable.getStyleClass().add("flat-table");

        TableColumn<EpisodeRow, Boolean> selCol = new TableColumn<>("选择");
        selCol.setCellValueFactory(cd -> cd.getValue().selected);
        selCol.setCellFactory(CheckBoxTableCell.forTableColumn(selCol));
        selCol.setEditable(true);
        selCol.setPrefWidth(56);

        TableColumn<EpisodeRow, String> indexCol = new TableColumn<>("序号");
        indexCol.setCellValueFactory(cd -> new ReadOnlyStringWrapper(cd.getValue().episode.index));
        indexCol.setPrefWidth(70);

        TableColumn<EpisodeRow, String> titleCol = new TableColumn<>("标题");
        titleCol.setCellValueFactory(cd ->
                new ReadOnlyStringWrapper(cd.getValue().episode.displayTitle()));

        TableColumn<EpisodeRow, String> durationCol = new TableColumn<>("时长");
        durationCol.setCellValueFactory(cd -> new ReadOnlyStringWrapper(
                StringUtils.formatDuration(cd.getValue().episode.duration)));
        durationCol.setPrefWidth(90);

        TableColumn<EpisodeRow, String> vipCol = new TableColumn<>("会员");
        vipCol.setCellValueFactory(cd -> new ReadOnlyStringWrapper(
                cd.getValue().episode.vipOnly ? "大会员" : ""));
        vipCol.setPrefWidth(70);

        episodeTable.getColumns().addAll(List.of(selCol, indexCol, titleCol, durationCol, vipCol));
    }

    private Tab buildDownloadTab() {
        Tab tab = new Tab("下载列表");
        tab.setClosable(false);
        tab.setContent(new DownloadView(getHostServices()));
        return tab;
    }

    private HBox buildStatusBar() {
        statusLabel.getStyleClass().add("status-text");
        HBox bar = new HBox(statusLabel);
        bar.getStyleClass().add("status-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    // ==================== 登录 ====================

    private void showLogin() {
        new LoginDialog(stage, uname -> {
            loginButton.setText(uname);
            statusLabel.setText("登录成功: " + uname);
        }).show();
    }

    private void refreshLoginState() {
        if (!CookieManager.hasLoginCookie()) {
            loginButton.setText("登录");
            return;
        }
        loginButton.setText("已登录");
        new Thread(() -> {
            try {
                JsonObject nav = BiliApi.nav();
                if (nav.get("code").getAsInt() == 0) {
                    String uname = nav.getAsJsonObject("data").get("uname").getAsString();
                    Platform.runLater(() -> loginButton.setText(uname));
                } else {
                    // Cookie 已失效
                    Platform.runLater(() -> loginButton.setText("登录"));
                }
            } catch (Exception ignored) {
            }
        }, "nav-check").start();
    }

    // ==================== 解析 ====================

    private void startParse() {
        String input = inputField.getText().trim();
        if (input.isEmpty()) {
            statusLabel.setText("请输入视频或番剧链接");
            return;
        }
        parseButton.setDisable(true);
        statusLabel.setText("正在解析…");
        new Thread(() -> {
            try {
                ParseResult result = ParseService.parse(input);
                Platform.runLater(() -> {
                    showResult(result);
                    statusLabel.setText("解析完成");
                });
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                Platform.runLater(() -> {
                    statusLabel.setText("解析失败: " + cause.getMessage());
                    Alert alert = new Alert(Alert.AlertType.ERROR,
                            "解析失败：" + cause.getMessage(), ButtonType.OK);
                    alert.setTitle("错误");
                    alert.setHeaderText(null);
                    alert.initOwner(stage);
                    alert.showAndWait();
                });
            } finally {
                Platform.runLater(() -> parseButton.setDisable(false));
            }
        }, "parse").start();
    }

    private void showResult(ParseResult result) {
        currentResult = result;
        titleLabel.setText(result.displayName());
        descLabel.setText(result.description == null ? "" : result.description);
        loadCover(result.coverUrl);

        episodeRows.setAll(result.episodes.stream().map(EpisodeRow::new).toList());

        // 画质下拉：优先接口返回的可用画质
        qualityCombo.getItems().setAll(
                result.qualities.isEmpty() ? List.of(DEFAULT_QUALITIES) : result.qualities);
        qualityCombo.getSelectionModel().select(result.qualities.isEmpty() ? 5 : 0);
    }

    private void loadCover(String url) {
        coverView.setImage(null);
        if (url == null || url.isEmpty()) {
            return;
        }
        new Thread(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(15_000);
                conn.setReadTimeout(15_000);
                conn.setRequestProperty("Referer", "https://www.bilibili.com");
                conn.setRequestProperty("User-Agent", WebClient.UA);
                byte[] bytes;
                try (InputStream in = conn.getInputStream()) {
                    bytes = in.readAllBytes();
                }
                Image image = new Image(new ByteArrayInputStream(bytes), 640, 0, true, true);
                Platform.runLater(() -> coverView.setImage(image));
            } catch (Exception ignored) {
            }
        }, "cover-load").start();
    }

    // ==================== 下载 ====================

    private void startDownload() {
        if (currentResult == null || episodeRows.isEmpty()) {
            statusLabel.setText("请先解析视频");
            return;
        }
        Quality quality = qualityCombo.getValue();
        int qn = quality == null ? 80 : quality.qn;

        List<EpisodeRow> selected = episodeRows.stream().filter(r -> r.selected.get()).toList();
        if (selected.isEmpty()) {
            statusLabel.setText("请至少选择一集");
            return;
        }

        Config config = Config.get();
        File baseDir = new File(config.downloadDir);
        // 番剧或多P视频按标题建子目录
        File dir = (currentResult.kind == ParseResult.Kind.BANGUMI || selected.size() > 1)
                ? new File(baseDir, StringUtils.sanitizeFileName(currentResult.displayName()))
                : baseDir;

        for (EpisodeRow row : selected) {
            Episode ep = row.episode;
            String fileName = StringUtils.sanitizeFileName(episodeFileName(ep)) + ".mp4";
            File output = new File(dir, fileName);
            DownloadManager.get().submit(new DownloadJob(ep,
                    currentResult.displayName() + " - " + episodeFileName(ep), output, qn));
        }
        statusLabel.setText("已添加 " + selected.size() + " 个下载任务");
        // 提交后自动切换到下载列表页，方便查看进度
        tabs.getSelectionModel().select(1);
    }

    private void setAllSelected(boolean selected) {
        episodeRows.forEach(r -> r.selected.set(selected));
    }

    /** 文件名主体：多P视频加序号前缀（如 1-视频）；单P与番剧保持原名（番剧 index 即标题） */
    private String episodeFileName(Episode ep) {
        if (currentResult.kind != ParseResult.Kind.BANGUMI && currentResult.episodes.size() > 1) {
            return ep.index + "-" + ep.title;
        }
        return currentResult.kind == ParseResult.Kind.BANGUMI ? ep.displayTitle() : ep.title;
    }

    private void checkFfmpeg() {
        new Thread(() -> {
            if (!isFfmpegAvailable(Config.get().ffmpegPath)) {
                Platform.runLater(() -> SettingsDialog.showMissingFfmpegHint(stage));
            }
        }, "ffmpeg-check").start();
    }

    private static boolean isFfmpegAvailable(String path) {
        try {
            Process p = new ProcessBuilder(path, "-version").redirectErrorStream(true).start();
            int exit = p.waitFor();
            return exit == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 剧集行模型 ====================

    /** 剧集表行：勾选状态 + 剧集数据 */
    public static class EpisodeRow {
        final Episode episode;
        final SimpleBooleanProperty selected = new SimpleBooleanProperty(true);

        EpisodeRow(Episode episode) {
            this.episode = episode;
        }
    }
}
