package org.simulator.validator;

/**
 * @param target  REQUEST or RESPONSE
 * @param field   data element number (0 for message-level issues such as MTI)
 */
public record ValidationIssue(Severity severity, String target, int field, String rule, String expected, String actual,
                              String message) {

    public enum Severity { ERROR, WARNING, INFO }

    public static ValidationIssue error(String target, int field, String rule, String expected, String actual, String message) {
        return new ValidationIssue(Severity.ERROR, target, field, rule, expected, actual, message);
    }

    @Override
    public String toString() {
        return severity + " " + target + (field > 0 ? " DE" + field : "") + " [" + rule + "] " + message
                + (expected != null ? " expected=" + expected : "") + (actual != null ? " actual=" + actual : "");
    }
}
