package org.simulator.base24.pathway;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Whitelist of PATHCOM verbs. Destructive verbs need explicit confirmation; command chaining is blocked.
 */
public class CommandPolicy {

    public enum Verdict { ALLOWED, NEEDS_CONFIRMATION, DENIED }

    public record Decision(Verdict verdict, String verb, String reason) {
    }

    private final Set<String> allowed;
    private final Set<String> confirm;

    public CommandPolicy(List<String> allowedVerbs, List<String> confirmVerbs) {
        this.allowed = upper(allowedVerbs);
        this.confirm = upper(confirmVerbs);
    }

    private static Set<String> upper(List<String> l) {
        return l == null ? Set.of() : l.stream().map(s -> s.trim().toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
    }

    public Decision check(String command) {
        if (command == null || command.isBlank()) {
            return new Decision(Verdict.DENIED, null, "Empty command");
        }
        if (command.contains(";") || command.contains("\n") || command.contains("\r")) {
            return new Decision(Verdict.DENIED, null, "Command chaining is not allowed");
        }
        String verb = command.trim().split("\\s+")[0].toUpperCase(Locale.ROOT);
        if (!allowed.contains(verb)) {
            return new Decision(Verdict.DENIED, verb, "Verb '" + verb + "' is not in the allowed list " + allowed);
        }
        if (confirm.contains(verb)) {
            return new Decision(Verdict.NEEDS_CONFIRMATION, verb, "Verb '" + verb + "' requires confirmation");
        }
        return new Decision(Verdict.ALLOWED, verb, "OK");
    }
}
