package org.simulator.acquirer;

import org.simulator.common.SimulatorException;
import org.simulator.iso.IsoMessage;

import java.security.SecureRandom;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves placeholders in templates.
 * <p>
 * Built-ins: {@code STAN, RRN, TRANSMISSION_DATETIME (MMddHHmmss UTC), LOCAL_TIME (HHmmss),
 * LOCAL_DATE (MMdd), DATE_YYMMDD, DATETIME_YYMMDDHHMMSS, EXPIRY_YYMM, RANDOM_N:n, RANDOM_AN:n}.
 * Any other name is looked up in the variables map. Placeholders may carry a default:
 * {@code ${AMOUNT:000000001000}}.
 */
public class TemplateEngine {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_]+)(?::([^}]*))?}");
    private static final DateTimeFormatter MMDDHHMMSS = DateTimeFormatter.ofPattern("MMddHHmmss");
    private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HHmmss");
    private static final DateTimeFormatter MMDD = DateTimeFormatter.ofPattern("MMdd");
    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");
    private static final DateTimeFormatter YYMMDDHHMMSS = DateTimeFormatter.ofPattern("yyMMddHHmmss");
    private static final DateTimeFormatter YYMM = DateTimeFormatter.ofPattern("yyMM");
    private static final String AN = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final SequenceGenerator sequences;
    private final SecureRandom random = new SecureRandom();

    public TemplateEngine(SequenceGenerator sequences) {
        this.sequences = sequences;
    }

    public IsoMessage build(TransactionTemplate t, String defaultDialect, Map<String, String> overrides) {
        Map<String, String> vars = new HashMap<>(t.getVariables());
        if (overrides != null) {
            vars.putAll(overrides);
        }
        Context ctx = new Context(vars);
        String dialect = t.getDialect() != null && !t.getDialect().isBlank() ? t.getDialect() : defaultDialect;
        IsoMessage msg = new IsoMessage(dialect, resolve(t.getMti(), ctx));
        if (t.getHeader() != null) {
            msg.setHeader(resolve(t.getHeader(), ctx));
        }
        for (Map.Entry<String, String> e : t.getFields().entrySet()) {
            int field = parseField(e.getKey());
            String value = resolve(e.getValue(), ctx);
            if (value != null && !value.isEmpty()) {
                msg.set(field, value);
            }
        }
        return msg;
    }

    public String resolve(String text, Map<String, String> vars) {
        return resolve(text, new Context(vars == null ? new HashMap<>() : new HashMap<>(vars)));
    }

    private String resolve(String text, Context ctx) {
        if (text == null || text.indexOf("${") < 0) {
            return text;
        }
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = value(m.group(1), m.group(2), ctx);
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String value(String name, String arg, Context ctx) {
        ZonedDateTime now = ctx.now;
        switch (name) {
            case "STAN": return ctx.stan();
            case "RRN": return sequences.rrn(ctx.stan());
            case "TRANSMISSION_DATETIME": return now.withZoneSameInstant(ZoneOffset.UTC).format(MMDDHHMMSS);
            case "LOCAL_TIME": return now.format(HHMMSS);
            case "LOCAL_DATE": return now.format(MMDD);
            case "DATE_YYMMDD": return now.format(YYMMDD);
            case "DATETIME_YYMMDDHHMMSS": return now.format(YYMMDDHHMMSS);
            case "EXPIRY_YYMM": return now.plusYears(3).format(YYMM);
            case "RANDOM_N": return randomOf("0123456789", len(arg, 6));
            case "RANDOM_AN": return randomOf(AN, len(arg, 6));
            default:
                String v = ctx.vars.get(name);
                if (v != null) {
                    return resolve(v, ctx);
                }
                if (arg != null) {
                    return arg;
                }
                throw new SimulatorException("Unresolved placeholder ${" + name + "}");
        }
    }

    private String randomOf(String alphabet, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private static int len(String arg, int def) {
        try {
            return arg == null ? def : Integer.parseInt(arg.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static int parseField(String key) {
        String k = key.trim().toUpperCase();
        if (k.startsWith("DE")) {
            k = k.substring(2);
        } else if (k.startsWith("F")) {
            k = k.substring(1);
        }
        return Integer.parseInt(k);
    }

    /** Per-message context: STAN and timestamp are generated once so all fields agree. */
    private final class Context {
        final Map<String, String> vars;
        final ZonedDateTime now;
        String stan;

        Context(Map<String, String> vars) {
            this.vars = vars;
            this.now = ZonedDateTime.now(sequences.clock());
            this.stan = vars.get("STAN");
        }

        String stan() {
            if (stan == null) {
                stan = sequences.nextStan();
            }
            return stan;
        }
    }
}
