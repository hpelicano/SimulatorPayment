package org.simulator.base24.pathway;

import org.simulator.common.SimulatorException;
import org.simulator.persistence.repo.RunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Pathway command console: policy enforcement, execution, history and audit.
 */
public class PathwayService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PathwayService.class);
    private static final int HISTORY = 200;

    public record CommandResult(String command, boolean success, String output, long durationMs, Instant at) {
    }

    private final PathwayCommandPort port;
    private final CommandPolicy policy;
    private final RunRepository runs;
    private final String source;
    private final Deque<CommandResult> history = new ArrayDeque<>();

    public PathwayService(PathwayCommandPort port, CommandPolicy policy, RunRepository runs) {
        this(port, policy, runs, "PATHWAY");
    }

    public PathwayService(PathwayCommandPort port, CommandPolicy policy, RunRepository runs, String source) {
        this.port = port;
        this.policy = policy;
        this.runs = runs;
        this.source = source;
    }

    public CommandPolicy.Decision check(String command) {
        return policy.check(command);
    }

    public CommandResult execute(String command, boolean confirmed, String user) {
        CommandPolicy.Decision d = policy.check(command);
        if (d.verdict() == CommandPolicy.Verdict.DENIED) {
            audit(user, command, false, d.reason());
            throw new SimulatorException(d.reason());
        }
        if (d.verdict() == CommandPolicy.Verdict.NEEDS_CONFIRMATION && !confirmed) {
            throw new SimulatorException(d.reason());
        }
        long start = System.currentTimeMillis();
        CommandResult r;
        try {
            String out = port.execute(command.trim());
            boolean ok = !out.matches("(?is).*\\*\\s*ERROR\\s*\\*.*|.*\\bERROR\\s*-\\s*\\*.*");
            r = new CommandResult(command, ok, out, System.currentTimeMillis() - start, Instant.now());
        } catch (RuntimeException e) {
            r = new CommandResult(command, false, e.getMessage(), System.currentTimeMillis() - start, Instant.now());
        }
        synchronized (history) {
            history.addFirst(r);
            while (history.size() > HISTORY) {
                history.removeLast();
            }
        }
        audit(user, command, r.success(), r.output());
        log.info("{} '{}' -> {}", source, command, r.success() ? "OK" : "ERROR");
        return r;
    }

    public List<CommandResult> history() {
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }

    public PathwayCommandPort port() {
        return port;
    }

    private void audit(String user, String command, boolean ok, String detail) {
        if (runs != null) {
            String d = detail == null ? "" : detail.length() > 2000 ? detail.substring(0, 2000) : detail;
            runs.audit(user, source, command, d, ok);
        }
    }

    @Override
    public void close() {
        port.close();
    }
}
