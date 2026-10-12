package org.simulator.base24.doc;

import org.simulator.base24.guardian.QueryResult;
import org.simulator.common.json.Json;
import org.simulator.persistence.repo.TransactionLogRecord;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Test fixture for PDF message and audit-flow coverage. */
public final class DocBuilderExample {
    private DocBuilderExample() {}

    public static TransactionLogRecord transaction() {
        return new TransactionLogRecord(-1L, "SIMULATED_DOC", "DEMO", "base24-pos",
                "0200", "000000", "123456", "000000123456", "TERM0001", "4761739001010010",
                "000000001500", "00", "SIMULADO", 42000L,
                request(), response(), null, null, null, Instant.parse("2026-10-10T15:30:00Z"));
    }

    private static String request() {
        return Json.write(Map.of("dialect", "base24-pos", "mti", "0200", "fields",
                Map.of("2", "4761739001010010", "3", "000000", "4", "000000001500",
                        "11", "123456", "37", "000000123456", "41", "TERM0001", "49", "032")));
    }

    private static String response() {
        return Json.write(Map.of("dialect", "base24-pos", "mti", "0210", "fields",
                Map.of("11", "123456", "37", "000000123456", "38", "654321", "39", "00", "41", "TERM0001")));
    }

    public static QueryResult ptlf() {
        List<Map<String, String>> rows = List.of(row("fecha", "2026-10-10T15:30:00Z",
                "archivo", "$DEMO.B24.PTLF", "PAN", "4761739001010010", "terminal", "TERM0001",
                "request", request(), "response", response(), "estado", "APROBADA - SIMULADO"));
        return result("PTLF", rows);
    }

    public static QueryResult audit() {
        List<Map<String, String>> rows = List.of(
                step("1", "15:30:00.000", "$POSIN", "$B24AUTH", "RECEPCION REQUEST", request()),
                step("2", "15:30:00.010", "$B24AUTH", "$HOSTOUT", "ENVIO A AUTORIZADOR", request()),
                step("3", "15:30:00.030", "$HOSTOUT", "$B24AUTH", "RESPUESTA AUTORIZADOR", response()),
                step("4", "15:30:00.042", "$B24AUTH", "$POSIN", "RETORNO AL TERMINAL", response()));
        return result("AUDIT", rows);
    }

    private static Map<String, String> step(String order, String time, String origin, String target,
                                           String event, String message) {
        return row("paso", order, "hora", time, "proceso_origen", origin, "proceso_destino", target,
                "evento", event, "mensaje", message, "evidencia", "SIMULADA");
    }

    private static Map<String, String> row(String... pairs) {
        Map<String, String> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) row.put(pairs[i], pairs[i + 1]);
        return row;
    }

    private static QueryResult result(String kind, List<Map<String, String>> rows) {
        return new QueryResult("SIMULATED_" + kind, "SIMULADO - No se ejecuto SSH / gtacl", 0,
                List.copyOf(rows.get(0).keySet()), rows, Json.write(rows), "", 0);
    }
}
