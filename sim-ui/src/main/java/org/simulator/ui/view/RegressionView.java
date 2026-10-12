package org.simulator.ui.view;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Spinner;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.simulator.iso.IsoMessage;
import org.simulator.persistence.repo.TestCaseRecord;
import org.simulator.persistence.repo.RunRepository.RegressionRunRecord;
import org.simulator.persistence.repo.TestCaseRepository.SuiteRecord;
import org.simulator.regression.CaseResult;
import org.simulator.regression.RegressionOptions;
import org.simulator.regression.RegressionReport;
import org.simulator.regression.RegressionRunner;
import org.simulator.ui.AppContext;
import org.simulator.ui.util.Ui;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Regression: suites of test cases stored in H2, executed against Base24 and compared with the expected result. */
public class RegressionView implements View {

    private final AppContext ctx;
    private final BorderPane root = new BorderPane();
    private final ListView<SuiteRecord> suites = new ListView<>();
    private final TableView<TestCaseRecord> cases = new TableView<>();
    private final TextArea caseJson = Ui.code(true);
    private final TextField expectedRc = new TextField();
    private final TableView<CaseResult> results = new TableView<>();
    private final TableView<RegressionRunRecord> history = new TableView<>();
    private final TextArea detail = Ui.code(false);
    private final ProgressBar progress = new ProgressBar(0);
    private final Label summary = new Label();
    private final Spinner<Integer> concurrency = new Spinner<>(1, 64, 1);
    private final TextField delay = new TextField("0");
    private final TextField ignore = new TextField("7,11,12,13,37,38");
    private final TextField tags = new TextField();
    private final CheckBox refresh = new CheckBox("Refrescar campos dinámicos (STAN/RRN/fechas)");
    private final CheckBox stopFirst = new CheckBox("Detener en primer fallo");
    private volatile RegressionRunner runner;
    private RegressionReport lastReport;

    public RegressionView(AppContext ctx) {
        this.ctx = ctx;
        refresh.setSelected(true);
        tags.setPromptText("tag (opcional)");
        delay.setPrefColumnCount(5);
        expectedRc.setPrefColumnCount(4);

        suites.getSelectionModel().selectedItemProperty().addListener((o, a, s) -> loadCases());
        VBox left = new VBox(6, new Label("Suites"), suites, Ui.bar(
                Ui.button("Nueva", () -> Ui.prompt("Nombre de la suite", "").ifPresent(n -> {
                    ctx.testCases().createSuite(n, null);
                    loadSuites();
                })),
                Ui.button("Eliminar", this::deleteSuite),
                Ui.button("Refrescar", this::loadSuites)));
        VBox.setVgrow(suites, Priority.ALWAYS);
        left.setPrefWidth(230);

        cases.getColumns().addAll(List.of(
                Ui.column("Id", 50, TestCaseRecord::id), Ui.column("Nombre", 220, TestCaseRecord::name),
                Ui.column("Formato", 90, TestCaseRecord::dialect), Ui.column("RC esp.", 60, TestCaseRecord::expectedRc),
                Ui.column("Origen", 80, TestCaseRecord::source), Ui.column("Tags", 90, TestCaseRecord::tags),
                Ui.column("Activo", 55, (TestCaseRecord t) -> t.enabled() ? "Sí" : "No")));
        cases.getSelectionModel().selectedItemProperty().addListener((o, a, tc) -> {
            caseJson.setText(tc == null ? "" : pretty(tc.requestJson()));
            expectedRc.setText(tc == null || tc.expectedRc() == null ? "" : tc.expectedRc());
        });
        VBox caseBox = new VBox(6, cases, Ui.bar(new Label("RC esperado:"), expectedRc,
                Ui.button("Guardar caso", this::saveCase), Ui.button("Nuevo caso desde JSON", this::newCase),
                Ui.button("Eliminar caso", this::deleteCase)), caseJson);
        VBox.setVgrow(cases, Priority.ALWAYS);
        VBox.setVgrow(caseJson, Priority.ALWAYS);

        results.getColumns().addAll(List.of(
                Ui.column("Caso", 220, CaseResult::name),
                Ui.column("Resultado", 80, (CaseResult r) -> r.passed() ? "PASÓ" : "FALLÓ"),
                Ui.column("RC esp.", 60, CaseResult::expectedRc), Ui.column("RC real", 60, CaseResult::actualRc),
                Ui.column("Outcome", 90, CaseResult::outcome),
                Ui.column("Latencia ms", 90, (CaseResult r) -> Ui.fmt(r.latencyMicros() / 1000.0)),
                Ui.column("Detalle", 400, CaseResult::summary)));
        results.setRowFactory(tv -> new javafx.scene.control.TableRow<>() {
            @Override
            protected void updateItem(CaseResult item, boolean empty) {
                super.updateItem(item, empty);
                setStyle(empty || item == null ? "" : item.passed() ? "" : "-fx-background-color: -color-danger-subtle;");
            }
        });
        results.getSelectionModel().selectedItemProperty().addListener((o, a, r) -> detail.setText(r == null ? ""
                : "Issues:\n" + r.issues().stream().map(Object::toString).collect(Collectors.joining("\n"))
                + (r.error() != null ? "\nError: " + r.error() : "")
                + "\n\nRequest:\n" + pretty(r.requestJson()) + "\n\nResponse:\n" + pretty(r.responseJson())));

        history.getColumns().addAll(List.of(
                Ui.column("Run", 50, RegressionRunRecord::id), Ui.column("Suite", 160, RegressionRunRecord::suiteName),
                Ui.column("Entorno", 70, RegressionRunRecord::environment), Ui.column("Inicio", 170, RegressionRunRecord::startedAt),
                Ui.column("Total", 50, RegressionRunRecord::total), Ui.column("OK", 50, RegressionRunRecord::passed),
                Ui.column("Fallas", 55, RegressionRunRecord::failed), Ui.column("Estado", 90, RegressionRunRecord::status)));

        SplitPane resultSplit = new SplitPane(results, detail);
        resultSplit.setOrientation(javafx.geometry.Orientation.VERTICAL);
        TabPane tabs = new TabPane(new Tab("Casos", caseBox), new Tab("Resultados", resultSplit), new Tab("Historial", historyBox()));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        var run = Ui.button("Ejecutar suite", () -> {
            tabs.getSelectionModel().select(1);
            run();
        });
        var top = new VBox(4, Ui.bar(Ui.title("Regresión"), Ui.spacer(), run,
                Ui.button("Cancelar", () -> {
                    RegressionRunner r = runner;
                    if (r != null) {
                        r.cancel();
                    }
                }),
                Ui.button("Exportar HTML", () -> export(true)), Ui.button("Exportar JUnit XML", () -> export(false))),
                Ui.bar(new Label("Concurrencia:"), concurrency, new Label("Pausa ms:"), delay,
                        new Label("Ignorar campos:"), ignore, tags, refresh, stopFirst),
                Ui.bar(progress, summary));
        progress.setPrefWidth(300);

        SplitPane split = new SplitPane(left, tabs);
        split.setDividerPositions(0.2);
        root.setTop(top);
        root.setCenter(split);
        root.setPadding(new Insets(12));
    }

    private static String pretty(String json) {
        if (json == null || json.isBlank()) {
            return "";
        }
        try {
            return Ui.pretty(org.simulator.common.json.Json.mapper().readTree(json));
        } catch (Exception e) {
            return json;
        }
    }

    private void loadSuites() {
        SuiteRecord sel = suites.getSelectionModel().getSelectedItem();
        suites.getItems().setAll(ctx.testCases().suites());
        if (sel != null) {
            suites.getItems().stream().filter(s -> s.id().equals(sel.id())).findFirst()
                    .ifPresent(s -> suites.getSelectionModel().select(s));
        }
        history.getItems().setAll(ctx.runs().regressionRuns(100));
    }

    private void loadCases() {
        SuiteRecord s = suites.getSelectionModel().getSelectedItem();
        cases.getItems().setAll(s == null ? List.of() : ctx.testCases().bySuite(s.id()));
    }

    private void deleteSuite() {
        SuiteRecord s = suites.getSelectionModel().getSelectedItem();
        if (s != null && Ui.confirm("Eliminar suite", "¿Eliminar la suite '" + s.name() + "' y sus " + s.caseCount() + " casos?")) {
            ctx.testCases().deleteSuite(s.id());
            loadSuites();
        }
    }

    private void saveCase() {
        TestCaseRecord tc = cases.getSelectionModel().getSelectedItem();
        if (tc == null) {
            Ui.error("Seleccione un caso");
            return;
        }
        try {
            IsoMessage m = ctx.messages().fromJson(caseJson.getText());
            String rc = expectedRc.getText().isBlank() ? null : expectedRc.getText().trim();
            ctx.testCases().save(new TestCaseRecord(tc.id(), tc.suiteId(), tc.name(), m.getDialect(), caseJson.getText(),
                    rc, tc.ignoreFields(), tc.tags(), tc.source(), tc.enabled(), tc.createdAt()));
            loadCases();
        } catch (Exception e) {
            Ui.error(e);
        }
    }

    private void newCase() {
        SuiteRecord s = suites.getSelectionModel().getSelectedItem();
        if (s == null) {
            Ui.error("Seleccione una suite");
            return;
        }
        try {
            IsoMessage m = ctx.messages().fromJson(caseJson.getText());
            Ui.prompt("Nombre del caso", m.getMti() + " " + (m.get(3) == null ? "" : m.get(3))).ifPresent(name -> {
                String rc = expectedRc.getText().isBlank() ? "00" : expectedRc.getText().trim();
                ctx.testCases().save(TestCaseRecord.of(s.id(), name, m.getDialect(), caseJson.getText(), rc).withSource("MANUAL"));
                loadCases();
                loadSuites();
            });
        } catch (Exception e) {
            Ui.error(e);
        }
    }

    private void deleteCase() {
        TestCaseRecord tc = cases.getSelectionModel().getSelectedItem();
        if (tc != null && Ui.confirm("Eliminar caso", "¿Eliminar '" + tc.name() + "'?")) {
            ctx.testCases().delete(tc.id());
            loadCases();
        }
    }

    private javafx.scene.Node historyBox() {
        history.getSelectionModel().setSelectionMode(javafx.scene.control.SelectionMode.MULTIPLE);
        javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(6,
                Ui.bar(Ui.button("Comparar 2 corridas", this::compare), Ui.button("Refrescar", this::loadSuites),
                        new javafx.scene.control.Label("Seleccione la corrida base y la nueva (Ctrl+clic). Se ignoran campos dinámicos (7, 11, 12, 13, 15, 17, 37, 38).")),
                history);
        javafx.scene.layout.VBox.setVgrow(history, javafx.scene.layout.Priority.ALWAYS);
        return box;
    }

    private void compare() {
        List<RegressionRunRecord> sel = new java.util.ArrayList<>(history.getSelectionModel().getSelectedItems());
        if (sel.size() != 2) {
            Ui.error("Seleccione exactamente 2 corridas");
            return;
        }
        sel.sort(java.util.Comparator.comparingLong(RegressionRunRecord::id));
        long base = sel.get(0).id();
        long other = sel.get(1).id();
        Ui.async(null, () -> new org.simulator.regression.compare.RegressionComparator(ctx.runs()).compare(base, other), rep -> {
            if (Ui.confirm("Comparación run " + base + " vs " + other + (rep.hasRegressions() ? "  ⚠ REGRESIONES" : ""),
                    rep.summary() + "\n\n¿Guardar el reporte HTML?")) {
                Ui.saveText(Ui.window(history), "Reporte de comparación", "compare-" + base + "-" + other + ".html", rep.toHtml());
            }
        });
    }

    private void run() {
        SuiteRecord s = suites.getSelectionModel().getSelectedItem();
        if (s == null) {
            Ui.error("Seleccione una suite");
            return;
        }
        if (!ctx.acquirer().isConnected()) {
            Ui.error("Conecte el canal en la vista Adquirencia antes de ejecutar la regresión");
            return;
        }
        if (runner != null) {
            Ui.error("Ya hay una regresión en ejecución");
            return;
        }
        RegressionOptions opts;
        try {
            Set<Integer> ign = ignore.getText().isBlank() ? Set.of() : Arrays.stream(ignore.getText().split("[,; ]+"))
                    .filter(x -> !x.isBlank()).map(Integer::parseInt).collect(Collectors.toSet());
            opts = new RegressionOptions().setConcurrency(concurrency.getValue())
                    .setDelayBetweenMs(Long.parseLong(delay.getText().trim())).setIgnoreFields(new java.util.HashSet<>(ign))
                    .setRefreshDynamicFields(refresh.isSelected()).setStopOnFirstFailure(stopFirst.isSelected())
                    .setTagFilter(tags.getText().isBlank() ? null : tags.getText().trim());
        } catch (NumberFormatException e) {
            Ui.error("Opciones inválidas: " + e.getMessage());
            return;
        }
        results.getItems().clear();
        progress.setProgress(0);
        summary.setText("Ejecutando " + s.name() + "...");
        RegressionRunner r = new RegressionRunner(ctx.acquirer(), ctx.testCases(), ctx.runs());
        runner = r;
        Ui.async(null, () -> {
            try {
                return r.run(s.id(), s.name(), ctx.env().getName(), opts, (res, done, total) -> Platform.runLater(() -> {
                    results.getItems().add(res);
                    progress.setProgress(total == 0 ? 1 : (double) done / total);
                    summary.setText(done + "/" + total);
                }));
            } finally {
                runner = null;
            }
        }, rep -> {
            lastReport = rep;
            progress.setProgress(1);
            summary.setText(String.format("%s | %d casos | %d OK | %d fallas", rep.status(), rep.results().size(),
                    rep.passed(), rep.failed()));
            loadSuites();
        });
    }

    private void export(boolean html) {
        if (lastReport == null) {
            Ui.error("No hay reporte para exportar");
            return;
        }
        String base = "regresion-" + lastReport.suiteName().replaceAll("\\W+", "_") + "-" + lastReport.runId();
        Ui.saveText(Ui.window(root), "Exportar reporte", base + (html ? ".html" : ".xml"),
                html ? lastReport.toHtml() : lastReport.toJUnitXml());
    }

    @Override
    public String title() {
        return "Regresión";
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public void onShow() {
        loadSuites();
    }
}
