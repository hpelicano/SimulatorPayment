package org.simulator.issuer;

import org.simulator.common.SimulatorException;
import org.simulator.common.json.Json;
import org.simulator.iso.IsoMessage;
import org.simulator.iso.MessageService;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides the response for each incoming request according to {@link IssuerRules}.
 */
public class IssuerRuleEngine {

    /** Decision produced for a request. {@code response == null} means "do not answer". */
    public record Decision(IsoMessage response, long delayMs, String ruleName) {
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Z_]+)(?::([^}]*))?}");

    private final MessageService messages;
    private final SecureRandom random = new SecureRandom();
    private final Map<ResponseRule, AtomicInteger> sequences = new ConcurrentHashMap<>();
    private final Map<String, Pattern> patterns = new ConcurrentHashMap<>();
    private volatile IssuerRules rules;

    public IssuerRuleEngine(MessageService messages, IssuerRules rules) {
        this.messages = messages;
        this.rules = rules;
    }

    public static IssuerRules defaultRules() {
        try (InputStream in = IssuerRuleEngine.class.getResourceAsStream("/issuer/default-rules.json")) {
            if (in == null) {
                return new IssuerRules();
            }
            return Json.mapper().readValue(in, IssuerRules.class);
        } catch (IOException e) {
            throw new SimulatorException("Cannot read default issuer rules", e);
        }
    }

    public static IssuerRules load(Path file) {
        return Json.read(file, IssuerRules.class);
    }

    public IssuerRules rules() {
        return rules;
    }

    public void setRules(IssuerRules rules) {
        this.rules = rules;
        sequences.clear();
    }

    public Decision decide(IsoMessage req) {
        IssuerRules r = rules;
        ResponseRule matched = null;
        for (ResponseRule rule : r.getRules()) {
            if (rule.isEnabled() && matches(rule.getMatch(), req)) {
                matched = rule;
                break;
            }
        }
        ResponseRule.Action action = matched != null ? matched.getAction() : null;
        String ruleName = matched != null ? matched.getName() : "default";

        long delay = r.getDefaultDelayMs();
        if (action != null) {
            delay = action.getDelayMs();
            if (action.getJitterMs() > 0) {
                delay += ThreadLocalRandom.current().nextLong(action.getJitterMs() + 1);
            }
            if (action.isNoResponse()) {
                return new Decision(null, delay, ruleName);
            }
        }

        IsoMessage resp = new IsoMessage(req.getDialect(), IsoMessage.responseMti(req.getMti()));
        resp.setHeader(messages.responseHeader(req.getDialect(), req.getHeader()));
        req.fields().forEach((k, v) -> {
            if (!r.getExcludeFromResponse().contains(k) && k != 1 && k != 65) {
                resp.set(k, v);
            }
        });

        String rc = responseCode(r, matched, action);
        resp.set(39, rc);
        boolean financial = req.getMti() != null && (req.getMti().startsWith("01") || req.getMti().startsWith("02"));
        if (financial && r.isGenerateAuthCode() && ("00".equals(rc) || "10".equals(rc)) && !resp.has(38)) {
            resp.set(38, randomDigits(6));
        }
        if (action != null) {
            action.getRemoveFields().forEach(resp::remove);
            for (Map.Entry<String, String> e : action.getSetFields().entrySet()) {
                resp.set(Integer.parseInt(e.getKey().replaceAll("\\D", "")), expand(e.getValue(), req));
            }
        }
        return new Decision(resp, delay, ruleName);
    }

    private String responseCode(IssuerRules r, ResponseRule rule, ResponseRule.Action action) {
        if (action != null && !action.getResponseCodeSequence().isEmpty()) {
            int i = sequences.computeIfAbsent(rule, k -> new AtomicInteger()).getAndIncrement();
            return action.getResponseCodeSequence().get(Math.floorMod(i, action.getResponseCodeSequence().size()));
        }
        if (action != null && action.getResponseCode() != null) {
            return action.getResponseCode();
        }
        return r.getDefaultResponseCode();
    }

    boolean matches(ResponseRule.Match m, IsoMessage req) {
        if (!regex(m.getMti(), req.getMti())) {
            return false;
        }
        if (!regex(m.getProcessingCode(), req.get(3))) {
            return false;
        }
        if (!regex(m.getTerminalId(), req.get(41))) {
            return false;
        }
        if (!m.getBinPrefixes().isEmpty()) {
            String pan = req.get(2);
            if (pan == null || m.getBinPrefixes().stream().noneMatch(pan::startsWith)) {
                return false;
            }
        }
        if (m.getAmountMin() != null || m.getAmountMax() != null) {
            BigDecimal amount;
            try {
                amount = new BigDecimal(req.get(4).trim());
            } catch (RuntimeException e) {
                return false;
            }
            if (m.getAmountMin() != null && amount.compareTo(m.getAmountMin()) < 0) {
                return false;
            }
            if (m.getAmountMax() != null && amount.compareTo(m.getAmountMax()) > 0) {
                return false;
            }
        }
        for (Map.Entry<String, String> e : m.getFields().entrySet()) {
            int f = Integer.parseInt(e.getKey().replaceAll("\\D", ""));
            if ("!".equals(e.getValue())) {
                if (req.has(f)) {
                    return false;
                }
            } else if (!req.has(f) || !regex(e.getValue(), req.get(f))) {
                return false;
            }
        }
        return true;
    }

    private boolean regex(String pattern, String value) {
        if (pattern == null || pattern.isBlank()) {
            return true;
        }
        if (value == null) {
            return false;
        }
        return patterns.computeIfAbsent(pattern, Pattern::compile).matcher(value).matches();
    }

    private String expand(String value, IsoMessage req) {
        if (value == null || !value.contains("${")) {
            return value;
        }
        Matcher m = PLACEHOLDER.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String arg = m.group(2);
            String rep = switch (m.group(1)) {
                case "RANDOM_N" -> randomDigits(arg == null ? 6 : Integer.parseInt(arg));
                case "REQ" -> {
                    String v = arg == null ? null : req.get(Integer.parseInt(arg));
                    yield v == null ? "" : v;
                }
                default -> m.group(0);
            };
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String randomDigits(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append((char) ('0' + random.nextInt(10)));
        }
        return sb.toString();
    }
}
