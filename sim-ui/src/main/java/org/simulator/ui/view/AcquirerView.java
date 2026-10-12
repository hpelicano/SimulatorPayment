package org.simulator.ui.view;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.simulator.persistence.DbAdminService.SqlResult;
import org.simulator.acquirer.TransactionOutcome;
import org.simulator.common.json.Json;
import org.simulator.iso.codec.FieldTrace;
import org.simulator.iso.IsoMessage;
import org.simulator.transport.Framing;
import org.simulator.ui.AppContext;
import org.simulator.ui.util.FramingChoice;
import org.simulator.ui.util.Ui;
import org.simulator.validator.ValidationIssue;
import org.simulator.validator.ValidationProfile;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Acquirer: loads persisted ISO messages from H2 and sends them to Base24 (TCP or API). */
public class AcquirerView implements View {

    private static final int MAX_RECORDS = 200;

    private final AppContext ctx;
    private final BorderPane root = new BorderPane();
    private final ComboBox<String> tables = new ComboBox<>();
    private final ComboBox<String> dialects = new ComboBox<>();
    private final TableView<List<String>> records = new TableView<>();
    private final TextArea request = Ui.code(true);
    private final TextArea response = Ui.code(false);
    private final TextArea hex = Ui.code(false);
    private final ListView<String> issues = new ListView<>();
    private final TableView<FieldTrace> trace = new TableView<>();
    private final Label status = new Label("Desconectado");
    private final Label result = new Label();
    private final ChoiceBox<String> mode = new ChoiceBox<>(FXCollections.observableArrayList("TCP", "API"));
    private final CheckBox showSensitive = new CheckBox("Mostrar hex (datos sensibles)");
    private List<String> recordColumns = List.of();
    private ValidationProfile activeValidation;
    private TransactionOutcome last;
    private final boolean issuerClient;
    private final TextField clientHost = new TextField();
    private final TextField clientPort = new TextField();
    private final ChoiceBox<Framing> framing;

    public AcquirerView(AppContext ctx) {
        this(ctx, false);
    }

    /**
     * @param issuerClient true to build the issuer "client mode" panel: same H2 message workflow, but over the
     *                     independent issuer client connection (host/port editable, TCP only, no own title).
     */
    public AcquirerView(AppContext ctx, boolean issuerClient) {
        this.ctx = ctx;
        this.issuerClient = issuerClient;
        root.getStyleClass().add("acquirer-view");
        mode.setValue("TCP");
        CheckBox autoRev = new CheckBox("Reverso automático en timeout");
        autoRev.setSelected(service().isAutoReversalOnTimeout());
        autoRev.selectedProperty().addListener((o, a, b) -> service().setAutoReversalOnTimeout(b));

        var connect = Ui.button("Conectar", this::connect);
        var disconnect = Ui.button("Desconectar", () -> {
            service().disconnect();
            updateStatus();
        });
        org.simulator.ui.util.WrapBox top;
        if (issuerClient) {
            var ep = ctx.env().getIssuerClient();
            framing = FramingChoice.create(ep.getFraming());
            clientHost.setText(ep.getHost());
            clientHost.setPrefColumnCount(10);
            clientPort.setText(String.valueOf(ep.getPort()));
            clientPort.setPrefColumnCount(5);
            top = Ui.bar(new Label("IP destino:"), clientHost, new Label("Puerto:"), clientPort,
                    new Label("Framing:"), framing, connect, disconnect,
                    Ui.button("Echo (0800)", this::echo), autoRev, Ui.spacer(), status);
        } else {
            framing = FramingChoice.create(ctx.env().getAcquirer().getFraming());
            framing.disableProperty().bind(mode.valueProperty().isEqualTo("API"));
            top = Ui.bar(Ui.title("Adquirencia"), new Label("Canal:"), mode, new Label("Framing:"), framing,
                    connect, disconnect, Ui.button("Echo (0800)", this::echo), autoRev, Ui.spacer(), status);
        }

        tables.valueProperty().addListener((o, oldTable, table) -> loadRecords());
        dialects.valueProperty().addListener((o, oldDialect, dialect) -> loadRecords());
        records.getSelectionModel().selectedItemProperty().addListener((o, oldRow, row) -> showMessage(row));
        tables.setMaxWidth(Double.MAX_VALUE);
        dialects.setMaxWidth(Double.MAX_VALUE);
        records.setFixedCellSize(24);

        VBox left = new VBox(4, new Label("Tablas H2"), tables,
                new Label("Suite / dialect"), dialects,
                new Label("Registros (seleccione para ver el mensaje)"), records);
        VBox.setVgrow(records, Priority.ALWAYS);
        left.setPrefWidth(440);

        var reloadMessage = Ui.button("Reload", () -> showMessage(records.getSelectionModel().getSelectedItem()));
        var updateMessage = Ui.button("Update", this::updateSelected);
        var saveMessage = Ui.button("Save as", this::saveAsNew);
        var sendMessage = Ui.button("Send", this::sendEdited);
        reloadMessage.getStyleClass().addAll("message-action", "message-reload");
        updateMessage.getStyleClass().addAll("message-action", "message-update");
        saveMessage.getStyleClass().addAll("message-action", "message-save");
        sendMessage.getStyleClass().addAll("message-action", "message-send");
        sendMessage.setDefaultButton(true);
        VBox center = new VBox(6, Ui.bar(new Label("Mensaje (JSON)"), Ui.spacer(),
                reloadMessage, updateMessage, saveMessage, sendMessage), request);
        VBox.setVgrow(request, Priority.ALWAYS);

        trace.getColumns().addAll(List.of(
                Ui.column("Elemento", 80, FieldTrace::element), Ui.column("Nombre", 180, FieldTrace::name),
                Ui.column("Offset", 60, FieldTrace::offset), Ui.column("Long.", 60, FieldTrace::length),
                Ui.column("Valor", 220, FieldTrace::value), Ui.column("Hex", 260, FieldTrace::rawHex)));
        showSensitive.selectedProperty().addListener((o, a, b) -> renderHex());
        VBox hexBox = new VBox(4, showSensitive, hex);
        VBox.setVgrow(hex, Priority.ALWAYS);
        TabPane tabs = new TabPane(new Tab("Respuesta", response), new Tab("Validación", issues),
                new Tab("Hex", hexBox), new Tab("Trace respuesta", trace));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        VBox right = new VBox(6, Ui.bar(result, Ui.spacer(),
                Ui.button("Reversar (0420)", this::reverse)), tabs);
        VBox.setVgrow(tabs, Priority.ALWAYS);

        SplitPane split = new SplitPane(left, center, right);
        split.setDividerPositions(0.30, 0.63);
        root.setTop(top);
        root.setCenter(split);
        root.setPadding(issuerClient ? new Insets(0) : new Insets(12));
    }

    private org.simulator.acquirer.AcquirerService service() {
        return issuerClient ? ctx.issuerClient() : ctx.acquirer();
    }

    private String source() {
        return issuerClient ? "ISSUER_CLIENT" : "ACQUIRER";
    }

    private void loadTables() {
        String selectedTable = tables.getValue();
        String selectedDialect = dialects.getValue();
        Ui.async(tables, () -> new Catalog(ctx.dbAdmin().messageTables().stream()
                .filter(table -> !"AUDIT_LOG".equalsIgnoreCase(table)).toList(), ctx.dbAdmin().dialects()), catalog -> {
            List<String> loaded = catalog.tables();
            tables.getItems().setAll(loaded);
            dialects.getItems().setAll(catalog.dialects());
            if (selectedTable != null && loaded.contains(selectedTable)) {
                tables.setValue(selectedTable);
            } else if (!loaded.isEmpty()) {
                tables.setValue(loaded.get(0));
            }
            if (selectedDialect != null && catalog.dialects().contains(selectedDialect)) {
                dialects.setValue(selectedDialect);
            } else if (!catalog.dialects().isEmpty()) {
                dialects.setValue(catalog.dialects().get(0));
            }
        });
    }

    private void loadRecords() {
        String table = tables.getValue();
        String suiteId = dialects.getValue();
        records.getItems().clear();
        records.getColumns().clear();
        recordColumns = List.of();
        request.clear();
        if (table == null) {
            return;
        }
        Ui.async(records, () -> {
            if ("TRANSACTION_LOG".equalsIgnoreCase(table)) {
                ctx.txLog().flush();
            }
            return ctx.dbAdmin().tableRows(table, suiteId, MAX_RECORDS);
        }, result -> {
            if (!table.equals(tables.getValue()) || !java.util.Objects.equals(suiteId, dialects.getValue())) {
                return;
            }
            showRecords(result);
        });
    }

    private void showRecords(SqlResult result) {
        recordColumns = List.copyOf(result.columns());
        Ui.fillRows(records, result.columns(), result.rows());
        if (!records.getItems().isEmpty()) {
            records.getSelectionModel().selectFirst();
        } else {
            resultText("La tabla seleccionada no contiene registros.");
        }
    }

    private void showMessage(List<String> row) {
        request.clear();
        if (row == null) {
            return;
        }
        int jsonColumn = indexOfColumn("REQUEST");
        if (jsonColumn < 0) {
            jsonColumn = indexOfColumn("REQUEST_JSON");
        }
        if (jsonColumn < 0) {
            resultText("La tabla seleccionada no contiene una columna REQUEST.");
            return;
        }
        String json = jsonColumn < row.size() ? row.get(jsonColumn) : null;
        if (json == null || json.isBlank()) {
            resultText("El registro seleccionado no contiene un mensaje ISO.");
            return;
        }
        try {
            request.setText(Ui.pretty(Json.mapper().readTree(json)));
            result.setText("");
            showConfiguredValidation(row);
        } catch (Exception e) {
            request.setText(json);
            resultText("El registro contiene JSON inválido: " + Ui.root(e).getMessage());
        }
    }

    private void showConfiguredValidation(List<String> row) {
        issues.getItems().setAll(
                "CONFIG REQUEST: validación estructural según el dialect",
                "CONFIG RESPONSE: MTI esperado y comparación de campos eco",
                "CONFIG EXPECTED_RC: " + orElse(value(row, "EXPECTED_RC"), "(sin control)"),
                "CONFIG IGNORE_FIELDS: " + orElse(value(row, "IGNORE_FIELDS"), "(ninguno)"),
                "Pendiente de ejecución: envíe el mensaje para obtener el resultado.");
    }

    private int indexOfColumn(String name) {
        for (int i = 0; i < recordColumns.size(); i++) {
            if (name.equalsIgnoreCase(recordColumns.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private void resultText(String text) {
        result.setStyle("");
        result.setText(text);
    }

    private void saveAsNew() {
        if (request.getText().isBlank()) {
            Ui.error("No hay un mensaje JSON para guardar");
            return;
        }
        showRecordDialog("Guardar transacción como nuevo registro", tables.getValue(), true, null).ifPresent(data ->
                Ui.async(records, () -> ctx.dbAdmin().insertMessage(data.table(), data.suiteId(), data.name(),
                        data.request(), data.expectedRc(), data.ignoreFields(), data.enabled()), id -> {
                    tables.setValue(data.table());
                    dialects.setValue(data.suiteId());
                    loadRecords();
                    Ui.info("Registro #" + id + " guardado en " + data.table());
                }));
    }

    private void updateSelected() {
        List<String> row = records.getSelectionModel().getSelectedItem();
        String table = tables.getValue();
        String id = value(row, "ID");
        if (row == null || id == null || !ctx.dbAdmin().reusableMessageTables().contains(table)) {
            Ui.error("Seleccione un registro editable de una tabla de mensajes");
            return;
        }
        showRecordDialog("Actualizar registro #" + id, table, false, row).ifPresent(data ->
                Ui.async(records, () -> {
                    ctx.dbAdmin().updateMessage(table, Long.parseLong(id), data.suiteId(), data.name(), data.request(),
                            data.expectedRc(), data.ignoreFields(), data.enabled());
                    return null;
                }, ignored -> {
                    dialects.setValue(data.suiteId());
                    loadRecords();
                    Ui.info("Registro #" + id + " actualizado");
                }));
    }

    private Optional<RecordFormData> showRecordDialog(String title, String selectedTable, boolean allowTableChange,
                                                       List<String> row) {
        List<String> targetTables = ctx.dbAdmin().reusableMessageTables();
        List<String> availableDialects = ctx.dbAdmin().dialects();
        if (targetTables.isEmpty() || availableDialects.isEmpty()) {
            Ui.error("Cree primero una tabla de mensajes y un dialect en el módulo Base de datos");
            return Optional.empty();
        }

        ComboBox<String> targetTable = new ComboBox<>(FXCollections.observableArrayList(targetTables));
        ComboBox<String> suite = new ComboBox<>(FXCollections.observableArrayList(availableDialects));
        TextField name = new TextField();
        TextField expectedRc = new TextField("00");
        TextField ignoreFields = new TextField();
        CheckBox enabled = new CheckBox("Habilitado");
        TextArea message = Ui.code(true);
        targetTable.setValue(targetTables.contains(selectedTable) ? selectedTable : targetTables.get(0));
        targetTable.setDisable(!allowTableChange);
        String rowSuite = value(row, "SUITE_ID");
        suite.setValue(availableDialects.contains(rowSuite) ? rowSuite
                : availableDialects.contains(dialects.getValue()) ? dialects.getValue() : availableDialects.get(0));
        name.setPromptText("Nombre descriptivo");
        name.setText(orElse(value(row, "NAME"), ""));
        expectedRc.setText(orElse(value(row, "EXPECTED_RC"), "00"));
        ignoreFields.setText(orElse(value(row, "IGNORE_FIELDS"), ""));
        message.setText(row == null ? request.getText() : orElse(value(row, "REQUEST"), request.getText()));
        message.setPrefRowCount(12);
        enabled.setSelected(row == null || Boolean.parseBoolean(orElse(value(row, "ENABLED"), "true")));

        GridPane form = Ui.form("Tabla", targetTable, "Suite / dialect", suite, "Nombre", name,
                "RC esperado", expectedRc, "Campos ignorados", ignoreFields,
                "Estado", enabled, "Request JSON", message);
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.setHeaderText("Complete los datos del registro");
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane().setPrefSize(680, 620);
        Ui.styleDialog(dialog.getDialogPane());

        Node accept = dialog.getDialogPane().lookupButton(ButtonType.OK);
        accept.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            if (targetTable.getValue() == null || suite.getValue() == null || name.getText().isBlank()
                    || message.getText().isBlank()) {
                event.consume();
                Ui.error("Tabla, suite, nombre y request son obligatorios");
                return;
            }
            try {
                ctx.messages().fromJson(message.getText());
            } catch (RuntimeException e) {
                event.consume();
                Ui.error("Request JSON inválido: " + Ui.root(e).getMessage());
            }
        });

        if (dialog.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            return Optional.of(new RecordFormData(targetTable.getValue(), suite.getValue(), name.getText().trim(),
                    message.getText(), blankToNull(expectedRc.getText()), blankToNull(ignoreFields.getText()),
                    enabled.isSelected()));
        }
        return Optional.empty();
    }

    private String value(List<String> row, String column) {
        int index = indexOfColumn(column);
        return row == null || index < 0 || index >= row.size() ? null : row.get(index);
    }

    private static String orElse(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record Catalog(List<String> tables, List<String> dialects) {
    }

    private record RecordFormData(String table, String suiteId, String name, String request, String expectedRc,
                                  String ignoreFields, boolean enabled) {
    }

    private void connect() {
        String m = mode.getValue();
        if (issuerClient) {
            String host = clientHost.getText().trim();
            int port;
            try {
                port = Integer.parseInt(clientPort.getText().trim());
            } catch (NumberFormatException e) {
                Ui.error("Puerto inválido");
                return;
            }
            if (host.isEmpty()) {
                Ui.error("Ingrese la IP destino");
                return;
            }
            Framing f = framing.getValue();
            Ui.async(root.getTop(), () -> {
                ctx.connectIssuerClient(host, port, f);
                return null;
            }, x -> updateStatus());
            return;
        }
        Framing f = framing.getValue();
        Ui.async(root.getTop(), () -> {
            if ("API".equals(m)) {
                ctx.connectAcquirerApi();
            } else {
                ctx.connectAcquirerTcp(f);
            }
            return null;
        }, x -> updateStatus());
    }

    private void updateStatus() {
        var ch = service().channel();
        status.setText(ch != null && service().isConnected() ? "Conectado: " + ch.describe() : "Desconectado");
    }

    private void sendEdited() {
        try {
            if (request.getText().isBlank()) {
                throw new IllegalStateException("Seleccione un registro que contenga un mensaje ISO");
            }
            IsoMessage msg = ctx.messages().fromJson(request.getText());
            send(msg, selectedValidation());
        } catch (Exception e) {
            Ui.error(e);
        }
    }

    private ValidationProfile selectedValidation() {
        List<String> row = records.getSelectionModel().getSelectedItem();
        ValidationProfile profile = ValidationProfile.expecting(blankToNull(value(row, "EXPECTED_RC")));
        String expectedRc = blankToNull(value(row, "EXPECTED_RC"));
        if (expectedRc != null) {
            profile.setExpectedResponseCode(expectedRc);
        }
        String ignored = value(row, "IGNORE_FIELDS");
        if (ignored != null && !ignored.isBlank() && profile.getEchoFields() != null) {
            Set<Integer> fields = Arrays.stream(ignored.split("[,; ]+"))
                    .filter(s -> !s.isBlank())
                    .map(s -> Integer.parseInt(s.replaceAll("\\D", "")))
                    .collect(Collectors.toSet());
            profile.setEchoFields(profile.getEchoFields().stream().filter(f -> !fields.contains(f)).toList());
        }
        return profile;
    }

    private void send(IsoMessage msg, ValidationProfile profile) {
        if (!connected()) {
            return;
        }
        activeValidation = profile;
        result.setText("Enviando...");
        Ui.async(null, () -> service().send(msg, profile, source()).join(), this::show);
    }

    private void echo() {
        if (!connected()) {
            return;
        }
        activeValidation = null;
        Ui.async(null, () -> service().echoTest().join(), this::show);
    }

    private void reverse() {
        if (last == null) {
            Ui.error("No hay transacción previa para reversar");
            return;
        }
        if (!connected()) {
            return;
        }
        activeValidation = null;
        Ui.async(null, () -> service().reverse(last, "0420").join(), this::show);
    }

    private boolean connected() {
        if (!service().isConnected()) {
            Ui.error("El canal no está conectado. Presione 'Conectar'.");
            return false;
        }
        return true;
    }

    private void show(TransactionOutcome o) {
        last = o;
        result.setText(String.format("%s | RC=%s | %.2f ms%s", o.status(), o.responseCode() == null ? "-" : o.responseCode(),
                o.latencyMillis(), o.passed() ? " | Validación OK" : " | Validación con errores"));
        result.setStyle(o.passed() ? "-fx-text-fill: -color-success-fg;" : "-fx-text-fill: -color-danger-fg;");
        response.setText(o.response() != null ? service().maskedJson(o.response())
                : (o.error() != null ? o.error() : "(sin respuesta)"));
        issues.getItems().clear();
        if (activeValidation != null) {
            issues.getItems().add((o.validation() != null && o.validation().passed() ? "RESULTADO: VALIDACIÓN OK"
                    : "RESULTADO: VALIDACIÓN CON ERRORES"));
            issues.getItems().add("REQUEST: estructura=" + activeValidation.isStructural()
                    + ", reglas=" + activeValidation.getRequestRules().size());
            issues.getItems().add("RESPONSE: MTI=" + activeValidation.isCheckResponseMti()
                    + ", echo=" + activeValidation.getEchoFields()
                    + ", reglas=" + activeValidation.getResponseRules().size());
            issues.getItems().add("EXPECTED_RC: "
                    + orElse(activeValidation.getExpectedResponseCode(), "(sin control)"));
        }
        if (o.validation() != null) {
            for (ValidationIssue i : o.validation().issues()) {
                issues.getItems().add(i.toString());
            }
        }
        if (o.validation() != null && o.validation().issues().isEmpty()) {
            issues.getItems().add("Sin observaciones: todos los controles configurados se cumplieron.");
        } else if (issues.getItems().isEmpty()) {
            issues.getItems().add("La operación no tiene un perfil de validación asociado.");
        }
        renderHex();
        trace.getItems().clear();
        if (o.rawResponse() != null && o.response() != null) {
            try {
                trace.getItems().setAll(ctx.messages().trace(o.response().getDialect(), o.rawResponse()));
            } catch (Exception ignored) {
                // API responses are JSON, not wire format
            }
        }
    }

    private void renderHex() {
        if (last == null) {
            hex.clear();
        } else if (!showSensitive.isSelected()) {
            hex.setText("Contenido crudo oculto (puede contener PIN y datos sensibles de track).\n"
                    + "El PAN se muestra en texto plano en los mensajes JSON.\n"
                    + "Habilite 'Mostrar hex (datos sensibles)' para visualizarlo. Nunca se persiste en la base.");
        } else {
            hex.setText("REQUEST (" + len(last.rawRequest()) + " bytes)\n" + dump(last.rawRequest())
                    + "\n\nRESPONSE (" + len(last.rawResponse()) + " bytes)\n" + dump(last.rawResponse()));
        }
    }

    private static int len(byte[] b) {
        return b == null ? 0 : b.length;
    }

    /** Classic hex dump with an ASCII column. */
    static String dump(byte[] b) {
        if (b == null) {
            return "(vacío)";
        }
        StringBuilder sb = new StringBuilder();
        HexFormat hf = HexFormat.of().withUpperCase();
        for (int i = 0; i < b.length; i += 16) {
            sb.append(String.format("%04X  ", i));
            StringBuilder ascii = new StringBuilder();
            for (int j = 0; j < 16; j++) {
                if (i + j < b.length) {
                    sb.append(hf.toHexDigits(b[i + j])).append(' ');
                    int c = b[i + j] & 0xFF;
                    ascii.append(c >= 32 && c < 127 ? (char) c : '.');
                } else {
                    sb.append("   ");
                }
            }
            sb.append(' ').append(ascii).append('\n');
        }
        return sb.toString();
    }

    @Override
    public String title() {
        return issuerClient ? "Emisión - Cliente" : "Adquirencia";
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public void onShow() {
        updateStatus();
        loadTables();
    }
}
