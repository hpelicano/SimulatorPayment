package org.simulator.base24.doc;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.simulator.base24.doc.DocBuilderService.AuditParameters;
import org.simulator.base24.doc.DocBuilderService.PtlfParameters;
import org.simulator.base24.guardian.QueryResult;
import org.simulator.common.json.Json;
import org.simulator.config.model.DocBuilderConfig;
import org.simulator.persistence.Database;
import org.simulator.persistence.Jdbc;
import org.simulator.persistence.repo.RunRepository;
import org.simulator.persistence.repo.TransactionLogRecord;
import org.simulator.ssh.ExecResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocBuilderTest {
    private static DocBuilderConfig configWithTestLauncher() {
        DocBuilderConfig config = new DocBuilderConfig();
        config.setGtaclCommandTemplate("printf '%s\\n' ${command} | ${gtaclExecutable} -c");
        return config;
    }

    private static PtlfParameters ptlf(String manual) {
        return new PtlfParameters(manual, "$DATA1.B24.PT251003", "000000", "235959", "2",
                "4761739001010010", "TERM0001");
    }

    @Test
    void emptyTemplatesRequireManualCommandsAndConfigurationRoundTrips() {
        DocBuilderConfig config = configWithTestLauncher();
        DocBuilderService service = new DocBuilderService(command -> null, config, null);
        assertThatThrownBy(() -> service.previewPtlf(ptlf(""))).hasMessageContaining("ptlfCommandTemplate");
        assertThatThrownBy(() -> service.previewAudit(new AuditParameters("", "", "", "", "", "")))
                .hasMessageContaining("auditCommandTemplate");
        var copy = Json.read(Json.write(config), DocBuilderConfig.class);
        assertThat(copy.getPtlfParser().getType()).isEqualTo("RAW");
        assertThat(copy.getGtaclCommandTemplate()).isEqualTo("printf '%s\\n' ${command} | ${gtaclExecutable} -c");
        assertThat(Json.write(config)).contains("gtaclCommandTemplate").doesNotContain("gtaclLauncherTemplate");
        assertThatThrownBy(() -> new DocBuilderService(command -> null, new DocBuilderConfig(), null)
                .previewPtlf(ptlf("FUP INFO X"))).hasMessageContaining("verified OSS gtacl launcher");
        assertThat(service.previewPtlf(ptlf("FUP INFO $DATA1.B24.PT251003, DETAIL")))
                .isEqualTo("printf '%s\\n' 'FUP INFO $DATA1.B24.PT251003, DETAIL' | 'gtacl' -c");
    }

    @Test
    void previewAndExecutionUseTheSameQuotedCommandAndAuditFailuresHonestly() {
        AtomicReference<String> executed = new AtomicReference<>();
        try (Database db = Database.inMemory("doc" + System.nanoTime())) {
            RunRepository runs = new RunRepository(new Jdbc(db));
            DocBuilderService service = new DocBuilderService(command -> {
                executed.set(command);
                return new ExecResult(command, 7, "Primera línea\nsegunda|línea\núltima", "falla remota", 18);
            }, configWithTestLauncher(), runs);
            PtlfParameters parameters = ptlf("FUP INFO $DATA1.B24.X, DETAIL 'O'Hara' $(not-a-shell-command)");
            QueryResult result = service.queryPtlf(parameters, "tester");
            assertThat(result.command()).isEqualTo(service.previewPtlf(parameters)).isEqualTo(executed.get());
            assertThat(result.command()).contains("'\"'\"'").contains("$DATA1").contains("$(not-a-shell-command)");
            assertThat(result.exitCode()).isEqualTo(7);
            assertThat(result.columns()).containsExactly("line");
            assertThat(result.rows()).hasSize(3);
            assertThat(result.rows().get(1)).containsEntry("line", "segunda|línea");
            assertThat(result.rawOutput()).endsWith("última");
            assertThat(result.stderr()).isEqualTo("falla remota");
            String[] audit = runs.auditTrail(1).get(0);
            assertThat(audit[2]).isEqualTo("DOC_BUILDER_PTLF");
            assertThat(audit[4]).isEqualTo("false");
        }
    }

    @Test
    void configuredQueriesValidateParametersAndPreserveCompleteRawOutput() {
        DocBuilderConfig config = configWithTestLauncher();
        config.setPtlfCommandTemplate("SITE_READER ${file} ${fromTime} ${toTime} ${maxRows} ${pan} ${terminal}");
        config.setAuditCommandTemplate("SITE_AUDIT ${files} ${processName} ${options} ${pan} ${terminal}");
        config.setGtaclExecutable("/site tools/gtacl");
        config.setGtaclCommandTemplate("printf '%s\\n' ${command} | ${gtaclExecutable} -p site_reader");
        config.getPtlfParser().setType("DELIMITED");
        config.getPtlfParser().setDelimiter("\\|");
        DocBuilderService service = new DocBuilderService(command -> new ExecResult(command, 0,
                "PAN|RC\n4761739001010010|00\n4761739001010010|51\n4761739001010010|05\n", "", 4), config, null);
        assertThat(service.previewPtlf(ptlf(""))).contains("'SITE_READER $DATA1.B24.PT251003 000000 235959 2")
                .endsWith("'/site tools/gtacl' -p site_reader");
        QueryResult result = service.queryPtlf(ptlf(""), "tester");
        assertThat(result.rows()).hasSize(2);
        assertThat(result.rawOutput()).contains("|05");
        assertThat(result.columns()).containsExactly("PAN", "RC");
        assertThat(service.previewAudit(new AuditParameters("", "$DATA.AUD.A1, $DATA.AUD.A2", "$PROC",
                "-verbose", "4761739001010010", "TERM0001")))
                .contains("'SITE_AUDIT $DATA.AUD.A1, $DATA.AUD.A2 $PROC -verbose 4761739001010010 TERM0001'");
        assertThatThrownBy(() -> service.previewPtlf(new PtlfParameters("", "$DATA.X.PT", "246000",
                "235959", "2", "", ""))).hasMessageContaining("HHMMSS");
        assertThatThrownBy(() -> service.previewPtlf(new PtlfParameters("", "$DATA.X.PT", "120000",
                "115959", "2", "", ""))).hasMessageContaining("after");
        assertThatThrownBy(() -> service.previewPtlf(new PtlfParameters("", "$DATA.X.PT", "000000",
                "235959", "0", "", ""))).hasMessageContaining("maxRows");
        assertThatThrownBy(() -> service.previewPtlf(new PtlfParameters("", "$DATA.X.PT", "000000",
                "235959", "2", "4761******0010", ""))).hasMessageContaining("PAN");
        assertThatThrownBy(() -> service.previewAudit(new AuditParameters("", "", "", "; FUP PURGE X", "", "")))
                .hasMessageContaining("separator");
        assertThatThrownBy(() -> service.previewPtlf(ptlf("FUP INFO X\nFUP PURGE Y"))).hasMessageContaining("one line");
        assertThatThrownBy(() -> service.previewPtlf(ptlf("FUP INFO X\u2028FUP PURGE Y"))).hasMessageContaining("one line");
        assertThatThrownBy(() -> service.previewPtlf(new PtlfParameters("FUP INFO X", "x\r", "", "", "", "", "")))
                .hasMessageContaining("one line");
        config.setPtlfCommandTemplate("SITE_READER ${unknown}");
        assertThatThrownBy(() -> service.previewPtlf(ptlf(""))).hasMessageContaining("placeholder");
        config.setGtaclCommandTemplate("gtacl -c");
        assertThatThrownBy(() -> service.previewPtlf(ptlf("FUP INFO X"))).hasMessageContaining("${command}");
    }

    @Test
    void parserAndConnectionFailuresAreAuditedWithoutFallback() {
        try (Database db = Database.inMemory("docFailure" + System.nanoTime())) {
            RunRepository runs = new RunRepository(new Jdbc(db));
            DocBuilderConfig config = configWithTestLauncher();
            config.getAuditParser().setType("JSON");
            DocBuilderService parsing = new DocBuilderService(command -> new ExecResult(command, 0,
                    "not-json", "", 1), config, runs);
            assertThatThrownBy(() -> parsing.queryAudit(new AuditParameters("SITE_AUDIT", "", "", "", "", ""), "u"))
                    .hasMessageContaining("JSON");
            DocBuilderService connection = new DocBuilderService(command -> {
                throw new IllegalStateException("connection unavailable");
            }, config, runs);
            assertThatThrownBy(() -> connection.queryPtlf(ptlf("FUP INFO X"), "u"))
                    .hasMessageContaining("connection unavailable");
            assertThat(runs.auditTrail(5)).hasSize(2).allSatisfy(row -> assertThat(row[4]).isEqualTo("false"));
        }
    }

    @Test
    void emptyFailedExecutionReturnsRemoteStatusInsteadOfInventingRows() {
        DocBuilderConfig config = configWithTestLauncher();
        config.getAuditParser().setType("JSON");
        DocBuilderService service = new DocBuilderService(command -> new ExecResult(command, 9, "", "denied", 2),
                config, null);
        QueryResult result = service.queryAudit(new AuditParameters("SITE_AUDIT", "", "", "", "", ""), "u");
        assertThat(result.rows()).isEmpty();
        assertThat(result.exitCode()).isEqualTo(9);
        assertThat(result.stderr()).isEqualTo("denied");
    }

    @Test
    void simulatedPdfIncludesBothMessagesPtlfAndEveryAuditProcess(@org.junit.jupiter.api.io.TempDir Path folder)
            throws Exception {
        Path file = folder.resolve("simulated.pdf");
        new TransactionPdfExporter().write(file, DocBuilderExample.transaction(),
                DocBuilderExample.ptlf(), DocBuilderExample.audit());
        try (var document = Loader.loadPDF(file.toFile())) {
            String content = new PDFTextStripper().getText(document);
            assertThat(content).contains("Request JSON", "Response JSON",
                    "PTLF - Transacciones registradas", "AUDIT - Flujo", "Transacción PTLF 1",
                    "Paso del flujo 1", "Paso del flujo 2", "Paso del flujo 3", "Paso del flujo 4",
                    "$POSIN", "$B24AUTH", "$HOSTOUT", "RECEPCION REQUEST", "RETORNO AL TERMINAL",
                    "654321", "4761739001010010");
            assertThat(document.getDocumentInformation().getTitle()).contains("Base24");
        }
    }

    @Test
    void pdfPreservesMetadataJsonErrorsPanAccentsLongLinesAndMissingQueries() throws Exception {
        Path folder = Path.of("target", "doc-builder-tests");
        Files.createDirectories(folder);
        Path file = folder.resolve("report-" + System.nanoTime() + ".pdf");
        String longLine = "x".repeat(20_000) + "FINAL_DE_SALIDA";
        TransactionLogRecord transaction = new TransactionLogRecord(42L, "ACQUIRER", "DEV", "base24-pos",
                "0200", "000000", "000123", "000000000123", "TERM0001", "4761739001010010", "1000",
                "00", "APPROVED", 321L, "{\"fields\":{\"2\":\"4761739001010010\",\"nota\":\"Autorización española\"}}",
                "{\"response\":\"00\"} invalid { respuesta", "AABB", "CCDD", "Error español ñ",
                Instant.parse("2026-10-03T12:34:56Z"));
        QueryResult ptlf = new QueryResult("PTLF", "printf '%s\\n' 'SITE $DATA.X.PT' | 'gtacl' -c", 7,
                List.of("line"), List.of(Map.of("line", "Evidencia con áéíóú ñ")),
                longLine + "\nCARACTER_漢_FIN", "stderr último", 14);
        try {
            new TransactionPdfExporter().write(file, transaction, ptlf, null);
            try (var document = Loader.loadPDF(file.toFile())) {
                assertThat(document.getNumberOfPages()).isGreaterThan(3);
                String content = new PDFTextStripper().getText(document);
                assertThat(content).contains("4761739001010010", "TERM0001", "000123", "AABB", "CCDD",
                        "Autorización española", "JSON inválido", "invalid { respuesta", "Error español ñ",
                        "NO EJECUTADA", "ERROR remoto", "stderr último", "FINAL_DE_SALIDA", "\\u6F22");
                int preservedCharacters = java.util.regex.Pattern.compile("x{20,}").matcher(content)
                        .results().mapToInt(match -> match.group().length()).sum();
                assertThat(preservedCharacters).isEqualTo(20_000);
            }
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(folder);
        }
    }
}
