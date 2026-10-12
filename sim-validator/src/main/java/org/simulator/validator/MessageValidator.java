package org.simulator.validator;

import org.simulator.common.util.Luhn;
import org.simulator.iso.IsoMessage;
import org.simulator.iso.dialect.FieldDef;
import org.simulator.iso.dialect.MessageDialect;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Field-level message validator: structural checks against the dialect, declarative rules, echo of request fields,
 * expected response code and message-to-message comparison for regression.
 */
public class MessageValidator {

    private static final Pattern N = Pattern.compile("\\d*");
    private static final Pattern A = Pattern.compile("[A-Za-z ]*");
    private static final Pattern AN = Pattern.compile("[A-Za-z0-9 ]*");
    private static final Pattern NS = Pattern.compile("[0-9 \\p{Punct}]*");
    private static final Pattern Z = Pattern.compile("[0-9=D]*");
    private static final Pattern B = Pattern.compile("([0-9A-Fa-f]{2})*");
    private static final Pattern ANS = Pattern.compile("[\\x20-\\x7E]*");

    /** Validates type and length of every field of {@code msg} against {@code dialect}. */
    public ValidationResult validateStructure(String target, IsoMessage msg, MessageDialect dialect) {
        ValidationResult r = new ValidationResult();
        if (msg.getMti() == null || !msg.getMti().matches("\\d{4}")) {
            r.add(ValidationIssue.error(target, 0, "MTI", "4 digits", msg.getMti(), "Invalid MTI"));
        }
        msg.fields().forEach((n, v) -> {
            FieldDef def = dialect.field(n);
            if (def == null) {
                r.add(ValidationIssue.error(target, n, "DEFINED", null, v, "Field not defined in dialect " + dialect.getId()));
                return;
            }
            Pattern p = switch (def.getType()) {
                case N -> N;
                case A -> A;
                case AN -> AN;
                case NS -> NS;
                case Z -> Z;
                case B -> B;
                case ANS -> ANS;
            };
            if (!p.matcher(v).matches()) {
                r.add(ValidationIssue.error(target, n, "TYPE", def.getType().name(), v, "Invalid characters for type " + def.getType()));
            }
            int len = def.getType() == org.simulator.iso.dialect.FieldType.B ? v.length() / 2 : v.length();
            if (len > def.getLength()) {
                r.add(ValidationIssue.error(target, n, "LENGTH", (def.isVariable() ? "<=" : "=") + def.getLength(),
                        String.valueOf(len), "Length out of bounds"));
            } else if (!def.isVariable() && len < def.getLength()) {
                r.add(new ValidationIssue(ValidationIssue.Severity.WARNING, target, n, "LENGTH", "=" + def.getLength(),
                        String.valueOf(len), "Value will be padded"));
            }
        });
        return r;
    }

    /** Validates a request before sending (structure + request rules). */
    public ValidationResult validateRequest(ValidationProfile profile, IsoMessage request, MessageDialect dialect) {
        ValidationResult r = new ValidationResult();
        if (profile.isStructural() && dialect != null) {
            r.addAll(validateStructure("REQUEST", request, dialect));
        }
        profile.getRequestRules().forEach(rule -> apply("REQUEST", rule, request, null, request.getMti(), r));
        return r;
    }

    /** Validates a full exchange: request rules, response structure, MTI, echo fields, expected RC and response rules. */
    public ValidationResult validateExchange(ValidationProfile profile, IsoMessage request, IsoMessage response,
                                             MessageDialect dialect) {
        ValidationResult r = validateRequest(profile, request, dialect);
        if (response == null) {
            return r.add(ValidationIssue.error("RESPONSE", 0, "RESPONSE", "response", "none", "No response received"));
        }
        if (profile.isStructural() && dialect != null) {
            r.addAll(validateStructure("RESPONSE", response, dialect));
        }
        if (profile.isCheckResponseMti() && request.getMti() != null) {
            String expected = IsoMessage.responseMti(request.getMti());
            if (!expected.equals(response.getMti())) {
                r.add(ValidationIssue.error("RESPONSE", 0, "MTI", expected, response.getMti(), "Unexpected response MTI"));
            }
        }
        for (int f : profile.getEchoFields()) {
            if (request.has(f)) {
                apply("RESPONSE", new FieldRule(f, FieldRule.Type.ECHO, null), response, request, request.getMti(), r);
            }
        }
        String rc = profile.getExpectedResponseCode();
        if (rc != null && !rc.isBlank()) {
            String actual = response.get(39);
            boolean ok = actual != null && Arrays.stream(rc.split("\\|")).map(String::trim).anyMatch(actual.trim()::equals);
            if (!ok) {
                r.add(ValidationIssue.error("RESPONSE", 39, "RESPONSE_CODE", rc, actual, "Unexpected response code"));
            }
        }
        profile.getResponseRules().forEach(rule -> apply("RESPONSE", rule, response, request, request.getMti(), r));
        return r;
    }

    /**
     * Compares an expected (baseline) response with the actual one, ignoring volatile fields.
     */
    public ValidationResult compare(IsoMessage expected, IsoMessage actual, Set<Integer> ignoreFields) {
        ValidationResult r = new ValidationResult();
        if (!Objects.equals(expected.getMti(), actual.getMti())) {
            r.add(ValidationIssue.error("RESPONSE", 0, "COMPARE", expected.getMti(), actual.getMti(), "MTI differs"));
        }
        Set<Integer> all = new TreeSet<>(expected.fields().keySet());
        all.addAll(actual.fields().keySet());
        for (int f : all) {
            if (ignoreFields.contains(f)) {
                continue;
            }
            String e = expected.get(f);
            String a = actual.get(f);
            if (!Objects.equals(e, a)) {
                r.add(ValidationIssue.error("RESPONSE", f, "COMPARE", e, a, e == null ? "Unexpected field" : a == null ? "Missing field" : "Value differs"));
            }
        }
        return r;
    }

    private void apply(String target, FieldRule rule, IsoMessage msg, IsoMessage request, String requestMti, ValidationResult r) {
        if (rule.getWhenMti() != null && (requestMti == null || !requestMti.matches(rule.getWhenMti()))) {
            return;
        }
        int f = rule.getField();
        String v = msg.get(f);
        String failure = switch (rule.getType()) {
            case REQUIRED -> v == null ? "Field is required" : null;
            case ABSENT -> v != null ? "Field must be absent" : null;
            case EQUALS -> !Objects.equals(rule.getValue(), v) ? "Value mismatch" : null;
            case NOT_EQUALS -> Objects.equals(rule.getValue(), v) ? "Value must differ" : null;
            case STARTS_WITH -> v == null || !v.startsWith(rule.getValue()) ? "Prefix mismatch" : null;
            case REGEX -> v == null || !v.matches(rule.getValue()) ? "Does not match pattern" : null;
            case IN -> v == null || rule.getValues() == null || !rule.getValues().contains(v) ? "Value not allowed" : null;
            case LENGTH -> v == null || (rule.getMin() != null && v.length() < rule.getMin())
                    || (rule.getMax() != null && v.length() > rule.getMax()) ? "Length out of range" : null;
            case NUMERIC -> v == null || !v.matches("\\d+") ? "Not numeric" : null;
            case LUHN -> v == null || !Luhn.isValid(v) ? "Luhn check failed" : null;
            case RANGE -> rangeFailure(v, rule);
            case ECHO -> {
                String req = request == null ? null : request.get(f);
                yield !Objects.equals(req, v) ? "Response does not echo request value" : null;
            }
        };
        if (failure != null) {
            String expected = switch (rule.getType()) {
                case IN -> String.valueOf(rule.getValues());
                case LENGTH, RANGE -> "[" + rule.getMin() + ".." + rule.getMax() + "]";
                case ECHO -> request == null ? null : request.get(f);
                default -> rule.getValue();
            };
            r.add(new ValidationIssue(rule.getSeverity(), target, f, rule.getType().name(), expected, v,
                    rule.getDescription() != null ? rule.getDescription() + ": " + failure : failure));
        }
    }

    private static String rangeFailure(String v, FieldRule rule) {
        if (v == null || !v.matches("\\d+")) {
            return "Not numeric";
        }
        long n = Long.parseLong(v);
        if ((rule.getMin() != null && n < rule.getMin()) || (rule.getMax() != null && n > rule.getMax())) {
            return "Value out of range";
        }
        return null;
    }
}
