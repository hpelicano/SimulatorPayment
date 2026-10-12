package org.simulator.ui.view;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.simulator.common.json.Json;
import org.simulator.iso.codec.FieldTrace;
import org.simulator.iso.codec.IsoParseException;
import org.simulator.iso.codec.Tlv;
import org.simulator.iso.IsoMessage;
import org.simulator.iso.dialect.FieldDef;
import org.simulator.ui.AppContext;
import org.simulator.ui.util.Ui;
import org.simulator.validator.ValidationResult;

import java.util.HexFormat;
import java.util.List;

/** Message parser workbench: JSON ⇄ wire (hex), field trace, dialect conversion and structural validation. */
public class ParserView implements View {

    private final AppContext ctx;
    private final BorderPane root = new BorderPane();
    private final ChoiceBox<String> dialect = new ChoiceBox<>();
    private final ChoiceBox<String> target = new ChoiceBox<>();
    private final ComboBox<String> tables = new ComboBox<>();
    private final ComboBox<String> suites = new ComboBox<>();
    private final TableView<List<String>> records = new TableView<>();
    private final Label recordInfo = new Label();
    private List<String> recordColumns = List.of();
    private boolean loadingCatalog;
    private long catalogVersion;
    private long recordsVersion;
    private static final String ALL_SUITES = "(Todas)";
    private final TextArea json = Ui.code(true);
    private final TextArea hex = Ui.code(true);
    private final TableView<FieldTrace> trace = new TableView<>();
    private final TableView<Tlv.Detail> tlv = new TableView<>();
    private final Label tlvInfo = new Label("Seleccione un Data Element TLV en Trace.");
    private final TextArea tlvValue = Ui.code(false);
    private final ListView<String> issues = new ListView<>();
    private final TextArea definition = Ui.code(false);
    private final Label info = new Label();

    public ParserView(AppContext ctx) {
        this.ctx = ctx;
        List<String> ids = ctx.messages().registry().ids();
        dialect.setItems(FXCollections.observableArrayList(ids));
        target.setItems(FXCollections.observableArrayList(ids));
        if (!ids.isEmpty()) {
            dialect.setValue(ids.contains("base24-pos") ? "base24-pos" : ids.get(0));
            target.setValue(ids.contains("iso87-ascii") ? "iso87-ascii" : ids.get(0));
        }
        dialect.valueProperty().addListener((o, a, b) -> {
            trace.getItems().clear();
            showTlv(null);
            showDefinition();
        });
        hex.setWrapText(true);
        tables.setMaxWidth(Double.MAX_VALUE);
        suites.setMaxWidth(Double.MAX_VALUE);
        tables.valueProperty().addListener((o, a, b) -> {
            if (!loadingCatalog) loadRecords();
        });
        suites.valueProperty().addListener((o, a, b) -> {
            if (!loadingCatalog) loadRecords();
        });
        records.setFixedCellSize(24);
        records.setPlaceholder(new Label("Sin transacciones"));
        records.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> loadMessage(b));
        recordInfo.setWrapText(true);

        var top = Ui.bar(Ui.title("Parser de mensajería"), new Label("Formato:"), dialect,
                Ui.button("JSON → Hex", this::toHex), Ui.button("Hex → JSON", this::toJson),
                Ui.button("Trace", this::trace), Ui.button("Validar estructura", this::validate),
                new Label("Convertir a:"), target, Ui.button("Convertir", this::convert),
                Ui.button("Formatear JSON", this::pretty));

        trace.getColumns().addAll(List.of(
                Ui.column("Elemento", 80, FieldTrace::element), Ui.column("Nombre", 200, FieldTrace::name),
                Ui.column("Offset", 60, FieldTrace::offset), Ui.column("Long.", 60, FieldTrace::length),
                Ui.column("Valor", 260, FieldTrace::value), Ui.column("Hex", 320, FieldTrace::rawHex)));
        trace.setFixedCellSize(26);
        trace.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> showTlv(b));
        tlv.getColumns().addAll(List.of(
                Ui.column("Tag", 65, Tlv.Detail::tag),
                Ui.column("Descripción", 240, Tlv.Detail::description),
                Ui.column("Long. (bytes)", 85, Tlv.Detail::length),
                Ui.column("Valor (hex)", 220, Tlv.Detail::value),
                Ui.column("Ruta", 150, Tlv.Detail::path),
                Ui.column("Offset DE", 80, Tlv.Detail::offset)));
        tlv.setFixedCellSize(26);
        tlv.setPlaceholder(new Label("Sin datos TLV para mostrar"));
        tlvInfo.setWrapText(true);
        tlvValue.setPrefRowCount(3);
        tlvValue.setWrapText(true);
        tlv.getSelectionModel().selectedItemProperty().addListener((o, a, b) ->
                tlvValue.setText(b == null ? "" : b.path() + " - " + b.description()
                        + "\nLongitud: " + b.length() + " bytes; offset en DE: " + b.offset()
                        + (b.constructed() ? "; construido" : "; primitivo") + "\n" + b.value()));

        VBox left = new VBox(4, new Label("JSON"), json);
        VBox.setVgrow(json, Priority.ALWAYS);
        VBox right = new VBox(4, new Label("Wire (hex)"), hex);
        VBox.setVgrow(hex, Priority.ALWAYS);
        SplitPane editors = new SplitPane(left, right);

        VBox tracePane = new VBox(4, new Label("Trace de Data Elements"), trace);
        VBox.setVgrow(trace, Priority.ALWAYS);
        VBox tlvPane = new VBox(4, new Label("Detalle TLV / EMV"), tlvInfo, tlv, tlvValue);
        VBox.setVgrow(tlv, Priority.ALWAYS);
        SplitPane traceDetails = new SplitPane(tracePane, tlvPane);
        traceDetails.setDividerPositions(0.5);
        TabPane tabs = new TabPane(new Tab("Trace", traceDetails), new Tab("Validación", issues),
                new Tab("Definición del formato", definition));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        SplitPane vertical = new SplitPane(editors, tabs);
        vertical.setOrientation(javafx.geometry.Orientation.VERTICAL);
        vertical.setDividerPositions(0.55);

        VBox source = new VBox(4, new Label("Tablas H2"), tables,
                new Label("Suite / dialect"), suites, Ui.button("Reload", this::loadTables),
                new Label("Transacciones (seleccione para parsear)"), records, recordInfo);
        VBox.setVgrow(records, Priority.ALWAYS);
        source.setMinWidth(180);
        SplitPane workspace = new SplitPane(source, vertical);
        workspace.setDividerPositions(0.23);
        root.setTop(new VBox(4, top, info));
        root.setCenter(workspace);
        root.setPadding(new Insets(12));
        showDefinition();
    }

    private void loadTables() {
        long version = ++catalogVersion;
        ++recordsVersion;
        String selectedTable = tables.getValue();
        String selectedSuite = suites.getValue();
        Ui.async(null, () -> new Catalog(ctx.dbAdmin().messageTables(), ctx.dbAdmin().dialects()), catalog -> {
            if (version != catalogVersion) return;
            loadingCatalog = true;
            try {
                tables.getItems().setAll(catalog.tables());
                suites.getItems().setAll(ALL_SUITES);
                suites.getItems().addAll(catalog.suites());
                tables.setValue(catalog.tables().contains(selectedTable) ? selectedTable
                        : catalog.tables().contains("UNIT_TEST") ? "UNIT_TEST"
                        : catalog.tables().isEmpty() ? null : catalog.tables().get(0));
                suites.setValue(suites.getItems().contains(selectedSuite) ? selectedSuite : ALL_SUITES);
            } finally {
                loadingCatalog = false;
            }
            loadRecords();
        });
    }

    private void loadRecords() {
        long version = ++recordsVersion;
        String table = tables.getValue();
        String suite = ALL_SUITES.equals(suites.getValue()) ? null : suites.getValue();
        records.getSelectionModel().clearSelection();
        records.getItems().clear();
        records.getColumns().clear();
        recordColumns = List.of();
        clearMessage();
        if (table == null) {
            recordInfo.setText("No hay tablas con mensajes disponibles.");
            return;
        }
        recordInfo.setText("Cargando " + table + "...");
        Ui.async(null, () -> {
            if ("TRANSACTION_LOG".equalsIgnoreCase(table)) ctx.txLog().flush();
            return ctx.dbAdmin().tableRows(table, suite, 1000);
        }, result -> {
            if (version != recordsVersion) return;
            recordColumns = List.copyOf(result.columns());
            for (String column : List.of("ID", "NAME", "SUITE_ID", "DIALECT", "MTI", "STATUS", "CREATED_AT")) {
                int index = columnIndex(column);
                if (index >= 0) {
                    records.getColumns().add(Ui.column(column, "NAME".equals(column) ? 200 : 100,
                            (List<String> row) -> row.get(index)));
                }
            }
            records.getItems().setAll(result.rows());
            recordInfo.setText(result.rows().size() + " registros (máximo 1000). "
                    + "Carga de REQUEST/REQUEST_JSON, sin envío ni modificación en H2.");
            records.getSelectionModel().selectFirst();
        });
    }

    private int columnIndex(String name) {
        for (int i = 0; i < recordColumns.size(); i++) {
            if (name.equalsIgnoreCase(recordColumns.get(i))) return i;
        }
        return -1;
    }

    private String value(List<String> row, String name) {
        int index = columnIndex(name);
        return index < 0 ? null : row.get(index);
    }

    private void clearMessage() {
        json.clear();
        hex.clear();
        trace.getItems().clear();
        showTlv(null);
        issues.getItems().clear();
        info.setText("");
    }

    private void loadMessage(List<String> row) {
        clearMessage();
        if (row == null) return;
        String request = value(row, "REQUEST");
        if (request == null || request.isBlank()) request = value(row, "REQUEST_JSON");
        if (request == null || request.isBlank()) {
            info.setText("El registro seleccionado no contiene REQUEST/REQUEST_JSON.");
            return;
        }
        json.setText(request);
        try {
            var tree = Json.mapper().readTree(request);
            if (tree == null || !tree.isObject()) {
                throw new IllegalArgumentException("El mensaje debe ser un objeto JSON.");
            }
            json.setText(Ui.pretty(tree));
            IsoMessage message = ctx.messages().fromJsonNode(tree);
            if (message.getDialect() == null || message.getDialect().isBlank()) {
                String format = value(row, "DIALECT");
                if (format == null || format.isBlank()) format = value(row, "SUITE_ID");
                if (format == null || !ctx.messages().registry().ids().contains(format)) {
                    info.setText("El registro no indica un dialect válido. Seleccione Formato y use JSON → Hex.");
                    return;
                }
                message.setDialect(format);
            }
            dialect.setValue(message.getDialect());
            byte[] wire = ctx.messages().pack(message);
            json.setText(Ui.pretty(ctx.messages().toJsonNode(message)));
            hex.setText(HexFormat.of().withUpperCase().formatHex(wire));
            refreshTrace(message.getDialect(), wire);
            info.setText(tables.getValue() + " #" + value(row, "ID") + " - " + wire.length + " bytes (sin envío)");
        } catch (java.io.IOException | org.simulator.common.SimulatorException | IllegalArgumentException e) {
            info.setText("No se pudo parsear el registro: " + e.getMessage()
                    + ". El JSON permanece visible para revisión.");
        }
    }

    private record Catalog(List<String> tables, List<String> suites) {
    }

    private IsoMessage parseJson() {
        IsoMessage m = ctx.messages().fromJson(json.getText());
        if (m.getDialect() == null) {
            m.setDialect(dialect.getValue());
        }
        return m;
    }

    private void toHex() {
        try {
            IsoMessage m = parseJson();
            byte[] wire = ctx.messages().pack(m);
            hex.setText(HexFormat.of().withUpperCase().formatHex(wire));
            dialect.setValue(m.getDialect());
            info.setText("Empaquetado OK: " + wire.length + " bytes");
            refreshTrace(m.getDialect(), wire);
        } catch (Exception e) {
            Ui.error(e);
        }
    }

    private byte[] wire() {
        return HexFormat.of().parseHex(hex.getText().replaceAll("[^0-9A-Fa-f]", ""));
    }

    private void toJson() {
        try {
            byte[] w = wire();
            IsoMessage m = ctx.messages().unpack(dialect.getValue(), w);
            json.setText(ctx.messages().toJson(m));
            info.setText("Desempaquetado OK: " + w.length + " bytes, MTI " + m.getMti() + ", " + m.fields().size() + " campos");
            refreshTrace(dialect.getValue(), w);
        } catch (Exception e) {
            Ui.error(e);
        }
    }

    private void trace() {
        try {
            if (hex.getText().isBlank()) {
                IsoMessage m = parseJson();
                refreshTrace(m.getDialect(), ctx.messages().pack(m));
            } else {
                refreshTrace(dialect.getValue(), wire());
            }
        } catch (Exception e) {
            Ui.error(e);
        }
    }

    private void refreshTrace(String format, byte[] wire) {
        List<FieldTrace> rows = ctx.messages().trace(format, wire);
        dialect.setValue(format);
        trace.getSelectionModel().clearSelection();
        trace.getItems().setAll(rows);
        showTlv(null);
        rows.stream().filter(this::isTlv).findFirst().ifPresent(row -> trace.getSelectionModel().select(row));
    }

    private boolean isTlv(FieldTrace row) {
        if (!row.element().startsWith("DE")) {
            return false;
        }
        FieldDef def = ctx.messages().dialect(dialect.getValue()).field(Integer.parseInt(row.element().substring(2)));
        return def != null && ("BER_TLV".equalsIgnoreCase(def.getSubFormat())
                || "TLV".equalsIgnoreCase(def.getSubFormat()));
    }

    private void showTlv(FieldTrace row) {
        tlv.getSelectionModel().clearSelection();
        tlv.getItems().clear();
        tlvValue.clear();
        if (row == null || !isTlv(row)) {
            tlvInfo.setText(row == null ? "Seleccione un Data Element TLV en Trace."
                    : row.element() + " no está definido como TLV en este formato.");
            return;
        }
        try {
            int field = Integer.parseInt(row.element().substring(2));
            List<Tlv.Detail> details = Tlv.parseDetailed(row.value(), field);
            tlv.getItems().setAll(details);
            tlvInfo.setText(row.element() + " - " + row.name() + ": " + details.size()
                    + " tags. Offsets relativos al contenido del DE, sin prefijo de longitud.");
        } catch (IsoParseException | IllegalArgumentException e) {
            tlvInfo.setText("TLV inválido en " + row.element() + ": " + e.getMessage());
        }
    }

    private void validate() {
        try {
            IsoMessage m = parseJson();
            ValidationResult r = ctx.validator().validateStructure("message", m, ctx.messages().dialect(m.getDialect()));
            issues.getItems().clear();
            r.issues().forEach(i -> issues.getItems().add(i.toString()));
            if (issues.getItems().isEmpty()) {
                issues.getItems().add("Estructura válida para " + m.getDialect());
            }
            info.setText(r.passed() ? "Validación OK" : "Errores de validación: " + r.errorCount());
        } catch (Exception e) {
            Ui.error(e);
        }
    }

    private void convert() {
        try {
            IsoMessage converted = ctx.messages().convert(parseJson(), target.getValue());
            json.setText(ctx.messages().toJson(converted));
            dialect.setValue(target.getValue());
            hex.clear();
            trace.getItems().clear();
            showTlv(null);
            info.setText("Convertido a " + target.getValue());
        } catch (Exception e) {
            Ui.error(e);
        }
    }

    private void pretty() {
        try {
            json.setText(Ui.pretty(Json.mapper().readTree(json.getText())));
        } catch (Exception e) {
            Ui.error("JSON inválido: " + e.getMessage());
        }
    }

    private void showDefinition() {
        try {
            definition.setText(Ui.pretty(ctx.messages().dialect(dialect.getValue())));
        } catch (Exception e) {
            definition.setText(String.valueOf(e.getMessage()));
        }
    }

    @Override
    public String title() {
        return "Parser";
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public void onShow() {
        loadTables();
    }
}
