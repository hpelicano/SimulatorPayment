package org.simulator.regression.compare;

import org.simulator.persistence.repo.RunRepository.RegressionRunRecord;

import java.util.List;

/** Differences between two regression runs (e.g. same suite on QA vs UAT, or before/after a release). */
public record ComparisonReport(RegressionRunRecord base, RegressionRunRecord other, List<Entry> entries) {

    public enum Change { SAME, REGRESSION, FIXED, RC_CHANGED, FIELDS_CHANGED, ONLY_IN_BASE, ONLY_IN_OTHER }

    public record Entry(String key, String name, String baseRc, String otherRc, Boolean basePassed, Boolean otherPassed,
                        Double baseLatencyMs, Double otherLatencyMs, Change change, List<String> fieldDiffs) {
    }

    public long count(Change c) {
        return entries.stream().filter(e -> e.change() == c).count();
    }

    public boolean hasRegressions() {
        return count(Change.REGRESSION) > 0 || count(Change.RC_CHANGED) > 0;
    }

    public String summary() {
        StringBuilder sb = new StringBuilder();
        for (Change c : Change.values()) {
            long n = count(c);
            if (n > 0) sb.append(sb.length() == 0 ? "" : " | ").append(c).append('=').append(n);
        }
        return sb.toString();
    }

    public String toHtml() {
        StringBuilder sb = new StringBuilder("<!DOCTYPE html><html><head><meta charset='utf-8'><title>Comparación de regresiones</title>"
                + "<style>body{font-family:sans-serif}table{border-collapse:collapse}td,th{border:1px solid #ccc;padding:3px 8px}"
                + ".REGRESSION,.RC_CHANGED{background:#fdd}.FIXED{background:#dfd}.FIELDS_CHANGED{background:#ffd}"
                + ".ONLY_IN_BASE,.ONLY_IN_OTHER{background:#eee}</style></head><body>")
                .append("<h2>Comparación de corridas #").append(base.id()).append(" (").append(esc(base.environment()))
                .append(") vs #").append(other.id()).append(" (").append(esc(other.environment())).append(")</h2>")
                .append("<p>").append(esc(summary())).append("</p><table><tr><th>Caso</th><th>Cambio</th><th>RC base</th>"
                        + "<th>RC otro</th><th>OK base</th><th>OK otro</th><th>Lat. base ms</th><th>Lat. otro ms</th><th>Diferencias</th></tr>");
        for (Entry e : entries) {
            sb.append("<tr class='").append(e.change()).append("'>").append(td(e.name())).append(td(e.change()))
                    .append(td(e.baseRc())).append(td(e.otherRc())).append(td(e.basePassed())).append(td(e.otherPassed()))
                    .append(td(fmt(e.baseLatencyMs()))).append(td(fmt(e.otherLatencyMs())))
                    .append(td(String.join("; ", e.fieldDiffs()))).append("</tr>");
        }
        return sb.append("</table></body></html>").toString();
    }

    private static String fmt(Double d) {
        return d == null ? "" : String.format("%.1f", d);
    }

    private static String td(Object o) {
        return "<td>" + esc(o == null ? "" : String.valueOf(o)) + "</td>";
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}