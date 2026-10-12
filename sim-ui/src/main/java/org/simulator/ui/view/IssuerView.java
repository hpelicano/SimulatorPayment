package org.simulator.ui.view;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import javafx.util.StringConverter;
import org.simulator.acquirer.TransactionOutcome;
import org.simulator.common.json.Json;
import org.simulator.iso.IsoMessage;
import org.simulator.issuer.IssuerRuleEngine;
import org.simulator.issuer.IssuerRules;
import org.simulator.issuer.IssuerSimulator.Routing;
import org.simulator.issuer.IssuerSimulator.Traffic;
import org.simulator.transport.Framing;
import org.simulator.ui.AppContext;
import org.simulator.ui.util.FramingChoice;
import org.simulator.ui.util.Ui;

import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Issuer module with two operating modes:
 * <ul>
 *   <li><b>Cliente</b>: independent outgoing connection that sends H2 messages simulating a brand (Visa, Mastercard...).</li>
 *   <li><b>Server</b>: listens on port 1 and answers with the JSON rules, either on the same socket (full duplex)
 *       or routing responses through port 2 (listening or connecting to the originator).</li>
 * </ul>
 * Each mode has its own exportable message log.
 */
public class IssuerView implements View {

    private static final int MAX_ROWS = 2000;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private final AppContext ctx;
    private final BorderPane root = new BorderPane();
    private final StackPane modes = new StackPane();
    private final Label modeHint = new Label();

    // client mode
    private final AcquirerView clientView;
    private final MessageLogPane clientLog;
    private final BiConsumer<TransactionOutcome, String> clientOutcomeListener = this::onClientOutcome;
    private final Consumer<IsoMessage> clientUnsolicitedListener;

    // server mode
    private final BorderPane serverPane = new BorderPane();
    private final TextField inHost = new TextField();
    private final TextField inPort = new TextField();
    private final ChoiceBox<Routing> routing = new ChoiceBox<>(FXCollections.observableArrayList(Routing.values()));
    private final TextField outHost = new TextField();
    private final TextField outPort = new TextField();
    private final ChoiceBox<Framing> inFraming;
    private final ChoiceBox<Framing> outFraming;
    private final Label status = new Label();
    private final TextArea rules = Ui.code(true);
    private final RulesEditor rulesForm = new RulesEditor();
    private final TabPane ruleTabs = new TabPane();
    private final TableView<Traffic> traffic = new TableView<>();
    private final ConcurrentLinkedQueue<Traffic> pending = new ConcurrentLinkedQueue<>();
    private final MessageLogPane serverLog;
    private final Consumer<String> routeErrorListener;
    private final Timeline timer;

    public IssuerView(AppContext ctx) {
        this.ctx = ctx;
        root.getStyleClass().addAll("acquirer-view", "issuer-view");

        clientView = new AcquirerView(ctx, true);
        clientLog = new MessageLogPane(ctx.messages(), "emision-cliente", "Log de mensajes - modo Cliente");
        clientUnsolicitedListener = m -> clientLog.log("IN", clientChannel(), m, "Mensaje no solicitado");
        ctx.issuerClient().addOutcomeListener(clientOutcomeListener);
        ctx.addIssuerClientUnsolicitedListener(clientUnsolicitedListener);
        SplitPane clientPane = new SplitPane(clientView.node(), clientLog);
        clientPane.setOrientation(Orientation.VERTICAL);
        clientPane.setDividerPositions(0.64);

        serverLog = new MessageLogPane(ctx.messages(), "emision-server", "Log de mensajes - modo Server");
        routeErrorListener = error -> serverLog.event("ERR", "Puerto 2", error);
        ctx.issuer().addRouteErrorListener(routeErrorListener);
        inFraming = FramingChoice.create(ctx.env().getIssuer().getFraming());
        outFraming = FramingChoice.create(ctx.env().getIssuerOutbound().getFraming());
        buildServerPane();

        modes.getChildren().addAll(clientPane, serverPane);
        ToggleGroup group = new ToggleGroup();
        ToggleButton client = new ToggleButton("Modo Cliente");
        ToggleButton server = new ToggleButton("Modo Server");
        client.getStyleClass().add("mode-toggle");
        server.getStyleClass().add("mode-toggle");
        client.setToggleGroup(group);
        server.setToggleGroup(group);
        group.selectedToggleProperty().addListener((o, previous, selected) -> {
            if (selected == null) {
                previous.setSelected(true);
                return;
            }
            boolean isClient = selected == client;
            clientPane.setVisible(isClient);
            serverPane.setVisible(!isClient);
            modeHint.setText(isClient
                    ? "Emite transacciones por una conexión IP/puerto propia (simula Visa, Mastercard, etc.)"
                    : "Recibe transacciones y responde según las reglas JSON (full-duplex o puertos de entrada/salida separados)");
            if (isClient) {
                clientView.onShow();
            }
        });
        modeHint.getStyleClass().add("mode-hint");
        server.setSelected(true);

        root.setTop(Ui.bar(Ui.title("Emisión"), client, server, modeHint));
        root.setCenter(modes);
        root.setPadding(new Insets(12));
        BorderPane.setMargin(modes, new Insets(6, 0, 0, 0));

        ctx.issuer().setTrafficListener(this::onServerTraffic);
        timer = new Timeline(new KeyFrame(Duration.millis(300), e -> drain()));
        timer.setCycleCount(Timeline.INDEFINITE);
        timer.play();
        refreshStatus();
    }

    // ------------------------------------------------------------------ server mode

    private void buildServerPane() {
        var in = ctx.env().getIssuer();
        var out = ctx.env().getIssuerOutbound();
        inHost.setText(in.getHost());
        inHost.setPrefColumnCount(8);
        inPort.setText(String.valueOf(in.getPort()));
        inPort.setPrefColumnCount(5);
        outHost.setText(out.getHost());
        outHost.setPrefColumnCount(8);
        outPort.setText(String.valueOf(out.getPort()));
        outPort.setPrefColumnCount(5);
        routing.setConverter(new StringConverter<>() {
            @Override
            public String toString(Routing r) {
                if (r == null) {
                    return "";
                }
                return switch (r) {
                    case FULL_DUPLEX -> "Full-duplex (mismo socket)";
                    case SPLIT_LISTEN -> "Puertos separados: escuchar en puerto 2";
                    case SPLIT_CONNECT -> "Puertos separados: conectar al originante";
                };
            }

            @Override
            public Routing fromString(String s) {
                return null;
            }
        });
        try {
            routing.setValue(Routing.parse(ctx.env().getIssuerRouting()));
        } catch (IllegalArgumentException e) {
            routing.setValue(Routing.FULL_DUPLEX);
        }
        routing.valueProperty().addListener((o, a, r) -> updateRoutingControls());
        updateRoutingControls();

        var top = Ui.bar(new Label("IP escucha:"), inHost, new Label("Puerto 1 (entrada):"), inPort,
                new Label("Framing P1:"), inFraming,
                new Label("Ruteo:"), routing, new Label("IP salida:"), outHost,
                new Label("Puerto 2 (salida):"), outPort, new Label("Framing P2:"), outFraming,
                Ui.button("Iniciar", this::start), Ui.button("Detener", () -> {
                    ctx.issuer().stop();
                    serverLog.event("--", "Server", "Servidor detenido");
                    refreshStatus();
                }), Ui.spacer(), status);

        rules.setText(Ui.pretty(ctx.issuer().engine().rules()));
        VBox left = new VBox(6, Ui.bar(new Label("Reglas de respuesta (JSON)"), Ui.spacer(),
                Ui.button("Aplicar", this::applyRules),
                Ui.button("Guardar", this::saveRules),
                Ui.button("Por defecto", () -> {
                    rules.setText(Ui.pretty(IssuerRuleEngine.defaultRules()));
                    rulesForm.setRules(IssuerRuleEngine.defaultRules());
                })), ruleTabs,
                new Label("Match: mti, processingCode, binPrefixes, amountMin/Max, terminalId, fields (\"!\" = ausente).\n"
                        + "Action: responseCode, responseCodeSequence, delayMs, jitterMs, noResponse, setFields (${REQ:n}, ${RANDOM_N:n}), removeFields."));
        var jsonTab = new Tab("JSON", rules);
        var formTab = new Tab("Formulario", rulesForm);
        ruleTabs.getTabs().setAll(jsonTab, formTab);
        ruleTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        ruleTabs.getSelectionModel().selectedItemProperty().addListener((o, a, tab) -> {
            try {
                if (tab == formTab) {
                    rulesForm.setRules(Json.mapper().readValue(rules.getText(), IssuerRules.class));
                } else {
                    rules.setText(Ui.pretty(rulesForm.getRules()));
                }
            } catch (Exception e) {
                Ui.error("No se pudo sincronizar las reglas: " + Ui.root(e).getMessage());
            }
        });
        VBox.setVgrow(ruleTabs, Priority.ALWAYS);

        traffic.getColumns().addAll(List.of(
                Ui.column("Hora", 90, (Traffic t) -> TIME.format(t.at())),
                Ui.column("MTI", 45, (Traffic t) -> t.request().getMti()),
                Ui.column("PC", 60, (Traffic t) -> t.request().get(3)),
                Ui.column("STAN", 60, (Traffic t) -> t.request().get(11)),
                Ui.column("PAN", 155, (Traffic t) -> t.request().get(2)),
                Ui.column("Importe", 95, (Traffic t) -> t.request().get(4)),
                Ui.column("Terminal", 75, (Traffic t) -> t.request().get(41)),
                Ui.column("Resp MTI", 65, (Traffic t) -> t.response() == null ? "(sin resp.)" : t.response().getMti()),
                Ui.column("RC", 35, (Traffic t) -> t.response() == null ? "" : t.response().get(39)),
                Ui.column("Ruteo", 95, Traffic::route),
                Ui.column("Regla", 140, Traffic::rule),
                Ui.column("Demora ms", 70, Traffic::delayMs)));
        traffic.setFixedCellSize(24);
        VBox liveBox = new VBox(6, Ui.bar(new Label("Tráfico en vivo"), Ui.spacer(),
                Ui.button("Limpiar", () -> traffic.getItems().clear())), traffic);
        VBox.setVgrow(traffic, Priority.ALWAYS);
        TabPane rightTabs = new TabPane(new Tab("Tráfico en vivo", liveBox), new Tab("Log de mensajes", serverLog));
        rightTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        SplitPane split = new SplitPane(left, rightTabs);
        split.setDividerPositions(0.36);
        serverPane.setTop(top);
        serverPane.setCenter(split);
        BorderPane.setMargin(split, new Insets(6, 0, 0, 0));
    }

    private void updateRoutingControls() {
        boolean split = routing.getValue() != null && routing.getValue() != Routing.FULL_DUPLEX;
        outHost.setDisable(!split);
        outPort.setDisable(!split);
        outFraming.setDisable(!split);
        outHost.setPromptText(routing.getValue() == Routing.SPLIT_LISTEN ? "IP local" : "IP originante");
    }

    private void onServerTraffic(Traffic t) {
        pending.add(t);
        String note = "Regla: " + (t.rule() == null ? "(default)" : t.rule());
        serverLog.log("IN", "Puerto 1 :" + ctx.issuer().port(), t.request(), note);
        String outChannel = Routing.FULL_DUPLEX.name().equals(t.route())
                ? "Puerto 1 :" + ctx.issuer().port()
                : "Puerto 2 " + ctx.issuer().outboundDescription();
        if (t.response() != null) {
            serverLog.log("OUT", outChannel, t.response(), t.delayMs() > 0 ? "Demora " + t.delayMs() + " ms" : "");
        } else {
            serverLog.event("OUT", outChannel, "Sin respuesta (" + note + ")");
        }
    }

    private void drain() {
        List<Traffic> batch = new ArrayList<>();
        Traffic t;
        while ((t = pending.poll()) != null) {
            batch.add(0, t);
        }
        if (!batch.isEmpty()) {
            traffic.getItems().addAll(0, batch);
            if (traffic.getItems().size() > MAX_ROWS) {
                traffic.getItems().remove(MAX_ROWS, traffic.getItems().size());
            }
        }
        refreshStatus();
    }

    private void refreshStatus() {
        var is = ctx.issuer();
        if (!is.isRunning()) {
            status.setText("Detenido");
            return;
        }
        String text = "Puerto 1 :" + is.port() + " | conexiones: " + is.connections() + " | atendidas: " + is.handledCount();
        if (is.routing() != Routing.FULL_DUPLEX) {
            text += " | Puerto 2 " + is.outboundDescription() + " (" + is.outboundConnections() + " enlace/s"
                    + (is.routeErrorCount() > 0 ? ", errores: " + is.routeErrorCount() : "") + ")";
        }
        status.setText(text);
    }

    private void start() {
        int p;
        int op;
        try {
            p = Integer.parseInt(inPort.getText().trim());
            op = Integer.parseInt(outPort.getText().trim());
        } catch (NumberFormatException e) {
            Ui.error("Puerto inválido");
            return;
        }
        Routing r = routing.getValue();
        if (r != Routing.FULL_DUPLEX && op == p && r == Routing.SPLIT_LISTEN) {
            Ui.error("El puerto de salida debe ser distinto al de entrada");
            return;
        }
        if (r != Routing.FULL_DUPLEX && outHost.getText().isBlank()) {
            Ui.error("Ingrese la IP de salida");
            return;
        }
        if (!applyRules()) {
            return;
        }
        String host = inHost.getText();
        String oh = outHost.getText();
        Framing f1 = inFraming.getValue();
        Framing f2 = outFraming.getValue();
        Ui.async(serverPane.getTop(), () -> {
            ctx.startIssuer(host, p, f1, r, oh, op, f2);
            return null;
        }, x -> {
            serverLog.event("--", "Server", "Servidor iniciado en puerto " + ctx.issuer().port() + " (" + f1 + ") | ruteo "
                    + r + (r == Routing.FULL_DUPLEX ? "" : " | salida " + ctx.issuer().outboundDescription() + " (" + f2 + ")"));
            refreshStatus();
        });
    }

    private boolean applyRules() {
        try {
            if (ruleTabs.getSelectionModel().getSelectedIndex() == 1) {
                rules.setText(Ui.pretty(rulesForm.getRules()));
            }
            IssuerRules r = Json.mapper().readValue(rules.getText(), IssuerRules.class);
            ctx.issuer().engine().setRules(r);
            return true;
        } catch (Exception e) {
            Ui.error("Reglas inválidas: " + Ui.root(e).getMessage());
            return false;
        }
    }

    private void saveRules() {
        if (applyRules()) {
            Path f = ctx.config().getConfigDir().resolve("issuer-rules.json");
            Json.write(f, ctx.issuer().engine().rules());
            Ui.info("Reglas guardadas en " + f);
        }
    }

    // ------------------------------------------------------------------ client mode

    private String clientChannel() {
        var ch = ctx.issuerClient().channel();
        return ch == null ? "Cliente" : "Cliente " + ch.describe().replaceFirst("^tcp://", "").replaceFirst(" \\(.*$", "");
    }

    private void onClientOutcome(TransactionOutcome o, String source) {
        String channel = clientChannel();
        clientLog.log("OUT", channel, o.request(), "");
        String result = o.status() + String.format(" | %.2f ms", o.latencyMillis())
                + (o.validation() != null ? (o.passed() ? " | Validación OK" : " | Validación con errores") : "");
        if (o.response() != null) {
            clientLog.log("IN", channel, o.response(), result);
        } else {
            clientLog.event("IN", channel, result + (o.error() != null ? " | " + o.error() : ""));
        }
    }

    @Override
    public String title() {
        return "Emisión";
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public void onShow() {
        clientView.onShow();
        refreshStatus();
    }

    @Override
    public void dispose() {
        timer.stop();
        clientLog.dispose();
        serverLog.dispose();
        ctx.issuerClient().removeOutcomeListener(clientOutcomeListener);
        ctx.removeIssuerClientUnsolicitedListener(clientUnsolicitedListener);
        ctx.issuer().removeRouteErrorListener(routeErrorListener);
        clientView.dispose();
    }
}
