package org.simulator.base24.guardian;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.simulator.base24.CommandRenderer;
import org.simulator.base24.TemplateCatalog;
import org.simulator.common.SimulatorException;
import org.simulator.common.json.Json;
import org.simulator.persistence.repo.RunRepository;
import org.simulator.persistence.repo.TestCaseRecord;
import org.simulator.persistence.repo.TestCaseRepository;
import org.simulator.ssh.ExecResult;
import org.simulator.ssh.RemoteExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Runs read-only queries on the Base24 Guardian host (transaction repository: PTLF/TLF extracts,
 * card/account files, etc.), converts the output to JSON and optionally imports rows into H2 as
 * regression test cases.
 */
public class GuardianQueryService {

    private static final Logger log = LoggerFactory.getLogger(GuardianQueryService.class);

    private final RemoteExecutor executor;
    private final TemplateCatalog<QueryTemplate> catalog;
    private final RunRepository runs;
    private final Map<String, String> context;

    /**
     * @param context values available to every template, e.g. {@code subvol} = default subvolume
     */
    public GuardianQueryService(RemoteExecutor executor, TemplateCatalog<QueryTemplate> catalog, RunRepository runs,
                                Map<String, String> context) {
        this.executor = executor;
        this.catalog = catalog;
        this.runs = runs;
        this.context = context != null ? context : Map.of();
    }

    public static TemplateCatalog<QueryTemplate> defaultCatalog(Path userFile) {
        return TemplateCatalog.load(QueryTemplate.class, "/base24/guardian-queries.json", userFile, QueryTemplate::getId);
    }

    public TemplateCatalog<QueryTemplate> catalog() {
        return catalog;
    }

    public String preview(String templateId, Map<String, String> params) {
        QueryTemplate t = catalog.get(templateId);
        return CommandRenderer.render(t.getCommandTemplate(), t.getParameters(), params, context);
    }

    public QueryResult execute(String templateId, Map<String, String> params) {
        QueryTemplate t = catalog.get(templateId);
        String command = CommandRenderer.render(t.getCommandTemplate(), t.getParameters(), params, context);
        log.info("Guardian query '{}'", templateId);
        ExecResult r = executor.exec(command);
        if (!r.ok() && r.stdout().isBlank()) {
            throw new SimulatorException("Query '" + templateId + "' failed (exit " + r.exitCode() + "): " + r.stderr());
        }
        List<Map<String, String>> rows = OutputParser.parse(r.stdout(), t.getParser());
        LinkedHashSet<String> cols = new LinkedHashSet<>();
        rows.forEach(row -> cols.addAll(row.keySet()));
        QueryResult result = new QueryResult(templateId, command, r.exitCode(), new ArrayList<>(cols), rows,
                r.stdout(), r.stderr(), r.durationMs());
        if (runs != null) {
            runs.audit(null, "GUARDIAN_QUERY", templateId, command, true);
        }
        return result;
    }

    public long saveSnapshot(QueryResult result, String environment, Map<String, String> params) {
        if (runs == null) {
            throw new SimulatorException("No repository configured");
        }
        return runs.saveSnapshot(result.templateId(), environment, Json.write(params), result.rows().size(), result.toJson());
    }

    /**
     * Converts every row to an ISO request JSON using the template mapping and stores it in the suite.
     *
     * @return number of imported test cases
     */
    public int importAsTestCases(QueryResult result, TestCaseRepository repo, String suiteName) {
        QueryTemplate t = catalog.get(result.templateId());
        QueryTemplate.TestCaseMapping m = t.getMapping();
        if (m == null) {
            throw new SimulatorException("Template '" + t.getId() + "' has no test case mapping");
        }
        String suiteId = repo.suiteIdOrCreate(suiteName);
        int n = 0;
        for (Map<String, String> row : result.rows()) {
            ObjectNode root = Json.mapper().createObjectNode();
            root.put("dialect", m.getDialect());
            root.put("mti", value(m.getMti(), row));
            ObjectNode fields = root.putObject("fields");
            m.getFields().forEach((field, col) -> {
                String v = value(col, row);
                if (v != null && !v.isEmpty()) {
                    fields.put(field.replaceAll("\\D", ""), v);
                }
            });
            String rc = m.getResponseCode() != null ? value(m.getResponseCode(), row) : null;
            String name = m.getNameColumn() != null ? value(m.getNameColumn(), row) : null;
            if (name == null || name.isBlank()) {
                name = t.getId() + " #" + (n + 1);
            }
            TestCaseRecord tc = TestCaseRecord.of(suiteId, name, m.getDialect(), root.toString(),
                    rc == null || rc.isBlank() ? "00" : rc).withSource("GUARDIAN");
            repo.save(tc);
            n++;
        }
        log.info("Imported {} test cases into suite '{}'", n, suiteName);
        return n;
    }

    private static String value(String spec, Map<String, String> row) {
        if (spec == null) {
            return null;
        }
        if (spec.startsWith("=")) {
            return spec.substring(1);
        }
        return row.get(spec);
    }
}
