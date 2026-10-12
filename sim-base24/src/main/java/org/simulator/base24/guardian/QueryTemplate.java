package org.simulator.base24.guardian;

import org.simulator.base24.TemplateParameter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Remote query definition: a command run on Guardian/OSS (SQLCI, ENFORM, a TACL macro or a script)
 * and how to parse its output into rows.
 */
public class QueryTemplate {

    public enum ParserType { JSON, DELIMITED, REGEX, FIXED }

    private String id;
    private String name;
    private String description;
    private String commandTemplate;
    private List<TemplateParameter> parameters = new ArrayList<>();
    private Parser parser = new Parser();
    /** Optional mapping of result rows to ISO test cases. */
    private TestCaseMapping mapping;

    public static class Parser {
        private ParserType type = ParserType.JSON;
        /** DELIMITED: column separator (regex). */
        private String delimiter = "\\|";
        /** DELIMITED/FIXED: first data line holds the column names. */
        private boolean headerLine = true;
        /** Explicit column names (override header line). */
        private List<String> columns = new ArrayList<>();
        /** REGEX: pattern with named groups, applied per line. */
        private String regex;
        /** FIXED: widths of each column. */
        private List<Integer> widths = new ArrayList<>();
        /** Lines matching this regex are ignored (banners, separators, row counts). */
        private String skipLines = "^[\\s\\-|+=]*$|^---.*|^>>.*|^\\s*\\d+ row\\(s\\) selected.*";
        /** JSON: pointer to the array inside the document (e.g. "/transactions"); empty = root. */
        private String jsonPointer = "";
        /** Trim values. */
        private boolean trim = true;

        public ParserType getType() { return type; }
        public void setType(ParserType type) { this.type = type; }
        public String getDelimiter() { return delimiter; }
        public void setDelimiter(String delimiter) { this.delimiter = delimiter; }
        public boolean isHeaderLine() { return headerLine; }
        public void setHeaderLine(boolean headerLine) { this.headerLine = headerLine; }
        public List<String> getColumns() { return columns; }
        public void setColumns(List<String> columns) { this.columns = columns == null ? new ArrayList<>() : columns; }
        public String getRegex() { return regex; }
        public void setRegex(String regex) { this.regex = regex; }
        public List<Integer> getWidths() { return widths; }
        public void setWidths(List<Integer> widths) { this.widths = widths == null ? new ArrayList<>() : widths; }
        public String getSkipLines() { return skipLines; }
        public void setSkipLines(String skipLines) { this.skipLines = skipLines; }
        public String getJsonPointer() { return jsonPointer; }
        public void setJsonPointer(String jsonPointer) { this.jsonPointer = jsonPointer; }
        public boolean isTrim() { return trim; }
        public void setTrim(boolean trim) { this.trim = trim; }
    }

    /** Converts a row into {@code {"dialect","mti","fields":{...}}}. */
    public static class TestCaseMapping {
        private String dialect = "base24-pos";
        /** Column holding the MTI, or a literal when prefixed with '='. */
        private String mti = "=0200";
        /** Column holding the expected response code (optional). */
        private String responseCode;
        /** Column used as test case name (optional). */
        private String nameColumn;
        /** ISO field number -> column name (or '=literal'). */
        private Map<String, String> fields = new LinkedHashMap<>();

        public String getDialect() { return dialect; }
        public void setDialect(String dialect) { this.dialect = dialect; }
        public String getMti() { return mti; }
        public void setMti(String mti) { this.mti = mti; }
        public String getResponseCode() { return responseCode; }
        public void setResponseCode(String responseCode) { this.responseCode = responseCode; }
        public String getNameColumn() { return nameColumn; }
        public void setNameColumn(String nameColumn) { this.nameColumn = nameColumn; }
        public Map<String, String> getFields() { return fields; }
        public void setFields(Map<String, String> fields) { this.fields = fields == null ? new LinkedHashMap<>() : fields; }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCommandTemplate() { return commandTemplate; }
    public void setCommandTemplate(String commandTemplate) { this.commandTemplate = commandTemplate; }
    public List<TemplateParameter> getParameters() { return parameters; }
    public void setParameters(List<TemplateParameter> parameters) { this.parameters = parameters == null ? new ArrayList<>() : parameters; }
    public Parser getParser() { return parser; }
    public void setParser(Parser parser) { this.parser = parser == null ? new Parser() : parser; }
    public TestCaseMapping getMapping() { return mapping; }
    public void setMapping(TestCaseMapping mapping) { this.mapping = mapping; }

    @Override
    public String toString() {
        return name != null ? name : id;
    }
}
