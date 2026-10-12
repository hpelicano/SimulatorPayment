package org.simulator.regression;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/** Outcome of a whole regression run, exportable to HTML and JUnit XML. */
public record RegressionReport(
        long runId,
        String suiteName,
        String environment,
        Instant startedAt,
        Instant finishedAt,
        String status,
        List<CaseResult> results) {

    public long passed() {
        return results.stream().filter(CaseResult::passed).count();
    }

    public long failed() {
        return results.size() - passed();
    }

    public String toJUnitXml() {
        double secs = Duration.between(startedAt, finishedAt).toMillis() / 1000.0;
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append(String.format(Locale.ROOT, "<testsuite name=\"%s\" tests=\"%d\" failures=\"%d\" errors=\"0\" time=\"%.3f\" timestamp=\"%s\">%n",
                esc(suiteName), results.size(), failed(), secs, startedAt));
        for (CaseResult r : results) {
            sb.append(String.format(Locale.ROOT, "  <testcase classname=\"%s\" name=\"%s\" time=\"%.3f\"",
                    esc(suiteName), esc(r.name()), r.latencyMicros() / 1_000_000.0));
            if (r.passed()) {
                sb.append("/>\n");
            } else {
                sb.append(">\n    <failure message=\"").append(esc(r.summary())).append("\">")
                        .append(esc("expected RC=" + r.expectedRc() + " actual RC=" + r.actualRc()
                                + " outcome=" + r.outcome()))
                        .append("</failure>\n  </testcase>\n");
            }
        }
        sb.append("</testsuite>\n");
        return sb.toString();
    }

    public String toHtml() {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><title>Regresion ")
                .append(esc(suiteName)).append("</title><style>")
                .append("body{font-family:Segoe UI,Arial,sans-serif;margin:24px}table{border-collapse:collapse;width:100%}")
                .append("td,th{border:1px solid #ccc;padding:4px 8px;font-size:13px}th{background:#263238;color:#fff}")
                .append(".ok{background:#e8f5e9}.ko{background:#ffebee}.kpi{display:inline-block;margin-right:24px;font-size:18px}")
                .append("pre{white-space:pre-wrap;margin:0;font-size:11px}</style></head><body>");
        sb.append("<h1>Regresion: ").append(esc(suiteName)).append("</h1>");
        sb.append("<p>Run #").append(runId).append(" &mdash; entorno ").append(esc(environment))
                .append(" &mdash; ").append(startedAt).append(" a ").append(finishedAt).append("</p>");
        sb.append("<div class=\"kpi\">Total: <b>").append(results.size()).append("</b></div>")
                .append("<div class=\"kpi\">OK: <b>").append(passed()).append("</b></div>")
                .append("<div class=\"kpi\">Fallidos: <b>").append(failed()).append("</b></div>")
                .append("<div class=\"kpi\">Estado: <b>").append(esc(status)).append("</b></div>");
        sb.append("<table><tr><th>#</th><th>Caso</th><th>Resultado</th><th>RC esperado</th><th>RC obtenido</th>")
                .append("<th>Latencia (ms)</th><th>Detalle</th></tr>");
        int i = 1;
        for (CaseResult r : results) {
            sb.append("<tr class=\"").append(r.passed() ? "ok" : "ko").append("\"><td>").append(i++)
                    .append("</td><td>").append(esc(r.name())).append("</td><td>").append(r.passed() ? "OK" : "FALLO")
                    .append("</td><td>").append(esc(r.expectedRc())).append("</td><td>").append(esc(r.actualRc()))
                    .append("</td><td>").append(String.format(Locale.ROOT, "%.2f", r.latencyMicros() / 1000.0))
                    .append("</td><td><pre>").append(esc(r.summary())).append("</pre></td></tr>");
        }
        sb.append("</table></body></html>");
        return sb.toString();
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
