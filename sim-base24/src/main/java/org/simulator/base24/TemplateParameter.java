package org.simulator.base24;

/**
 * Parameter of a remote command template. Values are validated against {@code pattern} before being
 * substituted, which prevents command injection on the Guardian host.
 */
public class TemplateParameter {

    /** Conservative default: identifiers, Guardian names ($VOL.SUBVOL.FILE), numbers, dates. */
    public static final String DEFAULT_PATTERN = "[A-Za-z0-9_.$#:/\\- ]{0,64}";

    private String name;
    private String label;
    private String pattern;
    private boolean required = true;
    private String defaultValue;
    /** SQL string literal: single quotes are doubled after validation. */
    private boolean sqlString;

    public TemplateParameter() {
    }

    public TemplateParameter(String name, String pattern, boolean required) {
        this.name = name;
        this.pattern = pattern;
        this.required = required;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getLabel() { return label != null ? label : name; }
    public void setLabel(String label) { this.label = label; }
    public String getPattern() { return pattern != null && !pattern.isBlank() ? pattern : DEFAULT_PATTERN; }
    public void setPattern(String pattern) { this.pattern = pattern; }
    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }
    public String getDefaultValue() { return defaultValue; }
    public void setDefaultValue(String defaultValue) { this.defaultValue = defaultValue; }
    public boolean isSqlString() { return sqlString; }
    public void setSqlString(boolean sqlString) { this.sqlString = sqlString; }
}
