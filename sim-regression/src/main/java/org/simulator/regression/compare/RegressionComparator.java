package org.simulator.regression.compare;

import com.fasterxml.jackson.databind.JsonNode;
import org.simulator.common.SimulatorException;
import org.simulator.common.json.Json;
import org.simulator.persistence.repo.RunRepository;
import org.simulator.persistence.repo.RunRepository.RegressionResultRecord;
import org.simulator.persistence.repo.RunRepository.RegressionRunRecord;
import org.simulator.regression.compare.ComparisonReport.Change;
import org.simulator.regression.compare.ComparisonReport.Entry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Compares two stored regression runs case by case (matched by test case id, else by name). */
public class RegressionComparator {

    /** Fields that legitimately differ between runs (dates, STAN, RRN, auth code). */
    public static final Set<Integer> DEFAULT_IGNORED = Set.of(7, 11, 12, 13, 15, 17, 37, 38);

    private final RunRepository runs;
    private final Set<Integer> ignored;

    public RegressionComparator(RunRepository runs) {
        this(runs, DEFAULT_IGNORED);
    }

    public RegressionComparator(RunRepository runs, Set<Integer> ignored) {
        this.runs = runs;
        this.ignored = ignored;
    }

    public ComparisonReport compare(long baseRunId, long otherRunId) {
        RegressionRunRecord base = runs.regressionRun(baseRunId).orElseThrow(() -> new SimulatorException("Corrida inexistente #" + baseRunId));
        RegressionRunRecord other = runs.regressionRun(otherRunId).orElseThrow(() -> new SimulatorException("Corrida inexistente #" + otherRunId));
        Map<String, RegressionResultRecord> a = index(runs.regressionResults(baseRunId));
        Map<String, RegressionResultRecord> b = index(runs.regressionResults(otherRunId));
        List<Entry> entries = new ArrayList<>();
        for (var e : a.entrySet()) {
            RegressionResultRecord x = e.getValue();
            RegressionResultRecord y = b.get(e.getKey());
            if (y == null) {
                entries.add(new Entry(e.getKey(), x.testCaseName(), x.actualRc(), null, x.passed(), null, ms(x), null,
                        Change.ONLY_IN_BASE, List.of()));
                continue;
            }
            List<String> diffs = fieldDiffs(x.responseJson(), y.responseJson());
            Change c;
            if (x.passed() && !y.passed()) c = Change.REGRESSION;
            else if (!x.passed() && y.passed()) c = Change.FIXED;
            else if (!Objects.equals(x.actualRc(), y.actualRc())) c = Change.RC_CHANGED;
            else if (!diffs.isEmpty()) c = Change.FIELDS_CHANGED;
            else c = Change.SAME;
            entries.add(new Entry(e.getKey(), x.testCaseName(), x.actualRc(), y.actualRc(), x.passed(), y.passed(), ms(x), ms(y), c, diffs));
        }
        for (var e : b.entrySet()) {
            if (!a.containsKey(e.getKey())) {
                RegressionResultRecord y = e.getValue();
                entries.add(new Entry(e.getKey(), y.testCaseName(), null, y.actualRc(), null, y.passed(), null, ms(y),
                        Change.ONLY_IN_OTHER, List.of()));
            }
        }
        return new ComparisonReport(base, other, entries);
    }

    List<String> fieldDiffs(String jsonA, String jsonB) {
        Map<String, String> fa = fields(jsonA), fb = fields(jsonB);
        if (fa == null || fb == null) return List.of();
        Set<String> keys = new TreeSet<>((p, q) -> Integer.compare(num(p), num(q)));
        keys.addAll(fa.keySet());
        keys.addAll(fb.keySet());
        List<String> out = new ArrayList<>();
        for (String k : keys) {
            if (ignored.contains(num(k))) continue;
            String va = fa.get(k), vb = fb.get(k);
            if (!Objects.equals(va, vb)) {
                out.add("DE" + k + ": " + (va == null ? "∅" : va) + " → " + (vb == null ? "∅" : vb));
            }
        }
        return out;
    }

    private static Map<String, String> fields(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode n = Json.mapper().readTree(json).path("fields");
            Map<String, String> m = new LinkedHashMap<>();
            n.fields().forEachRemaining(f -> m.put(f.getKey(), f.getValue().isValueNode() ? f.getValue().asText() : f.getValue().toString()));
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    private static int num(String k) {
        try {
            return Integer.parseInt(k.replaceAll("\\D", ""));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static Map<String, RegressionResultRecord> index(List<RegressionResultRecord> list) {
        Map<String, RegressionResultRecord> m = new LinkedHashMap<>();
        for (RegressionResultRecord r : list) {
            String key = r.testCaseId() != null ? "id:" + r.testCaseId() : "name:" + r.testCaseName();
            m.putIfAbsent(key, r);
        }
        return m;
    }

    private static Double ms(RegressionResultRecord r) {
        return r.latencyMicros() == null ? null : r.latencyMicros() / 1000.0;
    }
}