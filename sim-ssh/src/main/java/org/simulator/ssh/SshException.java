package org.simulator.ssh;

import org.simulator.common.SimulatorException;

public class SshException extends SimulatorException {

    public SshException(String message) {
        super(message);
    }

    public SshException(String message, Throwable cause) {
        super(message, cause);
    }
}
