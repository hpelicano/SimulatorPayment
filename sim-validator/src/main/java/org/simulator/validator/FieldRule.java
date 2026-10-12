package org.simulator.validator;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Declarative field rule, typically loaded from JSON:
 * <pre>{"field": 39, "type": "IN", "values": ["00","10"]}</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FieldRule {

    public enum Type { REQUIRED, ABSENT, EQUALS, NOT_EQUALS, REGEX, IN, LENGTH, NUMERIC, LUHN, RANGE, ECHO, STARTS_WITH }

    private int field;
    private Type type;
    private String value;
    private List<String> values;
    private Integer min;
    private Integer max;
    /** Only apply the rule when the request MTI matches this regex. */
    private String whenMti;
    private ValidationIssue.Severity severity = ValidationIssue.Severity.ERROR;
    private String description;

    public FieldRule() {
    }

    public FieldRule(int field, Type type, String value) {
        this.field = field;
        this.type = type;
        this.value = value;
    }

    public static FieldRule of(int field, Type type) {
        return new FieldRule(field, type, null);
    }

    public int getField() { return field; }
    public void setField(int field) { this.field = field; }
    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public List<String> getValues() { return values; }
    public void setValues(List<String> values) { this.values = values; }
    public Integer getMin() { return min; }
    public void setMin(Integer min) { this.min = min; }
    public Integer getMax() { return max; }
    public void setMax(Integer max) { this.max = max; }
    public String getWhenMti() { return whenMti; }
    public void setWhenMti(String whenMti) { this.whenMti = whenMti; }
    public ValidationIssue.Severity getSeverity() { return severity; }
    public void setSeverity(ValidationIssue.Severity severity) { this.severity = severity; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
