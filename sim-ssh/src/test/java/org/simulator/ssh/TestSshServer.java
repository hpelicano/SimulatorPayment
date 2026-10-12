package org.simulator.ssh;

import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Embedded SSH server used by tests to emulate the Guardian host.
 * Exec commands are answered by {@code responder}; the shell emulates a TACL-like prompt.
 */
public final class TestSshServer implements AutoCloseable {

    public record Reply(String stdout, String stderr, int exit) {
        public static Reply ok(String out) {
            return new Reply(out, "", 0);
        }
    }

    private final SshServer sshd;

    public TestSshServer(Path dir, String user, String password, Function<String, Reply> responder,
                         Map<String, String> shellReplies, String prompt) throws IOException {
        sshd = SshServer.setUpDefaultServer();
        sshd.setHost("127.0.0.1");
        sshd.setPort(0);
        sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(dir.resolve("hostkey.ser")));
        sshd.setPasswordAuthenticator((u, p, s) -> user.equals(u) && password.equals(p));
        sshd.setCommandFactory((channel, command) -> new ExecCommand(command, responder));
        sshd.setShellFactory(channel -> new ShellCommand(shellReplies, prompt));
        sshd.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        sshd.setFileSystemFactory(new VirtualFileSystemFactory(dir));
        sshd.start();
    }

    public int port() {
        return sshd.getPort();
    }

    @Override
    public void close() throws IOException {
        sshd.stop(true);
    }

    private abstract static class BaseCommand implements Command {
        InputStream in;
        OutputStream out;
        OutputStream err;
        ExitCallback exit;

        @Override public void setExitCallback(ExitCallback callback) { this.exit = callback; }
        @Override public void setInputStream(InputStream in) { this.in = in; }
        @Override public void setOutputStream(OutputStream out) { this.out = out; }
        @Override public void setErrorStream(OutputStream err) { this.err = err; }
        @Override public void destroy(ChannelSession channel) { }

        void write(OutputStream os, String s) throws IOException {
            os.write(s.getBytes(StandardCharsets.ISO_8859_1));
            os.flush();
        }
    }

    private static final class ExecCommand extends BaseCommand {
        private final String command;
        private final Function<String, Reply> responder;

        ExecCommand(String command, Function<String, Reply> responder) {
            this.command = command;
            this.responder = responder;
        }

        @Override
        public void start(ChannelSession channel, Environment env) {
            new Thread(() -> {
                try {
                    Reply r = responder.apply(command);
                    write(out, r.stdout());
                    if (!r.stderr().isEmpty()) {
                        write(err, r.stderr());
                    }
                    exit.onExit(r.exit());
                } catch (Exception e) {
                    exit.onExit(255, e.getMessage());
                }
            }, "test-exec").start();
        }
    }

    private static final class ShellCommand extends BaseCommand {
        private final Map<String, String> replies;
        private final String prompt;

        ShellCommand(Map<String, String> replies, String prompt) {
            this.replies = replies;
            this.prompt = prompt;
        }

        @Override
        public void start(ChannelSession channel, Environment env) {
            new Thread(() -> {
                try {
                    write(out, "Welcome to TEST\r\n" + prompt);
                    StringBuilder line = new StringBuilder();
                    int c;
                    while ((c = in.read()) >= 0) {
                        if (c == '\r' || c == '\n') {
                            if (line.length() == 0) {
                                continue;
                            }
                            String cmd = line.toString().trim();
                            line.setLength(0);
                            write(out, cmd + "\r\n");
                            if (cmd.equalsIgnoreCase("EXIT")) {
                                exit.onExit(0);
                                return;
                            }
                            write(out, replies.getOrDefault(cmd.toUpperCase(), "UNKNOWN COMMAND " + cmd) + "\r\n" + prompt);
                        } else {
                            line.append((char) c);
                        }
                    }
                    exit.onExit(0);
                } catch (IOException e) {
                    exit.onExit(1);
                }
            }, "test-shell").start();
        }
    }
}
