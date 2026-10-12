package org.simulator.ui.view;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.util.Duration;
import org.simulator.ui.util.Ui;
import org.simulator.ui.util.UiLogAppender;
import org.simulator.ui.util.UiLogAppender.Line;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/** In-app log viewer fed by {@link UiLogAppender}. */
public class LogsView implements View {

    private static final int MAX = 5000;
    private static final List<String> LEVELS = List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR");

    private final BorderPane root = new BorderPane();
    private final ListView<Line> list = new ListView<>();
    private final TextField filter = new TextField();
    private final ChoiceBox<String> level = new ChoiceBox<>(FXCollections.observableArrayList(LEVELS));
    private final CheckBox follow = new CheckBox("Seguir");
    private final ConcurrentLinkedQueue<Line> pending = new ConcurrentLinkedQueue<>();
    private final Consumer<Line> listener = pending::add;
    private final Timeline timer;

    public LogsView() {
        level.setValue("INFO");
        follow.setSelected(true);
        filter.setPromptText("filtro");
        list.setStyle("-fx-font-family: 'Consolas', 'Monospaced'; -fx-font-size: 12px;");
        list.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(Line item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.toString());
                setStyle(empty || item == null ? "" : switch (item.level()) {
                    case "ERROR" -> "-fx-text-fill: -color-danger-fg;";
                    case "WARN" -> "-fx-text-fill: -color-warning-fg;";
                    default -> "";
                });
            }
        });
        filter.textProperty().addListener((o, a, b) -> reload());
        level.valueProperty().addListener((o, a, b) -> reload());
        root.setTop(Ui.bar(Ui.title("Logs"), Ui.spacer(), new Label("Nivel mínimo:"), level, filter, follow,
                Ui.button("Limpiar", () -> {
                    UiLogAppender.clear();
                    list.getItems().clear();
                })));
        root.setCenter(list);
        root.setPadding(new Insets(12));
        UiLogAppender.addListener(listener);
        reload();
        timer = new Timeline(new KeyFrame(Duration.millis(400), e -> drain()));
        timer.setCycleCount(Timeline.INDEFINITE);
        timer.play();
    }

    private boolean accept(Line l) {
        if (LEVELS.indexOf(l.level()) < LEVELS.indexOf(level.getValue())) {
            return false;
        }
        String f = filter.getText();
        return f == null || f.isBlank() || l.toString().toLowerCase().contains(f.toLowerCase());
    }

    private void reload() {
        pending.clear();
        list.getItems().setAll(UiLogAppender.snapshot().stream().filter(this::accept).toList());
        scroll();
    }

    private void drain() {
        List<Line> batch = new ArrayList<>();
        Line l;
        while ((l = pending.poll()) != null) {
            if (accept(l)) {
                batch.add(l);
            }
        }
        if (!batch.isEmpty()) {
            list.getItems().addAll(batch);
            if (list.getItems().size() > MAX) {
                list.getItems().remove(0, list.getItems().size() - MAX);
            }
            scroll();
        }
    }

    private void scroll() {
        if (follow.isSelected() && !list.getItems().isEmpty()) {
            list.scrollTo(list.getItems().size() - 1);
        }
    }

    @Override
    public String title() {
        return "Logs";
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public void dispose() {
        timer.stop();
        UiLogAppender.removeListener(listener);
    }
}
