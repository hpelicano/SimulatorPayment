package org.simulator.ui.view;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.simulator.common.json.Json;
import org.simulator.iso.IsoMessage;
import org.simulator.iso.MessageService;
import org.simulator.ui.util.Ui;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Captures every message of a channel (direction, port, MTI, STAN, RC and JSON with plain PAN) and exports it
 * to a text file to document test sessions. {@link #log} and {@link #event} are thread-safe.
 */
public class MessageLogPane extends VBox {

    private static final int MAX_ENTRIES = 5000;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter FILE = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    /** One captured message or channel event ({@code json} is null for events). */
    public record Entry(Instant at, String direction, String channel, String mti, String stan, String processingCode,
                        String responseCode, String note, String json) {
    }

    private final MessageService messages;
    private final String name;
    private final TableView<Entry> table = new TableView<>();
    private final TextArea detail = Ui.code(false);
    private final Label count = new Label("0 mensajes");
    private final ConcurrentLinkedQueue<Entry> pending = new ConcurrentLinkedQueue<>();
    private final Timeline timer;

    public MessageLogPane(MessageService messages, String name, String title) {
        super(4);
        this.messages = messages;
        this.name = name;
        table.getColumns().addAll(List.of(
                Ui.column("Hora", 90, (Entry e) -> TIME.format(e.at())),
                Ui.column("Dir", 45, Entry::direction),
                Ui.column("Canal", 105, Entry::channel),
                Ui.column("MTI", 45, Entry::mti),
                Ui.column("STAN", 60, Entry::stan),
                Ui.column("PC", 60, Entry::processingCode),
                Ui.column("RC", 35, Entry::responseCode),
                Ui.column("Nota", 200, Entry::note)));
        table.setFixedCellSize(24);
        table.setPlaceholder(new Label("Sin mensajes capturados"));
        table.getSelectionModel().selectedItemProperty().addListener((o, a, e) -> showDetail(e));

        SplitPane split = new SplitPane(table, detail);
        split.setDividerPositions(0.62);
        VBox.setVgrow(split, Priority.ALWAYS);
        getChildren().addAll(Ui.bar(new Label(title), Ui.spacer(), count,
                Ui.button("Limpiar", this::clear),
                Ui.button("Exportar log", this::export)), split);

        timer = new Timeline(new KeyFrame(Duration.millis(300), e -> drain()));
        timer.setCycleCount(Timeline.INDEFINITE);
        timer.play();
    }

    /** Captures plain PAN while redacting track details and PIN before the message can change. */
    public void log(String direction, String channel, IsoMessage msg, String note) {
        if (msg == null) {
            event(direction, channel, note);
            return;
        }
        String json;
        try {
            json = messages.toJson(msg.redactedCopy());
        } catch (RuntimeException e) {
            json = "{\"error\":\"" + e.getMessage() + "\"}";
        }
        pending.add(new Entry(Instant.now(), direction, channel, msg.getMti(), msg.get(11), msg.get(3), msg.get(39),
                note == null ? "" : note, json));
    }

    /** Records a channel event without message (connection, timeout, routing error...). */
    public void event(String direction, String channel, String note) {
        pending.add(new Entry(Instant.now(), direction, channel, "", "", "", "", note == null ? "" : note, null));
    }

    public List<Entry> entries() {
        drain();
        return List.copyOf(table.getItems());
    }

    private void drain() {
        List<Entry> batch = new ArrayList<>();
        Entry e;
        while ((e = pending.poll()) != null) {
            batch.add(e);
        }
        if (batch.isEmpty()) {
            return;
        }
        table.getItems().addAll(batch);
        if (table.getItems().size() > MAX_ENTRIES) {
            table.getItems().remove(0, table.getItems().size() - MAX_ENTRIES);
        }
        count.setText(table.getItems().size() + " mensajes");
        if (table.getSelectionModel().isEmpty()) {
            table.scrollTo(table.getItems().size() - 1);
        }
    }

    private void showDetail(Entry e) {
        if (e == null) {
            detail.clear();
        } else if (e.json() == null) {
            detail.setText(e.note());
        } else {
            detail.setText(pretty(e.json()));
        }
    }

    private void clear() {
        pending.clear();
        table.getItems().clear();
        detail.clear();
        count.setText("0 mensajes");
    }

    private void export() {
        drain();
        if (table.getItems().isEmpty()) {
            Ui.info("No hay mensajes para exportar");
            return;
        }
        Ui.saveText(getScene() == null ? null : getScene().getWindow(), "Exportar log de mensajes",
                name + "-" + FILE.format(Instant.now()) + ".log", render(table.getItems()));
    }

    /** Plain-text document with one block per message (plain PAN, redacted non-PAN sensitive data). */
    static String render(List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Log de mensajería - generado ").append(STAMP.format(Instant.now()))
                .append(" - ").append(entries.size()).append(" entradas\n");
        sb.append("# PAN en texto plano; PIN y datos sensibles de track ocultos.\n\n");
        for (Entry e : entries) {
            sb.append('[').append(STAMP.format(e.at())).append("] ")
                    .append(e.direction()).append(' ').append(e.channel());
            if (!e.mti().isEmpty()) {
                sb.append(" MTI=").append(e.mti());
            }
            append(sb, "STAN", e.stan());
            append(sb, "PC", e.processingCode());
            append(sb, "RC", e.responseCode());
            if (!e.note().isEmpty()) {
                sb.append(" | ").append(e.note());
            }
            sb.append('\n');
            if (e.json() != null) {
                sb.append(pretty(e.json())).append('\n');
            }
            sb.append("--------------------------------------------------------------------------------\n");
        }
        return sb.toString();
    }

    private static void append(StringBuilder sb, String label, String value) {
        if (value != null && !value.isEmpty()) {
            sb.append(' ').append(label).append('=').append(value);
        }
    }

    private static String pretty(String json) {
        try {
            return Ui.pretty(Json.mapper().readTree(json));
        } catch (Exception ex) {
            return json;
        }
    }

    public void dispose() {
        timer.stop();
    }
}
