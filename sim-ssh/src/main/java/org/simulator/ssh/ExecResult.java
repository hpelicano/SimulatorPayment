package org.simulator.ssh;

/** Result of a remote command. */
public record ExecResult(String command, int exitCode, String stdout, String stderr, long durationMs) {

    public boolean ok() {
        return exitCode == 0;
    }
}
