package org.simulator.ui.view;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.simulator.regression.replay.RecordedTransaction;
import org.simulator.regression.replay.Recording;
import org.simulator.regression.replay.RecordingStore;
import org.simulator.regression.replay.ReplayEngine;
import org.simulator.regression.replay.ReplayReport;
import org.simulator.ui.AppContext;
import org.simulator.ui.util.Ui;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Records live traffic (acquirer outcomes + issuer requests) and replays it with the original timing. */
public class ReplayView implements View {

    private final AppContext ctx;
    private final BorderPane root = new BorderPane();
    private final TextField name = Ui.field("Nombre de la grabación", "grabacion");
    private final Label state = new Label();
    private final TableView<RecordedTransaction> items = new TableView<>();
    private final TableView<ReplayReport.Item> results = new TableView<>();
    private final TextField speed = Ui.field("x velocidad (0 = máx.)", "1.0");
    private final TextField sources = Ui.field("Orígenes (vacío = todos)", "");
    private final CheckBox refresh = new CheckBox("Regenerar STAN/fechas/RRN");
    private final ProgressBar progress = new ProgressBar(0);
    private final Label summary = new Label();
    private Recording recording;
    private ReplayReport lastReport;
    private ReplayEngine engine;

    public ReplayView(AppContext ctx) {
        this.ctx = ctx;
        refresh.setSelected(true);
        items.getColumns().setAll(java.util.List.of(
                Ui.<RecordedTransaction>column("Offset ms", 90, RecordedTransaction::offsetMs),
                Ui.<RecordedTransaction>column("Origen", 100, RecordedTransaction::source),
                Ui.<RecordedTransaction>column("MTI", 60, RecordedTransaction::mti),
                Ui.<RecordedTransaction>column("RC", 50, RecordedTransaction::responseRc),
                Ui.<RecordedTransaction>column("Estado", 100, RecordedTransaction::status),
                Ui.<RecordedTransaction>column("Latencia ms", 90, t -> t.latencyMicros() / 1000.0)));
        results.getColumns().setAll(java.util.List.of(
                Ui.<ReplayReport.Item>column("#", 50, ReplayReport.Item::index),
                Ui.<ReplayReport.Item>column("Origen", 90, ReplayReport.Item::source),
                Ui.<ReplayReport.Item>column("MTI", 60, ReplayReport.Item::mti),
                Ui.<ReplayReport.Item>column("STAN", 70, ReplayReport.Item::stan),
                Ui.<ReplayReport.Item>column("RC orig.", 70, ReplayReport.Item::originalRc),
                Ui.<ReplayReport.Item>column("RC replay", 70, ReplayReport.Item::replayRc),
                Ui.<ReplayReport.Item>column("OK", 50, i -> i.match() ? "✔" : "✘"),
                Ui.<ReplayReport.Item>column("Estado", 90, ReplayReport.Item::status),
                Ui.<ReplayReport.Item>column("Error", 220, ReplayReport.Item::error)));

        VBox top = new VBox(6,
                Ui.title("Grabación de tráfico"),
                Ui.bar(new Label("Nombre:"), name, Ui.button("● Grabar", this::startRecording),
                        Ui.button("■ Detener", this::stopRecording), state, Ui.spacer(),
                        Ui.button("Guardar (cifrado)", this::save), Ui.button("Abrir", this::open)),
                items,
                Ui.title("Replay"),
                Ui.bar(new Label("Velocidad:"), speed, new Label("Orígenes:"), sources, refresh,
                        Ui.button("▶ Reproducir", this::replay), Ui.button("Cancelar", () -> {
                            if (engine != null) {
                                engine.cancel();
                            }
                        }), progress),
                summary, results,
                Ui.bar(Ui.button("Guardar reporte HTML", () -> {
                    if (lastReport != null) {
                        Ui.saveText(Ui.window(root), "Reporte replay", "replay-report.html", lastReport.toHtml());
                    }
                })));
        VBox.setVgrow(items, Priority.ALWAYS);
        VBox.setVgrow(results, Priority.ALWAYS);
        sources.setPromptText("ACQUIRER,REGRESSION,ISSUER");
        root.setCenter(top);
        root.setPadding(new Insets(12));
        refreshState();
    }

    private void refreshState() {
        state.setText(ctx.recorder().isRecording() ? "GRABANDO (" + ctx.recorder().size() + " tx)"
                : recording == null ? "Sin grabación" : recording.getName() + ": " + recording.getItems().size() + " tx");
    }

    private void startRecording() {
        ctx.recorder().start(name.getText().trim().isEmpty() ? "grabacion" : name.getText().trim(), ctx.env().getName());
        refreshState();
    }

    private void stopRecording() {
        if (!ctx.recorder().isRecording()) {
            return;
        }
        recording = ctx.recorder().stop();
        items.getItems().setAll(recording.getItems());
        refreshState();
    }

    private void save() {
        if (recording == null) {
            Ui.error("No hay grabación para guardar");
            return;
        }
        if (!ctx.ensureUnlocked()) {
            Ui.error("Desbloquee la configuración: las grabaciones se cifran con la clave maestra");
            return;
        }
        Ui.chooseSave(Ui.window(root), "Guardar grabación", recording.getName() + ".simrec").ifPresent(p ->
                Ui.async(null, () -> {
                    RecordingStore.save(p, recording, ctx.config()::encryptSecret);
                    return p;
                }, x -> Ui.info("Grabación guardada en " + x)));
    }

    private void open() {
        if (!ctx.ensureUnlocked()) {
            Ui.error("Desbloquee la configuración para descifrar la grabación");
            return;
        }
        Ui.chooseOpen(Ui.window(root), "Abrir grabación").ifPresent(p ->
                Ui.async(null, () -> RecordingStore.load(p, ctx.config()::secret), r -> {
                    recording = r;
                    items.getItems().setAll(r.getItems());
                    refreshState();
                }));
    }

    private void replay() {
        if (recording == null || recording.getItems().isEmpty()) {
            Ui.error("Cargue o grabe tráfico primero");
            return;
        }
        if (!ctx.acquirer().isConnected()) {
            Ui.error("Conecte el adquirente (TCP o API) antes del replay");
            return;
        }
        double sp;
        try {
            sp = Double.parseDouble(speed.getText().trim());
        } catch (NumberFormatException e) {
            Ui.error("Velocidad inválida");
            return;
        }
        Set<String> src = Arrays.stream(sources.getText().split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).map(String::toUpperCase).collect(Collectors.toSet());
        engine = new ReplayEngine(ctx.acquirer());
        progress.setProgress(0);
        Recording rec = recording;
        boolean ref = refresh.isSelected();
        Ui.async(null, () -> engine.replay(rec, sp, ref, src,
                        (done, total) -> Ui.run(() -> progress.setProgress(total == 0 ? 1 : done / (double) total))),
                r -> {
                    lastReport = r;
                    results.getItems().setAll(r.items());
                    progress.setProgress(1);
                    summary.setText("Coincidencias RC: " + r.matches() + "/" + r.items().size()
                            + " | diferencias: " + r.mismatches());
                });
    }

    @Override
    public String title() {
        return "Grabación/Replay";
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public void onShow() {
        refreshState();
    }
}
