package com.biliparse.ui;

import com.biliparse.download.DownloadJob;
import com.biliparse.download.DownloadManager;
import com.biliparse.util.Config;
import com.biliparse.util.StringUtils;
import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 下载列表页：任务进度、速度、取消与清理。
 * 采用属性驱动：每个任务对应一个 JobRow（JavaFX 属性），
 * 仅当数值变化时更新对应单元格，不做全表刷新，避免进度条抖动。
 */
public class DownloadView extends VBox {

    private final DownloadManager manager = DownloadManager.get();
    private final TableView<JobRow> table = new TableView<>();
    private final Label summaryLabel = new Label();
    private final Map<DownloadJob, JobRow> rowMap = new HashMap<>();

    public DownloadView(HostServices hostServices) {
        getStyleClass().add("download-view");
        setPadding(new Insets(12));
        setSpacing(12);

        buildTable();

        Button cancelButton = new Button("取消选中任务");
        cancelButton.setOnAction(e -> {
            JobRow row = table.getSelectionModel().getSelectedItem();
            if (row != null) {
                DownloadJob job = row.job;
                if (job.state == DownloadJob.State.QUEUED || job.state == DownloadJob.State.FETCHING
                        || job.state == DownloadJob.State.DOWNLOADING || job.state == DownloadJob.State.MERGING) {
                    job.requestCancel();
                }
            }
        });
        Button clearButton = new Button("清除已完成");
        clearButton.setOnAction(e -> {
            manager.removeFinished();
            syncJobs();
        });
        Button openDirButton = new Button("打开下载目录");
        openDirButton.setOnAction(e -> {
            File dir = new File(Config.get().downloadDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            hostServices.showDocument(dir.toURI().toString());
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        summaryLabel.getStyleClass().add("status-text");
        HBox actionBar = new HBox(8, cancelButton, clearButton, openDirButton, spacer, summaryLabel);
        actionBar.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(table, actionBar);
        VBox.setVgrow(table, Priority.ALWAYS);

        syncJobs();
        manager.addListener(job -> Platform.runLater(this::syncJobs));

        // 低频采样（1 秒）：只把变化的属性写回 UI，单元格按需更新，不做全表刷新
        Timeline timer = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            manager.tick();
            updateRows();
            updateSummary();
        }));
        timer.setCycleCount(Animation.INDEFINITE);
        timer.play();
    }

    private void buildTable() {
        table.setPlaceholder(new Label("暂无下载任务，解析视频后点击「下载所选」开始"));
        table.getStyleClass().add("flat-table");

        TableColumn<JobRow, String> titleCol = new TableColumn<>("标题");
        titleCol.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().title));
        titleCol.setPrefWidth(360);

        TableColumn<JobRow, String> sizeCol = new TableColumn<>("大小");
        sizeCol.setCellValueFactory(cd -> cd.getValue().sizeText);
        sizeCol.setPrefWidth(120);

        TableColumn<JobRow, Double> progressCol = new TableColumn<>("进度");
        progressCol.setCellValueFactory(cd -> cd.getValue().progress.asObject());
        progressCol.setPrefWidth(190);
        progressCol.setCellFactory(col -> new ProgressCell());

        TableColumn<JobRow, String> speedCol = new TableColumn<>("速度");
        speedCol.setCellValueFactory(cd -> cd.getValue().speedText);
        speedCol.setPrefWidth(90);

        TableColumn<JobRow, String> stateCol = new TableColumn<>("状态");
        stateCol.setCellValueFactory(cd -> cd.getValue().stateText);
        stateCol.setPrefWidth(150);

        table.getColumns().addAll(List.of(titleCol, sizeCol, progressCol, speedCol, stateCol));
    }

    // ==================== 数据同步 ====================

    /** 任务增删时重建行列表（复用同一任务的 JobRow，保持单元格状态） */
    private void syncJobs() {
        List<JobRow> rows = new ArrayList<>();
        for (DownloadJob job : manager.jobs()) {
            rows.add(rowMap.computeIfAbsent(job, JobRow::new));
        }
        rowMap.keySet().retainAll(manager.jobs());
        table.getItems().setAll(rows);
        updateRows();
        updateSummary();
    }

    /** 把下载线程写入的最新数据搬运到 UI 属性，仅在变化时赋值 */
    private void updateRows() {
        for (JobRow row : table.getItems()) {
            DownloadJob job = row.job;
            double p = progressValue(job);
            if (row.progress.get() != p) {
                row.progress.set(p);
            }
            String size = sizeText(job);
            if (!size.equals(row.sizeText.get())) {
                row.sizeText.set(size);
            }
            String speed = job.state == DownloadJob.State.DOWNLOADING && job.speed > 0
                    ? StringUtils.formatBytes(job.speed) + "/s" : "";
            if (!speed.equals(row.speedText.get())) {
                row.speedText.set(speed);
            }
            String state = stateText(job);
            if (!state.equals(row.stateText.get())) {
                row.stateText.set(state);
            }
        }
    }

    private void updateSummary() {
        long totalSpeed = manager.jobs().stream()
                .filter(j -> j.state == DownloadJob.State.DOWNLOADING)
                .mapToLong(j -> j.speed).sum();
        long active = manager.jobs().stream()
                .filter(j -> j.state == DownloadJob.State.QUEUED || j.state == DownloadJob.State.FETCHING
                        || j.state == DownloadJob.State.DOWNLOADING || j.state == DownloadJob.State.MERGING)
                .count();
        long done = manager.jobs().stream()
                .filter(j -> j.state == DownloadJob.State.DONE).count();
        StringBuilder summary = new StringBuilder();
        if (active > 0) {
            summary.append("进行中 ").append(active);
            if (totalSpeed > 0) {
                summary.append(" · 总速度 ").append(StringUtils.formatBytes(totalSpeed)).append("/s");
            }
        }
        if (done > 0) {
            if (!summary.isEmpty()) {
                summary.append(" · ");
            }
            summary.append("已完成 ").append(done);
        }
        summaryLabel.setText(summary.toString());
    }

    private static String sizeText(DownloadJob job) {
        return job.totalBytes > 0
                ? StringUtils.formatBytes(job.downloaded.get()) + " / " + StringUtils.formatBytes(job.totalBytes)
                : StringUtils.formatBytes(job.downloaded.get());
    }

    private static String stateText(DownloadJob job) {
        return switch (job.state) {
            case QUEUED -> "排队中";
            case FETCHING -> "获取流地址";
            case DOWNLOADING -> "下载中";
            case MERGING -> "混流中";
            case DONE -> "已完成";
            case FAILED -> "失败: " + (job.errorMessage == null ? "" : job.errorMessage);
            case CANCELLED -> "已取消";
        };
    }

    /** 进度列取值：正常为 0~1；排队为 0（静态空条）；-1 获取中，-2 混流中（滚动动画） */
    private static double progressValue(DownloadJob job) {
        return switch (job.state) {
            case QUEUED -> 0.0;
            case FETCHING -> -1.0;
            case MERGING -> -2.0;
            default -> job.progress();
        };
    }

    // ==================== 行模型 ====================

    /** 任务行：持有 JavaFX 属性，属性变化时仅对应单元格自动更新 */
    public static class JobRow {
        final DownloadJob job;
        final String title;
        final StringProperty sizeText = new SimpleStringProperty("");
        final DoubleProperty progress = new SimpleDoubleProperty(0);
        final StringProperty speedText = new SimpleStringProperty("");
        final StringProperty stateText = new SimpleStringProperty("");

        JobRow(DownloadJob job) {
            this.job = job;
            this.title = job.title;
        }
    }

    // ==================== 进度单元格 ====================

    /**
     * 进度单元格：进度条 + 百分比文字。
     * 每秒采样一次目标进度，用补间动画线性推进到目标（时长略长于采样间隔，
     * 保证两次采样之间进度条始终在平滑移动）；状态切换时直接重置不拖沓。
     */
    private static class ProgressCell extends TableCell<JobRow, Double> {
        private final ProgressBar bar = new ProgressBar(0);
        private final Label text = new Label();
        private final StackPane pane = new StackPane(bar, text);
        /** 渲染模式：0=确定进度 1=准备中(不定态) 2=混流中(不定态) 3=排队中(静态空条) -1=未初始化 */
        private int mode = -1;
        private double lastTarget = Double.NaN;
        private JobRow boundRow;
        private Timeline animator;

        ProgressCell() {
            bar.setMaxWidth(Double.MAX_VALUE);
            bar.getStyleClass().add("download-bar");
            text.getStyleClass().add("progress-text");
        }

        @Override
        protected void updateItem(Double progress, boolean empty) {
            super.updateItem(progress, empty);
            JobRow row = getTableRow() == null ? null : getTableRow().getItem();
            if (empty || row == null) {
                setGraphic(null);
                stopAnimation();
                return;
            }
            if (row != boundRow) {
                // 单元格换绑到另一个任务：完整重绘（同一任务不重置，避免动画重启）
                boundRow = row;
                mode = -1;
                lastTarget = Double.NaN;
                stopAnimation();
                bar.setProgress(0);
            }
            double p = progress == null ? 0 : progress;
            if (row.job.state == DownloadJob.State.QUEUED) {
                // 排队任务：静态空进度条，不挂动画
                if (mode != 3) {
                    stopAnimation();
                    bar.setProgress(0);
                    text.setText("");
                    mode = 3;
                }
            } else if (p < 0) {
                int m = p < -1.5 ? 2 : 1;
                if (mode != m) {
                    // 仅在进入不定态时设置一次，重复 setProgress(-1) 会重启滚动动画
                    stopAnimation();
                    bar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                    text.setText(m == 2 ? "混流中…" : "准备中…");
                    mode = m;
                }
            } else if (mode != 0 || row.job.state == DownloadJob.State.DONE) {
                // 新进入确定进度模式或已完成：直接定位，不拖沓
                stopAnimation();
                bar.setProgress(p);
                text.setText(String.format("%.1f%%", p * 100));
                mode = 0;
                lastTarget = p;
            } else if (p != lastTarget) {
                // 采样到新目标：从当前位置平滑推进（动画时长略长于 1 秒采样间隔，保证连续移动）
                lastTarget = p;
                text.setText(String.format("%.1f%%", p * 100));
                animateTo(p);
            }
            setGraphic(pane);
        }

        private void animateTo(double target) {
            if (animator != null) {
                animator.stop();
            }
            animator = new Timeline(new KeyFrame(Duration.millis(1200),
                    new KeyValue(bar.progressProperty(), target, Interpolator.LINEAR)));
            animator.play();
        }

        private void stopAnimation() {
            if (animator != null) {
                animator.stop();
                animator = null;
            }
        }
    }
}
