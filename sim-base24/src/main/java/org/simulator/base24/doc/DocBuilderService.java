package org.simulator.base24.doc;

import org.simulator.base24.guardian.OutputParser;
import org.simulator.base24.guardian.QueryResult;
import org.simulator.base24.guardian.QueryTemplate;
import org.simulator.common.SimulatorException;
import org.simulator.config.model.DocBuilderConfig;
import org.simulator.config.model.DocBuilderConfig.OutputParserConfig;
import org.simulator.persistence.repo.RunRepository;
import org.simulator.ssh.ExecResult;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Executes site-configured transaction evidence queries via SSH into OSS. */
public final class DocBuilderService {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss")
            .withResolverStyle(ResolverStyle.STRICT);
    private final Function<String, ExecResult> executor;
    private final DocBuilderConfig config;
    private final RunRepository runs;

    public record PtlfParameters(String manualCommand, String file, String fromTime, String toTime,
                                 String maxRows, String pan, String terminal) {}
    public record AuditParameters(String manualCommand, String files, String processName, String options,
                                  String pan, String terminal) {}

    public DocBuilderService(Function<String, ExecResult> executor, DocBuilderConfig config, RunRepository runs) {
        this.executor = Objects.requireNonNull(executor, "SSH executor");
        this.config = config == null ? new DocBuilderConfig() : config;
        this.runs = runs;
    }

    public String previewPtlf(PtlfParameters parameters) {
        return launch(ptlfCommand(Objects.requireNonNull(parameters)));
    }

    public String previewAudit(AuditParameters parameters) {
        return launch(auditCommand(Objects.requireNonNull(parameters)));
    }

    /** Manual Guardian commands require explicit confirmation by the caller before execution. */
    public QueryResult queryPtlf(PtlfParameters parameters, String user) {
        String command = previewPtlf(parameters);
        int limit = present(parameters.manualCommand()) ? Integer.MAX_VALUE : maxRows(parameters.maxRows());
        return execute("PTLF", command, config.getPtlfParser(), user, limit);
    }

    /** Manual Guardian commands require explicit confirmation by the caller before execution. */
    public QueryResult queryAudit(AuditParameters parameters, String user) {
        return execute("AUDIT", previewAudit(parameters), config.getAuditParser(), user, Integer.MAX_VALUE);
    }

    private String ptlfCommand(PtlfParameters p) {
        validateLines(p.manualCommand(), p.file(), p.fromTime(), p.toTime(), p.maxRows(), p.pan(), p.terminal());
        if (present(p.manualCommand())) return p.manualCommand();
        requireTemplate(config.getPtlfCommandTemplate(), "ptlfCommandTemplate");
        String file = field(p.file(), "file", true);
        if (!file.matches("(?:\\\\[A-Za-z0-9_-]+\\.)?\\$?[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_*-]+){0,2}")) {
            throw new SimulatorException("Invalid Guardian file");
        }
        LocalTime from = time(p.fromTime(), "fromTime");
        LocalTime to = time(p.toTime(), "toTime");
        if (from.isAfter(to)) throw new SimulatorException("fromTime must not be after toTime");
        int maximum = maxRows(p.maxRows());
        return render(config.getPtlfCommandTemplate(), Map.of("file", file, "fromTime", p.fromTime(),
                "toTime", p.toTime(), "maxRows", Integer.toString(maximum),
                "pan", pan(p.pan()), "terminal", field(p.terminal(), "terminal", false)));
    }

    private String auditCommand(AuditParameters p) {
        validateLines(p.manualCommand(), p.files(), p.processName(), p.options(), p.pan(), p.terminal());
        if (present(p.manualCommand())) return p.manualCommand();
        requireTemplate(config.getAuditCommandTemplate(), "auditCommandTemplate");
        return render(config.getAuditCommandTemplate(), Map.of(
                "files", field(p.files(), "files", false),
                "processName", field(p.processName(), "processName", false),
                "options", field(p.options(), "options", false),
                "pan", pan(p.pan()), "terminal", field(p.terminal(), "terminal", false)));
    }

    private String launch(String guardianCommand) {
        oneLine(guardianCommand);
        String executable = field(config.getGtaclExecutable(), "gtaclExecutable", true);
        String launcher = config.getGtaclCommandTemplate();
        if (!present(launcher)) {
            throw new SimulatorException("Configure docBuilder.gtaclCommandTemplate with the site's verified OSS gtacl launcher");
        }
        if (!launcher.contains("${command}")) {
            throw new SimulatorException("gtaclCommandTemplate must contain ${command}");
        }
        return render(launcher, Map.of("command", quote(guardianCommand), "gtaclExecutable", quote(executable)));
    }

    private QueryResult execute(String kind, String command, OutputParserConfig parser, String user, int limit) {
        ExecResult remote;
        List<Map<String, String>> rows;
        try {
            remote = Objects.requireNonNull(executor.apply(command), "SSH execution result");
            String output = remote.stdout() == null ? "" : remote.stdout();
            rows = output.isEmpty() ? List.of() : parse(output, parser);
            if (rows.size() > limit) rows = new ArrayList<>(rows.subList(0, limit));
        } catch (RuntimeException e) {
            audit(user, kind, command, false);
            throw e;
        }
        audit(user, kind, command, remote.ok());
        LinkedHashSet<String> columns = new LinkedHashSet<>();
        rows.forEach(row -> columns.addAll(row.keySet()));
        return new QueryResult(kind, command, remote.exitCode(), new ArrayList<>(columns), rows,
                remote.stdout() == null ? "" : remote.stdout(),
                remote.stderr() == null ? "" : remote.stderr(), remote.durationMs());
    }

    private void audit(String user, String kind, String command, boolean success) {
        if (runs != null) runs.audit(user, "DOC_BUILDER_" + kind, kind, command, success);
    }

    private static List<Map<String, String>> parse(String output, OutputParserConfig config) {
        String type = config.getType() == null ? "" : config.getType().toUpperCase(Locale.ROOT);
        if ("RAW".equals(type)) {
            return output.lines().map(line -> Map.of("line", line)).toList();
        }
        QueryTemplate.Parser parser = new QueryTemplate.Parser();
        try {
            parser.setType(QueryTemplate.ParserType.valueOf(type));
        } catch (IllegalArgumentException e) {
            throw new SimulatorException("Unknown Doc Builder output parser: " + config.getType(), e);
        }
        parser.setDelimiter(config.getDelimiter());
        parser.setHeaderLine(config.isHeaderLine());
        parser.setColumns(config.getColumns());
        parser.setRegex(config.getRegex());
        parser.setWidths(config.getWidths());
        parser.setSkipLines(config.getSkipLines());
        parser.setJsonPointer(config.getJsonPointer());
        parser.setTrim(config.isTrim());
        return OutputParser.parse(output, parser);
    }

    private static String render(String template, Map<String, String> values) {
        oneLine(template);
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuffer rendered = new StringBuffer();
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            if (value == null) throw new SimulatorException("Unknown Doc Builder placeholder: " + matcher.group());
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static String field(String value, String name, boolean required) {
        String text = value == null ? "" : value.trim();
        oneLine(text);
        if (required && text.isBlank()) throw new SimulatorException(name + " is required");
        // Parameters are data, not an additional Guardian command or OSS expression.
        if (text.matches(".*[;|&`<>].*")) throw new SimulatorException("Invalid command separator in " + name);
        return text;
    }

    private static String pan(String value) {
        String text = field(value, "pan", false);
        if (!text.isEmpty() && !text.matches("\\d{12,19}")) {
            throw new SimulatorException("PAN must contain 12 to 19 digits (masked PAN cannot be queried)");
        }
        return text;
    }

    private static int maxRows(String value) {
        String text = field(value, "maxRows", true);
        try {
            int maximum = Integer.parseInt(text);
            if (maximum > 0 && maximum <= 100_000) return maximum;
        } catch (NumberFormatException ignored) {
            // Report the same validation error for overflow and non-numeric input.
        }
        throw new SimulatorException("maxRows must be between 1 and 100000");
    }

    private static LocalTime time(String value, String name) {
        String text = field(value, name, true);
        try {
            return LocalTime.parse(text, TIME);
        } catch (DateTimeParseException e) {
            throw new SimulatorException(name + " must be a valid HHMMSS time", e);
        }
    }

    private static void requireTemplate(String template, String name) {
        if (!present(template)) {
            throw new SimulatorException("Configure docBuilder." + name + " or enter a manual Guardian command");
        }
    }

    private static boolean present(String text) { return text != null && !text.isBlank(); }

    private static void validateLines(String... values) {
        for (String value : values) if (value != null) oneLine(value);
    }

    private static void oneLine(String value) {
        if (value == null || value.chars().anyMatch(c -> Character.isISOControl(c) || c == 0x2028 || c == 0x2029)) {
            throw new SimulatorException("Doc Builder commands and parameters must be one line without control characters");
        }
    }
}
