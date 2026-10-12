package org.simulator.ui.view;

import javafx.scene.Node;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.simulator.base24.doc.DocBuilderService;
import org.simulator.base24.doc.DocBuilderService.AuditParameters;
import org.simulator.base24.doc.DocBuilderService.PtlfParameters;
import org.simulator.base24.doc.TransactionPdfExporter;
import org.simulator.base24.guardian.QueryResult;
import org.simulator.common.SimulatorException;
import org.simulator.common.json.Json;
import org.simulator.persistence.repo.TransactionLogRecord;
import org.simulator.ui.AppContext;
import org.simulator.ui.util.Ui;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

/** Read-only transaction documentation workbench, with remote evidence tied to the selected record. */
public final class DocBuilderPane {

    private final AppContext ctx;
    private final VBox root = new VBox(8);
    private final TableView<TransactionLogRecord> transactions = new TableView<>();
    private final TextArea request = Ui.code(false);
    private final TextArea response = Ui.code(false);
    private final TextField pan = new TextField();
    private final TextField terminal = new TextField();
    private final Label transactionInfo = new Label("Seleccione una transacción de TRANSACTION_LOG.");
    private final QueryPane ptlf = new QueryPane();
    private final QueryPane audit = new QueryPane();
    private final TableView<Map<String, String>> remoteRecords = new TableView<>();
    private final TextArea command = Ui.code(false);
    private final TextArea output = Ui.code(false);
    private final Label remoteInfo = new Label("Consultas pendientes.");
    private final VBox remoteForm = new VBox(6);
    private long remoteVersion;
    private long transactionVersion;
    private long refreshVersion;

    public DocBuilderPane(AppContext ctx) {
        this.ctx = ctx;
        pan.setEditable(false);
        terminal.setEditable(false);
        transactions.setFixedCellSize(24);
        transactions.setPrefHeight(160);
        transactions.getColumns().addAll(List.of(
                Ui.column("Id", 65, TransactionLogRecord::id),
                Ui.column("Fecha", 175, TransactionLogRecord::createdAt),
                Ui.column("Origen", 95, TransactionLogRecord::source),
                Ui.column("MTI", 55, TransactionLogRecord::mti),
                Ui.column("STAN", 85, TransactionLogRecord::stan),
                Ui.column("RRN", 110, TransactionLogRecord::rrn),
                Ui.column("PAN", 160, TransactionLogRecord::panMasked),
                Ui.column("Terminal", 105, TransactionLogRecord::terminalId),
                Ui.column("RC", 50, TransactionLogRecord::responseCode),
                Ui.column("Estado", 95, TransactionLogRecord::outcome)));
        transactions.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> selectTransaction(b));
        request.setPrefRowCount(7);
        response.setPrefRowCount(7);
        VBox requestBox = new VBox(4, new Label("Request JSON"), request);
        VBox responseBox = new VBox(4, new Label("Response JSON"), response);
        VBox.setVgrow(request, Priority.ALWAYS);
        VBox.setVgrow(response, Priority.ALWAYS);
        SplitPane messages = new SplitPane(requestBox, responseBox);
        messages.setOrientation(javafx.geometry.Orientation.VERTICAL);
        VBox.setVgrow(requestBox, Priority.ALWAYS);
        VBox.setVgrow(responseBox, Priority.ALWAYS);
        VBox transactionBox = new VBox(6,
                Ui.bar(new Label("TRANSACTION_LOG (últimas 1000)"), Ui.button("Reload", this::refresh),
                        Ui.button("Descargar PDF", this::exportPdf),
                        Ui.button("Help FUP / AUDIT", this::showHelp)),
                transactions, transactionInfo, messages);
        transactionInfo.setWrapText(true);
        VBox.setVgrow(messages, Priority.ALWAYS);

        ptlf.manual.setPromptText("Comando Guardian PTLF");
        audit.manual.setPromptText("Comando Guardian AUDIT");
        remoteRecords.setFixedCellSize(24);
        remoteRecords.setPrefHeight(180);
        command.setPrefRowCount(4);
        output.setPrefRowCount(4);
        remoteInfo.setWrapText(true);
        remoteForm.getChildren().addAll(
                Ui.form("PAN", pan, "Terminal", terminal,
                        "PTLF comando manual", ptlf.manual, "AUDIT comando manual", audit.manual),
                new Label("Registros remotos"), remoteRecords, remoteInfo,
                Ui.bar(Ui.button("Vista previa", this::preview), Ui.button("Ejecutar", this::execute)),
                new Label("Comando OSS / gtacl"), command,
                new Label("Salida completa / stderr"), output);
        VBox.setVgrow(remoteRecords, Priority.ALWAYS);
        SplitPane content = new SplitPane(transactionBox, remoteForm);
        content.setDividerPositions(0.5);
        root.getChildren().add(content);
        VBox.setVgrow(content, Priority.ALWAYS);
        for (TextField field : List.of(ptlf.manual, audit.manual)) {
            field.textProperty().addListener((o, a, b) -> clearRemoteResults());
        }
    }

    public Node node() { return root; }

    public void refresh() {
        long version = ++refreshVersion;
        TransactionLogRecord selected = transactions.getSelectionModel().getSelectedItem();
        Long id = selected == null ? null : selected.id();
        Ui.async(transactions, () -> {
            ctx.txLog().flush();
            return ctx.txLog().recent(1000);
        }, rows -> {
            if (version != refreshVersion) return;
            transactions.getSelectionModel().clearSelection();
            transactions.getItems().setAll(rows);
            if (id != null) {
                rows.stream().filter(row -> id.equals(row.id())).findFirst()
                        .ifPresent(row -> transactions.getSelectionModel().select(row));
            }
            if (transactions.getSelectionModel().getSelectedItem() == null) {
                transactions.getSelectionModel().selectFirst();
            }
            if (rows.isEmpty()) transactionInfo.setText("TRANSACTION_LOG no contiene transacciones. Envíe una transacción para registrarla.");
        });
    }

    private void selectTransaction(TransactionLogRecord row) {
        ++transactionVersion;
        clearRemoteResults();
        request.clear();
        response.clear();
        pan.clear();
        terminal.clear();
        if (row == null) {
            transactionInfo.setText("Seleccione una transacción.");
            return;
        }
        request.setText(displayJson(row.requestJson()));
        response.setText(displayJson(row.responseJson()));
        pan.setText(row.panMasked() == null ? "" : row.panMasked());
        terminal.setText(row.terminalId() == null ? "" : row.terminalId());
        transactionInfo.setText("Transacción #" + row.id() + " | " + row.environment()
                + " | " + row.outcome() + " | PAN en texto plano. Registros antiguos enmascarados no son recuperables.");
    }

    public void clearRemoteResults() {
        ++remoteVersion;
        ptlf.result = null;
        audit.result = null;
        remoteRecords.getItems().clear();
        remoteRecords.getColumns().clear();
        output.clear();
        command.clear();
        remoteInfo.setText("Consultas pendientes.");
    }

    private DocBuilderService service() {
        var ssh = ctx.ssh();
        return new DocBuilderService(ssh::exec, ctx.env().getDocBuilder(), ctx.runs());
    }

    private PtlfParameters ptlfParameters() {
        return new PtlfParameters(ptlf.manual.getText(), "", "", "", "", pan.getText(), terminal.getText());
    }

    private AuditParameters auditParameters() {
        return new AuditParameters(audit.manual.getText(), "", "", "", pan.getText(), terminal.getText());
    }

    static List<String> requestedQueries(String ptlfCommand, String auditCommand) {
        List<String> queries = new ArrayList<>();
        if (!ptlfCommand.isBlank()) queries.add("PTLF");
        if (!auditCommand.isBlank()) queries.add("AUDIT");
        if (queries.isEmpty()) throw new SimulatorException("Faltan datos: ingrese un comando manual PTLF o AUDIT.");
        return queries;
    }

    private String previewCommands(DocBuilderService service, PtlfParameters ptlfParams, AuditParameters auditParams) {
        List<String> commands = new ArrayList<>();
        for (String kind : requestedQueries(ptlfParams.manualCommand(), auditParams.manualCommand())) {
            commands.add(kind + ":\n" + ("PTLF".equals(kind)
                    ? service.previewPtlf(ptlfParams) : service.previewAudit(auditParams)));
        }
        return String.join("\n\n", commands);
    }

    private void preview() {
        try {
            requestedQueries(ptlf.manual.getText(), audit.manual.getText());
            command.setText(previewCommands(service(), ptlfParameters(), auditParameters()));
        } catch (SimulatorException | IllegalArgumentException e) {
            Ui.error(e);
        }
    }

    private void execute() {
        if (transactions.getSelectionModel().getSelectedItem() == null) {
            Ui.error("Seleccione una transacción de TRANSACTION_LOG.");
            return;
        }
        PtlfParameters ptlfParameters = ptlfParameters();
        AuditParameters auditParameters = auditParameters();
        DocBuilderService service;
        List<String> queries;
        String preview;
        try {
            queries = requestedQueries(ptlfParameters.manualCommand(), auditParameters.manualCommand());
            service = service();
            preview = previewCommands(service, ptlfParameters, auditParameters);
        } catch (SimulatorException | IllegalArgumentException e) {
            Ui.error(e);
            return;
        }
        if (!Ui.confirm("Ejecutar consultas " + String.join(" / ", queries),
                "Se ejecutará en " + ctx.env().getGuardian().getHost() + " vía SSH/OSS:\n\n" + preview)) return;
        clearRemoteResults();
        command.setText(preview);
        long selection = transactionVersion;
        long version = remoteVersion;
        executeNext(service, ptlfParameters, auditParameters, queries, 0, selection, version, ctx.user());
    }

    private void executeNext(DocBuilderService service, PtlfParameters ptlfParameters, AuditParameters auditParameters,
                             List<String> queries, int index, long selection, long version, String user) {
        String kind = queries.get(index);
        remoteInfo.setText("Ejecutando " + kind + "...");
        Ui.async(remoteForm, () -> "PTLF".equals(kind) ? service.queryPtlf(ptlfParameters, user)
                : service.queryAudit(auditParameters, user), result -> {
            if (selection != transactionVersion || version != remoteVersion) return;
            if ("PTLF".equals(kind)) ptlf.result = result;
            else audit.result = result;
            showRemoteResults();
            if (index + 1 < queries.size()) {
                executeNext(service, ptlfParameters, auditParameters, queries, index + 1, selection, version, user);
            }
        });
    }

    private void showRemoteResults() {
        LinkedHashSet<String> columns = new LinkedHashSet<>(List.of("Consulta"));
        List<Map<String, String>> rows = new ArrayList<>();
        List<String> outputs = new ArrayList<>();
        List<String> statuses = new ArrayList<>();
        for (QueryPane pane : List.of(ptlf, audit)) {
            QueryResult result = pane.result;
            if (result == null) continue;
            String kind = pane == ptlf ? "PTLF" : "AUDIT";
            columns.addAll(result.columns());
            for (Map<String, String> row : result.rows()) {
                Map<String, String> tagged = new LinkedHashMap<>(row);
                tagged.put("Consulta", kind);
                rows.add(tagged);
            }
            outputs.add(kind + ":\n" + result.rawOutput() + (result.stderr() == null || result.stderr().isBlank()
                    ? "" : "\n--- stderr ---\n" + result.stderr()));
            statuses.add(kind + ": exit=" + result.exitCode() + " | " + result.rows().size()
                    + " registros | " + result.durationMs() + " ms");
        }
        Ui.fillMaps(remoteRecords, new ArrayList<>(columns), rows);
        output.setText(String.join("\n\n", outputs));
        remoteInfo.setText(String.join("\n", statuses));
    }

    private void exportPdf() {
        TransactionLogRecord row = transactions.getSelectionModel().getSelectedItem();
        if (row == null) {
            Ui.error("Seleccione una transacción para construir el PDF.");
            return;
        }
        QueryResult ptlfResult = ptlf.result;
        QueryResult auditResult = audit.result;
        if ((ptlfResult == null || auditResult == null) && !Ui.confirm("Documento con consultas pendientes",
                "El PDF incluirá request y response. Las consultas PTLF/AUDIT no ejecutadas se indicarán como pendientes. ¿Continuar?")) return;
        Ui.chooseSave(Ui.window(root), "Documento PDF de la transacción", "transaccion-" + row.id() + ".pdf")
                .ifPresent(file -> Ui.async(null, () -> {
                    new TransactionPdfExporter().write(file, row, ptlfResult, auditResult);
                    return file;
                }, saved -> Ui.info("PDF generado: " + saved)));
    }

    private void showHelp() {
        TextArea help = Ui.code(false);
        help.setWrapText(true);
        help.setPrefRowCount(25);
        help.setText("""
                DOC BUILDER - AYUDA FUP / AUDIT

                Conexión: SSH al entorno OSS de Guardian. El comando Guardian se
                envía a gtacl mediante el launcher configurado en docBuilder.
                Ingrese el comando manual PTLF, AUDIT o ambos en el formulario único.
                Solo se ejecutan los comandos completos, primero PTLF y luego AUDIT.
                Si ambos están vacíos, se informa que faltan datos.
                Siempre revise la vista previa antes de ejecutar.

                FUP - ejemplos orientativos de inspección:
                  INFO $VOLUMEN.SUBVOL.ARCHIVO, DETAIL
                  LIST $VOLUMEN.SUBVOL.*
                  DUMP $VOLUMEN.SUBVOL.ARCHIVO

                INFO consulta atributos; LIST lista archivos; DUMP muestra contenido.
                DUMP no decodifica automáticamente un registro PTLF como transacción.
                La selección por PAN, terminal y horario depende del layout PTLF y
                del extractor instalado en su sitio. Escriba el comando completo
                de esa herramienta; no hay una sintaxis PTLF universal.
                No use comandos de modificación (PURGE, ALTER, etc.) en esta vista.

                Launcher OSS:
                Configure primero docBuilder.gtaclCommandTemplate con ${command}
                y opcionalmente ${gtaclExecutable}, usando las opciones gtacl
                verificadas en su sitio. Ambos se sustituyen como literales
                protegidos para shell. No se asume una opción -c universal.

                AUDIT:
                Escriba el comando completo con el aplicativo, archivos, Process
                Name y opciones de consulta AUDIT de su instalación.
                No se presume que AUDIT sea un comando FUP ni un ejecutable estándar.
                Esta vista no usa las plantillas automáticas PTLF/AUDIT.

                Resultados:
                La tabla unificada identifica el origen PTLF o AUDIT y depende
                del parser de salida configurado; se
                conserva también salida cruda, stderr, código de retorno y comando.
                Cambiar la transacción o comandos invalida resultados anteriores.
                El PDF combina la transacción seleccionada, request, response y
                consultas PTLF/AUDIT obtenidas; identifica consultas pendientes.

                PAN:
                Se muestra y persiste sin enmascarar según la configuración solicitada.
                Los PAN ya enmascarados en el historial no se pueden recuperar.
                Proteja los archivos PDF y el acceso a H2 como datos sensibles.
                """);
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Help FUP / AUDIT");
        dialog.getDialogPane().setContent(help);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.setResizable(true);
        Ui.styleDialog(dialog.getDialogPane());
        dialog.showAndWait();
    }

    private static String displayJson(String value) {
        if (value == null || value.isBlank()) return "(sin mensaje)";
        try {
            return Ui.pretty(Json.mapper().readTree(value));
        } catch (java.io.IOException e) {
            return "JSON inválido: " + e.getMessage() + "\n" + value;
        }
    }

    private static final class QueryPane {
        private final TextField manual = new TextField();
        private QueryResult result;
    }
}
