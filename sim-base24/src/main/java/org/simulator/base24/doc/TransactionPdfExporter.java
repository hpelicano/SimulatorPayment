package org.simulator.base24.doc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.simulator.base24.guardian.QueryResult;
import org.simulator.common.json.Json;
import org.simulator.persistence.repo.TransactionLogRecord;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.awt.Color;

/** Exports the selected transaction and the actual remote evidence, including unsuccessful queries. */
public final class TransactionPdfExporter {

    /** PAN is deliberately included in clear text. Protect the generated document as sensitive data. */
    public void write(Path destination, TransactionLogRecord transaction, QueryResult ptlf, QueryResult audit)
            throws IOException {
        Objects.requireNonNull(destination, "PDF destination");
        Objects.requireNonNull(transaction, "Selected transaction");
        try (PDDocument document = new PDDocument(); Report report = new Report(document)) {
            document.getDocumentInformation().setTitle("Base24 - Documento de transacción " + transaction.id());
            document.getDocumentInformation().setAuthor("SimulatorPayment");
            report.section("Base24 Doc Builder - Documento transaccional");
            report.text("Los resultados son evidencia de ejecución, no una certificación de éxito.");
            report.text("PAN incluido sin enmascarar. Documento sensible.");
            report.text("Caracteres fuera de WinAnsi se representan como \\uXXXX o \\UXXXXXXXX, sin descartarlos.");
            report.section("Metadatos de TRANSACTION_LOG");
            metadata(report, transaction);
            report.section("Request JSON");
            json(report, transaction.requestJson());
            report.section("Response JSON");
            json(report, transaction.responseJson());
            report.section("Request HEX");
            report.text(value(transaction.requestHex()));
            report.section("Response HEX");
            report.text(value(transaction.responseHex()));
            query(report, "PTLF", ptlf);
            query(report, "AUDIT", audit);
            report.finish();
            document.save(destination.toFile());
        }
    }

    private static void metadata(Report report, TransactionLogRecord transaction) throws IOException {
        report.text("ID: " + value(transaction.id()));
        report.text("Source: " + value(transaction.source()));
        report.text("Environment: " + value(transaction.environment()));
        report.text("Dialect: " + value(transaction.dialect()));
        report.text("MTI: " + value(transaction.mti()));
        report.text("Processing code: " + value(transaction.processingCode()));
        report.text("STAN: " + value(transaction.stan()));
        report.text("RRN: " + value(transaction.rrn()));
        report.text("Terminal: " + value(transaction.terminalId()));
        report.text("PAN (valor almacenado): " + value(transaction.panMasked()));
        report.text("Amount: " + value(transaction.amount()));
        report.text("Response code: " + value(transaction.responseCode()));
        report.text("Outcome: " + value(transaction.outcome()));
        report.text("Latency (microseconds): " + value(transaction.latencyMicros()));
        report.text("Created at: " + value(transaction.createdAt()));
        report.text("Error: " + value(transaction.errorMessage()));
    }

    private static void json(Report report, String text) throws IOException {
        if (text == null || text.isBlank()) {
            report.text("(sin mensaje)");
            return;
        }
        try {
            var tree = Json.mapper().reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text);
            if (tree == null) {
                report.text("(JSON sin contenido)");
            } else {
                report.text(Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(tree));
            }
        } catch (JsonProcessingException e) {
            report.text("JSON inválido; se conserva el contenido original:");
            report.text(text);
        }
    }

    private static void query(Report report, String name, QueryResult result) throws IOException {
        report.section(name.equals("PTLF") ? "PTLF - Transacciones registradas" : "AUDIT - Flujo de mensajería por procesos");
        if (result == null) {
            report.text("NO EJECUTADA / pendiente. No hay evidencia remota disponible.");
            return;
        }
        report.text("Template / query ID: " + value(result.templateId()));
        report.text("Comando OSS ejecutado:");
        report.text(value(result.command()));
        report.text("Exit code: " + result.exitCode() + (result.exitCode() == 0
                ? " (el proceso retornó 0)" : " (ERROR remoto)"));
        report.text("Duration (ms): " + result.durationMs());
        report.text("Columns: " + String.join(", ", result.columns()));
        report.text("Parsed rows: " + result.rows().size());
        int index = 0;
        for (Map<String, String> row : result.rows()) {
            report.card((name.equals("AUDIT") ? "Paso del flujo " : "Transacción PTLF ") + ++index);
            for (var entry : row.entrySet()) {
                report.text(entry.getKey() + ":");
                String content = value(entry.getValue());
                if (content.stripLeading().startsWith("{") || content.stripLeading().startsWith("[")) {
                    json(report, content);
                } else {
                    report.text(content);
                }
            }
        }
        report.section(name + " - stdout completo");
        report.text(value(result.rawOutput()));
        report.section(name + " - stderr");
        report.text(value(result.stderr()));
    }

    private static String value(Object value) { return value == null ? "(no disponible)" : value.toString(); }

    private static final class Report implements AutoCloseable {
        private static final float MARGIN = 40;
        private static final float SIZE = 9;
        private static final float LEADING = 13;
        private final PDDocument document;
        private final PDFont font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        private final PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        private PDPageContentStream stream;
        private float y;

        private Report(PDDocument document) { this.document = document; }

        private void section(String text) throws IOException {
            if (stream == null || y < MARGIN + 70) newPage();
            y -= 12;
            stream.setNonStrokingColor(new Color(30, 58, 95));
            stream.addRect(MARGIN, y - 8, PDRectangle.A4.getWidth() - 2 * MARGIN, 27);
            stream.fill();
            stream.setNonStrokingColor(Color.WHITE);
            stream.beginText();
            stream.setFont(bold, 11);
            stream.newLineAtOffset(MARGIN + 10, y);
            stream.showText(supported(text));
            stream.endText();
            y -= 31;
            stream.setNonStrokingColor(new Color(31, 41, 55));
        }

        private void card(String title) throws IOException {
            if (stream == null || y < MARGIN + 60) newPage();
            y -= 8;
            stream.setNonStrokingColor(new Color(229, 241, 249));
            stream.addRect(MARGIN, y - 5, PDRectangle.A4.getWidth() - 2 * MARGIN, 22);
            stream.fill();
            stream.setNonStrokingColor(new Color(30, 58, 95));
            line(title);
            y -= 8;
            stream.setNonStrokingColor(new Color(31, 41, 55));
        }

        private void text(String text) throws IOException {
            for (String line : text.split("\\R", -1)) wrap(supported(line));
        }

        private String supported(String text) throws IOException {
            StringBuilder out = new StringBuilder();
            for (int offset = 0; offset < text.length();) {
                int cp = text.codePointAt(offset);
                offset += Character.charCount(cp);
                if (cp == '\t') {
                    out.append("    ");
                    continue;
                }
                String character = new String(Character.toChars(cp));
                try {
                    font.encode(character);
                    out.append(character);
                } catch (IllegalArgumentException e) {
                    out.append(cp <= 0xffff ? String.format("\\u%04X", cp) : String.format("\\U%08X", cp));
                }
            }
            return out.toString();
        }

        private void wrap(String text) throws IOException {
            float available = PDRectangle.A4.getWidth() - 2 * MARGIN;
            if (text.isEmpty()) {
                line("");
                return;
            }
            int start = 0;
            while (start < text.length()) {
                float width = 0;
                int end = start;
                while (end < text.length()) {
                    float next = font.getStringWidth(text.substring(end, end + 1)) / 1000 * SIZE;
                    if (width + next > available && end > start) break;
                    width += next;
                    ++end;
                }
                line(text.substring(start, end));
                start = end;
            }
        }

        private void line(String text) throws IOException {
            if (stream == null || y < MARGIN + LEADING) newPage();
            stream.beginText();
            stream.setFont(font, SIZE);
            stream.newLineAtOffset(MARGIN, y);
            stream.showText(text);
            stream.endText();
            y -= LEADING;
        }

        private void newPage() throws IOException {
            finish();
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            float height = page.getMediaBox().getHeight();
            stream.setNonStrokingColor(new Color(15, 23, 42));
            stream.addRect(0, height - 55, page.getMediaBox().getWidth(), 55);
            stream.fill();
            stream.setNonStrokingColor(Color.WHITE);
            stream.beginText();
            stream.setFont(bold, 14);
            stream.newLineAtOffset(MARGIN, height - 33);
            stream.showText("SimulatorPayment  |  Base24");
            stream.endText();
            stream.setNonStrokingColor(new Color(100, 116, 139));
            stream.beginText();
            stream.setFont(font, 8);
            stream.newLineAtOffset(MARGIN, 23);
            stream.showText(supported("Documento sensible - Página " + document.getNumberOfPages()));
            stream.endText();
            stream.setNonStrokingColor(new Color(31, 41, 55));
            y = height - 80;
        }

        private void finish() throws IOException {
            if (stream != null) {
                stream.close();
                stream = null;
            }
        }

        @Override public void close() throws IOException { finish(); }
    }
}
