package org.simulator.ssh;

/** Abstraction over "run a command on the Base24 host" so services can be tested without SSH. */
@FunctionalInterface
public interface RemoteExecutor {

    ExecResult exec(String command);
}
