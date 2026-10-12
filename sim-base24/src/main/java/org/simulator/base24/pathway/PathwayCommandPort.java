package org.simulator.base24.pathway;

/**
 * Transport used to deliver PATHCOM commands to Base24 (raw TCP gateway or SSH + PATHCOM).
 */
public interface PathwayCommandPort extends AutoCloseable {

    void connect();

    boolean isConnected();

    /** Sends one command and returns its output (without the trailing prompt). */
    String execute(String command);

    String describe();

    @Override
    void close();
}
