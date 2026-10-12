package org.simulator.regression.replay;

import java.time.Instant;
import java.util.List;

public record ReplayReport(String recording, String environment, Instant started, Instant finished, double speed,
                           List<Item> items) {

    public record Item(int index, String source, String mti, String stan, String originalRc, String replayRc,
                       String status, boolean match, long originalLatencyMicros, long replayLatencyMicros, String error) {
    }

    public long matches() {
        return items.stream().filter(Item::match).count();
    }

    public long mismatches() {
        return items.size() - matches();
    }

    public String toHtml() {
        StringBuilder sb = new StringBuilder("<!DOCTYPE html><html><head><meta charset='utf-8'><title>Replay ")
                .append(esc(recording)).append("</title><style>body{font-family:sans-serif}table{border-collapse:collapse}"
                        + "td,th{border:1px solid #ccc;padding:3px 8px}.ko{background:#fdd}</style></head><body>")
                .append("<h2>Replay: ").append(esc(recording)).append("</h2><p>Entorno ").append(esc(environment))
                .append(" | velocidad x").append(speed).append(" | coincidencias ").append(matches()).append('/')
                .append(items.size()).append("</p><table><tr><th>#</th><th>Origen</th><th>MTI</th><th>STAN</th>"
                        + "<th>RC original</th><th>RC replay</th><th>Estado</th><th>Lat. orig ms</th><th>Lat. replay ms</th><th>Error</th></tr>");
        for (Item i : items) {
            sb.append(i.match() ? "<tr>" : "<tr class='ko'>").append(td(i.index())).append(td(i.source())).append(td(i.mti()))
                    .append(td(i.stan())).append(td(i.originalRc())).append(td(i.replayRc())).append(td(i.status()))
                    .append(td(String.format("%.1f", i.originalLatencyMicros() / 1000.0)))
                    .append(td(String.format("%.1f", i.replayLatencyMicros() / 1000.0))).append(td(i.error())).append("</tr>");
        }
        return sb.append("</table></body></html>").toString();
    }

    private static String td(Object o) {
        return "<td>" + esc(o == null ? "" : String.valueOf(o)) + "</td>";
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}