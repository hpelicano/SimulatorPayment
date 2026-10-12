package org.simulator.transport;

import org.simulator.common.SimulatorException;

public class TransportException extends SimulatorException {

    private final boolean timeout;

    public TransportException(String message, boolean timeout) {
        super(message);
        this.timeout = timeout;
    }

    public TransportException(String message, Throwable cause) {
        super(message, cause);
        this.timeout = false;
    }

    public boolean isTimeout() {
        return timeout;
    }
}
