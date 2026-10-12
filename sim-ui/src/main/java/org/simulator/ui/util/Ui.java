package org.simulator.ui.util;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Small helpers shared by all views: background execution, dialogs and common controls. */
public final class Ui {

    private static final Logger log = LoggerFactory.getLogger(Ui.class);

    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ui-worker");
        t.setDaemon(true);
        return t;
    });

    private Ui() {
    }

    /** Runs {@code work} off the FX thread and delivers the result (or an error dialog) on the FX thread. */
    public static <T> void async(Node busy, Callable<T> work, Consumer<T> onSuccess) {
        if (busy != null) {
            busy.setDisable(true);
        }
        POOL.submit(() -> {
            try {
                T result = work.call();
                Platform.runLater(() -> {
                    enable(busy);
                    if (onSuccess != null) {
                        onSuccess.accept(result);
                    }
                });
            } catch (Throwable e) {
                Platform.runLater(() -> {
                    enable(busy);
                    error(e);
                });
            }
        });
    }

    public static void run(Runnable r) {
        POOL.submit(r);
    }

    private static void enable(Node n) {
        if (n != null) {
            n.setDisable(false);
        }
    }

    public static Throwable root(Throwable t) {
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    public static void error(Throwable t) {
        Throwable r = root(t);
        log.warn("UI operation failed: {}", r.toString(), r);
        error(r.getMessage() != null ? r.getMessage() : r.toString());
    }

    /**
     * Keeps alerts inside the visible screen: long messages go into a scrollable, wrapping label
     * limited to a fraction of the screen instead of growing the dialog beyond it.
     */
    private static void fitToScreen(Alert a, String message) {
        javafx.geometry.Rectangle2D screen = javafx.stage.Screen.getPrimary().getVisualBounds();
        double maxW = screen.getWidth() * 0.6;
        double maxH = screen.getHeight() * 0.6;
        javafx.scene.control.Label text = new javafx.scene.control.Label(message);
        text.setWrapText(true);
        text.setMaxWidth(Math.min(560, maxW) - 40);
        javafx.scene.control.ScrollPane sp = new javafx.scene.control.ScrollPane(text);
        sp.setFitToWidth(true);
        sp.setMaxHeight(maxH);
        sp.setStyle("-fx-background-color: transparent;");
        a.getDialogPane().setContent(sp);
        a.getDialogPane().setMaxWidth(maxW);
        a.getDialogPane().setMaxHeight(screen.getHeight() * 0.9);
        a.setResizable(true);
        styleDialog(a.getDialogPane());
    }

    /** Applies the application stylesheet (gradient header, rounded buttons) to a dialog. */
    public static void styleDialog(javafx.scene.control.DialogPane pane) {
        if (!pane.getStylesheets().contains(Icons.STYLESHEET)) {
            pane.getStylesheets().add(Icons.STYLESHEET);
        }
        for (ButtonType bt : pane.getButtonTypes()) {
            if (bt.getButtonData().isDefaultButton() && pane.lookupButton(bt) instanceof Button b
                    && !b.getStyleClass().contains("accent")) {
                b.getStyleClass().add("accent");
            }
        }
    }

    public static void error(String message) {
        Alert a = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        a.setHeaderText("Error");
        fitToScreen(a, message);
        a.showAndWait();
    }

    public static void info(String message) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
        a.setHeaderText(null);
        fitToScreen(a, message);
        a.showAndWait();
    }

    public static boolean confirm(String header, String message) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL);
        a.setHeaderText(header);
        fitToScreen(a, message);
        return a.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
    }

    public static Optional<String> prompt(String header, String defaultValue) {
        TextInputDialog d = new TextInputDialog(defaultValue);
        d.setHeaderText(header);
        styleDialog(d.getDialogPane());
        return d.showAndWait().map(String::trim).filter(s -> !s.isEmpty());
    }

    public static Button button(String text, Runnable action) {
        Button b = new Button(text);
        b.setOnAction(e -> action.run());
        Icons.decorate(b);
        return b;
    }

    public static TextArea code(boolean editable) {
        TextArea t = new TextArea();
        t.setEditable(editable);
        t.setWrapText(false);
        t.setStyle("-fx-font-family: 'Consolas', 'Monospaced'; -fx-font-size: 12px;");
        t.getStyleClass().add("code-area");
        return t;
    }

    public static TextField field(String prompt, String value) {
        TextField f = new TextField(value);
        f.setPromptText(prompt);
        return f;
    }

    /** Toolbar row: behaves like an HBox (spacers grow) but wraps instead of truncating on narrow windows. */
    public static WrapBox bar(Node... nodes) {
        WrapBox h = new WrapBox(8, 6, nodes);
        for (Node n : nodes) {
            if (n instanceof Label l && !l.getStyleClass().contains("section-title")) {
                l.setMinWidth(Region.USE_PREF_SIZE);
            }
        }
        h.setPadding(new Insets(6, 0, 6, 0));
        return h;
    }

    public static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    /** Builds a two column label/control form. */
    public static GridPane form(Object... labelControlPairs) {
        GridPane g = new GridPane();
        g.setHgap(8);
        g.setVgap(6);
        g.setPadding(new Insets(4));
        ColumnConstraints c0 = new ColumnConstraints();
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setHgrow(Priority.ALWAYS);
        g.getColumnConstraints().addAll(c0, c1);
        for (int i = 0; i + 1 < labelControlPairs.length; i += 2) {
            g.add(new Label(String.valueOf(labelControlPairs[i])), 0, i / 2);
            g.add((Node) labelControlPairs[i + 1], 1, i / 2);
        }
        return g;
    }

    public static Label title(String text) {
        Label l = new Label(text);
        Region accent = new Region();
        accent.getStyleClass().add("title-accent");
        l.setGraphic(accent);
        l.getStyleClass().add("section-title");
        return l;
    }

    /** Fills a table whose rows are string lists. */
    public static void fillRows(TableView<List<String>> table, List<String> columns, List<List<String>> rows) {
        table.getColumns().clear();
        for (int i = 0; i < columns.size(); i++) {
            final int idx = i;
            TableColumn<List<String>, String> c = new TableColumn<>(columns.get(i));
            c.setCellValueFactory(cd -> new ReadOnlyStringWrapper(idx < cd.getValue().size() ? cd.getValue().get(idx) : ""));
            c.setPrefWidth(Math.max(80, Math.min(260, columns.get(i).length() * 12)));
            table.getColumns().add(c);
        }
        table.getItems().setAll(rows);
    }

    /** Fills a table whose rows are column-name maps. */
    public static void fillMaps(TableView<Map<String, String>> table, List<String> columns, List<Map<String, String>> rows) {
        table.getColumns().clear();
        for (String col : columns) {
            TableColumn<Map<String, String>, String> c = new TableColumn<>(col);
            c.setCellValueFactory(cd -> new ReadOnlyStringWrapper(cd.getValue().getOrDefault(col, "")));
            c.setPrefWidth(Math.max(80, Math.min(260, col.length() * 12)));
            table.getColumns().add(c);
        }
        table.getItems().setAll(rows);
    }

    public static <S> TableColumn<S, String> column(String title, double width, java.util.function.Function<S, Object> getter) {
        TableColumn<S, String> c = new TableColumn<>(title);
        c.setCellValueFactory(cd -> {
            Object v = getter.apply(cd.getValue());
            return new ReadOnlyStringWrapper(v == null ? "" : String.valueOf(v));
        });
        c.setPrefWidth(width);
        return c;
    }

    public static void saveText(Window owner, String title, String defaultName, String content) {
        FileChooser fc = new FileChooser();
        fc.setTitle(title);
        fc.setInitialFileName(defaultName);
        File f = fc.showSaveDialog(owner);
        if (f != null) {
            try {
                Files.writeString(f.toPath(), content, StandardCharsets.UTF_8);
                info("Archivo guardado: " + f);
            } catch (Exception e) {
                error(e);
            }
        }
    }

    public static Optional<Path> chooseOpen(Window owner, String title) {
        FileChooser fc = new FileChooser();
        fc.setTitle(title);
        File f = fc.showOpenDialog(owner);
        return Optional.ofNullable(f).map(File::toPath);
    }

    public static Optional<Path> chooseSave(Window owner, String title, String defaultName) {
        FileChooser fc = new FileChooser();
        fc.setTitle(title);
        fc.setInitialFileName(defaultName);
        File f = fc.showSaveDialog(owner);
        return Optional.ofNullable(f).map(File::toPath);
    }

    public static Window window(Node n) {
        return n.getScene() != null ? n.getScene().getWindow() : null;
    }

    public static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    public static String pretty(Object value) {
        try {
            return org.simulator.common.json.Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    public static void shutdown() {
        POOL.shutdownNow();
    }
}
