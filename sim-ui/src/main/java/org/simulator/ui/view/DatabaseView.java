package org.simulator.ui.view;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TableCell;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.collections.FXCollections;
import org.simulator.common.json.Json;
import org.simulator.persistence.DbAdminService.SqlResult;
import org.simulator.persistence.repo.TransactionLogRecord;
import org.simulator.ui.AppContext;
import org.simulator.ui.util.Ui;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Local H2 database management: SQL console, table browser, transaction log, backup / restore and purge. */
public class DatabaseView implements View {

    private static final Set<String> HIDDEN_TABLES = Set.of(
            "AUDIT_LOG", "SYSTEM_AUDIT_LOG", "BASE24_SNAPSHOT", "FLYWAY_SCHEMA_HISTORY");
    private final AppContext ctx;
    private final BorderPane root = new BorderPane();
    private final ListView<String> tables = new ListView<>();
    private final TextArea sql = Ui.code(true);
    private final TableView<List<String>> grid = new TableView<>();
    private final Label info = new Label();
    private final TextField maxRows = new TextField("1000");
    private final TableView<TransactionLogRecord> log = new TableView<>();
    private final TextArea logDetail = Ui.code(false);

    public DatabaseView(AppContext ctx) {
        this.ctx = ctx;
        tables.getStyleClass().add("database-table-list");
        grid.setFixedCellSize(24);
        log.setFixedCellSize(24);
        maxRows.setPrefColumnCount(6);
        tables.setOnMouseClicked(e -> {
            String t = tables.getSelectionModel().getSelectedItem();
            if (t != null && e.getClickCount() == 2) {
                sql.setText("SELECT * FROM " + t + " ORDER BY 1 DESC LIMIT 200");
                execute();
            }
        });
        VBox left = new VBox(6, new Label("Tablas (doble click)"), tables,
                Ui.button("Nueva tabla de mensajes", this::createMessageTable),
                Ui.button("Nuevo dialect", this::createDialect),
                Ui.button("Exportar tabla JSON", this::exportTable));
        VBox.setVgrow(tables, Priority.ALWAYS);
        left.setPrefWidth(220);

        sql.setPrefRowCount(6);
        sql.setText("SELECT response_code, outcome, COUNT(*) AS cantidad FROM transaction_log GROUP BY response_code, outcome");
        sql.setOnKeyPressed(e -> {
            if (e.isControlDown() && e.getCode() == KeyCode.ENTER) {
                execute();
                e.consume();
            }
        });
        var newRecord = Ui.button("Nuevo registro", this::createMessageRecord);
        newRecord.getStyleClass().add("success");
        VBox console = new VBox(6, Ui.bar(new Label("SQL (Ctrl+Enter ejecuta)"), Ui.spacer(), new Label("Máx. filas:"), maxRows,
                Ui.button("Ejecutar", this::execute), newRecord,
                Ui.button("Exportar CSV", this::exportCsv)), sql, info, grid);
        VBox.setVgrow(grid, Priority.ALWAYS);
        SplitPane consoleSplit = new SplitPane(left, console);
        consoleSplit.setDividerPositions(0.2);

        log.getColumns().addAll(List.of(
                Ui.column("Id", 60, TransactionLogRecord::id), Ui.column("Fecha", 170, TransactionLogRecord::createdAt),
                Ui.column("Origen", 80, TransactionLogRecord::source), Ui.column("Entorno", 60, TransactionLogRecord::environment),
                Ui.column("MTI", 50, TransactionLogRecord::mti), Ui.column("PC", 60, TransactionLogRecord::processingCode),
                Ui.column("STAN", 60, TransactionLogRecord::stan), Ui.column("RRN", 100, TransactionLogRecord::rrn),
                Ui.column("PAN", 140, TransactionLogRecord::panMasked), Ui.column("Importe", 100, TransactionLogRecord::amount),
                Ui.column("RC", 40, TransactionLogRecord::responseCode), Ui.column("Outcome", 80, TransactionLogRecord::outcome),
                Ui.column("Latencia ms", 80, (TransactionLogRecord r) -> r.latencyMicros() == null ? "" : Ui.fmt(r.latencyMicros() / 1000.0)),
                Ui.column("Error", 200, TransactionLogRecord::errorMessage)));
        log.getSelectionModel().selectedItemProperty().addListener((o, a, r) -> logDetail.setText(r == null ? ""
                : "REQUEST\n" + nz(r.requestJson()) + "\n\nRESPONSE\n" + nz(r.responseJson())));
        SplitPane logSplit = new SplitPane(log, logDetail);
        logSplit.setOrientation(javafx.geometry.Orientation.VERTICAL);
        VBox logBox = new VBox(6, Ui.bar(Ui.button("Refrescar", this::loadLog),
                new Label("(últimas 1000, PAN en texto plano; PIN y datos de track ocultos)")), logSplit);
        VBox.setVgrow(logSplit, Priority.ALWAYS);

        TabPane tabs = new TabPane(new Tab("Consola SQL", consoleSplit), new Tab("Log de transacciones", logBox));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        root.setTop(Ui.bar(Ui.title("Base de datos H2"), Ui.spacer(),
                Ui.button("Backup", this::backup), Ui.button("Restaurar", this::restore),
                Ui.button("Purgar log", this::purge)));
        root.setCenter(tabs);
        root.setPadding(new Insets(12));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private void execute() {
        String text = sql.getSelectedText() != null && !sql.getSelectedText().isBlank() ? sql.getSelectedText() : sql.getText();
        int max;
        try {
            max = Integer.parseInt(maxRows.getText().trim());
        } catch (NumberFormatException e) {
            max = 1000;
        }
        String upper = text.trim().toUpperCase();
        if ((upper.startsWith("DROP") || upper.startsWith("DELETE") || upper.startsWith("TRUNCATE"))
                && !Ui.confirm("Sentencia destructiva", "¿Ejecutar?\n\n" + text)) {
            return;
        }
        int m = max;
        Ui.async(null, () -> ctx.dbAdmin().execute(text, m), this::show);
    }

    private void show(SqlResult r) {
        grid.getColumns().clear();
        for (int i = 0; i < r.columns().size(); i++) {
            int index = i;
            String name = r.columns().get(i);
            var column = Ui.column(name, Math.max(80, Math.min(260, name.length() * 12)),
                    (List<String> row) -> index < row.size() ? row.get(index) : "");
            column.setCellFactory(c -> new TableCell<>() {
            {
                setWrapText(false);
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : singleLine(item));
                setGraphic(null);
            }
            });
            grid.getColumns().add(column);
        }
        grid.getItems().setAll(r.rows());
        info.setText(r.rows().size() + " filas | " + r.elapsedMs() + " ms");
        refreshTables();
    }

    private void exportCsv() {
        StringBuilder sb = new StringBuilder();
        grid.getColumns().forEach(c -> sb.append(c.getText()).append(';'));
        sb.append('\n');
        for (List<String> row : grid.getItems()) {
            for (String v : row) {
                sb.append(v == null ? "" : v.replace(";", ",").replace("\n", " ")).append(';');
            }
            sb.append('\n');
        }
        Ui.saveText(Ui.window(root), "Exportar CSV", "consulta.csv", sb.toString());
    }

    private void exportTable() {
        String t = tables.getSelectionModel().getSelectedItem();
        if (t != null) {
            Ui.async(null, () -> ctx.dbAdmin().exportTableJson(t),
                    json -> Ui.saveText(Ui.window(root), "Exportar tabla", t.toLowerCase() + ".json", json));
        }
    }

    private void createMessageTable() {
        TextField name = new TextField();
        name.setPromptText("ej: certificacion");
        Dialog<ButtonType> dialog = formDialog("Nueva tabla de mensajes",
                "La tabla usará la estructura estándar de transacciones", Ui.form("Nombre", name));
        Node accept = dialog.getDialogPane().lookupButton(ButtonType.OK);
        accept.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            if (!name.getText().trim().matches("[A-Za-z_][A-Za-z0-9_]*")) {
                event.consume();
                Ui.error("Use letras, números y guion bajo; el nombre debe comenzar con una letra");
            }
        });
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            Ui.async(tables, () -> {
                ctx.dbAdmin().createMessageTable(name.getText().trim());
                return visibleTables(ctx.dbAdmin().tables());
            }, loaded -> {
                tables.getItems().setAll(loaded);
                tables.getSelectionModel().select(name.getText().trim().toUpperCase());
            });
        }
    }

    private void createDialect() {
        TextField id = new TextField();
        TextField description = new TextField();
        id.setPromptText("ej: base24-nuevo");
        Dialog<ButtonType> dialog = formDialog("Nuevo dialect", "El identificador se usará como suite_id",
                Ui.form("Id", id, "Descripción", description));
        Node accept = dialog.getDialogPane().lookupButton(ButtonType.OK);
        accept.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            if (id.getText().isBlank()) {
                event.consume();
                Ui.error("El identificador del dialect es obligatorio");
            }
        });
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            Ui.async(tables, () -> {
                ctx.dbAdmin().createDialect(id.getText(), description.getText());
                return id.getText().trim();
            }, created -> Ui.info("Dialect '" + created + "' creado"));
        }
    }

    private void createMessageRecord() {
        List<String> targetTables = ctx.dbAdmin().reusableMessageTables();
        List<String> availableDialects = ctx.dbAdmin().dialects();
        if (targetTables.isEmpty() || availableDialects.isEmpty()) {
            Ui.error("Se necesita al menos una tabla de mensajes y un dialect registrado para crear el registro");
            return;
        }

        ComboBox<String> targetTable = new ComboBox<>(FXCollections.observableArrayList(targetTables));
        ComboBox<String> suiteId = new ComboBox<>(FXCollections.observableArrayList(availableDialects));
        TextField name = new TextField();
        TextField expectedRc = new TextField("00");
        TextField ignoreFields = new TextField();
        CheckBox enabled = new CheckBox("Habilitado");
        TextArea request = Ui.code(true);
        targetTable.setValue(targetTables.contains(tables.getSelectionModel().getSelectedItem())
                ? tables.getSelectionModel().getSelectedItem()
                : targetTables.stream().filter(t -> t.equalsIgnoreCase("UNIT_TEST")).findFirst()
                        .orElse(targetTables.get(0)));
        suiteId.setValue(availableDialects.get(0));
        name.setPromptText("Nombre descriptivo");
        ignoreFields.setPromptText("Ej: 7,11,37,38");
        request.setPromptText("{\"dialect\":\"" + availableDialects.get(0)
                + "\",\"mti\":\"0200\",\"fields\":{}}");
        request.setPrefRowCount(12);
        enabled.setSelected(true);

        Dialog<ButtonType> dialog = formDialog("Nuevo registro de transacción",
                "El ID y las fechas se asignan automáticamente; suite_id y request.dialect deben existir en DIALECT",
                Ui.form("Tabla", targetTable, "Suite ID", suiteId, "Nombre", name,
                        "RC esperado", expectedRc, "Campos ignorados", ignoreFields,
                        "Estado", enabled, "Request (JSON)", request));
        dialog.getDialogPane().setPrefSize(720, 650);
        Node accept = dialog.getDialogPane().lookupButton(ButtonType.OK);
        accept.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            if (targetTable.getValue() == null || suiteId.getValue() == null || name.getText().isBlank()
                    || request.getText().isBlank()) {
                event.consume();
                Ui.error("Tabla, suite_id, nombre y request son obligatorios");
                return;
            }
            try {
                var root = Json.mapper().readTree(request.getText());
                var dialect = root == null || !root.isObject() ? null : root.get("dialect");
                if (dialect == null || !dialect.isTextual() || dialect.asText().isBlank()) {
                    event.consume();
                    Ui.error("Request JSON debe incluir la propiedad dialect con un valor de texto");
                } else if (!availableDialects.contains(dialect.asText().trim())) {
                    event.consume();
                    Ui.error("El dialect '" + dialect.asText()
                            + "' del request no está registrado. Créelo primero desde Base de datos");
                }
            } catch (java.io.IOException e) {
                event.consume();
                Ui.error("Request JSON inválido: " + e.getMessage());
            }
        });

        if (dialog.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            String table = targetTable.getValue();
            String suite = suiteId.getValue();
            Ui.async(grid, () -> ctx.dbAdmin().insertMessage(table, suite, name.getText().trim(),
                    request.getText(), expectedRc.getText().isBlank() ? null : expectedRc.getText().trim(),
                    ignoreFields.getText().isBlank() ? null : ignoreFields.getText().trim(), enabled.isSelected()),
                    id -> {
                        tables.getSelectionModel().select(table);
                        sql.setText("SELECT * FROM " + table + " ORDER BY id DESC LIMIT 200");
                        execute();
                        Ui.info("Registro #" + id + " guardado de forma persistente en " + table);
                    });
        }
    }

    private static Dialog<ButtonType> formDialog(String title, String header, GridPane form) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.setHeaderText(header);
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Ui.styleDialog(dialog.getDialogPane());
        return dialog;
    }

    private void backup() {
        Ui.chooseSave(Ui.window(root), "Backup H2 (.zip o .sql)", "simulator-backup.zip").ifPresent(p ->
                Ui.async(null, () -> {
                    ctx.dbAdmin().backup(p);
                    return p;
                }, x -> Ui.info("Backup generado: " + x)));
    }

    private void restore() {
        Ui.chooseOpen(Ui.window(root), "Restaurar backup H2").ifPresent(p -> {
            if (Ui.confirm("Restaurar base", "Se reemplazarán los datos actuales con " + p.getFileName() + ". ¿Continuar?")) {
                Ui.async(null, () -> {
                    ctx.dbAdmin().restore(p);
                    return p;
                }, x -> {
                    refreshTables();
                    Ui.info("Base restaurada desde " + x);
                });
            }
        });
    }

    private void purge() {
        Ui.prompt("Eliminar registros del log con más de N días", String.valueOf(ctx.config().user().getLogRetentionDays()))
                .ifPresent(v -> {
                    try {
                        int days = Integer.parseInt(v);
                        Ui.async(null, () -> ctx.dbAdmin().purgeTransactionLog(days), n -> Ui.info(n + " registros eliminados"));
                    } catch (NumberFormatException e) {
                        Ui.error("Valor inválido");
                    }
                });
    }

    private void refreshTables() {
        Ui.async(null, () -> visibleTables(ctx.dbAdmin().tables()), l -> tables.getItems().setAll(l));
    }

    static List<String> visibleTables(List<String> names) {
        return names.stream().filter(name -> !HIDDEN_TABLES.contains(name.toUpperCase(Locale.ROOT))).toList();
    }

    static String singleLine(String value) {
        return value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
    }

    private void loadLog() {
        Ui.async(null, () -> {
            ctx.txLog().flush();
            return ctx.txLog().recent(1000);
        }, l -> log.getItems().setAll(l));
    }

    @Override
    public String title() {
        return "Base de datos";
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public void onShow() {
        refreshTables();
        loadLog();
        info.setText(ctx.dbAdmin().url());
    }
}
