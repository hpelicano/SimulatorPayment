package org.simulator.ssh;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import org.simulator.config.model.SshEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.UnaryOperator;

/**
 * SSH client over JSch for the Guardian / OSS host running Base24.
 * <p>
 * One session is kept open and reused; every {@link #exec} opens a new exec channel on it.
 * Host keys are verified against a known_hosts file unless strict checking is disabled.
 */
public class SshClient implements RemoteExecutor, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SshClient.class);

    private final SshEndpoint endpoint;
    private final UnaryOperator<String> secrets;
    private final Charset charset;
    private Session session;

    public SshClient(SshEndpoint endpoint, UnaryOperator<String> secrets) {
        this(endpoint, secrets, StandardCharsets.ISO_8859_1);
    }

    public SshClient(SshEndpoint endpoint, UnaryOperator<String> secrets, Charset charset) {
        this.endpoint = endpoint;
        this.secrets = secrets != null ? secrets : UnaryOperator.identity();
        this.charset = charset;
    }

    public synchronized Session session() {
        if (session != null && session.isConnected()) {
            return session;
        }
        try {
            JSch jsch = new JSch();
            if (endpoint.getPrivateKeyPath() != null && !endpoint.getPrivateKeyPath().isBlank()) {
                String pass = secrets.apply(endpoint.getPrivateKeyPassphrase());
                if (pass != null && !pass.isEmpty()) {
                    jsch.addIdentity(endpoint.getPrivateKeyPath(), pass);
                } else {
                    jsch.addIdentity(endpoint.getPrivateKeyPath());
                }
            }
            String knownHosts = endpoint.getKnownHostsPath();
            if (knownHosts == null || knownHosts.isBlank()) {
                Path def = Path.of(System.getProperty("user.home"), ".ssh", "known_hosts");
                knownHosts = Files.exists(def) ? def.toString() : null;
            }
            if (knownHosts != null) {
                jsch.setKnownHosts(knownHosts);
            }
            Session s = jsch.getSession(endpoint.getUsername(), endpoint.getHost(), endpoint.getPort());
            String password = secrets.apply(endpoint.getPassword());
            if (password != null && !password.isEmpty()) {
                s.setPassword(password);
            }
            s.setConfig("StrictHostKeyChecking", endpoint.isStrictHostKeyChecking() ? "yes" : "no");
            s.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password");
            s.setServerAliveInterval(30_000);
            s.connect(endpoint.getConnectTimeoutMs());
            log.info("SSH connected to {}@{}:{}", endpoint.getUsername(), endpoint.getHost(), endpoint.getPort());
            session = s;
            return s;
        } catch (JSchException e) {
            throw new SshException("SSH connection to " + endpoint.getHost() + ":" + endpoint.getPort()
                    + " failed: " + e.getMessage(), e);
        }
    }

    public boolean isConnected() {
        return session != null && session.isConnected();
    }

    /** Opens the session (if needed) and returns the server version banner. */
    public String testConnection() {
        return session().getServerVersion();
    }

    @Override
    public ExecResult exec(String command) {
        return exec(command, endpoint.getCommandTimeoutMs());
    }

    public ExecResult exec(String command, long timeoutMs) {
        long start = System.currentTimeMillis();
        ChannelExec ch = null;
        try {
            ch = (ChannelExec) session().openChannel("exec");
            ch.setCommand(command);
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            ch.setErrStream(err);
            ch.setInputStream(null);
            InputStream in = ch.getInputStream();
            ch.connect(endpoint.getConnectTimeoutMs());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            long deadline = start + timeoutMs;
            while (true) {
                while (in.available() > 0) {
                    int n = in.read(buf);
                    if (n < 0) {
                        break;
                    }
                    out.write(buf, 0, n);
                }
                if (ch.isClosed()) {
                    if (in.available() > 0) {
                        continue;
                    }
                    break;
                }
                if (System.currentTimeMillis() > deadline) {
                    throw new SshException("Command timed out after " + timeoutMs + " ms: " + command);
                }
                Thread.sleep(20);
            }
            ExecResult r = new ExecResult(command, ch.getExitStatus(), out.toString(charset), err.toString(charset),
                    System.currentTimeMillis() - start);
            log.debug("exec '{}' -> exit {} in {} ms", command, r.exitCode(), r.durationMs());
            return r;
        } catch (JSchException | IOException e) {
            throw new SshException("Command failed: " + command + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SshException("Interrupted", e);
        } finally {
            if (ch != null) {
                ch.disconnect();
            }
        }
    }

    public InteractiveShell openShell(String lineTerminator) {
        try {
            ChannelShell ch = (ChannelShell) session().openChannel("shell");
            ch.setPty(true);
            ch.setPtyType("vt100", 200, 50, 0, 0);
            InteractiveShell shell = new InteractiveShell(ch, charset, lineTerminator);
            ch.connect(endpoint.getConnectTimeoutMs());
            return shell;
        } catch (JSchException | IOException e) {
            throw new SshException("Cannot open shell: " + e.getMessage(), e);
        }
    }

    public void upload(Path local, String remote) {
        sftp(c -> c.put(local.toString(), remote));
    }

    public void download(String remote, Path local) {
        sftp(c -> c.get(remote, local.toString()));
    }

    public String readRemoteFile(String remote) {
        StringBuilder sb = new StringBuilder();
        sftp(c -> {
            try (InputStream in = c.get(remote)) {
                sb.append(new String(in.readAllBytes(), charset));
            } catch (IOException e) {
                throw new SshException("Cannot read " + remote, e);
            }
        });
        return sb.toString();
    }

    private interface SftpAction {
        void run(ChannelSftp c) throws SftpException;
    }

    private void sftp(SftpAction action) {
        ChannelSftp ch = null;
        try {
            ch = (ChannelSftp) session().openChannel("sftp");
            ch.connect(endpoint.getConnectTimeoutMs());
            action.run(ch);
        } catch (JSchException | SftpException e) {
            throw new SshException("SFTP failed: " + e.getMessage(), e);
        } finally {
            if (ch != null) {
                ch.disconnect();
            }
        }
    }

    public SshEndpoint endpoint() {
        return endpoint;
    }

    @Override
    public synchronized void close() {
        if (session != null) {
            session.disconnect();
            session = null;
        }
    }
}
