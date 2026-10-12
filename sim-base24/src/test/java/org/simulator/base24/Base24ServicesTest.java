package org.simulator.base24;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simulator.base24.guardian.GuardianQueryService;
import org.simulator.base24.guardian.OutputParser;
import org.simulator.base24.guardian.QueryResult;
import org.simulator.base24.guardian.QueryTemplate;
import org.simulator.base24.pathway.CommandPolicy;
import org.simulator.base24.pathway.PathwayService;
import org.simulator.base24.pathway.SocketPathwayClient;
import org.simulator.config.model.PathwayEndpoint;
import org.simulator.persistence.Database;
import org.simulator.persistence.Jdbc;
import org.simulator.persistence.repo.RunRepository;
import org.simulator.persistence.repo.TestCaseRecord;
import org.simulator.persistence.repo.TestCaseRepository;
import org.simulator.ssh.ExecResult;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Base24ServicesTest {

    private Database db;
    private RunRepository runs;
    private TestCaseRepository cases;
    private final List<String> executed = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        db = Database.inMemory("b24" + System.nanoTime());
        runs = new RunRepository(new Jdbc(db));
        cases = new TestCaseRepository(new Jdbc(db));
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void rendererValidatesParameters() {
        List<TemplateParameter> params = List.of(new TemplateParameter("pan", "\\d{12,19}", true),
                new TemplateParameter("name", ".*", true));
        params.get(1).setSqlString(true);
        assertThat(CommandRenderer.render("X ${pan} '${name}' ${subvol}", params,
                Map.of("pan", "4761739001010010", "name", "O'Hara"), Map.of("subvol", "$DATA1.B24")))
                .isEqualTo("X 4761739001010010 'O''Hara' $DATA1.B24");
        assertThatThrownBy(() -> CommandRenderer.render("${pan}", params.subList(0, 1), Map.of("pan", "123; rm -rf /"), null))
                .hasMessageContaining("Invalid value");
        assertThatThrownBy(() -> CommandRenderer.render("${pan}", params.subList(0, 1), Map.of(), null))
                .hasMessageContaining("required");
        assertThatThrownBy(() -> CommandRenderer.render("${other}", List.of(), Map.of(), null))
                .hasMessageContaining("no parameter");
    }

    @Test
    void parsers() {
        QueryTemplate.Parser delimited = new QueryTemplate.Parser();
        delimited.setType(QueryTemplate.ParserType.DELIMITED);
        String sqlci = ">>SELECT ...\nMTI|PAN|RC\n----|----|--\n0200|4761739001010010|00\n0200|4761739001010028|51\n\n--- 2 row(s) selected.\n";
        List<Map<String, String>> rows = OutputParser.parse(sqlci, delimited);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1)).containsEntry("PAN", "4761739001010028").containsEntry("RC", "51");

        QueryTemplate.Parser regex = new QueryTemplate.Parser();
        regex.setType(QueryTemplate.ParserType.REGEX);
        regex.setRegex("TERM (?<term>\\S+) STATUS (?<status>\\w+)");
        assertThat(OutputParser.parse("TERM T001 STATUS UP\nnoise\nTERM T002 STATUS DOWN", regex))
                .extracting(r -> r.get("status")).containsExactly("UP", "DOWN");

        QueryTemplate.Parser fixed = new QueryTemplate.Parser();
        fixed.setType(QueryTemplate.ParserType.FIXED);
        fixed.setWidths(List.of(4, 6));
        fixed.setHeaderLine(false);
        fixed.setColumns(List.of("A", "B"));
        assertThat(OutputParser.parse("0200ABC   \n0400XYZ", fixed).get(1)).containsEntry("A", "0400").containsEntry("B", "XYZ");

        QueryTemplate.Parser json = new QueryTemplate.Parser();
        json.setJsonPointer("/data");
        assertThat(OutputParser.parse("banner line\n{\"data\":[{\"a\":1,\"b\":{\"c\":2}}]}", json).get(0))
                .containsEntry("a", "1").containsEntry("b", "{\"c\":2}");
    }

    @Test
    void guardianQueryAndImport() {
        String json = "[{\"MTI\":\"0200\",\"PAN\":\"4761739001010010\",\"PROC_CODE\":\"000000\",\"AMOUNT\":\"000000001000\","
                + "\"STAN\":\"000123\",\"TERM_ID\":\"TERM0001\",\"RESP_CODE\":\"00\",\"CURRENCY\":\"032\"},"
                + "{\"MTI\":\"0200\",\"PAN\":\"4761739001010028\",\"PROC_CODE\":\"011000\",\"AMOUNT\":\"000000005000\","
                + "\"STAN\":\"000124\",\"TERM_ID\":\"TERM0002\",\"RESP_CODE\":\"51\",\"CURRENCY\":\"032\"}]";
        GuardianQueryService svc = new GuardianQueryService(cmd -> {
            executed.add(cmd);
            return new ExecResult(cmd, 0, "PTLF extract v1\n" + json, "", 12);
        }, GuardianQueryService.defaultCatalog(null), runs, Map.of());

        assertThat(svc.catalog().all()).extracting(QueryTemplate::getId).contains("ptlf-json", "sqlci-txn", "caf-card");
        QueryResult r = svc.execute("ptlf-json", Map.of("maxRows", "10"));
        assertThat(executed.get(0)).isEqualTo("/usr/local/b24sim/ptlf2json.sh $DATA1.B24LOG.PT250926 000000 235959 10");
        assertThat(r.rows()).hasSize(2);
        assertThat(r.columns()).contains("PAN", "RESP_CODE");
        assertThat(r.toJson()).contains("4761739001010028");
        assertThat(svc.saveSnapshot(r, "DEV", Map.of("maxRows", "10"))).isPositive();

        assertThat(svc.importAsTestCases(r, cases, "guardian")).isEqualTo(2);
        String suite = cases.findSuiteId("guardian").orElseThrow();
        List<TestCaseRecord> imported = cases.bySuite(suite);
        assertThat(imported).extracting(TestCaseRecord::expectedRc).containsExactly("00", "51");
        assertThat(imported.get(0).requestJson()).contains("\"2\":\"4761739001010010\"").contains("\"41\":\"TERM0001\"")
                .contains("${TRANSMISSION_DATETIME}");
        assertThat(runs.auditTrail(10)).isNotEmpty();
    }

    @Test
    void pathwayOverSocket() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            Thread gateway = new Thread(() -> {
                try (Socket s = ss.accept();
                     BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
                     OutputStream out = s.getOutputStream()) {
                    out.write("PATHCOM GATEWAY\r\n=".getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                    String line;
                    while ((line = r.readLine()) != null) {
                        String reply = switch (line.trim().toUpperCase()) {
                            case "STATUS PATHWAY" -> "PATHWAY  RUNNING\r\n  SERVERS 12  TERMS 4";
                            case "STOP SERVER POS-AUTH" -> "SERVER POS-AUTH STOPPED";
                            default -> "*ERROR* UNKNOWN COMMAND";
                        };
                        out.write((line + "\r\n" + reply + "\r\n=").getBytes(StandardCharsets.ISO_8859_1));
                        out.flush();
                    }
                } catch (Exception ignored) {
                    // test gateway
                }
            });
            gateway.setDaemon(true);
            gateway.start();

            PathwayEndpoint ep = new PathwayEndpoint();
            ep.setHost("127.0.0.1");
            ep.setPort(ss.getLocalPort());
            ep.setTimeoutMs(3000);
            CommandPolicy policy = new CommandPolicy(ep.getAllowedVerbs(), ep.getConfirmVerbs());
            try (PathwayService svc = new PathwayService(new SocketPathwayClient(ep), policy, runs)) {
                PathwayService.CommandResult st = svc.execute("STATUS PATHWAY", false, "tester");
                assertThat(st.success()).isTrue();
                assertThat(st.output()).startsWith("PATHWAY  RUNNING").contains("SERVERS 12");

                assertThatThrownBy(() -> svc.execute("STOP SERVER POS-AUTH", false, "tester"))
                        .hasMessageContaining("confirmation");
                assertThat(svc.execute("STOP SERVER POS-AUTH", true, "tester").output()).isEqualTo("SERVER POS-AUTH STOPPED");
                assertThatThrownBy(() -> svc.execute("DELETE SERVER X", true, "tester")).hasMessageContaining("not in the allowed");
                assertThatThrownBy(() -> svc.execute("STATUS PATHWAY; STOP PATHWAY", true, "tester")).hasMessageContaining("chaining");
                assertThat(svc.execute("INFO FOO", false, "tester").success()).isFalse();
                assertThat(svc.history()).hasSize(3);
            }
        }
    }
}
