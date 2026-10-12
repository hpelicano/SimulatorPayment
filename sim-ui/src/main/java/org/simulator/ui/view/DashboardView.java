package org.simulator.ui.view;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.simulator.stats.StatsSnapshot;
import org.simulator.ui.AppContext;
import org.simulator.ui.util.Icons;
import org.simulator.ui.util.Ui;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.material2.Material2OutlinedAL;
import org.kordamp.ikonli.material2.Material2OutlinedMZ;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Real time statistics: approved / declined / timeouts, TPS and latency percentiles. */
public class DashboardView implements View {

    private final AppContext ctx;
    private final BorderPane root = new BorderPane();
    private final Map<String, Label> kpis = new LinkedHashMap<>();
    private final XYChart.Series<Number, Number> tpsSeries = new XYChart.Series<>();
    private final PieChart pie = new PieChart();
    private final TableView<Map.Entry<String, String>> resultMetrics = new TableView<>();
    private final TableView<Map.Entry<String, Long>> byRc = new TableView<>();
    private final TableView<Map.Entry<String, Long>> bySource = new TableView<>();
    private final Timeline timer;

    public DashboardView(AppContext ctx) {
        this.ctx = ctx;
        FlowPane tiles = new FlowPane(12, 12);
        tiles.getStyleClass().add("no-card");
        addKpi(tiles, "Total", "kpi-total", Material2OutlinedAL.ALL_INCLUSIVE);
        addKpi(tiles, "Aprobadas", "kpi-ok", Material2OutlinedAL.CHECK_CIRCLE);
        addKpi(tiles, "Rechazadas", "kpi-decl", Material2OutlinedAL.HIGHLIGHT_OFF);
        addKpi(tiles, "Timeouts", "kpi-timeout", Material2OutlinedMZ.TIMER);
        addKpi(tiles, "Errores", "kpi-error", Material2OutlinedAL.ERROR_OUTLINE);
        addKpi(tiles, "TPS actual", "kpi-tps", Material2OutlinedMZ.SPEED);

        NumberAxis x = new NumberAxis();
        x.setLabel("segundos");
        x.setForceZeroInRange(false);
        NumberAxis y = new NumberAxis();
        y.setLabel("TPS");
        LineChart<Number, Number> chart = new LineChart<>(x, y);
        chart.setTitle("TPS (últimos 5 minutos)");
        chart.setAnimated(false);
        chart.setCreateSymbols(false);
        chart.setLegendVisible(false);
        chart.getData().add(tpsSeries);

        pie.setTitle("Resultados");
        pie.setAnimated(false);
        pie.setLabelsVisible(true);
        pie.setPrefWidth(320);

        setupCounts(byRc, "Código respuesta");
        setupCounts(bySource, "Origen");
        VBox tables = new VBox(8, cardTitle("Por código de respuesta"), byRc, cardTitle("Por origen"), bySource);
        tables.setPrefWidth(280);
        tables.getStyleClass().add("card");
        VBox.setVgrow(byRc, Priority.ALWAYS);
        VBox.setVgrow(bySource, Priority.ALWAYS);

        VBox chartCard = new VBox(chart);
        chartCard.getStyleClass().add("card");
        VBox.setVgrow(chart, Priority.ALWAYS);
        resultMetrics.getColumns().addAll(List.of(
                Ui.column("Indicador", 190, Map.Entry::getKey),
                Ui.column("Valor", 100, Map.Entry::getValue)));
        resultMetrics.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        resultMetrics.setFixedCellSize(24);
        resultMetrics.setPrefHeight(210);
        resultMetrics.setMinHeight(210);
        resultMetrics.setMaxHeight(210);
        VBox pieCard = new VBox(8, pie, resultMetrics);
        pieCard.getStyleClass().add("card");
        VBox.setVgrow(pie, Priority.ALWAYS);

        HBox charts = new HBox(12, chartCard, pieCard, tables);
        HBox.setHgrow(chartCard, Priority.ALWAYS);
        VBox.setVgrow(charts, Priority.ALWAYS);
        VBox body = new VBox(12, tiles, charts);
        body.getStyleClass().add("no-card");

        var actions = Ui.bar(Ui.title("Estadísticas de transacciones"), Ui.spacer(),
                Ui.button("Reiniciar", () -> {
                    ctx.stats().reset();
                    refresh();
                }),
                Ui.button("Exportar CSV", () -> Ui.saveText(Ui.window(root), "Exportar estadísticas", "estadisticas.csv",
                        ctx.stats().toCsv())), metricsButton());
        root.setTop(actions);
        root.setCenter(body);
        root.setPadding(new Insets(12));

        timer = new Timeline(new KeyFrame(Duration.seconds(1), e -> refresh()));
        timer.setCycleCount(Timeline.INDEFINITE);
        timer.play();
    }

    private void addKpi(FlowPane tiles, String name, String variant, Ikon icon) {
        StackPane badge = new StackPane(Icons.of(icon, 19));
        badge.getStyleClass().add("kpi-icon");
        Label label = new Label(name);
        label.getStyleClass().add("kpi-label");
        Label value = new Label("0");
        value.getStyleClass().add("kpi-value");
        HBox tile = new HBox(badge, new VBox(1, label, value));
        tile.getStyleClass().addAll("kpi", variant);
        tiles.getChildren().add(tile);
        kpis.put(name, value);
    }

    private static Label cardTitle(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("card-title");
        return l;
    }

    private static void setupCounts(TableView<Map.Entry<String, Long>> t, String title) {
        t.getColumns().add(Ui.column(title, 150, Map.Entry::getKey));
        t.getColumns().add(Ui.column("Cantidad", 90, Map.Entry::getValue));
        t.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    }

    private void refresh() {
        if (root.getScene() == null) {
            return;
        }
        StatsSnapshot s = ctx.stats().snapshot();
        kpis.get("Total").setText(String.valueOf(s.total()));
        kpis.get("Aprobadas").setText(String.valueOf(s.approved()));
        kpis.get("Rechazadas").setText(String.valueOf(s.declined()));
        kpis.get("Timeouts").setText(String.valueOf(s.timeouts()));
        kpis.get("Errores").setText(String.valueOf(s.errors()));
        kpis.get("TPS actual").setText(Ui.fmt(s.currentTps()));
        resultMetrics.getItems().setAll(List.of(
                Map.entry("% Aprob.", Ui.fmt(s.approvalRate()) + " %"),
                Map.entry("TPS pico", Ui.fmt(s.peakTps())),
                Map.entry("TPS prom.", Ui.fmt(s.averageTps())),
                Map.entry("P50 ms", Ui.fmt(s.p50Ms())),
                Map.entry("P95 ms", Ui.fmt(s.p95Ms())),
                Map.entry("P99 ms", Ui.fmt(s.p99Ms())),
                Map.entry("Máx ms", Ui.fmt(s.maxMs()))));

        List<XYChart.Data<Number, Number>> points = new ArrayList<>();
        List<Long> h = s.tpsHistory();
        for (int i = 0; i < h.size(); i++) {
            points.add(new XYChart.Data<>(i - h.size() + 1, h.get(i)));
        }
        tpsSeries.getData().setAll(points);

        updatePie("Aprobadas", s.approved(), 0);
        updatePie("Rechazadas", s.declined(), 1);
        updatePie("Timeouts", s.timeouts(), 2);
        updatePie("Errores", s.errors(), 3);

        byRc.getItems().setAll(new ArrayList<>(s.byResponseCode().entrySet()));
        bySource.getItems().setAll(new ArrayList<>(s.bySource().entrySet()));
    }

    private void updatePie(String name, long value, int idx) {
        if (pie.getData().size() <= idx) {
            pie.getData().add(new PieChart.Data(name, value));
        } else {
            pie.getData().get(idx).setPieValue(value);
        }
    }

    private javafx.scene.control.ToggleButton metricsButton() {
        var btn = new javafx.scene.control.ToggleButton();
        Runnable label = () -> btn.setText(ctx.exporter().isRunning()
                ? "Prometheus: http://localhost:" + ctx.exporter().port() + "/metrics" : "Prometheus: OFF");
        btn.setSelected(ctx.exporter().isRunning());
        label.run();
        btn.setOnAction(e -> {
            try {
                if (btn.isSelected()) {
                    ctx.startMetrics();
                } else {
                    ctx.stopMetrics();
                }
            } catch (Exception ex) {
                btn.setSelected(false);
                Ui.error(ex);
            }
            label.run();
        });
        return btn;
    }

    @Override
    public String title() {
        return "Dashboard";
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public void onShow() {
        refresh();
    }

    @Override
    public void dispose() {
        timer.stop();
    }
}
