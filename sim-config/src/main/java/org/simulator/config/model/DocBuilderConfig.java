package org.simulator.config.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;

/** Site-specific Guardian commands and output layouts for transaction evidence. */
public class DocBuilderConfig {
    private String gtaclExecutable = "gtacl";
    /**
     * Trusted OSS shell template. Both placeholders are substituted as POSIX-quoted literals.
     * No default flags are assumed: configure the site's verified gtacl entry point.
     * Example shape: printf '%s\n' ${command} | ${gtaclExecutable} SITE_FLAGS
     */
    private String gtaclCommandTemplate = "";
    /** Guardian command placeholders: file, fromTime, toTime, maxRows, pan, terminal. */
    private String ptlfCommandTemplate = "";
    /** Guardian command placeholders: files, processName, options, pan, terminal. */
    private String auditCommandTemplate = "";
    private OutputParserConfig ptlfParser = new OutputParserConfig();
    private OutputParserConfig auditParser = new OutputParserConfig();

    public String getGtaclExecutable() { return gtaclExecutable; }
    public void setGtaclExecutable(String value) { gtaclExecutable = value; }
    public String getGtaclCommandTemplate() { return gtaclCommandTemplate; }
    public void setGtaclCommandTemplate(String value) { gtaclCommandTemplate = value; }
    /** Compatibility alias; JSON writes only the canonical gtaclCommandTemplate property. */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    public String getGtaclLauncherTemplate() { return gtaclCommandTemplate; }
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    public void setGtaclLauncherTemplate(String value) { gtaclCommandTemplate = value; }
    public String getPtlfCommandTemplate() { return ptlfCommandTemplate; }
    public void setPtlfCommandTemplate(String value) { ptlfCommandTemplate = value; }
    public String getAuditCommandTemplate() { return auditCommandTemplate; }
    public void setAuditCommandTemplate(String value) { auditCommandTemplate = value; }
    public OutputParserConfig getPtlfParser() { return ptlfParser; }
    public void setPtlfParser(OutputParserConfig value) {
        ptlfParser = value == null ? new OutputParserConfig() : value;
    }
    public OutputParserConfig getAuditParser() { return auditParser; }
    public void setAuditParser(OutputParserConfig value) {
        auditParser = value == null ? new OutputParserConfig() : value;
    }

    /** Independent configuration DTO, avoiding a dependency from sim-config to sim-base24. */
    public static class OutputParserConfig {
        private String type = "RAW";
        private String delimiter = "\\|";
        private boolean headerLine = true;
        private List<String> columns = new ArrayList<>();
        private String regex;
        private List<Integer> widths = new ArrayList<>();
        private String skipLines = "^[\\s\\-|+=]*$|^---.*|^>>.*|^\\s*\\d+ row\\(s\\) selected.*";
        private String jsonPointer = "";
        private boolean trim = true;

        public String getType() { return type; }
        public void setType(String value) { type = value; }
        public String getDelimiter() { return delimiter; }
        public void setDelimiter(String value) { delimiter = value; }
        public boolean isHeaderLine() { return headerLine; }
        public void setHeaderLine(boolean value) { headerLine = value; }
        public List<String> getColumns() { return columns; }
        public void setColumns(List<String> value) { columns = value == null ? new ArrayList<>() : value; }
        public String getRegex() { return regex; }
        public void setRegex(String value) { regex = value; }
        public List<Integer> getWidths() { return widths; }
        public void setWidths(List<Integer> value) { widths = value == null ? new ArrayList<>() : value; }
        public String getSkipLines() { return skipLines; }
        public void setSkipLines(String value) { skipLines = value; }
        public String getJsonPointer() { return jsonPointer; }
        public void setJsonPointer(String value) { jsonPointer = value; }
        public boolean isTrim() { return trim; }
        public void setTrim(boolean value) { trim = value; }
    }
}
