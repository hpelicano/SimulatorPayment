package org.simulator.ui.view;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.simulator.acquirer.TransactionOutcome;
import org.simulator.acquirer.TransactionTemplate;
import org.simulator.common.json.Json;
import org.simulator.stress.LoadProfile;
import org.simulator.stress.SlaPolicy;
import org.simulator.stress.StressEngine;
import org.simulator.stress.StressResult;
import org.simulator.stress.StressSample;
import org.simulator.stress.StressTarget;
import org.simulator.stress.distributed.AgentRequest;
import org.simulator.stress.distributed.StressCoordinator;
import org.simulator.ui.AppContext;
import org.simulator.ui.util.Ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Stress / volume test: open-model load generator with TPS profiles, live charts and SLA based abort. */
public class StressView implements View {

    private final AppContext ctx;
    private final BorderPane root = new BorderPane();
    private final ChoiceBox<TransactionTemplate> template = new ChoiceBox<>();
    private final ChoiceBox<LoadProfile.Type> type = new ChoiceBox<>(FXCollections.observableArrayList(LoadProfile.Type.values()));
    private final TextField name = new TextField("stress");
    private final TextField targetTps = new TextField("50");
    private final TextField startTps = new TextField("1");
    private final TextField duration = new TextField("60");
    private final TextField rampSec = new TextField("30");
    private final TextField stepTps = new TextField("10");
    private final TextField stepEvery = new TextField("10");
    private final TextField spikeTps = new TextField("200");
    private final TextField spikeAt = new TextField("20");
    private final TextField spikeDur = new TextField("5");
    private final TextField maxInFlight = new TextField("500");
    private final TextField slaP99 = new TextField("0");
    private final TextField slaErr = new TextField("0");
    private final TextField slaGrace = new TextField("5");
    private final XYChart.Series<Number, Number> target = new XYChart.Series<>();
    private final XYChart.Series<Number, Number> achieved = new XYChart.Series<>();
    private final XYChart.Series<Number, Number> errors = new XYChart.Series<>();
    private final XYChart.Series<Number, Number> p99 = new XYChart.Series<>();
    private final XYChart.Series<Number, Number> p50 = new XYChart.Series<>();
    private final Label live = new Label();
    private final TextArea result = Ui.code(false);
    private final TextArea agents = new TextArea();
    private volatile StressCoordinator coordinator;
    private StressResult last;

    public StressView(AppContext ctx) {
        this.ctx = ctx;
        type.setValue(LoadProfile.Type.CONSTANT);
        target.setName("TPS objetivo");
        achieved.setName("TPS logrado");
        errors.setName("Errores+timeouts/s");
        p50.setName("P50 ms");
        p99.setName("P99 ms");

        GridPane form = Ui.form(
                "Nombre", name, "Plantilla", template, "Perfil", type, "TPS objetivo", targetTps,
                "TPS inicial (RAMP/STEP)", startTps, "Duración (s)", duration, "Rampa (s)", rampSec,
                "Incremento STEP (TPS)", stepTps, "Cada (s)", stepEvery, "TPS pico (SPIKE)", spikeTps,
                "Pico en (s)", spikeAt, "Duración pico (s)", spikeDur, "Máx. en vuelo", maxInFlight,
                "SLA P99 máx ms (0=off)", slaP99, "SLA % error máx (0=off)", slaErr, "SLA gracia (s)", slaGrace);
        form.setPrefWidth(330);

        var start = Ui.button("Iniciar", this::start);
        agents.setPromptText("http://host:7700@token (uno por línea)");
        agents.setPrefRowCount(3);
        VBox left = new VBox(8, form, Ui.bar(start, Ui.button("Detener", () -> {
                    ctx.stressEngine().stop();
                    StressCoordinator c = coordinator;
                    if (c != null) {
                        c.stop();
                    }
                }),
                Ui.button("Exportar CSV", () -> {
                    if (last != null) {
                        Ui.saveText(Ui.window(root), "Exportar resultado", last.name() + ".csv", last.toCsv());
                    }
                })),
                Ui.title("Distribuido (agentes)"), agents,
                Ui.bar(Ui.button("Health", this::health), Ui.button("Ejecutar distribuido", this::startDistributed)),
                new Label("Resultado"), result);
        VBox.setVgrow(result, Priority.ALWAYS);

        LineChart<Number, Number> tpsChart = chart("Throughput (TPS)", "TPS");
        tpsChart.getData().addAll(List.of(target, achieved, errors));
        LineChart<Number, Number> latChart = chart("Latencia", "ms");
        latChart.getData().addAll(List.of(p50, p99));
        VBox charts = new VBox(6, live, tpsChart, latChart);
        VBox.setVgrow(tpsChart, Priority.ALWAYS);
        VBox.setVgrow(latChart, Priority.ALWAYS);

        root.setTop(Ui.bar(Ui.title("Stress test (TPS)"), Ui.spacer(),
                new Label("Tip: use el simulador de Emisión como destino para calibrar el generador.")));
        root.setLeft(left);
        root.setCenter(charts);
        BorderPane.setMargin(charts, new Insets(0, 0, 0, 12));
        root.setPadding(new Insets(12));
    }

    private static LineChart<Number, Number> chart(String title, String yLabel) {
        NumberAxis x = new NumberAxis();
        x.setLabel("segundo");
        NumberAxis y = new NumberAxis();
        y.setLabel(yLabel);
        LineChart<Number, Number> c = new LineChart<>(x, y);
        c.setTitle(title);
        c.setAnimated(false);
        c.setCreateSymbols(false);
        return c;
    }

    private LoadProfile profile() {
        return new LoadProfile().setName(name.getText().trim()).setType(type.getValue())
                .setTargetTps(d(targetTps)).setStartTps(d(startTps)).setDurationSec(i(duration)).setRampSec(i(rampSec))
                .setStepTps(d(stepTps)).setStepEverySec(i(stepEvery)).setSpikeTps(d(spikeTps)).setSpikeAtSec(i(spikeAt))
                .setSpikeDurationSec(i(spikeDur)).setMaxInFlight(i(maxInFlight));
    }

    private static double d(TextField f) {
        return Double.parseDouble(f.getText().trim().replace(',', '.'));
    }

    private static int i(TextField f) {
        return Integer.parseInt(f.getText().trim());
    }

    private void start() {
        StressEngine engine = ctx.stressEngine();
        if (engine.isRunning()) {
            Ui.error("Ya hay una prueba en ejecución");
            return;
        }
        TransactionTemplate tpl = template.getValue();
        if (tpl == null) {
            Ui.error("Seleccione una plantilla");
            return;
        }
        if (!ctx.acquirer().isConnected()) {
            Ui.error("Conecte el canal en la vista Adquirencia antes de iniciar la prueba");
            return;
        }
        LoadProfile profile;
        SlaPolicy sla;
        try {
            profile = profile();
            sla = new SlaPolicy().setMaxP99Ms(d(slaP99)).setMaxErrorRatePct(d(slaErr)).setGraceSec(i(slaGrace));
        } catch (NumberFormatException e) {
            Ui.error("Parámetros inválidos: " + e.getMessage());
            return;
        }
        for (var s : List.of(target, achieved, errors, p50, p99)) {
            s.getData().clear();
        }
        result.clear();
        StressTarget fire = seq -> ctx.acquirer().send(tpl, tpl.getVariables(), "STRESS").thenApply(TransactionOutcome::status);
        String env = ctx.env().getName();
        Ui.async(null, () -> {
            long runId = ctx.runs().startStress(profile.getName(), env, Json.write(profile));
            List<Object[]> rows = new ArrayList<>();
            ctx.acquirer().setLogPayloads(false);
            StressResult r;
            try {
                r = engine.run(profile, sla, fire, new StressEngine.Listener() {
                    @Override
                    public void onSample(StressSample s) {
                        rows.add(s.toRow());
                        Platform.runLater(() -> plot(s));
                    }
                });
            } finally {
                ctx.acquirer().setLogPayloads(true);
            }
            ctx.runs().addStressSamples(runId, rows);
            ctx.runs().finishStress(runId, r.sent(), r.approved(), r.declined(), r.timeouts(), r.errors(),
                    r.achievedTps(), r.p50Ms(), r.p95Ms(), r.p99Ms(), r.maxMs(), r.status());
            return r;
        }, this::showResult);
    }

    private void health() {
        List<StressCoordinator.Agent> list;
        try {
            list = StressCoordinator.parse(agents.getText());
        } catch (Exception e) {
            Ui.error(e);
            return;
        }
        if (list.isEmpty()) {
            Ui.error("Defina al menos un agente");
            return;
        }
        Ui.async(null, () -> new StressCoordinator(list).health(), m -> Ui.info(m.entrySet().stream()
                .map(e -> (e.getValue() ? "✔ " : "✘ ") + e.getKey()).collect(java.util.stream.Collectors.joining("\n"))));
    }

    private void startDistributed() {
        if (coordinator != null) {
            Ui.error("Ya hay una prueba distribuida en ejecución");
            return;
        }
        TransactionTemplate tpl = template.getValue();
        if (tpl == null) {
            Ui.error("Seleccione una plantilla");
            return;
        }
        AgentRequest req;
        List<StressCoordinator.Agent> list;
        try {
            list = StressCoordinator.parse(agents.getText());
            req = new AgentRequest().setProfile(profile()).setTemplateId(tpl.getId())
                    .setVariables(new java.util.LinkedHashMap<>(tpl.getVariables()))
                    .setSla(new SlaPolicy().setMaxP99Ms(d(slaP99)).setMaxErrorRatePct(d(slaErr)).setGraceSec(i(slaGrace)));
        } catch (Exception e) {
            Ui.error("Parámetros inválidos: " + Ui.root(e).getMessage());
            return;
        }
        if (list.isEmpty()) {
            Ui.error("Defina al menos un agente");
            return;
        }
        for (var s : List.of(target, achieved, errors, p50, p99)) {
            s.getData().clear();
        }
        result.clear();
        StressCoordinator c = new StressCoordinator(list);
        coordinator = c;
        String env = ctx.env().getName();
        Ui.async(null, () -> {
            try {
                long runId = ctx.runs().startStress(req.getProfile().getName() + " [x" + list.size() + " agentes]", env, Json.write(req.getProfile()));
                List<Object[]> rows = new ArrayList<>();
                StressResult r = c.run(req, s -> {
                    rows.add(s.toRow());
                    Platform.runLater(() -> plot(s));
                });
                ctx.runs().addStressSamples(runId, rows);
                ctx.runs().finishStress(runId, r.sent(), r.approved(), r.declined(), r.timeouts(), r.errors(),
                        r.achievedTps(), r.p50Ms(), r.p95Ms(), r.p99Ms(), r.maxMs(), r.status());
                return r;
            } finally {
                coordinator = null;
            }
        }, this::showResult);
    }

    private void plot(StressSample s) {
        target.getData().add(new XYChart.Data<>(s.second(), s.targetTps()));
        achieved.getData().add(new XYChart.Data<>(s.second(), s.completed()));
        errors.getData().add(new XYChart.Data<>(s.second(), s.timeouts() + s.errors()));
        p50.getData().add(new XYChart.Data<>(s.second(), s.p50Ms()));
        p99.getData().add(new XYChart.Data<>(s.second(), s.p99Ms()));
        live.setText(String.format("t=%ds | objetivo %.1f TPS | enviados %d | completados %d | en vuelo %d | throttled %d | "
                        + "P99 %.1f ms | error %.2f%%", s.second(), s.targetTps(), s.sent(), s.completed(), s.inFlight(),
                s.throttled(), s.p99Ms(), s.errorRatePct()));
    }

    private void showResult(StressResult r) {
        last = r;
        result.setText(String.format("""
                        Estado:        %s %s
                        Duración:      %.1f s
                        Enviadas:      %d  (throttled %d)
                        Aprobadas:     %d
                        Rechazadas:    %d
                        Timeouts:      %d
                        Errores:       %d
                        TPS logrado:   %.2f
                        P50/P95/P99:   %.2f / %.2f / %.2f ms
                        Máx:           %.2f ms""",
                r.status(), r.abortReason() == null ? "" : "(" + r.abortReason() + ")", r.durationSec(), r.sent(),
                r.throttled(), r.approved(), r.declined(), r.timeouts(), r.errors(), r.achievedTps(),
                r.p50Ms(), r.p95Ms(), r.p99Ms(), r.maxMs()));
    }

    @Override
    public String title() {
        return "Stress";
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public void onShow() {
        TransactionTemplate sel = template.getValue();
        List<TransactionTemplate> l = new ArrayList<>(ctx.templates().all());
        l.sort(Comparator.comparing(TransactionTemplate::getName));
        template.getItems().setAll(l);
        template.setValue(sel != null && l.contains(sel) ? sel : l.stream()
                .filter(t -> "purchase".equals(t.getId())).findFirst().orElse(l.isEmpty() ? null : l.get(0)));
    }

    @Override
    public void dispose() {
        ctx.stressEngine().stop();
    }
}
