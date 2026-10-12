package org.simulator.ui.view;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.simulator.base24.pathway.CommandPolicy;
import org.simulator.base24.pathway.PathwayService;
import org.simulator.base24.pathway.PathwayService.CommandResult;
import org.simulator.common.SimulatorException;
import org.simulator.common.json.Json;
import org.simulator.ui.AppContext;
import org.simulator.ui.util.Ui;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Transaction documentation, Pathway console and Base24 API. */
public class Base24View implements View {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private final AppContext ctx;
    private final BorderPane root = new BorderPane();
    private final Label sshStatus = new Label();
    private final DocBuilderPane docBuilder;
    private final ComboBox<String> servers = new ComboBox<>();
    private final TextField pathCmd = new TextField();
    private final TextArea pathOut = Ui.code(false);
    private final ListView<CommandResult> pathHistory = new ListView<>();
    private final Label pathInfo = new Label();
    private final ChoiceBox<String> apiMethod = new ChoiceBox<>(FXCollections.observableArrayList("GET", "POST", "PUT", "DELETE"));
    private final TextField apiPath = new TextField();
    private final TextArea apiBody = Ui.code(true);
    private final TextArea apiResponse = Ui.code(false);
    private final TableView<String[]> audit = new TableView<>();

    public Base24View(AppContext ctx) {
        this.ctx = ctx;
        docBuilder = new DocBuilderPane(ctx);
        Tab auditTab = new Tab("Auditoría", auditTab());
        TabPane tabs = new TabPane(new Tab("Doc Builder", docBuilder.node()), new Tab("Pathway", pathwayTab()),
                new Tab("API Base24", apiTab()), auditTab);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b == auditTab) loadAudit();
        });
        root.setTop(Ui.bar(Ui.title("Base24"), Ui.spacer(), sshStatus,
                Ui.button("Probar SSH", () -> Ui.async(null, () -> ctx.ssh().testConnection(),
                        v -> sshStatus.setText("SSH Guardian OK: " + v))),
                Ui.button("Recargar configuración", () -> {
                    ctx.environmentChanged();
                    docBuilder.clearRemoteResults();
                    onShow();
                })));
        root.setCenter(tabs);
        root.setPadding(new Insets(12));
    }

    private Node pathwayTab() {
        servers.setPromptText("Server Pathway");
        servers.valueProperty().addListener((o, a, b) -> {
            pathOut.clear();
            pathHistory.getItems().setAll(ctx.pathwayHistory(b));
            pathInfo.setText(b == null ? "Configure pathway.servers." : "Server seleccionado: " + b);
        });
        pathCmd.setPromptText("p.ej. STATUS SERVER *");
        pathCmd.setOnAction(e -> runPathway(pathCmd.getText()));
        FlowPane quick = new FlowPane(6, 6);
        for (String command : List.of("STATUS PATHMON", "INFO PATHWAY", "STATUS SERVER *", "STATUS TCP *", "STATUS TERM *")) {
            quick.getChildren().add(Ui.button(command, () -> runPathway(command)));
        }
        pathHistory.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(CommandResult item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : TIME.format(item.at())
                        + (item.success() ? "  OK   " : "  ERR  ") + item.command());
            }
        });
        pathHistory.getSelectionModel().selectedItemProperty().addListener((o, a, r) -> {
            if (r != null) pathOut.setText("> " + r.command() + "\n" + r.output());
        });
        VBox center = new VBox(6, Ui.bar(new Label("Server:"), servers, new Label("Comando PATHCOM:"),
                pathCmd, Ui.button("Enviar", () -> runPathway(pathCmd.getText())),
                Ui.button("Desconectar", this::disconnectPathway)), quick, pathInfo, pathOut);
        HBox.setHgrow(pathCmd, Priority.ALWAYS);
        VBox.setVgrow(pathOut, Priority.ALWAYS);
        SplitPane sp = new SplitPane(center, new VBox(6, new Label("Historial del server"), pathHistory));
        sp.setDividerPositions(0.75);
        return sp;
    }

    private void disconnectPathway() {
        if (servers.getValue() == null) {
            Ui.error("Seleccione un server Pathway.");
            return;
        }
        String server = servers.getValue();
        Ui.async(null, () -> {
            ctx.pathway(server).close();
            return null;
        }, ignored -> {
            if (server.equals(servers.getValue())) pathInfo.setText("Desconectado: " + server);
        });
    }

    private void runPathway(String command) {
        String c = command == null ? "" : command.trim();
        if (c.isEmpty() || servers.getValue() == null) {
            Ui.error("Seleccione un server e ingrese un comando.");
            return;
        }
        PathwayService service;
        try {
            service = ctx.pathway(servers.getValue());
        } catch (SimulatorException | IllegalArgumentException e) {
            Ui.error(e);
            return;
        }
        CommandPolicy.Decision decision = service.check(c);
        if (decision.verdict() == CommandPolicy.Verdict.DENIED) {
            Ui.error("Comando no permitido: " + decision.reason());
            return;
        }
        if (decision.verdict() == CommandPolicy.Verdict.NEEDS_CONFIRMATION
                && !Ui.confirm("Comando crítico (" + decision.verb() + ")",
                "¿Ejecutar en " + ctx.env().getName() + " / " + servers.getValue() + "?\n\n" + c)) return;
        String server = servers.getValue();
        pathInfo.setText("Enviando a " + service.port().describe() + "...");
        Ui.async(null, () -> service.execute(c, true, ctx.user()), result -> {
            if (!server.equals(servers.getValue())) return;
            pathOut.setText("> " + result.command() + "\n" + result.output());
            pathInfo.setText((result.success() ? "OK" : "ERROR") + " | " + result.durationMs()
                    + " ms | " + service.port().describe());
            pathHistory.getItems().setAll(service.history());
        });
    }

    private Node apiTab() {
        apiMethod.setValue("POST");
        apiPath.setText(ctx.env().getApi().getTransactionPath());
        apiBody.setPromptText("Cuerpo JSON");
        HBox.setHgrow(apiPath, Priority.ALWAYS);
        VBox box = new VBox(6, Ui.bar(new Label("Método"), apiMethod, new Label("Path"), apiPath,
                Ui.button("Enviar", this::callApi)), new Label("Request"), apiBody, new Label("Response"), apiResponse);
        VBox.setVgrow(apiBody, Priority.ALWAYS);
        VBox.setVgrow(apiResponse, Priority.ALWAYS);
        box.setPadding(new Insets(6));
        return box;
    }

    private void callApi() {
        String method = apiMethod.getValue();
        String path = apiPath.getText().trim();
        String body = apiBody.getText();
        apiResponse.setText("Enviando a " + ctx.api().describe() + "...");
        Ui.async(null, () -> ctx.api().call(method, path, body.isBlank() ? null : body).join(), result -> {
            StringBuilder sb = new StringBuilder();
            sb.append("HTTP ").append(result.status()).append(" | ").append(result.protocol()).append(" | ")
                    .append(String.format("%.2f ms", result.latencyNanos() / 1e6))
                    .append(" | intentos ").append(result.attempts()).append('\n');
            result.headers().forEach((k, v) -> sb.append(k).append(": ").append(String.join(", ", v)).append('\n'));
            apiResponse.setText(sb.append('\n').append(prettyJson(result.body())).toString());
        });
    }

    private Node auditTab() {
        String[] columns = {"Fecha", "Usuario", "Acción", "Objetivo", "Detalle", "OK"};
        for (int i = 0; i < columns.length; i++) {
            final int index = i;
            audit.getColumns().add(Ui.column(columns[i], i == 4 ? 420 : 130, (String[] row) -> row[index]));
        }
        audit.setFixedCellSize(24);
        VBox box = new VBox(6, Ui.bar(Ui.button("Refrescar", this::loadAudit)), audit);
        VBox.setVgrow(audit, Priority.ALWAYS);
        return box;
    }

    private void loadAudit() {
        Ui.async(null, () -> ctx.runs().auditTrail(500), rows -> audit.getItems().setAll(rows));
    }

    private static String prettyJson(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            return Ui.pretty(Json.mapper().readTree(value));
        } catch (java.io.IOException e) {
            return value;
        }
    }

    @Override
    public String title() { return "Base24"; }

    @Override
    public Node node() { return root; }

    @Override
    public void onShow() {
        String selected = servers.getValue();
        var endpoint = ctx.env().getPathway();
        servers.getItems().setAll(endpoint.serverNames());
        servers.setValue(servers.getItems().contains(selected) ? selected
                : servers.getItems().contains(endpoint.getServerName()) ? endpoint.getServerName()
                : servers.getItems().isEmpty() ? null : servers.getItems().get(0));
        pathHistory.getItems().setAll(ctx.pathwayHistory(servers.getValue()));
        var guardian = ctx.env().getGuardian();
        sshStatus.setText("Guardian: " + guardian.getHost() + ":" + guardian.getPort()
                + " | Pathway: " + endpoint.getMode() + " " + endpoint.getHost() + ":" + endpoint.getPort());
        docBuilder.refresh();
    }
}
