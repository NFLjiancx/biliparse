package com.biliparse.ui;

import com.biliparse.download.DownloadManager;
import com.biliparse.util.Config;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

import java.io.File;

/**
 * 设置对话框：下载目录、FFmpeg 路径、并发数
 */
public class SettingsDialog {

    public static void show(Window owner) {
        build(owner).showAndWait();
    }

    /** 构建配置好的对话框（自检模式下可 show() 非阻塞打开） */
    public static Dialog<ButtonType> build(Window owner) {
        Config config = Config.get();

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("设置");
        dialog.setHeaderText(null);
        dialog.setResizable(false);

        TextField dirField = new TextField(config.downloadDir);
        dirField.setPrefColumnCount(30);
        Button browseButton = new Button("浏览…");
        browseButton.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("选择下载目录");
            File current = new File(dirField.getText());
            if (current.isDirectory()) {
                chooser.setInitialDirectory(current);
            }
            File chosen = chooser.showDialog(dialog.getDialogPane().getScene().getWindow());
            if (chosen != null) {
                dirField.setText(chosen.getAbsolutePath());
            }
        });
        HBox dirBox = new HBox(6, dirField, browseButton);
        HBox.setHgrow(dirField, Priority.ALWAYS);

        TextField ffmpegField = new TextField(config.ffmpegPath);
        ffmpegField.setPrefColumnCount(30);
        Spinner<Integer> jobSpinner = new Spinner<>(1, 8, config.maxConcurrentJobs);
        jobSpinner.setEditable(true);
        jobSpinner.setPrefWidth(90);
        Spinner<Integer> segSpinner = new Spinner<>(1, 16, config.segmentsPerFile);
        segSpinner.setEditable(true);
        segSpinner.setPrefWidth(90);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(12);
        grid.setPadding(new Insets(15, 15, 5, 15));
        ColumnConstraints fieldCol = new ColumnConstraints();
        fieldCol.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(new ColumnConstraints(), fieldCol);
        grid.addRow(0, new Label("下载目录:"), dirBox);
        grid.addRow(1, new Label("FFmpeg 路径:"), ffmpegField);
        grid.addRow(2, new Label("同时下载任务数:"), jobSpinner);
        grid.addRow(3, new Label("单文件线程数:"), segSpinner);

        ButtonType saveType = new ButtonType("保存", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(grid);

        Button saveButton = (Button) dialog.getDialogPane().lookupButton(saveType);
        saveButton.getStyleClass().add("primary");
        // 保存时校验并写回配置
        saveButton.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            config.downloadDir = dirField.getText().trim();
            config.ffmpegPath = ffmpegField.getText().trim();
            if (config.ffmpegPath.isEmpty()) {
                config.ffmpegPath = "ffmpeg";
            }
            config.maxConcurrentJobs = jobSpinner.getValue();
            config.segmentsPerFile = segSpinner.getValue();
            config.save();
            DownloadManager.get().applyConcurrency();
        });

        return dialog;
    }

    public static void showMissingFfmpegHint(Window owner) {
        Alert alert = new Alert(Alert.AlertType.WARNING,
                "未检测到 FFmpeg，下载完成后将无法混流为 MP4。\n" +
                "请安装 FFmpeg（如 sudo apt install ffmpeg），\n" +
                "或在 菜单 设置 中指定 ffmpeg 可执行文件路径。",
                ButtonType.OK);
        alert.setTitle("提示");
        alert.setHeaderText(null);
        alert.initOwner(owner);
        alert.showAndWait();
    }
}
