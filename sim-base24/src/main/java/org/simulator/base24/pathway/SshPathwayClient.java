package org.simulator.base24.pathway;

import org.simulator.config.model.PathwayEndpoint;
import org.simulator.ssh.InteractiveShell;
import org.simulator.ssh.SshClient;

import java.util.regex.Pattern;

/**
 * Sends PATHCOM commands through an SSH interactive shell: opens TACL, starts
 * {@code PATHCOM <pathmon>} and then exchanges commands using the PATHCOM prompt.
 */
public class SshPathwayClient implements PathwayCommandPort {

    private final SshClient ssh;
    private final PathwayEndpoint ep;
    private final String taclPrompt;
    private final String startupCommand;
    private InteractiveShell shell;

    /**
     * @param taclPrompt regex of the TACL prompt shown after login, e.g. {@code "\\d+> ?$"}
     */
    public SshPathwayClient(SshClient ssh, PathwayEndpoint ep, String taclPrompt) {
        this(ssh, ep, taclPrompt, "PATHCOM " + ep.getPathmon());
    }

    public SshPathwayClient(SshClient ssh, PathwayEndpoint ep, String taclPrompt, String startupCommand) {
        this.ssh = ssh;
        this.ep = ep;
        this.taclPrompt = taclPrompt;
        this.startupCommand = startupCommand;
    }

    private String promptRegex() {
        return "(?m)^" + Pattern.quote(ep.getPrompt()) + "\\s*$";
    }

    @Override
    public synchronized void connect() {
        if (isConnected()) {
            return;
        }
        shell = ssh.openShell(ep.getLineTerminator());
        shell.expect(taclPrompt, ep.getTimeoutMs());
        shell.sendLine(startupCommand);
        shell.expect(promptRegex(), ep.getTimeoutMs());
    }

    @Override
    public boolean isConnected() {
        return shell != null && shell.isOpen();
    }

    @Override
    public synchronized String execute(String command) {
        connect();
        return shell.command(command, promptRegex(), ep.getTimeoutMs());
    }

    @Override
    public String describe() {
        return "ssh://" + ssh.endpoint().getHost() + ":" + ssh.endpoint().getPort() + " " + startupCommand;
    }

    @Override
    public synchronized void close() {
        if (shell != null) {
            try {
                shell.sendLine("EXIT");
            } catch (RuntimeException ignored) {
                // closing
            }
            shell.close();
            shell = null;
        }
    }
}
